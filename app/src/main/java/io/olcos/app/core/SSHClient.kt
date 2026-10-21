package io.olcos.app.core

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.olcos.app.data.SSHHostKeys
import io.olcos.app.data.ServerSecrets
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * One SSH connection to a server, for the install of the server and the
 * agent (ServerInstall): the iOS app's SSHRunner, on jsch. A password or a
 * pasted private key signs in; the host key is trusted on the first
 * connection and remembered per address (SSHHostKeys), and a changed one
 * is refused. Every command runs in its own exec channel on the one
 * connection; the caller closes it when the operation is over.
 */
class SSHClient private constructor(private val session: Session, val host: String, val port: Int, val user: String) {
    /** The server presents a key other than the remembered one. */
    class HostKeyChanged(host: String) : IOException("the SSH host key of $host changed")

    /** The server refused the password or the key. */
    class AuthFailed : IOException("the login was not accepted")

    class ConnectFailed(message: String, cause: Throwable?) : IOException(message, cause)

    class CommandTimeout(seconds: Int) : IOException("no answer from the command in $seconds s")

    val isConnected: Boolean get() = session.isConnected

    /**
     * Runs a command (a whole script is fine: the remote shell takes the
     * text) and returns its exit status with stdout and stderr merged.
     */
    fun exec(command: String, timeoutSeconds: Int): Pair<Int, String> {
        val channel = session.openChannel("exec") as ChannelExec
        val out = ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setInputStream(null)
        channel.setOutputStream(out)
        channel.setErrStream(out)
        try {
            channel.connect(CONNECT_TIMEOUT_MS)
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) throw CommandTimeout(timeoutSeconds)
                Thread.sleep(100)
            }
            return channel.exitStatus to out.toString("UTF-8")
        } finally {
            channel.disconnect()
        }
    }

    fun close() {
        runCatching { session.disconnect() }
    }

    /** Trust on first use: the key of each type is kept per address; a different one later is a change. */
    private class TrustOnFirstUse(private val store: SSHHostKeys, private val host: String, private val port: Int) : HostKeyRepository {
        @Volatile
        var changed = false

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            val presented = HostKey(this.host, key)
            val known = store.known(this.host, port, presented.type) ?: return HostKeyRepository.NOT_INCLUDED
            if (known == presented.key) return HostKeyRepository.OK
            changed = true
            return HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {
            store.remember(host, port, hostkey.type, hostkey.key)
        }

        override fun remove(host: String?, type: String?) {}

        override fun remove(host: String?, type: String?, key: ByteArray?) {}

        override fun getKnownHostsRepositoryID(): String = "olcos"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers jsch's prompts: a new host key is accepted, a changed one is not; the password serves keyboard-interactive too. */
    private class Prompts(private val password: String?, private val keys: TrustOnFirstUse) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null

        override fun getPassword(): String? = password

        override fun promptPassword(message: String?): Boolean = password != null

        override fun promptPassphrase(message: String?): Boolean = false

        override fun promptYesNo(message: String?): Boolean = !keys.changed

        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<String>?, echo: BooleanArray?,
        ): Array<String>? {
            val pw = password ?: return null
            val count = prompt?.size ?: return null
            return Array(count) { pw }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * Connects and signs in; throws HostKeyChanged, AuthFailed or
         * ConnectFailed (whose cause names the network failure).
         */
        fun connect(context: Context, host: String, port: Int, user: String, secret: ServerSecrets.Secret): SSHClient {
            val jsch = JSch()
            val keys = TrustOnFirstUse(SSHHostKeys(context.applicationContext), host, port)
            jsch.setHostKeyRepository(keys)
            val password = (secret as? ServerSecrets.Secret.Password)?.password
            try {
                if (secret is ServerSecrets.Secret.Key) {
                    val passphrase = secret.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                    jsch.addIdentity("olcos", normalizedKey(secret.privateKey).toByteArray(Charsets.UTF_8), null, passphrase)
                }
                val session = jsch.getSession(user, host, port)
                session.setConfig("StrictHostKeyChecking", "ask")
                session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                if (password != null) session.setPassword(password)
                session.setUserInfo(Prompts(password, keys))
                session.setServerAliveInterval(15_000)
                session.setServerAliveCountMax(4)
                session.connect(CONNECT_TIMEOUT_MS)
                return SSHClient(session, host, port, user)
            } catch (e: JSchException) {
                if (keys.changed) throw HostKeyChanged(host)
                val text = e.message.orEmpty()
                if (text.startsWith("Auth ") || text.contains("USERAUTH fail")) throw AuthFailed()
                throw ConnectFailed(text.ifEmpty { e.javaClass.simpleName }, e.cause)
            }
        }

        /** A pasted key: Windows line ends and a missing final newline are the usual damage. */
        private fun normalizedKey(text: String): String = text.trim().replace("\r\n", "\n").replace('\r', '\n') + "\n"
    }
}
