package io.olcos.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.R
import io.olcos.app.core.AppLog

/** The app's log as it grows: the cores' lines, the state changes, the agent; copy, share or clear it. */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val revision by AppLog.revision.collectAsStateWithLifecycle()
    val lines = remember(revision) { AppLog.snapshot() }
    val listState = rememberLazyListState()

    BackHandler(onBack = onBack)
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        OlcTopBar(stringResource(R.string.logs_title), onBack = onBack, backLabel = stringResource(R.string.tab_settings))
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.s5, vertical = Metrics.s1), horizontalArrangement = Arrangement.spacedBy(Metrics.s5)) {
            LogAction(stringResource(R.string.action_copy), p) {
                clipboard.setText(AnnotatedString(AppLog.dump()))
                Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
            }
            LogAction(stringResource(R.string.action_share), p) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.dump())
                context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_title)))
            }
            LogAction(stringResource(R.string.action_clear), p) { AppLog.clear() }
        }
        Spacer(Modifier.size(8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(lines) { line ->
                Text(line, style = Type.mono, color = p.textSecondary)
            }
        }
    }
}

@Composable
private fun LogAction(text: String, p: OlcosPalette, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge, color = p.accentTint,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}
