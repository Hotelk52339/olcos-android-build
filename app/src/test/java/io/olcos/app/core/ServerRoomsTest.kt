package io.olcos.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app last heard about each server's rooms: which containers are stopped, and which came back. */
class ServerRoomsTest {
    private fun room(provider: String, id: String, up: Boolean) =
        ServerStatus.Room("server-$provider.yaml", provider, "datachannel", id, "olcrtc-$provider",
            if (up) "Up 2 hours" else "Exited (0) 5 minutes ago", false)

    @After
    fun tearDown() {
        ServerRooms.forget("srv-a")
        ServerRooms.forget("srv-b")
    }

    @Test
    fun aStatusNotesTheStoppedRoomsOfEveryServer() {
        ServerRooms.note("srv-a", listOf(room("jitsi", "j1", true), room("telemost", "t1", false)))
        ServerRooms.note("srv-b", listOf(room("vkcalls", "v1", false)))
        assertEquals(setOf(Health.room("telemost", "t1"), Health.room("vkcalls", "v1")), ServerRooms.stopped.value)
    }

    @Test
    fun aRoomThatRunsAgainIsNoLongerStoppedAndIsReportedAsBack() {
        ServerRooms.note("srv-a", listOf(room("telemost", "t1", false)))
        val back = ServerRooms.note("srv-a", listOf(room("telemost", "t1", true)))
        assertEquals(setOf(Health.room("telemost", "t1")), back)
        assertTrue(ServerRooms.stopped.value.isEmpty())
    }

    @Test
    fun aRoomThatWasRunningAllAlongIsNotReportedAsBack() {
        ServerRooms.note("srv-a", listOf(room("telemost", "t1", true)))
        assertTrue(ServerRooms.note("srv-a", listOf(room("telemost", "t1", true))).isEmpty())
    }

    @Test
    fun aForgottenServerTakesItsStoppedRoomsWithIt() {
        ServerRooms.note("srv-a", listOf(room("telemost", "t1", false)))
        ServerRooms.forget("srv-a")
        assertTrue(ServerRooms.stopped.value.isEmpty())
    }
}
