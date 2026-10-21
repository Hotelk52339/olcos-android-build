package io.olcos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What keeps the server page still: a refetch of an unchanged server reads
 * the same (only the event counter moves), and a refetch that an event asked
 * for waits out the window since the last one.
 */
class ServerStatusTest {
    private fun status(version: Long, uptime: String = "up 3 days") = ServerStatus(
        readiness = ServerStatus.Readiness.RUNNING, containerStatus = "Up 3 days",
        disk = "40%", ram = "512M", uptime = uptime,
        rooms = listOf(ServerStatus.Room("server.yaml", "jitsi", "datachannel", "room-a", "olcrtc-server", "Up 3 days", true)),
        xray = emptyList(), version = version,
    )

    @Test
    fun onlyTheEventCounterDiffersBetweenTwoReadingsOfAnUnchangedServer() {
        assertTrue(status(7).sameReadingAs(status(8)))
    }

    @Test
    fun aChangedFigureIsAChangedReading() {
        assertFalse(status(7).sameReadingAs(status(8, uptime = "up 4 days")))
    }

    @Test
    fun noReadingYetIsNeverTheSame() {
        assertFalse(status(7).sameReadingAs(null))
    }

    @Test
    fun theFirstFetchIsNotHeld() {
        assertEquals(0L, ServerStatus.holdBeforeRefetch(lastFetchAt = 0L, now = 123_456L))
    }

    @Test
    fun aRefetchWaitsOutTheRestOfTheWindow() {
        assertEquals(20_000L, ServerStatus.holdBeforeRefetch(lastFetchAt = 100_000L, now = 110_000L))
        assertEquals(ServerStatus.REFRESH_WINDOW_MS, ServerStatus.holdBeforeRefetch(lastFetchAt = 100_000L, now = 100_000L))
    }

    @Test
    fun aRefetchAfterTheWindowGoesAtOnce() {
        assertEquals(0L, ServerStatus.holdBeforeRefetch(lastFetchAt = 100_000L, now = 130_000L))
        assertEquals(0L, ServerStatus.holdBeforeRefetch(lastFetchAt = 100_000L, now = 500_000L))
    }
}
