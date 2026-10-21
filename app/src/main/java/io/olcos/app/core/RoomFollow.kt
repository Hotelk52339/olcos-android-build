package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink

/**
 * PURE: how the saved connections and a live session follow the server's
 * rooms. A room is matched as the server names it (RoomServices.sameRoom),
 * so an MTS Link link with its ICE hint is the room the server reports plain.
 */
object RoomFollow {
    /**
     * The server's protocol records (not its whole-server one) that ride a
     * room the server no longer has, each given the room of the same protocol
     * and transport that took its place: a room on the server that no record
     * rides yet, in the server's order. Only a changed room is followed — a
     * room added or removed on the server is the server page's own operation.
     * Returns the records that changed.
     */
    fun protocolRecords(records: List<ConnectionRecord>, rooms: List<ServerStatus.Room>): List<ConnectionRecord> {
        val present = rooms.filter { !it.isMissing && it.provider.isNotEmpty() && it.room.isNotEmpty() }
            .sortedWith(compareBy<ServerStatus.Room>({ it.provider }, { it.transport }, { it.lane ?: 1 }))
        val links = records.mapNotNull { r -> r.olcrtc?.takeIf { !it.isBundle }?.let { r to it } }
        fun rides(link: OlcrtcLink, carrier: String, room: String) =
            link.carrier == carrier && (listOf(link.roomID) + link.extraRooms).any { RoomServices.sameRoom(carrier, it, room) }
        val claimed = HashSet<ServerStatus.Room>()
        val changed = mutableListOf<ConnectionRecord>()
        for ((record, link) in links) {
            val mine = present.filter { it.provider == link.carrier && it.transport == link.transport }
            if (mine.isEmpty()) continue
            val free = mine.filter { r -> r !in claimed && links.none { (_, other) -> rides(other, r.provider, r.room) } }.toMutableList()
            val current = listOf(link.roomID) + link.extraRooms
            val next = current.map { room ->
                if (mine.any { RoomServices.sameRoom(link.carrier, room, it.room) }) return@map room
                val replacement = free.removeFirstOrNull() ?: return@map room
                claimed += replacement
                replacement.room
            }
            if (next == current) continue
            changed += record.copy(details = Details.Olcrtc(link.copy(roomID = next[0], extraRooms = next.drop(1))))
        }
        return changed
    }

    /**
     * What a live session needs to take [fresh] in place of [old]: the lanes
     * (by index) whose room changed, empty when none did; null when the shape
     * differs (another lane count, order, protocol or transport, another key
     * or bond endpoint) and only a new session can carry it.
     */
    fun laneChanges(old: OlcrtcLink, fresh: OlcrtcLink): Map<Int, Lane>? {
        if (old.key != fresh.key || old.bond != fresh.bond) return null
        val a = old.lanes
        val b = fresh.lanes
        if (a.size != b.size) return null
        val changes = LinkedHashMap<Int, Lane>()
        for (i in a.indices) {
            if (a[i].carrier != b[i].carrier || a[i].transport != b[i].transport) return null
            if (a[i].roomID != b[i].roomID) changes[i] = b[i]
        }
        return changes
    }
}

/**
 * A lane of a live session that stays out of its room: reported once it has
 * been down for [limitMs] (the lane watch's own join limit), then again every
 * [repeatMs] while it stays down, so its room can be looked up anew. Not
 * thread-safe: the lane watch calls it under its lock.
 */
class LaneDeathWatch(private val limitMs: Long, private val repeatMs: Long = REPEAT_MS) {
    private val downSince = HashMap<Int, Long>()
    private val reportedAt = HashMap<Int, Long>()

    /** Notes lane [lane] at [now]; true when it is time to report it dead. */
    fun observe(lane: Int, up: Boolean, now: Long): Boolean {
        if (up) {
            forget(lane)
            return false
        }
        val since = downSince.getOrPut(lane) { now }
        if (now - since < limitMs) return false
        val last = reportedAt[lane]
        if (last != null && now - last < repeatMs) return false
        reportedAt[lane] = now
        return true
    }

    /** The lanes down for longer than the limit. */
    fun dead(now: Long): Set<Int> = downSince.filterValues { now - it >= limitMs }.keys.toSet()

    /** A lane that came back, or was given another room: its count starts again. */
    fun forget(lane: Int) {
        downSince.remove(lane)
        reportedAt.remove(lane)
    }

    companion object {
        /** How often a lane that stays dead is reported again. */
        const val REPEAT_MS = 10 * 60_000L
    }
}
