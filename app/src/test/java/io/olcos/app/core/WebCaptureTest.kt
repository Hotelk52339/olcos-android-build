package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the "what the app saw" list keeps: the shape of each request stays
 * readable — the create request has to be written from it — and no cookie
 * or token value rides along. No network here.
 */
class WebCaptureTest {
    // MARK: headers

    @Test fun theCredentialHeaderValuesAreNeverKept() {
        val out = WebCapture.scrubHeaders(
            mapOf(
                "Cookie" to "session=abc123; other=1",
                "Authorization" to "Bearer eyJhbGciOi.payload",
                "X-Auth-Token" to "tok-123",
                "Accept" to "application/json",
            ),
        )
        assertEquals("Cookie: <hidden>\nAuthorization: <hidden>\nX-Auth-Token: <hidden>\nAccept: application/json", out)
    }

    @Test fun anyHeaderNamedLikeACredentialLosesOnlyItsValue() {
        val out = WebCapture.scrubHeaders(mapOf("X-CSRF-Token" to "tok-123", "X-Session-Id" to "abc", "Content-Type" to "application/json"))
        assertEquals("X-CSRF-Token: <hidden>\nX-Session-Id: <hidden>\nContent-Type: application/json", out)
    }

    @Test fun aCredentialValueInsideAKeptHeaderIsBlanked() {
        val out = WebCapture.scrubHeaders(mapOf("X-Note" to "sent with access_token=tok-123"))
        assertFalse(out.contains("tok-123"))
    }

    @Test fun aProvenanceMarkerStandsInForTheCredentialValue() {
        val out = WebCapture.scrubHeaders(mapOf("Authorization" to "Bearer @cookie(access)", "X-Token" to "@seen(x-token)", "X-Csrf-Token" to "@local(csrf)"))
        assertEquals("Authorization: Bearer @cookie(access)\nX-Token: @seen(x-token)\nX-Csrf-Token: @local(csrf)", out)
    }

    @Test fun aMarkerIsNeverTakenForTheCookieHeaderOrForARealValue() {
        assertEquals("Cookie: <hidden>", WebCapture.scrubHeaders(mapOf("Cookie" to "@cookie(a)")))
        assertEquals("Authorization: <hidden>", WebCapture.scrubHeaders(mapOf("Authorization" to "Bearer abc.def @cookie(a)")))
        assertEquals("Authorization: <hidden>", WebCapture.scrubHeaders(mapOf("Authorization" to "Bearer abc.def")))
    }

    // MARK: text

    @Test fun theNamedCredentialValuesAreBlanked() {
        val out = WebCapture.scrub("{\"title\":\"Planning meeting\",\"access_token\":\"tok-123\",\"sessionId\":\"s-9\"}")
        assertFalse(out.contains("tok-123"))
        assertFalse(out.contains("s-9"))
        assertTrue(out.contains("Planning meeting"))
    }

    @Test fun formValuesAreBlankedByName() {
        val out = WebCapture.scrub("nickname=Vasya&password=hunter2&title=Planning")
        assertFalse(out.contains("hunter2"))
        assertEquals("nickname=Vasya&password=<hidden>&title=Planning", out)
    }

    @Test fun anAuthorizationSchemeIsBlanked() {
        val out = WebCapture.scrub("Authorization: Bearer abc.def-123")
        assertFalse(out.contains("abc.def-123"))
    }

    @Test fun aLongOpaqueRunIsBlankedButAnUnspacedNameIsNot() {
        val key = "Aa1" + "b".repeat(40)
        val out = WebCapture.scrub("got $key and named Planning-the-quarterly-review-meeting-with-everyone")
        assertFalse(out.contains(key))
        assertTrue(out.contains("Planning-the-quarterly-review-meeting-with-everyone"))
    }

    @Test fun aRoomLinkAndAMeetingNameStay() {
        // The dump exists to say which request makes which meeting; the link and the name are not secrets.
        val out = WebCapture.scrub("created https://my.mts-link.ru/j/0/400500600700 and named Planning 2026")
        assertTrue(out.contains("https://my.mts-link.ru/j/0/400500600700"))
        assertTrue(out.contains("Planning 2026"))
    }

    // MARK: entries

