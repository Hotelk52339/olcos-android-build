package io.olcos.app.core

import android.content.Context
import io.olcos.app.model.OlcrtcLink
import java.net.URI

/**
 * The owner's maintenance on a server, run through the agent's `exec`: the
 * same scripts the iOS app runs over SSH (scripts/add-carrier.sh and
 * rotate-key.sh ride in the assets; the room change and the protocol removal
 * are the iOS builders, line for line). Every value that reaches a script
 * goes through `shellSafe` first, as on iOS.
 */
object Maintenance {
    const val DEFAULT_JITSI_BASE = "https://meet.egovm.ru"

    /** The protocols a server runs, as the add-protocol dialog and the server install offer them. */
    val CARRIERS = listOf("jitsi", "telemost", "vkcalls", "salutejazz", "wbstream")
    val TRANSPORTS = listOf("datachannel", "vp8channel", "seichannel")
    /** The services whose room the server cannot make by itself: the phone brings one. */
    val ROOM_REQUIRED = setOf("telemost", "vkcalls", "salutejazz", "wbstream")

    /** The core this app speaks: the pinned commit, the fork that carries it, the image that builds it (AppConstants on iOS). */
    const val CORE_PIN = "403edd409011dd371796260196ecd17630df0fdd"
    const val CORE_REPO = "https://github.com/ghostlane-project/olcrtc.git"
    const val GO_IMAGE = "docker.io/library/golang:1.26-alpine3.22"
    private const val PATCH_ASSET = "scripts/olcrtc-core.patch"
    private const val REMOTE_PATCH = "/tmp/olcos-android-core.patch"
    private const val REMOTE_UPDATE = "/tmp/olcos-update.sh"
    private const val REMOTE_LOG = "/tmp/olcos-update.log"
    private const val REMOTE_EXIT = "/tmp/olcos-update.exit"

