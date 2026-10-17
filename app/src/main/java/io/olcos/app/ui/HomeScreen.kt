package io.olcos.app.ui

import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.core.Diagnostics
import io.olcos.app.core.OlcrtcEngine
import io.olcos.app.core.SessionInfo
import io.olcos.app.core.SpeedTest
import io.olcos.app.core.TelegramProxyLink
import io.olcos.app.core.TunnelManager
import io.olcos.app.data.TunnelMode
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.ui.hero.FirewallBeamState
import io.olcos.app.ui.hero.SignalMotionPolicy
import io.olcos.app.ui.hero.SignalWaveform
import io.olcos.app.ui.hero.ThroughputReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Home tab, as the iOS app draws it (ConnectionsView + ConnectHero): the
 * state as the largest words on the screen, one plain line under it, the
 * picture of the signal and the wall, the one action, then the connection it
 * uses; under that what proxy mode covers, the rooms of a live session and
 * the connection check, one tap away.
 */
@Composable
fun HomeScreen(
    onConnect: (ConnectionRecord) -> Unit,
    onDisconnect: () -> Unit,
    onGoToConnections: () -> Unit,
    onOpenSpeed: () -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onAddServer: () -> Unit,
) {
    val p = OlcosTheme.palette
    val store = AppGraph.store
    val settings = AppGraph.settings
    val records by store.records.collectAsStateWithLifecycle()
    val primaryId by store.primaryId.collectAsStateWithLifecycle()
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    val liveRecord by TunnelManager.record.collectAsStateWithLifecycle()
    val activeMode by TunnelManager.activeMode.collectAsStateWithLifecycle()
    val session by TunnelManager.session.collectAsStateWithLifecycle()
    val mode by settings.mode.collectAsStateWithLifecycle()
    val port by settings.socksPort.collectAsStateWithLifecycle()
    val checks by Diagnostics.results.collectAsStateWithLifecycle()
    val checking by Diagnostics.busy.collectAsStateWithLifecycle()
    val latestRelease by AppGraph.updates.latest.collectAsStateWithLifecycle()

    val primary = records.firstOrNull { it.id == primaryId } ?: records.firstOrNull()
    val idle = state is TunnelManager.State.Disconnected || state is TunnelManager.State.Failed
    // The live record while a session is up, else the one Connect will dial.
    val subject = if (idle) primary else (liveRecord ?: primary)
    val scopeMode = if (idle) mode else (activeMode ?: mode)
    val connected = state is TunnelManager.State.Connected

    val view = LocalView.current
    // A connect the person asked for here: its outcome is felt once, never replayed.
    var awaitingOutcome by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (!awaitingOutcome) return@LaunchedEffect
        when (state) {
            is TunnelManager.State.Connected -> { Haptics.success(view); awaitingOutcome = false }
            is TunnelManager.State.Failed -> { Haptics.error(view); awaitingOutcome = false }
            is TunnelManager.State.Disconnected -> awaitingOutcome = false
            else -> {}
        }
    }

    val throughput = rememberThroughput(connected)
    // Where traffic leaves the tunnel, looked up once a session carries traffic.
    var exitPlace by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connected, session) {
        exitPlace = null
        val live = session ?: return@LaunchedEffect
        if (!connected) return@LaunchedEffect
        exitPlace = withContext(Dispatchers.IO) { SpeedTest.exitPlace(live.port, live.socksUser, live.socksPass) }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        HomeTitleBar(onPaste = onPaste, onScan = onScan, onAddServer = onAddServer, onAddManual = onGoToConnections)
        if (records.isEmpty()) {
            OlcEmptyState(
                icon = Icons.Filled.Add,
                title = stringResource(R.string.hero_subject_none),
                hint = stringResource(R.string.home_no_connection),
                ctaTitle = stringResource(R.string.action_add_connection),
                ctaIcon = Icons.Filled.Add,
                onCta = onGoToConnections,
                modifier = Modifier.padding(horizontal = Metrics.s4, vertical = Metrics.s7),
            )
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.s4)
                .padding(bottom = Metrics.s6),
            verticalArrangement = Arrangement.spacedBy(Metrics.s4),
        ) {
            val update = latestRelease?.let { AppGraph.updates.available }
            if (update != null) {
                val context = LocalContext.current
                OlcCard(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkURL ?: update.url)))
                }) {
                    Text(stringResource(R.string.update_available_fmt, update.tag), style = Type.bodyStrong, color = p.green)
                    Text(stringResource(R.string.update_tap_to_download), style = Type.caption, color = p.textSecondary)
                }
            }
            Hero(
                state = state,
                subject = subject,
                scopeMode = scopeMode,
                port = session?.port ?: port,
                session = session,
                exitPlace = exitPlace,
                healthLine = subject?.let { healthLine(checks[it.id], it.id in checking) },
                throughput = throughput,
                canChoose = records.size > 1,
                onConnect = {
                    subject?.let {
                        awaitingOutcome = true
                        Haptics.impact(view)
                        onConnect(it)
                    }
                },
                onDisconnect = {
                    awaitingOutcome = false
                    Haptics.impact(view)
                    onDisconnect()
                },
                onChoose = onGoToConnections,
            )
            if (scopeMode == TunnelMode.PROXY) ProxyScopeCard(port = session?.port ?: port)
            val live = session
            if (connected && live != null && live.laneCount > 1) LanesCard(live)
            OlcGroup {
                OlcRow(
                    title = stringResource(R.string.speed_title),
                    subtitle = stringResource(R.string.diagnostics_row_subtitle),
                    icon = Icons.Filled.Speed,
                    chevron = true,
                    onClick = onOpenSpeed,
                )
            }
        }
    }
}

