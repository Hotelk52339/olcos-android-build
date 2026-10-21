package io.olcos.app.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.olcos.app.AppGraph
import io.olcos.app.MainActivity
import io.olcos.app.R
import io.olcos.app.core.TunnelManager

/** The quick-settings tile: one tap connects the chosen connection or disconnects. */
class TunnelTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        sync()
    }

    override fun onClick() {
        super.onClick()
        // What the tile cannot do by itself (a VPN consent, no connection yet, a
        // service the system would not start from here) the app does once open.
        if (!WidgetToggle.act(this)) openApp()
        sync()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun sync() {
        val tile = qsTile ?: return
        val state = TunnelManager.state.value
        tile.label = getString(R.string.app_name)
        // The subtitle exists from Android 10; older tiles show the label alone.
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (state) {
                is TunnelManager.State.Connected -> getString(R.string.state_connected)
                is TunnelManager.State.Connecting, is TunnelManager.State.Reconnecting -> getString(R.string.state_connecting)
                is TunnelManager.State.Failed -> getString(R.string.state_failed)
                is TunnelManager.State.Disconnected -> AppGraph.store.primary?.displayName ?: getString(R.string.state_disconnected)
            }
        }
        tile.state = when (state) {
            is TunnelManager.State.Connected -> Tile.STATE_ACTIVE
            is TunnelManager.State.Connecting, is TunnelManager.State.Reconnecting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
