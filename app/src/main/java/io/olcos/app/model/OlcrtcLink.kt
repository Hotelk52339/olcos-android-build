package io.olcos.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** One room of one protocol on a server: a lane of a whole-server connection. */
@Serializable
data class Lane(val carrier: String, val transport: String, val roomID: String) {
    /** `carrier?transport@room`, the form a subscription's `##with` lists lanes in. */
    fun text(): String = "$carrier?$transport@$roomID"

    companion object {
        fun parse(text: String): Lane? {
            val q = text.indexOf('?')
            if (q <= 0) return null
            val rest = text.substring(q + 1)
            val at = rest.indexOf('@')
            if (at <= 0 || at == rest.length - 1) return null
            return Lane(text.substring(0, q), rest.substring(0, at), rest.substring(at + 1))
        }
    }
}

/**
 * One olcrtc connection as its link carries it:
 *
 *     olcrtc://<carrier>?<transport>[<params>]@<roomID>#<key>[%<clientID>][$<mimo>]
 *
 * The params block comes as `[vp8-fps=60,vp8-batch=8]` (the apps' own links)
 * or `<vp8-fps=60&vp8-batch=8>` (the server's install script); both are read.
 * The same grammar as the iOS app's OlcrtcURI. A subscription can widen the
 * connection to the whole server: more rooms of this protocol (`extraRooms`,
 * the list's `##lanes`), the server's other protocols (`with`, `##with`) and
 * the bond endpoint that carries every connection over all of them (`bond`,
 * `##bond`).
 */
