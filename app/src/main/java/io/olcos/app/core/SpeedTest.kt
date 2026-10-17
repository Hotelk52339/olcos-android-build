package io.olcos.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Measures the live session as it is, the way the iOS ProtocolSpeedCheck
 * does: through each room's own loopback port in turn (round trip, download
 * speed, the address the internet sees), then through the shared port, where
 * every room carries the download at once. Nothing reconnects.
 */
object SpeedTest {
    const val ALL_TOGETHER = -1
    private const val DOWNLOAD_SECONDS = 8
    private const val DOWNLOAD_HOST = "speed.cloudflare.com"
    private const val DOWNLOAD_PATH = "/__down?bytes=50000000"
    private const val PING_HOST = "cp.cloudflare.com"
    private const val PING_PATH = "/generate_204"

    enum class Phase { IDLE, WAITING, MEASURING, DONE, FAILED }

    data class Row(
        val id: Int,
        val name: String,
        val phase: Phase = Phase.IDLE,
        val pingMs: Long? = null,
        val bytesPerSecond: Double? = null,
        val exit: String? = null,
    )

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** The rows a session gets: one per lane, and all together when there are several. */
    fun targets(session: SessionInfo): List<Row> {
        if (session.lanePorts.size <= 1) return listOf(Row(ALL_TOGETHER, ""))
        return session.lanePorts.indices.map { i -> Row(i, session.laneNames.getOrElse(i) { "" }) } + Row(ALL_TOGETHER, "")
    }

    suspend fun run(session: SessionInfo) {
        if (_running.value) return
        _running.value = true
        try {
            _rows.value = targets(session).map { it.copy(phase = Phase.WAITING) }
            for (row in _rows.value) {
                val port = if (row.id == ALL_TOGETHER) session.port else session.lanePorts[row.id]
                update(row.id) { it.copy(phase = Phase.MEASURING) }
                val ping = withContext(Dispatchers.IO) { roundTrip(port, session.socksUser, session.socksPass) }
                update(row.id) { it.copy(pingMs = ping) }
                val rate = withContext(Dispatchers.IO) { download(port, session.socksUser, session.socksPass) }
                update(row.id) { it.copy(bytesPerSecond = rate) }
                val exit = withContext(Dispatchers.IO) { exitLine(port, session.socksUser, session.socksPass) }
                update(row.id) { it.copy(exit = exit, phase = if (ping == null && (rate ?: 0.0) <= 0) Phase.FAILED else Phase.DONE) }
                AppLog.i("olcos.speed", (row.name.ifEmpty { "together" }) + ": " + (ping?.let { "$it ms" } ?: "no ping") +
                    ", " + (rate?.let { "%.2f Mbit/s".format(it * 8 / 1_000_000) } ?: "no download") + (exit?.let { ", $it" } ?: ""))
            }
        } finally {
            _running.value = false
        }
    }

    private fun update(id: Int, change: (Row) -> Row) {
        _rows.value = _rows.value.map { if (it.id == id) change(it) else it }
    }

    /** The best of three round trips to a tiny page, in milliseconds. */
    private fun roundTrip(port: Int, user: String, pass: String): Long? {
        var best: Long? = null
        repeat(3) {
            val start = System.currentTimeMillis()
            val ok = runCatching { TunnelProbe.request(port, PING_HOST, PING_PATH, user, pass, 10_000) }.getOrDefault(false)
            if (ok) {
                val elapsed = System.currentTimeMillis() - start
                best = minOf(best ?: elapsed, elapsed)
            }
        }
        return best
    }

    /** Downloads for at most DOWNLOAD_SECONDS; the average in bytes per second, or null when nothing arrived. */
    private fun download(port: Int, user: String, pass: String): Double? = runCatching {
        open(port, user, pass, DOWNLOAD_HOST, 15_000) { tls ->
            tls.outputStream.write(
                "GET $DOWNLOAD_PATH HTTP/1.1\r\nHost: $DOWNLOAD_HOST\r\nConnection: close\r\nUser-Agent: olcOS-android\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            val input = tls.inputStream
            val buffer = ByteArray(64 * 1024)
            val start = System.currentTimeMillis()
            var total = 0L
            var headerSkipped = false
            while (System.currentTimeMillis() - start < DOWNLOAD_SECONDS * 1000L) {
                val n = input.read(buffer)
                if (n < 0) break
                if (!headerSkipped) {
                    val head = String(buffer, 0, n, Charsets.ISO_8859_1)
                    val end = head.indexOf("\r\n\r\n")
                    if (end >= 0) {
                        headerSkipped = true
                        total += n - (end + 4)
                    }
                    continue
                }
                total += n
            }
            val seconds = (System.currentTimeMillis() - start) / 1000.0
            if (total <= 0 || seconds <= 0) null else total / seconds
        }
    }.getOrNull()

    /** "City, CC · 203.0.113.7" from ipinfo.io, through the proxy. */
    private fun exitLine(port: Int, user: String, pass: String): String? = runCatching {
        open(port, user, pass, "ipinfo.io", 10_000) { tls ->
            tls.outputStream.write("GET /json HTTP/1.1\r\nHost: ipinfo.io\r\nAccept: application/json\r\nConnection: close\r\nUser-Agent: olcOS-android\r\n\r\n".toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            val text = readAll(tls.inputStream, 64 * 1024)
            val body = text.substringAfter("\r\n\r\n", "")
            val json = JSONObject(body.substring(body.indexOf('{')))
            val ip = json.optString("ip")
            val place = listOf(json.optString("city"), json.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
            if (ip.isEmpty()) null else if (place.isEmpty()) ip else "$place · $ip"
        }
    }.getOrNull()

    /**
     * Where traffic leaves the tunnel, for the Home screen: "🇳🇱 Amsterdam, NL"
     * from ipinfo.io through the proxy, or null when the lookup gave nothing.
     */
    fun exitPlace(port: Int, user: String, pass: String): String? = runCatching {
        open(port, user, pass, "ipinfo.io", 10_000) { tls ->
            tls.outputStream.write("GET /json HTTP/1.1\r\nHost: ipinfo.io\r\nAccept: application/json\r\nConnection: close\r\nUser-Agent: olcOS-android\r\n\r\n".toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            val body = readAll(tls.inputStream, 64 * 1024).substringAfter("\r\n\r\n", "")
            val json = JSONObject(body.substring(body.indexOf('{')))
            val country = json.optString("country")
            val place = listOf(json.optString("city"), country).filter { it.isNotEmpty() }.joinToString(", ")
            if (place.isEmpty()) null else (flag(country)?.let { "$it " } ?: "") + place
        }
    }.getOrNull()

    /** The flag emoji of a two-letter country code: two regional indicator letters. */
    private fun flag(code: String): String? {
        if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
        return code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    private fun <T> open(port: Int, user: String, pass: String, host: String, timeoutMs: Int, block: (SSLSocket) -> T): T {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(Loopback.address, port), 3_000)
            socket.soTimeout = timeoutMs
            Socks5.connect(socket, host, 443, user, pass)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(socket, host, 443, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.startHandshake()
            return block(tls)
        }
    }

    private fun readAll(input: InputStream, cap: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < cap) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString("ISO-8859-1")
    }
}
