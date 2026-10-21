package io.olcos.app.core

import android.content.Context
import android.os.SystemClock
import io.olcos.app.AppGraph
import io.olcos.app.data.ServerStore
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps what the app knows about its servers' rooms fresh. A whole-server
 * session whose lane stays out of its room past the join limit (the room may
 * have been changed on the server, or its container stopped) looks the room
 * up again: the status of the servers the connection goes through (stopped
 * rooms noted, the records follow the rooms), the list it came from, and then
 * the lane joins its new room in place — or the session starts over when the
 * connection's shape changed. The dead lane stays listed meanwhile. The same
 * status pass runs when the app comes to the front.
 */
object SessionRefresh {
    private const val TAG = "olcos.refresh"
    /** One look-up at a time, and at most one every two minutes, however many lanes die. */
    private const val MIN_GAP_MS = 2 * 60_000L
    private const val STATUS_TIMEOUT_MS = 15_000L
    private var lastAt = 0L

    /** Fetches a subscription again; set by the app (the import lives with the screens). */
    @Volatile
    var refreshList: (suspend (String) -> Unit)? = null

    fun install(context: Context) {
        val app = context.applicationContext
        OlcrtcEngine.onLaneDead = { index, lane ->
            AppLog.i(TAG, "lane ${index + 1} (${lane.carrier}) stays out of its room: looking its room up again")
            laneDead(app)
        }
    }

    private fun laneDead(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (lastAt != 0L && now - lastAt < MIN_GAP_MS) return
            lastAt = now
        }
        AppGraph.scope.launch {
            runCatching { refreshLive(context) }.onFailure { AppLog.w(TAG, "look-up failed: ${it.message}") }
        }
    }

    /**
     * Every paired server that [only] picks: its status, the stopped rooms
     * noted, its records brought in step with its rooms and, when one changed
     * on a server this phone owns, the invite lists uploaded.
     */
    suspend fun refreshServers(context: Context, only: (ServerRecord) -> Boolean = { true }) {
        val servers = AppGraph.servers
        for (server in servers.servers.value.filter { it.isPaired && only(it) }) {
            val client = AgentClient(server, AgentKey.loadOrCreate())
            val status = withTimeoutOrNull(STATUS_TIMEOUT_MS) { runCatching { client.status() }.getOrNull() } ?: continue
            val parsed = ServerStatus.parse(status.outputs, status.version)
            ServerRooms.note(server.id, parsed.rooms)
            val moved = runCatching { ServerBond.follow(context, server, parsed.rooms, AppGraph.store, servers) }.getOrDefault(false)
            if (moved) upload(client, servers, server.id)
        }
    }

    private suspend fun upload(client: AgentClient, servers: ServerStore, serverID: String) {
        val fresh = servers.byId(serverID)?.takeIf { it.isOwner && it.invites.isNotEmpty() } ?: return
        runCatching { Invites.upload(client, fresh) }.onFailure { AppLog.w("olcos.invites", "lists for ${fresh.name} not updated: ${it.message}") }
    }

    private suspend fun refreshLive(context: Context) {
        val live = TunnelManager.record.value ?: return
        refreshServers(context) { AgentClient.isThrough(it, live.id, live.subSource) }
        live.subSource?.let { source -> refreshList?.invoke(source) }
        adopt(context, live)
    }

    /** The live session takes what changed in its record: a lane's new room in place, else a new session. */
    private fun adopt(context: Context, live: ConnectionRecord) {
        if (TunnelManager.state.value !is TunnelManager.State.Connected) return
        val current = TunnelManager.record.value?.takeIf { it.id == live.id } ?: return
        val fresh = AppGraph.store.records.value.firstOrNull { it.id == live.id } ?: return
        val old = current.olcrtc ?: return
        val new = fresh.olcrtc ?: return
        val changes = RoomFollow.laneChanges(old, new)
        when {
            changes == null -> {
                AppLog.i(TAG, "${fresh.displayName}: its rooms changed shape on the server, starting the session over")
                TunnelManager.restart(context, fresh)
            }
            changes.isEmpty() -> AppLog.i(TAG, "${fresh.displayName}: no room changed")
            OlcrtcEngine.replaceLanes(changes) -> {
                AppLog.i(TAG, "${fresh.displayName}: ${changes.size} lane(s) join their new room")
                TunnelManager.adoptRecord(fresh)
            }
            else -> TunnelManager.restart(context, fresh)
        }
    }
}
