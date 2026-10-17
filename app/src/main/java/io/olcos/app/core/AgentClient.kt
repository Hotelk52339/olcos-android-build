package io.olcos.app.core

import android.util.Base64
import io.olcos.app.data.ConnectionStore
import io.olcos.app.model.AgentOffer
import io.olcos.app.model.InviteLink
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The app side of the server agent: signed requests over the pinned HTTPS,
 * with a route per attempt (through the live tunnel when the session runs on
 * this server, since its exit reaches the server's own address; then direct),
 * exactly the iOS AgentClient's contract. The agent answers anything it
 * cannot authenticate with an nginx 404: a wrong clock, a revoked key or a
 * bad pin all read the same, so `reason` says what is known.
 */
class AgentClient(val server: ServerRecord, private val key: AgentKey) {
    class AgentError(message: String, val status: Int = 0) : IOException(message)

    data class Device(val id: String, val name: String, val role: String, val addedAt: Long, val revoked: Boolean)

    suspend fun status(): AgentStatus = withContext(Dispatchers.IO) {
        val json = decode(send("GET", "status", ByteArray(0), longPoll = false))
        val outputs = HashMap<String, String>()
        json.optJSONObject("outputs")?.let { o -> for (k in o.keys()) outputs[k] = o.optString(k) }
        AgentStatus(json.optLong("version"), json.optLong("at"), outputs)
    }

    /** Long-polls the event version; returns the (possibly unchanged) version. */
    suspend fun events(since: Long): Long = withContext(Dispatchers.IO) {
        decode(send("GET", "events?since=$since", ByteArray(0), longPoll = true)).optLong("version", since)
    }

    /** A room's start, stop or restart: podman takes seconds for it (a stop waits up to ten), so the wait is long. */
    suspend fun room(container: String, action: String) = withContext(Dispatchers.IO) {
        decode(send("POST", "rooms/$container/$action", ByteArray(0), longPoll = false, timeoutMs = ROOM_MS))
        Unit
    }

    suspend fun log(container: String, lines: Int = 200): String = withContext(Dispatchers.IO) {
        decode(send("GET", "rooms/$container/log?lines=$lines", ByteArray(0), longPoll = false)).optString("log")
    }

    /** Owner only: runs a maintenance script; `{exit, output}`. */
    suspend fun exec(script: String, timeoutSeconds: Int = 600): Pair<Int, String> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("script", Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .put("timeout", timeoutSeconds)
            .toString().toByteArray(Charsets.UTF_8)
        // The agent answers when the script ends: the wait is the script's own budget and a margin.
        val json = decode(send("POST", "exec", body, longPoll = true, timeoutMs = (timeoutSeconds + 15) * 1000))
        json.optInt("exit", 1) to json.optString("output")
    }

    suspend fun devices(): List<Device> = withContext(Dispatchers.IO) {
        val list = decode(send("GET", "devices", ByteArray(0), longPoll = false)).optJSONArray("devices") ?: JSONArray()
        (0 until list.length()).map { i ->
            val d = list.getJSONObject(i)
            Device(d.optString("id"), d.optString("name"), d.optString("role"), d.optLong("added_at"), d.optBoolean("revoked"))
        }
    }

    /**
     * What the server saw of each device in the rooms (owners only): when, and
     * how much. `bytesIn` is what the device sent through the server, `bytesOut`
     * what it received.
     */
    data class DeviceUsage(val id: String, val lastSeen: Long, val online: Boolean, val bytesIn: Long, val bytesOut: Long, val sessions: Int)

    suspend fun usage(): List<DeviceUsage> = withContext(Dispatchers.IO) {
        // The agent may first read the rooms' logs (up to two seconds).
        val list = decode(send("GET", "usage", ByteArray(0), longPoll = false, timeoutMs = USAGE_MS)).optJSONArray("devices") ?: JSONArray()
        (0 until list.length()).map { i ->
            val d = list.getJSONObject(i)
            DeviceUsage(d.optString("id"), d.optLong("last_seen"), d.optBoolean("online"), d.optLong("in"), d.optLong("out"), d.optInt("sessions"))
        }
    }

    suspend fun revoke(deviceID: String) = withContext(Dispatchers.IO) {
        decode(send("DELETE", "devices/$deviceID", ByteArray(0), longPoll = false))
        Unit
    }

    // MARK: Transport

    private fun routes(): List<AgentHTTPS.Socks?> = routesFor(server)

    private fun send(method: String, pathSuffix: String, body: ByteArray, longPoll: Boolean, timeoutMs: Int? = null): AgentHTTPS.Response {
        var last: Exception = AgentError("no route")
        for (socks in routes()) {
            val response = try {
                val timeout = timeoutMs ?: if (longPoll) LONG_POLL_MS else if (socks != null) TUNNEL_MS else DIRECT_MS
                val path = "/${server.prefix}/v1/$pathSuffix"
                val request = AgentHTTPS.request(method, path, server.host, server.port, signedHeaders(method, path, body), body)
                AgentHTTPS.send(server.host, server.port, server.certSHA256, request, socks, timeout)
            } catch (e: AgentHTTPS.AnswerLost) {
                // The request went out: a change may have been made, and is never sent twice.
                if (method != "GET") throw e
                last = e
                continue
            } catch (e: Exception) {
                // Nothing came back this way; the next route may reach the agent.
                last = e
                continue
            }
            // The agent answered: its answer stands, whichever route carried it,
            // and a room action is never sent a second time.
            if (response.status != 200) throw AgentError("HTTP ${response.status}", response.status)
            return response
        }
        AppLog.w("olcos.agent", "agent on ${server.host} did not answer: ${reason(last)}")
        throw last
    }

    /** The four headers that authenticate a request (the agent's canonical string). */
    private fun signedHeaders(method: String, path: String, body: ByteArray): List<Pair<String, String>> {
        val now = (System.currentTimeMillis() / 1000).toString()
        val nonce = AgentKey.base64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val canonical = canonical(method, path, now, nonce, body)
        val signature = key.sign(canonical.toByteArray(Charsets.UTF_8))
        return listOf(
            "X-Olc-Device" to key.deviceID,
            "X-Olc-Time" to now,
            "X-Olc-Nonce" to nonce,
            "X-Olc-Sig" to AgentKey.base64url(signature),
        )
    }

    companion object {
        private const val TUNNEL_MS = 6_000
        private const val DIRECT_MS = 4_000
        private const val LONG_POLL_MS = 30_000
        private const val PAIR_MS = 10_000
        private const val ROOM_MS = 40_000
        private const val USAGE_MS = 8_000

        /**
         * Through the live session first when it runs through this server (its
         * exit reaches the server's own address, which the phone's own network
         * may not), then direct.
         */
        fun routesFor(server: ServerRecord): List<AgentHTTPS.Socks?> {
            val record = TunnelManager.record.value
            val session = TunnelManager.session.value
            val live = TunnelManager.state.value is TunnelManager.State.Connected && session != null &&
                record != null && isThrough(server, record.id, record.subSource)
            return if (live) listOf(AgentHTTPS.Socks(session!!.port, session.socksUser, session.socksPass), null) else listOf(null)
        }

        /** PURE: whether a session on this record runs through the server: a record it produced, or one a list of it brought. */
        fun isThrough(server: ServerRecord, recordID: String, subSource: String?): Boolean =
            recordID in server.recordIDs || recordID == server.bundleRecordID ||
                (subSource != null && sameServer(subSource, server))

        /** PURE: a list of this server: the invite that brought it, or another invite served from the server's own address. */
        fun sameServer(subSource: String, server: ServerRecord): Boolean =
            (server.source != null && ConnectionStore.canonicalSource(subSource) == ConnectionStore.canonicalSource(server.source)) ||
                InviteLink.parse(subSource)?.host?.equals(server.host, ignoreCase = true) == true

        /** `METHOD \n path?query \n time \n nonce \n hex(sha256(body))`. */
        fun canonical(method: String, path: String, time: String, nonce: String, body: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            return method + "\n" + path + "\n" + time + "\n" + nonce + "\n" + digest
        }

        /** A short, secret-free reason for a failure. */
        fun reason(e: Throwable): String = when (e) {
            is AgentError -> e.message ?: "error"
            is AgentHTTPS.PinMismatch -> "certificate does not match the pin"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }

        /** The `result` of an ok envelope; throws the agent's own error text otherwise. */
        private fun decode(response: AgentHTTPS.Response): JSONObject {
            val text = String(response.body, Charsets.UTF_8)
            return runCatching { JSONObject(text) }.getOrElse { throw AgentError("unreadable answer") }
        }

        /**
         * The one unsigned call: redeems the invite's code for this device's key.
         * The agent registers the key with the invite's role and burns the code.
         */
        suspend fun pair(
            offer: AgentOffer, key: AgentKey, deviceName: String,
            routes: List<AgentHTTPS.Socks?> = listOf(null),
        ): Pair<String, String> = withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("code", offer.code)
                .put("public_key", Base64.encodeToString(key.spkiDER, Base64.NO_WRAP))
                .put("name", deviceName)
                .toString().toByteArray(Charsets.UTF_8)
            val path = "/${offer.prefix}/v1/pair"
            val request = AgentHTTPS.request("POST", path, offer.host, offer.port, emptyList(), body)
            var last: Exception = AgentError("no route")
            for (socks in routes) {
                val response = try {
                    AgentHTTPS.send(offer.host, offer.port, offer.certSHA256, request, socks, PAIR_MS)
                } catch (e: Exception) {
                    // Nothing came back this way; the next route may reach the agent.
                    AppLog.w("olcos.agent", "pairing with ${offer.host} " + (if (socks != null) "through the tunnel" else "direct") + ": ${reason(e)}")
                    last = e
                    continue
                }
                // An answer is final: the code is one-time and is not sent again.
                if (response.status != 200) throw AgentError("HTTP ${response.status}", response.status)
                val json = decode(response)
                val id = json.optString("device_id")
                if (id.isEmpty()) throw AgentError("unreadable answer")
                return@withContext id to json.optString("role", offer.role)
            }
            throw last
        }
    }
}

/** The decoded `GET status`: the version counter, the server clock, the cached script outputs. */
data class AgentStatus(val version: Long, val at: Long, val outputs: Map<String, String>)
