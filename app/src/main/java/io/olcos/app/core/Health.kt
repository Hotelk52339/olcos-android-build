package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink

/**
 * The one answer to "does it work", for a room, a protocol, a server and a
 * connection. All of them read the same two kinds of evidence:
 *
 *  - a check (an isolated probe through the room), good for [FADE_MS] either
 *    way: a reading older than that says nothing about now;
 *  - the live tunnel: a room the connected session carries works, whatever
 *    its last check said.
 *
 * A protocol that was never checked, or whose good check has faded, is
 * UNKNOWN — not a failure, and never listed as one. PURE: no clocks, no
 * screens; the callers pass `now` and the evidence in.
 */
object Health {
    /** How long a check speaks for the room: the iOS fade, and the longest an unchallenged good check still counts. */
    const val FADE_MS = 30 * 60_000L

    /** STOPPED: the room's container is not running; neither working nor failed, and never checked on its own. */
    enum class State { WORKING, FAILED, UNKNOWN, STOPPED }

    /** A room by what makes it that room: the protocol and the room's identity (an MTS Link hint makes no other room). */
    data class Room(val carrier: String, val room: String)

    fun room(carrier: String, room: String): Room = Room(carrier, RoomServices.roomIdentity(carrier, room))

    /** What one room of a checked connection answered: [ms] is null when it did not. */
    data class LaneReading(val carrier: String, val roomID: String, val ms: Long?, val error: String?) {
        val ok: Boolean get() = ms != null
    }

    /** A connection's check: the best time over its rooms, how many answered, and each room's own answer. */
    data class Check(
        val ms: Long?,
        val lanesOK: Int,
        val lanesTotal: Int,
        val error: String?,
        val at: Long,
        val lanes: List<LaneReading> = emptyList(),
    ) {
        val ok: Boolean get() = ms != null
    }

    /** The newest word about one room: it answered, or it did not, at [at]. */
    class Proof(val ok: Boolean, val at: Long)

    /** The state plus the protocols named as not answering. */
    data class Verdict(val state: State, val failed: List<String>)

    private fun best(states: Collection<State>): State = when {
        State.WORKING in states -> State.WORKING
        State.FAILED in states -> State.FAILED
        states.isNotEmpty() && states.all { it == State.STOPPED } -> State.STOPPED
        else -> State.UNKNOWN
    }

    /** The newest check word per room, over every checked connection: a bundle's check speaks for each of its rooms. */
    fun proofs(records: List<ConnectionRecord>, checks: Map<String, Check>): Map<Room, Proof> {
        val found = HashMap<Room, Proof>()
        fun note(room: Room, proof: Proof) {
            val old = found[room]
            if (old == null || proof.at > old.at) found[room] = proof
        }
        for (record in records) {
            val link = record.olcrtc ?: continue
            val check = checks[record.id] ?: continue
            if (check.lanes.isNotEmpty()) {
                for (lane in check.lanes) note(room(lane.carrier, lane.roomID), Proof(lane.ok, check.at))
            } else if (link.lanes.size == 1) {
                note(room(link.carrier, link.roomID), Proof(check.ok, check.at))
            }
        }
        return found
    }

    /**
     * The rooms the connected session carries. A single room is live once the
     * session is connected; a bundle's room is live while the core reports it
     * running ([laneStates] in lane order), and none is claimed while those
     * are not known yet.
     */
    fun liveRooms(connected: Boolean, link: OlcrtcLink?, laneStates: List<String>?): Set<Room> {
        if (!connected || link == null) return emptySet()
        val lanes = link.lanes
        if (lanes.size == 1) return setOf(room(lanes[0].carrier, lanes[0].roomID))
        if (laneStates == null || laneStates.size != lanes.size) return emptySet()
        return lanes.filterIndexed { i, _ -> laneStates[i] == "running" }.map { room(it.carrier, it.roomID) }.toSet()
    }

    /** [stopped]: the rooms whose container the server last reported not running (whatever their checks said). */
    fun roomState(room: Room, proofs: Map<Room, Proof>, live: Set<Room>, now: Long, stopped: Set<Room> = emptySet()): State {
        if (room in live) return State.WORKING
        if (room in stopped) return State.STOPPED
        val proof = proofs[room] ?: return State.UNKNOWN
        if (now - proof.at > FADE_MS) return State.UNKNOWN
        return if (proof.ok) State.WORKING else State.FAILED
    }

    /** The rooms of a server whose container is not running (stopped, or gone). */
    fun stoppedRooms(rooms: List<ServerStatus.Room>): Set<Room> =
        rooms.filter { !it.isUp && it.provider.isNotEmpty() && it.room.isNotEmpty() }.map { room(it.provider, it.room) }.toSet()

    /** The lanes worth a ping: a stopped room cannot answer. Empty when every room is stopped (a check by hand then pings them all). */
    fun checkableLanes(lanes: List<Lane>, stopped: Set<Room>): List<Lane> =
        lanes.filter { room(it.carrier, it.roomID) !in stopped }

