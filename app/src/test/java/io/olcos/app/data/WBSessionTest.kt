package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The token out of the web view's jar, read for both host families the login may set the cookie on. */
class WBSessionTest {
    @Test fun readsTheCookieFromTheStreamHost() {
        assertEquals(
            "tok1",
            WBSession.cookieValue { url -> if (url == "https://stream.wb.ru") "wb_access_token=tok1" else null },
        )
    }

    @Test fun fallsBackToTheWildberriesHost() {
        assertEquals(
            "tok2",
            WBSession.cookieValue { url -> if (url == "https://wildberries.ru") "other=1; wb_access_token=tok2" else null },
        )
    }

    @Test fun refusesWhatIsNotAToken() {
        assertNull(WBSession.cookieValue { "wb_access_token=" })
        assertNull(WBSession.cookieValue { null })
    }
}
