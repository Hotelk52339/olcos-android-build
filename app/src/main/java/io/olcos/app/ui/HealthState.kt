package io.olcos.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.core.Diagnostics
import io.olcos.app.core.Health
import io.olcos.app.core.OlcrtcEngine
import io.olcos.app.core.ServerRooms
import io.olcos.app.core.TunnelManager
import io.olcos.app.model.ConnectionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * What every status line on a screen reads: the checks, the rooms the live
 * session carries and the clock the fade is measured by. One of these per
 * screen, so a server page, a connection row and the Home hero cannot say
 * different things about the same room.
 */
class HealthView(
    val checks: Map<String, Health.Check>,
    val proofs: Map<Health.Room, Health.Proof>,
    val live: Set<Health.Room>,
    val now: Long,
    /** The connection the connected session is for; null while none is connected. */
    val liveID: String?,
    /** The rooms whose container the servers last reported not running. */
    val stopped: Set<Health.Room> = emptySet(),
) {
    fun verdict(record: ConnectionRecord): Health.Verdict =
        Health.recordVerdict(record, checks[record.id], proofs, live, isLive = record.id == liveID, now = now, stopped = stopped)
}

/** The rooms of the connected session that the core reports running (polled while a bundle is up). */
@Composable
fun rememberLiveRooms(): Set<Health.Room> {
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    val record by TunnelManager.record.collectAsStateWithLifecycle()
    val connected = state is TunnelManager.State.Connected
    val link = record?.olcrtc
    var states by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(connected, link) {
        states = null
        if (!connected || link == null || link.lanes.size < 2) return@LaunchedEffect
        while (isActive) {
            states = withContext(Dispatchers.IO) { OlcrtcEngine.laneStates() }
            delay(3_000)
        }
    }
    return remember(connected, link, states) { Health.liveRooms(connected, link, states) }
}

/** The evidence for [records], and a clock that moves on so a reading fades while the screen stays open. */
@Composable
fun rememberHealth(records: List<ConnectionRecord>): HealthView {
    val checks by Diagnostics.results.collectAsStateWithLifecycle()
    val live = rememberLiveRooms()
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    val liveRecord by TunnelManager.record.collectAsStateWithLifecycle()
    val liveID = if (state is TunnelManager.State.Connected) liveRecord?.id else null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // A check that landed after the last tick must not read as from the future.
    val clock = maxOf(now, checks.values.maxOfOrNull { it.at } ?: 0L)
    val proofs = remember(records, checks) { Health.proofs(records, checks) }
    val stopped by ServerRooms.stopped.collectAsStateWithLifecycle()
    return remember(checks, proofs, live, clock, liveID, stopped) { HealthView(checks, proofs, live, clock, liveID, stopped) }
}
