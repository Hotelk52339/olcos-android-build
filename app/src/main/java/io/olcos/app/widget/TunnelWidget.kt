package io.olcos.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.olcos.app.AppGraph
import io.olcos.app.MainActivity
import io.olcos.app.R
import io.olcos.app.core.TunnelManager
import io.olcos.app.data.TunnelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The home-screen widget: the state of the tunnel, the chosen connection and
 * one button, like the iOS tunnel widget. The button connects when a VPN
 * consent is not needed (proxy mode, or a VPN already allowed once); the app
 * opens otherwise.
 */
class TunnelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        val state = TunnelManager.state.value
        val record = TunnelManager.record.value ?: AppGraph.store.primary
        val (label, color) = when (state) {
            is TunnelManager.State.Connected -> context.getString(R.string.state_connected) to Color(0xFF30D158)
            is TunnelManager.State.Connecting, is TunnelManager.State.Reconnecting -> context.getString(R.string.state_connecting) to Color(0xFFFFD60A)
            is TunnelManager.State.Failed -> context.getString(R.string.state_failed) to Color(0xFFFF5F57)
            is TunnelManager.State.Disconnected -> context.getString(R.string.state_disconnected) to Color(0xFF9A9CA5)
        }
        val live = state is TunnelManager.State.Connected || state is TunnelManager.State.Connecting || state is TunnelManager.State.Reconnecting
        // A tap that needs the app (a VPN consent, nothing to connect) opens it
        // straight from the widget: an activity cannot be started from the callback.
        val button = if (WidgetToggle.needsApp(context)) {
            actionStartActivity<MainActivity>()
        } else {
            actionRunCallback<ToggleAction>()
        }
        GlanceTheme {
            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color(0xFF17191D)).cornerRadius(20.dp).padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("olcOS", style = TextStyle(color = ColorProvider(Color(0xFFF5F5F7)), fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(4.dp))
                Text(label, style = TextStyle(color = ColorProvider(color), fontWeight = FontWeight.Medium))
                record?.let {
                    Text(it.displayName, style = TextStyle(color = ColorProvider(Color(0xFF9A9CA5))), maxLines = 1)
                }
                Spacer(GlanceModifier.height(10.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        context.getString(if (live) R.string.action_disconnect else R.string.action_connect),
                        style = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)), fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.background(Color(0xFF4F5BD5)).cornerRadius(12.dp).padding(horizontal = 14.dp, vertical = 8.dp)
                            .clickable(button),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                }
            }
        }
    }

    companion object {
        /** Redraws every placed widget; called on every state change. */
        suspend fun refresh(context: Context) {
            runCatching { TunnelWidget().updateAll(context) }
        }
    }
}

class TunnelWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TunnelWidget()
}

/** The widget's button: disconnects a live session, else connects the chosen record. */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!WidgetToggle.act(context)) {
            // The system refused the service from here: the app, once open, starts it.
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_CONNECT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        TunnelWidget.refresh(context)
    }
}

object WidgetToggle {
    /** Whether a tap can only be served by the app: nothing to connect, or a VPN consent still to give. */
    fun needsApp(context: Context): Boolean {
        val state = TunnelManager.state.value
        if (state !is TunnelManager.State.Disconnected && state !is TunnelManager.State.Failed) return false
        if (AppGraph.store.primary == null) return true
        return AppGraph.settings.mode.value == TunnelMode.VPN && TunnelManager.vpnConsent(context) != null
    }

    /** Disconnects a live session or connects the chosen record. False when the app has to do it instead. */
    fun act(context: Context): Boolean {
        val state = TunnelManager.state.value
        if (state !is TunnelManager.State.Disconnected && state !is TunnelManager.State.Failed) {
            TunnelManager.disconnect(context)
            return true
        }
        if (needsApp(context)) return false
        val record = AppGraph.store.primary ?: return false
        return TunnelManager.connect(context, record, AppGraph.settings.mode.value)
    }
}

/** Keeps the widgets and the tile in step with the tunnel; wired from TunnelManager.report. */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun stateChanged(context: Context) {
        scope.launch { TunnelWidget.refresh(context) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, TunnelTile::class.java)) }
    }
}
