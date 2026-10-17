package io.olcos.app.core

import android.util.Base64
import io.olcos.app.AppGraph
import io.olcos.app.data.ConnectionStore
import io.olcos.app.model.AgentOffer
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.DeviceInvite
import io.olcos.app.model.InviteCipher
import io.olcos.app.model.InviteLink
import io.olcos.app.model.ServerRecord
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device invites published from this phone, as the iOS app publishes them:
 * one encrypted list per invited device, served by a small busybox web
 * server on the VPS, kept current when the server's connections change and
 * deleted on revocation. The server side runs through the agent (this
 * device is an owner), with the same scripts the iOS app runs over SSH; an
 * invite can hand over server access with a one-time pairing code the agent
 * mints.
 */
object Invites {
    const val REFRESH = "30m"
    private const val SHARE_DIR = "/opt/olcos-share"
    private const val SHARE_CONTAINER = "olcos-share"
    private const val SHARE_IMAGE = "docker.io/library/busybox:1.37.0"

    data class ShareResult(
        val port: Int?,
        val written: Set<String>,
        val deleted: Set<String>,
        val stopped: Boolean,
        val error: String?,
    )

    class Failure(message: String) : Exception(message)

    fun makeToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun makeKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun isWellFormedToken(token: String): Boolean = token.length == 32 && token.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The invite's tag: the first 8 hex digits of SHA-256(token). Its links
     * carry `inv-<tag>` as their client id, and the invited device announces
     * `inv-<tag>-<its own id>` in the rooms (the iOS app does the same), so
     * the server can tell whose traffic is whose without the token, which
     * opens the list, ever reaching its logs.
     */
    fun tag(token: String): String = digest(token).take(8)

    /** What the server saw of one invite's devices, summed over every install that took it: what they received and sent. */
    data class Usage(val installs: Int, val online: Boolean, val lastSeen: Long, val bytesDown: Long, val bytesUp: Long)

    /** PURE: the usage of the invite with this token, from the agent's per-device list; null when none matches. */
    fun usageOf(token: String, devices: List<AgentClient.DeviceUsage>): Usage? {
        val id = "inv-" + tag(token)
        val mine = devices.filter { it.id == id || it.id.startsWith("$id-") }
        if (mine.isEmpty()) return null
        return Usage(
            installs = mine.size,
            online = mine.any { it.online },
            lastSeen = mine.maxOf { it.lastSeen },
            // The server counts from the device's side of each stream: "in" is what the device sent.
            bytesDown = mine.sumOf { it.bytesOut },
            bytesUp = mine.sumOf { it.bytesIn },
        )
    }

    fun randomSharePort(): Int = (20_000..59_999).random()

    fun link(host: String, port: Int, token: String, key: ByteArray): String =
        InviteLink.source(host, port, token) + "#" + InviteLink.encodeKey(key)

    /** The records a server's invites carry: what the server's own invite brought, and what this device added to it. */
    fun records(server: ServerRecord, store: ConnectionStore): List<ConnectionRecord> {
        val canon = server.source?.let { ConnectionStore.canonicalSource(it) }
        return store.records.value.filter { record ->
            record.id in server.recordIDs || (canon != null && record.subSource?.let { ConnectionStore.canonicalSource(it) } == canon)
        }
    }

