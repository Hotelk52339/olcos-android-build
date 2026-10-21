package io.olcos.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one make-a-room flow where it decides without a network or a sign-in:
 * An unknown protocol (WB Stream among them) answers Manual, MTS Link before the app has
 * learned how a meeting is made opens the sheet with the reason, and Jitsi
 * mints a fresh room at once on the server of the room being replaced. The
 * signed-in creators and the MTS Link replay are integration paths: they need
 * the network (or a web view) and a kept sign-in.
 */
class RoomMintTest {
    @Test fun wbStreamIsNoLongerMade() = runBlocking {
        assertEquals(RoomMint.Outcome.Manual, RoomMint.mint("wbstream"))
    }

    @Test fun mtsLinkOpensTheSheetWithAReasonUntilTheAppHasLearned() = runBlocking {
        val outcome = RoomMint.mint("mtslink", "https://my.mts-link.ru/j/100200300/400500600700")
        assertEquals(RoomMint.Outcome.SignIn(RoomMint.Service.MTS, rejected = false, reason = io.olcos.app.R.string.mts_fallback_not_learned), outcome)
    }

    @Test fun anUnknownProtocolIsTypedByHand() = runBlocking {
        assertEquals(RoomMint.Outcome.Manual, RoomMint.mint("unknown"))
    }

    @Test fun jitsiMintsAFreshRoomOnTheSameServer() = runBlocking {
        val outcome = RoomMint.mint("jitsi", "https://meet.example.org/olcrtc-old")
        assertTrue(outcome is RoomMint.Outcome.Made)
        val made = outcome as RoomMint.Outcome.Made
        assertTrue(made.room, made.room.startsWith("https://meet.example.org/olcrtc-"))
    }

    @Test fun jitsiWithoutARoomNearItMintsOnTheDefaultServer() = runBlocking {
        val outcome = RoomMint.mint("jitsi")
        assertTrue(outcome is RoomMint.Outcome.Made)
        val made = outcome as RoomMint.Outcome.Made
        assertTrue(made.room, made.room.startsWith(Maintenance.DEFAULT_JITSI_BASE))
    }
}
