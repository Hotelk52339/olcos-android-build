package io.olcos.app.core

import io.olcos.app.R
import io.olcos.app.core.RoomServices.MTSLink.Meeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether the server can enter an MTS Link meeting, asked the way the core
 * asks before it joins: the event's public record (`GET /api/event/<id>` on
 * the gateway, no cookies). The core takes the first of `eventSessions` and
 * fails with "the event has no session" when there is none; it is meant for
 * endless meetings («постоянные встречи»), which have a session from the
 * moment they are made and are entered with no organizer inside. A quick
 * meeting has a session only while its organizer is in it, so it is refused
 * here even when it has one. Nothing reaches a server, a template or a room
 * field unless this says [Verdict.Usable].
 */
object MtsMeetingCheck {
    private const val TAG = "olcos.mtscheck"
    private const val GATEWAY = "https://gw.mts-link.ru"
    private const val ORIGIN = "https://my.mts-link.ru"
    const val TIMEOUT_MS = 10_000

    sealed class Verdict {
        /** An endless meeting with a session the core will join. */
        data class Usable(val sessionID: Long) : Verdict()

        /** `isEndless` is false or absent: a quick or scheduled meeting, alive only while its organizer is in it. */
        data object NotPermanent : Verdict()

        /** Endless, but no session to join. */
        data object NoSession : Verdict()

        /** The service does not know the event (404 / 406 / 410: the core's ErrRoomGone). */
        data object Gone : Verdict()

        /** No answer that says anything about the meeting; [why] is for the log. */
        data class Unknown(val why: String) : Verdict()

        val usable: Boolean get() = this is Usable
    }

    /** PURE: the record's address for a meeting link, or null when [link] names no meeting. */
    fun recordURL(link: String): String? = RoomServices.MTSLink.eventID(link)?.let { "$GATEWAY/api/event/$it" }

    /** PURE: the headers the core sends with the call; no cookie, no token. */
    fun requestHeaders(): List<Pair<String, String>> = listOf(
        "Origin" to ORIGIN,
        "Referer" to "$ORIGIN/",
        "User-Agent" to RoomServices.BROWSER_UA,
    )

