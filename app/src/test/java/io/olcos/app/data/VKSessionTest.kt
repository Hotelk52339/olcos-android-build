package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the web view's jar hands over, parsed for the one cookie that matters and shaped into the credential. */
class VKSessionTest {
    @Test fun picksTheNamedCookie() {
        assertEquals("abc123", VKSession.cookieValue("remixsid=abc123; remixlang=61"))
        assertEquals("v-2", VKSession.cookieValue("remixstid=1; remixsid=v-2"))
    }

    @Test fun rejectsTheDeletionIdiom() {
        assertNull(VKSession.cookieValue("remixsid=DELETED"))
        assertNull(VKSession.cookieValue("other=1; remixsid=DELETED"))
    }

    @Test fun rejectsAStubOrMissingValue() {
        assertNull(VKSession.cookieValue("remixsid=noauth123"))
        assertNull(VKSession.cookieValue("remixsid="))
        assertNull(VKSession.cookieValue("other=1"))
        assertNull(VKSession.cookieValue(null))
    }

    // MARK: the jar

    @Test fun jarPutsTheSessionCookieFirst() {
        assertEquals(
            "remixsid=abc123; remixstid=1; remixlang=61",
            VKSession.jar("remixstid=1; remixsid=abc123; remixlang=61"),
        )
    }

    @Test fun jarDropsEmptyAndDeletedValues() {
        assertEquals(
            "remixsid=abc123; remixlang=61",
            VKSession.jar("remixsid=abc123; remixir=DELETED; remixgp=; remixlang=61"),
        )
    }

    @Test fun jarKeepsTheFirstOfTwoCookiesWithOneName() {
        assertEquals("remixsid=abc123; a=1", VKSession.jar("a=1; remixsid=abc123; a=2"))
        assertEquals("remixsid=first", VKSession.jar("remixsid=first; remixsid=second"))
    }

    @Test fun jarSkipsADeletedSessionCookieInFavourOfALiveOne() {
        assertEquals("remixsid=abc123", VKSession.jar("remixsid=DELETED; remixsid=abc123"))
    }

    @Test fun jarNeedsALiveSessionCookie() {
        assertNull(VKSession.jar(null))
        assertNull(VKSession.jar(""))
        assertNull(VKSession.jar("   "))
        assertNull(VKSession.jar("remixstid=1; remixlang=61"))
        assertNull(VKSession.jar("remixsid=DELETED; remixlang=61"))
        assertNull(VKSession.jar("remixsid=; remixlang=61"))
        assertNull(VKSession.jar("remixsid=noauth123; remixlang=61"))
    }

    @Test fun jarRefusesInjectionInTheSessionCookie() {
        assertNull(VKSession.jar("remixsid=abc\r\nX-Evil: 1"))
        assertNull(VKSession.jar("remixsid=a b"))
        assertNull(VKSession.jar("remixsid=a,b"))
        assertNull(VKSession.jar("remixsid=a\"b"))
        assertNull(VKSession.jar("remixsid=a\\b"))
        assertNull(VKSession.jar("remixsid=abc\u0000"))
        assertNull(VKSession.jar("remixsid=\u00e9"))
    }

    @Test fun jarDropsAnUnsafeOtherCookieButKeepsTheSession() {
        val out = VKSession.jar("remixsid=abc123; evil=x\r\nSet-Cookie: y=1; ok=1; bad name=2; semi=a,b")
        assertEquals("remixsid=abc123; ok=1", out)
        assertFalse(out!!.contains('\r') || out.contains('\n'))
    }

    @Test fun jarKeepsEqualsSignsInsideValues() {
        assertEquals("remixsid=abc==; remixsf=a=b", VKSession.jar("remixsf=a=b; remixsid=abc=="))
    }

    @Test fun jarTrimsTheLine() {
        assertEquals("remixsid=abc123; remixlang=61", VKSession.jar("  remixsid=abc123 ;  remixlang=61  "))
    }

    @Test fun jarIgnoresPartsThatAreNoPair() {
        assertEquals("remixsid=abc123", VKSession.jar("stray; =novalue; remixsid=abc123;"))
    }

    @Test fun aShapedJarStaysAsItIs() {
        // What the sheet verifies is what store keeps, so shaping twice must change nothing.
        val jar = VKSession.jar("remixstid=1; remixsid=abc123; remixir=DELETED; a=1; a=2; remixlang=61")!!
        assertEquals(jar, VKSession.jar(jar))
        assertEquals(jar, VKSession.credentialOf(jar))
    }

    // MARK: what store keeps

    @Test fun aSavedCredentialIsAJarOrABareValue() {
        assertEquals("remixsid=abc123; remixlang=61", VKSession.credentialOf("remixlang=61; remixsid=abc123"))
        assertEquals("abc123", VKSession.credentialOf("abc123"))
        assertEquals("abc123", VKSession.credentialOf("  abc123 "))
    }

