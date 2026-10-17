package io.olcos.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Yandex session the phone signed in with once, for minting Telemost
 * rooms: the `Session_id` cookie and its HTTPS twin `sessionid2`. Both are
 * bearer credentials; they live in a private preferences file of their own
 * and never reach the log.
 */
class YandexSession(context: Context) {
    private val prefs = context.getSharedPreferences("olcos.yandex", Context.MODE_PRIVATE)

    val session: String? get() = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotEmpty() }
    val twin: String? get() = prefs.getString(KEY_TWIN, null)?.takeIf { it.isNotEmpty() }
    val isSignedIn: Boolean get() = session != null

    private val _signedIn = MutableStateFlow(isSignedIn)
    /** Whether a session is kept, for the Settings row that offers to sign out. */
    val signedIn: StateFlow<Boolean> = _signedIn

    fun store(session: String, twin: String?) {
        prefs.edit().putString(KEY_SESSION, session).putString(KEY_TWIN, twin).apply()
        _signedIn.value = true
    }

    fun clear() {
        prefs.edit().clear().apply()
        _signedIn.value = false
    }

    companion object {
        private const val KEY_SESSION = "session"
        private const val KEY_TWIN = "twin"

        /** Picks a cookie's value out of a `name=value; name=value` line, under the rule the web view's jar sets it: never a `noauth` stub. */
        fun cookieValue(line: String?, name: String): String? {
            if (line.isNullOrEmpty()) return null
            for (part in line.split(';')) {
                val pair = part.trim()
                val eq = pair.indexOf('=')
                if (eq <= 0 || pair.substring(0, eq) != name) continue
                val value = pair.substring(eq + 1).trim()
                if (value.isEmpty() || value.startsWith("noauth")) return null
                return value
            }
            return null
        }
    }
}
