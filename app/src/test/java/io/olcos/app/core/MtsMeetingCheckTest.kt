package io.olcos.app.core

import io.olcos.app.R
import io.olcos.app.core.MtsMeetingCheck.AfterReplay
import io.olcos.app.core.MtsMeetingCheck.Lesson
import io.olcos.app.core.MtsMeetingCheck.Verdict
import io.olcos.app.core.RoomServices.MTSLink.Meeting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an MTS Link meeting is one the server can enter, read from the
 * event's public record: the call the core itself makes first. The two
 * record shapes below are the ones measured on 2026-10-20 (a quick meeting
 * with no session, and an older quick meeting with one stopped session),
 * with every id replaced by a made-up one.
 */
class MtsMeetingCheckTest {
    private val quickNoSession = """{"id":20300400500,"name":"Новое мероприятие сегодня","type":"meeting","isEndless":false,""" +
        """"status":"RESERVED","rule":"FREQ=DAILY;COUNT=1","startType":"manual","eventSessions":[]}"""
    private val quickStopped = """{"id":20300400501,"name":"Новое мероприятие сегодня","type":"meeting","isEndless":false,""" +
        """"status":"STOP","eventSessions":[{"id":20300400600,"status":"STOP","startsAt":"2026-10-20T05:45:00+03:00"}]}"""
    private val endless = """{"id":20300400502,"name":"Постоянная встреча","type":"meeting","isEndless":true,""" +
        """"status":"ACTIVE","eventSessions":[{"id":20300400601,"status":"STOP"},{"id":20300400602,"status":"ACTIVE"}]}"""
    private val endlessNoSession = """{"id":20300400503,"isEndless":true,"eventSessions":[]}"""

    // MARK: the record

    @Test fun anEndlessMeetingWithASessionIsUsableAndTheFirstSessionCounts() {
        // The core takes eventSessions[0], whatever its status.
        assertEquals(Verdict.Usable(20300400601L), MtsMeetingCheck.verdict(200, endless))
    }

    @Test fun theQuickMeetingTheLessonMadeIsNotPermanent() {
        assertEquals(Verdict.NotPermanent, MtsMeetingCheck.verdict(200, quickNoSession))
    }

    @Test fun aQuickMeetingWithASessionIsStillNotPermanent() {
        assertEquals(Verdict.NotPermanent, MtsMeetingCheck.verdict(200, quickStopped))
    }

    @Test fun aRecordWithoutIsEndlessIsNotPermanent() {
        assertEquals(Verdict.NotPermanent, MtsMeetingCheck.verdict(200, """{"id":20300400504,"eventSessions":[{"id":20300400603}]}"""))
        assertEquals(Verdict.NotPermanent, MtsMeetingCheck.verdict(200, """{"isEndless":"true","eventSessions":[{"id":20300400603}]}"""))
        assertEquals(Verdict.NotPermanent, MtsMeetingCheck.verdict(200, """{"isEndless":null,"eventSessions":[{"id":20300400603}]}"""))
    }

    @Test fun anEndlessMeetingWithoutASessionHasNoSession() {
        assertEquals(Verdict.NoSession, MtsMeetingCheck.verdict(200, endlessNoSession))
        assertEquals(Verdict.NoSession, MtsMeetingCheck.verdict(200, """{"isEndless":true}"""))
        assertEquals(Verdict.NoSession, MtsMeetingCheck.verdict(200, """{"isEndless":true,"eventSessions":[{"id":0}]}"""))
        assertEquals(Verdict.NoSession, MtsMeetingCheck.verdict(200, """{"isEndless":true,"eventSessions":[{"status":"ACTIVE"}]}"""))
        assertEquals(Verdict.NoSession, MtsMeetingCheck.verdict(200, """{"isEndless":true,"eventSessions":[{"id":-5}]}"""))
    }

    @Test fun whatTheCoreCallsAGoneRoomIsGone() {
        for (status in listOf(404, 406, 410)) assertEquals("HTTP $status", Verdict.Gone, MtsMeetingCheck.verdict(status, ""))
    }

    @Test fun everythingElseIsUnknown() {
        for (status in listOf(-1, 0, 201, 301, 401, 403, 429, 500, 502)) {
            assertTrue("HTTP $status", MtsMeetingCheck.verdict(status, endless) is Verdict.Unknown)
        }
        for (junk in listOf("", "<html>gateway</html>", "[]", "null", "{\"isEndless\":tru", "\"text\"")) {
            assertTrue(junk, MtsMeetingCheck.verdict(200, junk) is Verdict.Unknown)
        }
    }