    /** Whether an automatic pass checks the record: not when every room it rides is stopped. */
    fun checksAutomatically(record: ConnectionRecord, stopped: Set<Room>): Boolean {
        val link = record.olcrtc ?: return true
        return checkableLanes(link.lanes, stopped).isNotEmpty()
    }

    /** How one room of the live session reads in its list. */
    enum class LaneShow { UP, JOINING, STOPPED, NOT_ANSWERING }

    /**
     * A room the core reports in its room carries traffic; else a room whose
     * container is stopped says so; else one the engine gave up waiting for
     * ([dead], lane indexes) does not answer; else it is joining.
     */
    fun laneShow(index: Int, lanes: List<Lane>, laneStates: List<String>, stopped: Set<Room>, dead: Set<Int>): LaneShow {
        if (laneStates.getOrNull(index) == "running") return LaneShow.UP
        val lane = lanes.getOrNull(index)
        if (lane != null && room(lane.carrier, lane.roomID) in stopped) return LaneShow.STOPPED
        if (index in dead) return LaneShow.NOT_ANSWERING
        return LaneShow.JOINING
    }

    // MARK: A server

    /**
     * One room of a server. A room whose container is not running is
     * stopped: neither working nor failed (its own line says so) — unless the
     * live tunnel carries it.
     */
    fun serverRoomState(r: ServerStatus.Room, proofs: Map<Room, Proof>, live: Set<Room>, now: Long): State {
        val key = room(r.provider, r.room)
        if (key in live) return State.WORKING
        if (!r.isUp) return State.STOPPED
        return roomState(key, proofs, live, now)
    }

    /** Each protocol of the server (in the order its rooms come): working when any of its rooms is. */
    fun serverProtocols(rooms: List<ServerStatus.Room>, proofs: Map<Room, Proof>, live: Set<Room>, now: Long): Map<String, State> {
        val byProtocol = LinkedHashMap<String, MutableList<State>>()
        for (r in rooms) {
            if (r.isMissing || r.provider.isEmpty()) continue
            byProtocol.getOrPut(r.provider) { mutableListOf() } += serverRoomState(r, proofs, live, now)
        }
        return byProtocol.mapValues { best(it.value) }
    }

    /**
     * Can the server be used now? Working when any protocol is; not working
     * only when every protocol that has been checked failed and none carries
     * the live tunnel; otherwise not checked. [Verdict.failed] names the
     * protocols whose last check failed — a protocol never checked or faded is
     * not among them.
     */
    fun serverHeadline(rooms: List<ServerStatus.Room>, proofs: Map<Room, Proof>, live: Set<Room>, now: Long): Verdict {
        val protocols = serverProtocols(rooms, proofs, live, now)
        val failed = protocols.filterValues { it == State.FAILED }.keys.toList()
        return Verdict(best(protocols.values), failed)
    }

    // MARK: A connection

    /** A direct (Xray) server: its own check, with the same fade. */
    fun directVerdict(check: Check?, now: Long): State {
        if (check == null || now - check.at > FADE_MS) return State.UNKNOWN
        return if (check.ok) State.WORKING else State.FAILED
    }

    /**
     * A connection row: working when any of its protocols is (or it carries
     * the live tunnel), with the protocols that did not answer named; not
     * working when none answered. A single room never names itself.
     */
    fun recordVerdict(record: ConnectionRecord, check: Check?, proofs: Map<Room, Proof>, live: Set<Room>, isLive: Boolean, now: Long,
                      stopped: Set<Room> = emptySet()): Verdict {
        val link = record.olcrtc
        if (link == null) return Verdict(if (isLive) State.WORKING else directVerdict(check, now), emptyList())
        val byProtocol = LinkedHashMap<String, MutableList<State>>()
        for (lane in link.lanes) {
            byProtocol.getOrPut(lane.carrier) { mutableListOf() } += roomState(room(lane.carrier, lane.roomID), proofs, live, now, stopped)
        }
        val states = byProtocol.mapValues { best(it.value) }
        val state = if (isLive) State.WORKING else best(states.values)
        val failed = if (state == State.WORKING) states.filterValues { it == State.FAILED }.keys.toList() else emptyList()
        return Verdict(state, failed)
    }

    /** The time a check measured, while the check still speaks for the connection. */
    fun shownMs(check: Check?, now: Long): Long? =
        check?.takeIf { now - it.at <= FADE_MS }?.ms

    /** «Yandex Telemost, VK Calls +2»: two names at most, then how many more. */
    fun failedNames(carriers: List<String>): String {
        val names = carriers.take(2).joinToString(", ") { OlcrtcLink.carrierLabel(it) }
        return if (carriers.size > 2) "$names +${carriers.size - 2}" else names
    }
}
