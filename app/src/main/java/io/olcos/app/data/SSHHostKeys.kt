package io.olcos.app.data

import android.content.Context

/**
 * The SSH host keys this phone has seen, one per server address and key
 * type: the first connection trusts and remembers the key, a later
 * connection that meets a different one is refused (SSHClient.HostKeyChanged).
 */
class SSHHostKeys(context: Context) {
    private val prefs = context.getSharedPreferences("olcos.ssh.hostkeys", Context.MODE_PRIVATE)

    /** The remembered key (base64) of this type for the address, or null when none was seen. */
    fun known(host: String, port: Int, type: String): String? = prefs.getString(key(host, port, type), null)

    fun remember(host: String, port: Int, type: String, keyBase64: String) {
        prefs.edit().putString(key(host, port, type), keyBase64).apply()
    }

    /** Forgets every key of the address, for a server that was reinstalled on purpose. */
    fun forget(host: String, port: Int) {
        val prefix = "$host:$port/"
        val edit = prefs.edit()
        for (name in prefs.all.keys) if (name.startsWith(prefix)) edit.remove(name)
        edit.apply()
    }

    private fun key(host: String, port: Int, type: String): String = "$host:$port/$type"
}
