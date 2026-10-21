package io.olcos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The ids of the meetings this app made: what a rotation may delete, capped and keyed by the meeting's event id. */
class MTSLinkMeetingsTest {
    private val meeting = "https://my.mts-link.ru/j/0/400500600700"
    private val other = "https://my.mts-link.ru/j/100200300/400500600701"

    @Test fun addingTakesTheEventID() {
        assertEquals(listOf("400500600700"), MTSLinkMeetings.added(emptyList(), meeting))
    }

    @Test fun addingTheSameMeetingTwiceKeepsOneEntryAtTheFront() {
        val ids = MTSLinkMeetings.added(MTSLinkMeetings.added(listOf("400500600700"), other), meeting)
        assertEquals(listOf("400500600700", "400500600701"), ids)
    }

    @Test fun theOldestIDsGoWhenTheCapIsPassed() {
        val ids = (1..MTSLinkMeetings.LIMIT).map { "event$it" }
        val out = MTSLinkMeetings.added(ids, meeting)
        assertEquals(MTSLinkMeetings.LIMIT, out.size)
        assertEquals("400500600700", out.first())
        assertEquals("event${MTSLinkMeetings.LIMIT - 1}", out.last())
    }

    @Test fun removingTakesTheMeetingOut() {
        assertEquals(listOf("400500600701"), MTSLinkMeetings.removed(listOf("400500600700", "400500600701"), meeting))
    }

    @Test fun whatIsNotAMeetingLinkChangesNothing() {
        val ids = listOf("400500600700")
        assertEquals(ids, MTSLinkMeetings.added(ids, "not a link"))
        assertEquals(ids, MTSLinkMeetings.removed(ids, ""))
    }
}
