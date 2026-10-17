package io.olcos.app.data

import android.webkit.CookieManager
import android.webkit.WebStorage
import io.olcos.app.core.AppLog

/**
 * The web view's own copy of a sign-in: the cookies and page storage the
 * sign-in pages leave in its jar. They keep a page signed in whether or not
 * the app holds a session, so signing out for real means clearing them as
 * well as the saved one. A jar line does not say which domain and path a
 * cookie was stored under, so each name is expired under every shape it can
 * have; if that misses, the whole jar goes rather than leave a page signed in.
 * Page storage always goes whole: a sign-in page may keep its login there
 * instead of in a cookie, or sign itself back in from it, and deleting one
 * origin's storage is not guaranteed to reach that. The web view shows
 * nothing but these sign-in pages, so nothing else is lost.
 */
object WebSessions {
    private const val LOG_TAG = "olcos.sessions"

    /** The hosts the VK sign-in leaves its cookies on: the page and its login services. */
    val VK_HOSTS = listOf("vk.ru", "login.vk.ru", "id.vk.ru")
    /** The hosts the WB Stream sign-in may leave its cookies and storage on: the family the reference project accepts the token on. */
    val WB_HOSTS = listOf("stream.wb.ru", "wb.ru", "wildberries.ru")

    /** Every cookie and all page storage, for every site: where the sign-in pages left their copies, gone, so the next page starts signed out. */
    fun clearAll() {
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    /** The vk.ru cookies and the page storage; the whole jar if the VK session is still in it afterwards. */
    fun clearVK() {
        clear(VK_HOSTS)
        val left = runCatching { VKSession.jar(CookieManager.getInstance().getCookie(VKSession.JAR_URL)) }.getOrNull()
        if (left != null) {
            AppLog.w(LOG_TAG, "the VK cookies outlived their removal one by one; clearing the whole web view jar")
            clearAll()
        }
    }

    /** The cookies of stream.wb.ru, wb.ru and wildberries.ru and the page storage; the whole jar if the WB token cookie is still there afterwards. */
    fun clearWB() {
        clear(WB_HOSTS)
        val left = runCatching { WBSession.cookieValue { CookieManager.getInstance().getCookie(it) } }.getOrNull()
        if (left != null) {
            AppLog.w(LOG_TAG, "the WB cookies outlived their removal one by one; clearing the whole web view jar")
            clearAll()
        }
    }

    private fun clear(hosts: List<String>) {
        runCatching {
            val cookies = CookieManager.getInstance()
            for (host in hosts) {
                val url = "https://$host"
                for (name in cookieNames(cookies.getCookie(url))) {
                    for (domain in domainVariants(host)) cookies.setCookie(url, expiry(name, domain))
                }
            }
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
        }.onFailure { AppLog.w(LOG_TAG, "the web sign-in could not be cleared: ${it.message}") }
    }

    /** PURE: the cookie names a jar line holds, each once and in order; a name no cookie can have is left out. */
    fun cookieNames(line: String?): List<String> =
        CookieLine.pairs(line).map { it.first }.filter { CookieLine.safeName(it) }.distinct()

    /**
     * PURE: the domains a cookie seen at this host may have been stored
     * under: none (a cookie of that host alone), then the host itself and each
     * parent down to two labels (a domain cookie, shared with subdomains).
     */
    fun domainVariants(host: String): List<String?> {
        val labels = host.split('.')
        val out = ArrayList<String?>()
        out.add(null)
        for (i in 0..labels.size - 2) out.add(labels.drop(i).joinToString("."))
        return out
    }

    /** PURE: the Set-Cookie line that expires `name` under `domain` (null: the host alone). `Secure` lets it reach `__Secure-` and `__Host-` cookies too. */
    fun expiry(name: String, domain: String?): String =
        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure" + (if (domain != null) "; Domain=$domain" else "")
}
