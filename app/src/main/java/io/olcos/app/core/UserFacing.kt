package io.olcos.app.core

import android.content.Context
import io.olcos.app.R
import io.olcos.app.data.SubscriptionFetcher
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The one place a failure is turned into words a person can act on: the
 * known ones get a sentence with what to do, everything else keeps the
 * core's own text. Used by the services, the import toasts, Diagnostics and
 * the server page alike, so the same failure never reads two ways.
 */
object UserFacing {
    fun describe(context: Context, e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException -> return context.getString(R.string.error_dns)
                is SubscriptionFetcher.HttpStatus -> return context.getString(R.string.error_http_status_fmt, cause.code)
                is RoomServices.Refused -> return context.getString(R.string.error_room_http_fmt, cause.status)
                is AgentHTTPS.PinMismatch -> return context.getString(R.string.error_agent_pin)
                is AgentClient.AgentError -> if (cause.status == 404) return context.getString(R.string.error_agent_404)
                is XrayEngine.InvokeError -> {
                    val text = cause.message.orEmpty()
                    if (text.startsWith("engine.start")) {
                        return context.getString(R.string.error_xray_start_fmt, text.substringAfter(": ", text))
                    }
                }
                is SocketTimeoutException, is ConnectException -> return context.getString(R.string.error_unreachable)
                is SSHClient.HostKeyChanged -> return context.getString(R.string.install_error_host_key_changed)
                is SSHClient.AuthFailed -> return context.getString(R.string.install_error_auth)
            }
            cause = cause.cause
        }
        // An SSH failure with no network cause under it: jsch's own words, marked as SSH.
        if (e is SSHClient.ConnectFailed) return context.getString(R.string.install_error_ssh_fmt, e.message ?: "")
        return describe(context, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

    /** The same mapping for a failure that only survived as its text (a core line, a stored reason). */
    fun describe(context: Context, message: String): String {
        val text = message.lowercase()
        return when {
            "no peer in the room" in text || "room expired" in text || "room is empty" in text || "no peer" in text ->
                context.getString(R.string.error_room_empty)
            "is busy" in text || "address already in use" in text || "eaddrinuse" in text || "bind:" in text ->
                context.getString(R.string.error_port_busy)
            "unknownhostexception" in text || "unable to resolve host" in text || "no such host" in text ->
                context.getString(R.string.error_dns)
            "unreadable answer" in text -> context.getString(R.string.error_unreadable_answer)
            text.startsWith("engine.start") -> context.getString(R.string.error_xray_start_fmt, message.substringAfter(": ", message))
            text == "no answer" -> context.getString(R.string.error_no_answer)
            else -> message
        }
    }
}