@Serializable
data class OlcrtcLink(
    val carrier: String,
    val transport: String,
    val roomID: String,
    val key: String,
    val clientID: String = "default",
    val vp8FPS: Int? = null,
    val vp8Batch: Int? = null,
    val mimo: String = "",
    val extraRooms: List<String> = emptyList(),
    val with: List<Lane> = emptyList(),
    val bond: String? = null,
    /** A seichannel link's own tuning (`fps`, `batch`, `frag`, `ack-ms`); null where the link names none. */
    val seiFPS: Int? = null,
    val seiBatch: Int? = null,
    val seiFrag: Int? = null,
    val seiACK: Int? = null,
) {
    /**
     * The four numbers the engine sets on a seichannel lane, once the link
     * names any: the missing ones take the iOS defaults, each kept in the iOS
     * range. Null when the link names none (the core keeps its own).
     */
    fun seiOptions(): List<Int>? {
        if (seiFPS == null && seiBatch == null && seiFrag == null && seiACK == null) return null
        return listOf(
            (seiFPS ?: 30).coerceIn(1, 120),
            (seiBatch ?: 10).coerceIn(1, 256),
            (seiFrag ?: 1200).coerceIn(100, 60_000),
            (seiACK ?: 1).coerceIn(1, 10_000),
        )
    }

    val serviceName: String get() = carrierLabel(carrier)
    val transportName: String get() = transportLabel(transport)

    /** Every lane the engine joins, this protocol's rooms first. */
    val lanes: List<Lane>
        get() = (listOf(roomID) + extraRooms).map { Lane(carrier, transport, it) } + with

    val isBundle: Boolean get() = with.isNotEmpty()

    /** The services the connection joins, each once: "Yandex Telemost + VK Calls". */
    val servicesLine: String
        get() = lanes.map { it.carrier }.distinct().joinToString(" + ") { carrierLabel(it) }

    companion object {
        const val SCHEME = "olcrtc://"

        fun parse(raw: String): OlcrtcLink? {
            val text = raw.trim()
            if (!text.startsWith(SCHEME, ignoreCase = true)) return null
            val body = text.substring(SCHEME.length)
            val q = body.indexOf('?')
            if (q <= 0) return null
            val carrier = body.substring(0, q).lowercase()
            val afterCarrier = body.substring(q + 1)
            val at = afterCarrier.indexOf('@')
            if (at <= 0) return null
            val transportBlock = afterCarrier.substring(0, at)
            val rest = afterCarrier.substring(at + 1)
            val hash = rest.lastIndexOf('#')
            if (hash <= 0) return null
            val roomID = rest.substring(0, hash)
            var tail = rest.substring(hash + 1)
            var clientID = "default"
            var mimo = ""
            val dollar = tail.indexOf('$')
            if (dollar >= 0) {
                mimo = tail.substring(dollar + 1)
                tail = tail.substring(0, dollar)
            }
            val percent = tail.indexOf('%')
            if (percent >= 0) {
                clientID = tail.substring(percent + 1).ifEmpty { "default" }
                tail = tail.substring(0, percent)
            }
            val key = tail.lowercase()
            if (key.length != 64 || !key.all { it in '0'..'9' || it in 'a'..'f' }) return null

            var transport = transportBlock
            var fps: Int? = null
            var batch: Int? = null
            var seiFPS: Int? = null
            var seiBatch: Int? = null
            var seiFrag: Int? = null
            var seiACK: Int? = null
            val open = transportBlock.indexOfFirst { it == '[' || it == '<' }
            if (open >= 0) {
                transport = transportBlock.substring(0, open)
                val close = transportBlock.indexOfLast { it == ']' || it == '>' }
                // Bare fps/batch belong to the transport: SEI's own numbers on a seichannel link, else VP8's (older links), as iOS reads them.
                val sei = transport.lowercase() == "seichannel"
                if (close > open) {
                    for (pair in transportBlock.substring(open + 1, close).split(',', '&')) {
                        val eq = pair.indexOf('=')
                        if (eq <= 0) continue
                        val value = pair.substring(eq + 1).trim().toIntOrNull() ?: continue
                        when (pair.substring(0, eq).trim()) {
                            "vp8-fps" -> fps = value
                            "vp8-batch" -> batch = value
                            "fps" -> if (sei) seiFPS = value else fps = value
                            "batch" -> if (sei) seiBatch = value else batch = value
                            "frag" -> if (sei) seiFrag = value
                            "ack-ms" -> if (sei) seiACK = value
                        }
                    }
                }
            }
            transport = transport.lowercase()
            if (carrier.isEmpty() || transport.isEmpty() || roomID.isEmpty()) return null
            return OlcrtcLink(carrier, transport, roomID, key, clientID, fps, batch, mimo,
                seiFPS = seiFPS, seiBatch = seiBatch, seiFrag = seiFrag, seiACK = seiACK)
        }

        fun carrierLabel(carrier: String): String = when (carrier) {
            "telemost" -> "Yandex Telemost"
            "vkcalls" -> "VK Calls"
            "salutejazz" -> "SaluteJazz"
            "wbstream" -> "WB Stream"
            "jitsi" -> "Jitsi"
            "mtslink" -> "MTS Link"
            else -> carrier
        }

        fun transportLabel(transport: String): String = when (transport) {
            "datachannel" -> "DataChannel"
            "vp8channel" -> "VP8"
            "seichannel" -> "SEI"
            "videochannel" -> "Video"
            else -> transport
        }

        /** Splits "host:port" (IPv6 in brackets); null when malformed. */
        fun splitEndpoint(endpoint: String): Pair<String, Int>? {
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0) return null
            val port = endpoint.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            var host = endpoint.substring(0, colon)
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
            return if (host.isEmpty()) null else host to port
        }
    }

    /** The plain link of the primary room, as the app writes it. */
    fun encode(): String {
        val params = buildList {
            vp8FPS?.let { add("vp8-fps=$it") }
            vp8Batch?.let { add("vp8-batch=$it") }
            // Bare keys read as VP8 on any other transport: SEI's numbers go out only on a seichannel link.
            if (transport == "seichannel") {
                seiFPS?.let { add("fps=$it") }
                seiBatch?.let { add("batch=$it") }
                seiFrag?.let { add("frag=$it") }
                seiACK?.let { add("ack-ms=$it") }
            }
        }
        val block = if (params.isEmpty()) "" else "[" + params.joinToString(",") + "]"
        val client = if (clientID == "default" || clientID.isEmpty()) "" else "%$clientID"
        return "$SCHEME$carrier?$transport$block@$roomID#$key$client"
    }
}

/** What a connection is made of: an olcrtc room (or a whole server of them), or an Xray server. */
@Serializable
sealed class Details {
    @Serializable
    @SerialName("olcrtc")
    data class Olcrtc(val link: OlcrtcLink) : Details()

    @Serializable
    @SerialName("xray")
    data class Xray(val link: XrayLink) : Details()
}

/** A saved connection: its name, what it is and, for a subscription's node, where it came from. */
@Serializable
data class ConnectionRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val details: Details,
    /** The subscription link the record was imported from; null for a pasted one. */
    val subSource: String? = null,
    /** The node's identity inside that list, so a refresh updates it in place. */
    val subNodeKey: String? = null,
) {
    val olcrtc: OlcrtcLink? get() = (details as? Details.Olcrtc)?.link
    val xray: XrayLink? get() = (details as? Details.Xray)?.link

    val displayName: String
        get() = name.ifBlank { olcrtc?.serviceName ?: xray?.fallbackName ?: "" }

    /** The services the connection goes through, for the notification. */
    val servicesLine: String
        get() = olcrtc?.servicesLine ?: "Xray"
}