    /** PURE: what an answer to the record call says; [status] below 0 is a call that never got an answer. */
    fun verdict(status: Int, body: String): Verdict {
        if (status == 404 || status == 406 || status == 410) return Verdict.Gone
        if (status != 200) return Verdict.Unknown(if (status < 0) "no answer" else "HTTP $status")
        val record = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return Verdict.Unknown("the answer is not a JSON object")
        val endless = (record["isEndless"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        if (!endless) return Verdict.NotPermanent
        val first = (record["eventSessions"] as? JsonArray)?.firstOrNull() as? JsonObject
        val id = (first?.get("id") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return if (id != null && id > 0) Verdict.Usable(id) else Verdict.NoSession
    }

    /** PURE: whether a room must pass the check before it goes to a server. */
    fun mustCheck(carrier: String, room: String): Boolean = carrier == "mtslink" && room.isNotBlank()

    /** PURE: the one line that says why a meeting is refused; null for a usable one. */
    fun refusalText(verdict: Verdict): Int? = when (verdict) {
        is Verdict.Usable -> null
        Verdict.NotPermanent -> R.string.mts_check_quick
        Verdict.NoSession -> R.string.mts_check_no_session
        Verdict.Gone -> R.string.mts_check_gone
        is Verdict.Unknown -> R.string.mts_check_unknown
    }

    /** One word per verdict for a log line (no ids). */
    fun logWord(verdict: Verdict): String = when (verdict) {
        is Verdict.Usable -> "usable"
        Verdict.NotPermanent -> "not a permanent meeting"
        Verdict.NoSession -> "no session"
        Verdict.Gone -> "gone"
        is Verdict.Unknown -> "unknown (${verdict.why})"
    }

    // MARK: the lesson's Finish

    /** What the lesson's Finish offers once its meetings were checked. */
    sealed class Lesson {
        /** None, one usable meeting, or several usable ones to pick from. */
        data class Ready(val result: MtsLearn.Result) : Lesson()

        /** Nothing usable: no template is saved and nothing is used. */
        data class Refused(val verdict: Verdict) : Lesson()
    }

    /** PURE: the meetings a Finish result asks about. */
    fun candidates(result: MtsLearn.Result): List<Meeting> = when (result) {
        MtsLearn.Result.None -> emptyList()
        is MtsLearn.Result.One -> listOf(result.meeting)
        is MtsLearn.Result.Several -> result.meetings
    }

    /**
     * PURE: [result] with only the usable meetings left, [verdicts] keyed by
     * event id (a missing one is unknown). A single meeting that is not
     * usable is refused, its template with it; of several, the usable ones
     * stay (one left is offered alone and teaches nothing), and with none
     * usable the most telling reason is named.
     */
    fun lesson(result: MtsLearn.Result, verdicts: Map<String, Verdict>): Lesson {
        fun of(m: Meeting) = verdicts[m.event] ?: Verdict.Unknown("not checked")
        return when (result) {
            MtsLearn.Result.None -> Lesson.Ready(result)
            is MtsLearn.Result.One -> of(result.meeting).let { if (it.usable) Lesson.Ready(result) else Lesson.Refused(it) }
            is MtsLearn.Result.Several -> {
                val usable = result.meetings.filter { of(it).usable }
                when (usable.size) {
                    0 -> Lesson.Refused(mostTelling(result.meetings.map(::of)))
                    1 -> Lesson.Ready(MtsLearn.Result.One(usable[0], madeByApp = false, template = null, why = "the only usable one of several"))
                    else -> Lesson.Ready(MtsLearn.Result.Several(usable))
                }
            }
        }
    }

    private fun mostTelling(verdicts: List<Verdict>): Verdict =
        verdicts.firstOrNull { it == Verdict.NotPermanent }
            ?: verdicts.firstOrNull { it == Verdict.NoSession }
            ?: verdicts.firstOrNull { it == Verdict.Gone }
            ?: verdicts.firstOrNull { it is Verdict.Unknown }
            ?: Verdict.Unknown("nothing checked")

    // MARK: the one-button make

    sealed class AfterReplay {
        /** The meeting is usable: it goes where the room was asked for. */
        data object Use : AfterReplay()

        /** The template makes meetings the server cannot enter: it is dropped and the lesson opens again. */
        data class Relearn(val verdict: Verdict) : AfterReplay()

        /** The meeting could not be checked: nothing is used, the template stays for the next try. */
        data class Retry(val verdict: Verdict) : AfterReplay()
    }

    /** PURE: what a replayed meeting's verdict leads to. */
    fun afterReplay(verdict: Verdict): AfterReplay = when (verdict) {
        is Verdict.Usable -> AfterReplay.Use
        is Verdict.Unknown -> AfterReplay.Retry(verdict)
        else -> AfterReplay.Relearn(verdict)
    }

    // MARK: the call

    /** The record call for [link], and its verdict; one log line, with no ids. */
    suspend fun check(link: String): Verdict = withContext(Dispatchers.IO) {
        val url = recordURL(link) ?: return@withContext Verdict.Unknown("not a meeting link").also { log(it) }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            for ((name, value) in requestHeaders()) setRequestProperty(name, value)
        }
        val verdict = try {
            val status = connection.responseCode
            val body = if (status == 200) connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) else ""
            verdict(status, body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Verdict.Unknown(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
        log(verdict)
        verdict
    }

    /** The verdicts of several meetings, keyed by event id, one call each. */
    suspend fun checkAll(meetings: List<Meeting>): Map<String, Verdict> =
        meetings.distinctBy { it.event }.associate { it.event to check(it.link) }

    /** The verdict that stops [room] going to a server, or null when it may go (another protocol, or a usable meeting). */
    suspend fun refusal(carrier: String, room: String): Verdict? {
        if (!mustCheck(carrier, room)) return null
        return check(room).takeIf { !it.usable }
    }

    private fun log(verdict: Verdict) {
        val line = "meeting check: " + logWord(verdict)
        if (verdict.usable) AppLog.i(TAG, line) else AppLog.w(TAG, line)
    }
}