    /** The olcOS core patch series the app carries (CI puts it in the assets), or null without one. */
    fun corePatch(context: Context): String? =
        runCatching { asset(context, PATCH_ASSET) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** SHA-256 of the series, "" without one: what a server records once it applied it. */
    fun corePatchID(context: Context): String = corePatch(context)?.let { sha256(it) } ?: ""

    /** The commit, followed by `+<series id>` when a patch series runs on top of it. */
    fun coreIdentity(commit: String, series: String): String = if (series.isEmpty()) commit else "$commit+$series"

    fun expectedCoreIdentity(context: Context): String = coreIdentity(CORE_PIN, corePatchID(context))

    /** "403edd4 + 884eea8d": one line for an identity. */
    fun shortCoreIdentity(identity: String): String {
        val parts = identity.split('+', limit = 2)
        val commit = parts[0].take(7)
        return if (parts.size == 2) commit + " + " + parts[1].take(8) else commit
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * The update the iOS app runs over SSH (SSHRunner.updateScript): the deploy
     * dir is reset to the pinned commit of the fork, the series applied when
     * the app carries one, the binary rebuilt in a builder container with the
     * shared caches, every container recreated with `--restart always` where
     * it still ran with `unless-stopped`, then the primary and the running
     * siblings restarted.
     */
    fun updateScript(context: Context, container: String): String {
        val target = shellSafe(container)
        val patchID = corePatchID(context)
        return listOf(
            "set -e",
            "if podman ps -a --format '{{.Names}}' | grep -q '^$target\$' 2>/dev/null; then",
            "    CNAME=\"$target\"",
            "else",
            "    CNAME=\$(podman ps -aq --filter 'name=olcrtc-server-' --format '{{.Names}}' | head -1)",
            "fi",
            "if [ -z \"\$CNAME\" ]; then echo \"ERROR: no olcrtc container found\" >&2; exit 1; fi",
            "echo \"UPDATE: container=\$CNAME\"",
            "echo \"OLCRTC_UPDATE_CONTAINER=\$CNAME\"",
            "DEPLOY_DIR=\$(podman inspect --format '{{range .Mounts}}{{if eq .Type \"bind\"}}{{.Source}}{{break}}{{end}}{{end}}' \"\$CNAME\")",
            "if [ -z \"\$DEPLOY_DIR\" ] || [ ! -d \"\$DEPLOY_DIR/.git\" ]; then",
            "    echo \"ERROR: git deploy dir not found for \$CNAME (dir: \${DEPLOY_DIR:-unknown})\" >&2; exit 1",
            "fi",
            "git -C \"\$DEPLOY_DIR\" reset -q --hard",
            "git -C \"\$DEPLOY_DIR\" remote set-url origin $CORE_REPO",
            "git -C \"\$DEPLOY_DIR\" fetch --depth 1 origin $CORE_PIN",
            "git -C \"\$DEPLOY_DIR\" checkout -q FETCH_HEAD",
            "if [ -s $REMOTE_PATCH ]; then",
            "    git -C \"\$DEPLOY_DIR\" apply $REMOTE_PATCH || { echo \"ERROR: the olcOS core patch series does not apply to the pinned core\" >&2; exit 1; }",
            "    printf '%s\\n' '$patchID' > \"\$DEPLOY_DIR/.olcos-core-patch\"",
            "else",
            "    rm -f \"\$DEPLOY_DIR/.olcos-core-patch\"",
            "fi",
            "CACHE_DIR=\"\$HOME/.cache/olcrtc\"",
            "mkdir -p \"\$CACHE_DIR/gomod\" \"\$CACHE_DIR/gobuild\"",
            "podman run --rm --network host -v \"\$DEPLOY_DIR\":/app:Z -v \"\$CACHE_DIR/gomod\":/go/pkg/mod:Z -v \"\$CACHE_DIR/gobuild\":/root/.cache/go-build:Z -w /app $GO_IMAGE sh -c \"go mod download && go build -trimpath -ldflags='-s -w' -o olcrtc ./cmd/olcrtc\"",
            "recreate_always() {",
            "    c=\"\$1\"",
            "    policy=\$(podman inspect --format '{{.HostConfig.RestartPolicy.Name}}' \"\$c\" 2>/dev/null) || return 0",
            "    [ \"\$policy\" = \"always\" ] && return 0",
            "    cmd=\$(podman inspect --format '{{index .Config.Cmd 2}}' \"\$c\" 2>/dev/null) || return 0",
            "    case \"\$cmd\" in",
            "        \"./olcrtc server\"*.yaml) ;;",
            "        *) echo \"WARN: \$c runs '\$cmd'; its restart policy is left as is\"; return 0 ;;",
            "    esac",
            "    dir=\$(podman inspect --format '{{range .Mounts}}{{if eq .Type \"bind\"}}{{.Source}}{{break}}{{end}}{{end}}' \"\$c\") || return 0",
            "    img=\$(podman inspect --format '{{.ImageName}}' \"\$c\") || return 0",
            "    running=\$(podman inspect --format '{{.State.Running}}' \"\$c\") || return 0",
            "    aside=\"olcos-recreate-\$\$\"",
            "    podman stop -t 3 \"\$c\" >/dev/null 2>&1 || true",
            "    podman rename \"\$c\" \"\$aside\" || return 0",
            "    if podman create --name \"\$c\" --network host --restart always -v \"\$dir\":/app:Z -w /app \"\$img\" sh -c \"\$cmd\" >/dev/null; then",
            "        podman rm -f \"\$aside\" >/dev/null || true",
            "        echo \"RECREATED: \$c --restart always\"",
            "    else",
            "        podman rename \"\$aside\" \"\$c\" || true",
            "        echo \"WARN: \$c could not be recreated with --restart always; kept as it was\"",
            "    fi",
            "    if [ \"\$running\" = \"true\" ]; then podman start \"\$c\" >/dev/null || true; fi",
            "}",
            "for name in \"\$CNAME\" \$(podman ps -a --format '{{.Names}}' | grep \"^\${CNAME}-\" || true); do",
            "    recreate_always \"\$name\"",
            "done",
            "systemctl enable podman-restart.service >/dev/null 2>&1 || true",
            "podman restart \"\$CNAME\"",
            "for c in \$(podman ps --format '{{.Names}}' | grep \"^\${CNAME}-\" 2>/dev/null); do",
            "    podman restart \"\$c\" >/dev/null 2>&1 || true",
            "done",
            "echo \"OLCRTC_UPDATED=ok\"",
        ).joinToString("\n")
    }

    /** Puts the update script and the series on the server and starts the update detached; the log and exit code land in files. */
    fun updateLaunchScript(context: Context, container: String): String {
        val script = android.util.Base64.encodeToString(updateScript(context, container).toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val patch = corePatch(context)?.let { android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) }
        return listOf(
            "printf '%s' '$script' | base64 -d > $REMOTE_UPDATE",
            if (patch != null) "printf '%s' '$patch' | base64 -d > $REMOTE_PATCH" else "rm -f $REMOTE_PATCH",
            "rm -f $REMOTE_EXIT $REMOTE_LOG",
            "nohup sh -c 'bash $REMOTE_UPDATE; echo \$? > $REMOTE_EXIT' > $REMOTE_LOG 2>&1 &",
            "echo OLCOS_UPDATE_STARTED=ok",
        ).joinToString("\n")
    }

    /** The tail of the update's log, and its exit code once it has one. */
    const val UPDATE_POLL_SCRIPT = "tail -c 6000 $REMOTE_LOG 2>/dev/null; if [ -f $REMOTE_EXIT ]; then echo \"OLCOS_UPDATE_EXIT=\$(cat $REMOTE_EXIT)\"; fi"

    /**
     * Runs the update through the agent and follows its log until it ends
     * (at most half an hour). `onProgress` gets the log's tail every few seconds.
     */
    suspend fun runUpdate(client: AgentClient, context: Context, container: String, onProgress: (String) -> Unit): String {
        val (startExit, startOut) = client.exec(updateLaunchScript(context, container), 60)
        if (startExit != 0 || extract(startOut, "OLCOS_UPDATE_STARTED") != "ok") throw Failure(startOut.lines().lastOrNull { it.isNotBlank() } ?: "could not start")
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(5_000)
            val (_, out) = client.exec(UPDATE_POLL_SCRIPT, 30)
            val exit = extract(out, "OLCOS_UPDATE_EXIT")
            val log = out.lines().filterNot { it.startsWith("OLCOS_UPDATE_EXIT=") }.joinToString("\n")
            onProgress(log)
            if (exit != null) {
                if (exit == "0" && extract(log, "OLCRTC_UPDATED") == "ok") return extract(log, "OLCRTC_UPDATE_CONTAINER") ?: container
                val error = log.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
                    ?: log.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" ").take(300).ifEmpty { "exit $exit" }
                throw Failure(error)
            }
        }
        throw Failure("the update did not finish in half an hour")
    }

