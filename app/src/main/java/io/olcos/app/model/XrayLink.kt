package io.olcos.app.model

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A VLESS + REALITY connection for the Xray engine, as a `vless://` link
 * carries it (the same fields and rules as the iOS XrayURI codec):
 *
 *     vless://<uuid>@<host>:<port>?security=reality&sni=…&pbk=…[&sid=…]&fp=…&flow=…&spx=…&type=tcp#<name>
 */
@Serializable
data class XrayLink(
    val host: String,
    val port: Int = 443,
    val uuid: String,
    val sni: String,
    val publicKey: String,
    val shortID: String = "",
    val fingerprint: String = "chrome",
    val flow: String = "xtls-rprx-vision",
    val spiderX: String = "/",
) {
    val fallbackName: String get() = "Xray · $host"

    /** The canonical link the core parses; the name rides in the fragment. */
    fun encode(name: String = ""): String {
        val shownHost = if (host.contains(":")) "[$host]" else host
        val query = buildList {
            add("encryption=none")
            add("flow=" + enc(flow))
            add("security=reality")
            add("sni=" + enc(sni))
            add("fp=" + enc(fingerprint))
            add("pbk=" + enc(publicKey))
            if (shortID.isNotEmpty()) add("sid=" + enc(shortID))
            add("spx=" + enc(spiderX))
            add("type=tcp")
        }.joinToString("&")
        val fragment = if (name.isEmpty()) "" else "#" + enc(name)
        return "$SCHEME$uuid@$shownHost:$port?$query$fragment"
    }

    class ParseError(message: String) : Exception(message)

    companion object {
        const val SCHEME = "vless://"

        fun matches(raw: String): Boolean = raw.trim().startsWith(SCHEME, ignoreCase = true)

        /** The link's connection and its name; throws naming the missing or unsupported field. */
        fun parse(raw: String): Pair<XrayLink, String> {
            val trimmed = raw.trim()
            if (!matches(trimmed)) throw ParseError("not a vless:// link")
            var body = trimmed.substring(SCHEME.length)
            var name = ""
            val hash = body.indexOf('#')
            if (hash >= 0) {
                name = dec(body.substring(hash + 1))
                body = body.substring(0, hash)
            }
            val question = body.indexOf('?')
            val authority = if (question >= 0) body.substring(0, question) else body
            val rawQuery = if (question >= 0) body.substring(question + 1) else ""
            val at = authority.lastIndexOf('@')
            if (at <= 0) throw ParseError("uuid")
            val uuid = dec(authority.substring(0, at))
            var hostPort = authority.substring(at + 1)
            var host: String
            var portText: String?
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) throw ParseError("host")
                host = hostPort.substring(1, close)
                hostPort = hostPort.substring(close + 1)
                portText = hostPort.removePrefix(":").ifEmpty { null }
            } else {
                val colon = hostPort.lastIndexOf(':')
                host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
                portText = if (colon >= 0) hostPort.substring(colon + 1) else null
            }
            if (host.isEmpty()) throw ParseError("host")
            val port = portText?.let { it.toIntOrNull() ?: throw ParseError("port") } ?: 443

            val q = HashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = dec(if (eq < 0) pair else pair.substring(0, eq)).lowercase()
                q[key] = if (eq < 0) "" else dec(pair.substring(eq + 1))
            }
            val security = (q["security"] ?: "").lowercase()
            if (security != "reality") throw ParseError("security=" + security.ifEmpty { "none" })
            // "raw" is Xray's newer name for the same TCP transport.
            val type = (q["type"] ?: "tcp").lowercase()
            if (type != "tcp" && type != "raw") throw ParseError("type=$type")
            val encryption = (q["encryption"] ?: "none").lowercase()
            if (encryption != "none") throw ParseError("encryption=$encryption")
            val pbk = q["pbk"].orEmpty()
            if (pbk.isEmpty()) throw ParseError("pbk")
            val sni = (q["sni"] ?: q["servername"]).orEmpty()
            if (sni.isEmpty()) throw ParseError("sni")
            val link = XrayLink(
                host = host, port = port, uuid = uuid, sni = sni, publicKey = pbk,
                shortID = q["sid"].orEmpty(),
                fingerprint = q["fp"]?.ifEmpty { null } ?: "chrome",
                flow = q["flow"] ?: "",
                spiderX = q["spx"]?.ifEmpty { null } ?: "/",
            )
            return link to name
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun dec(s: String): String =
            runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    }
}