    @Test fun onlyUsableIsUsable() {
        assertTrue(Verdict.Usable(20300400601L).usable)
        for (v in listOf(Verdict.NotPermanent, Verdict.NoSession, Verdict.Gone, Verdict.Unknown("x"))) assertFalse(v.toString(), v.usable)
    }

    // MARK: the request

    @Test fun theRecordIsAskedForAtTheCoresOwnAddress() {
        assertEquals("https://gw.mts-link.ru/api/event/20300400500", MtsMeetingCheck.recordURL("https://my.mts-link.ru/j/100200300/20300400500"))
        assertEquals("https://gw.mts-link.ru/api/event/20300400500", MtsMeetingCheck.recordURL("https://my.mts-link.ru/j/100200300/20300400500?ice=both"))
        assertEquals("https://gw.mts-link.ru/api/event/20300400500", MtsMeetingCheck.recordURL("my.mts-link.ru/j/supportwebinar/20300400500/session/20300400600"))
        assertNull(MtsMeetingCheck.recordURL("https://telemost.yandex.ru/j/12345678901234"))
        assertNull(MtsMeetingCheck.recordURL(""))
    }

    @Test fun theRequestCarriesTheCoresHeadersAndNoCookie() {
        val headers = MtsMeetingCheck.requestHeaders().toMap()
        assertEquals("https://my.mts-link.ru", headers["Origin"])
        assertEquals("https://my.mts-link.ru/", headers["Referer"])
        assertEquals(RoomServices.BROWSER_UA, headers["User-Agent"])
        assertFalse(headers.keys.any { it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true) })
        assertEquals(10_000, MtsMeetingCheck.TIMEOUT_MS)
    }

    // MARK: what goes to a server

    @Test fun onlyAnMtsLinkRoomIsChecked() {
        assertTrue(MtsMeetingCheck.mustCheck("mtslink", "https://my.mts-link.ru/j/100200300/20300400500"))
        assertFalse(MtsMeetingCheck.mustCheck("mtslink", ""))
        assertFalse(MtsMeetingCheck.mustCheck("telemost", "12345678901234"))
        assertFalse(MtsMeetingCheck.mustCheck("jitsi", "https://meet.example.org/olcrtc-x"))
    }

    @Test fun aRefusedMeetingIsNamedInOneLine() {
        assertNull(MtsMeetingCheck.refusalText(Verdict.Usable(1L)))
        assertEquals(R.string.mts_check_quick, MtsMeetingCheck.refusalText(Verdict.NotPermanent))
        assertEquals(R.string.mts_check_no_session, MtsMeetingCheck.refusalText(Verdict.NoSession))
        assertEquals(R.string.mts_check_gone, MtsMeetingCheck.refusalText(Verdict.Gone))
        assertEquals(R.string.mts_check_unknown, MtsMeetingCheck.refusalText(Verdict.Unknown("timeout")))
    }

    // MARK: the lesson's Finish

    private val made = Meeting("100200300", "20300400500")
    private val other = Meeting("100200300", "20300400505")
    private val third = Meeting("100200300", "20300400506")
    private val template = MtsLearn.Template(
        "POST", "https://gw.mts-link.ru/api/events", "application/json", MtsRequest.Body.Text("{}", true),
        emptyList(), MtsLearn.Kind.EVENT, listOf(MtsLearn.Seg.Key("id")), "100200300", 1L,
    )
    private val ok = Verdict.Usable(20300400601L)

    @Test fun aPermanentMeetingTheLessonMadeIsOfferedWithItsTemplate() {
        val one = MtsLearn.Result.One(made, madeByApp = true, template = template, why = null)
        assertEquals(Lesson.Ready(one), MtsMeetingCheck.lesson(one, mapOf(made.event to ok)))
    }

    @Test fun aQuickMeetingTheLessonMadeIsRefusedAndNothingIsLearned() {
        val one = MtsLearn.Result.One(made, madeByApp = true, template = template, why = null)
        assertEquals(Lesson.Refused(Verdict.NotPermanent), MtsMeetingCheck.lesson(one, mapOf(made.event to Verdict.NotPermanent)))
    }

    @Test fun aMeetingOnlySeenIsRefusedTheSameWay() {
        val one = MtsLearn.Result.One(made, madeByApp = false, template = null, why = "no create call was recorded")
        assertEquals(Lesson.Refused(Verdict.NoSession), MtsMeetingCheck.lesson(one, mapOf(made.event to Verdict.NoSession)))
    }

    @Test fun aMeetingThatWasNotCheckedIsRefusedAsUnknown() {
        val one = MtsLearn.Result.One(made, madeByApp = true, template = template, why = null)
        val outcome = MtsMeetingCheck.lesson(one, emptyMap())
        assertTrue(outcome is Lesson.Refused && outcome.verdict is Verdict.Unknown)
    }

    @Test fun ofSeveralOnlyTheUsableOnesAreOffered() {
        val several = MtsLearn.Result.Several(listOf(made, other, third))
        assertEquals(
            Lesson.Ready(MtsLearn.Result.Several(listOf(made, third))),
            MtsMeetingCheck.lesson(several, mapOf(made.event to ok, other.event to Verdict.NotPermanent, third.event to ok)),
        )
    }

    @Test fun theOneUsableOfSeveralIsOfferedAloneAndTeachesNothing() {
        val several = MtsLearn.Result.Several(listOf(made, other))
        val outcome = MtsMeetingCheck.lesson(several, mapOf(made.event to Verdict.NotPermanent, other.event to ok))
        assertTrue(outcome is Lesson.Ready)
        val one = (outcome as Lesson.Ready).result as MtsLearn.Result.One
        assertEquals(other, one.meeting)
        assertNull(one.template)
        assertFalse(one.madeByApp)
    }

    @Test fun severalWithNoneUsableNameTheMostTellingReason() {
        val several = MtsLearn.Result.Several(listOf(made, other, third))
        assertEquals(Lesson.Refused(Verdict.NotPermanent),
            MtsMeetingCheck.lesson(several, mapOf(made.event to Verdict.Gone, other.event to Verdict.NotPermanent, third.event to Verdict.NoSession)))
        assertEquals(Lesson.Refused(Verdict.NoSession),
            MtsMeetingCheck.lesson(several, mapOf(made.event to Verdict.Gone, other.event to Verdict.NoSession)))
        assertEquals(Lesson.Refused(Verdict.Gone), MtsMeetingCheck.lesson(several, mapOf(made.event to Verdict.Gone)))
        val unknown = MtsMeetingCheck.lesson(several, emptyMap())
        assertTrue(unknown is Lesson.Refused && unknown.verdict is Verdict.Unknown)
    }

    @Test fun noMeetingStaysNoMeeting() {
        assertEquals(Lesson.Ready(MtsLearn.Result.None), MtsMeetingCheck.lesson(MtsLearn.Result.None, emptyMap()))
    }

    @Test fun theMeetingsALessonResultAsksAbout() {
        assertEquals(emptyList<Meeting>(), MtsMeetingCheck.candidates(MtsLearn.Result.None))
        assertEquals(listOf(made), MtsMeetingCheck.candidates(MtsLearn.Result.One(made, true, template, null)))
        assertEquals(listOf(made, other), MtsMeetingCheck.candidates(MtsLearn.Result.Several(listOf(made, other))))
    }

    // MARK: the one-button make

    @Test fun aUsableReplayedMeetingIsUsed() {
        assertEquals(AfterReplay.Use, MtsMeetingCheck.afterReplay(ok))
    }

    @Test fun aReplayThatMadeTheWrongKindDropsTheTemplate() {
        assertEquals(AfterReplay.Relearn(Verdict.NotPermanent), MtsMeetingCheck.afterReplay(Verdict.NotPermanent))
        assertEquals(AfterReplay.Relearn(Verdict.NoSession), MtsMeetingCheck.afterReplay(Verdict.NoSession))
        assertEquals(AfterReplay.Relearn(Verdict.Gone), MtsMeetingCheck.afterReplay(Verdict.Gone))
    }

    @Test fun aReplayWhoseMeetingCouldNotBeCheckedKeepsTheTemplateAndSendsNothing() {
        val unknown = Verdict.Unknown("timeout")
        assertEquals(AfterReplay.Retry(unknown), MtsMeetingCheck.afterReplay(unknown))
    }
}
