package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What clearing a web sign-in works from: the cookie names the jar shows and the shapes a cookie can be stored under. */
class WebSessionsTest {
    @Test fun cookieNamesAreEveryNameOnceInOrder() {
        assertEquals(
            listOf("remixsid", "remixir", "remixlang"),
            WebSessions.cookieNames("remixsid=a; remixir=DELETED; remixlang=61; remixsid=b"),
        )
    }

    @Test fun cookieNamesIncludeCookiesWithAnEmptyValue() {
        assertEquals(listOf("remixsid", "remixgp"), WebSessions.cookieNames("remixsid=a; remixgp="))
    }

    @Test fun cookieNamesLeaveOutWhatIsNoName() {
        assertEquals(emptyList<String>(), WebSessions.cookieNames(null))
        assertEquals(emptyList<String>(), WebSessions.cookieNames(""))
        assertEquals(listOf("ok"), WebSessions.cookieNames("stray; =novalue; bad name=1; ok=1"))
    }

    @Test fun aCookieMayBelongToTheHostAloneOrToADomainAbove() {
        assertEquals(listOf<String?>(null, "vk.ru"), WebSessions.domainVariants("vk.ru"))
        assertEquals(listOf<String?>(null, "login.vk.ru", "vk.ru"), WebSessions.domainVariants("login.vk.ru"))
        assertEquals(listOf<String?>(null, "my.mts-link.ru", "mts-link.ru"), WebSessions.domainVariants("my.mts-link.ru"))
        assertEquals(listOf<String?>(null), WebSessions.domainVariants("localhost"))
    }

    @Test fun anExpiryLineDeletesTheNamedCookie() {
        assertEquals(
            "remixsid=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure",
            WebSessions.expiry("remixsid", null),
        )
        assertEquals(
            "remixsid=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=vk.ru",
            WebSessions.expiry("remixsid", "vk.ru"),
        )
    }

    @Test fun theHostsAreTheOnesEachSignInLeavesItsCookiesOn() {
        assertTrue("vk.ru" in WebSessions.VK_HOSTS)
    }

    // MARK: Yandex

    @Test fun theYandexHostsAreTheSignInPagesAndTheYandexDomains() {
        assertEquals(
            listOf("passport.yandex.ru", "telemost.yandex.ru", "yandex.ru", "yandex.com", "ya.ru", "yandex.net"),
            WebSessions.YANDEX_HOSTS,
        )
    }

    @Test fun everyYandexHostIsOneOfTheDomainsOrBelowIt() {
        val domains = listOf("yandex.ru", "yandex.com", "ya.ru", "yandex.net")
        for (domain in domains) assertTrue(domain, domain in WebSessions.YANDEX_HOSTS)
        for (host in WebSessions.YANDEX_HOSTS) assertTrue(host, domains.any { host == it || host.endsWith(".$it") })
    }

    @Test fun aYandexCookieMayBelongToThePageAloneOrToADomainAbove() {
        assertEquals(listOf<String?>(null, "passport.yandex.ru", "yandex.ru"), WebSessions.domainVariants("passport.yandex.ru"))
        assertEquals(listOf<String?>(null, "yandex.ru"), WebSessions.domainVariants("yandex.ru"))
        assertEquals(listOf<String?>(null, "ya.ru"), WebSessions.domainVariants("ya.ru"))
    }

    @Test fun theYandexJarLineGivesEveryNameOnce() {
        assertEquals(
            listOf("Session_id", "sessionid2", "yandexuid", "_ym_uid"),
            WebSessions.cookieNames("Session_id=3:abc; sessionid2=3:def; yandexuid=1; yandexuid=2; _ym_uid=3"),
        )
    }

    @Test fun theYandexSessionCookieIsExpiredUnderEveryShapeItCanHave() {
        val lines = WebSessions.domainVariants("passport.yandex.ru").map { WebSessions.expiry("Session_id", it) }
        assertEquals(
            listOf(
                "Session_id=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure",
                "Session_id=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=passport.yandex.ru",
                "Session_id=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=yandex.ru",
            ),
            lines,
        )
    }
}
