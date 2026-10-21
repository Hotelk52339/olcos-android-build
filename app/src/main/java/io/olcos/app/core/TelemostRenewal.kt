package io.olcos.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.olcos.app.AppGraph
import io.olcos.app.MainActivity
import io.olcos.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A Telemost room stops working 24 hours after it was made. On every server
 * this device owns, a Telemost room with less than five hours left gets a
 * new one, made with the saved Yandex session and put on the server through
 * the agent, and the saved connections follow; without a session the phone
 * says so, once per room, so the person can sign in. The same lead times as
 * the iOS TelemostRenewalPolicy.
 *
 * This is Telemost's Automatic rule in Settings → Room auto-update: on
 * unless the owner switched it Off (read at every pass), and a room that a
 * connection the owner skipped rides is left as it is.
 */
object TelemostRenewal {
    private const val LIFETIME_S = 24 * 3600L
    private const val RENEWAL_LEAD_S = 5 * 3600L
    private const val NOTIFICATION_ID = 2
    private val running = MutableStateFlow(false)
    private val warned = HashSet<String>()

    /** Every owned server, with a fresh status from its agent. */
    suspend fun sweep(context: Context) {
        if (!isOn()) return
        if (!running.compareAndSet(expect = false, update = true)) return
        try {
            for (server in AppGraph.servers.servers.value.filter { it.isOwner }) {
                val client = AgentClient(server, AgentKey.loadOrCreate())
                val status = runCatching { client.status() }.getOrNull() ?: continue
                consider(context, client, ServerStatus.parse(status.outputs, status.version))
            }
        } finally {
            running.value = false
        }
    }

    /** One server whose status is already known (the server page has it). */
    suspend fun consider(context: Context, client: AgentClient, status: ServerStatus) {
        if (!client.server.isOwner) return
        if (!isOn()) return
        val now = System.currentTimeMillis() / 1000
        for (room in status.rooms.filter { it.provider == "telemost" && room(it).isNotEmpty() }) {
            val created = AppGraph.roomAges.get(room.room) ?: continue
            val left = created + LIFETIME_S - now
            if (left > RENEWAL_LEAD_S) continue
            if (skipped(room)) continue
            renew(context, client, room, left)
        }
    }

    private fun room(r: ServerStatus.Room): String = r.room

    /** Whether Telemost's rule is on: Automatic, its default, or anything but Off. */
    private fun isOn(): Boolean = AppGraph.settings.roomRule("telemost") != RoomRule.OFF

    /** Whether a connection the owner skipped rides this room. */
    private fun skipped(room: ServerStatus.Room): Boolean =
        RoomRules.skipsRoom(AppGraph.store.records.value, AppGraph.settings.roomRuleExceptions.value, "telemost", room.room)

    private suspend fun renew(context: Context, client: AgentClient, room: ServerStatus.Room, leftSeconds: Long) {
        val yandex = AppGraph.yandex
        val session = yandex.session
        if (session == null) {
            warn(context, room, leftSeconds)
            return
        }
        try {
            val fresh = RoomServices.Telemost.create(session, yandex.twin)
            val result = Maintenance.run(client, Maintenance.reconfigureScript(room.container, "telemost", room.transport, fresh), "OLCRTC_RECONFIGURED")
            val changed = result.uri?.let { AppGraph.store.replaceRoom("telemost", room.room, it) } ?: 0
            AppGraph.roomAges.set(fresh, System.currentTimeMillis() / 1000)
            AppGraph.roomAges.remove(room.room)
            // Invited devices get the new room through their lists.
            runCatching { Invites.upload(client, AppGraph.servers.byId(client.server.id) ?: client.server) }
                .onFailure { AppLog.w("olcos.invites", "lists for ${client.server.name} not updated: ${it.message}") }
            warned.remove(room.room)
            AppLog.i("olcos.telemost", "room renewed on ${client.server.host}: ${room.shortRoom} → $fresh ($changed connections follow)")
            notify(context, context.getString(R.string.telemost_renewed), context.getString(R.string.telemost_renewed_detail_fmt, client.server.name))
        } catch (e: RoomServices.SessionRejected) {
            yandex.clear()
            warn(context, room, leftSeconds)
        } catch (e: Exception) {
            AppLog.w("olcos.telemost", "renewal on ${client.server.host} failed: ${e.message}")
        }
    }

    private fun warn(context: Context, room: ServerStatus.Room, leftSeconds: Long) {
        if (!warned.add(room.room)) return
        val hours = (leftSeconds / 3600).coerceAtLeast(0)
        notify(context, context.getString(R.string.telemost_expiring_title), context.getString(R.string.telemost_expiring_fmt, hours))
    }

    private fun notify(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, Notifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }
}
