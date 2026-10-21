package io.olcos.app.core

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.data.TunnelMode
import io.olcos.app.model.ConnectionRecord
import kotlin.concurrent.thread

/**
 * Proxy mode: a foreground service that keeps the process, and the core's
 * SOCKS5 listener on 127.0.0.1, alive while the user keeps it on. A session
 * that is lost (no answer through it, another network, every room gone) is
 * joined again and, when it keeps failing, moved to another connection
 * (Recovery), as the iOS app does.
 */
class ProxyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    /** Set once the service is on its way out, so a start that ends late reports nothing. */
    @Volatile private var stopping = false
    /** Bumped by every start: a start that a newer one (another connection) overtook reports nothing. */
    @Volatile private var generation = 0
    /** The recovery under way after a lost session; null for a start the person asked for. */
    private var recovery: Recovery.Run? = null
    private var keeper: SessionKeeper? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TunnelManager.ACTION_STOP) {
            // The notification's Disconnect comes here too: a sticky restart must not rejoin.
            AppGraph.settings.rememberSession(null)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            Notifications.startForeground(this, getString(R.string.notification_connecting))
        } catch (e: Exception) {
            // The system would not let the service into the foreground from here
            // (a restart in the background): it ends, and the app starts it once open.
            AppLog.w("olcos.service", "not allowed in the foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val record = TunnelManager.recordFrom(intent) ?: rememberedRecord() ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        recovery = null
        begin(record)
        return START_STICKY
    }

    /** Joins [record]'s rooms and proves the session; on the main thread. */
    private fun begin(start: ConnectionRecord) {
        // A recovery joins the connection as it is now: a room renewed or a key
        // rotated while the session was down is in the store, not in the copy.
        val record = if (recovery != null) AppGraph.store.records.value.firstOrNull { it.id == start.id } ?: start else start
        TunnelManager.noteStarted(record, TunnelMode.PROXY, recovering = recovery != null)
        val settings = AppGraph.settings
        val myGeneration = ++generation
        keeper?.stop()
        keeper = null
        worker?.interrupt()
        SessionWatch.reset()
        OlcrtcEngine.onLanesUp = { up ->
            if (generation == myGeneration && SessionWatch.lanesUp(up)) {
                main.post { if (generation == myGeneration && !stopping) lost(record, getString(R.string.state_rooms_gone)) }
            }
        }
        worker = thread(name = "olcos-proxy") {
            try {
                val session = Engines.start(record, { !stopping && generation == myGeneration }, OlcrtcEngine.StartSettings(
                    port = settings.socksPort.value, dns = settings.dns.value,
                    deviceID = settings.deviceID, vpn = false,
                    directRules = settings.directRules,
                    socksUser = settings.socksUser.value, socksPass = settings.socksPass.value,
                    vp8FPS = settings.vp8FPS.value, vp8Batch = settings.vp8Batch.value))
                // The room answering is not enough: only a request that comes back counts.
                if (!TunnelProbe.verify(session.port, session.socksUser, session.socksPass)) {
                    // A newer start owns the engine by now: its session is not this one's to stop.
                    if (generation == myGeneration) Engines.stop()
                    throw IllegalStateException(getString(R.string.state_no_traffic))
                }
                main.post {
                    if (stopping || generation != myGeneration) return@post
                    recovery = null
                    TunnelManager.reportSession(session)
                    TunnelManager.report(TunnelManager.State.Connected)
                    Notifications.update(this, getString(R.string.notification_connected, servicesLine(record)))
                    keeper = SessionKeeper(this, session, received = { null }) { reason ->
                        if (generation == myGeneration && !stopping) lost(record, reason)
                    }.also { it.start() }
                }
            } catch (e: OlcrtcEngine.Cancelled) {
                // A stop overtook the start: the service is already on its way out.
            } catch (e: Exception) {
                main.post {
                    if (stopping || generation != myGeneration) return@post
                    failed(e)
                }
            }
        }
    }

    /** A live session stopped carrying traffic: join the rooms again after the first wait. */
    private fun lost(record: ConnectionRecord, reason: String) {
        AppLog.w("olcos.proxy", "session lost ($reason): joining the rooms again")
        keeper?.stop()
        keeper = null
        val run = Recovery.Run(record).also { recovery = it }
        TunnelManager.report(TunnelManager.State.Reconnecting)
        Notifications.update(this, getString(R.string.state_reconnecting))
        retryAfter(run.firstDelay(), run.record)
    }

    /**
     * A start that did not come up. One the person asked for is shown at
     * once; one of a recovery is tried again, on another connection once this
     * one is spent, until every one has had its turn.
     */
    private fun failed(e: Exception) {
        val run = recovery
        if (run == null) {
            TunnelManager.report(TunnelManager.State.Failed(UserFacing.describe(this, e)))
            stopSelf()
            return
        }
        val delay = run.afterFailure(Recovery.networkUp(this)) { current, tried -> nextCandidate(current, tried) }
        if (delay == null) {
            recovery = null
            TunnelManager.report(TunnelManager.State.Failed(getString(R.string.state_reconnect_gave_up)))
            stopSelf()
            return
        }
        retryAfter(delay, run.record)
    }

    private fun retryAfter(delayMs: Long, record: ConnectionRecord) {
        val token = ++generation
        main.postDelayed({
            if (!stopping && generation == token) begin(record)
        }, delayMs)
    }

    private fun nextCandidate(current: ConnectionRecord, tried: Set<String>): ConnectionRecord? {
        if (!AppGraph.settings.autoFailover.value) return null
        return Recovery.candidates(current, AppGraph.store.records.value, AppGraph.servers.servers.value, Diagnostics.results.value)
            .firstOrNull { it.id !in tried }
    }

    /** The record the user last connected, for a start the system makes on its own. */
    private fun rememberedRecord(): ConnectionRecord? {
        val id = AppGraph.settings.rememberedRecordId ?: return null
        return AppGraph.store.records.value.firstOrNull { it.id == id }
    }

    private fun servicesLine(record: ConnectionRecord): String =
        record.olcrtc?.servicesLine ?: getString(R.string.engine_xray)

    override fun onDestroy() {
        stopping = true
        worker = null
        keeper?.stop()
        keeper = null
        main.removeCallbacksAndMessages(null)
        OlcrtcEngine.onLanesUp = null
        // The engine's stop waits for the rooms to close (seconds): never on the main thread.
        Engines.stopInBackground()
        TunnelManager.noteStopped()
        super.onDestroy()
    }
}