    /**
     * The list an invited device receives (the iOS InvitePayload.body): every
     * connection under its record id, the rooms' ages, and the agent offer
     * when the invite gives server access. Null without any connection.
     */
    fun body(serverName: String, records: List<ConnectionRecord>, agent: AgentOffer?, tag: String? = null): String? {
        if (records.isEmpty()) return null
        val lines = mutableListOf("#name: " + oneLine(serverName), "#refresh: $REFRESH")
        agent?.let { offer ->
            val shown = if (offer.host.contains(":")) "[${offer.host}]" else offer.host
            lines += "#agent: $shown:${offer.port}"
            lines += "#agent-cert: " + oneLine(offer.certSHA256)
            lines += "#agent-prefix: " + oneLine(offer.prefix)
            lines += "#agent-code: " + oneLine(offer.code)
            lines += "#agent-role: " + oneLine(offer.role)
        }
        for (record in records) {
            val olcrtc = record.olcrtc
            val xray = record.xray
            if (olcrtc != null) {
                // Every device announces its own name in the room; an invite's
                // devices put the invite's tag in front of it.
                lines += olcrtc.copy(clientID = tag?.let { "inv-$it" } ?: "default").encode()
                lines += "##name: " + oneLine(record.displayName)
                AppGraph.roomAges.get(olcrtc.roomID)?.let { lines += "##created: $it" }
                if (olcrtc.extraRooms.isNotEmpty()) lines += "##lanes: " + olcrtc.extraRooms.joinToString(",") { oneLine(it) }
                if (olcrtc.with.isNotEmpty()) lines += "##with: " + olcrtc.with.joinToString(",") { oneLine(it.text()) }
                if (olcrtc.bond != null && olcrtc.lanes.size > 1) lines += "##bond: " + oneLine(olcrtc.bond)
            } else if (xray != null) {
                lines += xray.encode()
                lines += "##name: " + oneLine(record.displayName)
            } else {
                continue
            }
            lines += "##id: " + record.id.lowercase()
        }
        return lines.joinToString("\n") + "\n"
    }

