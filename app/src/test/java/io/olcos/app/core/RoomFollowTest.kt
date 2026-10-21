package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connections follow the server: a protocol record whose room changed
 * on the server takes the new one (G6), a live whole-server session takes a
 * lane's new room without starting over (G4), and a lane that stays dead
 * past the limit is reported, once and then now and then.
 */
class RoomFollowTest {
    private val key = "b".repeat(64)

    private fun room(provider: String, id: String, file: String = "server-$provider.yaml", transport: String = "datachannel", up: Boolean = true) =
        ServerStatus.Room(file, provider, transport, id, "olcrtc-x-" + file.removeSuffix(".yaml"), if (up) "Up 1 hour" else "Exited (0)", file == "server.yaml")

    private fun record(id: String, link: OlcrtcLink) = ConnectionRecord(id = id, name = id, details = Details.Olcrtc(link))

    // MARK: Protocol records follow the server's rooms (G6)

    @Test
    fun aProtocolRecordTakesTheRoomThatReplacedItsOwnOnTheServer() {
        val telemost = record("t", OlcrtcLink("telemost", "datachannel", "old-room-1", key))
        val jitsi = record("j", OlcrtcLink("jitsi", "datachannel", "https://meet.example.org/room-j", key))
        val rooms = listOf(room("jitsi", "https://meet.example.org/room-j", "server.yaml"), room("telemost", "new-room-2"))
        val moved = RoomFollow.protocolRecords(listOf(telemost, jitsi), rooms)
        assertEquals(1, moved.size)
        assertEquals("t", moved[0].id)
        assertEquals("new-room-2", moved[0].olcrtc!!.roomID)
    }

    @Test
    fun aRecordWhoseRoomsAreAllOnTheServerIsLeftAlone() {
        val telemost = record("t", OlcrtcLink("telemost", "datachannel", "room-a", key, extraRooms = listOf("room-b")))
        val rooms = listOf(room("telemost", "room-a", "server.yaml"), room("telemost", "room-b", "server-telemost-2.yaml"), room("telemost", "room-c", "server-telemost-3.yaml"))
        // A room added on the server is not pushed into a record here; only a room that changed is followed.
        assertTrue(RoomFollow.protocolRecords(listOf(telemost), rooms).isEmpty())
    }

    @Test
    fun anExtraRoomThatChangedIsFollowedInItsPlace() {
        val telemost = record("t", OlcrtcLink("telemost", "datachannel", "room-a", key, extraRooms = listOf("room-b", "room-c")))
        val rooms = listOf(room("telemost", "room-a", "server.yaml"), room("telemost", "room-b2", "server-telemost-2.yaml"), room("telemost", "room-c", "server-telemost-3.yaml"))
        val moved = RoomFollow.protocolRecords(listOf(telemost), rooms).single().olcrtc!!
        assertEquals("room-a", moved.roomID)
        assertEquals(listOf("room-b2", "room-c"), moved.extraRooms)
    }

    @Test
    fun aNewRoomAnotherRecordAlreadyRidesIsNotTakenTwice() {
        val first = record("a", OlcrtcLink("vkcalls", "datachannel", "gone-1", key))
        val second = record("b", OlcrtcLink("vkcalls", "datachannel", "room-2", key))
        val rooms = listOf(room("vkcalls", "room-2"))
        assertTrue(RoomFollow.protocolRecords(listOf(first, second), rooms).isEmpty())
    }

    @Test
    fun aRoomOfAnotherTransportOrAMissingContainerIsNotTaken() {
        val telemost = record("t", OlcrtcLink("telemost", "datachannel", "old", key))
        val vp8 = room("telemost", "fresh-vp8", transport = "vp8channel")
        val missing = ServerStatus.Room("server-telemost-2.yaml", "telemost", "datachannel", "fresh-missing", "c", "missing", false)
        assertTrue(RoomFollow.protocolRecords(listOf(telemost), listOf(vp8, missing)).isEmpty())
    }

    @Test
    fun aStoppedRoomIsStillTheServersRoom() {
        val telemost = record("t", OlcrtcLink("telemost", "datachannel", "old", key))
        val moved = RoomFollow.protocolRecords(listOf(telemost), listOf(room("telemost", "fresh", up = false)))
        assertEquals("fresh", moved.single().olcrtc!!.roomID)
    }

