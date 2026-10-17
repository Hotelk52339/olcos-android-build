package io.olcos.app.core

import io.olcos.app.AppGraph
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mobile.Mobile
import mobile.Runtime
import java.net.ServerSocket

/**
 * Checks a connection the way the iOS HealthCoordinator verifies one: an
 * HTTP round trip through an isolated probe of the core (`Runtime.Ping`), for
 * every lane of a whole-server connection, or `engine.ping` for Xray. A probe
 * never touches the live session. Results stay in memory for the rows.
 */
object Diagnostics {
    data class Check(val ms: Long?, val lanesOK: Int, val lanesTotal: Int, val error: String?, val at: Long) {
        val ok: Boolean get() = ms != null
    }

    private const val PING_URL = "https://cp.cloudflare.com/generate_204"
    private const val TIMEOUT_MS = 20_000L

    private val _results = MutableStateFlow<Map<String, Check>>(emptyMap())
    val results: StateFlow<Map<String, Check>> = _results

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** At most this many checks at once: each one joins a room. */
    private val slots = Semaphore(2)

    /** The probe runtime: a Runtime of its own, so a live session's runtimes stay untouched. */
    private val probe: Runtime by lazy { Mobile.new_() }

    suspend fun check(record: ConnectionRecord) {
        if (record.id in _busy.value) return
        _busy.value = _busy.value + record.id
        try {
            val result = slots.withPermit { withContext(Dispatchers.IO) { measure(record) } }
            _results.value = _results.value + (record.id to result)
            AppLog.i("olcos.check", "${record.displayName}: " + (result.ms?.let { "$it ms, ${result.lanesOK}/${result.lanesTotal} lanes" } ?: "failed: ${result.error}"))
        } finally {
            _busy.value = _busy.value - record.id
        }
    }

    suspend fun checkAll(records: List<ConnectionRecord>) = coroutineScope {
        records.map { async { check(it) } }.awaitAll()
    }

    /** An automatic pass leaves a connection checked this recently alone (iOS HealthPolicy). */
    private const val RECHECK_MS = 120_000L
    /** A Jitsi room is rechecked far less often: the bridge takes about a minute to set up again after a probe. */
    private const val JITSI_RECHECK_MS = 900_000L
    /** Direct (Xray) servers an automatic pass checks beyond the ones it always keeps. */
    private const val AUTOMATIC_DIRECT_LIMIT = 8

    /**
     * The check when the app opens (Settings → Check on opening), as the
     * iOS app's automatic sweep: [automaticCandidates], at most two at once.
     * [keep] are the connections always checked: the chosen one, the live one.
     */
    suspend fun checkOnOpen(records: List<ConnectionRecord>, keep: Set<String>) {
        val due = automaticCandidates(records, keep, _results.value, System.currentTimeMillis())
        if (due.isEmpty()) return
        AppLog.i("olcos.check", "on opening: checking ${due.size} of ${records.size} connections")
        checkAll(due)
    }

    /**
     * PURE: what an automatic pass checks. Every call-service connection, and
     * of the other direct servers only those that worked last time, at most
     * [AUTOMATIC_DIRECT_LIMIT]: a free list holds dozens, and checking each on
     * every opening would run for minutes. Nothing checked in the last two
     * minutes (a Jitsi room fifteen), and no single room that a whole-server
     * connection already carries: its check pings every one of its rooms.
     */
    fun automaticCandidates(records: List<ConnectionRecord>, keep: Set<String>,
                            results: Map<String, Check>, now: Long): List<ConnectionRecord> {
        val bundled = records.mapNotNull { it.olcrtc }.filter { it.lanes.size > 1 }
            .flatMap { link -> link.lanes.map { it.carrier to it.roomID } }.toSet()
        var directLeft = AUTOMATIC_DIRECT_LIMIT
        return records.filter { record ->
            val link = record.olcrtc
            if (link != null && link.lanes.size == 1 && (link.lanes[0].carrier to link.lanes[0].roomID) in bundled) {
                return@filter false
            }
            val last = results[record.id]
            if (last != null) {
                val gap = if (link?.lanes?.any { it.carrier == "jitsi" } == true) JITSI_RECHECK_MS else RECHECK_MS
                if (now - last.at < gap) return@filter false
            }
            if (record.details !is Details.Xray || record.id in keep) return@filter true
            if (last?.ok == true && directLeft > 0) {
                directLeft--
                true
            } else {
                false
            }
        }
    }

    private fun measure(record: ConnectionRecord): Check {
        val now = System.currentTimeMillis()
        return when (val d = record.details) {
            is Details.Xray -> try {
                val ms = XrayEngine.ping(d.link, TIMEOUT_MS.toInt(), PING_URL)
                if (ms >= 0) Check(ms, 1, 1, null, now) else Check(null, 0, 1, words("no answer"), now)
            } catch (e: Exception) {
                Check(null, 0, 1, words(e), now)
            }
            is Details.Olcrtc -> {
                val lanes = d.link.lanes
                var best: Long? = null
                var ok = 0
                var lastError: String? = null
                for (lane in lanes) {
                    try {
                        val ms = pingLane(record.id, d.link, lane)
                        ok++
                        best = minOf(best ?: ms, ms)
                    } catch (e: Exception) {
                        lastError = words(e)
                    }
                }
                Check(best, ok, lanes.size, if (best == null) lastError ?: words("no answer") else null, now)
            }
        }
    }

    /** The failure in the words the rows show; the raw text when no context is around (tests). */
    private fun words(e: Throwable): String =
        AppGraph.appContext?.let { UserFacing.describe(it, e) } ?: (e.message ?: e.toString())

    private fun words(message: String): String =
        AppGraph.appContext?.let { UserFacing.describe(it, message) } ?: message

    /**
     * PURE: the name a check announces in the room, as the iOS app's checks do.
     * Two peers that share one name in a room break each other, and the live
     * session holds this device's own.
     */
    fun probeDeviceID(recordID: String): String = "olc-ping-" + recordID.replace("-", "").take(8)

    @Synchronized
    private fun pingLane(recordID: String, link: OlcrtcLink, lane: Lane): Long {
        val settings = AppGraph.settings
        val rt = probe
        rt.setDNS(settings.dns.value)
        rt.setDirectRules(settings.directRules)
        val port = ServerSocket(0, 1, Loopback.address).use { it.localPort }
        val deviceID = probeDeviceID(recordID)
        val fps = (link.vp8FPS ?: settings.vp8FPS.value).toLong()
        val batch = (link.vp8Batch ?: settings.vp8Batch.value).toLong()
        return rt.ping(lane.carrier, lane.transport, lane.roomID, deviceID, link.key, port.toLong(), TIMEOUT_MS, PING_URL, fps, batch)
    }
}
