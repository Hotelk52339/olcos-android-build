package io.olcos.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import io.olcos.app.MainActivity
import io.olcos.app.R

/** The one ongoing notification a tunnel service shows. */
object Notifications {
    const val CHANNEL = "olcos.tunnel"
    const val ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /** `service` is the class the Disconnect action stops; null leaves the notification without one. */
    fun build(context: Context, text: String, service: Class<out Service>?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (service != null) {
            // The same stop the app's button sends: the service forgets the session and ends.
            val stop = PendingIntent.getService(
                context, 1, Intent(context, service).setAction(TunnelManager.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_glyph),
                context.getString(R.string.action_disconnect), stop).build())
        }
        return builder.build()
    }

    /** Puts a tunnel service in the foreground; the type argument exists from API 34. */
    fun startForeground(service: Service, text: String) {
        val notification = build(service, text, service.javaClass)
        if (Build.VERSION.SDK_INT >= 34) {
            service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(ID, notification)
        }
    }

    fun update(service: Service, text: String) {
        service.getSystemService(NotificationManager::class.java).notify(ID, build(service, text, service.javaClass))
    }
}
