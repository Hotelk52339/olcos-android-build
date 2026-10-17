package io.olcos.app.model

import android.util.Base64
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A device invite: the phone that manages a server publishes an encrypted
 * subscription for another device and keeps it current. The link is
 *
 *     olcrtc-sub://<host>:<port>/<token>?enc=v1#<key>
 *
 * The server serves the file `<token>` over plain HTTP; what it serves is
 * AES-GCM ciphertext, and the key exists only in the link's fragment, which
 * no request carries. The same format as the iOS app's DeviceInvite.
 */
data class InviteLink(val host: String, val port: Int, val token: String, val key: ByteArray?) {
    /** The link without its key: what is stored and shown. */
    val source: String get() = source(host, port, token)

    val url: String get() = "http://" + (if (host.contains(":")) "[$host]" else host) + ":$port/$token"

    companion object {
        const val SCHEME = "olcrtc-sub"
        const val VERSION = "v1"

        fun source(host: String, port: Int, token: String): String {
            val shown = if (host.contains(":")) "[$host]" else host
            return "$SCHEME://$shown:$port/$token?enc=$VERSION"
        }

        /** Whether a subscription link, with or without its key, is an invite. */
        fun isInvite(link: String): Boolean = parse(link) != null

        /** The link's parts; the key is null when the link carries none. Null for anything but an invite. */
        fun parse(link: String): InviteLink? {
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val query = uri.rawQuery ?: return null
            if (query.split('&').none { it == "enc=$VERSION" }) return null
            var host = uri.host ?: return null
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
            val port = uri.port
            if (host.isEmpty() || port !in 1..65535) return null
            val token = (uri.rawPath ?: "").trimStart('/')
            if (token.length != 32 || !token.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val key = uri.rawFragment?.let { decodeKey(it) }
            return InviteLink(host, port, token, key)
        }

        fun decodeKey(text: String): ByteArray? {
            val bytes = runCatching { Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull()
            return bytes?.takeIf { it.size == 32 }
        }

        fun encodeKey(key: ByteArray): String = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    override fun equals(other: Any?): Boolean = other is InviteLink && other.source == source
    override fun hashCode(): Int = source.hashCode()
}

/** The AES-GCM wrapping of an invite list: `olcos1:` + base64(nonce ‖ ciphertext ‖ tag), AAD `olcos-invite-v1`. */
object InviteCipher {
    const val PREFIX = "olcos1:"
    private val associatedData = "olcos-invite-v1".toByteArray(Charsets.UTF_8)

    class NotAnInvite : Exception("not an invite list")
    class WrongKey : Exception("the key does not fit the list")

    fun open(served: String, key: ByteArray): String {
        val text = served.trim()
        if (!text.startsWith(PREFIX)) throw NotAnInvite()
        val combined = runCatching { Base64.decode(text.substring(PREFIX.length), Base64.DEFAULT) }.getOrNull()
            ?: throw NotAnInvite()
        if (combined.size < 12 + 16) throw NotAnInvite()
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, combined, 0, 12))
            cipher.updateAAD(associatedData)
            cipher.doFinal(combined, 12, combined.size - 12)
        } catch (e: Exception) {
            throw WrongKey()
        }
        return String(plain, Charsets.UTF_8)
    }
}