    fun digest(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** `olcos1:` + base64(nonce ‖ ciphertext ‖ tag), AAD `olcos-invite-v1`: what InviteCipher.open reads. */
    fun seal(plaintext: String, key: ByteArray): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("olcos-invite-v1".toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return InviteCipher.PREFIX + Base64.encodeToString(nonce + sealed, Base64.NO_WRAP)
    }

    /** Starts the share server when it is missing (on `port`), then writes each list atomically. */
    fun sharePublishScript(port: Int, lists: List<Pair<String, String>>): String {
        val lines = mutableListOf(
            "set -e",
            "D=$SHARE_DIR",
            "mkdir -p \"\$D\"",
            "chmod 755 \"\$D\"",
            "[ -f \"\$D/index.html\" ] || : > \"\$D/index.html\"",
            "if ! podman container exists $SHARE_CONTAINER 2>/dev/null; then",
            "  if ! podman run -d --name $SHARE_CONTAINER --restart=always -p $port:8080 -v \"\$D:/www:ro,Z\" $SHARE_IMAGE httpd -f -p 8080 -h /www >/dev/null 2>/tmp/olcos-share.err; then",
            "    if grep -qi 'address already in use' /tmp/olcos-share.err; then echo OLCOS_SHARE_ERROR=port_busy; exit 0; fi",
            "    echo \"OLCOS_SHARE_ERROR=\$(tail -n 1 /tmp/olcos-share.err | tr -d '\\r')\"; exit 0",
            "  fi",
            "else",
            "  podman start $SHARE_CONTAINER >/dev/null 2>&1 || true",
            "fi",
            "echo \"OLCOS_SHARE_PORT=\$(podman port $SHARE_CONTAINER 8080/tcp 2>/dev/null | head -n 1 | sed 's/.*://')\"",
        )
        for ((token, served) in lists) {
            if (!isWellFormedToken(token) || !isServable(served)) continue
            lines += "printf '%s' '$served' > \"\$D/.tmp-$token\""
            lines += "chmod 644 \"\$D/.tmp-$token\""
            lines += "mv -f \"\$D/.tmp-$token\" \"\$D/$token\""
            lines += "echo OLCOS_SHARE_OK=$token"
        }
        return lines.joinToString("\n")
    }

    /** Deletes lists; when none is left the share server goes too. */
    fun shareDeleteScript(tokens: List<String>): String {
        val lines = mutableListOf("D=$SHARE_DIR")
        for (token in tokens.filter { isWellFormedToken(it) }) lines += "rm -f \"\$D/$token\" && echo OLCOS_SHARE_DELETED=$token"
        lines += "if [ -d \"\$D\" ] && [ -z \"\$(find \"\$D\" -maxdepth 1 -type f ! -name index.html ! -name '.tmp-*' 2>/dev/null | head -n 1)\" ]; then"
        lines += "  podman rm -f $SHARE_CONTAINER >/dev/null 2>&1 || true"
        lines += "  rm -rf \"\$D\""
        lines += "  echo OLCOS_SHARE_STOPPED=1"
        lines += "fi"
        return lines.joinToString("\n")
    }

    fun parseShare(output: String): ShareResult {
        var port: Int? = null
        val written = HashSet<String>()
        val deleted = HashSet<String>()
        var stopped = false
        var error: String? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("OLCOS_SHARE_PORT=") -> port = line.substring(17).toIntOrNull()
                line.startsWith("OLCOS_SHARE_OK=") -> written += line.substring(15)
                line.startsWith("OLCOS_SHARE_DELETED=") -> deleted += line.substring(20)
                line == "OLCOS_SHARE_STOPPED=1" -> stopped = true
                line.startsWith("OLCOS_SHARE_ERROR=") -> error = line.substring(18).ifEmpty { "unknown" }
            }
        }
        return ShareResult(port, written, deleted, stopped, error)
    }

    /** `olcos-agent invite`: a one-time pairing code with a role, valid 30 days. */
    fun agentInviteScript(role: String, name: String): String {
        val d = ServerPairingOwner.AGENT_DIR
        val safeRole = if (role == "owner") "owner" else "member"
        val safeName = Maintenance.shellSafe(name.ifEmpty { "device" })
        return listOf(
            "set -e",
            "[ -x $d/olcos-agent ] && [ -s $d/config.json ] || { echo OLCOS_AGENT_ERROR=not_installed; exit 0; }",
            "$d/olcos-agent invite -dir $d -role $safeRole -name '$safeName' -days 30 2>/tmp/olcos-agent-invite.err || { echo \"OLCOS_AGENT_ERROR=invite: \$(tail -n 1 /tmp/olcos-agent-invite.err | tr -d '\\r')\"; exit 0; }",
        ).joinToString("\n")
    }

    /** `olcos-agent revoke-invite`: burns the code and revokes the device that used it. */
    fun agentRevokeScript(code: String): String {
        val d = ServerPairingOwner.AGENT_DIR
        return listOf(
            "[ -x $d/olcos-agent ] && [ -s $d/config.json ] || { echo OLCOS_AGENT_REVOKED=-; exit 0; }",
            "$d/olcos-agent revoke-invite -dir $d -code '${Maintenance.shellSafe(code)}' 2>/dev/null || echo OLCOS_AGENT_REVOKED=-",
        ).joinToString("\n")
    }

    // MARK: Flows through the agent

    /** A new invite, uploaded before it is returned; with access, the pairing code is minted first. */
    suspend fun create(client: AgentClient, server: ServerRecord, name: String, access: String): DeviceInvite {
        val store = AppGraph.store
        val servers = AppGraph.servers
        if (body(server.name, records(server, store), null) == null) throw Failure("nothing to share")
        var invite = DeviceInvite(name = name.trim(), token = makeToken(), createdAt = System.currentTimeMillis() / 1000)
        if (access == "member" || access == "owner") {
            val (_, out) = client.exec(agentInviteScript(access, invite.name), 60)
            Maintenance.extract(out, "OLCOS_AGENT_ERROR")?.let { throw Failure(it) }
            val code = Maintenance.extract(out, "OLCOS_AGENT_INVITE") ?: throw Failure("no code")
            invite = invite.copy(agentCode = code, agentRole = Maintenance.extract(out, "OLCOS_AGENT_INVITE_ROLE") ?: access)
        }
        AppGraph.secrets.storeInviteKey(invite.token, makeKey())
        servers.update(server.id) { it.copy(invites = it.invites + invite) }
        upload(client, servers.byId(server.id) ?: server, setOf(invite.token))
        return servers.byId(server.id)?.invites?.firstOrNull { it.id == invite.id } ?: invite
    }

    /** Uploads the lists that are out of date (or the named ones). Throws on a failure the server reports. */
    suspend fun upload(client: AgentClient, server: ServerRecord, only: Set<String>? = null) {
        val store = AppGraph.store
        val servers = AppGraph.servers
        val records = records(server, store)
        val bodies = HashMap<String, String>()
        for (invite in server.invites) {
            val offer = invite.agentCode?.let { code ->
                AgentOffer(server.host, server.port, server.certSHA256, server.prefix, code, invite.agentRole ?: "member")
            }
            body(server.name, records, offer, tag(invite.token))?.let { bodies[invite.token] = it }
        }
        val due = server.invites.filter { invite ->
            val body = bodies[invite.token] ?: return@filter false
            only?.contains(invite.token) ?: (invite.publishedDigest != digest(body))
        }
        if (due.isEmpty()) return
        val lists = due.mapNotNull { invite ->
            val key = AppGraph.secrets.inviteKey(invite.token) ?: return@mapNotNull null
            invite.token to seal(bodies.getValue(invite.token), key)
        }
        if (lists.isEmpty()) return
        var port = server.sharePort ?: randomSharePort()
        var result: ShareResult? = null
        repeat(3) { attempt ->
            if (result != null) return@repeat
            val (_, out) = client.exec(sharePublishScript(port, lists), 120)
            val parsed = parseShare(out)
            if (parsed.error == "port_busy" && attempt < 2) {
                port = randomSharePort()
            } else {
                result = parsed
            }
        }
        val final = result ?: throw Failure("port busy")
        final.error?.let { throw Failure(it) }
        val now = System.currentTimeMillis() / 1000
        servers.update(server.id) { fresh ->
            fresh.copy(
                sharePort = final.port ?: fresh.sharePort,
                invites = fresh.invites.map { invite ->
                    if (invite.token in final.written) invite.copy(publishedAt = now, publishedDigest = bodies[invite.token]?.let(::digest)) else invite
                },
            )
        }
        AppLog.i("olcos.invites", "invites for ${server.name}: ${final.written.size} list(s) uploaded")
    }

    /** Deletes the invite's list and burns its pairing code (revoking the device that used it), then forgets it. */
    suspend fun revoke(client: AgentClient, server: ServerRecord, invite: DeviceInvite) {
        val servers = AppGraph.servers
        if (invite.publishedAt != null || server.sharePort != null) {
            val (_, out) = client.exec(shareDeleteScript(listOf(invite.token)), 60)
            val result = parseShare(out)
            invite.agentCode?.let { code -> runCatching { client.exec(agentRevokeScript(code), 60) } }
            servers.update(server.id) { fresh ->
                fresh.copy(invites = fresh.invites.filterNot { it.id == invite.id }, sharePort = if (result.stopped) null else fresh.sharePort)
            }
        } else {
            servers.update(server.id) { fresh -> fresh.copy(invites = fresh.invites.filterNot { it.id == invite.id }) }
        }
        AppGraph.secrets.forgetInviteKey(invite.token)
        AppLog.i("olcos.invites", "invite ${invite.name.ifEmpty { "—" }} revoked on ${server.name}")
    }

    private fun oneLine(text: String): String = text.lines().joinToString(" ").trim()

    /** The served text goes inside single quotes; only the cipher's own alphabet may. */
    private fun isServable(text: String): Boolean =
        text.isNotEmpty() && text.all { it.isLetterOrDigit() || it in "+/=:" }
}

/** The agent's install directory on a server, as the iOS installer names it. */
object ServerPairingOwner {
    const val AGENT_DIR = "/opt/olcos-agent"
}
