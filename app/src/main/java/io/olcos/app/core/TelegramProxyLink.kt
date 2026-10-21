package io.olcos.app.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands the in-app SOCKS5 proxy to Telegram in one tap (its own "enable
 * proxy?" sheet), or to the t.me page that offers it when Telegram is not
 * installed. The proxy listens on loopback only.
 */
object TelegramProxyLink {
    private const val SERVER = "127.0.0.1"

    fun appURL(port: Int, user: String = "", pass: String = ""): Uri = build("tg", "socks", "", port, user, pass)

    fun webURL(port: Int, user: String = "", pass: String = ""): Uri = build("https", "t.me", "/socks", port, user, pass)

    fun open(context: Context, port: Int, user: String = "", pass: String = "") {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, appURL(port, user, pass)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, webURL(port, user, pass)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun build(scheme: String, host: String, path: String, port: Int, user: String, pass: String): Uri {
        val builder = Uri.Builder().scheme(scheme).authority(host).path(path)
            .appendQueryParameter("server", SERVER)
            .appendQueryParameter("port", port.toString())
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            builder.appendQueryParameter("user", user).appendQueryParameter("pass", pass)
        }
        return builder.build()
    }
}
