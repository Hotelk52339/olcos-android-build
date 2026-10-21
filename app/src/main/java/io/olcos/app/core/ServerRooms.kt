package io.olcos.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app last heard about each managed server's rooms: the ones whose
 * container is not running. Every status fetch (the server page, a refresh on
 * return to the app, a dead lane of a live session) notes it here, so a
 * connection row, the Home lanes and the automatic checks read a stopped room
 * as stopped even while the server page is closed.
 */
object ServerRooms {
    private val byServer = HashMap<String, Set<Health.Room>>()
    private val _stopped = MutableStateFlow<Set<Health.Room>>(emptySet())
    val stopped: StateFlow<Set<Health.Room>> = _stopped

    /** Notes a server's rooms; returns the rooms that were stopped and run again. */
    @Synchronized
    fun note(serverID: String, rooms: List<ServerStatus.Room>): Set<Health.Room> {
        val before = byServer[serverID].orEmpty()
        byServer[serverID] = Health.stoppedRooms(rooms)
        _stopped.value = byServer.values.flatten().toSet()
        val running = rooms.filter { it.isUp }.map { Health.room(it.provider, it.room) }.toSet()
        return before.filterTo(HashSet()) { it in running }
    }

    @Synchronized
    fun forget(serverID: String) {
        if (byServer.remove(serverID) != null) _stopped.value = byServer.values.flatten().toSet()
    }
}
