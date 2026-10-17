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
        assertEquals(listOf<String?>(null, "stream.wb.ru", "wb.ru"), WebSessions.domainVariants("stream.wb.ru"))
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
        assertEquals(listOf("stream.wb.ru", "wb.ru", "wildberries.ru"), WebSessions.WB_HOSTS)
    }
}
