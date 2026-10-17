package io.olcos.app.core

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.data.AppSettings
import io.olcos.app.data.TunnelMode
import io.olcos.app.model.ConnectionRecord
import tunstack.TunWriter
import tunstack.Tunnel
import tunstack.Tunstack
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * VPN mode: the whole device through the tunnel. The core runs in this
 * process with its sockets protected from the tunnel; the packets the system
 * hands us go through tun2socks (tunstack) into the core's loopback SOCKS5
 * listener and back. TCP only: UDP DNS is answered "truncated" so the
 * resolver retries over TCP, other UDP is dropped, like the iOS extension.
 * The device's routes are taken only after the session has carried a
 * request, so a dead room never leaves the phone without a network.
 *
 * While the interface is open the system holds this service bound, so
 * `stopSelf` alone never reaches `onDestroy`: every way out goes through
 * `finish`, which closes the interface first.
 */
class OlcosVpnService : VpnService() {
    private val tag = "olcos.vpn"
    private val main = Handler(Looper.getMainLooper())
    /** Guards the interface and the generation between the main thread and a start's worker. */
    private val lock = Any()
    private var fd: ParcelFileDescriptor? = null
    private var tunnel: Tunnel? = null
    private var pump: Thread? = null
    /** Bumped by every start and every stop: a start that a newer start or a stop overtook reports nothing. */
    @Volatile private var generation = 0
    /**
     * Whether this service runs a session (main thread). The system may end
     * the service long after its session, when another one runs already: a
     * stop of an inactive service leaves the engine and the state alone.
     */
    private var active = false
    /** The recovery under way after a lost session; null for a start the person asked for (main thread). */
    private var recovery: Recovery.Run? = null
    private var keeper: SessionKeeper? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TunnelManager.ACTION_STOP) {
            // The notification's Disconnect comes here too: a sticky restart must not rejoin.
            AppGraph.settings.rememberSession(null)
            finish()
            return START_NOT_STICKY
        }
        try {
            Notifications.startForeground(this, getString(R.string.notification_connecting))
        } catch (e: Exception) {
            // The system would not let the service into the foreground from here
            // (a restart in the background): it ends, and the app starts it once open.
            AppLog.w("olcos.service", "not allowed in the foreground: ${e.message}")
            finish()
            return START_NOT_STICKY
        }
        val record = TunnelManager.recordFrom(intent) ?: rememberedRecord() ?: run {
            finish()
            return START_NOT_STICKY
        }
        recovery = null
        begin(record)
        return START_STICKY
    }

    /**
     * Joins [record]'s rooms and proves the session, then takes the device's
     * routes; on the main thread. During a recovery the interface of the lost
     * session keeps the routes until the new one replaces it, so nothing
     * leaves the phone outside the tunnel meanwhile.
     */
    private fun begin(start: ConnectionRecord) {
        // A recovery joins the connection as it is now: a room renewed or a key
        // rotated while the session was down is in the store, not in the copy.
        val record = if (recovery != null) AppGraph.store.records.value.firstOrNull { it.id == start.id } ?: start else start
        TunnelManager.noteStarted(record, TunnelMode.VPN, recovering = recovery != null)
        active = true
        val myGeneration = synchronized(lock) { ++generation }
        keeper?.stop()
        keeper = null
        val settings = AppGraph.settings
        SessionWatch.reset()
        OlcrtcEngine.onLanesUp = { up ->
            if (SessionWatch.lanesUp(up)) main.post {
                if (generation == myGeneration && active) lost(record, getString(R.string.state_rooms_gone))
            }
        }
        thread(name = "olcos-vpn") {
            try {
                val port = settings.socksPort.value
                val dnsHost = AppSettings.dnsHost(settings.dns.value)
                // A login of this session's own for the loopback listener: the
                // packet path signs in with it, so another app on the phone
                // cannot use the port. The Xray listener takes none (Engines).
                val socksUser = randomLogin()
                val socksPass = randomLogin()
                val session = Engines.start(record, { generation == myGeneration }, OlcrtcEngine.StartSettings(
                    port = port, dns = settings.dns.value, deviceID = settings.deviceID,
                    vpn = true, protector = { socket -> protect(socket) },
                    directRules = settings.directRules,
                    socksUser = socksUser, socksPass = socksPass,
                    vp8FPS = settings.vp8FPS.value, vp8Batch = settings.vp8Batch.value))
                if (!TunnelProbe.verify(session.port, session.socksUser, session.socksPass)) {
                    // A newer start owns the engine by now: its session is not this one's to stop.
                    if (generation == myGeneration) Engines.stop()
                    throw IllegalStateException(getString(R.string.state_no_traffic))
                }
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1500)
                    .addAddress("198.18.0.1", 24)
                    .addRoute("0.0.0.0", 0)
                    // IPv6 enters the tunnel and is dropped here: a blackhole, never a bypass.
                    .addAddress("fd6f:6c63:7274:6300::1", 64)
                    .addRoute("::", 0)
                    .addDnsServer(dnsHost)
                    .setBlocking(true)
                // Not a metered network of its own: on Wi-Fi, apps would hold back large downloads.
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
                // This app's own sockets stay off the tunnel: the cores' uplinks
                // (xray-core has no protect hook of its own), the probe, the lists.
                runCatching { builder.addDisallowedApplication(packageName) }
                synchronized(lock) {
                    // A stop that came while the rooms were joined: no routes are taken.
                    if (generation != myGeneration) throw OlcrtcEngine.Cancelled()
                    val pfd = builder.establish() ?: throw IllegalStateException(getString(R.string.error_vpn_refused))
                    // The interface of a session this one replaces goes now: the routes are on the new one.
                    closeInterface()
                    fd = pfd
                    val output = FileOutputStream(pfd.fileDescriptor)
                    val writer = TunWriter { packet ->
                        try {
                            output.write(packet)
                        } catch (e: Exception) {
                            AppLog.w(tag, "write to the device failed: ${e.message}")
                        }
                    }
                    // The session says which login its listener took: none for an Xray one.
                    val tun = if (session.socksUser.isEmpty()) {
                        Tunstack.newTunnel("127.0.0.1", port.toLong(), writer)
                    } else {
                        Tunstack.newTunnelAuth("127.0.0.1", port.toLong(), session.socksUser, session.socksPass, writer)
                    }
                    tunnel = tun
                    pump = thread(name = "olcos-vpn-pump") { pumpPackets(pfd, tun) }
                }
                main.post {
                    if (generation != myGeneration) return@post
                    recovery = null
                    TunnelManager.reportSession(session)
                    TunnelManager.report(TunnelManager.State.Connected)
                    Notifications.update(this, getString(R.string.notification_connected, servicesLine(record)))
                    // What came back to the phone through the tunnel proves the session without a request of its own.
                    keeper = SessionKeeper(this, session, received = {
                        synchronized(lock) { runCatching { tunnel?.rxBytes() }.getOrNull() }
                    }) { reason ->
                        if (generation == myGeneration && active) lost(record, reason)
                    }.also { it.start() }
                }
            } catch (e: OlcrtcEngine.Cancelled) {
                // A stop overtook the start: the service is already on its way out.
            } catch (e: Exception) {
                AppLog.w(tag, "start failed: ${e.message}")
                main.post {
                    if (generation != myGeneration) return@post
                    failed(e)
                }
            }
        }
    }

    /** A live session stopped carrying traffic: join the rooms again after the first wait. */
    private fun lost(record: ConnectionRecord, reason: String) {
        AppLog.w(tag, "session lost ($reason): joining the rooms again")
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
            finish()
            return
        }
        val delay = run.afterFailure(Recovery.networkUp(this)) { current, tried -> nextCandidate(current, tried) }
        if (delay == null) {
            recovery = null
            TunnelManager.report(TunnelManager.State.Failed(getString(R.string.state_reconnect_gave_up)))
            finish()
            return
        }
        retryAfter(delay, run.record)
    }

    private fun retryAfter(delayMs: Long, record: ConnectionRecord) {
        val token = synchronized(lock) { ++generation }
        main.postDelayed({
            if (active && generation == token) begin(record)
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

    /** 16 base64url characters nobody else knows: a SOCKS user or password for one session. */
    private fun randomLogin(): String =
        Base64.encodeToString(ByteArray(12).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** OS → tun2socks: every IPv4 packet the system routes into the tunnel. */
    private fun pumpPackets(pfd: ParcelFileDescriptor, tun: Tunnel) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32 * 1024)
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                input.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) break
            if ((buffer[0].toInt() shr 4) and 0x0F != 4) continue
            try {
                tun.writePacket(buffer.copyOf(n))
            } catch (e: Exception) {
                break
            }
        }
    }

    /** Closes the device's interface and the packet path; under `lock`, safe to call again. */
    private fun closeInterface() {
        pump?.interrupt()
        pump = null
        runCatching { tunnel?.close() }
        tunnel = null
        runCatching { fd?.close() }
        fd = null
    }

    /**
     * Ends the session and the service, on the main thread. The interface is
     * closed first, which lets the system unbind; the state reads Disconnected
     * at once, not when the rooms have finished closing.
     */
    private fun finish() {
        synchronized(lock) {
            generation++
            closeInterface()
        }
        keeper?.stop()
        keeper = null
        recovery = null
        main.removeCallbacksAndMessages(null)
        if (active) {
            active = false
            OlcrtcEngine.onLanesUp = null
            Engines.stopInBackground()
            TunnelManager.noteStopped()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took the device, or the person turned this one off in the system settings.
        AppGraph.settings.rememberSession(null)
        finish()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }
}