    class Failure(message: String) : Exception(message)

    /** What a script reported: the primary's new link, its container, and the siblings re-keyed with it. */
    data class Result(
        val uri: OlcrtcLink?,
        val container: String?,
        val siblings: List<Pair<String, OlcrtcLink>>,
        val output: String,
    )

    /** iOS SSHRunner.shellSafe: printable ASCII without the shell's own characters. */
    fun shellSafe(s: String): String = s.filter { c -> c.code in 0x21..0x7E && c !in "\"\\`$';&|<>()" }

    fun extract(output: String, key: String): String? =
        output.lines().map { it.trim() }.lastOrNull { it.startsWith("$key=") }?.substring(key.length + 1)

    /** A new random room on the same Jitsi instance as `near` (a room URL), like the iOS freshJitsiRoom. */
    fun freshJitsiRoom(near: String): String {
        val base = runCatching { URI(near) }.getOrNull()?.let { u ->
            val host = u.host ?: return@let null
            (u.scheme ?: "https") + "://" + host + (if (u.port > 0) ":" + u.port else "")
        } ?: DEFAULT_JITSI_BASE
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val name = (1..8).map { alphabet.random() }.joinToString("")
        return base.trimEnd('/') + "/olcrtc-" + name
    }

    /** Rewrites the container's own yaml (provider, room, transport, token) and restarts it. */
    fun reconfigureScript(container: String, carrier: String, transport: String, roomID: String, wbToken: String = ""): String {
        val safeCname = shellSafe(container)
        val safeCarrier = shellSafe(carrier)
        val safeTransport = shellSafe(transport)
        val safeRoom = shellSafe(roomID)
        val safeToken = shellSafe(wbToken)
        val tokenInsert = if (carrier == "wbstream" && safeToken.isNotEmpty()) {
            "    -e 's|^  provider: .*|&\\n  token: \"$safeToken\"|' \\\n"
        } else {
            ""
        }
        return listOf(
            "set -e",
            "CNAME=\"$safeCname\"",
            "if ! podman ps -a --format '{{.Names}}' | grep -q \"^\${CNAME}\$\" 2>/dev/null; then",
            "    echo \"ERROR: container \${CNAME} not found\" >&2; exit 1",
            "fi",
            "DEPLOY_DIR=\$(podman inspect --format '{{range .Mounts}}{{if eq .Type \"bind\"}}{{.Source}}{{break}}{{end}}{{end}}' \"\${CNAME}\")",
            // The container's own command names the yaml it reads: a sibling's, not the primary's.
            "CONFIG_NAME=\$(podman inspect --format '{{range .Config.Cmd}}{{.}} {{end}}' \"\${CNAME}\" | grep -oE 'server[A-Za-z0-9_.-]*[.]yaml' | tail -1)",
            "if [ -z \"\$CONFIG_NAME\" ]; then CONFIG_NAME=\"server.yaml\"; fi",
            "CONFIG=\"\${DEPLOY_DIR}/\${CONFIG_NAME}\"",
            "if [ -z \"\$DEPLOY_DIR\" ] || [ ! -f \"\$CONFIG\" ]; then",
            "    echo \"ERROR: \${CONFIG_NAME} not found for \${CNAME} (deploy dir: \${DEPLOY_DIR:-unknown})\" >&2; exit 1",
            "fi",
            "echo \"RECONFIGURE: updating \${CONFIG}…\"",
            "sed -i \\",
            "    -e '/^  token: /d' \\",
            "    -e 's|^  provider: .*|  provider: \"$safeCarrier\"|' \\",
            tokenInsert + "    -e 's|^  id: .*|  id: \"$safeRoom\"|' \\",
            "    -e 's|^  transport: .*|  transport: \"$safeTransport\"|' \\",
            "    \"\$CONFIG\"",
            "podman restart \"\${CNAME}\"",
            "KEY=\$(cat ~/.olcrtc_key 2>/dev/null | tr -d '[:space:]')",
            "[ -n \"\$KEY\" ] || KEY=\$(sed -n 's/^  key: \"\\(.*\\)\"/\\1/p' \"\$CONFIG\")",
            "echo \"OLCRTC_URI=olcrtc://$safeCarrier?$safeTransport@$safeRoom#\${KEY}\"",
            "echo \"OLCRTC_RECONFIGURED=ok\"",
        ).joinToString("\n")
    }

