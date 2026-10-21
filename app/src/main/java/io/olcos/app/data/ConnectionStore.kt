package io.olcos.app.data

import android.content.Context
import android.content.SharedPreferences
import io.olcos.app.core.RoomServices
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.InviteLink
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import io.olcos.app.model.SubscriptionList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One subscription the app follows; an invite keeps the key its link carried. */
@Serializable
data class SubscriptionSource(
    val link: String,
    val name: String? = null,
    val lastRefresh: Long = 0,
    val inviteKey: String? = null,
    /** The list's own `#refresh` as it last came; null when it named none. */
    val refresh: String? = null,
) {
    fun isRefreshDue(now: Long): Boolean = SubscriptionList.isRefreshDue(refresh, lastRefresh, now)
}

/**
 * The saved connections, the subscriptions they came from and which one
 * Connect uses. Kept in the app's preferences as JSON; secrets (the key)
 * live in the link, so the file is private to the app.
 */
class ConnectionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("olcos.connections", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _records = MutableStateFlow(loadRecords())
    val records: StateFlow<List<ConnectionRecord>> = _records

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<SubscriptionSource>> = _sources

    private val _primaryId = MutableStateFlow(prefs.getString(KEY_PRIMARY, null))
    val primaryId: StateFlow<String?> = _primaryId

    val primary: ConnectionRecord?
        get() = _records.value.firstOrNull { it.id == _primaryId.value } ?: _records.value.firstOrNull()

    fun add(details: Details, name: String = ""): ConnectionRecord {
        val record = ConnectionRecord(name = name, details = details)
        saveRecords(_records.value + record)
        if (_primaryId.value == null) setPrimary(record.id)
        return record
    }

    fun add(link: OlcrtcLink, name: String = ""): ConnectionRecord = add(Details.Olcrtc(link), name)

    /** A saved record with exactly these details, so a link pasted twice is not saved twice. */
    fun find(details: Details): ConnectionRecord? = _records.value.firstOrNull { it.details == details }

    fun remove(id: String) {
        saveRecords(_records.value.filterNot { it.id == id })
        if (_primaryId.value == id) setPrimary(_records.value.firstOrNull()?.id)
    }

    /** Replaces the saved record with the same id (its place and the choice of it survive); a stranger is ignored. */
    fun update(record: ConnectionRecord) {
        if (_records.value.none { it.id == record.id }) return
        saveRecords(_records.value.map { if (it.id == record.id) record else it })
    }

    fun setPrimary(id: String?) {
        _primaryId.value = id
        prefs.edit().putString(KEY_PRIMARY, id).apply()
    }

    /**
     * Brings the records of one subscription up to the list: a node already
     * saved is updated in place (its id, and so the choice of it, survive),
     * a new one is added, one gone from the list is removed. A node whose key
     * the server rotated is matched by the rest of its identity. Returns how
     * many connections the list carries.
     */
    fun importSubscription(source: String, list: SubscriptionList, inviteKey: String? = null): Int {
        val canon = canonicalSource(source)
        val result = _records.value.toMutableList()
        val mine = result.filter { it.subSource?.let(::canonicalSource) == canon && it.subNodeKey != null }
        val byKey = mine.associateBy { it.subNodeKey.orEmpty() }
        val listed = HashSet<String>()
        val incoming = list.entries.filter { listed.add(it.nodeKey) }
        val claimed = HashSet<String>()
        for (entry in incoming) {
            var prior = byKey[entry.nodeKey]
            if (prior == null) {
                val stale = byKey.entries.firstOrNull { (k, r) -> k !in listed && k !in claimed && entry.isSameNode(r) }
                if (stale != null) {
                    claimed.add(stale.key)
                    prior = stale.value
                }
            }
            if (prior != null) {
                val at = result.indexOfFirst { it.id == prior.id }
                val updated = prior.copy(name = entry.recordName, details = entry.details, subSource = source, subNodeKey = entry.nodeKey)
                if (at >= 0) result[at] = updated else result += updated
            } else {
                result += ConnectionRecord(name = entry.recordName, details = entry.details, subSource = source, subNodeKey = entry.nodeKey)
            }
        }
        result.removeAll { it.subSource?.let(::canonicalSource) == canon && !listed.contains(it.subNodeKey.orEmpty()) }
        saveRecords(result)
        if (_primaryId.value == null || result.none { it.id == _primaryId.value }) setPrimary(result.firstOrNull()?.id)
        val previous = _sources.value.firstOrNull { canonicalSource(it.link) == canon }
        val others = _sources.value.filterNot { canonicalSource(it.link) == canon }
        saveSources(others + SubscriptionSource(source, list.name ?: previous?.name, System.currentTimeMillis(),
            inviteKey ?: previous?.inviteKey, list.refresh))
        return incoming.size
    }

    /** The subscription a link belongs to, if the app follows it. */
    fun source(link: String): SubscriptionSource? {
        val canon = canonicalSource(link)
        return _sources.value.firstOrNull { canonicalSource(it.link) == canon }
    }

    /**
     * After a room change on the server: every record that rides `carrier` in
     * `oldRoom`, as its own room, an extra room or a joined lane, now rides
     * the new link's room, transport and key. A room is matched as the
     * server names it, so an MTS Link link with an ICE hint rides the room
     * the server reported plain. Returns how many changed.
     */
    fun replaceRoom(carrier: String, oldRoom: String, new: OlcrtcLink): Int {
        val isOld = { room: String -> RoomServices.sameRoom(carrier, room, oldRoom) }
        var changed = 0
        val updated = _records.value.map { record ->
            var link = record.olcrtc ?: return@map record
            var touched = false
            if (link.carrier == carrier && isOld(link.roomID)) {
                link = link.copy(roomID = new.roomID, transport = new.transport, key = new.key)
                touched = true
            }
            if (link.carrier == carrier && link.extraRooms.any(isOld)) {
                link = link.copy(extraRooms = link.extraRooms.map { if (isOld(it)) new.roomID else it })
                touched = true
            }
            if (link.with.any { it.carrier == carrier && isOld(it.roomID) }) {
                link = link.copy(with = link.with.map {
                    if (it.carrier == carrier && isOld(it.roomID)) it.copy(roomID = new.roomID, transport = new.transport) else it
                })
                touched = true
            }
            if (!touched) return@map record
            changed++
            record.copy(details = Details.Olcrtc(link))
        }
        if (changed > 0) saveRecords(updated)
        return changed
    }

    /**
     * After an extra room of a carrier was added on the server: the records
     * that ride the carrier's own room take it as an extra room, and a
     * whole-server record that joins the carrier takes it as a lane.
     */
    fun addRoom(carrier: String, transport: String, anchorRoom: String, newRoom: String): Int {
        val isAnchor = { room: String -> RoomServices.sameRoom(carrier, room, anchorRoom) }
        val isNew = { room: String -> RoomServices.sameRoom(carrier, room, newRoom) }
        var changed = 0
        val updated = _records.value.map { record ->
            val link = record.olcrtc ?: return@map record
            var next = link
            if (link.carrier == carrier && isAnchor(link.roomID) && link.extraRooms.none(isNew)) {
                next = next.copy(extraRooms = next.extraRooms + newRoom)
            }
            if (link.with.any { it.carrier == carrier } && link.with.none { it.carrier == carrier && isNew(it.roomID) } &&
                !(link.carrier == carrier && isAnchor(link.roomID))) {
                next = next.copy(with = next.with + Lane(carrier, transport, newRoom))
            }
            if (next == link) return@map record
            changed++
            record.copy(details = Details.Olcrtc(next))
        }
        if (changed > 0) saveRecords(updated)
        return changed
    }

    /** After a protocol was removed on the server: plain records of that room go, a whole-server record drops the lane. */
    fun dropRoom(carrier: String, room: String): Int {
        val isGone = { r: String -> RoomServices.sameRoom(carrier, r, room) }
        var changed = 0
        val kept = mutableListOf<ConnectionRecord>()
        for (record in _records.value) {
            val link = record.olcrtc
            if (link == null) {
                kept += record
                continue
            }
            if (link.carrier == carrier && isGone(link.roomID) && link.extraRooms.isEmpty() && link.with.isEmpty()) {
                changed++
                continue
            }
            val extra = if (link.carrier == carrier) link.extraRooms.filterNot(isGone) else link.extraRooms
            val with = link.with.filterNot { it.carrier == carrier && isGone(it.roomID) }
            if (extra.size != link.extraRooms.size || with.size != link.with.size) {
                changed++
                kept += record.copy(details = Details.Olcrtc(link.copy(extraRooms = extra, with = with)))
            } else {
                kept += record
            }
        }
        if (changed > 0) {
            saveRecords(kept)
            if (kept.none { it.id == _primaryId.value }) setPrimary(kept.firstOrNull()?.id)
        }
        return changed
    }

    /**
     * After a key rotation: every olcrtc record from `source` or named in `ids`
     * (the server's own records, when this phone set it up and it has no
     * source), and every record sharing one of their keys, takes the new key.
     */
    fun rekey(source: String?, newKey: String, ids: Collection<String> = emptyList()): Int {
        val canon = source?.let(::canonicalSource)
        val oldKeys = _records.value.filter { (canon != null && it.subSource?.let(::canonicalSource) == canon) || it.id in ids }
            .mapNotNull { it.olcrtc?.key }.toSet()
        var changed = 0
        val updated = _records.value.map { record ->
            val link = record.olcrtc ?: return@map record
            val mine = (canon != null && record.subSource?.let(::canonicalSource) == canon) || record.id in ids || link.key in oldKeys
            if (!mine || link.key == newKey) return@map record
            changed++
            record.copy(details = Details.Olcrtc(link.copy(key = newKey)))
        }
        if (changed > 0) saveRecords(updated)
        return changed
    }

    /** Forgets a subscription and every connection it brought. */
    fun removeSource(link: String) {
        val canon = canonicalSource(link)
        saveRecords(_records.value.filterNot { it.subSource?.let(::canonicalSource) == canon })
        saveSources(_sources.value.filterNot { canonicalSource(it.link) == canon })
        if (_records.value.none { it.id == _primaryId.value }) setPrimary(_records.value.firstOrNull()?.id)
    }

    private fun saveRecords(records: List<ConnectionRecord>) {
        _records.value = records
        prefs.edit().putString(KEY_RECORDS, json.encodeToString(ListSerializer(ConnectionRecord.serializer()), records)).apply()
    }

    private fun saveSources(sources: List<SubscriptionSource>) {
        _sources.value = sources
        prefs.edit().putString(KEY_SOURCES, json.encodeToString(ListSerializer(SubscriptionSource.serializer()), sources)).apply()
    }

    private fun loadRecords(): List<ConnectionRecord> {
        val text = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ConnectionRecord.serializer()), text) }
            .getOrDefault(emptyList())
    }

    private fun loadSources(): List<SubscriptionSource> {
        val text = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(SubscriptionSource.serializer()), text) }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val KEY_RECORDS = "records"
        private const val KEY_SOURCES = "sources"
        private const val KEY_PRIMARY = "primary"

        /** One spelling for the olcrtc-sub:// and https forms of the same list; an invite without its key. */
        fun canonicalSource(link: String): String =
            InviteLink.parse(link)?.source
                ?: SubscriptionList.httpsUrl(link)?.lowercase()?.trimEnd('/')
                ?: link.trim()
    }
}
