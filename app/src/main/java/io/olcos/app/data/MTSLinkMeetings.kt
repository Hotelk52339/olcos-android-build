package io.olcos.app.data

import android.content.Context
import io.olcos.app.core.RoomServices

/**
 * The MTS Link meetings this phone made itself, by event id, and nothing
 * else: a meeting the owner made by hand is not here and is never touched.
 * The list is what lets a rotation delete the meeting it replaces — only
 * its own — and it is capped: the oldest id goes when one more arrives.
 */
class MTSLinkMeetings(context: Context) {
    private val prefs = context.getSharedPreferences("olcos.mtsmeetings", Context.MODE_PRIVATE)

    /** Whether the meeting [link] names is one this app made. */
    fun madeByApp(link: String): Boolean {
        val event = RoomServices.MTSLink.eventID(link) ?: return false
        return event in ids()
    }

    /** Remembers the meeting [link] this app just made. */
    fun noteCreated(link: String) {
        save(added(ids(), link))
    }

    /** Forgets the meeting [link], deleted or not: the phone does not ride it any more. */
    fun forget(link: String) {
        save(removed(ids(), link))
    }

    private fun ids(): List<String> = (prefs.getString(KEY, "") ?: "").split('\n').filter { it.isNotEmpty() }

    private fun save(ids: List<String>) {
        prefs.edit().putString(KEY, ids.joinToString("\n")).apply()
    }

    companion object {
        /** The newest first; only this many are kept. */
        const val LIMIT = 20
        private const val KEY = "created"

        /** PURE: the ids with one more created meeting remembered: its event id first, its older entries gone, the oldest dropped past [LIMIT]. */
        fun added(ids: List<String>, link: String): List<String> {
            val event = RoomServices.MTSLink.eventID(link) ?: return ids
            return (listOf(event) + ids.filter { it != event }).take(LIMIT)
        }

        /** PURE: the ids without the meeting [link]'s one. */
        fun removed(ids: List<String>, link: String): List<String> {
            val event = RoomServices.MTSLink.eventID(link) ?: return ids
            return ids.filter { it != event }
        }
    }
}
