package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the room services: the link a typed or API-given VK value
 * normalizes to, the MTS Link meeting link with its ICE hint and the room
 * the server names for it, and the values and envelopes the create answers
 * carry. No network here.
 */
class RoomServicesTest {
    // MARK: VK link normalization

    @Test fun vkLinkFromFullURL() {
        assertEquals("https://vk.ru/call/join/Ab-1_2", RoomServices.VKCall.normalized("https://vk.ru/call/join/Ab-1_2"))
    }

    @Test fun vkLinkKeepsTheHostItCameWith() {
        assertEquals("https://vk.com/call/join/xyz", RoomServices.VKCall.normalized("m.vk.com/call/join/xyz"))
    }

    @Test fun vkLinkStopsAtTheEndOfTheID() {
        assertEquals("https://vk.ru/call/join/Ab-1", RoomServices.VKCall.normalized("https://vk.ru/call/join/Ab-1?z=1"))
    }

    @Test fun vkLinkRejectsAnythingElse() {
        assertNull(RoomServices.VKCall.normalized("not a link"))
    }

    @Test fun vkLinkFromBareID() {
        assertEquals("https://vk.ru/call/join/Ab-1_2", RoomServices.VKCall.linkFromID("Ab-1_2"))
        assertEquals("https://vk.ru/call/join/Ab-1", RoomServices.VKCall.linkFromID(" Ab-1 "))
    }

    @Test fun vkLinkFromBareIDRejectsAnythingElse() {
        assertNull(RoomServices.VKCall.linkFromID("bad id"))
        assertNull(RoomServices.VKCall.linkFromID(""))
    }

    // MARK: VK create answers

    @Test fun vkWebTokenAnswer() {
        assertEquals("tok.en-1_2", RoomServices.VKCall.accessToken("{\"data\":{\"access_token\":\"tok.en-1_2\"}}"))
    }

    @Test fun vkWebTokenRefusalNamesItsReason() {
        assertEquals(
            "unauthorized",
            RoomServices.VKCall.webTokenRefusal("{\"type\":\"error\",\"error_code\":\"\",\"error_info\":\"unauthorized\"}"),
        )
        assertEquals(
            "wrong origin",
            RoomServices.VKCall.webTokenRefusal("{\"type\":\"error\",\"error_code\":\"\",\"error_info\":\"wrong origin\"}"),
        )
    }

    @Test fun vkWebTokenRefusalStaysOutOfOtherAnswers() {
        assertNull(RoomServices.VKCall.webTokenRefusal("{\"type\":\"okay\",\"data\":{\"access_token\":\"tok\"}}"))
        assertNull(RoomServices.VKCall.webTokenRefusal("{\"error\":{\"error_code\":5,\"error_msg\":\"no\"}}"))
        assertNull(RoomServices.VKCall.webTokenRefusal("not json"))
    }

    @Test fun vkJoinLinkPrefersThePlainOne() {
        assertEquals(
            "https://vk.ru/call/join/Ab-1",
            RoomServices.VKCall.joinLink("{\"response\":{\"join_link\":\"https:\\/\\/vk.ru\\/call\\/join\\/Ab-1\",\"ok_join_link\":\"other\"}}"),
        )
    }

    @Test fun vkJoinLinkFallsBackToTheOkOne() {
        assertEquals("other", RoomServices.VKCall.joinLink("{\"response\":{\"ok_join_link\":\"other\"}}"))
    }

    @Test fun vkCreatedRoomFromAURL() {
        assertEquals(
            "https://vk.ru/call/join/Ab-1",
            RoomServices.VKCall.createdRoom("{\"response\":{\"join_link\":\"https:\\/\\/vk.ru\\/call\\/join\\/Ab-1\"}}"),
        )
    }

    @Test fun vkCreatedRoomFromABareID() {
        assertEquals("https://vk.ru/call/join/Ab-1", RoomServices.VKCall.createdRoom("{\"response\":{\"join_link\":\"Ab-1\"}}"))
    }

    @Test fun vkCreatedRoomFromABareOkID() {
        assertEquals("https://vk.ru/call/join/Zz9", RoomServices.VKCall.createdRoom("{\"response\":{\"ok_join_link\":\"Zz9\"}}"))
    }

