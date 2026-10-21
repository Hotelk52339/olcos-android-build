package io.olcos.app.core

import io.olcos.app.core.RoomServices.MTSLink.Meeting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import java.net.URI

/**
 * What the app learns of MTS Link from the owner's own action in the
 * sign-in sheet, as pure decisions: which recorded call made the meeting he
 * ended up with (and so what a replay should send), how the credential
 * headers of that call are rebuilt later, where in the answer the new
 * meeting sits. The sheet records; the owner presses Finish; only then does
 * [finish] read what was recorded. Nothing here touches a network, a file or
 * a log: the callers do, with the names this file hands them.
 */
object MtsLearn {
    // MARK: where a credential header's value comes from

    /** Where a header's value comes from when the request is made again. */
    sealed class Source {
        /** An ordinary header: sent as it was. */
        data class Literal(val value: String) : Source()

        /** The value of that cookie in `document.cookie`, after [prefix] (`Bearer `). */
        data class Cookie(val name: String, val prefix: String = "") : Source()

        /** The value under that `localStorage` key, after [prefix]. */
        data class Local(val key: String, val prefix: String = "") : Source()

        /** The value under that `sessionStorage` key, after [prefix]. */
        data class Session(val key: String, val prefix: String = "") : Source()

        /** The latest value the page itself sends under that header name when it is loaded again. */
        data class Seen(val name: String) : Source()
    }

    data class Header(val name: String, val source: Source)

    private val credentialName = Regex("(?i)authorization|token|csrf|xsrf|auth|session|secret|api-?key")

    /** A header whose name says its value is a credential: it is never stored, only where it comes from. */
    fun isCredentialHeader(name: String): Boolean = credentialName.containsMatchIn(name)

    private val marker = Regex("^((?:[A-Za-z]{1,12} )?)@(cookie|local|session|seen)\\(([A-Za-z0-9_.:-]{1,100})\\)$")

    /** PURE: the source a page hook's marker names (`Bearer @cookie(NAME)`), or null when [value] is no marker. */
    fun parseMarker(value: String): Source? {
        val m = marker.matchEntire(value.trim()) ?: return null
        val (prefix, kind, key) = m.destructured
        return when (kind) {
            "cookie" -> Source.Cookie(key, prefix)
            "local" -> Source.Local(key, prefix)
            "session" -> Source.Session(key, prefix)
            else -> Source.Seen(key.lowercase())
        }
    }

    /** PURE: the marker text of a source (what the page hook writes, and what the log and the dump show). */
    fun markerOf(source: Source): String = when (source) {
        is Source.Literal -> source.value
        is Source.Cookie -> source.prefix + "@cookie(" + source.name + ")"
        is Source.Local -> source.prefix + "@local(" + source.key + ")"
        is Source.Session -> source.prefix + "@session(" + source.key + ")"
        is Source.Seen -> "@seen(" + source.name + ")"
    }

    // MARK: the template

    enum class Kind { EVENT, LINK }

    /** One step into a JSON answer: an object key or an array index. */
    sealed class Seg {
        data class Key(val name: String) : Seg()
        data class Index(val i: Int) : Seg()
    }

    /**
     * The request that makes a permanent meeting, learned from the page, as the
     * page sent it (MtsRequest): [url], the literal [headers] and [body] hold
     * markers where a value was a cookie or storage item, and [headers] say
     * where each credential comes from, so no cookie or token value is in it.
     * It is still the owner's own request and may carry ids, so it is kept
     * encrypted (MTSLinkTemplate). [contentType] is the one the page set for a
     * string body (empty: the browser's own); a form body has none, the browser
     * sets it. [path] is where in the answer the new meeting sits (its event id
     * for [Kind.EVENT], a string holding its join link for [Kind.LINK]; an
     * empty path with LINK reads the join link out of the whole answer's text).
     */
    data class Template(
        val method: String,
        val url: String,
        val contentType: String,
        val body: MtsRequest.Body,
        val headers: List<Header>,
        val kind: Kind,
        val path: List<Seg>,
        /** The account id of the learned meeting's link (`0` for an alias). */
        val owner: String,
        val learnedAt: Long,
    ) {
        /** The shape for a log line: the method and the URL up to its query. Nothing else. */
        fun shape(): String = "$method " + url.substringBefore('?').substringBefore('#')
    }

