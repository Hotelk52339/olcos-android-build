package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the room services: the link a typed or API-given VK value
 * normalizes to, and the values and envelopes the create answers carry. No
 * network here.
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

    // MARK: WB Stream create answers

    @Test fun wbRoomID() {
        assertEquals("987", RoomServices.WBStream.roomID("{\"roomId\":\"987\"}"))
    }

    @Test fun wbRoomIDRejectsAnErrorAnswer() {
        assertNull(RoomServices.WBStream.roomID("{\"error\":\"no\"}"))
    }

    // MARK: WB Stream guest refusals

    @Test fun wbGuestRefusalOnTheCreateGate() {
        assertTrue(
            RoomServices.WBStream.guestRefusal(400, "{\"code\":3,\"message\":\"Guests are not allowed to create room\",\"details\":[]}"),
        )
    }

    @Test fun wbGuestRefusalOnThePermissionCode() {
        assertTrue(RoomServices.WBStream.guestRefusal(403, "{\"code\":7,\"message\":\"guests cannot create rooms\"}"))
    }

    @Test fun wbGuestRefusalStaysOutOfOtherAnswers() {
        // A validation refusal carries code 3 too, but it is not the guest gate.
        assertFalse(
            RoomServices.WBStream.guestRefusal(400, "{\"code\":3,\"message\":\"invalid CreateRoomV2Request.RoomType: value must not be in list [ROOM_TYPE_UNSPECIFIED]\"}"),
        )
        assertFalse(RoomServices.WBStream.guestRefusal(403, "{\"code\":5,\"message\":\"not found\"}"))
        assertFalse(RoomServices.WBStream.guestRefusal(401, "{\"code\":16,\"message\":\"unauthenticated\"}"))
        assertFalse(RoomServices.WBStream.guestRefusal(400, "not json"))
    }

    // MARK: WB Stream session probe

    @Test fun wbSessionStateOfTheProbeStatus() {
        assertEquals(RoomServices.WBStream.SessionState.ALIVE, RoomServices.WBStream.sessionState(200))
        assertEquals(RoomServices.WBStream.SessionState.DEAD, RoomServices.WBStream.sessionState(401))
        // Anything else is not an answer about the token: no false alarm.
        assertEquals(RoomServices.WBStream.SessionState.UNKNOWN, RoomServices.WBStream.sessionState(403))
        assertEquals(RoomServices.WBStream.SessionState.UNKNOWN, RoomServices.WBStream.sessionState(500))
    }

    // MARK: WB Stream login gate

    @Test fun wbPastLoginOnTheStreamHostPastTheForm() {
        assertTrue(RoomServices.WBStream.pastLogin("https://stream.wb.ru/"))
        assertTrue(RoomServices.WBStream.pastLogin("https://stream.wb.ru/room/019dfce8-c3a6-7b44-a308-2d2f03a634b9"))
    }

    @Test fun wbPastLoginStaysShutOnTheLoginForm() {
        assertFalse(RoomServices.WBStream.pastLogin("https://stream.wb.ru/login"))
        assertFalse(RoomServices.WBStream.pastLogin("https://stream.wb.ru/login?return=/room/1"))
    }

    @Test fun wbPastLoginIgnoresALoginElsewhereInTheURL() {
        assertTrue(RoomServices.WBStream.pastLogin("https://stream.wb.ru/?from=login"))
        assertTrue(RoomServices.WBStream.pastLogin("https://stream.wb.ru/room/login-ish"))
    }

    @Test fun wbPastLoginRefusesOtherHostsAndEmptyURLs() {
        assertFalse(RoomServices.WBStream.pastLogin("https://passport.wb.ru/auth"))
        assertFalse(RoomServices.WBStream.pastLogin(""))
    }
}
