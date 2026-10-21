package io.olcos.app.core

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import io.olcos.app.AppGraph
import io.olcos.app.data.TunnelMode
import io.olcos.app.model.ConnectionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * What the app knows about the tunnel. The services (ProxyService,
 * OlcosVpnService) run it and report here; the screens read here.
 */
object TunnelManager {
    sealed class State {
        data object Disconnected : State()
        data object Connecting : State()
        data object Connected : State()
        /** Every lane of a live session is gone; the engine is joining them again. */
        data object Reconnecting : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    private val _record = MutableStateFlow<ConnectionRecord?>(null)
    /** The connection the session is for, while one is up or on its way. */
    val record: StateFlow<ConnectionRecord?> = _record

    private val _mode = MutableStateFlow<TunnelMode?>(null)
    val activeMode: StateFlow<TunnelMode?> = _mode

    private val _session = MutableStateFlow<SessionInfo?>(null)
    /** The lanes and the joining of the live session, once it carries traffic. */
    val session: StateFlow<SessionInfo?> = _session

    val json = Json { ignoreUnknownKeys = true }

    const val EXTRA_RECORD = "record"
    const val ACTION_STOP = "io.olcos.app.STOP"

    /** The consent the system needs before a VPN can start, or null when it is already given. */
    fun vpnConsent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Starts the service for the record. False when the system refused to
     * start it from where the call came (a tile, a widget in the background):
     * the state is left alone, and the caller opens the app instead.
     */
    fun connect(context: Context, record: ConnectionRecord, mode: TunnelMode): Boolean {
        val service = when (mode) {
            TunnelMode.PROXY -> ProxyService::class.java
            TunnelMode.VPN -> OlcosVpnService::class.java
        }
        val intent = Intent(context, service).putExtra(EXTRA_RECORD, json.encodeToString(ConnectionRecord.serializer(), record))
        val previous = _state.value
        _record.value = record
        _mode.value = mode
        _session.value = null
        _state.value = State.Connecting
        AppGraph.settings.rememberSession(record.id)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                // Not a failure of the tunnel: the app, once open, may start it.
                _state.value = previous
                _mode.value = null
                _record.value = null
                return false
            }
            report(State.Failed(UserFacing.describe(context, e)))
            return false
        }
        return true
    }

    /** The live session took the record's new rooms in place (OlcrtcEngine.replaceLanes): the screens read them from here. */
    internal fun adoptRecord(record: ConnectionRecord) {
        if (_record.value?.id == record.id) _record.value = record
    }

    /**
     * Starts the running session over with the record as it is now, from the
     * app's own work (a whole-server connection whose shape changed on the
     * server). The service is in the foreground already, so a plain start
     * reaches it even with no screen open; false when it did not.
     */
    fun restart(context: Context, record: ConnectionRecord): Boolean {
        val mode = _mode.value ?: return false
        val service = when (mode) {
            TunnelMode.PROXY -> ProxyService::class.java
            TunnelMode.VPN -> OlcosVpnService::class.java
        }
        val intent = Intent(context, service).putExtra(EXTRA_RECORD, json.encodeToString(ConnectionRecord.serializer(), record))
        return try {
            context.startService(intent)
            _record.value = record
            true
        } catch (e: Exception) {
            AppLog.w("olcos.state", "restart not delivered: ${e.message}")
            false
        }
    }

    fun disconnect(context: Context) {
        AppGraph.settings.rememberSession(null)
        val mode = _mode.value ?: return
        val service = when (mode) {
            TunnelMode.PROXY -> ProxyService::class.java
            TunnelMode.VPN -> OlcosVpnService::class.java
        }
        try {
            context.startService(Intent(context, service).setAction(ACTION_STOP))
        } catch (e: Exception) {
            // The system would not deliver the stop from here: the service is gone already.
            AppLog.w("olcos.state", "stop not delivered: ${e.message}")
            noteStopped()
        }
    }

    /** A service started by the system (after a kill, or always-on VPN) says which record it runs. */
    internal fun noteStarted(record: ConnectionRecord, mode: TunnelMode, recovering: Boolean = false) {
        _record.value = record
        _mode.value = mode
        _session.value = null
        // A recovery joins the rooms again under "Reconnecting", not a fresh "Connecting".
        if (recovering) {
            report(State.Reconnecting)
        } else if (_state.value !is State.Connecting) {
            _state.value = State.Connecting
        }
    }

    internal fun report(state: State) {
        val changed = state != _state.value
        if (changed) AppLog.i("olcos.state", state.toString())
        _state.value = state
        if (state is State.Disconnected || state is State.Failed) {
            _mode.value = null
            _session.value = null
        }
        if (changed) AppGraph.appContext?.let { io.olcos.app.widget.WidgetSync.stateChanged(it) }
    }

    /** A service went away: Disconnected, unless it already said why it failed — that reason stays until the next connect. */
    internal fun noteStopped() {
        if (_state.value is State.Failed) return
        report(State.Disconnected)
    }

    internal fun reportSession(session: SessionInfo?) {
        _session.value = session
    }

    internal fun recordFrom(intent: Intent?): ConnectionRecord? {
        val text = intent?.getStringExtra(EXTRA_RECORD) ?: return null
        return runCatching { json.decodeFromString(ConnectionRecord.serializer(), text) }.getOrNull()
    }
}
