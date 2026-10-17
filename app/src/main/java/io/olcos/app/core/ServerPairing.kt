package io.olcos.app.core

import android.os.Build
import io.olcos.app.AppGraph
import io.olcos.app.data.ServerStore
import io.olcos.app.model.AgentOffer
import io.olcos.app.model.ServerRecord

/**
 * Turns an invite's agent offer into a managed server: this device's key is
 * registered with the agent once, with the role the offer grants. A failed
 * pairing keeps the code for a retry from the server page.
 */
object ServerPairing {
    sealed class Outcome {
        data class Paired(val server: ServerRecord) : Outcome()
        data class Pending(val server: ServerRecord, val reason: String) : Outcome()
        data object AlreadyPaired : Outcome()
    }

    val deviceName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
            .replaceFirstChar { it.uppercase() }.take(40)

    /** Pairs when the offer is new for this address; a paired server is left alone. */
    suspend fun take(offer: AgentOffer, serverName: String, source: String?, servers: ServerStore): Outcome {
        val existing = servers.find(offer.host, offer.port)
        if (existing != null && existing.isPaired && existing.prefix == offer.prefix) return Outcome.AlreadyPaired
        val base = existing ?: ServerRecord(
            name = serverName, host = offer.host, port = offer.port,
            certSHA256 = offer.certSHA256, prefix = offer.prefix, role = offer.role, source = source,
        )
        return pair(base.copy(
            name = serverName.ifBlank { base.name }, certSHA256 = offer.certSHA256, prefix = offer.prefix,
            role = offer.role, source = source ?: base.source, pendingCode = offer.code, deviceID = null, lastError = null,
        ), servers)
    }

    /** Redeems the server's pending code; the record is saved either way. */
    suspend fun pair(server: ServerRecord, servers: ServerStore): Outcome {
        val code = server.pendingCode ?: return Outcome.Pending(server, "no pairing code")
        val offer = AgentOffer(server.host, server.port, server.certSHA256, server.prefix, code, server.role)
        return try {
            val key = AgentKey.loadOrCreate()
            val (deviceID, role) = try {
                AgentClient.pair(offer, key, deviceName, AgentClient.routesFor(server))
            } catch (e: AgentClient.AgentError) {
                // Turned down: when an earlier try was taken and only its answer
                // was lost, the code is spent and this key is already registered.
                if (e.status != 404 || !registered(server, key)) throw e
                key.deviceID to server.role
            }
            val paired = server.copy(deviceID = deviceID, role = role, pendingCode = null, lastError = null)
            servers.upsert(paired)
            AppLog.i("olcos.agent", "paired with ${server.host} as $role")
            Outcome.Paired(paired)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A screen that left or restarted its work: not a failure to show.
            throw e
        } catch (e: Exception) {
            // The stored reason is what the pending card shows: the person's words (a 404 is "check the clock"), not the wire's.
            val reason = AppGraph.appContext?.let { UserFacing.describe(it, e) } ?: AgentClient.reason(e)
            val pending = server.copy(deviceID = null, lastError = reason)
            servers.upsert(pending)
            AppLog.w("olcos.agent", "pairing with ${server.host} failed: $reason")
            Outcome.Pending(pending, reason)
        }
    }

    /** Whether the agent already knows this key: a signed call it answers. */
    private suspend fun registered(server: ServerRecord, key: AgentKey): Boolean =
        runCatching { AgentClient(server, key).status() }.isSuccess

    /**
     * Pairs every server still waiting that the live session runs through:
     * its exit reaches the agent when the phone's own network does not.
     */
    suspend fun retryPending(servers: ServerStore) {
        val record = TunnelManager.record.value ?: return
        for (server in servers.servers.value) {
            if (server.isPaired || server.pendingCode == null) continue
            if (!AgentClient.isThrough(server, record.id, record.subSource)) continue
            pair(server, servers)
        }
    }
}