    @Test
    fun aWholeServerRecordIsNotAProtocolRecord() {
        val bundle = record("all", OlcrtcLink("jitsi", "datachannel", "j-old", key, with = listOf(Lane("telemost", "datachannel", "t1"))))
        assertTrue(RoomFollow.protocolRecords(listOf(bundle), listOf(room("jitsi", "j-new", "server.yaml"))).isEmpty())
    }

    @Test
    fun anMtsLinkRoomKeepsItsIceHintWhenTheServerNamesItPlain() {
        val hinted = "https://my.mts-link.ru/j/100200300/400500600700?ice=tcp"
        val plain = "https://my.mts-link.ru/j/100200300/400500600700"
        val mts = record("m", OlcrtcLink("mtslink", "vp8channel", hinted, key))
        assertTrue(RoomFollow.protocolRecords(listOf(mts), listOf(room("mtslink", plain, transport = "vp8channel"))).isEmpty())
    }

    // MARK: A live whole-server session takes a lane's new room (G4)

    private val live = OlcrtcLink("jitsi", "datachannel", "j1", key, extraRooms = listOf("j2"),
        with = listOf(Lane("telemost", "datachannel", "t1"), Lane("vkcalls", "datachannel", "v1")), bond = "203.0.113.7:40000")

    @Test
    fun aLaneWhoseRoomChangedIsTheOnlyChange() {
        val fresh = live.copy(with = listOf(Lane("telemost", "datachannel", "t2"), Lane("vkcalls", "datachannel", "v1")))
        assertEquals(mapOf(2 to Lane("telemost", "datachannel", "t2")), RoomFollow.laneChanges(live, fresh))
    }

    @Test
    fun nothingChangedIsAnEmptyChange() {
        assertEquals(emptyMap<Int, Lane>(), RoomFollow.laneChanges(live, live.copy(vp8FPS = 60)))
    }

    @Test
    fun anotherShapeKeyOrEndpointNeedsTheSessionStartedOver() {
        assertNull(RoomFollow.laneChanges(live, live.copy(extraRooms = emptyList())))
        assertNull(RoomFollow.laneChanges(live, live.copy(key = "c".repeat(64))))
        assertNull(RoomFollow.laneChanges(live, live.copy(bond = null)))
        assertNull(RoomFollow.laneChanges(live, live.copy(with = listOf(Lane("vkcalls", "datachannel", "v1"), Lane("telemost", "datachannel", "t1")))))
    }

    // MARK: A lane that stays dead

    @Test
    fun aLaneIsReportedOnceItStaysDownPastTheLimitThenOnlyNowAndThen() {
        val watch = LaneDeathWatch(limitMs = 45_000, repeatMs = 600_000)
        assertFalse(watch.observe(1, up = false, now = 0))
        assertFalse(watch.observe(1, up = false, now = 44_999))
        assertTrue(watch.observe(1, up = false, now = 45_000))
        assertFalse(watch.observe(1, up = false, now = 60_000))
        assertEquals(setOf(1), watch.dead(60_000))
        assertTrue(watch.observe(1, up = false, now = 645_000))
    }

    @Test
    fun aLaneThatComesBackIsNoLongerDead() {
        val watch = LaneDeathWatch(limitMs = 45_000)
        watch.observe(0, up = false, now = 0)
        watch.observe(0, up = false, now = 50_000)
        watch.observe(0, up = true, now = 53_000)
        assertTrue(watch.dead(53_000).isEmpty())
        assertFalse(watch.observe(0, up = false, now = 56_000))
    }

    @Test
    fun aLaneGivenANewRoomGetsAFreshGrace() {
        val watch = LaneDeathWatch(limitMs = 45_000)
        watch.observe(2, up = false, now = 0)
        assertTrue(watch.observe(2, up = false, now = 45_000))
        watch.forget(2)
        assertTrue(watch.dead(46_000).isEmpty())
        assertFalse(watch.observe(2, up = false, now = 46_000))
        assertTrue(watch.observe(2, up = false, now = 91_000))
    }
}
