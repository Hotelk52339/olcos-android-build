package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the web view's jar hands over, parsed for the one cookie that matters. */
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
}
