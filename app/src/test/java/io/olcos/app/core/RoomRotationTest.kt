package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions of the room auto-update: when a room is due for a
 * fresh one, and which intervals the setting keeps. No server, no store,
 * no clock.
 */
class RoomRotationTest {
    // MARK: due

    @Test fun offNeverRotates() {
        assertFalse(RoomRotation.due("vkcalls", 0, createdAt = 1000L, now = 999_999L))
    }

    @Test fun telemostIsRenewedByItsOwnLifetimeInstead() {
        assertFalse(RoomRotation.due("telemost", 24, createdAt = 0L, now = 10_000_000L))
    }

    @Test fun aFreshRoomIsLeftAlone() {
        assertFalse(RoomRotation.due("vkcalls", 24, createdAt = 1000L, now = 1000L + 23 * 3600L))
        assertFalse(RoomRotation.due("wbstream", 168, createdAt = 1000L, now = 1000L + 100 * 3600L))
    }

    @Test fun anOldRoomIsDue() {
        assertTrue(RoomRotation.due("vkcalls", 24, createdAt = 1000L, now = 1000L + 24 * 3600L))
        assertTrue(RoomRotation.due("wbstream", 168, createdAt = 1000L, now = 1000L + 169 * 3600L))
    }

    @Test fun aRoomOfUnknownAgeIsLeftAlone() {
        assertFalse(RoomRotation.due("vkcalls", 24, createdAt = null, now = 10_000_000L))
    }

    // MARK: the intervals

    @Test fun onlyTheOfferedIntervalsExist() {
        assertEquals(0, RoomRotation.intervalHours(0))
        assertEquals(24, RoomRotation.intervalHours(24))
        assertEquals(168, RoomRotation.intervalHours(168))
        assertEquals(0, RoomRotation.intervalHours(25))
        assertEquals(0, RoomRotation.intervalHours(-24))
    }
}
