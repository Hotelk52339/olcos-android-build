package io.olcos.app.core

/**
 * Which lanes of a bonded session have stopped bringing anything in, as the
 * iOS app's LaneSilenceWatch. A lane whose runtime counts as running can have
 * lost its room: the core then reconnects inside it, pausing up to five
 * minutes between rounds, and its state stays "running" all along. Such a lane
 * receives nothing, while a live one gets frames, or the server's probe copies
 * every few seconds while a download runs. Fed the bond client's per-lane
 * received bytes on every look of the lane watch.
 */
class LaneSilenceWatch {
    private var lastRx = LongArray(0)
    private var grewAt = LongArray(0)
    private var totalAtGrowth = LongArray(0)

    /**
     * The lanes silent past [LIMIT_MS] while the others received at least
     * [EVIDENCE_BYTES]. A named lane's watch starts over, so it is named at
     * most once per limit.
     */
    fun silentLanes(rx: LongArray, nowMs: Long): List<Int> {
        val total = rx.sum()
        if (lastRx.size != rx.size) {
            lastRx = rx.copyOf()
            grewAt = LongArray(rx.size) { nowMs }
            totalAtGrowth = LongArray(rx.size) { total }
            return emptyList()
        }
        val silent = mutableListOf<Int>()
        for (i in rx.indices) {
            // Growth, or counters that started over (a new bond client).
            if (rx[i] != lastRx[i] || total < totalAtGrowth[i]) {
                lastRx[i] = rx[i]
                grewAt[i] = nowMs
                totalAtGrowth[i] = total
            } else if (nowMs - grewAt[i] >= LIMIT_MS && total - totalAtGrowth[i] >= EVIDENCE_BYTES) {
                silent += i
                grewAt[i] = nowMs
                totalAtGrowth[i] = total
            }
        }
        return silent
    }

    companion object {
        /** How long a lane may receive nothing while the others carry. */
        const val LIMIT_MS = 180_000L
        /** What the other lanes must have received meanwhile: proof that traffic ran. */
        const val EVIDENCE_BYTES = 256L * 1024

        /** PURE: each lane's received bytes from the bond client's `rx:tx,rx:tx,…`; null when it is not that. */
        fun receivedBytes(laneBytes: String): LongArray? {
            val parts = laneBytes.split(',').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val rx = LongArray(parts.size)
            for ((i, part) in parts.withIndex()) {
                val pair = part.split(':')
                if (pair.size != 2 || pair[1].toLongOrNull() == null) return null
                rx[i] = pair[0].toLongOrNull() ?: return null
            }
            return rx
        }
    }
}
