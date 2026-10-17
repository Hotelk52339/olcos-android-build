package io.olcos.app.core

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Proves a session carries traffic before the app calls it connected: one
 * HTTPS request through the SOCKS port, the way the iOS app's verifyTunnel
 * does. The SOCKS handshake is spoken here, by name, so the core resolves the
 * host through the tunnel and the device's own DNS never decides the answer.
 */
object TunnelProbe {
    private val targets = listOf(
        "cp.cloudflare.com" to "/generate_204",
        "www.gstatic.com" to "/generate_204",
    )

    fun verify(port: Int, user: String = "", pass: String = "", timeoutMs: Int = 15_000): Boolean =
        targets.any { (host, path) ->
            runCatching { request(port, host, path, user, pass, timeoutMs) }
                .onFailure { AppLog.w(TAG, "traffic check via $host failed: ${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault(false)
        }

    private const val TAG = "olcos.probe"

    /** One HTTPS GET through the proxy; the status line, or null when nothing came back. */
    fun request(port: Int, host: String, path: String, user: String, pass: String, timeoutMs: Int): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(Loopback.address, port), 3_000)
            socket.soTimeout = timeoutMs
            Socks5.connect(socket, host, 443, user, pass)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(socket, host, 443, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.startHandshake()
            tls.outputStream.write(
                "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: olcOS-android\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            val head = ByteArray(64)
            val n = tls.inputStream.read(head)
            if (n <= 0) return false
            val line = String(head, 0, n, Charsets.ISO_8859_1)
            return line.startsWith("HTTP/1.1 2") || line.startsWith("HTTP/1.0 2")
        }
    }
}
