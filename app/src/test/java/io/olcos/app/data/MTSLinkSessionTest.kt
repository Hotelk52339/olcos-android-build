package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the web view's jar hands over, kept whole as the MTS Link credential. */
class MTSLinkSessionTest {
    @Test fun jarKeepsEveryUsablePairInOrder() {
        assertEquals("a=1; sid=abc==; lang=ru", MTSLinkSession.jar("a=1; sid=abc==; lang=ru"))
    }

    @Test fun jarDropsEmptyDeletedAndUnsafePairs() {
        assertEquals("sid=abc123; ok=1", MTSLinkSession.jar("sid=abc123; gone=DELETED; empty=; evil=x\r\nSet-Cookie: y=1; ok=1"))
    }

    @Test fun jarKeepsTheFirstOfTwoCookiesWithOneName() {
        assertEquals("a=first; b=1", MTSLinkSession.jar("a=first; b=1; a=second"))
    }

    @Test fun jarOfNothingIsNoSession() {
        assertNull(MTSLinkSession.jar(null))
        assertNull(MTSLinkSession.jar(""))
        assertNull(MTSLinkSession.jar("   "))
        assertNull(MTSLinkSession.jar("gone=DELETED; empty="))
        assertNull(MTSLinkSession.jar("stray; =novalue"))
    }

    @Test fun aShapedJarStaysAsItIs() {
        // What the sheet hands up is what store keeps, so shaping twice must change nothing.
        val jar = MTSLinkSession.jar("a=1; a=2; b=x")!!
        assertEquals(jar, MTSLinkSession.jar(jar))
        assertEquals(jar, MTSLinkSession.credentialOf(jar))
    }

    @Test fun whatStoreKeepsIsTheJar() {
        assertEquals("lang=ru; sid=abc123", MTSLinkSession.credentialOf("lang=ru; sid=abc123"))
        assertNull(MTSLinkSession.credentialOf(""))
    }
}
