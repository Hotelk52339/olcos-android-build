package io.olcos.app.core

import android.os.SystemClock
import bond.Bond
import bond.Client as BondClient
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import mobile.LogWriter
import mobile.Mobile
import mobile.Runtime
import mobile.SocketProtector
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The olcrtc core in this process: one Go runtime per lane of a session,
 * configured and started the way the iOS app does it (TunnelEngine.swift).
 * A single room listens on the port apps use. Several rooms each get a
 * loopback port of their own and are joined on the apps' port: by the bond
 * client when the server runs a bond endpoint (every connection over all
 * rooms at once), else by the LaneBalancer (each connection to the next room
 * that is up). A lane that drops is joined again with a growing wait.
 */
object OlcrtcEngine {
    private const val TAG = "olcos.core"
    private const val STOP_TIMEOUT_MS = 5_000L
    private const val DEFAULT_VP8_FPS = 30
    private const val DEFAULT_VP8_BATCH = 64
    private const val LANE_WATCH_MS = 3_000L
    /** The core's own seichannel numbers (mobile/config.go): fps, batch, fragment size, ack timeout in ms. */
    internal val CORE_SEI_DEFAULTS = listOf(30, 64, 900, 2000)

    class StartSettings(
        val port: Int,
        val dns: String,
        val deviceID: String,
        /** VPN: no UDP relay (the packet path carries TCP only) and sockets kept out of the tunnel. */
        val vpn: Boolean,
        val protector: ((Int) -> Boolean)? = null,
        val timeoutMs: Long = 60_000,
        /** Settings → Routing: the destinations the core dials itself, in its direct-list syntax. */
        val directRules: String = "",
        /** The login the SOCKS listener asks apps for; empty = none. */
        val socksUser: String = "",
        val socksPass: String = "",
        val vp8FPS: Int = DEFAULT_VP8_FPS,
        val vp8Batch: Int = DEFAULT_VP8_BATCH,
    )

    /** What a started session is made of. */
    data class Session(val link: OlcrtcLink, val port: Int, val lanePorts: List<Int>, val bonded: Boolean) {
        val laneCount: Int get() = lanePorts.size
    }

    /** Told how many lanes of the live session are up, every few seconds, from the lane-watch thread. */
    @Volatile
    var onLanesUp: ((Int) -> Unit)? = null

    /**
     * Told, from the lane-watch thread, when a lane of a session over several
     * rooms has stayed out of its room past the join limit (then every ten
     * minutes while it stays out): its room may have changed on the server.
     */
    @Volatile
    var onLaneDead: ((Int, Lane) -> Unit)? = null

    /** The rooms the live session's lanes join now: a lane given a new room (`replaceLanes`) joins that one. */
    private var liveLanes: MutableList<Lane>? = null
    /** Lanes to end and join again at once, in their new room. */
    private val rejoin = HashSet<Int>()
    /** The lanes the watch has given up waiting for, for the screens. */
    @Volatile private var deadLanes: Set<Int> = emptySet()

    /** Thrown out of a `start` that a `stop` (or a newer start) overtook: not a failure of the tunnel. */
    class Cancelled : Exception("the start was stopped")

    /** Runtimes are reused across sessions; one more is made whenever a session needs it. */
    private val runtimes = mutableListOf<Runtime>()
    private var bondClient: BondClient? = null
    private var balancer: LaneBalancer? = null
    private var laneWatch: Thread? = null
    /** Bumped by every start and stop; a start that waits off the lock checks it to notice a stop. */
    @Volatile private var epoch = 0
    /** Set by `stop`, cleared by the next `start`: what the wait for a room polls between tries. */
    @Volatile private var stopping = false
    private var logRouted = false

    private fun runtime(index: Int): Runtime {
        if (!logRouted) {
            Mobile.setLogWriter(LogWriter { line -> AppLog.i(TAG, line.trim()) })
            logRouted = true
        }
        while (runtimes.size <= index) runtimes += Mobile.new_()
        return runtimes[index]
    }