    @Test fun vkCreatedRoomFromTheOkLink() {
        assertEquals(
            "https://vk.com/call/join/Zz",
            RoomServices.VKCall.createdRoom("{\"response\":{\"ok_join_link\":\"https:\\/\\/vk.com\\/call\\/join\\/Zz\"}}"),
        )
    }

    @Test fun vkCreatedRoomRejectsAnErrorAnswer() {
        assertNull(RoomServices.VKCall.createdRoom("{\"error\":{\"error_msg\":\"fail\"}}"))
    }

    // MARK: VK method errors

    @Test fun vkErrorCodeOutOfTheMethodEnvelope() {
        assertEquals(5, RoomServices.VKCall.errorCode("{\"error\":{\"error_code\":5,\"error_msg\":\"User authorization failed\"}}"))
    }

    @Test fun vkErrorCodeStaysOutOfOtherAnswers() {
        assertNull(RoomServices.VKCall.errorCode("{\"type\":\"error\",\"error_code\":\"\",\"error_info\":\"unauthorized\"}"))
        assertNull(RoomServices.VKCall.errorCode("{\"data\":{\"access_token\":\"tok\"}}"))
    }

    @Test fun vkErrorForTheDeadSessionCode() {
        assertTrue(RoomServices.VKCall.errorFor(5) is RoomServices.SessionRejected)
    }

    @Test fun vkErrorForTheWaitCodes() {
        for (code in listOf(6, 9, 14, 29)) {
            val e = RoomServices.VKCall.errorFor(code)
            assertTrue(e is RoomServices.Refused)
            assertEquals(429, (e as RoomServices.Refused).status)
        }
    }

    @Test fun vkErrorForTheTokenRequiredCodeIsNotARelogin() {
        val e = RoomServices.VKCall.errorFor(15)
        assertTrue(e !is RoomServices.SessionRejected)
        assertEquals(15, (e as RoomServices.Refused).status)
    }

    @Test fun vkCallsStartIsSentWithNoPeerID() {
        assertEquals(listOf("v" to "5.282"), RoomServices.VKCall.startForm())
    }

    // MARK: VK credential → Cookie header

    @Test fun vkJarGoesOutAsItIs() {
        val jar = "remixsid=abc123; remixstid=1; remixlang=61"
        assertEquals(jar, RoomServices.VKCall.cookieHeader(jar))
    }

    @Test fun vkBareValueGainsItsName() {
        assertEquals("remixsid=abc123", RoomServices.VKCall.cookieHeader("abc123"))
    }

    @Test fun vkAJarOfOneCookieIsStillAJar() {
        assertEquals("remixsid=abc123", RoomServices.VKCall.cookieHeader("remixsid=abc123"))
    }

    // MARK: VK sign-in verdicts

    @Test fun vkVerdictOfAnAcceptedExchange() {
        assertEquals(
            RoomServices.VKCall.Verdict.Accepted,
            RoomServices.VKCall.verdictOf("{\"type\":\"okay\",\"data\":{\"access_token\":\"tok.en-1_2\",\"user_id\":1}}"),
        )
    }

    @Test fun vkVerdictOfARefusalCarriesItsReason() {
        assertEquals(
            RoomServices.VKCall.Verdict.Refusal("unauthorized"),
            RoomServices.VKCall.verdictOf("{\"type\":\"error\",\"error_code\":\"\",\"error_info\":\"unauthorized\"}"),
        )
        assertEquals(
            RoomServices.VKCall.Verdict.Refusal("wrong origin"),
            RoomServices.VKCall.verdictOf("{\"type\":\"error\",\"error_code\":\"\",\"error_info\":\"wrong origin\"}"),
        )
    }

    @Test fun vkVerdictOfARefusalWithNoReasonStillRefuses() {
        assertEquals(RoomServices.VKCall.Verdict.Refusal("unknown"), RoomServices.VKCall.verdictOf("{\"type\":\"error\"}"))
    }