/** The inline title of the tab and its «+»: everything that adds a connection. */
@Composable
private fun HomeTitleBar(onPaste: () -> Unit, onScan: () -> Unit, onAddServer: () -> Unit, onAddManual: () -> Unit) {
    val p = OlcosTheme.palette
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(p.background)
            .padding(top = 6.dp)
            .heightIn(min = 52.dp),
    ) {
        Text(stringResource(R.string.tab_home), style = Type.bodyStrong, color = p.textPrimary, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
            Box(Modifier.size(44.dp).olcPressable { open = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_title), tint = p.accentTint, modifier = Modifier.size(28.dp))
            }
            OlcDropdown(open, onDismiss = { open = false }, items = listOf(
                OlcMenuItem(stringResource(R.string.action_paste_link), Icons.Filled.ContentPaste, onClick = onPaste),
                OlcMenuItem(stringResource(R.string.action_scan_qr), Icons.Filled.QrCodeScanner, onClick = onScan),
                OlcMenuItem(stringResource(R.string.action_add_server), Icons.Filled.Dns, dividerBefore = true, onClick = onAddServer),
                OlcMenuItem(stringResource(R.string.tab_connections), Icons.AutoMirrored.Filled.KeyboardArrowRight, onClick = onAddManual),
            ))
        }
    }
}

/** "Working · 84 ms", "Not working", "Not checked": the last check of this connection, in plain words. */
@Composable
private fun healthLine(check: Diagnostics.Check?, busy: Boolean): String = when {
    busy -> stringResource(R.string.health_checking)
    check == null -> stringResource(R.string.health_not_checked)
    check.ms != null -> stringResource(R.string.health_working) + " · " + stringResource(R.string.health_ms_fmt, check.ms.toInt())
    else -> stringResource(R.string.health_not_working)
}

