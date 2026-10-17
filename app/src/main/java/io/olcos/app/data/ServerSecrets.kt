package io.olcos.app.data

import android.content.Context
import android.util.Base64

/**
 * The secrets of the servers this device manages over SSH, and the keys of
 * the device invites it published: a private preferences file of their own,
 * never in a log or a list.
 */
class ServerSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("olcos.secrets", Context.MODE_PRIVATE)

    sealed class Secret {
        data class Password(val password: String) : Secret()
        data class Key(val privateKey: String, val passphrase: String?) : Secret()
    }

    fun secret(serverID: String): Secret? {
        prefs.getString("key:$serverID", null)?.takeIf { it.isNotEmpty() }?.let { return Secret.Key(it, prefs.getString("passphrase:$serverID", null)) }
        prefs.getString("password:$serverID", null)?.let { return Secret.Password(it) }
        return null
    }

    fun store(serverID: String, secret: Secret) {
        val edit = prefs.edit().remove("key:$serverID").remove("passphrase:$serverID").remove("password:$serverID")
        when (secret) {
            is Secret.Password -> edit.putString("password:$serverID", secret.password)
            is Secret.Key -> edit.putString("key:$serverID", secret.privateKey).putString("passphrase:$serverID", secret.passphrase)
        }
        edit.apply()
    }

    fun forget(serverID: String) {
        prefs.edit().remove("key:$serverID").remove("passphrase:$serverID").remove("password:$serverID").apply()
    }

    fun inviteKey(token: String): ByteArray? =
        prefs.getString("invite:$token", null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }?.takeIf { it.size == 32 }

    fun storeInviteKey(token: String, key: ByteArray) {
        prefs.edit().putString("invite:$token", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    fun forgetInviteKey(token: String) {
        prefs.edit().remove("invite:$token").apply()
    }
}
