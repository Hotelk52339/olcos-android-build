package io.olcos.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import io.olcos.app.AppGraph
import io.olcos.app.MainActivity
import io.olcos.app.R
import io.olcos.app.core.RoomRotationPolicy.Timing
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId

/**
 * The rooms of VK Calls, SaluteJazz and Jitsi do not expire by themselves,
 * so changing them is the owner's choice, made per protocol under Settings →
 * Room auto-update (Telemost has its own clock, TelemostRenewal). Every
 * connection of a server this phone manages, on a protocol with a schedule
 * and not skipped, gets a fresh room once its current one is older than the
 * interval and, when the protocol has a window, only inside it (any time,
 * at night, or within a chosen hour of the phone's clock; a room that misses
 * its window waits for the next one). The room is made by the protocol's own creator (VK Calls with
 * the saved sign-in, MTS Link by replaying what the app learned from the
 * owner's lesson and checking the meeting the way the core will
 * (MtsMeetingCheck), SaluteJazz with one anonymous request, Jitsi as a new
 * random room on the same Jitsi server) and put on the server through the
 * agent exactly the way the Telemost renewal does; the saved connections
 * follow. An MTS Link meeting is never deleted by the app (no delete request
 * is learned): the old one stays in the account. A room whose age
 * is unknown is stamped quietly first, so its interval counts from then;
 * one room is changed per connection per pass; and a refused sign-in is
 * named once and the connection then left alone for a day, so a dead
 * session is not hammered every sweep. The live connection waits while the
 * tunnel is busy, up to six hours past due (RoomRotationPolicy).
 */
object RoomRotation {
    private const val NOTIFICATION_ID = 3
    private const val SIGN_IN_QUIET_S = 24 * 3600L
    private const val BUSY_SAMPLE_MS = 3_000L
    private const val LOG_TAG = "olcos.rotation"
    private val running = MutableStateFlow(false)
    private val signInQuietUntil = HashMap<String, Long>()
    /** The rooms being changed right now: the hourly sweep and a server page can reach the same one together. */
    private val changing = HashSet<String>()

    /** Every owned server, with a fresh status from its agent. */
    suspend fun sweep(context: Context) {
        val rules = AppGraph.settings.roomRules.value
        if (!RoomRules.anyScheduled(rules)) return
        // Outside every window there is nothing to change, so no server is asked.
        if (!RoomRotationPolicy.anyWindowOpen(rules, AppGraph.settings.roomWindows.value, System.currentTimeMillis() / 1000, ZoneId.systemDefault())) return
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
        val server = client.server
        if (!server.isOwner) return
        val rules = AppGraph.settings.roomRules.value
        if (!RoomRules.anyScheduled(rules)) return
        val windows = AppGraph.settings.roomWindows.value
        val zone = ZoneId.systemDefault()
        val skipped = AppGraph.settings.roomRuleExceptions.value
        val records = AppGraph.store.records.value
        val now = System.currentTimeMillis() / 1000
        // The rooms changed in this pass: a connection that rides one still carries the old id here.
        val changed = HashSet<String>()
        // Whether the tunnel is busy is measured once per pass, and only when a live room is due.
        var busy: Boolean? = null
        val covered = coveredConnections(records, AppGraph.servers.servers.value, rules, only = server)
        for (record in RoomRules.scheduled(covered, rules, skipped)) {
            val link = record.olcrtc ?: continue
            if ((signInQuietUntil[record.id] ?: 0) > now) continue
            val rule = RoomRules.ruleOf(rules, link.carrier)
            val window = RoomRules.windowOf(windows, link.carrier)
            for (id in listOf(link.roomID) + link.extraRooms) {
                if (RoomServices.roomIdentity(link.carrier, id) in changed) continue
                val room = status.rooms.firstOrNull { it.provider == link.carrier && RoomServices.sameRoom(link.carrier, it.room, id) && !it.isMissing } ?: continue
                // A skipped connection may ride this room too, and a room is one thing on the server.
                if (RoomRules.skipsRoom(records, skipped, link.carrier, id)) continue
                val createdAt = AppGraph.roomAges.get(id)
                var timing = RoomRotationPolicy.timing(rule, createdAt, now, liveAndBusy = false, window = window, zone = zone)
                if (timing == Timing.ROTATE && carriesLiveSession(link.carrier, id)) {
                    val busyNow = busy ?: tunnelBusy().also { busy = it }
                    timing = RoomRotationPolicy.timing(rule, createdAt, now, liveAndBusy = busyNow, window = window, zone = zone)
                }
                when (timing) {
                    Timing.NOT_DUE, Timing.WAIT_WINDOW -> Unit
                    Timing.STAMP -> {
                        AppGraph.roomAges.set(id, now)
                        AppLog.i(LOG_TAG, "room of \"${record.displayName}\" has no recorded age; counting its interval from now")
                    }
                    Timing.POSTPONE -> AppLog.i(LOG_TAG, "rotation on ${server.host}: the tunnel is busy, the room waits")
                    Timing.ROTATE -> {
                        if (renew(context, client, record, room, id)) changed += RoomServices.roomIdentity(room.provider, room.room)
                        // One room per connection per pass; the next one waits for the next.
                        break
                    }
                }
            }
        }
    }