    /** add-carrier.sh with the iOS env contract (addCarrierEnv); no build, the binary is reused. */
    fun addCarrierScript(
        context: Context, base: String, carrier: String, transport: String, roomID: String,
        jitsiURL: String, dns: String, vp8FPS: Int, vp8Batch: Int, lane: Int? = null, wbToken: String = "",
    ): String {
        val env = mutableListOf(
            "OLCRTC_BASE_CONTAINER=" + shellSafe(base),
            "OLCRTC_CARRIER=" + shellSafe(carrier),
            "OLCRTC_TRANSPORT=" + shellSafe(transport),
            "OLCRTC_DNS=" + shellSafe(dns),
            "OLCRTC_CONFIG_NAME=auto-provisioned",
        )
        if (roomID.isNotEmpty()) env += "OLCRTC_ROOM_ID=" + shellSafe(roomID)
        if (carrier == "jitsi") env += "OLCRTC_JITSI_URL=" + shellSafe(jitsiURL)
        if (carrier == "wbstream" && wbToken.isNotEmpty()) env += "OLCRTC_WB_TOKEN=" + shellSafe(wbToken)
        if (transport == "vp8channel") {
            env += "OLCRTC_VP8_FPS=$vp8FPS"
            env += "OLCRTC_VP8_BATCH=$vp8Batch"
        }
        if (transport == "seichannel") {
            env += listOf("OLCRTC_SEI_FPS=30", "OLCRTC_SEI_BATCH=10", "OLCRTC_SEI_FRAG=1200", "OLCRTC_SEI_ACK=1")
        }
        if (lane != null && lane in 2..8) env += "OLCRTC_LANE=$lane"
        return exported(env) + asset(context, "scripts/add-carrier.sh")
    }

