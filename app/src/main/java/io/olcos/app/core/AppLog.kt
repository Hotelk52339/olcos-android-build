package io.olcos.app.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The app's recent log lines, kept in memory for Settings → Copy logs: what
 * the cores and the services say, with what could identify a server, a room
 * or a person blanked before it is kept (see `redact`).
 */
object AppLog {
    private const val LIMIT = 3000
    private val lines = ArrayDeque<String>(LIMIT)
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Applied in this order: the wider patterns go after the exact ones they would otherwise swallow.
    private val hexKey = Regex("[0-9a-fA-F]{64}")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b")
    // Four or more groups, or a `::` shorthand with a group beside it: a clock reading like 12:34:56 is not one.
    private val ipv6 = Regex("\\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\\b" +
        "|(?:[0-9a-fA-F]{1,4}:){1,6}:(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,5})?" +
        "|::[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6}")
    private val fragment = Regex("#\\S+")
    /** SaluteJazz: `code-code-code:password`. */
    private val saluteJazz = Regex("\\b[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+:[A-Za-z0-9_-]{4,}\\b")
    /** A room id after "room" (one with a digit in it) or after an arrow, as the renewal log writes them. */
    private val roomAfterWord = Regex("(?i)\\b(room[\\s:=]+)(?=\\S*\\d)(\\S+)")
    private val roomAfterArrow = Regex("(→\\s*)(\\S+)")
    /** The sign-in cookies (`Session_id=…`, `remixsid=…`, `wb_access_token=…`) and the token env var: the value is a bearer credential. */
    private val signInCookie = Regex("(?i)\\b(session_id|sessionid2|remixsid|wb_access_token|olcrtc_wb_token)=([^;\\s\"']+)")
    private val base64Key = Regex("\\b[A-Za-z0-9+/=_-]{32,}\\b")

    /** Bumped on every line, for a screen that follows the log. */
    val revision = MutableStateFlow(0)

    /**
     * What is blanked, everywhere a line is kept or copied: keys (hex and
     * base64), ids (UUIDs), server addresses (IPv4 with a port, IPv6; the
     * phone's own 127.x stays), room ids and the SaluteJazz code:password,
     * the values of the sign-in cookies, and anything after `#` in a link.
     */
    fun redact(text: String): String {
        var out = hexKey.replace(text, "<key>")
        out = uuid.replace(out, "<id>")
        out = ipv4.replace(out) { m ->
            val v = m.value
            if (v.startsWith("127.") || v.startsWith("0.0.0.0")) v else "<ip>"
        }
        out = ipv6.replace(out, "<ip>")
        out = fragment.replace(out, "#<hidden>")
        out = saluteJazz.replace(out, "<room>")
        out = roomAfterWord.replace(out) { m -> m.groupValues[1] + "<room>" }
        out = roomAfterArrow.replace(out) { m -> m.groupValues[1] + "<room>" }
        out = signInCookie.replace(out) { m -> m.groupValues[1] + "=<hidden>" }
        out = base64Key.replace(out, "<key>")
        return out
    }

    @Synchronized
    fun add(tag: String, line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        if (lines.size >= LIMIT) lines.removeFirst()
        lines.addLast(stamp.format(Date()) + " [" + tag + "] " + redact(text))
        revision.value = revision.value + 1
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        revision.value = revision.value + 1
    }

    fun i(tag: String, line: String) {
        Log.i(tag, line)
        add(tag, line)
    }

    fun w(tag: String, line: String) {
        Log.w(tag, line)
        add(tag, line)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun count(): Int = lines.size
}
