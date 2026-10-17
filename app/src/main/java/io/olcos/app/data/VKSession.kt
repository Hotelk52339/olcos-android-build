package io.olcos.app.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The VK session the phone signed in with once, for minting VK calls: the
 * vk.ru cookie jar the sign-in page holds, as one `Cookie` header string
 * (`remixsid=…; other=…`, the session cookie first). VK's web client sends
 * its whole jar, and the `remixsid` value alone is not enough for a real
 * sign-in; earlier builds kept only that bare value, and such a saved value
 * keeps working. It is a bearer credential; it lives encrypted in a store of
 * its own and never reaches the log.
 */
class VKSession(context: Context) {
    /** Where the sign-in page stands, for the status line under its hint. */
    enum class SignInStatus { NO_SESSION, CHECKING, REFUSED }

    private val prefs = SecurePrefs.open(context, "olcos.vk", KEY_SESSION)

    /**
     * The saved credential, named for the cookie it began as: a jar
     * (`remixsid=…; other=…`), or the bare `remixsid` value an earlier build
     * kept. `RoomServices.VKCall.cookieHeader` is the one place that tells
     * the two apart.
     */
    val remixSid: String? get() = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotEmpty() }
    val isSignedIn: Boolean get() = remixSid != null

    private val _signedIn = MutableStateFlow(isSignedIn)
    /** Whether a session is kept, for the Settings row that offers to sign out. */
    val signedIn: StateFlow<Boolean> = _signedIn

    /** Keeps a jar or a bare `remixsid` value (see [credentialOf]); anything that is no session is ignored and never replaces a live one. */
    fun store(credential: String) {
        // VK deletes a cookie as `remixsid=DELETED`; that value is never a
        // session and must not replace a live one.
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
        private const val KEY_SESSION = "remixsid"
        /** VK's idiom for a deleted cookie: the value survives in the jar, but it is worth nothing. */
        private const val DELETED = "DELETED"
        /** The guest stub a signed-out page can hold in place of a session. */
        private const val NOAUTH = "noauth"

        /** Where the sign-in page's jar is read from. */
        const val JAR_URL = "https://vk.ru"
        /** A jar is asked about no sooner than this after the last attempt. */
        const val RETRY_GAP_MS = 3_000L

        /** The cookie's value out of a `name=value; …` jar line: neither a `noauth` stub nor the deletion idiom `DELETED` is a session. */
        fun cookieValue(line: String?): String? =
            YandexSession.cookieValue(line, KEY_SESSION)?.takeIf { it != DELETED }

        /**
         * PURE: the credential out of the web view's jar line, or null when
         * the line holds no session. It is the whole vk.ru jar, as VK's web
         * client sends it, with `remixsid` first: every pair that is usable.
         * A pair is dropped when its value is empty or VK's `DELETED`, or
         * when its name or value is not fit for a header (controls,
         * whitespace, `;`, `,`, `"`, `\`, anything but printable ASCII); of
         * two pairs with one name the first stays. The session cookie has to
         * survive all of that, and must not be a `noauth` stub.
         */
        fun jar(line: String?): String? {
            val kept = LinkedHashMap<String, String>()
            for ((name, value) in CookieLine.pairs(line)) {
                if (value.isEmpty() || value == DELETED) continue
                if (!CookieLine.safeName(name) || !CookieLine.safeValue(value)) continue
                if (name !in kept) kept[name] = value
            }
            val session = kept[KEY_SESSION] ?: return null
            if (session.startsWith(NOAUTH)) return null
            return buildString {
                append(KEY_SESSION).append('=').append(session)
                for ((name, value) in kept) {
                    if (name != KEY_SESSION) append("; ").append(name).append('=').append(value)
                }
            }
        }

        /**
         * PURE: what [store] keeps of a credential. One with an `=` is a
         * jar, kept as [jar] shapes it; one without is a bare value, as
         * earlier builds saved `remixsid`, kept as it is when it would pass
         * as that cookie's value. Null when it is no session.
         */
        fun credentialOf(value: String): String? {
            val text = value.trim()
            if ('=' in text) return jar(text)
            return text.takeIf { jar("$KEY_SESSION=$it") != null }
        }

        /**
         * PURE: what makes one jar a new thing to ask VK about: the `remixsid`
         * value and the set of cookie names. The other values move on every
         * page load and prove nothing; a new name is the sign-in landing its
         * companion cookies. Held in memory by the sign-in sheet only.
         */
        fun refusalKey(jar: String): String {
            val pairs = CookieLine.pairs(jar)
            val session = pairs.firstOrNull { it.first == KEY_SESSION }?.second.orEmpty()
            return session + "|" + pairs.map { it.first }.toSortedSet().joinToString(",")
        }

        /**
         * PURE: whether the sign-in sheet asks VK about this jar now. Not
         * while it is the jar VK last refused (`refusedKey`) — a refusal
         * stands until the jar changes, which is exactly what a real sign-in
         * does to it — and never sooner than [RETRY_GAP_MS] after the last
         * attempt (`lastAttemptMs`, null before the first). An attempt that
         * got no answer leaves no refusal behind, so its jar is tried again
         * after the gap.
         */
        fun shouldVerify(jar: String, refusedKey: String?, lastAttemptMs: Long?, nowMs: Long): Boolean {
            if (refusedKey != null && refusalKey(jar) == refusedKey) return false
            return lastAttemptMs == null || nowMs - lastAttemptMs >= RETRY_GAP_MS
        }

        /**
         * PURE: where the sign-in page stands. No session cookie yet: the
         * person has to sign in on the page. A jar VK has not refused is being
         * checked (or waits out the gap before the next try). The jar VK last
         * refused stays refused until it changes.
         */
        fun signInStatus(jar: String?, refusedKey: String?): SignInStatus = when {
            jar == null -> SignInStatus.NO_SESSION
            refusedKey != null && refusalKey(jar) == refusedKey -> SignInStatus.REFUSED
            else -> SignInStatus.CHECKING
        }
    }
}
