package io.olcos.app.core

/**
 * Keeps the shown state true to the rooms: a session whose every lane is
 * gone reads as reconnecting, not connected, while the lane watch joins
 * them again; one that stays empty for minutes is given up, so a VPN never
 * holds the device's routes with nothing behind them.
 */
object SessionWatch {
    private const val GRACE_MS = 10_000L
    private const val GIVE_UP_MS = 3 * 60_000L

    @Volatile private var downSince = 0L
    /**
     * Whether this watch put Reconnecting on the screen. A recovery shows it
     * too, and a lane that answers proves nothing about that one: only the
     * recovery's own check may say Connected again.
     */
    @Volatile private var raised = false

    fun reset() {
        downSince = 0
        raised = false
    }

    /** Takes the lane count of the live session; true when it is time to give the session up. */
    fun lanesUp(up: Int, now: Long = System.currentTimeMillis()): Boolean {
        val state = TunnelManager.state.value
        if (up > 0) {
            downSince = 0
            if (raised && state is TunnelManager.State.Reconnecting) {
                raised = false
                TunnelManager.report(TunnelManager.State.Connected)
            }
            return false
        }
        if (downSince == 0L) downSince = now
        val down = now - downSince
        if (state is TunnelManager.State.Connected && down >= GRACE_MS) {
            raised = true
            TunnelManager.report(TunnelManager.State.Reconnecting)
        }
        return down >= GIVE_UP_MS
    }
}
