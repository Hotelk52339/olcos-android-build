package io.olcos.app.data

import android.content.Context
import android.content.SharedPreferences
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The servers this device manages through their agents. */
class ServerStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("olcos.servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<ServerRecord>> = _servers

    fun find(host: String, port: Int): ServerRecord? =
        _servers.value.firstOrNull { it.host == host && it.port == port }

    fun byId(id: String): ServerRecord? = _servers.value.firstOrNull { it.id == id }

    /** Adds the server, or replaces the one at the same address. */
    fun upsert(record: ServerRecord) {
        val rest = _servers.value.filterNot { it.id == record.id || (it.host == record.host && it.port == record.port) }
        save(rest + record)
    }

    fun remove(id: String) {
        save(_servers.value.filterNot { it.id == id })
    }

    /** Changes one server in place. */
    fun update(id: String, change: (ServerRecord) -> ServerRecord) {
        save(_servers.value.map { if (it.id == id) change(it) else it })
    }

    /** The server a record belongs to, when this device set it up. */
    fun serverOf(recordID: String): ServerRecord? = _servers.value.firstOrNull { recordID in it.recordIDs }

    private fun save(servers: List<ServerRecord>) {
        _servers.value = servers
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(ServerRecord.serializer()), servers)).apply()
    }

    private fun load(): List<ServerRecord> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ServerRecord.serializer()), text) }
            .getOrDefault(emptyList())
    }

    private companion object {
        const val KEY = "servers"
    }
}
