package io.olcos.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import io.olcos.app.AppGraph
import kotlin.concurrent.thread

/**
 * Keeps a live session true to its name, as the iOS app's keep-alive and
 * path monitor do. Every [INTERVAL_MS] a request goes through the session,
 * unless traffic came back to the phone through it meanwhile (the evidence is
 * already there); three misses in a row, or a change of the phone's own
 * network (Wi-Fi to mobile data, a network back after none), tell the service
 * once that the session is lost. The rooms' own dropping out is the lane
 * watch's (OlcrtcEngine) and SessionWatch's; this catches a session that
 * looks up and carries nothing, and one stranded on a network that is gone.
 */
class SessionKeeper(
    private val context: Context,
    private val session: SessionInfo,
    /** Bytes the session has handed back to the phone so far; null when that cannot be told (proxy mode). */
    private val received: () -> Long?,
    /** Called once, on the main thread, with the reason. */
    private val onLost: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var loop: Thread? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        loop = thread(name = "olcos-keep-alive", isDaemon = true) { probeLoop() }
        watchNetwork()
    }

    /** Safe to call more than once, from any thread. */
    fun stop() {
        stopped = true
        loop?.interrupt()
        loop = null
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun probeLoop() {
        var misses = 0
        var lastReceived = received()
        while (!stopped) {
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
            if (stopped) return
            // Switched off, rooms being joined again (SessionWatch has that), or
            // a speed test filling the rooms: nothing for this round to decide.
            if (!AppGraph.settings.keepAlive.value ||
                TunnelManager.state.value !is TunnelManager.State.Connected ||
                SpeedTest.running.value
            ) {
                misses = 0
                lastReceived = received()
                continue
            }
            val now = received()
            val before = lastReceived
            lastReceived = now
            if (now != null && before != null && now - before >= ACTIVITY_BYTES) {
                misses = 0
                continue
            }
            if (TunnelProbe.verify(session.port, session.socksUser, session.socksPass, PROBE_TIMEOUT_MS)) {
                misses = 0
                continue
            }
            if (stopped) return
            misses++
            AppLog.w(TAG, "keep-alive: no answer through the tunnel ($misses/$MISSES)")
            if (misses >= MISSES) {
                lost("no answer through the tunnel $MISSES times in a row")
                return
            }
        }
    }

    /**
     * The phone's own network (the app's sockets stay off the VPN, so this is
     * the network the rooms run over): the first answer is the one the
     * session started on; another one, or one back after none, strands the
     * rooms' connections, which the session cannot carry over.
     */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var gone = false

            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                when {
                    previous == null && !gone -> Unit
                    previous == network -> Unit
                    previous == null -> lost("the phone's network came back")
                    else -> lost("the phone moved to another network")
                }
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    gone = true
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb, main) }.onSuccess { callback = cb }
    }

    private fun lost(reason: String) {
        main.post {
            if (stopped) return@post
            stop()
            AppLog.w(TAG, "session lost: $reason")
            onLost(reason)
        }
    }

    companion object {
        private const val TAG = "olcos.keepalive"
        /** The iOS default: a check every half minute, three misses (about 90 s) before the rooms are joined again. */
        const val INTERVAL_MS = 30_000L
        const val MISSES = 3
        private const val PROBE_TIMEOUT_MS = 10_000
        /** Traffic back to the phone in one interval that proves the session without a request of its own. */
        private const val ACTIVITY_BYTES = 4_096L
    }
}
