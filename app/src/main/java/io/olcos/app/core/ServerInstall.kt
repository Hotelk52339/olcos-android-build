package io.olcos.app.core

import android.content.Context
import android.util.Base64
import android.widget.Toast
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.data.ServerSecrets
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.OlcrtcLink
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Installs the person's own server on their own VPS over SSH, the way the
 * iOS app does (SSHRunner.install + agentEnsureScript) and install.sh does
 * by hand: srv.sh and the core patch go up and run under nohup, the log is
 * followed until the exit file appears, then the agent is built in the Go
 * image with this phone as its owner, the status scripts are stored, the
 * systemd unit started. What comes back is saved as a connection and a
 * managed server; the SSH login stays in ServerSecrets for the reinstall.
 *
 * The work runs in AppGraph.scope and reports through `state`, so the screen
 * can go away and come back; a finished install saves without it.
 */
object ServerInstall {
    private const val TAG = "olcos.install"

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val step: String = "",
        val log: String = "",
        val error: String? = null,
        val serverID: String? = null,
        val agentPort: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    data class Options(
        val name: String,
        val host: String,
        val sshPort: Int,
        val user: String,
        val secret: ServerSecrets.Secret,
        val carrier: String,
        val transport: String,
        val roomID: String,
        val jitsiURL: String,
        val wbToken: String = "",
    )

    data class Outcome(val server: ServerRecord, val record: ConnectionRecord)

    class Failure(message: String) : Exception(message)

    /** The endpoint facts the agent prints (OLCOS_AGENT_*). */
    data class AgentFacts(val port: Int, val certSHA256: String, val prefix: String, val deviceID: String)

    /** Client-side paths only, the iOS RemotePaths; srv.sh never reads them. */
    private object Remote {
        const val SCRIPT = "/tmp/olcrtc-ios-srv.sh"
        const val CORE_PATCH = "/tmp/olcrtc-ios-core.patch"
        const val LOG = "/tmp/olcrtc-install.log"
        const val EXIT = "/tmp/olcrtc-install.exit"
        const val STAGE = "/tmp/olcos-agent-stage"
    }

    const val AGENT_DIR = "/opt/olcos-agent"
    private const val AGENT_GO_MOD = "module github.com/openlibrecommunity/olcrtc-ios/gomobile\n\ngo 1.22\n"
    private const val POLL_MS = 5_000L
    private const val INSTALL_LIMIT_MS = 25 * 60_000L
    private const val POLL = "tail -n 40 ${Remote.LOG} 2>/dev/null; if [ -f ${Remote.EXIT} ]; then echo \"OLCOS_INSTALL_EXIT=\$(cat ${Remote.EXIT})\"; fi"
    /** One `printf | base64 -d` per chunk: a single shell argument is capped at 128 KiB on Linux. */
    private const val CHUNK = 60_000

    private var lastOptions: Options? = null

