package io.olcos.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.olcos.app.AppGraph
import io.olcos.app.MainActivity
import io.olcos.app.R

/**
 * The once-a-day look at the saved sign-ins, always on: a session that has
 * died is named before a room is needed, so signing in again is one tap in
 * Settings → Accounts rather than a failed room. VK is checked with the
 * same web-token exchange a room rides on; MTS Link with one cheap GET under
 * the saved session. Yandex stays implicit — its session
 * is spent only when a Telemost room is made, and the Telemost renewal
 * already says when it is refused. The tokens themselves never reach the
 * log.
 */
object AccountCheck {
    private const val NOTIFICATION_ID = 4
    private const val DAY_MS = 24 * 3600_000L
    private const val LOG_TAG = "olcos.accounts"

    suspend fun sweep(context: Context) {
        val now = System.currentTimeMillis()
        if (!due(AppGraph.settings.accountCheckAt, now)) return
        AppGraph.settings.accountCheckAt = now

        val sid = AppGraph.vk.remixSid
        if (sid != null) {
            // A refused exchange is definitive; no answer (the network, a hiccup) keeps the session.
            // The check writes its own verdict line; these say what was done about it.
            when (RoomServices.VKCall.verifySession(sid)) {
                RoomServices.VKCall.Verdict.Accepted ->
                    AppLog.i(LOG_TAG, "the saved VK sign-in still works")
                is RoomServices.VKCall.Verdict.Refusal -> {
                    AppGraph.vk.clear()
                    AppLog.w(LOG_TAG, "the saved VK sign-in is dead")
                    notify(context, context.getString(R.string.account_check_title), context.getString(R.string.account_check_vk))
                }
                RoomServices.VKCall.Verdict.NoAnswer -> {}
            }
        }

        val mts = AppGraph.mts.credential
        if (mts != null) {
            when (runCatching { RoomServices.MTSLink.checkSession(mts) }.getOrDefault(RoomServices.MTSLink.SessionState.UNKNOWN)) {
                RoomServices.MTSLink.SessionState.ALIVE ->
                    AppLog.i(LOG_TAG, "the saved MTS Link sign-in still works")
                RoomServices.MTSLink.SessionState.DEAD -> {
                    AppGraph.mts.clear()
                    AppLog.w(LOG_TAG, "the saved MTS Link sign-in is dead")
                    notify(context, context.getString(R.string.account_check_title), context.getString(R.string.account_check_mtslink))
                }
                RoomServices.MTSLink.SessionState.UNKNOWN -> {}
            }
        }
    }

    /** PURE: once a day, no sooner. */
    /** A zero bookmark means "never ran" — due immediately, not after a day of zero-based arithmetic. */
    fun due(lastRunMs: Long, nowMs: Long): Boolean = lastRunMs <= 0L || nowMs - lastRunMs >= DAY_MS

    private fun notify(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java),
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
