package io.olcos.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An olcrtc:// link whose room carries a query of its own: an MTS Link
 * meeting link with its ICE hint. The `?` inside the room is not the
 * carrier's, and writing the link back leaves the room as it was.
 */
class OlcrtcLinkTest {
    private val key = "0123456789abcdef".repeat(4)
    private val room = "https://my.mts-link.ru/j/100200300/400500600700?ice=tcp"
    private val raw = "olcrtc://mtslink?vp8channel@$room#$key\$name"

    @Test fun theRoomKeepsItsOwnQuery() {
        assertEquals(OlcrtcLink("mtslink", "vp8channel", room, key, mimo = "name"), OlcrtcLink.parse(raw))
    }

    @Test fun theLinkIsWrittenBackWithItsRoomUnchanged() {
        val link = OlcrtcLink.parse(raw)!!
        // The app writes its links without the `$` comment, as the iOS app does; everything before it comes back byte for byte.
        assertEquals(raw.substringBeforeLast('$'), link.encode())
        assertEquals(link.copy(mimo = ""), OlcrtcLink.parse(link.encode()))
    }

    @Test fun aLinkWithoutTheCommentRoundTripsByteForByte() {
        val plain = "olcrtc://mtslink?vp8channel@$room#$key"
        assertEquals(plain, OlcrtcLink.parse(plain)!!.encode())
    }

    @Test fun aLaneOfTheRoomReadsBack() {
        val lane = Lane("mtslink", "vp8channel", room)
        assertEquals("mtslink?vp8channel@$room", lane.text())
        assertEquals(lane, Lane.parse(lane.text()))
    }

    @Test fun mtsLinkHasItsName() {
        assertEquals("MTS Link", OlcrtcLink.carrierLabel("mtslink"))
    }

    // The app no longer offers WB Stream, but a saved connection or a list entry of it still reads, shows its name and connects.
    @Test fun anOldWbStreamConnectionStillReadsAndKeepsItsName() {
        val old = "olcrtc://wbstream?datachannel@room-1#$key"
        val link = OlcrtcLink.parse(old)!!
        assertEquals("wbstream", link.carrier)
        assertEquals("WB Stream", link.serviceName)
        assertEquals(old, link.encode())
        assertEquals(listOf(Lane("wbstream", "datachannel", "room-1")), link.lanes)
    }

    // MARK: Tuning numbers a link carries (the iOS OlcrtcURI grammar)

    @Test fun aSeiLinkKeepsItsFourNumbers() {
        val link = OlcrtcLink.parse("olcrtc://jitsi?seichannel[fps=25,batch=8,frag=900,ack-ms=40]@room-sei#$key")!!
        assertEquals(25, link.seiFPS)
        assertEquals(8, link.seiBatch)
        assertEquals(900, link.seiFrag)
        assertEquals(40, link.seiACK)
        // Bare fps/batch of a SEI link are SEI numbers, never VP8 ones.
        assertEquals(null, link.vp8FPS)
        assertEquals(null, link.vp8Batch)
    }

    @Test fun aSeiLinkIsWrittenBackWithItsNumbers() {
        val text = "olcrtc://jitsi?seichannel[fps=25,batch=8,frag=900,ack-ms=40]@room-sei#$key"
        val link = OlcrtcLink.parse(text)!!
        assertEquals(text, link.encode())
        assertEquals(link, OlcrtcLink.parse(link.encode()))
    }

    @Test fun aSeiLinkWithoutNumbersStaysWithout() {
        val text = "olcrtc://jitsi?seichannel@room-sei#$key"
        val link = OlcrtcLink.parse(text)!!
        assertEquals(null, link.seiOptions())
        assertEquals(text, link.encode())
    }

    @Test fun theBareFpsAndBatchOfAnOlderVp8LinkAreItsVp8Numbers() {
        val link = OlcrtcLink.parse("olcrtc://vkcalls?vp8channel[fps=45,batch=16]@room-v#$key")!!
        assertEquals(45, link.vp8FPS)
        assertEquals(16, link.vp8Batch)
        // Written back in the apps' own spelling.
        assertEquals("olcrtc://vkcalls?vp8channel[vp8-fps=45,vp8-batch=16]@room-v#$key", link.encode())
    }

    @Test fun theServersAngleBracketFormReadsTheSameKeys() {
        val link = OlcrtcLink.parse("olcrtc://jitsi?seichannel<fps=30&batch=10&frag=1200&ack-ms=1>@room-sei#$key")!!
        assertEquals(listOf(30, 10, 1200, 1), link.seiOptions())
    }

    @Test fun theSeiNumbersTheEngineSetsFillTheMissingOnesAndStayInRange() {
        val partial = OlcrtcLink("jitsi", "seichannel", "room-sei", key, seiFPS = 500, seiFrag = 0)
        // The iOS defaults (30/10/1200/1) fill what the link left out; out-of-range values are brought into the iOS ranges.
        assertEquals(listOf(120, 10, 100, 1), partial.seiOptions())
    }
}
