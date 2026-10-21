package io.olcos.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.olcos.app.data.ConnectionStore
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.ServerRecord

/**
 * How a session that was lost comes back, as the iOS app's reconnect loop
 * does it (TunnelManager.requestReconnect): the rooms are joined again after a
 * capped, jittered backoff; a connection that keeps failing gives way to
 * another protocol of the same server, then to a connection whose last check
 * passed; when every one has had its turn, the session is given up. A start
 * the person asked for never enters this: its failure is shown at once.
 */
object Recovery {
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 60_000L
    /** Tries on one connection with nowhere to move to: 2+4+8+16+32+60 s, about two minutes. */
    const val MAX_ATTEMPTS = 6
    /** Tries on one connection when another one waits: about 14 s each. */
    const val ATTEMPTS_PER_CANDIDATE = 3
    /** With no network at all, a recovery waits for one (a tunnel, the underground) instead of spending its tries. */
    const val OFFLINE_RETRY_MS = 10_000L
    const val OFFLINE_LIMIT_MS = 30 * 60_000L

    /**
     * Whether the phone has a network with internet now. The app's own
     * sockets stay off the VPN, so in VPN mode too this is the network the
     * rooms would run over.
     */
    fun networkUp(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * PURE: the wait before try [attempt] (0 first): 2 s doubling to 60 s,
     * less up to a quarter, so phones that lost a room together do not all
     * come back to it in the same second.
     */
    fun delayMs(attempt: Int, random: Double = Math.random()): Long {
        val base = (BASE_DELAY_MS shl attempt.coerceIn(0, 20)).coerceAtMost(MAX_DELAY_MS)
        return (base * (1.0 - 0.25 * random.coerceIn(0.0, 1.0))).toLong()
    }

    /**
     * PURE: where to go when [current] stops working: the other protocols of
     * its server first (the ones the server made, or its lists brought), then
     * any connection whose last check passed, fastest first. Nothing unchecked
     * or failing: a tour of dead servers would only make the outage longer.
     */
    fun candidates(
        current: ConnectionRecord,
        records: List<ConnectionRecord>,
        servers: List<ServerRecord>,
        checks: Map<String, Health.Check>,
    ): List<ConnectionRecord> {
        val home = servers.firstOrNull { AgentClient.isThrough(it, current.id, current.subSource) }
        val siblings = records.filter { record ->
            record.id != current.id && when {
                home != null -> AgentClient.isThrough(home, record.id, record.subSource)
                current.subSource != null -> record.subSource?.let { ConnectionStore.canonicalSource(it) } ==
                    ConnectionStore.canonicalSource(current.subSource)
                else -> false
            }
        }.sortedBy { checks[it.id]?.ms ?: Long.MAX_VALUE }
        val taken = siblings.map { it.id }.toSet() + current.id
        val working = records.filter { it.id !in taken && checks[it.id]?.ms != null }
            .sortedBy { checks[it.id]?.ms ?: Long.MAX_VALUE }
        return siblings + working
    }

    /**
     * One recovery run of a service: the connection it is on now, the tries
     * spent on it and the connections already tried, so a move never comes
     * back to one of them.
     */
    class Run(start: ConnectionRecord) {
        var record: ConnectionRecord = start
            private set
        private var attempt = 0
        private val tried = mutableSetOf(start.id)
        private var offlineMs = 0L

        /** The wait before the first try. */
        fun firstDelay(): Long = delayMs(0)

        /**
         * After a failed try: the wait before the next one, on [record] (which
         * may now be another connection), or null when every one has had its
         * turn. [next] is the connection to move to when this one is spent.
         */
        fun afterFailure(online: Boolean, next: (ConnectionRecord, Set<String>) -> ConnectionRecord?): Long? {
            // No network: nothing was learned about this connection, so no try is spent on it.
            if (!online && offlineMs < OFFLINE_LIMIT_MS) {
                offlineMs += OFFLINE_RETRY_MS
                return OFFLINE_RETRY_MS
            }
            val hop = next(record, tried)
            val budget = if (hop != null) ATTEMPTS_PER_CANDIDATE else MAX_ATTEMPTS
            attempt++
            if (attempt >= budget) {
                if (hop == null) return null
                AppLog.i("olcos.recovery", "${record.displayName} keeps failing: moving to ${hop.displayName}")
                tried += hop.id
                record = hop
                attempt = 0
            }
            return delayMs(attempt)
        }
    }
}
