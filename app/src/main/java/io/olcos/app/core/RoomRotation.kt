package io.olcos.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.olcos.app.AppGraph
import io.olcos.app.MainActivity
import io.olcos.app.R
import io.olcos.app.model.ConnectionRecord
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The rooms the phone mints with its own sign-ins do not live forever, and
 * Telemost is the only service that says when its rooms die (its 24 hours,
 * renewed by TelemostRenewal). This is the owner-chosen schedule for the
 * rest: every connection opted in under Settings → Room auto-update gets a
 * fresh VK Calls or WB Stream room once its current one is older than the
 * interval, made with the saved sign-in and put on the server through the
 * agent exactly the way the Telemost renewal does, and the saved
 * connections follow. A refused sign-in is named once and the connection
 * then left alone for a day, so a dead session is not hammered every sweep.
 */
object RoomRotation {
    /** The intervals the setting offers, in hours: never, daily, weekly. */
    val INTERVALS = listOf(0, 24, 168)

    private const val NOTIFICATION_ID = 3
    private const val SIGN_IN_QUIET_S = 24 * 3600L
    private const val LOG_TAG = "olcos.rotation"
    private val running = MutableStateFlow(false)
    private val signInQuietUntil = HashMap<String, Long>()

    /** Every owned server, with a fresh status from its agent. */
    suspend fun sweep(context: Context) {
        if (AppGraph.settings.roomRotation.value <= 0 || AppGraph.settings.roomRotationIDs.value.isEmpty()) return
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
        val interval = AppGraph.settings.roomRotation.value
        if (interval <= 0) return
        val optedIn = AppGraph.settings.roomRotationIDs.value
        if (optedIn.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        for (record in AppGraph.store.records.value) {
            if (record.id !in optedIn) continue
            val link = record.olcrtc ?: continue
            if (link.carrier != "vkcalls" && link.carrier != "wbstream") continue
            if ((signInQuietUntil[record.id] ?: 0) > now) continue
            for (id in listOf(link.roomID) + link.extraRooms) {
                val room = status.rooms.firstOrNull { it.provider == link.carrier && it.room == id } ?: continue
                if (!due(link.carrier, interval, AppGraph.roomAges.get(id), now)) continue
                renew(context, client, record, room)
            }
        }
    }

    /** PURE: whether a room of this carrier is due for a fresh one under the interval, at `now` (unix seconds). A room whose age is unknown is left alone. */
    fun due(carrier: String, intervalHours: Int, createdAt: Long?, now: Long): Boolean {
        if (intervalHours <= 0) return false
        if (carrier != "vkcalls" && carrier != "wbstream") return false
        val created = createdAt ?: return false
        return now - created >= intervalHours * 3600L
    }

    /** PURE: the intervals the setting keeps; anything else reads as Off. */
    fun intervalHours(raw: Int): Int = if (raw in INTERVALS) raw else 0

    private suspend fun renew(context: Context, client: AgentClient, record: ConnectionRecord, room: ServerStatus.Room) {
        val service = context.getString(if (room.provider == "vkcalls") R.string.accounts_vk else R.string.accounts_wb)
        val fresh: String
        val wbToken: String
        when (room.provider) {
            "vkcalls" -> {
                val sid = AppGraph.vk.remixSid
                if (sid == null) {
                    needsSignIn(context, record, service)
                    return
                }
                fresh = try {
                    RoomServices.VKCall.create(sid)
                } catch (e: RoomServices.SessionRejected) {
                    AppGraph.vk.clear()
                    needsSignIn(context, record, service)
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(LOG_TAG, "rotation on ${client.server.host}: no room made (${e.message})")
                    return
                }
                wbToken = ""
            }
            "wbstream" -> {
                val token = AppGraph.wb.token
                if (token == null) {
                    needsSignIn(context, record, service)
                    return
                }
                fresh = try {
                    RoomServices.WBStream.create(token)
                } catch (e: RoomServices.SessionRejected) {
                    AppGraph.wb.clear()
                    needsSignIn(context, record, service)
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(LOG_TAG, "rotation on ${client.server.host}: no room made (${e.message})")
                    return
                }
                // The account's token rides along as the server's `auth.token`,
                // the way a room changed by hand on the server page carries it.
                wbToken = token
            }
            else -> return
        }
        try {
            val result = Maintenance.run(client, Maintenance.reconfigureScript(room.container, room.provider, room.transport, fresh, wbToken), "OLCRTC_RECONFIGURED")
            val changed = result.uri?.let { AppGraph.store.replaceRoom(room.provider, room.room, it) } ?: 0
            val now = System.currentTimeMillis() / 1000
            AppGraph.roomAges.set(fresh, now)
            AppGraph.roomAges.remove(room.room)
            // Invited devices get the new room through their lists.
            runCatching { Invites.upload(client, AppGraph.servers.byId(client.server.id) ?: client.server) }
                .onFailure { AppLog.w(LOG_TAG, "lists for ${client.server.name} not updated: ${it.message}") }
            AppLog.i(LOG_TAG, "room rotated on ${client.server.host}: ${room.shortRoom} → $fresh ($changed connections follow)")
            notify(context, context.getString(R.string.room_rotation_updated),
                context.getString(R.string.room_rotation_updated_detail_fmt, client.server.name, room.serviceName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The fresh room was made but not applied: it stays unused, and the old one keeps running.
            AppLog.w(LOG_TAG, "rotation on ${client.server.host} failed: ${e.message}")
        }
    }

    /** Names the dead sign-in once, then leaves the connection alone for a day. */
    private fun needsSignIn(context: Context, record: ConnectionRecord, service: String) {
        signInQuietUntil[record.id] = System.currentTimeMillis() / 1000 + SIGN_IN_QUIET_S
        AppLog.w(LOG_TAG, "rotation for \"${record.displayName}\" needs a new $service sign-in; quiet for a day")
        notify(context, context.getString(R.string.room_rotation_sign_in_title),
            context.getString(R.string.room_rotation_sign_in_fmt, service))
    }

    private fun notify(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java),
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