    /**
     * The connections of managed servers whose protocol rule is on, skipped
     * or not: what the Exceptions list shows. [only] narrows "managed" to one
     * server (a pass over that server); without it, every server this phone
     * owns counts. A connection belongs to a server by AgentClient.isThrough.
     */
    fun coveredConnections(
        records: List<ConnectionRecord>,
        servers: List<ServerRecord>,
        rules: Map<String, RoomRule>,
        only: ServerRecord? = null,
    ): List<ConnectionRecord> {
        val managers = if (only != null) listOf(only) else servers.filter { it.isOwner }
        return RoomRules.covered(records, rules, servers.mapNotNull { it.bundleRecordID }.toSet()) { record ->
            managers.any { AgentClient.isThrough(it, record.id, record.subSource) }
        }
    }

    /** Whether the session that is up, or on its way, joins this room: changing it then takes a lane away from the live tunnel. */
    private fun carriesLiveSession(carrier: String, room: String): Boolean {
        val state = TunnelManager.state.value
        if (state is TunnelManager.State.Disconnected || state is TunnelManager.State.Failed) return false
        val live = TunnelManager.record.value?.olcrtc ?: return false
        return live.lanes.any { it.carrier == carrier && RoomServices.sameRoom(carrier, it.roomID, room) }
    }

    /**
     * Whether the tunnel is carrying something now: a speed test, a session
     * being joined, or traffic of a stream, a download or a call. Every byte
     * the rooms carry crosses this app's sockets, so their counters over a
     * few seconds are the session's rate; where the system has none, the
     * tunnel is taken as quiet.
     */
    private suspend fun tunnelBusy(): Boolean {
        if (SpeedTest.running.value || TunnelManager.state.value is TunnelManager.State.Connecting) return true
        val uid = Process.myUid()
        val in0 = TrafficStats.getUidRxBytes(uid)
        val out0 = TrafficStats.getUidTxBytes(uid)
        if (in0 < 0 || out0 < 0) return false
        val start = System.nanoTime()
        delay(BUSY_SAMPLE_MS)
        val in1 = TrafficStats.getUidRxBytes(uid)
        val out1 = TrafficStats.getUidTxBytes(uid)
        val seconds = (System.nanoTime() - start) / 1e9
        if (in1 < in0 || out1 < out0 || seconds <= 0) return false
        return RoomRotationPolicy.isBusy(((in1 - in0) + (out1 - out0)) / seconds)
    }

    /** A room the protocol's own creator made. */
    private class Fresh(val room: String)

    /** Changes one room on the server; true when it was changed. [previous] is the record's own spelling of the room being replaced. */
    private suspend fun renew(context: Context, client: AgentClient, record: ConnectionRecord, room: ServerStatus.Room, previous: String): Boolean {
        val key = RoomServices.roomIdentity(room.provider, room.room)
        if (!synchronized(changing) { changing.add(key) }) return false
        try {
            val fresh = make(context, client, record, room) ?: return false
            return deliver(context, client, room, previous, fresh)
        } finally {
            synchronized(changing) { changing.remove(key) }
        }
    }

