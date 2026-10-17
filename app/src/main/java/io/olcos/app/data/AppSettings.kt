package io.olcos.app.data

import android.content.Context
import android.content.SharedPreferences
import io.olcos.app.core.RoomRotation
import io.olcos.app.model.DirectRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

enum class TunnelMode { PROXY, VPN }

/** The app's look, as on iOS: the system's, or always light or dark. */
enum class Appearance { SYSTEM, LIGHT, DARK }

/** The user's settings, mirrored into preferences on every change. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("olcos.settings", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { TunnelMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(TunnelMode.VPN))
    val mode: StateFlow<TunnelMode> = _mode

    private val _socksPort = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val socksPort: StateFlow<Int> = _socksPort

    private val _dns = MutableStateFlow(prefs.getString(KEY_DNS, DEFAULT_DNS) ?: DEFAULT_DNS)
    val dns: StateFlow<String> = _dns

    /** The in-app proxy asks apps for this login; empty = no login (the default). Proxy mode only. */
    private val _socksUser = MutableStateFlow(prefs.getString(KEY_SOCKS_USER, "") ?: "")
    val socksUser: StateFlow<String> = _socksUser
    private val _socksPass = MutableStateFlow(prefs.getString(KEY_SOCKS_PASS, "") ?: "")
    val socksPass: StateFlow<String> = _socksPass

    private val _vp8FPS = MutableStateFlow(prefs.getInt(KEY_VP8_FPS, DEFAULT_VP8_FPS).coerceIn(VP8_FPS_RANGE))
    val vp8FPS: StateFlow<Int> = _vp8FPS
    private val _vp8Batch = MutableStateFlow(prefs.getInt(KEY_VP8_BATCH, DEFAULT_VP8_BATCH).coerceIn(VP8_BATCH_RANGE))
    val vp8Batch: StateFlow<Int> = _vp8Batch

    /** Routing: everything through the tunnel, or the sites on the list straight from the phone. */
    private val _routingListDirect = MutableStateFlow(prefs.getBoolean(KEY_ROUTING_LIST, false))
    val routingListDirect: StateFlow<Boolean> = _routingListDirect
    private val _routingRussian = MutableStateFlow(prefs.getBoolean(KEY_ROUTING_RUSSIAN, true))
    val routingRussian: StateFlow<Boolean> = _routingRussian
    private val _routingOwn = MutableStateFlow(prefs.getString(KEY_ROUTING_OWN, "") ?: "")
    val routingOwn: StateFlow<String> = _routingOwn

    /**
     * Staying connected, as on iOS: a request through a live session every
     * half minute (three misses, or another network, join the rooms again),
     * a connection that stops working giving way to one that works, and the
     * chosen connection joined when the app opens. All on by default.
     */
    private val _keepAlive = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALIVE, true))
    val keepAlive: StateFlow<Boolean> = _keepAlive
    private val _autoFailover = MutableStateFlow(prefs.getBoolean(KEY_AUTO_FAILOVER, true))
    val autoFailover: StateFlow<Boolean> = _autoFailover
    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect
    /** Opening the app checks which connections work (iOS refreshOnEntry). */
    private val _checkOnOpen = MutableStateFlow(prefs.getBoolean(KEY_CHECK_ON_OPEN, true))
    val checkOnOpen: StateFlow<Boolean> = _checkOnOpen
    /** IP addresses on screen are masked, for screenshots; copies and logs stay real (iOS maskIPs). */
    private val _maskIPs = MutableStateFlow(prefs.getBoolean(KEY_MASK_IPS, false))
    val maskIPs: StateFlow<Boolean> = _maskIPs

    /** The room auto-update interval in hours: 0 never, 24 daily, 168 weekly (RoomRotation.INTERVALS). */
    private val _roomRotation = MutableStateFlow(RoomRotation.intervalHours(prefs.getInt(KEY_ROOM_ROTATION, 0)))
    val roomRotation: StateFlow<Int> = _roomRotation

    /** The connections opted into the room auto-update, by record id; the default is empty — nothing rotates unless opted in. */
    private val _roomRotationIDs = MutableStateFlow(prefs.getStringSet(KEY_ROOM_ROTATION_IDS, emptySet()) ?: emptySet())
    val roomRotationIDs: StateFlow<Set<String>> = _roomRotationIDs

    private val _appearance = MutableStateFlow(
        runCatching { Appearance.valueOf(prefs.getString(KEY_APPEARANCE, null) ?: "") }.getOrDefault(Appearance.SYSTEM))
    val appearance: StateFlow<Appearance> = _appearance

    fun setAppearance(appearance: Appearance) {
        _appearance.value = appearance
        prefs.edit().putString(KEY_APPEARANCE, appearance.name).apply()
    }

    fun setKeepAlive(on: Boolean) {
        _keepAlive.value = on
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, on).apply()
    }

    fun setAutoFailover(on: Boolean) {
        _autoFailover.value = on
        prefs.edit().putBoolean(KEY_AUTO_FAILOVER, on).apply()
    }

    fun setAutoConnect(on: Boolean) {
        _autoConnect.value = on
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, on).apply()
    }

    fun setCheckOnOpen(on: Boolean) {
        _checkOnOpen.value = on
        prefs.edit().putBoolean(KEY_CHECK_ON_OPEN, on).apply()
    }

    fun setMaskIPs(on: Boolean) {
        _maskIPs.value = on
        prefs.edit().putBoolean(KEY_MASK_IPS, on).apply()
    }

    fun setRoomRotation(hours: Int) {
        val value = RoomRotation.intervalHours(hours)
        _roomRotation.value = value
        prefs.edit().putInt(KEY_ROOM_ROTATION, value).apply()
    }

    fun setRoomRotationEnabled(id: String, on: Boolean) {
        val next = _roomRotationIDs.value.toMutableSet()
        if (on) next += id else next -= id
        _roomRotationIDs.value = next
        prefs.edit().putStringSet(KEY_ROOM_ROTATION_IDS, next).apply()
    }

    /** What the engines take: the direct list, or "" for everything through the tunnel. */
    val directRules: String
        get() = DirectRoutes.effective(_routingListDirect.value, _routingRussian.value, _routingOwn.value)

    /** This install's name in a room, made once; never the device's own. */
    val deviceID: String = prefs.getString(KEY_DEVICE, null) ?: newDeviceID().also {
        prefs.edit().putString(KEY_DEVICE, it).apply()
    }

    /** The record a tunnel service runs, so a service the system restarts knows what to join; null when the user stopped it. */
    val rememberedRecordId: String? get() = prefs.getString(KEY_SESSION, null)

    /** When the once-a-day account check last ran; a bookmark, not a setting. */
    var accountCheckAt: Long
        get() = prefs.getLong(KEY_ACCOUNT_CHECK, 0L)
        set(value) { prefs.edit().putLong(KEY_ACCOUNT_CHECK, value).apply() }

    fun rememberSession(recordId: String?) {
        prefs.edit().putString(KEY_SESSION, recordId).apply()
    }

    fun setMode(mode: TunnelMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun setSocksPort(port: Int) {
        val clamped = port.coerceIn(1024, 65535)
        _socksPort.value = clamped
        prefs.edit().putInt(KEY_PORT, clamped).apply()
    }

    /** Keeps the value only when it is `ip[:port]`; false, and the old value stays, otherwise. */
    fun setDns(dns: String): Boolean {
        val value = dns.trim().ifEmpty { DEFAULT_DNS }
        if (!isValidDns(value)) return false
        _dns.value = value
        prefs.edit().putString(KEY_DNS, value).apply()
        return true
    }

    fun setSocksLogin(user: String, pass: String) {
        _socksUser.value = user.trim()
        _socksPass.value = pass
        prefs.edit().putString(KEY_SOCKS_USER, user.trim()).putString(KEY_SOCKS_PASS, pass).apply()
    }

    fun setVP8(fps: Int, batch: Int) {
        _vp8FPS.value = fps.coerceIn(VP8_FPS_RANGE)
        _vp8Batch.value = batch.coerceIn(VP8_BATCH_RANGE)
        prefs.edit().putInt(KEY_VP8_FPS, _vp8FPS.value).putInt(KEY_VP8_BATCH, _vp8Batch.value).apply()
    }

    fun setRouting(listDirect: Boolean, russian: Boolean, own: String) {
        _routingListDirect.value = listDirect
        _routingRussian.value = russian
        _routingOwn.value = own
        prefs.edit().putBoolean(KEY_ROUTING_LIST, listDirect).putBoolean(KEY_ROUTING_RUSSIAN, russian)
            .putString(KEY_ROUTING_OWN, own).apply()
    }

    private fun newDeviceID(): String {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        return "olcos-" + bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 8808
        const val DEFAULT_DNS = "1.1.1.1:53"
        const val DEFAULT_VP8_FPS = 30
        const val DEFAULT_VP8_BATCH = 64
        val VP8_FPS_RANGE = 5..60
        val VP8_BATCH_RANGE = 1..256
        private val ipv4 = Regex("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)")
        private val ipv6 = Regex("[0-9a-fA-F:]+")

        /** PURE: `1.1.1.1`, `1.1.1.1:53` or `[2606:4700::1111]:53`: an address, with a port or without. */
        fun isValidDns(text: String): Boolean {
            val s = text.trim()
            val host: String
            val port: String?
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                if (close < 0) return false
                host = s.substring(1, close)
                val rest = s.substring(close + 1)
                port = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.substring(1)
                    else -> return false
                }
                if (!ipv6.matches(host) || host.count { it == ':' } < 2) return false
            } else {
                val colon = s.indexOf(':')
                host = if (colon >= 0) s.substring(0, colon) else s
                port = if (colon >= 0) s.substring(colon + 1) else null
                if (!ipv4.matches(host)) return false
            }
            return port == null || (port.toIntOrNull()?.let { it in 1..65535 } ?: false)
        }

        /** The address part of a DNS setting, without brackets or a port: what the VPN hands the system. */
        fun dnsHost(dns: String): String {
            val s = dns.trim()
            if (s.startsWith("[")) {
                val close = s.indexOf(']')
                return if (close > 1) s.substring(1, close) else "1.1.1.1"
            }
            return s.substringBefore(':').ifEmpty { "1.1.1.1" }
        }
        private const val KEY_MODE = "mode"
        private const val KEY_PORT = "socksPort"
        private const val KEY_DNS = "dns"
        private const val KEY_DEVICE = "deviceID"
        private const val KEY_SESSION = "session.record"
        private const val KEY_SOCKS_USER = "socks.user"
        private const val KEY_SOCKS_PASS = "socks.pass"
        private const val KEY_VP8_FPS = "vp8.fps"
        private const val KEY_VP8_BATCH = "vp8.batch"
        private const val KEY_ROUTING_LIST = "routing.list"
        private const val KEY_KEEP_ALIVE = "keepAlive"
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_AUTO_FAILOVER = "autoFailover"
        private const val KEY_AUTO_CONNECT = "autoConnect"
        private const val KEY_CHECK_ON_OPEN = "checkOnOpen"
        private const val KEY_MASK_IPS = "maskIPs"
        private const val KEY_ROOM_ROTATION = "roomRotation.hours"
        private const val KEY_ROOM_ROTATION_IDS = "roomRotation.ids"
        private const val KEY_ACCOUNT_CHECK = "accounts.lastCheck"
        private const val KEY_ROUTING_RUSSIAN = "routing.russian"
        private const val KEY_ROUTING_OWN = "routing.own"
    }
}