    @Test fun aShortBodyIsKeptWholeAndTheMethodCapitalized() {
        val entry = WebCapture.entry("post", "https://example/", emptyMap(), "hello")
        assertEquals("POST", entry.method)
        assertEquals("hello", entry.body)
        assertFalse(entry.bodyCut)
    }

    @Test fun aLongBodyIsCutAtTheLimit() {
        val entry = WebCapture.entry("POST", "https://example/", emptyMap(), "x".repeat(WebCapture.BODY_LIMIT + 100))
        assertEquals(WebCapture.BODY_LIMIT, entry.body.length)
        assertTrue(entry.bodyCut)
    }

    @Test fun anEntryIsScrubbedWhereItIsMade() {
        val entry = WebCapture.entry("POST", "https://gw.mts-link.ru/api/events?token=tok-1", mapOf("Cookie" to "sid=abc123"), "password=hunter2")
        assertFalse(entry.url.contains("tok-1"))
        assertFalse(entry.headers.contains("abc123"))
        assertFalse(entry.body.contains("hunter2"))
    }

    // MARK: the buffer

    @Test fun theBufferKeepsTheNewestCallsOnly() {
        val buffer = WebCapture.Buffer(limit = 3)
        for (i in 1..5) buffer.record("GET", "https://example/page$i", emptyMap(), "")
        val entries = buffer.entries()
        assertEquals(3, buffer.count())
        assertEquals("https://example/page3", entries.first().url)
        assertEquals("https://example/page5", entries.last().url)
    }

    @Test fun nothingUnscrubbedEverSitsInTheBuffer() {
        val buffer = WebCapture.Buffer()
        buffer.record(
            "POST", "https://gw.mts-link.ru/api/events",
            mapOf("Cookie" to "sid=abc123", "X-Auth-Token" to "tok-77"),
            "{\"accessToken\":\"tok-9\"}",
        )
        val dump = buffer.dump()
        assertFalse(dump.contains("abc123"))
        assertFalse(dump.contains("tok-77"))
        assertFalse(dump.contains("tok-9"))
    }

    @Test fun theDumpCarriesTheShapeOfEachCall() {
        val buffer = WebCapture.Buffer()
        buffer.record("POST", "https://gw.mts-link.ru/api/events", mapOf("Content-Type" to "application/json"), "{\"title\":\"Planning\"}")
        buffer.record("GET", "https://my.mts-link.ru/j/0/1", emptyMap(), "")
        val dump = buffer.dump()
        assertTrue(dump.startsWith("POST https://gw.mts-link.ru/api/events"))
        assertTrue(dump.contains("Content-Type: application/json"))
        assertTrue(dump.contains("{\"title\":\"Planning\"}"))
        assertTrue(dump.contains("GET https://my.mts-link.ru/j/0/1"))
    }

    @Test fun anAnswerIsKeptWithItsCallAndScrubbed() {
        val buffer = WebCapture.Buffer()
        buffer.record("POST", "https://gw.mts-link.ru/api/events", emptyMap(), "{}", id = 7, at = 100L)
        buffer.record("GET", "https://gw.mts-link.ru/api/me", emptyMap(), "", id = 8, at = 110L)
        buffer.respond(7, 201, "{\"data\":{\"id\":400500600700},\"accessToken\":\"tok-1\"}", at = 150L)
        buffer.respond(99, 200, "orphan")
        buffer.respond(0, 200, "unnumbered")
        val first = buffer.entries().first()
        assertEquals(201, first.status)
        assertEquals(150L, first.respondedAt)
        assertTrue(first.response.contains("400500600700"))
        assertFalse(first.response.contains("tok-1"))
        assertEquals(null, buffer.entries().last().status)
        assertTrue(buffer.dump().contains("POST https://gw.mts-link.ru/api/events → 201"))
        assertFalse(buffer.dump().contains("orphan"))
    }

    @Test fun anAnswerIsCappedAtTheLimit() {
        val entry = WebCapture.answered(WebCapture.entry("POST", "https://example/", emptyMap(), ""), 200, "x".repeat(WebCapture.RESPONSE_LIMIT + 50))
        assertEquals(WebCapture.RESPONSE_LIMIT, entry.response.length)
    }

    @Test fun clearingTheBufferLeavesNothing() {
        val buffer = WebCapture.Buffer()
        buffer.record("GET", "https://example/", emptyMap(), "x")
        buffer.clear()
        assertEquals(0, buffer.count())
        assertEquals("", buffer.dump())
    }
}
