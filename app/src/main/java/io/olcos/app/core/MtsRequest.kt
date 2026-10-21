package io.olcos.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The request the page sent, as the template keeps it: its URL, headers and
 * body AS SENT, except that a value equal to a cookie, a `localStorage` item
 * or a `sessionStorage` item at capture time is written as a marker
 * (`@cookie(NAME)`, `@local(KEY)`, `@session(KEY)`; the page hook does that
 * IN the page, so only names leave it). At replay the markers are resolved
 * from the page again. Nothing else is scrubbed: a UUID or a long id in a
 * path or a body is what a create call carries and must survive.
 *
 * This is a second copy. The one the sheet shows, logs and dumps stays
 * scrubbed (WebCapture); this one is never shown anywhere.
 */
object MtsRequest {
    /** What the page sent as the body, and so what the replay builds again in the page. */
    sealed class Body {
        /** No body. */
        data object None : Body()

        /** A string (sent with whatever content type the page set, if any); [json] when it parses as JSON. */
        data class Text(val text: String, val json: Boolean) : Body()

        /** A `URLSearchParams`: its fields. The browser sets the content type. */
        data class Form(val fields: List<Pair<String, String>>) : Body()

        /** A `FormData`: its string fields. The browser sets the content type and the boundary. */
        data class Multipart(val fields: List<Pair<String, String>>) : Body()
    }

    /** The page hook's copy of one request; [unsupported] says why it cannot be replayed (a file or binary body). */
    data class Copy(val url: String, val headers: List<Pair<String, String>>, val body: Body, val unsupported: String?)

    /** PURE: the hook's JSON copy of a request; null for anything unreadable. */
    fun parseCopy(text: String?): Copy? {
        if (text.isNullOrEmpty()) return null
        return runCatching {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
            fun pairs(k: String): List<Pair<String, String>> = (o[k] as? JsonArray)?.mapNotNull { el ->
                val p = el as? JsonArray ?: return@mapNotNull null
                val a = (p.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val b = (p.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: ""
                a to b
            } ?: emptyList()
            val body = when (str("kind")) {
                "none" -> Body.None
                "text" -> Body.Text(str("text"), (o["json"] as? JsonPrimitive)?.contentOrNull == "true")
                "form" -> Body.Form(pairs("fields"))
                "multipart" -> Body.Multipart(pairs("fields"))
                else -> Body.None
            }
            val why = if (str("kind") == "unsupported") str("why").ifEmpty { "a body of an unsupported kind" } else null
            Copy(str("url"), pairs("headers"), body, why)
        }.getOrNull()
    }

    // MARK: markers

    data class Marker(val kind: String, val key: String)

    private val markerPattern = Regex("@(cookie|local|session)\\(([A-Za-z0-9_.:-]{1,100})\\)")

    /** PURE: the cookie / storage markers a text holds, in order, once each. */
    fun markersIn(text: String): List<Marker> =
        markerPattern.findAll(text).map { Marker(it.groupValues[1], it.groupValues[2]) }.distinct().toList()

    /** PURE: every marker of a body's strings (the text, or the field values). */
    fun markersIn(body: Body): List<Marker> = when (body) {
        Body.None -> emptyList()
        is Body.Text -> markersIn(body.text)
        is Body.Form -> body.fields.flatMap { markersIn(it.second) }.distinct()
        is Body.Multipart -> body.fields.flatMap { markersIn(it.second) }.distinct()
    }

    /** PURE: how many values of the URL, the literal headers and the body were taken from cookies or storage (for the log; names never leave this file). */
    fun takenFromStorage(url: String, headerValues: List<String>, body: Body): Int {
        val inText = (listOf(url) + headerValues).sumOf { t -> markerPattern.findAll(t).count() }
        val inBody = when (body) {
            Body.None -> 0
            is Body.Text -> markerPattern.findAll(body.text).count()
            is Body.Form -> body.fields.sumOf { markerPattern.findAll(it.second).count() }
            is Body.Multipart -> body.fields.sumOf { markerPattern.findAll(it.second).count() }
        }
        return inText + inBody
    }

    /** How a resolved value is written into the text around its marker. */
    enum class Mode { RAW, JSON, URL }

    /** A marker the page could not give a value for. */
    class Gap(val marker: Marker) : Exception("no value for " + marker.kind + " " + marker.key)

    /** PURE: [text] with each marker replaced by its value from [lookup], written as [mode] says; throws [Gap] for a marker without a value. */
    fun resolve(text: String, mode: Mode, lookup: (Marker) -> String?): String {
        if ('@' !in text) return text
        return markerPattern.replace(text) { m ->
            val marker = Marker(m.groupValues[1], m.groupValues[2])
            val value = lookup(marker)?.takeIf { it.isNotEmpty() } ?: throw Gap(marker)
            when (mode) {
                Mode.RAW -> value
                Mode.JSON -> jsonEscape(value)
                Mode.URL -> percentEncode(value)
            }
        }
    }

    /** PURE: the body with its markers resolved (JSON text is JSON-escaped, a field's value is written as it is); throws [Gap]. */
    fun resolve(body: Body, lookup: (Marker) -> String?): Body = when (body) {
        Body.None -> Body.None
        is Body.Text -> Body.Text(resolve(body.text, if (body.json) Mode.JSON else Mode.RAW, lookup), body.json)
        is Body.Form -> Body.Form(body.fields.map { (k, v) -> k to resolve(v, Mode.RAW, lookup) })
        is Body.Multipart -> Body.Multipart(body.fields.map { (k, v) -> k to resolve(v, Mode.RAW, lookup) })
    }

    private fun jsonEscape(value: String): String = JsonPrimitive(value).toString().let { it.substring(1, it.length - 1) }

    private fun percentEncode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                append(c.toChar())
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }
}