    @Test fun vkVerdictOfAnythingElseIsSilence() {
        val answers = listOf(
            "",
            "not json",
            "{}",
            "{\"type\":\"okay\",\"data\":{}}",
            "{\"data\":{\"access_token\":\"\"}}",
            "{\"error\":{\"error_code\":5,\"error_msg\":\"no\"}}",
        )
        for (answer in answers) {
            assertEquals(answer, RoomServices.VKCall.Verdict.NoAnswer, RoomServices.VKCall.verdictOf(answer))
        }
    }

    @Test fun vkVerdictLines() {
        assertEquals("VK sign-in check: accepted", RoomServices.VKCall.verdictLine(RoomServices.VKCall.Verdict.Accepted))
        assertEquals(
            "VK sign-in check: refused (unauthorized)",
            RoomServices.VKCall.verdictLine(RoomServices.VKCall.Verdict.Refusal("unauthorized")),
        )
        assertEquals(
            "VK sign-in check: refused (wrong origin)",
            RoomServices.VKCall.verdictLine(RoomServices.VKCall.Verdict.Refusal("wrong origin")),
        )
        assertEquals("VK sign-in check: no answer", RoomServices.VKCall.verdictLine(RoomServices.VKCall.Verdict.NoAnswer))
    }

    @Test fun vkVerdictLineNeverCarriesAnythingShapedLikeASecret() {
        val reasons = listOf(
            "remixsid=abc123def456",
            "Bearer eyJhbGciOi.payload.sig",
            "token 1234",
            "a".repeat(80),
            "",
            "line\nbreak",
        )
        for (reason in reasons) {
            assertEquals(
                "VK sign-in check: refused (other)",
                RoomServices.VKCall.verdictLine(RoomServices.VKCall.Verdict.Refusal(reason)),
            )
        }
    }

    // MARK: MTS Link links

    private val mtsLink = "https://my.mts-link.ru/j/100200300/400500600700"

    @Test fun mtsLinkKeepsTheCanonicalLink() {
        assertEquals(mtsLink, RoomServices.MTSLink.normalized(mtsLink))
    }

    @Test fun mtsLinkTakesEitherHostAnySchemeOrNone() {
        val pasted = listOf(
            "http://my.mts-link.ru/j/100200300/400500600700",
            "https://mts-link.ru/j/100200300/400500600700",
            "my.mts-link.ru/j/100200300/400500600700",
            "HTTPS://MY.MTS-LINK.RU/J/100200300/400500600700",
        )
        for (raw in pasted) assertEquals(raw, mtsLink, RoomServices.MTSLink.normalized(raw))
    }