    /**
     * Joins every lane and waits until one answers. Throws with the core's
     * words, or `Cancelled` when a stop came first. The lock is held only
     * while the runtimes are configured and wired, never during the wait for
     * a room (up to a minute), so a stop from the service is never kept
     * waiting behind it.
     */
    fun start(link: OlcrtcLink, s: StartSettings): Session {
        stop()
        val lanes = link.lanes
        val myEpoch: Int
        val ports: List<Int>
        val rts: List<Runtime>
        synchronized(this) {
            stopping = false
            epoch++
            myEpoch = epoch
            ports = if (lanes.size == 1) listOf(s.port) else freePorts(lanes.size, exclude = s.port)
            rts = lanes.indices.map { runtime(it) }
            // Socket protection is process-wide: one call covers every runtime.
            rts[0].setProtector(s.protector?.let { protect -> SocketProtector { fd -> protect(fd.toInt()) } })
            try {
                for (i in lanes.indices) {
                    configure(rts[i], link, lanes[i], ports[i], s)
                    rts[i].start()
                }
            } catch (e: Exception) {
                stopRuntimes(rts)
                throw e
            }
        }
        try {
            waitAnyReady(rts, s.timeoutMs, myEpoch)
        } catch (e: Exception) {
            synchronized(this) {
                // A newer start owns these runtimes now: leave them to it.
                if (epoch != myEpoch) throw Cancelled()
                stopRuntimes(rts)
            }
            throw e
        }
        return synchronized(this) {
            if (epoch != myEpoch || stopping) throw Cancelled()
            wire(link, s, lanes, ports, rts, myEpoch)
        }
    }

    /** The joining of several lanes and the lane watch; under the lock. */
    private fun wire(link: OlcrtcLink, s: StartSettings, lanes: List<Lane>, ports: List<Int>, rts: List<Runtime>, myEpoch: Int): Session {
        var bonded = false
        if (lanes.size > 1) {
            val endpoint = link.bond?.let { OlcrtcLink.splitEndpoint(it) }
            if (endpoint != null) {
                val client = Bond.newClient(s.port.toLong(), s.socksUser, s.socksPass, link.key, endpoint.first, endpoint.second.toLong())
                    ?: run {
                        stopRuntimes(rts)
                        throw IllegalStateException("the bond client could not be made")
                    }
                for (p in ports) client.addLane(p.toLong(), s.socksUser, s.socksPass)
                // A bonded connection reaches a lane only as a CONNECT to the bond
                // server: the client decides what goes direct before it bonds.
                client.setDirectRules(s.directRules)
                try {
                    client.start()
                } catch (e: Exception) {
                    stopRuntimes(rts)
                    throw IllegalStateException("port ${s.port} is busy: ${e.message}")
                }
                bondClient = client
                bonded = true
            } else {
                val joiner = LaneBalancer(s.port, ports) { i -> rts[i].state() == "running" }
                try {
                    joiner.start()
                } catch (e: Exception) {
                    stopRuntimes(rts)
                    throw IllegalStateException("port ${s.port} is busy: ${e.message}")
                }
                balancer = joiner
            }
        }
        liveLanes = lanes.toMutableList()
        rejoin.clear()
        deadLanes = emptySet()
        laneWatch = thread(name = "olcos-lanes", isDaemon = true) { watchLanes(rts, ports, link, s, myEpoch) }
        AppLog.i(TAG, "SOCKS5 proxy ready on port ${s.port}: ${lanes.size} lane(s), " + if (bonded) "bonded" else "taking turns")
        return Session(link, s.port, ports, bonded)
    }

    /** Stops the session, and a start still waiting for a room: that start ends with `Cancelled`. */
    @Synchronized
    fun stop() {
        stopping = true
        epoch++
        laneWatch?.interrupt()
        laneWatch = null
        runCatching { bondClient?.stop() }
        bondClient = null
        balancer?.stop()
        balancer = null
        liveLanes = null
        rejoin.clear()
        deadLanes = emptySet()
        stopRuntimes(runtimes)
    }

    /** How each lane of the live session is doing, for the screens. */
    @Synchronized
    fun laneStates(): List<String> = runtimes.map { runCatching { it.state() }.getOrDefault("idle") }

    /** The lanes of the live session that stayed out of their rooms past the join limit. */
    fun deadLanes(): Set<Int> = deadLanes