@Composable
private fun Hero(
    state: TunnelManager.State,
    subject: ConnectionRecord?,
    scopeMode: TunnelMode,
    port: Int,
    session: SessionInfo?,
    exitPlace: String?,
    healthLine: String?,
    throughput: Throughput,
    canChoose: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onChoose: () -> Unit,
) {
    val p = OlcosTheme.palette
    Column(Modifier.fillMaxWidth()) {
        // 1. The answer: the state word, and one plain line from now under it.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Metrics.s2).padding(top = Metrics.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.s2),
        ) {
            AnimatedContent(
                targetState = stateTitle(state),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "state",
            ) { title ->
                Text(title, style = Type.answer, color = p.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Evidence(state, exitPlace, healthLine, subject != null)
        }
        // 2. The picture: the beam meets the wall, breaks through when connected.
        SignalWaveform(
            state = beamState(state),
            intensity = throughput.intensity,
            inboundShare = throughput.reading?.inboundShare ?: 0.5,
            throughput = if (state is TunnelManager.State.Connected) throughput.reading else null,
            modifier = Modifier.padding(vertical = Metrics.s2),
        )
        // 3. The one action.
        Column(Modifier.padding(top = Metrics.s3), verticalArrangement = Arrangement.spacedBy(Metrics.s2)) {
            when (state) {
                is TunnelManager.State.Connecting, is TunnelManager.State.Reconnecting ->
                    // Never locked mid-connect: a dead room must not cost the whole wait.
                    OlcButton(stringResource(R.string.action_cancel), onDisconnect, icon = Icons.Filled.Close, fillWidth = true)
                is TunnelManager.State.Connected ->
                    OlcButton(stringResource(R.string.action_disconnect), onDisconnect, icon = Icons.Filled.PowerSettingsNew, fillWidth = true)
                else -> {
                    OlcButton(
                        stringResource(if (state is TunnelManager.State.Failed) R.string.action_retry else R.string.action_connect),
                        onConnect, icon = Icons.Filled.PowerSettingsNew, role = OlcRole.PRIMARY, fillWidth = true,
                        enabled = subject != null,
                    )
                    if (subject == null) {
                        Text(stringResource(R.string.hero_pick_connection), style = Type.caption, color = p.textSecondary)
                    }
                }
            }
        }
        // 4. The connection the session uses, and the way to another one.
        SubjectPlate(subject, scopeMode, port, session, canChoose, onChoose, Modifier.padding(top = Metrics.s4))
    }
}

@Composable
private fun stateTitle(state: TunnelManager.State): String = stringResource(
    when (state) {
        is TunnelManager.State.Connected -> R.string.state_connected
        is TunnelManager.State.Connecting -> R.string.state_connecting
        is TunnelManager.State.Reconnecting -> R.string.state_reconnecting
        is TunnelManager.State.Failed -> R.string.state_failed
        is TunnelManager.State.Disconnected -> R.string.state_disconnected
    },
)

private fun beamState(state: TunnelManager.State): FirewallBeamState = when (state) {
    is TunnelManager.State.Connected -> FirewallBeamState.CONNECTED
    // Rooms gone and joining again reads as connecting: the wall closes and the beam pushes at it again.
    is TunnelManager.State.Connecting, is TunnelManager.State.Reconnecting -> FirewallBeamState.CONNECTING
    is TunnelManager.State.Failed -> FirewallBeamState.ERROR
    is TunnelManager.State.Disconnected -> FirewallBeamState.IDLE
}

/** The line under the state: dated evidence from now, never an age. */
@Composable
private fun Evidence(state: TunnelManager.State, exitPlace: String?, healthLine: String?, hasSubject: Boolean) {
    val p = OlcosTheme.palette
    val (text, tone) = when (state) {
        is TunnelManager.State.Connecting -> elapsedLine() to p.textSecondary
        is TunnelManager.State.Reconnecting -> stringResource(R.string.hero_reconnecting_note) to p.textSecondary
        // A session is Connected only after a request came back through it.
        is TunnelManager.State.Connected -> (exitPlace ?: stringResource(R.string.health_working)) to p.green
        is TunnelManager.State.Failed -> state.reason to p.red
        is TunnelManager.State.Disconnected -> (if (hasSubject) healthLine.orEmpty() else " ") to p.textSecondary
    }
    Text(text, style = Type.captionDigits, color = tone, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp))
}

/** "starting… 12 s": the one honest signal while connecting is the time it takes. */
@Composable
private fun elapsedLine(): String {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val since = System.currentTimeMillis()
        while (isActive) {
            seconds = (System.currentTimeMillis() - since) / 1000
            delay(1_000)
        }
    }
    return stringResource(R.string.hero_starting_fmt, seconds.toInt())
}

/** Service, whose server, what "Connected" covers; the row's menu; the way to another connection. */
@Composable
private fun SubjectPlate(
    subject: ConnectionRecord?,
    scopeMode: TunnelMode,
    port: Int,
    session: SessionInfo?,
    canChoose: Boolean,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    OlcCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.s1)) {
                if (subject != null) {
                    Text(Naming.title(subject), style = Type.answerSupport, color = p.textPrimary)
                    Text(Naming.subtitle(subject), style = Type.body, color = p.textSecondary)
                    val lanes = session?.laneCount ?: (subject.olcrtc?.lanes?.size ?: 1)
                    if (lanes > 1) {
                        val bonded = session?.bonded ?: (subject.olcrtc?.bond != null)
                        Text(
                            stringResource(R.string.connection_rooms_fmt, lanes) + " · " +
                                stringResource(if (bonded) R.string.connection_bonded else R.string.connection_turns),
                            style = Type.caption, color = p.textSecondary,
                        )
                    }
                    Text(
                        if (scopeMode == TunnelMode.VPN) stringResource(R.string.home_scope_vpn) else stringResource(R.string.home_scope_proxy, port),
                        style = Type.captionDigits, color = p.textSecondary, modifier = Modifier.padding(top = Metrics.s1),
                    )
                    if (scopeMode == TunnelMode.VPN) {
                        Text(stringResource(R.string.home_vpn_udp_note), style = Type.caption, color = p.textTertiary)
                    }
                } else {
                    Text(stringResource(R.string.hero_subject_none), style = Type.answerSupport, color = p.textSecondary)
                }
            }
            if (subject != null) {
                val link = subject.olcrtc?.encode() ?: subject.xray?.encode(subject.name) ?: ""
                OlcOverflowMenu(listOf(
                    OlcMenuItem(stringResource(R.string.action_check_this), Icons.Filled.NetworkCheck) {
                        AppGraph.scope.launch { Diagnostics.check(subject) }
                    },
                    OlcMenuItem(stringResource(R.string.action_copy_link), Icons.Filled.ContentCopy) {
                        clipboard.setText(AnnotatedString(link))
                        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                    },
                ))
            }
        }
        if (canChoose) {
            HorizontalDivider(Modifier.padding(top = Metrics.s3), thickness = 0.5.dp, color = p.separator)
            Row(
                Modifier.fillMaxWidth().heightIn(min = Metrics.controlHeight).olcPressable(onClick = onChoose),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.hero_choose_other), style = Type.bodyStrong, color = p.accentTint, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.textTertiary)
            }
        }
    }
}

