package io.olcos.app.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The MTS Link sign-in the phone kept once, for minting MTS Link rooms:
 * the my.mts-link.ru cookie jar the sign-in page holds, as one `Cookie`
 * header string. No public call says whether a jar is the account's or a
 * guest's, so the person says when they are signed in — the sheet's "Use
 * this account" — and the daily check only reports whether the service
 * still answers the jar. It is a bearer credential: it lives encrypted in
 * a store of its own and never reaches the log.
 */
class MTSLinkSession(context: Context) {
    private val prefs = SecurePrefs.open(context, "olcos.mtslink", KEY_SESSION)

    /** The saved sign-in: the whole my.mts-link.ru jar, as VK's is kept. */
    val credential: String? get() = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotEmpty() }
    val isSignedIn: Boolean get() = credential != null

    private val _signedIn = MutableStateFlow(isSignedIn)
    /** Whether a session is kept, for the Settings row that offers to sign out. */
    val signedIn: StateFlow<Boolean> = _signedIn

    /** Keeps the jar [credentialOf] shapes; anything that is no session is ignored. */
    fun store(credential: String) {
        val kept = credentialOf(credential) ?: return
        prefs.edit().putString(KEY_SESSION, kept).apply()
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
        private const val KEY_SESSION = "session"
        /** The idiom for a deleted cookie: the value survives in the jar, but it is worth nothing. */
        private const val DELETED = "DELETED"

        /** Where the sign-in page's jar is read from. */
        const val JAR_URL = "https://my.mts-link.ru"

        /**
         * PURE: the credential out of a jar line: every pair that is fit to
         * go out again in a `Cookie` header (CookieLine), each name once and
         * in order. Unlike VK there is no one cookie that says "signed in"
         * — the service names no such thing — so the whole jar is the
         * credential and the person says when it holds an account. Null
         * when the line holds nothing worth keeping.
         */
        fun jar(line: String?): String? {
            val kept = LinkedHashMap<String, String>()
            for ((name, value) in CookieLine.pairs(line)) {
                if (value.isEmpty() || value == DELETED) continue
                if (!CookieLine.safeName(name) || !CookieLine.safeValue(value)) continue
                if (name !in kept) kept[name] = value
            }
            if (kept.isEmpty()) return null
            return kept.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        }

        /** PURE: what [store] keeps of a credential, or null when it is no session. */
        fun credentialOf(value: String): String? = jar(value)
    }
}