    /**
     * Gives lanes of the live session new rooms (lane index → its lane with
     * the new room): each is ended and joins the new room at once; the other
     * lanes, the joining on the apps' port and the bond keep running. False
     * when there is no such session or a lane is not of it.
     */
    @Synchronized
    fun replaceLanes(changes: Map<Int, Lane>): Boolean {
        val lanes = liveLanes ?: return false
        if (changes.keys.any { it !in lanes.indices }) return false
        for ((i, lane) in changes) {
            if (lanes[i].carrier != lane.carrier || lanes[i].transport != lane.transport) return false
        }
        for ((i, lane) in changes) {
            lanes[i] = lane
            rejoin += i
        }
        return true
    }

    private val INVITE_CLIENT = Regex("^inv-[0-9a-f]{8}$")

    /**
     * PURE: the id a runtime announces in the room. A shared link's
     * placeholder becomes this install's own id; an invite's tag goes in
     * front of it, so two installs that took one invite never share an
     * identity (the server drops one of two equal ids); a typed id is kept.
     */
    fun announcedDeviceID(clientID: String, install: String): String = when {
        clientID.isEmpty() || clientID == "default" -> install
        INVITE_CLIENT.matches(clientID) -> "$clientID-$install"
        else -> clientID
    }

    /** PURE: the wait before the next try to join a lane again: 2 s, then doubling, at most 2 min. */
    fun nextLaneDelay(previous: Long): Long = (previous * 2).coerceIn(2_000L, 120_000L)

    /**
     * PURE: how long a lane may stay joining before it is ended and started
     * again: the start's own budget, at least 45 s (the iOS app's
     * VPNConfig.laneJoinLimit).
     */
    fun laneJoinLimitMs(timeoutMs: Long): Long = maxOf(timeoutMs, 45_000L)

    private fun configure(rt: Runtime, link: OlcrtcLink, lane: Lane, port: Int, s: StartSettings) {
        rt.setDebug(false)
        rt.setDNS(s.dns)
        rt.setProvider(lane.carrier)
        rt.setTransport(lane.transport)
        rt.setRoom(lane.roomID)
        rt.setDeviceID(announcedDeviceID(link.clientID, s.deviceID))
        rt.setKey(link.key)
        if (lane.transport == "vp8channel") {
            // The link's own tuning first, else the settings (the iOS defaults); a connection with an MTS Link lane runs at least at its numbers.
            val vp8 = Vp8Tuning.effective(link.lanes.map { it.carrier }, link.vp8FPS ?: s.vp8FPS, link.vp8Batch ?: s.vp8Batch)
            rt.setVP8Options(vp8.fps.toLong(), vp8.batch.toLong())
        }
        if (lane.transport == "seichannel") {
            // A link that names its SEI numbers (one shared from the iOS app) runs at them; one that names none runs at
            // the core's own, set again because a runtime is reused and may hold the last session's.
            val (fps, batch, frag, ack) = link.seiOptions() ?: CORE_SEI_DEFAULTS
            rt.setSEIOptions(fps.toLong(), batch.toLong(), frag.toLong(), ack.toLong())
        }
        rt.setLivenessOptions(30_000, 10_000, 3)
        rt.setDirectRules(s.directRules)
        rt.setProviderToken("")
        rt.setUDP(!s.vpn)
        rt.setSocksListenHost("127.0.0.1")
        rt.setSocksPort(port.toLong())
        rt.setSocksCredentials(s.socksUser, s.socksPass)
    }

