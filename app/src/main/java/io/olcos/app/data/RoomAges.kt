package io.olcos.app.data

import android.content.Context
import org.json.JSONObject

/**
 * When a room was created, by room id: what the phone made itself, and what
 * an invite's list says (`##created`). A Telemost room lives 24 hours, so
 * this is what lets the app renew one before it dies.
 */
class RoomAges(context: Context) {
    private val prefs = context.getSharedPreferences("olcos.roomages", Context.MODE_PRIVATE)

    /** Unix seconds, or null when unknown. */
    fun get(room: String): Long? = all().optLong(key(room), 0).takeIf { it > 0 }

    fun set(room: String, createdAt: Long) {
        val json = all().put(key(room), createdAt)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    fun remove(room: String) {
        val json = all()
        json.remove(key(room))
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun all(): JSONObject = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())

    private fun key(room: String): String = room.trim()

    private companion object {
        const val KEY = "ages"
    }
}
