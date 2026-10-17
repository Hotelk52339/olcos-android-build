package io.olcos.app.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * One HTTPS request to a server agent over TLS that trusts exactly the pinned
 * certificate (the SHA-256 of its DER, lowercase hex), with no hostname
 * check: the certificate is self-signed for an IP and the pin is the whole of
 * its trust. One request per connection (`Connection: close`). The tunnel
 * route dials through the live session's SOCKS port, by address, the way the
 * iOS AgentHTTPS does.
 */
object AgentHTTPS {
    class Response(val status: Int, val body: ByteArray)

    class PinMismatch : IOException("the server's certificate does not match the pin")

    /** The request went out and no answer came back: the agent may have acted on it. */
    class AnswerLost(cause: IOException) : IOException("no answer after the request went out: ${cause.message}", cause)

    fun pinOf(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** The request bytes: HTTP/1.1, the signing headers, a body with its length. */
    fun request(method: String, path: String, host: String, port: Int, headers: List<Pair<String, String>>, body: ByteArray): ByteArray {
        val shownHost = if (host.contains(":")) "[$host]" else host
        val head = StringBuilder()
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        head.append("Host: ").append(shownHost).append(':').append(port).append("\r\n")
        for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
        if (body.isNotEmpty()) head.append("Content-Type: application/json\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    /** The live session's SOCKS port and the login it asks for, for the tunnel route. */
    class Socks(val port: Int, val user: String = "", val pass: String = "")

    /** Sends `request` to host:port, straight or through the SOCKS port of the live session. */
    fun send(host: String, port: Int, pinHex: String, request: ByteArray, socks: Socks?, timeoutMs: Int): Response {
        val pin = pinHex.lowercase()
        val plain = Socket()
        try {
            if (socks != null) {
                plain.connect(InetSocketAddress(Loopback.address, socks.port), 3_000)
                plain.soTimeout = timeoutMs
                Socks5.connect(plain, host, port, socks.user, socks.pass)
            } else {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                plain.soTimeout = timeoutMs
            }
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || pinOf(chain[0].encoded) != pin) throw CertificateException("pin mismatch")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trust), null)
            val tls = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            tls.soTimeout = timeoutMs
            try {
                tls.startHandshake()
            } catch (e: Exception) {
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause.message?.contains("pin mismatch") == true) throw PinMismatch()
                    cause = cause.cause
                }
                throw e
            }
            tls.outputStream.write(request)
            tls.outputStream.flush()
            return try {
                readResponse(tls.inputStream)
            } catch (e: IOException) {
                throw AnswerLost(e)
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    /** Reads a whole `Connection: close` HTTP/1.1 answer (at most 1 MiB): status and body. */
    internal fun readResponse(input: InputStream): Response {
        val all = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            all.write(buffer, 0, n)
            if (all.size() > 1 shl 20) throw IOException("the answer is too large")
        }
        val bytes = all.toByteArray()
        val headerEnd = indexOf(bytes, "\r\n\r\n".toByteArray())
        if (headerEnd < 0) throw IOException("malformed answer")
        val head = String(bytes, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val status = lines[0].split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("malformed status line")
        var body = bytes.copyOfRange(headerEnd + 4, bytes.size)
        val chunked = lines.drop(1).any { it.lowercase().startsWith("transfer-encoding:") && it.lowercase().contains("chunked") }
        if (chunked) body = dechunk(body)
        val length = lines.drop(1).firstOrNull { it.lowercase().startsWith("content-length:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
        if (length != null && length < body.size) body = body.copyOfRange(0, length)
        return Response(status, body)
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            val lineEnd = indexOf(data, "\r\n".toByteArray(), pos)
            if (lineEnd < 0) break
            val size = String(data, pos, lineEnd - pos, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            if (start + size > data.size) break
            out.write(data, start, size)
            pos = start + size + 2
        }
        return out.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        var i = from
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

}
