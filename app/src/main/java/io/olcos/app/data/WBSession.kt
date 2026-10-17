package io.olcos.app.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The WB Stream session the phone signed in with once, for minting WB Stream
 * rooms: the `wb_access_token` cookie. It is a bearer credential (the same
 * value the server takes as `auth.token`); it lives encrypted in a store of
 * its own and never reaches the log.
 */
class WBSession(context: Context) {
    private val prefs = SecurePrefs.open(context, "olcos.wb", KEY_TOKEN)

    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
    val isSignedIn: Boolean get() = token != null

    private val _signedIn = MutableStateFlow(isSignedIn)
    /** Whether a session is kept, for the Settings row that offers to sign out. */
    val signedIn: StateFlow<Boolean> = _signedIn

    fun store(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _signedIn.value = true
        // The web view's jar keeps its own copy of the sign-in; flush it to
        // disk, or a process death before the jar's own write loses it and
        // the next sign-in starts from scratch.
        runCatching { CookieManager.getInstance().flush() }
    }

    fun clear() {
        prefs.edit().clear().apply()
        _signedIn.value = false
    }

    companion object {
        private const val KEY_TOKEN = "wb_access_token"

        /**
         * The token's value out of the web view's jar. The domain rule is the
         * whole family the reference project accepts the cookie on: `wb.ru`
         * with its subdomains (`stream.wb.ru` included) and `wildberries.ru`
         * with its subdomains — the login may set the cookie on either, so
         * the jar is read for both hosts.
         */
        fun cookieValue(jarLineFor: (url: String) -> String?): String? {
            for (url in listOf("https://stream.wb.ru", "https://wildberries.ru")) {
                YandexSession.cookieValue(jarLineFor(url), KEY_TOKEN)?.let { return it }
            }
            return null
        }
    }
}
