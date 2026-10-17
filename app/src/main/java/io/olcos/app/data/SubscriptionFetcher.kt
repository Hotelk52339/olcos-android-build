package io.olcos.app.data

import io.olcos.app.core.AgentHTTPS
import io.olcos.app.core.AppLog
import io.olcos.app.model.InviteCipher
import io.olcos.app.model.InviteLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Downloads a subscription list: over HTTPS, or an invite's encrypted file
 * over plain HTTP. When the phone's DNS gives no address for an HTTPS list's
 * host (a lookup the provider blocks), the address comes from DNS over HTTPS
 * instead and the list from that address, its certificate checked against the
 * name, as the iOS SubscriptionFetcher does.
 */
object SubscriptionFetcher {
    private const val TIMEOUT_MS = 20_000
    private const val DOH_TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 1 shl 20

    /**
     * JSON DNS-over-HTTPS resolvers, reached by address (their certificates
     * name the address). Google answers JSON at /resolve only.
     */
    private val DOH_ENDPOINTS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")

    class Revoked : IOException("the invite was revoked")

    /** The body of the list at `https`; throws with a short reason. */
    suspend fun fetch(https: String): String = get(https)

    /** The decrypted list behind an invite; a 404 means the invite was revoked. */
    suspend fun fetchInvite(invite: InviteLink, key: ByteArray): String {
        val served = try {
            get(invite.url)
        } catch (e: HttpStatus) {
            if (e.code == 404) throw Revoked() else throw e
        }
        return InviteCipher.open(served, key)
    }

    class HttpStatus(val code: Int) : IOException("HTTP $code")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try {
            direct(url)
        } catch (e: UnknownHostException) {
            // Only a name over HTTPS: the certificate is what proves the address right.
            val u = URL(url)
            if (!u.protocol.equals("https", ignoreCase = true) || isAddress(u.host)) throw e
            val ip = resolveOverHTTPS(u.host) ?: throw e
            AppLog.i("olcos.list", "${u.host}: no address from the phone's DNS, one from DNS over HTTPS")
            viaAddress(u, ip)
        }
    }

    private fun direct(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "text/plain,text/markdown,*/*")
            setRequestProperty("User-Agent", "olcOS-android")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatus(code)
            return text(connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
    }

    private fun text(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw IOException("the answer is too large for a list")
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) throw IOException("empty response")
        return text
    }

    /** The host's address from the first resolver that names it, IPv4 before IPv6; null when none does. */
    private fun resolveOverHTTPS(host: String): String? {
        for (endpoint in DOH_ENDPOINTS) {
            for (type in intArrayOf(TYPE_A, TYPE_AAAA)) {
                runCatching { queryDoH(endpoint, host, type) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    private fun queryDoH(endpoint: String, host: String, type: Int): String? {
        val url = URL(endpoint + "?name=" + URLEncoder.encode(host, "UTF-8") + "&type=" + type)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/dns-json")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            return answer(connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8), type)
        } finally {
            connection.disconnect()
        }
    }

    /** PURE: the first address of [type] in a JSON DNS answer; null when it holds none. */
    fun answer(json: String, type: Int): String? {
        val list = runCatching { JSONObject(json).optJSONArray("Answer") }.getOrNull() ?: return null
        for (i in 0 until list.length()) {
            val record = list.optJSONObject(i) ?: continue
            if (record.optInt("type") != type) continue
            val data = record.optString("data")
            if (data.isNotBlank()) return data
        }
        return null
    }

    /**
     * One GET of [u] from [ip] over TLS: the name goes in SNI and the Host
     * header, and the certificate must be valid for it, as for any HTTPS
     * request. No redirects (the iOS fallback follows none either).
     */
    private fun viaAddress(u: URL, ip: String): String {
        val port = if (u.port > 0) u.port else 443
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(InetAddress.getByName(ip), port), TIMEOUT_MS)
            plain.soTimeout = TIMEOUT_MS
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, u.host, port, true) as SSLSocket
            runCatching { tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(u.host)) } }
            tls.soTimeout = TIMEOUT_MS
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, tls.session)) {
                throw SSLPeerUnverifiedException("the certificate is not for ${u.host}")
            }
            val path = u.path.ifEmpty { "/" } + (u.query?.let { "?$it" } ?: "")
            val hostHeader = if (u.port > 0 && u.port != 443) "${u.host}:${u.port}" else u.host
            val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nAccept: text/plain,text/markdown,*/*\r\n" +
                "User-Agent: olcOS-android\r\nConnection: close\r\n\r\n"
            tls.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
            tls.outputStream.flush()
            val response = AgentHTTPS.readResponse(tls.inputStream)
            if (response.status !in 200..299) throw HttpStatus(response.status)
            return text(response.body)
        } finally {
            runCatching { plain.close() }
        }
    }

    /** Whether [host] is an address already: nothing for a resolver to name. */
    private fun isAddress(host: String): Boolean =
        host.contains(':') || host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } } }
}
