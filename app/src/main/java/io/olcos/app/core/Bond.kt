package io.olcos.app.core

import android.content.Context
import android.util.Base64
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.data.ConnectionStore
import io.olcos.app.data.ServerSecrets
import io.olcos.app.data.ServerStore
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The server side of bonding (gomobile/bond) and the whole-server connection
 * it carries, as the iOS app has them (SSHRunner+Bond, ServerBundle,
 * BundleSync). The olcos-bond program runs in its own container on the host
 * network; the lanes' olcrtc exits on the server dial its public address, so
 * one port carries every lane back together. It answers only hellos signed
 * with the server's tunnel key (~/.olcrtc_key, mounted read-only).
 *
 * The app carries the Go sources (the `bond` folder of the assets, copied by
 * CI from the core snapshot) and the server builds them once in the Go image
 * the olcrtc install already uses; a newer app rebuilds when its sources
 * differ (`version`, the same digest as the iOS bondVersion). The script
 * prints OLCOS_BOND_PORT=, or OLCOS_BOND_ERROR=<reason> with exit 0, like
 * the invite server. It runs through the agent's exec when this phone is an
 * owner, else over SSH when the phone set the server up.
 *
 * The whole-server connection is one olcrtc record that joins every protocol
 * and room of the server: the primary room as its own, the primary protocol's
 * other rooms as extra rooms (`##lanes`), every other protocol's room as a
 * joined lane (`##with`), and the endpoint as `##bond`. The engine starts a
 * runtime per lane and, with the endpoint, carries every connection over all
 * of them together. The record is derived: `sync` rebuilds it from the
 * server's rooms whenever its status comes in.
 */
object ServerBond {
    const val CONTAINER = "olcos-bond"
    const val DIRECTORY = "/opt/olcos-bond"
    private const val TAG = "olcos.bond"

    /** A module of its own on the server, so the build needs no network. */
    private const val GO_MOD = "module github.com/openlibrecommunity/olcrtc-ios/gomobile\n\ngo 1.22\n"

    /** The files the server needs, relative to the module root, in digest order. The client half of the package stays in the app. */
    val SOURCE_FILES = listOf("bond/protocol.go", "bond/stripe.go", "bond/server.go", "bond/cmd/olcos-bond/main.go")

    /** One `printf | base64 -d` per chunk: a single shell argument is capped at 128 KiB on Linux. */
    private const val CHUNK = 60_000

    data class Result(val port: Int?, val error: String?)

    class Failure(message: String) : Exception(message)

    /** A new endpoint's port: high and random, like the invite server's. */
    fun randomPort(): Int = (20_000..59_999).random()

    /** The sources from the assets, in SOURCE_FILES order; throws when this build carries none. */
    fun sources(context: Context): List<Pair<String, String>> = SOURCE_FILES.map { path ->
        val text = runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
        if (text.isNullOrBlank()) throw Failure(context.getString(R.string.install_error_installer_missing))
        path to text
    }

