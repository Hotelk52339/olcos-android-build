package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the web view's jar hands over, parsed for the cookies that matter. */
class YandexSessionTest {
    @Test fun picksTheNamedCookie() {
        assertEquals("abc123", YandexSession.cookieValue("Session_id=abc123; yandexid=q", "Session_id"))
        assertEquals("twin1", YandexSession.cookieValue("a=1; sessionid2=twin1", "sessionid2"))
    }

    @Test fun trimsAroundThePair() {
        assertEquals("abc123", YandexSession.cookieValue("  Session_id=abc123 ; a=1", "Session_id"))
    }

    @Test fun rejectsTheNoauthStub() {
        assertNull(YandexSession.cookieValue("Session_id=noauth", "Session_id"))
        assertNull(YandexSession.cookieValue("Session_id=noauth123", "Session_id"))
    }

    @Test fun rejectsAMissingOrEmptyValue() {
        assertNull(YandexSession.cookieValue("other=1", "Session_id"))
        assertNull(YandexSession.cookieValue("Session_id=", "Session_id"))
        assertNull(YandexSession.cookieValue("", "Session_id"))
        assertNull(YandexSession.cookieValue(null, "Session_id"))
    }

    // MARK: whether the jar still holds a session

    @Test fun theSignInReadsTheCookiesByTheirJarNames() {
        assertEquals("https://yandex.ru", YandexSession.JAR_URL)
        assertEquals("Session_id", YandexSession.SESSION_COOKIE)
        assertEquals("sessionid2", YandexSession.TWIN_COOKIE)
    }

    @Test fun aJarWithTheSessionCookieOrItsTwinHoldsASession() {
        assertTrue(YandexSession.hasSession("Session_id=abc123; yandexuid=q"))
        assertTrue(YandexSession.hasSession("yandexuid=q; sessionid2=twin1"))
        assertTrue(YandexSession.hasSession("Session_id=abc123; sessionid2=twin1"))
    }

    @Test fun aJarWithNoLiveSessionCookieHoldsNone() {
        assertFalse(YandexSession.hasSession(null))
        assertFalse(YandexSession.hasSession(""))
        assertFalse(YandexSession.hasSession("yandexuid=q; yp=1"))
        assertFalse(YandexSession.hasSession("Session_id=; sessionid2="))
        assertFalse(YandexSession.hasSession("Session_id=noauth:1760000000; sessionid2=noauth:1760000000"))
    }

    @Test fun aLiveTwinStaysASessionBesideADeadSessionCookie() {
        assertTrue(YandexSession.hasSession("Session_id=noauth:1760000000; sessionid2=twin1"))
    }
}
