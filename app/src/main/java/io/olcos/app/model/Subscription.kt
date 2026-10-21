package io.olcos.app.model

import android.util.Base64

/**
 * A subscription: a plain-text list over HTTPS in the upstream sub.md format,
 *
 *     #key: value      global fields (name, refresh, …)
 *     olcrtc://…       one connection per line
 *     ##key: value     fields of the connection line just above
 *
 * with the olcOS extensions `##id` (a node identity that outlives its room and
 * key), `##lanes` (more rooms of the same server), `##with` (the server's other
 * protocols, `carrier?transport@room`), `##bond` (the bond endpoint) and
 * `vless://` lines for the Xray engine. The app reaches the file through an
 * `olcrtc-sub://` link, which is the HTTPS link with its scheme swapped.
 * Parsing is tolerant: unknown fields and lines are skipped, a bad connection
 * line only counts as skipped.
 */
data class SubscriptionList(
    val name: String? = null,
    val refresh: String? = null,
    val entries: List<Entry> = emptyList(),
    val skipped: Int = 0,
    /** An invite's offer of server access (`#agent*` fields), when it carries one. */
    val agent: AgentOffer? = null,
) {
    data class Entry(
        val details: Details,
        val name: String? = null,
        val nodeID: String? = null,
        val ip: String? = null,
        val comment: String? = null,
        /** `##created`: when the room was made, unix seconds (a Telemost room lives 24 h). */
        val createdAt: Long? = null,
    ) {
        val olcrtc: OlcrtcLink? get() = (details as? Details.Olcrtc)?.link
        val xray: XrayLink? get() = (details as? Details.Xray)?.link

        val recordName: String
            get() = name?.takeIf { it.isNotBlank() }
                ?: olcrtc?.let { it.mimo.takeIf { m -> m.isNotBlank() } ?: "${it.carrier} · ${it.transport}" }
                ?: xray?.fallbackName
                ?: ""

        /** The node's identity for a refresh: the `##id`, else what defines the connection. */
        val nodeKey: String
            get() {
                if (!nodeID.isNullOrBlank()) return "id|$nodeID"
                olcrtc?.let { link ->
                    return (listOf(link.carrier, link.transport, link.roomID, link.key, link.clientID) +
                        (if (link.isBundle) listOf("bundle") else emptyList())).joinToString("|")
                }
                val x = xray ?: return ""
                return listOf("xray", x.host, x.port.toString(), x.uuid, x.sni).joinToString("|")
            }

        /** The same node after the server rotated its key. */
        fun isSameNode(record: ConnectionRecord): Boolean {
            olcrtc?.let { link ->
                val r = record.olcrtc ?: return false
                return r.carrier == link.carrier && r.transport == link.transport &&
                    r.roomID == link.roomID && r.clientID == link.clientID && r.isBundle == link.isBundle
            }
            val x = xray ?: return false
            val r = record.xray ?: return false
            return r.host == x.host && r.port == x.port && r.sni == x.sni
        }
    }

    sealed class Import {
        data class Connection(val details: Details, val name: String = "") : Import()
        data class Url(val https: String, val source: String) : Import()
        data class Invite(val invite: InviteLink) : Import()
        data class Body(val text: String) : Import()
        /** A vless:// link of a kind the engine does not run; `reason` names the field (`security=none`). */
        data class Unsupported(val reason: String) : Import()
        data object Unrecognized : Import()
    }

    companion object {
        const val SUB_SCHEME = "olcrtc-sub://"

        /** How often a list without a `#refresh` of its own is fetched again. */
        const val DEFAULT_REFRESH_MS = 30 * 60_000L
        /** The shortest wait between two fetches of one list, whatever it asks for. */
        const val MIN_REFRESH_MS = 60_000L

        /** `#refresh` in milliseconds, read as iOS reads it: `Ns`, `Nm`, `Nh`, `Nd`, a bare number in seconds; null when absent, unreadable or not positive. */
        fun refreshIntervalMs(refresh: String?): Long? {
            val r = refresh?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val unit = when (r.last()) {
                's' -> 1_000L
                'm' -> 60_000L
                'h' -> 3_600_000L
                'd' -> 86_400_000L
                else -> null
            }
            val number = (if (unit == null) r else r.dropLast(1)).trim().toDoubleOrNull() ?: return null
            val ms = (number * (unit ?: 1_000L)).toLong()
            return ms.takeIf { it > 0 }
        }

        /** Whether a list fetched at [lastRefresh] is due again: its own interval (at least a minute), else every half hour. */
        fun isRefreshDue(refresh: String?, lastRefresh: Long, now: Long): Boolean {
            val interval = (refreshIntervalMs(refresh) ?: DEFAULT_REFRESH_MS).coerceAtLeast(MIN_REFRESH_MS)
            return now - lastRefresh >= interval
        }

        private val linkToken = Regex("(?i)(?:olcrtc://|olcrtc-sub://|vless://|https://)\\S+")

        /** Every link in a text, as a messenger would have wrapped it: trailing punctuation and quotes dropped. */
        fun extractLinks(text: String): List<String> =
            linkToken.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '>', '"', '\'', '»', '”', '’') }
                .filter { it.length > "https://".length }.toList()

        /** The first link in a text, or null. */
        fun extractLink(text: String): String? = extractLinks(text).firstOrNull()

        /** What a pasted text is: one connection link, a list to fetch, or a list itself. */
        fun detectImport(raw: String): Import {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return Import.Unrecognized
            val links = extractLinks(raw)
            // One link, alone or inside a message: that link. Several lines with
            // `#` fields are a list body even when they carry a single link.
            val listLike = lines.size > 1 && lines.any { it.startsWith("#") }
            if (!listLike && links.size == 1) classify(links[0])?.let { return it }
            if (lines.size == 1) classify(lines[0])?.let { return it }
            val looksLikeList = links.isNotEmpty() || lines.any {
                it.startsWith(OlcrtcLink.SCHEME, ignoreCase = true) || it.startsWith("#") || XrayLink.matches(it)
            }
            if (looksLikeList || decodedBase64Body(raw) != null) return Import.Body(raw)
            return Import.Unrecognized
        }

        /** One token that is a link: what it imports as, or null when it is none of the kinds. */
        private fun classify(only: String): Import? {
            if (only.startsWith(OlcrtcLink.SCHEME, ignoreCase = true)) {
                return OlcrtcLink.parse(only)?.let { Import.Connection(Details.Olcrtc(it)) } ?: Import.Unrecognized
            }
            if (XrayLink.matches(only)) {
                return try {
                    val (link, name) = XrayLink.parse(only)
                    Import.Connection(Details.Xray(link), name)
                } catch (e: XrayLink.ParseError) {
                    Import.Unsupported(e.message ?: "")
                } catch (e: Exception) {
                    Import.Unrecognized
                }
            }
            InviteLink.parse(only)?.let { return Import.Invite(it) }
            httpsUrl(only)?.let { return Import.Url(it, only) }
            return null
        }

        /** The HTTPS address of a subscription link; null unless it is one (an invite is fetched over plain HTTP). */
        fun httpsUrl(link: String): String? {
            val s = link.trim()
            if (InviteLink.parse(s) != null) return null
            val https = when {
                s.startsWith(SUB_SCHEME, ignoreCase = true) -> "https://" + s.substring(SUB_SCHEME.length)
                s.startsWith("https://", ignoreCase = true) -> s
                else -> null
            }
            return https?.takeIf { it.length > "https://".length + 1 }
        }

        fun parse(body: String): SubscriptionList {
            decodedBase64Body(body)?.let { return parse(it) }
            var name: String? = null
            var refresh: String? = null
            val entries = mutableListOf<Entry>()
            var skipped = 0
            val agentFields = HashMap<String, String>()
            for (rawLine in body.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("##")) {
                    val (key, value) = field(line.substring(2)) ?: continue
                    if (entries.isEmpty()) continue
                    val last = entries.last()
                    val olcrtc = last.olcrtc
                    entries[entries.size - 1] = when (key) {
                        "name" -> last.copy(name = value)
                        "id" -> last.copy(nodeID = value)
                        "ip" -> last.copy(ip = value)
                        "comment" -> last.copy(comment = value)
                        "created" -> last.copy(createdAt = value.toLongOrNull()?.takeIf { it > 0 })
                        "lanes" -> if (olcrtc == null) last else last.copy(details = Details.Olcrtc(olcrtc.copy(
                            extraRooms = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })))
                        "with" -> if (olcrtc == null) last else last.copy(details = Details.Olcrtc(olcrtc.copy(
                            with = value.split(',').mapNotNull { Lane.parse(it.trim()) })))
                        "bond" -> if (olcrtc == null) last else last.copy(details = Details.Olcrtc(olcrtc.copy(
                            bond = value.takeIf { OlcrtcLink.splitEndpoint(it) != null })))
                        else -> last
                    }
                } else if (line.startsWith("#")) {
                    val (key, value) = field(line.substring(1)) ?: continue
                    when (key) {
                        "name" -> name = value
                        "refresh" -> refresh = value
                        "profile-title" -> if (name == null) name = value
                        "profile-update-interval" -> if (refresh == null) {
                            value.toIntOrNull()?.takeIf { it > 0 }?.let { refresh = "${it}h" }
                        }
                        "agent", "agent-cert", "agent-prefix", "agent-code", "agent-role" -> agentFields[key] = value
                    }
                } else {
                    // A line pasted out of a message may carry words before its link.
                    val token = if (line.startsWith(OlcrtcLink.SCHEME, ignoreCase = true) || XrayLink.matches(line)) line
                        else extractLink(line) ?: continue
                    if (token.startsWith(OlcrtcLink.SCHEME, ignoreCase = true)) {
                        val link = OlcrtcLink.parse(token)
                        if (link != null) entries += Entry(Details.Olcrtc(link)) else skipped++
                    } else if (XrayLink.matches(token)) {
                        val parsed = runCatching { XrayLink.parse(token) }.getOrNull()
                        if (parsed != null) {
                            entries += Entry(Details.Xray(parsed.first), name = parsed.second.ifEmpty { null })
                        } else {
                            skipped++
                        }
                    }
                }
            }
            return SubscriptionList(name, refresh, entries, skipped, agentOffer(agentFields))
        }

        /** The offer in an invite's global fields, when all of it is there. */
        fun agentOffer(fields: Map<String, String>): AgentOffer? {
            val endpoint = fields["agent"] ?: return null
            val (host, port) = OlcrtcLink.splitEndpoint(endpoint) ?: return null
            val offer = AgentOffer(
                host = host, port = port,
                certSHA256 = fields["agent-cert"].orEmpty().lowercase(),
                prefix = fields["agent-prefix"].orEmpty(),
                code = fields["agent-code"].orEmpty(),
                role = if (fields["agent-role"] == "owner") "owner" else "member",
            )
            return offer.takeIf { it.isComplete }
        }

        /** The text of a list served base64-encoded, as many providers do. */
        fun decodedBase64Body(body: String): String? {
            val compact = body.filter { !it.isWhitespace() }
            if (compact.length < 16 || compact.contains("://")) return null
            if (!compact.all { it.isLetterOrDigit() || it in "+/=-_" }) return null
            var b64 = compact.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val text = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                ?: return null
            return text.takeIf { it.contains("://") }
        }

        private fun field(s: String): Pair<String, String>? {
            val colon = s.indexOf(':')
            if (colon < 0) return null
            val key = s.substring(0, colon).trim().lowercase()
            val value = s.substring(colon + 1).trim()
            return if (key.isEmpty() || value.isEmpty()) null else key to value
        }
    }
}