    /** Starts the install in the app's scope; a run in progress is left alone. */
    fun start(context: Context, options: Options) {
        if (_state.value.phase == Phase.RUNNING) return
        lastOptions = options
        val app = context.applicationContext
        AppGraph.scope.launch {
            _state.value = State(phase = Phase.RUNNING, step = app.getString(R.string.install_step_connect))
            AppLog.i(TAG, "install on ${options.host}: ${options.carrier}/${options.transport}")
            try {
                val outcome = withContext(Dispatchers.IO) { install(app, options) }
                _state.value = State(phase = Phase.DONE, serverID = outcome.server.id, agentPort = outcome.server.port)
                AppLog.i(TAG, "server installed on ${options.host}: ${outcome.server.baseContainer}, agent port ${outcome.server.port}")
                Toast.makeText(app, app.getString(R.string.add_server_done_fmt, outcome.server.name), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val reason = UserFacing.describe(app, e)
                AppLog.w(TAG, "install on ${options.host} failed: $reason")
                _state.value = _state.value.copy(phase = Phase.FAILED, error = reason)
                Toast.makeText(app, app.getString(R.string.add_server_failed_fmt, reason), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runs the last options again; false when there are none (the process was restarted). */
    fun retry(context: Context): Boolean {
        val options = lastOptions ?: return false
        start(context, options)
        return true
    }

    /** Back to the form; a run in progress stays. */
    fun reset() {
        if (_state.value.phase != Phase.RUNNING) _state.value = State()
    }

    private fun step(app: Context, id: Int) {
        _state.value = _state.value.copy(step = app.getString(id))
    }

    private suspend fun install(app: Context, o: Options): Outcome {
        val srv = asset(app, "scripts/srv.sh") ?: throw Failure(app.getString(R.string.install_error_installer_missing))
        val sources = agentSources(app)
        val status = statusScripts(app)
        var ssh = SSHClient.connect(app, o.host, o.sshPort, o.user, o.secret)
        try {
            val (rc, who) = ssh.exec("id -u", 20)
            if (rc != 0 || who.trim() != "0") throw Failure(app.getString(R.string.install_error_not_root))

            step(app, R.string.install_step_upload)
            upload(ssh, Remote.SCRIPT, srv)
            run(ssh, "chmod +x ${Remote.SCRIPT}")
            // The series the app carries goes with the script; a copy left by
            // an older app would otherwise be applied to a core it was never written for.
            val patch = Maintenance.corePatch(app)
            if (patch != null) upload(ssh, Remote.CORE_PATCH, patch) else run(ssh, "rm -f ${Remote.CORE_PATCH}")

            step(app, R.string.install_step_launch)
            val env = installEnv(app, o).joinToString(" ")
            val launch = "rm -f ${Remote.LOG} ${Remote.EXIT}; " +
                "nohup sh -c '$env ${Remote.SCRIPT}; echo \$? > ${Remote.EXIT}' > ${Remote.LOG} 2>&1 & echo LAUNCH_OK"
            val (launchRC, launchOut) = ssh.exec(launch, 30)
            if (launchRC != 0 || !launchOut.contains("LAUNCH_OK")) {
                throw Failure(launchOut.lines().lastOrNull { it.isNotBlank() } ?: "exit $launchRC")
            }

            step(app, R.string.install_step_server)
            val deadline = System.currentTimeMillis() + INSTALL_LIMIT_MS
            var failures = 0
            var exit: String? = null
            while (exit == null) {
                if (System.currentTimeMillis() > deadline) throw Failure(app.getString(R.string.install_error_timeout))
                delay(POLL_MS)
                val out = try {
                    val (_, text) = ssh.exec(POLL, 30)
                    failures = 0
                    text
                } catch (e: Exception) {
                    // The connection drops now and then during a long build: open a new one.
                    if (++failures >= 3) throw e
                    AppLog.w(TAG, "poll failed, reconnecting: ${e.message}")
                    ssh.close()
                    delay(4_000)
                    ssh = SSHClient.connect(app, o.host, o.sshPort, o.user, o.secret)
                    continue
                }
                exit = Maintenance.extract(out, "OLCOS_INSTALL_EXIT")
                val tail = out.lines().filterNot { it.startsWith("OLCOS_INSTALL_EXIT=") }
                _state.value = _state.value.copy(log = tail.joinToString("\n"), step = phaseText(app, tail))
            }
            val (_, facts) = ssh.exec("grep -a -E '^OLCRTC_(URI|CONTAINER)=' ${Remote.LOG} 2>/dev/null; tail -n 80 ${Remote.LOG} 2>/dev/null", 30)
            if (exit != "0") {
                val error = facts.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") || it.startsWith("[X]") }
                    ?.removePrefix("ERROR:")?.removePrefix("[X]")?.trim()
                    ?: facts.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" ").take(300).ifEmpty { "exit $exit" }
                throw Failure(error)
            }
            val uri = Maintenance.extract(facts, "OLCRTC_URI")?.trim() ?: throw Failure(app.getString(R.string.install_error_no_link))
            val link = OlcrtcLink.parse(uri) ?: throw Failure(app.getString(R.string.install_error_no_link))
            val container = Maintenance.extract(facts, "OLCRTC_CONTAINER")?.trim()?.takeIf { it.isNotEmpty() } ?: detectContainer(ssh)

            step(app, R.string.install_step_agent)
            val agent = ensureAgent(app, ssh, o.host, container, sources, status)

            step(app, R.string.install_step_save)
            return save(o, link, container, agent, agentVersion(sources))
        } finally {
            ssh.close()
        }
    }

    /** Re-runs the agent step on a server this phone set up (after a core update changed the sources, or when the agent is gone). */
    suspend fun reinstallAgent(context: Context, server: ServerRecord): ServerRecord = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val user = server.sshUser?.takeIf { it.isNotEmpty() } ?: throw Failure(app.getString(R.string.install_error_no_ssh))
        val secret = AppGraph.secrets.secret(server.id) ?: throw Failure(app.getString(R.string.install_error_no_ssh))
        val sources = agentSources(app)
        val status = statusScripts(app)
        AppLog.i(TAG, "reinstalling the agent on ${server.host}")
        val ssh = SSHClient.connect(app, server.host, server.sshPort, user, secret)
        try {
            val container = server.baseContainer?.takeIf { it.isNotEmpty() } ?: detectContainer(ssh)
            val agent = ensureAgent(app, ssh, server.host, container, sources, status)
            val updated = server.copy(
                port = agent.port, certSHA256 = agent.certSHA256, prefix = agent.prefix, deviceID = agent.deviceID,
                role = "owner", pendingCode = null, lastError = null, baseContainer = container, agentVersion = agentVersion(sources),
            )
            AppGraph.servers.upsert(updated)
            AppLog.i(TAG, "agent on ${server.host} reinstalled, port ${agent.port}")
            updated
        } finally {
            ssh.close()
        }
    }

    // MARK: The server (srv.sh)

    /** The environment srv.sh reads: the iOS installEnv, value for value. */
    private fun installEnv(app: Context, o: Options): List<String> {
        val s = AppGraph.settings
        val env = mutableListOf(
            "OLCRTC_CARRIER=" + Maintenance.shellSafe(o.carrier),
            "OLCRTC_TRANSPORT=" + Maintenance.shellSafe(o.transport),
            "OLCRTC_DNS=" + Maintenance.shellSafe(s.dns.value),
            "OLCRTC_CONFIG_NAME=auto-provisioned",
            // The core commit the app speaks: a server on another one fails every tunnel after the handshake.
            "OLCRTC_PIN=" + Maintenance.CORE_PIN,
            "OLCRTC_CORE_PATCH=" + Remote.CORE_PATCH,
            "OLCRTC_CORE_PATCH_ID=" + Maintenance.corePatchID(app),
        )
        if (o.roomID.isNotEmpty()) env += "OLCRTC_ROOM_ID=" + Maintenance.shellSafe(o.roomID)
        if (o.carrier == "jitsi") env += "OLCRTC_JITSI_URL=" + Maintenance.shellSafe(o.jitsiURL.ifEmpty { Maintenance.DEFAULT_JITSI_BASE })
        if (o.carrier == "wbstream" && o.wbToken.isNotEmpty()) env += "OLCRTC_WB_TOKEN=" + Maintenance.shellSafe(o.wbToken)
        if (o.transport == "vp8channel") {
            env += "OLCRTC_VP8_FPS=" + s.vp8FPS.value
            env += "OLCRTC_VP8_BATCH=" + s.vp8Batch.value
        }
        if (o.transport == "seichannel") {
            env += listOf("OLCRTC_SEI_FPS=30", "OLCRTC_SEI_BATCH=10", "OLCRTC_SEI_FRAG=1200", "OLCRTC_SEI_ACK=1")
        }
        return env
    }

    /** What the install is doing, read off the log's last lines (the iOS installPhase). */
    private fun phaseText(app: Context, tail: List<String>): String {
        for (raw in tail.asReversed()) {
            val l = raw.lowercase()
            val id = when {
                "installing podman" in l || "apt install" in l || "apt-get install" in l || "dnf install" in l -> R.string.install_phase_packages
                "cloning" in l || "git clone" in l || "git fetch" in l -> R.string.install_phase_clone
                "pulling go image" in l || "podman pull" in l -> R.string.install_phase_image
                "go: downloading" in l || "go mod" in l -> R.string.install_phase_modules
                "building olcrtc" in l || "go build" in l -> R.string.install_phase_build
                "starting olcrtc" in l || "podman run -d" in l -> R.string.install_phase_start
                else -> continue
            }
            return app.getString(id)
        }
        return app.getString(R.string.install_step_server)
    }

    private fun detectContainer(ssh: SSHClient): String {
        val (_, out) = ssh.exec("podman ps -a --format '{{.Names}}' 2>/dev/null | grep -E '^olcrtc-server-[a-z0-9]+\$' | head -n 1", 30)
        return out.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: throw Failure("no olcrtc container found")
    }

    // MARK: The agent

    /**
     * The iOS app's order of the agent's sources (SSHRunner+Agent.swift
     * `agentSourceFiles`): the version digest covers them in order, and the two
     * apps must agree on it, or each would see the other's build as stale and
     * rebuild the agent. A file not listed here goes after these, by name.
     */
    private val AGENT_ORDER = listOf(
        "config.go", "auth.go", "camouflage.go", "certs.go", "state.go",
        "server.go", "install.go", "invites.go", "share.go", "usage.go",
        "configlock_unix.go", "configlock_windows.go",
    )

    /** The agent's Go sources from the assets, module-relative paths in the iOS app's order. */
    private fun agentSources(app: Context): List<Pair<String, String>> {
        val names = runCatching { app.assets.list("agent")?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".go") && !it.endsWith("_test.go") }
            .sortedWith(compareBy<String>({ AGENT_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
        val sources = names.map { "agent/$it" to (asset(app, "agent/$it") ?: "") } +
            ("agent/cmd/olcos-agent/main.go" to (asset(app, "agent/cmd/olcos-agent/main.go") ?: ""))
        if (sources.size < 2 || sources.any { it.second.isEmpty() }) throw Failure(app.getString(R.string.install_error_installer_missing))
        return sources
    }

    /** The three status scripts the agent serves, with the `__BASE__` placeholder still in. */
    private fun statusScripts(app: Context): Map<String, String> {
        val scripts = HashMap<String, String>()
        for (name in listOf("readiness", "carriers", "xray")) {
            scripts[name] = asset(app, "scripts/agent-status/$name.sh") ?: throw Failure(app.getString(R.string.install_error_installer_missing))
        }
        return scripts
    }

    /** A digest of the sources (the iOS agentVersion): a newer app rebuilds the server binary. */
    fun agentVersion(sources: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(AGENT_GO_MOD.toByteArray(Charsets.UTF_8))
        for ((path, text) in sources) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(text.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Stages the sources and the status scripts, then runs the ensure script:
     * build when the version differs, `init` with this phone as owner on the
     * first run (or `add-device` + `show` on a reinstall), scripts, unit,
     * firewall, readiness; the endpoint facts come back.
     */
    private fun ensureAgent(
        app: Context, ssh: SSHClient, host: String, container: String,
        sources: List<Pair<String, String>>, status: Map<String, String>,
    ): AgentFacts {
        val version = agentVersion(sources)
        val key = AgentKey.loadOrCreate()
        val stage = Remote.STAGE
        run(ssh, "rm -rf $stage && mkdir -p $stage/agent/cmd/olcos-agent $stage/status")
        upload(ssh, "$stage/go.mod", AGENT_GO_MOD)
        for ((path, text) in sources) upload(ssh, "$stage/$path", text)
        val safeContainer = Maintenance.shellSafe(container)
        for ((name, text) in status) upload(ssh, "$stage/status/$name.sh", text.replace("__BASE__", safeContainer))
        val deviceName = Maintenance.shellSafe(ServerPairing.deviceName.replace(' ', '-')).ifEmpty { "device" }
        val (rc, out) = ssh.exec(agentEnsureScript(version, Maintenance.shellSafe(host), key.spkiBase64, deviceName), 15 * 60)
        Maintenance.extract(out, "OLCOS_AGENT_ERROR")?.let { reason ->
            throw Failure(app.getString(R.string.install_error_agent_fmt, reason.ifEmpty { "unknown" }))
        }
        val port = Maintenance.extract(out, "OLCOS_AGENT_PORT")?.trim()?.toIntOrNull()
        val cert = Maintenance.extract(out, "OLCOS_AGENT_CERT")?.trim()
        val prefix = Maintenance.extract(out, "OLCOS_AGENT_PREFIX")?.trim()
        val device = Maintenance.extract(out, "OLCOS_AGENT_DEVICE")?.trim()?.takeIf { it.isNotEmpty() } ?: key.deviceID
        if (rc != 0 || port == null || cert.isNullOrEmpty() || prefix.isNullOrEmpty()) {
            val last = out.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" ").take(300).ifEmpty { "exit $rc" }
            throw Failure(app.getString(R.string.install_error_agent_fmt, last))
        }
        return AgentFacts(port, cert, prefix, device)
    }

    /** The iOS agentEnsureScript, with the sources and the scripts read from the staging dir instead of the command. */
    private fun agentEnsureScript(version: String, host: String, owner: String, deviceName: String): String {
        val d = AGENT_DIR
        val s = Remote.STAGE
        val image = Maintenance.GO_IMAGE
        return listOf(
            "set -e",
            "D=$d",
            "S=$s",
            "command -v systemctl >/dev/null 2>&1 || { echo OLCOS_AGENT_ERROR=no_systemd; exit 0; }",
            "command -v podman >/dev/null 2>&1 || { echo OLCOS_AGENT_ERROR=no_podman; exit 0; }",
            "mkdir -p \"\$D\" && chmod 700 \"\$D\"",
            "if [ ! -x \"\$D/olcos-agent\" ] || [ \"\$(cat \"\$D/version\" 2>/dev/null)\" != \"$version\" ]; then",
            "  rm -rf \"\$D/src\"",
            "  mkdir -p \"\$D/src\" \"\$D/cache\"",
            "  cp \"\$S/go.mod\" \"\$D/src/go.mod\"",
            "  cp -r \"\$S/agent\" \"\$D/src/agent\"",
            "  podman image exists $image 2>/dev/null || podman pull -q $image >/dev/null 2>&1 || true",
            "  podman rm -f olcos-agent-build >/dev/null 2>&1 || true",
            "  if ! podman run --rm --name olcos-agent-build --network none -v \"\$D:/work:Z\" -w /work/src " +
                "-e GOCACHE=/work/cache -e CGO_ENABLED=0 -e GOTOOLCHAIN=local $image " +
                "go build -trimpath -o /work/olcos-agent.new ./agent/cmd/olcos-agent >/tmp/olcos-agent-build.log 2>&1; then",
            "    echo \"OLCOS_AGENT_ERROR=build: \$(tail -n 1 /tmp/olcos-agent-build.log | tr -d '\\r')\"; exit 0",
            "  fi",
            "  mv -f \"\$D/olcos-agent.new\" \"\$D/olcos-agent\"",
            "  echo \"$version\" > \"\$D/version\"",
            "fi",
            // First install: generate everything and register this phone as owner.
            // Reinstall: keep the certificate and the devices, make sure the owner key is there, read the endpoint back.
            "if [ ! -s \"\$D/config.json\" ]; then",
            "  FACTS=\$(\"\$D/olcos-agent\" init -dir \"\$D\" -port 0 -host '$host' -owner '$owner' -name '$deviceName' 2>/tmp/olcos-agent-init.err) || " +
                "{ echo \"OLCOS_AGENT_ERROR=init: \$(tail -n 1 /tmp/olcos-agent-init.err | tr -d '\\r')\"; exit 0; }",
            "else",
            "  \"\$D/olcos-agent\" add-device -dir \"\$D\" -key '$owner' -role owner -name '$deviceName' >/dev/null 2>&1 || true",
            "  FACTS=\$(\"\$D/olcos-agent\" show -dir \"\$D\" 2>/tmp/olcos-agent-show.err) || " +
                "{ echo \"OLCOS_AGENT_ERROR=show: \$(tail -n 1 /tmp/olcos-agent-show.err | tr -d '\\r')\"; exit 0; }",
            "fi",
            "PORT=\$(printf '%s\\n' \"\$FACTS\" | sed -n 's/^OLCOS_AGENT_PORT=//p' | head -n1)",
            "[ -n \"\$PORT\" ] || { echo OLCOS_AGENT_ERROR=no_port; exit 0; }",
            // The status scripts, every run, so a newer app updates them.
            "for s in readiness carriers xray; do",
            "  if [ -s \"\$S/status/\$s.sh\" ]; then \"\$D/olcos-agent\" set-script -dir \"\$D\" -name \"\$s\" -file \"\$S/status/\$s.sh\" >/dev/null 2>&1 || true; fi",
            "done",
            "cat > /etc/systemd/system/olcos-agent.service <<'UNIT'",
            "[Unit]",
            "Description=olcOS server agent",
            "After=network-online.target",
            "Wants=network-online.target",
            "[Service]",
            "Type=simple",
            "ExecStart=$d/olcos-agent serve -dir $d",
            "Restart=always",
            "RestartSec=2",
            "[Install]",
            "WantedBy=multi-user.target",
            "UNIT",
            "systemctl daemon-reload >/dev/null 2>&1 || true",
            "systemctl enable olcos-agent >/dev/null 2>&1 || true",
            "systemctl restart olcos-agent >/dev/null 2>&1 || { echo \"OLCOS_AGENT_ERROR=service: \$(systemctl is-active olcos-agent 2>&1 | tr -d '\\r')\"; exit 0; }",
            // The port, best effort, where a host firewall is on.
            "if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi active; then ufw allow \"\$PORT\"/tcp >/dev/null 2>&1 || true; fi",
            "if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then firewall-cmd --add-port=\"\$PORT\"/tcp >/dev/null 2>&1 || true; firewall-cmd --permanent --add-port=\"\$PORT\"/tcp >/dev/null 2>&1 || true; fi",
            "LISTEN=\"\"",
            "for i in \$(seq 1 15); do",
            "  if ss -ltnH 2>/dev/null | grep -q \":\$PORT \" || netstat -ltn 2>/dev/null | grep -q \":\$PORT \"; then LISTEN=1; break; fi",
            "  sleep 1",
            "done",
            "if [ -z \"\$LISTEN\" ]; then if systemctl is-active --quiet olcos-agent; then LISTEN=1; fi; fi",
            "[ -n \"\$LISTEN\" ] || { echo \"OLCOS_AGENT_ERROR=not_listening: \$(systemctl is-active olcos-agent 2>&1 | tr -d '\\r')\"; exit 0; }",
            "rm -rf \"\$S\"",
            "printf '%s\\n' \"\$FACTS\"",
        ).joinToString("\n")
    }

    // MARK: Saving

    private fun save(o: Options, link: OlcrtcLink, container: String, agent: AgentFacts, version: String): Outcome {
        val record = AppGraph.store.add(link, o.name + " · " + OlcrtcLink.carrierLabel(link.carrier))
        // The phone put this room on the server now: that is the age the renewals and the auto-update count from.
        if (link.carrier == "telemost" || link.carrier == "vkcalls" || link.carrier == "wbstream") {
            AppGraph.roomAges.set(link.roomID, System.currentTimeMillis() / 1000)
        }
        val server = ServerRecord(
            name = o.name, host = o.host, port = agent.port, certSHA256 = agent.certSHA256, prefix = agent.prefix,
            deviceID = agent.deviceID, role = "owner", sshUser = o.user, sshPort = o.sshPort, baseContainer = container,
            recordIDs = listOf(record.id), agentVersion = version,
        )
        AppGraph.servers.upsert(server)
        AppGraph.secrets.store(server.id, o.secret)
        return Outcome(server, record)
    }

    // MARK: SSH helpers

    private fun run(ssh: SSHClient, command: String, timeoutSeconds: Int = 60) {
        val (rc, out) = ssh.exec(command, timeoutSeconds)
        if (rc != 0) throw Failure(out.lines().lastOrNull { it.isNotBlank() }?.take(300) ?: "exit $rc")
    }

    /** Writes a text file on the server through `printf '%s' '<base64>' | base64 -d`, in pieces a shell argument can hold. */
    private fun upload(ssh: SSHClient, path: String, text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        if (b64.isEmpty()) {
            run(ssh, ": > $path")
            return
        }
        var first = true
        for (chunk in b64.chunked(CHUNK)) {
            run(ssh, "printf '%s' '$chunk' | base64 -d " + (if (first) ">" else ">>") + " $path")
            first = false
        }
    }

    private fun asset(context: Context, path: String): String? =
        runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()?.takeIf { it.isNotBlank() }
}
