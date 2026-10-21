package io.olcos.app.core

import io.olcos.app.model.XrayLink
import olcos.LogWriter
import olcos.Olcos
import olcos.Runtime as OlcosRuntime
import org.json.JSONObject

/**
 * The VLESS + REALITY engine of olcos-core: one Runtime for the process,
 * driven over its JSON method channel the way the iOS XrayEngine drives it
 * (`engine.start` / `engine.stop`; envelope {"ok", "error", "result"}).
 * `start` succeeding says the SOCKS listener is up, not that the server
 * accepted us: xray-core reports a bad key or an unreachable server on the
 * first connection, as a `health:{…}` log line kept in `lastHealth`.
 */
object XrayEngine {
    private const val TAG = "olcos.xray"
    private const val HEALTH_PREFIX = "health:"

    @Volatile
    var lastHealth: String? = null
        private set

    private val runtime: OlcosRuntime by lazy {
        val rt = Olcos.new_()
        rt.setLogWriter(LogWriter { level, msg ->
            val line = msg.trim()
            if (line.startsWith(HEALTH_PREFIX)) {
                lastHealth = line.substring(HEALTH_PREFIX.length)
                AppLog.w(TAG, line)
            } else if (level <= 2) {
                // Level 3 is xray-core's per-connection chatter.
                AppLog.i(TAG, line)
            }
        })
        rt
    }

    class InvokeError(method: String, message: String) : Exception("$method: $message")

    /** Binds the SOCKS5 listener on `port` for the link's server. Throws with the core's words. */
    @Synchronized
    fun start(link: XrayLink, port: Int, directRules: String = "") {
        // Only one session per Runtime: a leftover from an interrupted stop must not fail this start.
        runtime.invoke("engine.stop", "{}")
        lastHealth = null
        val input = JSONObject().put("uri", link.encode()).put("socks_port", port)
            .also { if (directRules.isNotEmpty()) it.put("direct", directRules) }.toString()
        val result = decode(runtime.invoke("engine.start", input), "engine.start")
        val addr = result.optString("socks_addr")
        if (addr.substringAfterLast(':').toIntOrNull() != port) {
            stop()
            throw InvokeError("engine.start", "the core bound $addr, expected port $port")
        }
    }

    @Synchronized
    fun stop() {
        runCatching { runtime.invoke("engine.stop", "{}") }
    }

    /**
     * An HTTP round trip through a temporary Xray client, in milliseconds: it
     * fails on a wrong UUID or key, so its success proves the server. Safe
     * beside a running session (the probe is isolated on the Go side).
     */
    fun ping(link: XrayLink, timeoutMs: Int = 15_000, url: String = "https://cp.cloudflare.com/generate_204"): Long {
        val input = JSONObject().put("uri", link.encode()).put("timeout_ms", timeoutMs).put("url", url).toString()
        return decode(runtime.invoke("engine.ping", input), "engine.ping").optLong("rtt_ms", -1)
    }

    /** The `result` of an ok envelope; throws the core's own error text otherwise. */
    private fun decode(raw: String, method: String): JSONObject {
        val envelope = runCatching { JSONObject(raw) }.getOrElse {
            throw InvokeError(method, "malformed envelope: " + raw.take(200))
        }
        if (!envelope.optBoolean("ok")) {
            throw InvokeError(method, envelope.optString("error").ifEmpty { "unknown error" })
        }
        return envelope.optJSONObject("result") ?: JSONObject()
    }
}
