package io.olcos.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.core.IPMask
import io.olcos.app.core.SpeedTest
import io.olcos.app.core.TunnelManager
import io.olcos.app.model.OlcrtcLink
import kotlinx.coroutines.launch

/** The live session measured room by room and all together: round trip, download, the exit address. */
@Composable
fun SpeedScreen(onBack: () -> Unit) {
    val p = OlcosTheme.palette
    val rows by SpeedTest.rows.collectAsStateWithLifecycle()
    val running by SpeedTest.running.collectAsStateWithLifecycle()
    val session by TunnelManager.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        val s = session
        if (s != null && !running && rows.isEmpty()) SpeedTest.run(s)
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        OlcTopBar(stringResource(R.string.speed_title), onBack = onBack, backLabel = stringResource(R.string.tab_home)) {
            if (running) OlcSpinner(Modifier.padding(end = Metrics.s3))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.s4).padding(top = Metrics.s2)) {
            val s = session
            if (s == null) {
                Text(stringResource(R.string.speed_needs_session), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                return@Column
            }
            OlcCard(padding = 0.dp) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) OlcRowDivider(inset = 50.dp)
                    SpeedRow(row, p)
                }
            }
            Spacer(Modifier.height(12.dp))
            val together = rows.firstOrNull { it.id == SpeedTest.ALL_TOGETHER && it.phase == SpeedTest.Phase.DONE }?.bytesPerSecond
            val best = rows.filter { it.id != SpeedTest.ALL_TOGETHER && it.phase == SpeedTest.Phase.DONE }.mapNotNull { it.bytesPerSecond }.maxOrNull()
            if (together != null && best != null && best > 0) {
                Text(stringResource(R.string.speed_gain_fmt, together / best), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                Spacer(Modifier.height(12.dp))
            }
            Text(stringResource(R.string.speed_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            Spacer(Modifier.height(20.dp))
            ActionButton(stringResource(R.string.action_check_again), p, primary = !running) {
                if (!running) scope.launch { SpeedTest.run(s) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SpeedRow(row: SpeedTest.Row, p: OlcosPalette) {
    val name = if (row.id == SpeedTest.ALL_TOGETHER) stringResource(R.string.speed_all_together) else OlcrtcLink.carrierLabel(row.name)
    val tone = when (row.phase) {
        SpeedTest.Phase.DONE -> OlcTone.OK
        SpeedTest.Phase.FAILED -> OlcTone.ERROR
        SpeedTest.Phase.MEASURING -> OlcTone.PROGRESS
        else -> OlcTone.UNKNOWN
    }
    val maskIPs by AppGraph.settings.maskIPs.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(Metrics.s4), verticalAlignment = Alignment.CenterVertically) {
        OlcStatusDot(tone)
        Spacer(Modifier.size(Metrics.s3))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.bodyStrong, color = p.textPrimary)
            val detail = buildList {
                row.pingMs?.let { add(stringResource(R.string.speed_ping_fmt, it)) }
                row.bytesPerSecond?.let { add(stringResource(R.string.speed_download_fmt, it * 8 / 1_000_000)) }
                row.exit?.let { add(IPMask.displayAll(it, maskIPs)) }
                if (row.phase == SpeedTest.Phase.FAILED && isEmpty()) add(stringResource(R.string.speed_failed))
                if (row.phase == SpeedTest.Phase.MEASURING && isEmpty()) add(stringResource(R.string.speed_measuring))
                if (row.phase == SpeedTest.Phase.WAITING) add(stringResource(R.string.speed_waiting))
            }.joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
    }
}