    /**
     * Returns once any runtime is ready; throws the last error when the time
     * runs out or every runtime has stopped, `Cancelled` when a stop came.
     * Runs without the lock: only the stop flag and the epoch are read.
     */
    private fun waitAnyReady(rts: List<Runtime>, timeoutMs: Long, myEpoch: Int) {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000)
        var last: Exception = IllegalStateException("no peer in the room")
        while (System.currentTimeMillis() < deadline) {
            if (stopping || epoch != myEpoch) throw Cancelled()
            var anyActive = false
            for (rt in rts) {
                try {
                    rt.waitReady(250)
                    return
                } catch (e: Exception) {
                    last = e
                }
                if (rt.isRunning) anyActive = true
            }
            if (!anyActive) break
        }
        if (stopping || epoch != myEpoch) throw Cancelled()
        throw last
    }

    private fun watchLanes(rts: List<Runtime>, ports: List<Int>, link: OlcrtcLink, s: StartSettings, myEpoch: Int) {
        val delay = LongArray(rts.size)
        val due = LongArray(rts.size)
        // When each lane was first seen still joining; 0 once in its room.
        val joiningSince = LongArray(rts.size)
        val joinLimit = laneJoinLimitMs(s.timeoutMs)
        val silence = LaneSilenceWatch()
        // A lane out of its room past the join limit is reported, so its room can be looked up anew (several rooms only:
        // a single room that drops is the whole session's, and SessionWatch has it).
        val death = LaneDeathWatch(joinLimit)
        while (true) {
            try {
                Thread.sleep(LANE_WATCH_MS)
            } catch (e: InterruptedException) {
                return
            }
            var up = 0
            val reports = mutableListOf<Pair<Int, Lane>>()
            synchronized(this) {
                if (epoch != myEpoch) return
                val lanes = liveLanes ?: return
                // A bonded lane in its room that brings nothing in while the
                // others carry has lost the room (LaneSilenceWatch).
                val silent = bondClient?.laneBytes()?.let { LaneSilenceWatch.receivedBytes(it) }
                    ?.takeIf { it.size == rts.size }
                    ?.let { silence.silentLanes(it, SystemClock.elapsedRealtime()).toSet() }
                    ?: emptySet()
                for (i in rts.indices) {
                    val rt = rts[i]
                    val now = System.currentTimeMillis()
                    // A lane given a new room ends now and joins that room below, with a fresh count.
                    val moved = rejoin.remove(i)
                    if (moved) {
                        AppLog.i(TAG, "lane ${i + 1} (${lanes[i].carrier}): its room changed on the server, joining the new one")
                        delay[i] = 0
                        due[i] = 0
                        joiningSince[i] = 0
                        death.forget(i)
                    }
                    val inRoom = rt.isRunning && rt.state() == "running"
                    val carrying = inRoom && i !in silent && !moved
                    if (death.observe(i, carrying, SystemClock.elapsedRealtime()) && rts.size > 1) reports += i to lanes[i]
                    if (carrying) {
                        delay[i] = 0
                        joiningSince[i] = 0
                        up++
                        continue
                    }
                    if (rt.isRunning) {
                        if (moved) {
                            // Ended below, and joins its new room at once.
                        } else if (inRoom) {
                            AppLog.i(TAG, "lane ${i + 1} (${lanes[i].carrier}): nothing came in for ${LaneSilenceWatch.LIMIT_MS / 1000} s, joining it again")
                        } else {
                            // Still joining. A first join can wait forever (a room the
                            // server set up again under it, as Jitsi does with an empty
                            // conference): past the limit it is ended, and started afresh
                            // below. Under the lock, so a newer start never loses a lane.
                            if (joiningSince[i] == 0L) joiningSince[i] = now
                            if (now - joiningSince[i] < joinLimit) continue
                            AppLog.i(TAG, "lane ${i + 1} (${lanes[i].carrier}): still joining after ${joinLimit / 1000} s, joining it again")
                        }
                        runCatching { rt.stop(STOP_TIMEOUT_MS) }
                    }
                    joiningSince[i] = 0
                    if (now >= due[i]) {
                        delay[i] = nextLaneDelay(delay[i])
                        due[i] = now + delay[i]
                        val joined = runCatching {
                            configure(rt, link, lanes[i], ports[i], s)
                            rt.start()
                        }.isSuccess
                        AppLog.i(TAG, "lane ${i + 1} (${lanes[i].carrier}): " + if (joined) "joining again" else "could not join again")
                    }
                }
                deadLanes = if (rts.size > 1) death.dead(SystemClock.elapsedRealtime()) else emptySet()
            }
            onLanesUp?.invoke(up)
            for ((i, lane) in reports) onLaneDead?.invoke(i, lane)
        }
    }

    private fun stopRuntimes(rts: List<Runtime>) {
        for (rt in rts) runCatching { rt.stop(STOP_TIMEOUT_MS) }
    }

    /** Distinct free loopback ports, held until all are chosen so none is handed out twice. */
    private fun freePorts(count: Int, exclude: Int): List<Int> {
        val ports = mutableListOf<Int>()
        val held = mutableListOf<ServerSocket>()
        try {
            while (ports.size < count) {
                val socket = ServerSocket(0, 1, Loopback.address)
                val port = socket.localPort
                if (port == exclude || port in ports) {
                    socket.close()
                } else {
                    held += socket
                    ports += port
                }
            }
        } finally {
            for (socket in held) runCatching { socket.close() }
        }
        return ports
    }
}