    @Test fun mtsLinkComesOutOfTheTextAroundIt() {
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("Подключиться к встрече: $mtsLink — до встречи"))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("(the room: $mtsLink)."))
    }

    @Test fun mtsLinkAcceptsAnAliasOwnerAndReadsItAsZero() {
        // The service also names owners by alias; the guest flow needs the event alone, so every alias reads as `0`.
        assertEquals(
            "https://my.mts-link.ru/j/0/1532161965",
            RoomServices.MTSLink.normalized("https://my.mts-link.ru/j/supportwebinar/1532161965"),
        )
        assertEquals("https://my.mts-link.ru/j/0/400500600700", RoomServices.MTSLink.normalized("https://my.mts-link.ru/j/abc/400500600700"))
        // An alias may carry `.`, `-` and `_`, and the link around it drops as usual.
        assertEquals(
            "https://my.mts-link.ru/j/0/1532161965?ice=tcp",
            RoomServices.MTSLink.normalized("my.mts-link.ru/j/support.webinar-1_x/1532161965/session/700800900?ice=tcp"),
        )
    }

    @Test fun mtsLinkKeepsANumericOwnerAsItIs() {
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("https://my.mts-link.ru/j/100200300/400500600700"))
    }

    @Test fun mtsLinkDropsTheSessionOtherParametersAndTheFragment() {
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$mtsLink/session/700800900"))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$mtsLink?utm_source=mail&lang=ru#chat"))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$mtsLink/"))
    }

    @Test fun mtsLinkTakesTheStreamNewShapeAsTheEventLink() {
        // The page a guest lands on: the event is the room, the stream and its session are dropped.
        val streamNew = "$mtsLink/stream-new/700800900100"
        assertEquals(mtsLink, RoomServices.MTSLink.normalized(streamNew))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$streamNew/"))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$streamNew?utm_source=mail&lang=ru#chat"))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("my.mts-link.ru/j/100200300/400500600700/stream-new/700800900100"))
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("Подключиться: $streamNew — до встречи"))
        assertEquals("400500600700", RoomServices.MTSLink.eventID(streamNew))
        assertEquals("400500600700", RoomServices.MTSLink.eventID(RoomServices.MTSLink.normalized(streamNew).orEmpty()))
        assertEquals("$mtsLink?ice=tcp", RoomServices.MTSLink.normalized("$streamNew?ice=tcp"))
        assertEquals("$mtsLink?ice=udp", RoomServices.MTSLink.normalized("$streamNew?utm=1&ICE=UDP"))
    }

    @Test fun mtsLinkStreamNewWithAnAliasOwnerReadsAsZero() {
        assertEquals(
            "https://my.mts-link.ru/j/0/400500600700",
            RoomServices.MTSLink.normalized("https://my.mts-link.ru/j/supportwebinar/400500600700/stream-new/700800900100"),
        )
    }

    @Test fun mtsLinkKeepsAKnownIceHint() {
        assertEquals("$mtsLink?ice=tcp", RoomServices.MTSLink.normalized("$mtsLink?ice=tcp"))
        assertEquals("$mtsLink?ice=both", RoomServices.MTSLink.normalized("$mtsLink?utm_source=mail&ice=both#chat"))
        assertEquals("$mtsLink?ice=udp", RoomServices.MTSLink.normalized("$mtsLink/session/700800900?ICE=UDP"))
        assertEquals("$mtsLink?ice=tcp", RoomServices.MTSLink.normalized("Ссылка: $mtsLink?ice=tcp."))
    }

    @Test fun mtsLinkDropsAHintTheCoreDoesNotKnow() {
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$mtsLink?ice=quic"))
        // After `#` it is no part of the query.
        assertEquals(mtsLink, RoomServices.MTSLink.normalized("$mtsLink#ice=tcp"))
    }

    @Test fun mtsLinkRejectsAnythingElse() {
        val notMeetings = listOf(
            "",
            "not a link",
            "100200300/400500600700",
            "https://my.mts-link.ru/j/100200300",
            // An alias owner with no event is no meeting either.
            "https://my.mts-link.ru/j/abc",
            "https://my.mts-link.ru/j/" + "a".repeat(65) + "/400500600700",
            "https://my.mts-link.ru/j/100200300/400500600700abc",
            "https://my.mts-link.ru/j/100200300/" + "1".repeat(21),
            "https://my.mts-link.ru/events/400500600700",
            "https://evil-mts-link.ru/j/100200300/400500600700",
            "https://notmy.mts-link.ru/j/100200300/400500600700",
            "https://my.mts-link.ru.example.com/j/100200300/400500600700",
            "https://vk.ru/call/join/abc",
        )
        for (raw in notMeetings) assertNull(raw, RoomServices.MTSLink.normalized(raw))
    }

    @Test fun mtsLinkPlainCutsTheHintTheWayTheCoreDoes() {
        assertEquals(mtsLink, RoomServices.MTSLink.plain("$mtsLink?ice=tcp"))
        assertEquals(mtsLink, RoomServices.MTSLink.plain("$mtsLink?ice=both"))
        assertEquals("$mtsLink?a=1", RoomServices.MTSLink.plain("$mtsLink?a=1&ice=udp"))
        assertEquals(mtsLink, RoomServices.MTSLink.plain(" $mtsLink?ice=tcp "))
    }

    @Test fun mtsLinkPlainLeavesEverythingElseAsItWas() {
        assertEquals(mtsLink, RoomServices.MTSLink.plain(mtsLink))
        assertEquals("$mtsLink?ice=quic", RoomServices.MTSLink.plain("$mtsLink?ice=quic"))
        // Only a hint at the very end is one, as in the core.
        assertEquals("$mtsLink?ice=tcp&a=1", RoomServices.MTSLink.plain("$mtsLink?ice=tcp&a=1"))
    }

    @Test fun mtsLinkPlainOfANormalizedLinkIsTheMeetingLink() {
        assertEquals(mtsLink, RoomServices.MTSLink.plain(RoomServices.MTSLink.normalized("$mtsLink?utm=1&ice=tcp").orEmpty()))
    }

    @Test fun anMtsLinkRoomFieldTakesTheMeetingLinkOnly() {
        assertEquals("$mtsLink?ice=tcp", RoomServices.normalizedRoom("mtslink", " $mtsLink?ice=tcp "))
        assertEquals("", RoomServices.normalizedRoom("mtslink", "100200300/400500600700"))
        assertEquals("", RoomServices.normalizedRoom("mtslink", ""))
    }

    // MARK: room identity

    @Test fun anIceHintMakesNoOtherMtsLinkRoom() {
        assertEquals(mtsLink, RoomServices.roomIdentity("mtslink", "$mtsLink?ice=tcp"))
        assertEquals(mtsLink, RoomServices.roomIdentity("mtslink", "$mtsLink?ice=both"))
        assertEquals(mtsLink, RoomServices.roomIdentity("mtslink", mtsLink))
        assertTrue(RoomServices.sameRoom("mtslink", "$mtsLink?ice=tcp", mtsLink))
        assertTrue(RoomServices.sameRoom("mtslink", "$mtsLink?ice=tcp", "$mtsLink?ice=both"))
        assertTrue(RoomServices.sameRoom("mtslink", "$mtsLink?ice=udp", "$mtsLink?ice=both"))
        assertFalse(RoomServices.sameRoom("mtslink", mtsLink, "https://my.mts-link.ru/j/100200300/400500600701"))
    }

    @Test fun aServerGetsTheTypedIceHintOrBoth() {
        assertEquals("$mtsLink?ice=tcp", RoomServices.serverRoom("mtslink", "$mtsLink?ice=tcp"))
        assertEquals("$mtsLink?ice=udp", RoomServices.serverRoom("mtslink", "$mtsLink?ice=udp"))
        assertEquals("$mtsLink?ice=both", RoomServices.serverRoom("mtslink", "$mtsLink?ice=both"))
        // No hint typed: UDP first, TCP as the fallback.
        assertEquals("$mtsLink?ice=both", RoomServices.serverRoom("mtslink", mtsLink))
    }

    @Test fun theServerRoomIsTheSameRoomAndNeverGrowsTwoHints() {
        for (typed in listOf(mtsLink, "$mtsLink?ice=tcp", "$mtsLink?ice=udp")) {
            val once = RoomServices.serverRoom("mtslink", typed)
            assertEquals(once, RoomServices.serverRoom("mtslink", once))
            assertTrue(RoomServices.sameRoom("mtslink", typed, once))
            assertEquals(1, Regex("ice=").findAll(once).count())
        }
    }

    @Test fun theServerRoomOfAStreamNewLinkIsTheEventWithTheHint() {
        val pasted = "$mtsLink/stream-new/700800900100?ice=udp"
        val room = RoomServices.normalizedRoom("mtslink", pasted)
        assertEquals("$mtsLink?ice=udp", room)
        assertEquals("$mtsLink?ice=udp", RoomServices.serverRoom("mtslink", room))
        assertEquals(mtsLink, RoomServices.roomIdentity("mtslink", room))
    }

    @Test fun aServerRoomOfAnythingButAMeetingLinkIsLeftAlone() {
        assertEquals("", RoomServices.serverRoom("mtslink", ""))
        assertEquals("not a link", RoomServices.serverRoom("mtslink", "not a link"))
    }

    @Test fun otherServicesCompareTheirRoomsAsTheyAre() {
        // The hint is MTS Link's: another service's room keeps whatever it says.
        assertEquals("https://meet.example/r?ice=tcp", RoomServices.serverRoom("jitsi", "https://meet.example/r?ice=tcp"))
        assertEquals("https://meet.example/r?ice=tcp", RoomServices.roomIdentity("jitsi", "https://meet.example/r?ice=tcp"))
        assertFalse(RoomServices.sameRoom("jitsi", "https://meet.example/r?ice=tcp", "https://meet.example/r"))
        assertTrue(RoomServices.sameRoom("vkcalls", "https://vk.ru/call/join/a", "https://vk.ru/call/join/a"))
    }

    @Test fun thePhoneKeepsTheRoomItWasGiven() {
        // The server confirmed the plain link: the phone keeps the hint it was given.
        assertEquals("$mtsLink?ice=tcp", RoomServices.phoneRoom("mtslink", "$mtsLink?ice=tcp", mtsLink))
        // A server that names another room, or nothing entered: the server's room.
        assertEquals(mtsLink, RoomServices.phoneRoom("mtslink", "https://my.mts-link.ru/j/1/2?ice=tcp", mtsLink))
        assertEquals(mtsLink, RoomServices.phoneRoom("mtslink", "", mtsLink))
        // The server confirmed the room with the hint it was given: the phone keeps what was typed, and a link typed without a hint stays without one.
        assertEquals(mtsLink, RoomServices.phoneRoom("mtslink", mtsLink, "$mtsLink?ice=both"))
        assertEquals("$mtsLink?ice=tcp", RoomServices.phoneRoom("mtslink", "$mtsLink?ice=tcp", "$mtsLink?ice=tcp"))
        // A Jitsi name the server widened to a link: the server's.
        assertEquals("https://meet.example/olcrtc-ab", RoomServices.phoneRoom("jitsi", "olcrtc-ab", "https://meet.example/olcrtc-ab"))
    }

    // MARK: what a room change leaves behind

    @Test fun aRoomChangeStampsTheAgeUnderThePhoneSideRoom() {
        // The server confirms the plain link: the record keeps the room with its hint, and its age is keyed by that spelling.
        val out = RoomServices.delivered("mtslink", "$mtsLink?ice=tcp", mtsLink, "$mtsLink?ice=udp")
        assertEquals("$mtsLink?ice=tcp", out.room)
        assertEquals("$mtsLink?ice=udp", out.forget)
    }

    @Test fun aHintLessRoomIsDeliveredExactlyAsBefore() {
        for ((carrier, room) in listOf("mtslink" to mtsLink, "jitsi" to "https://meet.example/olcrtc-ab", "vkcalls" to "https://vk.ru/call/join/a")) {
            val out = RoomServices.delivered(carrier, room, room, room)
            assertEquals(carrier, room, out.room)
            assertEquals(carrier, room, out.forget)
        }
    }

    @Test fun aRoomTheServerNamedIsDeliveredAsTheServersOwn() {
        // A Jitsi name the server widened to a link: the server's, and the replaced room's own spelling is what is forgotten.
        val out = RoomServices.delivered("jitsi", "olcrtc-ab", "https://meet.example/olcrtc-ab", "olcrtc-ab")
        assertEquals("https://meet.example/olcrtc-ab", out.room)
        assertEquals("olcrtc-ab", out.forget)
    }

    // MARK: MTS Link events

    @Test fun nothingIsLearnedWhereTheAppIsNotRunning() {
        // No app graph in a unit test: the replay is not on offer.
        assertFalse(RoomServices.MTSLink.canCreate)
    }

    @Test fun mtsLinkEventIDIsTheOneTheCreatedListKeeps() {
        assertEquals("400500600700", RoomServices.MTSLink.eventID(mtsLink))
        assertEquals("400500600700", RoomServices.MTSLink.eventID("$mtsLink?ice=tcp"))
        assertEquals("1532161965", RoomServices.MTSLink.eventID("https://my.mts-link.ru/j/supportwebinar/1532161965"))
        assertNull(RoomServices.MTSLink.eventID("100200300/400500600700"))
    }

    @Test fun mtsLinkSessionStateOfTheProbeStatus() {
        assertEquals(RoomServices.MTSLink.SessionState.ALIVE, RoomServices.MTSLink.sessionState(200))
        assertEquals(RoomServices.MTSLink.SessionState.DEAD, RoomServices.MTSLink.sessionState(401))
        assertEquals(RoomServices.MTSLink.SessionState.DEAD, RoomServices.MTSLink.sessionState(403))
        // A redirect or a hiccup is not an answer about the session: no false alarm.
        assertEquals(RoomServices.MTSLink.SessionState.UNKNOWN, RoomServices.MTSLink.sessionState(302))
        assertEquals(RoomServices.MTSLink.SessionState.UNKNOWN, RoomServices.MTSLink.sessionState(500))
    }
}