    /** What the server should run: a digest of the sources, the one the iOS app computes for the same files. */
    fun version(sources: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GO_MOD.toByteArray(Charsets.UTF_8))
        for ((path, text) in sources) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(text.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * The iOS bondEnsureScript: builds the endpoint when its version differs,
     * starts it on `port` when it is not running (an existing one keeps its
     * own), and waits for it to listen.
     */
    fun ensureScript(port: Int, version: String, sources: List<Pair<String, String>>, dns: String): String {
        val image = Maintenance.GO_IMAGE
        val lines = mutableListOf(
            "set -e",
            "D=$DIRECTORY",
            "KEY=\"\$HOME/.olcrtc_key\"",
            "if [ ! -s \"\$KEY\" ]; then echo OLCOS_BOND_ERROR=no_key; exit 0; fi",
            "mkdir -p \"\$D\"",
            "if [ ! -x \"\$D/olcos-bond\" ] || [ \"\$(cat \"\$D/version\" 2>/dev/null)\" != \"$version\" ]; then",
            "  rm -rf \"\$D/src\"",
            "  mkdir -p \"\$D/src/bond/cmd/olcos-bond\" \"\$D/cache\"",
        )
        lines += writeLines(GO_MOD, "\"\$D/src/go.mod\"")
        for ((path, text) in sources) lines += writeLines(text, "\"\$D/src/$path\"")
        lines += listOf(
            "  podman rm -f olcos-bond-build >/dev/null 2>&1 || true",
            "  if ! podman run --rm --name olcos-bond-build --network none -v \"\$D:/work:Z\" -w /work/src " +
                "-e GOCACHE=/work/cache -e CGO_ENABLED=0 -e GOTOOLCHAIN=local $image " +
                "go build -trimpath -o /work/olcos-bond.new ./bond/cmd/olcos-bond >/tmp/olcos-bond-build.log 2>&1; then",
            "    echo \"OLCOS_BOND_ERROR=build: \$(tail -n 1 /tmp/olcos-bond-build.log | tr -d '\\r')\"; exit 0",
            "  fi",
            "  mv -f \"\$D/olcos-bond.new\" \"\$D/olcos-bond\"",
            "  echo \"$version\" > \"\$D/version\"",
            "  podman rm -f $CONTAINER >/dev/null 2>&1 || true",
            "fi",
            "PORT=\$(cat \"\$D/port\" 2>/dev/null || true)",
            "[ -n \"\$PORT\" ] || PORT=$port",
            "if ! podman container exists $CONTAINER 2>/dev/null; then",
            "  if ! podman run -d --name $CONTAINER --restart=always --network host " +
                "-v \"\$D:/app:ro,Z\" -v \"\$KEY:/key:ro,Z\" $image " +
                "/app/olcos-bond -listen \":\$PORT\" -key /key -dns \"${Maintenance.shellSafe(dns)}\" >/dev/null 2>/tmp/olcos-bond.err; then",
            "    echo \"OLCOS_BOND_ERROR=\$(tail -n 1 /tmp/olcos-bond.err | tr -d '\\r')\"; exit 0",
            "  fi",
            "  echo \"\$PORT\" > \"\$D/port\"",
            "else",
            "  podman start $CONTAINER >/dev/null 2>&1 || true",
            "fi",
            "for i in 1 2 3 4 5 6 7 8 9 10; do",
            "  if podman logs $CONTAINER 2>&1 | grep -q 'bond: listening'; then echo \"OLCOS_BOND_PORT=\$PORT\"; exit 0; fi",
            "  sleep 1",
            "done",
            "if podman logs $CONTAINER 2>&1 | grep -qi 'address already in use'; then",
            "  podman rm -f $CONTAINER >/dev/null 2>&1 || true",
            "  rm -f \"\$D/port\"",
            "  echo OLCOS_BOND_ERROR=port_busy; exit 0",
            "fi",
            "echo \"OLCOS_BOND_ERROR=\$(podman logs --tail 1 $CONTAINER 2>&1 | tr -d '\\r')\"",
        )
        return lines.joinToString("\n")
    }

    /** Uninstall and deep uninstall take the endpoint with the protocols: it mounts the key they delete (the iOS bondUninstallLines). */
    val UNINSTALL_LINES: String = listOf(
        "podman rm -f $CONTAINER >/dev/null 2>&1 || true",
        "rm -rf $DIRECTORY 2>/dev/null || true",
    ).joinToString("\n")

    fun parse(output: String): Result {
        var port: Int? = null
        var error: String? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("OLCOS_BOND_PORT=") -> port = line.removePrefix("OLCOS_BOND_PORT=").trim().toIntOrNull()
                line.startsWith("OLCOS_BOND_ERROR=") -> error = line.removePrefix("OLCOS_BOND_ERROR=").trim().ifEmpty { "unknown" }
            }
        }
        return Result(port, error)
    }

    /** `printf '%s' '<base64>' | base64 -d > <target>`, in pieces a shell argument can hold. */
    private fun writeLines(text: String, target: String): List<String> {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return b64.chunked(CHUNK).mapIndexed { i, chunk ->
            "  printf '%s' '$chunk' | base64 -d " + (if (i == 0) ">" else ">>") + " $target"
        }
    }

    // MARK: Where a script runs

    /** The agent's exec (this phone is an owner), else one SSH connection per script (the phone set the server up). */
    sealed class Runner {
        abstract suspend fun exec(script: String, timeoutSeconds: Int): Pair<Int, String>

        class Agent(private val client: AgentClient) : Runner() {
            override suspend fun exec(script: String, timeoutSeconds: Int): Pair<Int, String> = client.exec(script, timeoutSeconds)
        }

        class SSH(private val context: Context, private val server: ServerRecord, private val secret: ServerSecrets.Secret) : Runner() {
            override suspend fun exec(script: String, timeoutSeconds: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
                val ssh = SSHClient.connect(context, server.host, server.sshPort, server.sshUser.orEmpty(), secret)
                try {
                    ssh.exec(script, timeoutSeconds)
                } finally {
                    ssh.close()
                }
            }
        }
    }

    /** The agent when this phone owns the server, else SSH; throws when the phone has neither. */
    fun runner(context: Context, server: ServerRecord): Runner {
        if (server.isOwner) return Runner.Agent(AgentClient(server, AgentKey.loadOrCreate()))
        val user = server.sshUser?.takeIf { it.isNotEmpty() }
        val secret = AppGraph.secrets.secret(server.id)
        if (user != null && secret != null) return Runner.SSH(context, server, secret)
        throw Failure(context.getString(R.string.install_error_no_ssh))
    }

    /**
     * Makes sure the server runs the endpoint of this app's version; its port
     * and version. A busy port is retried with another one, as on iOS.
     */
    suspend fun ensure(context: Context, server: ServerRecord, runner: Runner): Pair<Int, String> {
        val sources = sources(context)
        val version = version(sources)
        val dns = AppGraph.settings.dns.value
        var port = server.bondPort ?: randomPort()
        repeat(3) { attempt ->
            val (_, output) = runner.exec(ensureScript(port, version, sources, dns), 600)
            val result = parse(output)
            if (result.error == "port_busy" && attempt < 2) {
                port = randomPort()
                return@repeat
            }
            result.error?.let { throw Failure(context.getString(R.string.bond_error_fmt, it)) }
            val bound = result.port ?: throw Failure(context.getString(R.string.bond_error_fmt,
                output.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" ").take(300).ifEmpty { "no port" }))
            return bound to version
        }
        throw Failure(context.getString(R.string.bond_error_fmt, "port_busy"))
    }

    // MARK: The whole-server connection

    /** "host:port" of the server's endpoint (IPv6 in brackets), the form `##bond` carries; null without one. */
    fun endpoint(server: ServerRecord): String? {
        val port = server.bondPort?.takeIf { it in 1..65535 } ?: return null
        val host = if (server.host.contains(":")) "[${server.host}]" else server.host
        return "$host:$port"
    }

    /**
     * PURE: the whole-server link for a server's rooms (the primary first):
     * the primary room as the main lane, the primary protocol's other rooms as
     * extra rooms, every other protocol's room as a joined lane, all under the
     * server's one key. Null with fewer than two rooms present.
     */
    fun wholeServerLink(rooms: List<ServerStatus.Room>, key: String, endpoint: String?, template: OlcrtcLink? = null): OlcrtcLink? {
        val present = rooms.filter { !it.isMissing && it.provider.isNotEmpty() && it.room.isNotEmpty() }
        val primary = present.firstOrNull { it.isPrimary } ?: present.firstOrNull() ?: return null
        val own = mutableListOf<String>()
        val with = mutableListOf<Lane>()
        for (room in present) {
            if (room.provider == primary.provider && room.transport == primary.transport) {
                if (room.room != primary.room && room.room !in own) own += room.room
            } else {
                val lane = Lane(room.provider, room.transport, room.room)
                if (lane !in with) with += lane
            }
        }
        if (own.isEmpty() && with.isEmpty()) return null
        return OlcrtcLink(
            carrier = primary.provider, transport = primary.transport, roomID = primary.room, key = key,
            vp8FPS = template?.vp8FPS, vp8Batch = template?.vp8Batch,
            extraRooms = own, with = with, bond = endpoint,
        )
    }

    /** The server's own connection of its primary room, else any of its protocol records: where its key and tuning come from. */
    fun primaryLink(server: ServerRecord, rooms: List<ServerStatus.Room>, store: ConnectionStore): OlcrtcLink? {
        val mine = Invites.records(server, store).filter { it.id != server.bundleRecordID }.mapNotNull { it.olcrtc }.filter { it.key.isNotEmpty() }
        val primary = rooms.firstOrNull { it.isPrimary }
        val ofPrimary = primary?.let { p -> mine.firstOrNull { it.carrier == p.provider && it.roomID == p.room } }
        return ofPrimary ?: mine.firstOrNull { !it.isBundle } ?: mine.firstOrNull()
    }

    /**
     * Keeps the whole-server record in step with the server's rooms: made
     * when `make` asks, corrected when a room changed or the endpoint came.
     * The server's protocol records with several rooms take the endpoint
     * too (the iOS syncProtocolBonds). Returns the record, or null without
     * one. Nothing is removed here: fewer than two rooms leave the record as
     * it is, and Remove on the server page takes it away.
     */
    fun sync(context: Context, server: ServerRecord, rooms: List<ServerStatus.Room>, store: ConnectionStore, servers: ServerStore, make: Boolean): ConnectionRecord? {
        val existing = server.bundleRecordID?.let { id -> store.records.value.firstOrNull { it.id == id } }
        if (existing == null && !make) return null
        val endpoint = endpoint(server)
        val base = primaryLink(server, rooms, store)
        val key = base?.key ?: existing?.olcrtc?.key?.takeIf { it.isNotEmpty() }
            ?: throw Failure(context.getString(R.string.bond_error_no_key))
        syncProtocolBonds(server, endpoint, store)
        val link = wholeServerLink(rooms, key, endpoint, base ?: existing?.olcrtc) ?: return existing
        if (existing != null) {
            if (existing.olcrtc == link) return existing
            val updated = existing.copy(details = Details.Olcrtc(link))
            store.update(updated)
            return updated
        }
        val record = store.add(Details.Olcrtc(link), server.name + " · " + context.getString(R.string.bundle_name))
        servers.update(server.id) { it.copy(bundleRecordID = record.id, recordIDs = it.recordIDs + record.id) }
        AppLog.i(TAG, "whole-server connection of ${server.name}: ${link.lanes.size} lanes" + if (endpoint != null) ", bonded" else "")
        return record
    }

    /** A protocol record of the server with several rooms bonds them over the endpoint too; one room needs none. */
    private fun syncProtocolBonds(server: ServerRecord, endpoint: String?, store: ConnectionStore) {
        for (record in store.records.value) {
            if (record.id !in server.recordIDs || record.id == server.bundleRecordID) continue
            val link = record.olcrtc ?: continue
            if (link.isBundle) continue
            val wanted = if (link.lanes.size > 1) endpoint else null
            if (link.bond == wanted) continue
            store.update(record.copy(details = Details.Olcrtc(link.copy(bond = wanted))))
        }
    }

    /**
     * The whole-server connection for a server this phone manages: the
     * endpoint of this app's version on the server first, then the record
     * from the rooms (added, or brought up to date), linked into the server.
     */
    suspend fun create(context: Context, server: ServerRecord, rooms: List<ServerStatus.Room>): ConnectionRecord {
        val store = AppGraph.store
        val servers = AppGraph.servers
        if (primaryLink(server, rooms, store) == null && server.bundleRecordID == null) {
            throw Failure(context.getString(R.string.bond_error_no_key))
        }
        val runner = runner(context, server)
        val (port, version) = ensure(context, server, runner)
        servers.update(server.id) { it.copy(bondPort = port, bondVersion = version) }
        AppLog.i(TAG, "bond endpoint on ${server.name}: port $port")
        val fresh = servers.byId(server.id) ?: server.copy(bondPort = port, bondVersion = version)
        return sync(context, fresh, rooms, store, servers, make = true)
            ?: throw Failure(context.getString(R.string.bond_error_fmt, "fewer than two rooms"))
    }

    /**
     * Stops the endpoint on the server and forgets the whole-server
     * connection; the protocol records ride their rooms in turn again.
     */
    suspend fun remove(context: Context, server: ServerRecord) {
        val store = AppGraph.store
        val servers = AppGraph.servers
        val (_, output) = runner(context, server).exec(UNINSTALL_LINES, 120)
        AppLog.i(TAG, "bond endpoint on ${server.name} removed" + output.lines().lastOrNull { it.isNotBlank() }?.let { ": $it" }.orEmpty())
        val bundleID = server.bundleRecordID
        if (bundleID != null) store.remove(bundleID)
        servers.update(server.id) {
            it.copy(bondPort = null, bondVersion = null, bundleRecordID = null, recordIDs = it.recordIDs.filterNot { id -> id == bundleID })
        }
        syncProtocolBonds(servers.byId(server.id) ?: server.copy(bundleRecordID = null), null, store)
    }
}