    /** rotate-key.sh: a new key for the primary and every sibling, each restarted. */
    fun rotateKeyScript(context: Context, base: String): String =
        exported(listOf("OLCRTC_CONTAINER=" + shellSafe(base), "OLCRTC_CONFIG_NAME=auto-provisioned")) +
            asset(context, "scripts/rotate-key.sh")

    /** Removes a sibling protocol (never the primary): its container and its yaml. */
    fun removeCarrierScript(base: String, container: String, configFile: String): String {
        val safeCname = shellSafe(base)
        val safeTarget = shellSafe(container)
        val safeFile = shellSafe(configFile)
        return listOf(
            "CNAME=\"$safeCname\"",
            "TARGET=\"$safeTarget\"",
            "FILE=\"$safeFile\"",
            "if [ \"\$TARGET\" = \"\$CNAME\" ] || [ \"\$FILE\" = \"server.yaml\" ]; then",
            "    echo \"ERROR: refusing to remove the primary protocol (use uninstall)\" >&2; exit 1",
            "fi",
            "DEPLOY_DIR=\$(podman inspect --format '{{range .Mounts}}{{if eq .Type \"bind\"}}{{.Source}}{{break}}{{end}}{{end}}' \"\${CNAME}\" 2>/dev/null)",
            "podman rm -f \"\${TARGET}\" 2>/dev/null || true",
            "[ -n \"\$DEPLOY_DIR\" ] && rm -f \"\${DEPLOY_DIR}/\${FILE}\" 2>/dev/null || true",
            "echo \"OLCRTC_CARRIER_REMOVED=ok\"",
        ).joinToString("\n")
    }

    /**
     * Runs a script through the agent; throws with the script's own `ERROR:`
     * line or its tail. Success is exit 0 plus the marker when one is named,
     * else a link in the output (rotate-key.sh prints no marker).
     */
    suspend fun run(client: AgentClient, script: String, okMarker: String?, timeoutSeconds: Int = 300): Result {
        val (exit, output) = client.exec(script, timeoutSeconds)
        AppLog.i("olcos.maintenance", "exec exit $exit: " + output.lines().takeLast(3).joinToString(" | "))
        val ok = if (okMarker != null) extract(output, okMarker) == "ok" else extract(output, "OLCRTC_URI") != null
        if (exit != 0 || !ok) {
            val error = output.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
                ?: output.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" ").take(300).ifEmpty { "exit $exit" }
            throw Failure(error)
        }
        return parse(output)
    }

    fun parse(output: String): Result {
        val uri = extract(output, "OLCRTC_URI")?.let { OlcrtcLink.parse(it) }
        val container = extract(output, "OLCRTC_CONTAINER")
        val siblings = output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (!line.startsWith("OLCRTC_SIBLING_URI=")) return@mapNotNull null
            val value = line.substring("OLCRTC_SIBLING_URI=".length)
            val bar = value.indexOf('|')
            if (bar <= 0) return@mapNotNull null
            OlcrtcLink.parse(value.substring(bar + 1))?.let { value.substring(0, bar) to it }
        }
        return Result(uri, container, siblings, output)
    }

    private fun exported(env: List<String>): String = env.joinToString("") { "export $it\n" }

    private fun asset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