    /** PURE: `data.items[0].id`. */
    fun pathText(path: List<Seg>): String = buildString {
        for (seg in path) when (seg) {
            is Seg.Key -> { if (isNotEmpty()) append('.'); append(seg.name) }
            is Seg.Index -> append('[').append(seg.i).append(']')
        }
    }

    private fun parseJson(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

    private fun text(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** PURE: the value at [path] in a JSON answer as text (a string or a number as written), or null. */
    fun evaluate(root: JsonElement, path: List<Seg>): String? {
        var node: JsonElement = root
        for (seg in path) {
            node = when (seg) {
                is Seg.Key -> (node as? JsonObject)?.get(seg.name) ?: return null
                is Seg.Index -> (node as? JsonArray)?.getOrNull(seg.i) ?: return null
            }
        }
        return text(node)
    }

    /** PURE: the shortest path (the first, among equals) to a string or number in [root] that [matches]; null when none does. */
    fun find(root: JsonElement, matches: (String) -> Boolean): List<Seg>? {
        val queue = ArrayDeque<Pair<JsonElement, List<Seg>>>()
        queue.addLast(root to emptyList())
        while (queue.isNotEmpty()) {
            val (node, path) = queue.removeFirst()
            when (node) {
                is JsonObject -> for ((k, v) in node) queue.addLast(v to (path + Seg.Key(k)))
                is JsonArray -> node.forEachIndexed { i, v -> queue.addLast(v to (path + Seg.Index(i))) }
                else -> if (path.isNotEmpty()) text(node)?.let { if (matches(it)) return path }
            }
        }
        return null
    }

    // MARK: what the sheet saw

    /** A page the sheet showed or a link it showed on a page: its text, and when. */
    data class Sighting(val text: String, val at: Long, val fromDom: Boolean) {
        val meetings: List<Meeting> get() = RoomServices.MTSLink.meetingsIn(text)
    }

    /** The pages and links of one open sheet, in order; the page hook reports from one of the web view's own threads. */
    class Sightings {
        private val list = ArrayList<Sighting>()
        private var changes = 0

        @Synchronized
        fun nav(url: String, at: Long = System.currentTimeMillis()) {
            if (list.lastOrNull()?.let { it.text == url && !it.fromDom } == true) return
            add(Sighting(url, at, fromDom = false))
        }

        @Synchronized
        fun dom(text: String, at: Long = System.currentTimeMillis()) {
            if (list.lastOrNull()?.let { it.text == text && it.fromDom } == true) return
            add(Sighting(text, at, fromDom = true))
        }

        private fun add(sighting: Sighting) {
            if (list.size >= LIMIT) list.removeAt(0)
            list += sighting
            changes++
        }

        @Synchronized
        fun all(): List<Sighting> = list.toList()

        /** Bumped by every sighting that was added (the list is capped, so its size alone cannot tell). */
        @Synchronized
        fun count(): Int = changes

        companion object {
            const val LIMIT = 300
        }
    }

    /** One recorded call that made a meeting, and the meeting. [viaLink]: the answer carried the join link itself. */
    data class Created(val entry: WebCapture.Entry, val meeting: Meeting, val viaLink: Boolean)

    private val mutating = setOf("POST", "PUT", "PATCH")
    private val bigNumber = Regex("(?<![0-9])[0-9]{6,20}(?![0-9])")

    /** How long after a call a page may show its meeting for the call to be the one that made it. */
    const val SHOW_WINDOW_MS = 10_000L

    /**
     * PURE: the calls that made a meeting, once per meeting and the earliest
     * of its calls. A call counts only when it is a POST, PUT or PATCH that
     * the service answered 2xx, and its answer carries (a) a join link the
     * request itself did not carry, or (b) a number of six digits or more
     * the request did not carry either, which the sheet then showed as the
     * event of a `/j/<owner>/<event>` page within [SHOW_WINDOW_MS]. A GET —
     * a list that mentions an old meeting — is never one.
     */
    fun created(entries: List<WebCapture.Entry>, sightings: List<Sighting>): List<Created> {
        val out = ArrayList<Created>()
        for (entry in entries) {
            if (entry.method !in mutating) continue
            val status = entry.status ?: continue
            if (status !in 200..299 || entry.response.isEmpty()) continue
            val request = (entry.url + " " + entry.body).replace("\\/", "/")
            val response = entry.response.replace("\\/", "/")
            val linked = RoomServices.MTSLink.meetingsIn(response).filter { it.event !in request }
            if (linked.isNotEmpty()) {
                for (m in linked) out += Created(entry, m, viaLink = true)
                continue
            }
            val until = maxOf(entry.respondedAt, entry.at) + SHOW_WINDOW_MS
            for (id in bigNumber.findAll(response).map { it.value }.distinct()) {
                if (id in request) continue
                val shown = sightings.firstOrNull { s -> s.at >= entry.at && s.at <= until && s.meetings.any { it.event == id } }
                    ?.meetings?.firstOrNull { it.event == id } ?: continue
                out += Created(entry, shown, viaLink = false)
            }
        }
        return out.distinctBy { it.meeting.event }
    }

    /** PURE: whether what is recorded already looks like a meeting being made: the passive hint, binding nothing. */
    fun looksCreated(entries: List<WebCapture.Entry>, sightings: List<Sighting>): Boolean = created(entries, sightings).isNotEmpty()

    /** What the Finish button found. */
    sealed class Result {
        /** No meeting on any recorded call or page. */
        data object None : Result()

        /**
         * Exactly one meeting. [madeByApp]: a recorded create call produced it.
         * [template] is set only when that call was understood well enough to
         * replay; otherwise [why] says what stopped it (for the log).
         */
        data class One(val meeting: Meeting, val madeByApp: Boolean, val template: Template?, val why: String?) : Result()

        /** Several different meetings: nothing is learned, the owner picks one or pastes a link. */
        data class Several(val meetings: List<Meeting>) : Result()
    }

    /**
     * PURE: what the sheet's session holds, read once the owner presses
     * Finish. One create call's meeting is the answer (with the template
     * built from that call); two or more different created meetings are
     * several; with no create call at all the meetings the pages and links
     * named decide, and then nothing is ever learned.
     */
    fun finish(entries: List<WebCapture.Entry>, sightings: List<Sighting>, now: Long): Result {
        val made = created(entries, sightings)
        if (made.size == 1) {
            val built = buildTemplate(made[0], now)
            return Result.One(made[0].meeting, madeByApp = true, template = built.template, why = built.why)
        }
        if (made.size > 1) return Result.Several(made.map { it.meeting })
        val seen = sightings.flatMap { it.meetings }.distinctBy { it.event }
        return when (seen.size) {
            0 -> Result.None
            1 -> Result.One(seen[0], madeByApp = false, template = null, why = "no create call was recorded")
            else -> Result.Several(seen)
        }
    }

    /** The template of a create call, or why there is none. */
    data class Built(val template: Template?, val why: String?)

    private val browserManaged = setOf("cookie", "content-length", "host", "origin", "referer", "user-agent", "accept-encoding", "connection")

    /**
     * PURE: the replayable template of [created], or the reason it cannot be
     * one. It is built from the page hook's unscrubbed copy of the request
     * (WebCapture.Entry.copy), never from the scrubbed one the sheet shows.
     */
    fun buildTemplate(created: Created, now: Long): Built {
        val entry = created.entry
        val copy = entry.raw ?: return Built(null, "the page hook gave no copy of the request")
        copy.unsupported?.let { return Built(null, "the request carries $it") }
        if (!copy.url.startsWith("https://")) return Built(null, "the request URL is not an absolute https URL")
        val json = parseJson(entry.response)
        val kind: Kind
        val path: List<Seg>
        if (created.viaLink) {
            kind = Kind.LINK
            path = if (json != null) {
                find(json) { v -> RoomServices.MTSLink.meetingsIn(v).any { it.event == created.meeting.event } }
                    ?: return Built(null, "the join link is not in the answer's JSON")
            } else {
                emptyList()
            }
        } else {
            kind = Kind.EVENT
            if (json == null) return Built(null, "the answer is not readable JSON")
            path = find(json) { it == created.meeting.event } ?: return Built(null, "the event id is not in the answer's JSON")
        }
        // A string body keeps the content type the page set (none: the browser's own); a form body has none
        // here, because the browser sets the content type (and a multipart boundary) itself.
        val textBody = copy.body is MtsRequest.Body.Text
        var contentType = ""
        val headers = ArrayList<Header>()
        for ((name, value) in copy.headers) {
            val lower = name.lowercase()
            if (lower == "content-type") {
                if (textBody) contentType = value
            } else if (lower in browserManaged) {
                continue
            } else if (isCredentialHeader(name)) {
                headers += Header(name, parseMarker(value) ?: Source.Seen(lower))
            } else {
                headers += Header(name, Source.Literal(value))
            }
        }
        return Built(Template(entry.method, copy.url, contentType, copy.body, headers, kind, path, created.meeting.owner, now), null)
    }

    /** PURE: how many values of the template's URL, headers and body were taken from cookies or storage (for the log). */
    fun takenFromStorage(template: Template): Int =
        MtsRequest.takenFromStorage(template.url, template.headers.mapNotNull { (it.source as? Source.Literal)?.value }, template.body)

    /** PURE: the headers whose value was not a literal, as `name<-source` (names only), for the log. */
    fun sourceNames(template: Template): List<String> = template.headers.filter { it.source !is Source.Literal }.map {
        it.name.lowercase() + "<-" + markerOf(it.source).substringAfter(' ')
    }

    // MARK: persistence

    private fun pathJson(path: List<Seg>): JsonArray = buildJsonArray {
        for (seg in path) when (seg) {
            is Seg.Key -> add(JsonPrimitive(seg.name))
            is Seg.Index -> add(JsonPrimitive(seg.i))
        }
    }

    /** The version of the kept text. 1 held a scrubbed body and no body kind: it is not read (see [decode]). */
    private const val FORMAT = 2

    private fun fieldsJson(fields: List<Pair<String, String>>): JsonArray = buildJsonArray {
        for ((k, v) in fields) add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(v)) })
    }

    private fun fieldsOf(element: JsonElement?): List<Pair<String, String>> = (element as JsonArray).map { el ->
        val p = el as JsonArray
        (p[0] as JsonPrimitive).content to (p[1] as JsonPrimitive).content
    }

    /** PURE: the template as the text that is kept (encrypted by the store: it holds the request as the page sent it). */
    fun encode(template: Template): String = buildJsonObject {
        put("v", FORMAT)
        put("method", template.method)
        put("url", template.url)
        put("contentType", template.contentType)
        when (val b = template.body) {
            MtsRequest.Body.None -> put("bodyKind", "none")
            is MtsRequest.Body.Text -> { put("bodyKind", "text"); put("body", b.text); put("json", b.json) }
            is MtsRequest.Body.Form -> { put("bodyKind", "form"); put("fields", fieldsJson(b.fields)) }
            is MtsRequest.Body.Multipart -> { put("bodyKind", "multipart"); put("fields", fieldsJson(b.fields)) }
        }
        put("headers", buildJsonArray {
            for (h in template.headers) add(buildJsonObject {
                put("name", h.name)
                when (val s = h.source) {
                    is Source.Literal -> { put("kind", "literal"); put("value", s.value) }
                    is Source.Cookie -> { put("kind", "cookie"); put("key", s.name); put("prefix", s.prefix) }
                    is Source.Local -> { put("kind", "local"); put("key", s.key); put("prefix", s.prefix) }
                    is Source.Session -> { put("kind", "session"); put("key", s.key); put("prefix", s.prefix) }
                    is Source.Seen -> { put("kind", "seen"); put("key", s.name) }
                }
            })
        })
        put("result", template.kind.name.lowercase())
        put("path", pathJson(template.path))
        put("owner", template.owner)
        put("learnedAt", template.learnedAt)
    }.toString()

    /** PURE: a kept template back; null for anything unreadable (it is then as if nothing was learned). */
    fun decode(text: String?): Template? {
        if (text.isNullOrEmpty()) return null
        return runCatching {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun str(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.contentOrNull ?: ""
            // An older text (format 1) held a scrubbed body: replaying it would send blanks, so it counts as nothing learned.
            if (str(o, "v") != FORMAT.toString()) error("an older format")
            val body: MtsRequest.Body = when (str(o, "bodyKind")) {
                "none" -> MtsRequest.Body.None
                "text" -> MtsRequest.Body.Text(str(o, "body"), str(o, "json") == "true")
                "form" -> MtsRequest.Body.Form(fieldsOf(o["fields"]))
                "multipart" -> MtsRequest.Body.Multipart(fieldsOf(o["fields"]))
                else -> error("unknown body kind")
            }
            val headers = (o["headers"] as JsonArray).map { el ->
                val h = el as JsonObject
                val source = when (str(h, "kind")) {
                    "literal" -> Source.Literal(str(h, "value"))
                    "cookie" -> Source.Cookie(str(h, "key"), str(h, "prefix"))
                    "local" -> Source.Local(str(h, "key"), str(h, "prefix"))
                    "session" -> Source.Session(str(h, "key"), str(h, "prefix"))
                    "seen" -> Source.Seen(str(h, "key"))
                    else -> error("unknown source")
                }
                Header(str(h, "name"), source)
            }
            val path = (o["path"] as JsonArray).map { el ->
                val p = el as JsonPrimitive
                if (p.isString) Seg.Key(p.content) else Seg.Index(p.int)
            }
            val template = Template(
                method = str(o, "method"), url = str(o, "url"), contentType = str(o, "contentType"), body = body,
                headers = headers, kind = if (str(o, "result") == "link") Kind.LINK else Kind.EVENT, path = path,
                owner = str(o, "owner").ifEmpty { "0" }, learnedAt = (o["learnedAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            )
            if (template.method !in mutating || !template.url.startsWith("https://")) error("not a request")
            template
        }.getOrNull()
    }

    // MARK: the replay

    /** What the page has to hand over before the headers can be rebuilt: the names the template's credential sources point at. */
    data class Needs(val cookies: Set<String>, val local: Set<String>, val session: Set<String>, val seen: Set<String>) {
        val onlySeen: Boolean get() = cookies.isEmpty() && local.isEmpty() && session.isEmpty()
    }

    /** PURE: what [template]'s credential headers need from the page. */
    fun needs(template: Template): Needs {
        val cookies = LinkedHashSet<String>()
        val local = LinkedHashSet<String>()
        val session = LinkedHashSet<String>()
        val seen = LinkedHashSet<String>()
        fun take(marker: MtsRequest.Marker) {
            when (marker.kind) {
                "cookie" -> cookies += marker.key
                "local" -> local += marker.key
                else -> session += marker.key
            }
        }
        for (h in template.headers) when (val s = h.source) {
            is Source.Cookie -> cookies += s.name
            is Source.Local -> local += s.key
            is Source.Session -> session += s.key
            is Source.Seen -> seen += s.name
            is Source.Literal -> MtsRequest.markersIn(s.value).forEach(::take)
        }
        MtsRequest.markersIn(template.url).forEach(::take)
        MtsRequest.markersIn(template.body).forEach(::take)
        return Needs(cookies, local, session, seen)
    }

    sealed class Assembled {
        /**
         * The request to send: its URL, its headers and its body with every
         * marker resolved, and (names only) where each credential came from.
         */
        data class Ready(
            val headers: List<Pair<String, String>>,
            val resolved: List<String>,
            val url: String,
            val body: MtsRequest.Body,
        ) : Assembled()

        /** A credential the original had could not be rebuilt ([header] names it: a header, or a cookie / storage item the URL or body used): the request is not sent. */
        data class Missing(val header: String) : Assembled()
    }

    /**
     * PURE: the replayed request from what the page handed over. [seen] is
     * keyed by lowercase header name. A credential whose source cannot be
     * resolved (nothing under that cookie or key, nothing seen) aborts with
     * [Assembled.Missing]: no request goes out without a token the original
     * had. The URL, the literal headers and the body get their markers back
     * as values (a query value percent-encoded, a JSON string escaped).
     */
    fun assemble(
        template: Template,
        cookies: Map<String, String>,
        local: Map<String, String>,
        session: Map<String, String>,
        seen: Map<String, String>,
    ): Assembled {
        val lookup = { m: MtsRequest.Marker ->
            when (m.kind) {
                "cookie" -> cookies[m.key]
                "local" -> local[m.key]
                else -> session[m.key]
            }
        }
        val out = ArrayList<Pair<String, String>>()
        val resolved = ArrayList<String>()
        try {
            for (h in template.headers) {
                val value: String = when (val s = h.source) {
                    is Source.Literal -> MtsRequest.resolve(s.value, MtsRequest.Mode.RAW, lookup)
                    is Source.Cookie -> cookies[s.name]?.takeIf { it.isNotEmpty() }?.let { s.prefix + it }
                    is Source.Local -> local[s.key]?.takeIf { it.isNotEmpty() }?.let { s.prefix + it }
                    is Source.Session -> session[s.key]?.takeIf { it.isNotEmpty() }?.let { s.prefix + it }
                    is Source.Seen -> seen[s.name.lowercase()]?.takeIf { it.isNotEmpty() }
                } ?: return Assembled.Missing(h.name)
                out += h.name to value
                if (h.source !is Source.Literal) resolved += h.name.lowercase() + "<-" + markerOf(h.source).substringAfter(' ')
            }
            val url = MtsRequest.resolve(template.url, MtsRequest.Mode.URL, lookup)
            val body = MtsRequest.resolve(template.body, lookup)
            for (m in MtsRequest.markersIn(template.url) + MtsRequest.markersIn(template.body)) resolved += "request<-@${m.kind}(${m.key})"
            // Only a string body carries the content type the page set; a form's is the browser's own.
            if (template.body is MtsRequest.Body.Text && template.contentType.isNotEmpty() &&
                out.none { it.first.equals("content-type", ignoreCase = true) }
            ) {
                out += "Content-Type" to template.contentType
            }
            return Assembled.Ready(out, resolved.distinct(), url, body)
        } catch (gap: MtsRequest.Gap) {
            return Assembled.Missing(gap.marker.kind + " " + gap.marker.key)
        }
    }

    /** PURE: the meeting link the answer to a replayed request holds, or null when it holds none. */
    fun resultLink(template: Template, answer: String): String? {
        val json = parseJson(answer)
        return when (template.kind) {
            Kind.EVENT -> {
                val event = json?.let { evaluate(it, template.path) }?.takeIf { e -> e.length in 1..20 && e.all { it.isDigit() } } ?: return null
                Meeting(template.owner, event).link
            }
            Kind.LINK -> {
                val source = if (template.path.isEmpty()) answer else json?.let { evaluate(it, template.path) } ?: return null
                RoomServices.MTSLink.meetingsIn(source).firstOrNull()?.link
            }
        }
    }

    /** PURE: whether a made room is a new one: the same meeting as the room that is current is a failure (the service handed back the old one). */
    fun isNewRoom(made: String, current: String): Boolean =
        current.isEmpty() || !RoomServices.sameRoom("mtslink", made, current)

    /** What the page handed over for [needs]: the values by name (seen headers by lowercase name) and the page's address. */
    data class Collected(
        val cookies: Map<String, String>,
        val local: Map<String, String>,
        val session: Map<String, String>,
        val seen: Map<String, String>,
        val href: String,
    )

    /** PURE: the page's answer to [MtsLinkScripts.collectScript] (`evaluateJavascript` hands a string back JSON-encoded); null for anything else. */
    fun parseCollected(raw: String?): Collected? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        val outer = parseJson(raw) as? JsonPrimitive ?: return null
        val inner = parseJson(outer.content) as? JsonObject ?: return null
        fun map(key: String): Map<String, String> =
            (inner[key] as? JsonObject)?.entries?.mapNotNull { (k, v) -> text(v)?.let { k to it } }?.toMap() ?: emptyMap()
        return Collected(map("cookies"), map("local"), map("session"), map("seen"), text(inner["href"] ?: JsonNull) ?: "")
    }

    /** What the answer to a replayed request means. */
    sealed class Answer {
        data class Made(val link: String) : Answer()

        /** The service refused the account (401 / 403): sign in again. */
        data object SignedOut : Answer()

        /** Any other failure; [reason] is for the log (no values). */
        data class Failed(val reason: String) : Answer()
    }

    /** PURE: the meaning of a replayed request's answer; [current] is the room being replaced, which the answer must not be. */
    fun readAnswer(template: Template, status: Int, text: String, current: String): Answer {
        if (status == 401 || status == 403) return Answer.SignedOut
        if (status !in 200..299) return Answer.Failed("HTTP $status")
        val link = resultLink(template, text)
            ?: return Answer.Failed("the answer holds no meeting at '" + (pathText(template.path).ifEmpty { "(whole text)" }) + "'")
        if (!isNewRoom(link, current)) return Answer.Failed("the service handed back the room that is current")
        return Answer.Made(link)
    }

    private val signInPath = Regex("(?i)/(login|signin|sign-in|sign_in|auth|authorize|registration|register|signup|sign-up|sso|oauth)(/|\\z)")

    /** PURE: whether the page the replay ended on is a sign-in, a login or a registration page: the saved session is dead. */
    fun isSignInUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        if (!(host == "mts-link.ru" || host.endsWith(".mts-link.ru"))) return true
        return signInPath.containsMatchIn(uri.path ?: "")
    }
}
