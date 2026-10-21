package io.olcos.app.core

import java.util.ArrayDeque

/**
 * The calls a sign-in sheet's page makes, kept for the sheet's "what the
 * app saw" list and its copyable dump: the method, the full URL, the
 * headers the page sent and the body. The point is to learn which request
 * makes a meeting, so the request's shape stays readable — but nothing
 * that identifies the account is kept: the values of the Cookie,
 * Authorization and x-auth-token headers never reach the buffer, the same
 * for any other header whose name says credential, and cookie, token,
 * session, password and key values in a URL or a body are blanked the way
 * AppLog blanks them. A meeting link and a meeting name are not secrets
 * and stay. Nothing captured goes anywhere: the buffer is memory of the
 * open sheet alone, and the dump travels by clipboard.
 */
object WebCapture {
    /** One captured call. */
    data class Entry(
        val method: String,
        val url: String,
        /** The headers as they are kept: one `Name: value` per line (see [scrubHeaders]). */
        val headers: String,
        /** The body as it is kept (see [scrub]), at most [BODY_LIMIT] characters. */
        val body: String,
        /** Whether the body was longer than [BODY_LIMIT] and got cut. */
        val bodyCut: Boolean,
        /** The page hook's number for the call, to pair its answer with it; 0 when there is none. */
        val id: Int = 0,
        /** When the call was made, in epoch milliseconds. */
        val at: Long = 0L,
        /** The HTTP status once the answer came; null while it is awaited (or never came). */
        val status: Int? = null,
        /** The answer's body as it is kept (see [scrub]), at most [RESPONSE_LIMIT] characters. */
        val response: String = "",
        /** When the answer came, in epoch milliseconds; 0 while it is awaited. */
        val respondedAt: Long = 0L,
        /**
         * The page hook's second, UNscrubbed copy of the request (URL, headers and body as sent, with cookie and
         * storage values already turned into markers in the page): what a template is built from. It is never
         * shown, logged or dumped; everything above is the scrubbed one.
         */
        val raw: MtsRequest.Copy? = null,
    ) {
        /** The one block of text the dump carries for this call. */
        fun dump(): String = buildString {
            append(method).append(' ').append(url)
            if (status != null) append(" → ").append(status)
            if (headers.isNotEmpty()) append('\n').append(headers)
            if (body.isNotEmpty()) {
                append("\n\n").append(body)
                if (bodyCut) append("\n…")
            }
            if (response.isNotEmpty()) {
                append("\n\n← ").append(response.take(DUMP_RESPONSE))
                if (response.length > DUMP_RESPONSE) append("\n…")
            }
        }
    }

    /** The kept part of one body: this many characters, and the rest goes. */
    const val BODY_LIMIT = 8 * 1024

    /** The kept part of one answer: the page hook sends no more than this, and the learning reads it whole. */
    const val RESPONSE_LIMIT = 16 * 1024

    /** How much of an answer one dump block carries; the sheet's copy button gives the developer the head of each. */
    private const val DUMP_RESPONSE = 2 * 1024

    /**
     * A credential header's value as the page hook reports it: where the
     * page got the value from, never the value. `@cookie(NAME)`,
     * `@local(KEY)`, `@session(KEY)` or `@seen(header-name)`, with an
     * optional scheme word before it (`Bearer @cookie(NAME)`).
     */
    val provenanceMarker = Regex("^(?:[A-Za-z]{1,12} )?@(?:cookie|local|session|seen)\\([A-Za-z0-9_.:-]{1,100}\\)$")

    /** How many calls one buffer holds: the oldest goes when one more arrives. */
    const val LIMIT = 100

    /** The headers whose values are credentials and are never kept; their names stay, so the shape of the request does. */
    private val SECRET_HEADERS = setOf("cookie", "authorization", "x-auth-token")

    /** A header name that says its value is a credential. */
    private val secretName = Regex("(?i)(token|auth|cookie|session|password|passwd|secret|sid|key)")

    /** A `name=value` whose name says the value is a credential. */
    private val secretValue = Regex("(?i)\\b([\\w.-]*(?:token|auth|cookie|session|password|passwd|secret|sid|key)[\\w.-]*)=([^;&\\s\"']+)")

    /** The same as a JSON pair: `"name": "value"`. */
    private val secretJson = Regex("(?i)\"([\\w.-]*(?:token|auth|cookie|session|password|passwd|secret|sid|key)[\\w.-]*)\"\\s*:\\s*\"[^\"]*\"")

    /** An authorization scheme with its credential: `Bearer eyJ…`. */
    private val scheme = Regex("(?i)\\b(bearer|basic|negotiate)\\s+[\\w.+/=_-]+")

    /** A 64-hex key. */
    private val hexKey = Regex("[0-9a-fA-F]{64}")

