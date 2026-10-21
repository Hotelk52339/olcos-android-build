package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import kotlin.concurrent.thread

/** What a started session is made of, whichever engine runs it. */
data class SessionInfo(
    val port: Int,
    val laneCount: Int,
    val bonded: Boolean,
    val lanePorts: List<Int> = emptyList(),
    val laneNames: List<String> = emptyList(),
    val socksUser: String = "",
    val socksPass: String = "",
)

/** Routes a connection to its engine; one engine runs at a time. */
object Engines {
    /** Orders a stop sent off the main thread against the next start. */
    private val lifecycle = Any()
    /** Bumped by every start: a stop sent before it does nothing to the session it begins. */
    @Volatile private var starts = 0

    /**
     * [wanted] is asked once a stop already under way has finished: a start
     * that was waiting behind a stop of its own session (a Connect, then a
     * Disconnect, quickly) starts nothing, so no rooms run without a service.
     */
    fun start(record: ConnectionRecord, wanted: () -> Boolean, s: OlcrtcEngine.StartSettings): SessionInfo {
        // Waits for a stop already under way, so it cannot end this session.
        synchronized(lifecycle) {
            if (!wanted()) throw OlcrtcEngine.Cancelled()
            starts++
        }
        return startEngine(record, s)
    }

    private fun startEngine(record: ConnectionRecord, s: OlcrtcEngine.StartSettings): SessionInfo = when (val d = record.details) {
        is Details.Olcrtc -> {
            XrayEngine.stop()
            val session = OlcrtcEngine.start(d.link, s)
            SessionInfo(session.port, session.laneCount, session.bonded, session.lanePorts,
                d.link.lanes.map { it.carrier }, s.socksUser, s.socksPass)
        }
        is Details.Xray -> {
            OlcrtcEngine.stop()
            // The Xray listener takes no login; a SOCKS client that offers none beside it still connects.
            XrayEngine.start(d.link, s.port, s.directRules)
            SessionInfo(s.port, 1, false)
        }
    }

    fun stop() {
        OlcrtcEngine.stop()
        XrayEngine.stop()
    }

    /**
     * The stop a service sends when it ends: on a thread of its own (the rooms
     * take seconds to close), and nothing once a newer start has begun, so a
     * quick Connect after Disconnect keeps its session.
     */
    fun stopInBackground() {
        val seen = starts
        thread(name = "olcos-engine-stop") {
            synchronized(lifecycle) { if (starts == seen) stop() }
        }
    }
}
