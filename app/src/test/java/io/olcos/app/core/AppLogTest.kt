package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What may leave the phone never carries a sign-in. */
class AppLogTest {
    @Test fun blanksTheSignInCookieValues() {
        assertEquals("Cookie: remixsid=<hidden>", AppLog.redact("Cookie: remixsid=abcdef123456"))
        assertEquals("Session_id=<hidden>", AppLog.redact("Session_id=xyz789"))
        assertEquals("wb_access_token=<hidden>", AppLog.redact("wb_access_token=" + "a".repeat(40)))
        assertEquals("OLCRTC_WB_TOKEN=<hidden>", AppLog.redact("OLCRTC_WB_TOKEN=token-value"))
    }

    @Test fun keepsTheCookieName() {
        val out = AppLog.redact("Cookie: remixsid=abcdef123456; other=1")
        assertFalse(out.contains("abcdef123456"))
        assertEquals("Cookie: remixsid=<hidden>; other=1", out)
    }
}