    /** An opaque run long enough not to be a word: a key or a token, or occasionally a name with no spaces in it. */
    private val opaque = Regex("\\b[A-Za-z0-9+/=_-]{32,}\\b")

    /**
     * PURE: one call as it is kept: every credential value scrubbed away,
     * the body capped at [BODY_LIMIT]. The method comes out in capitals,
     * and an empty one reads as GET.
     */
    fun entry(
        method: String, url: String, headers: Map<String, String>, body: String, id: Int = 0, at: Long = 0L,
        raw: MtsRequest.Copy? = null,
    ): Entry {
        val cut = body.length > BODY_LIMIT
        return Entry(
            method = method.trim().uppercase().ifEmpty { "GET" },
            url = scrub(url),
            headers = scrubHeaders(headers),
            body = scrub(if (cut) body.substring(0, BODY_LIMIT) else body),
            bodyCut = cut,
            id = id,
            at = at,
            raw = raw,
        )
    }

    /** PURE: [entry] with the answer that came for it: the status, and the body scrubbed and capped at [RESPONSE_LIMIT]. */
    fun answered(entry: Entry, status: Int, response: String, at: Long = 0L): Entry =
        entry.copy(status = status, response = scrub(response.take(RESPONSE_LIMIT)), respondedAt = at)

    /**
     * PURE: the headers as they are kept: one `Name: value` per line. The
     * value of a credential header — the three by name, and any other whose
     * name says credential — is `<hidden>` with the name left standing,
     * unless the page hook already replaced it with a provenance marker
     * (where the value comes from, which is no secret); the rest is scrubbed
     * like any other text. Cookie never carries a marker.
     */
    fun scrubHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { (name, value) ->
            val hidden = name.lowercase() in SECRET_HEADERS || secretName.containsMatchIn(name)
            val marked = hidden && name.lowercase() != "cookie" && provenanceMarker.matches(value)
            "$name: " + (if (marked) value else if (hidden) "<hidden>" else scrub(value))
        }

    /**
     * PURE: the text as it is kept: the credential values blanked — the
     * sign-in cookies and tokens, the authorization schemes, the long
     * opaque runs — and everything else as it was. A meeting link and a
     * meeting name stay: the dump exists to say which request makes which
     * meeting.
     */
    fun scrub(text: String): String {
        var out = scheme.replace(text) { it.groupValues[1] + " <hidden>" }
        out = secretValue.replace(out) { it.groupValues[1] + "=<hidden>" }
        out = secretJson.replace(out) { "\"${it.groupValues[1]}\":\"<hidden>\"" }
        out = hexKey.replace(out, "<key>")
        // A run with a digit or a base64 mark in it is a key or a token; one of letters and dashes may be a name, and stays.
        out = opaque.replace(out) { m -> if (m.value.any { it.isDigit() || it == '+' || it == '/' || it == '=' || it == '_' }) "<key>" else m.value }
        return out
    }

    /**
     * The calls of one open sheet, in order: what the bridge reports of the
     * page's own fetch and XMLHttpRequest calls. Bounded on both ends —
     * [LIMIT] calls, [BODY_LIMIT] characters of a body — and scrubbed on the
     * way in, so nothing unscrubbed is ever held here.
     */
    class Buffer(private val limit: Int = LIMIT) {
        private val kept = ArrayDeque<Entry>()
        private var changes = 0

        /** Bumped by every call and every answer, so a screen can tell whether anything changed since it last looked. */
        @Synchronized
        fun version(): Int = changes

        @Synchronized
        fun record(
            method: String, url: String, headers: Map<String, String>, body: String, id: Int = 0, at: Long = System.currentTimeMillis(),
            raw: MtsRequest.Copy? = null,
        ): Entry {
            val entry = entry(method, url, headers, body, id, at, raw)
            if (kept.size >= limit) kept.removeFirst()
            kept.addLast(entry)
            changes++
            return entry
        }

        /** The answer to call [id]: kept with the call. An answer to a call that is no longer here (or was never numbered) is dropped. */
        @Synchronized
        fun respond(id: Int, status: Int, response: String, at: Long = System.currentTimeMillis()) {
            if (id == 0) return
            val list = kept.toMutableList()
            val index = list.indexOfLast { it.id == id }
            if (index < 0) return
            list[index] = answered(list[index], status, response, at)
            kept.clear()
            kept.addAll(list)
            changes++
        }

        @Synchronized
        fun entries(): List<Entry> = kept.toList()

        @Synchronized
        fun count(): Int = kept.size

        @Synchronized
        fun clear() {
            kept.clear()
        }

        /** The copyable dump: every call as one block, in the order they were made. */
        @Synchronized
        fun dump(): String = kept.joinToString("\n\n") { it.dump() }
    }
}