    @Test fun aCredentialThatIsNoSessionIsNotKept() {
        assertNull(VKSession.credentialOf("DELETED"))
        assertNull(VKSession.credentialOf(""))
        assertNull(VKSession.credentialOf("noauth123"))
        assertNull(VKSession.credentialOf("a b"))
        assertNull(VKSession.credentialOf("x=1"))
        assertNull(VKSession.credentialOf("remixsid=DELETED"))
    }

    // MARK: what the sign-in sheet remembers of a refusal

    @Test fun theRefusalKeyIsTheSessionValueAndTheCookieNames() {
        // The other values move on every page load; they do not make a new jar.
        assertEquals(
            VKSession.refusalKey("remixsid=abc123; remixlang=61; remixstid=1"),
            VKSession.refusalKey("remixsid=abc123; remixstid=9; remixlang=62"),
        )
        assertEquals(
            VKSession.refusalKey("remixsid=abc123; x=1; y=2"),
            VKSession.refusalKey("remixsid=abc123; y=2; x=1"),
        )
    }

    @Test fun theRefusalKeyMovesWithANewSessionValueOrANewCookie() {
        val base = VKSession.refusalKey("remixsid=abc123; remixlang=61")
        assertNotEquals(base, VKSession.refusalKey("remixsid=def456; remixlang=61"))
        assertNotEquals(base, VKSession.refusalKey("remixsid=abc123; remixlang=61; remixrefkey=x"))
        assertNotEquals(base, VKSession.refusalKey("remixsid=abc123"))
    }

    @Test fun aRefusedJarIsNotAskedAboutAgain() {
        val jar = "remixsid=abc123; remixlang=61"
        val refused = VKSession.refusalKey(jar)
        assertFalse(VKSession.shouldVerify(jar, refused, lastAttemptMs = 0L, nowMs = 60_000L))
        // Other values moving does not make it a new jar.
        assertFalse(VKSession.shouldVerify("remixsid=abc123; remixlang=62", refused, lastAttemptMs = 0L, nowMs = 60_000L))
    }

    @Test fun aChangedJarIsAskedAboutOnceTheGapHasPassed() {
        val refused = VKSession.refusalKey("remixsid=abc123; remixlang=61")
        val changed = "remixsid=def456; remixlang=61"
        assertFalse(VKSession.shouldVerify(changed, refused, lastAttemptMs = 10_000L, nowMs = 12_999L))
        assertTrue(VKSession.shouldVerify(changed, refused, lastAttemptMs = 10_000L, nowMs = 13_000L))
    }

    @Test fun aJarGrowingNewCookiesIsAskedAboutAgain() {
        val refused = VKSession.refusalKey("remixsid=abc123")
        assertTrue(VKSession.shouldVerify("remixsid=abc123; remixrefkey=x", refused, lastAttemptMs = 0L, nowMs = 5_000L))
    }

    @Test fun theFirstAttemptIsNotHeldBack() {
        assertTrue(VKSession.shouldVerify("remixsid=abc123", refusedKey = null, lastAttemptMs = null, nowMs = 0L))
    }

    @Test fun anAnswerlessAttemptIsRetriedButNotFasterThanTheGap() {
        // No refusal is remembered when VK gave no answer: the same jar is tried again, after the gap.
        val jar = "remixsid=abc123"
        assertFalse(VKSession.shouldVerify(jar, refusedKey = null, lastAttemptMs = 1_000L, nowMs = 2_000L))
        assertTrue(VKSession.shouldVerify(jar, refusedKey = null, lastAttemptMs = 1_000L, nowMs = 4_000L))
    }

    // MARK: the sheet's status line

    @Test fun noSessionCookieYetIsTheSignInPrompt() {
        assertEquals(VKSession.SignInStatus.NO_SESSION, VKSession.signInStatus(null, null))
        assertEquals(VKSession.SignInStatus.NO_SESSION, VKSession.signInStatus(null, VKSession.refusalKey("remixsid=abc123")))
    }

    @Test fun aJarVKHasNotRefusedIsBeingChecked() {
        assertEquals(VKSession.SignInStatus.CHECKING, VKSession.signInStatus("remixsid=abc123", null))
        val refused = VKSession.refusalKey("remixsid=abc123; remixlang=61")
        assertEquals(VKSession.SignInStatus.CHECKING, VKSession.signInStatus("remixsid=def456; remixlang=61", refused))
    }

    @Test fun theJarVKRefusedStaysRefusedUntilItChanges() {
        val refused = VKSession.refusalKey("remixsid=abc123; remixlang=61")
        assertEquals(VKSession.SignInStatus.REFUSED, VKSession.signInStatus("remixsid=abc123; remixlang=61", refused))
        assertEquals(VKSession.SignInStatus.REFUSED, VKSession.signInStatus("remixsid=abc123; remixlang=62", refused))
    }
}