/** Proxy mode: what "Connected" covers, and the one-tap way to hand the proxy to Telegram. */
@Composable
private fun ProxyScopeCard(port: Int) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val settings = AppGraph.settings
    OlcCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Apps, contentDescription = null, tint = p.accentTint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(Metrics.s3))
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.s1)) {
                Text(stringResource(R.string.proxy_scope_title), style = Type.bodyStrong, color = p.textPrimary)
                Text(stringResource(R.string.proxy_scope_body), style = Type.body, color = p.textSecondary)
                Text(stringResource(R.string.proxy_scope_address_fmt, "127.0.0.1:$port"), style = Type.mono, color = p.textSecondary)
            }
        }
        Spacer(Modifier.height(Metrics.s3))
        OlcButton(
            stringResource(R.string.telegram_proxy_action),
            onClick = { TelegramProxyLink.open(context, port, settings.socksUser.value, settings.socksPass.value) },
            icon = Icons.AutoMirrored.Filled.Send, role = OlcRole.PRIMARY, fillWidth = true,
        )
        Spacer(Modifier.height(Metrics.s3))
        Text(stringResource(R.string.proxy_scope_whole_device), style = Type.caption, color = p.textSecondary)
    }
}

/** While the session runs over several rooms: which of them carry traffic now. */
@Composable
private fun LanesCard(session: SessionInfo) {
    val p = OlcosTheme.palette
    var states by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(session) {
        while (isActive) {
            states = withContext(Dispatchers.IO) { OlcrtcEngine.laneStates() }
            delay(3_000)
        }
    }
    OlcGroup(header = stringResource(R.string.lanes_title)) {
        session.laneNames.forEachIndexed { index, carrier ->
            if (index > 0) OlcRowDivider()
            val up = states.getOrNull(index) == "running"
            Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.s4, vertical = Metrics.s3), verticalAlignment = Alignment.CenterVertically) {
                OlcStatusDot(if (up) OlcTone.OK else OlcTone.PROGRESS)
                Spacer(Modifier.width(Metrics.s3))
                Text(io.olcos.app.model.OlcrtcLink.carrierLabel(carrier), style = Type.body, color = p.textPrimary, modifier = Modifier.weight(1f))
                Text(stringResource(if (up) R.string.lane_up else R.string.lane_joining), style = Type.caption, color = if (up) p.green else p.textSecondary)
            }
        }
    }
}

/** The measured rate of the live session and the picture's intensity from it. */
private class Throughput(val reading: ThroughputReading?, val intensity: Double)

/**
 * The app's own traffic, sampled once a second while a session is up and the
 * screen is shown: every byte the rooms carry crosses this app's sockets, so
 * its counters are the session's rate, in and out. Smoothed, so the picture
 * breathes instead of jumping; nothing is invented when the system has no
 * counters for the app.
 */
@Composable
private fun rememberThroughput(connected: Boolean): Throughput {
    var reading by remember { mutableStateOf<ThroughputReading?>(null) }
    LaunchedEffect(connected) {
        reading = null
        if (!connected) return@LaunchedEffect
        val uid = Process.myUid()
        var lastIn = TrafficStats.getUidRxBytes(uid)
        var lastOut = TrafficStats.getUidTxBytes(uid)
        if (lastIn < 0 || lastOut < 0) return@LaunchedEffect
        var lastAt = System.nanoTime()
        var smoothIn = 0.0
        var smoothOut = 0.0
        var first = true
        while (isActive) {
            delay(1_000)
            val nowIn = TrafficStats.getUidRxBytes(uid)
            val nowOut = TrafficStats.getUidTxBytes(uid)
            val now = System.nanoTime()
            val seconds = (now - lastAt) / 1e9
            if (nowIn < lastIn || nowOut < lastOut || seconds <= 0) {
                lastIn = nowIn; lastOut = nowOut; lastAt = now
                continue
            }
            val rateIn = (nowIn - lastIn) / seconds
            val rateOut = (nowOut - lastOut) / seconds
            smoothIn = if (first) rateIn else smoothIn * 0.5 + rateIn * 0.5
            smoothOut = if (first) rateOut else smoothOut * 0.5 + rateOut * 0.5
            first = false
            lastIn = nowIn; lastOut = nowOut; lastAt = now
            reading = ThroughputReading.Exact(smoothIn, smoothOut)
        }
    }
    val current = reading
    return Throughput(current, current?.let { SignalMotionPolicy.intensity(it.totalBytesPerSecond) } ?: 0.0)
}