    /** The fresh room, or null when none could be made (the sign-in is named, or the failure logged). */
    private suspend fun make(context: Context, client: AgentClient, record: ConnectionRecord, room: ServerStatus.Room): Fresh? {
        try {
            return when (room.provider) {
                "vkcalls" -> {
                    val sid = AppGraph.vk.remixSid
                    if (sid == null) {
                        needsSignIn(context, record, context.getString(R.string.accounts_vk))
                        return null
                    }
                    Fresh(RoomServices.VKCall.create(sid))
                }
                "salutejazz" -> Fresh(RoomServices.SaluteJazz.create())
                // A new random room on the Jitsi server the old one is on.
                "jitsi" -> Fresh(Maintenance.freshJitsiRoom(room.room))
                // A new meeting in the owner's own MTS Link account, replayed from what the app learned (the
                // make-a-room button's silent path). The old meeting is left in the account: no delete request
                // is ever learned, and the free plan's cap on permanent meetings is the owner's to tidy.
                "mtslink" -> {
                    val template = AppGraph.mtsTemplate.template
                    if (template == null) {
                        creationFailed(context, record, room, "the app has not learned how to make a room")
                        return null
                    }
                    if (AppGraph.mts.credential == null) {
                        needsSignIn(context, record, context.getString(R.string.accounts_mtslink))
                        return null
                    }
                    when (val replay = MtsLinkReplay.make(template, room.room)) {
                        // A meeting the server cannot enter never reaches it: the old room stays.
                        is MtsLinkReplay.Result.Made -> when (val next = MtsMeetingCheck.afterReplay(MtsMeetingCheck.check(replay.link))) {
                            MtsMeetingCheck.AfterReplay.Use -> {
                                AppGraph.mtsMeetings.noteCreated(replay.link)
                                Fresh(replay.link)
                            }
                            is MtsMeetingCheck.AfterReplay.Relearn -> {
                                AppGraph.mtsTemplate.forget()
                                creationFailed(context, record, room,
                                    "the made meeting is ${MtsMeetingCheck.logWord(next.verdict)}; the template was dropped, make a room once by hand")
                                null
                            }
                            is MtsMeetingCheck.AfterReplay.Retry -> {
                                creationFailed(context, record, room, "the made meeting could not be checked")
                                null
                            }
                        }
                        MtsLinkReplay.Result.SignedOut -> {
                            AppGraph.mts.clear()
                            needsSignIn(context, record, context.getString(R.string.accounts_mtslink))
                            null
                        }
                        is MtsLinkReplay.Result.MissingToken -> {
                            creationFailed(context, record, room, "no token for header ${replay.header.lowercase()}")
                            null
                        }
                        is MtsLinkReplay.Result.Failed -> {
                            creationFailed(context, record, room, replay.reason)
                            null
                        }
                    }
                }
                else -> null
            }
        } catch (e: RoomServices.SessionRejected) {
            when (room.provider) {
                "vkcalls" -> {
                    AppGraph.vk.clear()
                    needsSignIn(context, record, context.getString(R.string.accounts_vk))
                }
                else -> AppLog.w(LOG_TAG, "rotation on ${client.server.host}: no room made (${e.message})")
            }
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(LOG_TAG, "rotation on ${client.server.host}: no room made (${e.message})")
            return null
        }
    }

    /** Puts the fresh room on the server, the way the Telemost renewal does, and brings the saved connections along. */
    private suspend fun deliver(context: Context, client: AgentClient, room: ServerStatus.Room, previous: String, fresh: Fresh): Boolean {
        try {
            val result = Maintenance.run(client, Maintenance.reconfigureScript(
                room.container, room.provider, room.transport, fresh.room,
                AppGraph.settings.vp8FPS.value, AppGraph.settings.vp8Batch.value,
            ), "OLCRTC_RECONFIGURED")
            // The connections keep the room as the phone made it (an MTS Link link keeps the ICE hint the server never got), and the room's age is keyed by that same spelling (RoomServices.delivered).
            val out = RoomServices.delivered(room.provider, fresh.room, result.uri?.roomID ?: fresh.room, previous)
            val changed = result.uri?.let { AppGraph.store.replaceRoom(room.provider, room.room, it.copy(roomID = out.room)) } ?: 0
            AppGraph.roomAges.set(out.room, System.currentTimeMillis() / 1000)
            AppGraph.roomAges.remove(out.forget)
            // Invited devices get the new room through their lists.
            runCatching { Invites.upload(client, AppGraph.servers.byId(client.server.id) ?: client.server) }
                .onFailure { AppLog.w(LOG_TAG, "lists for ${client.server.name} not updated: ${it.message}") }
            AppLog.i(LOG_TAG, "room rotated on ${client.server.host}: ${room.shortRoom} → ${fresh.room} ($changed connections follow)")
            notify(context, context.getString(R.string.room_rotation_updated),
                context.getString(R.string.room_rotation_updated_detail_fmt, client.server.name, room.serviceName))
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The fresh room was made but not applied: it stays unused, and the old one keeps running.
            AppLog.w(LOG_TAG, "rotation on ${client.server.host} failed: ${e.message}")
            return false
        }
    }

    /** A room that could not be made (the free plan's cap, a missing token, an answer that holds no meeting): logged and notified once, the old room stays, and the connection is left alone for a day. */
    private fun creationFailed(context: Context, record: ConnectionRecord, room: ServerStatus.Room, reason: String) {
        signInQuietUntil[record.id] = System.currentTimeMillis() / 1000 + SIGN_IN_QUIET_S
        AppLog.w(LOG_TAG, "rotation for \"${record.displayName}\": no new ${room.serviceName} room ($reason); the old one stays, quiet for a day")
        notify(context, context.getString(R.string.room_rotation_failed_title),
            context.getString(R.string.room_rotation_failed_fmt, room.serviceName, reason))
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
