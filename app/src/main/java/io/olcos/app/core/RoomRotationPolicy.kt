package io.olcos.app.core

import java.time.Instant
import java.time.ZoneId

/**
 * WHEN a room on a schedule is changed, as pure decisions; RoomRotation does
 * the changing. Nothing here reads a clock, a setting or the tunnel: the
 * caller brings the time (and the zone the window is read in), the rule, the
 * window, the room's age and whether the tunnel is busy.
 */
object RoomRotationPolicy {
    /**
     * How far past its interval a room may wait for a quiet tunnel. After
     * this it is changed whatever the tunnel is doing, so a phone that is
     * always busy still gets its fresh rooms.
     */
    const val FORCE_AFTER_S = 6 * 3600L

    /** Bytes per second, in and out together, from which the tunnel counts as busy: a stream, a download, a call. */
    const val BUSY_BYTES_PER_SECOND = 64.0 * 1024

    /** How often a pass looks when no rule has a window: the hour. */
    const val SWEEP_MS = 60 * 60_000L

    /** How often a pass looks when a rule has a window, so a window of one hour is never stepped over. */
    const val WINDOWED_SWEEP_MS = 10 * 60_000L

    /** A chosen hour counts a room as due this long before its interval is up, so a daily change at that hour does not slip a day. */
    private const val HOUR_GRACE_S = 3600L

    /** What a pass does about one room. */
    enum class Timing {
        /** The rule has no schedule, or the room is younger than its interval. */
        NOT_DUE,

        /** The room's age is unknown: stamp it quietly, so its interval counts from now instead of from a guess. */
        STAMP,

        /** Due, but it is the live connection and the tunnel is busy: a later pass tries again. */
        POSTPONE,

        /** Due, but now is outside the rule's window: nothing is changed until the next one, however overdue the room is. */
        WAIT_WINDOW,

        /** Make a fresh room. */
        ROTATE,
    }

    /**
     * PURE: how old a room must be to be due. The rule's interval, less an
     * hour when the window is one chosen hour (see [HOUR_GRACE_S]); 0 for a
     * rule with no schedule.
     */
    fun dueAfterSeconds(rule: RoomRule, window: RoomWindow): Long {
        if (!rule.isScheduled) return 0L
        val interval = rule.hours * 3600L
        return if (window is RoomWindow.Hour) maxOf(interval - HOUR_GRACE_S, 0L) else interval
    }

    /** PURE: whether [now] (unix seconds) falls in [window] by the clock of [zone]. */
    fun inWindow(window: RoomWindow, now: Long, zone: ZoneId): Boolean =
        window.contains(Instant.ofEpochSecond(now).atZone(zone).hour)

    /**
     * PURE: what to do with a room created at [createdAt] (unix seconds, null
     * when unknown) at [now]. A room is due once it is older than the rule's
     * interval ([dueAfterSeconds]). A due room waits for its [window]: outside
     * it nothing is changed, and that holds for the busy cap below too. Inside
     * it, a room the live session joins waits while the tunnel is busy
     * ([liveAndBusy]), but only until it is [FORCE_AFTER_S] overdue, so a
     * room that missed its window and is long overdue is changed at the next
     * window whatever the tunnel is doing.
     */
    fun timing(
        rule: RoomRule,
        createdAt: Long?,
        now: Long,
        liveAndBusy: Boolean,
        window: RoomWindow = RoomWindow.Anytime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Timing {
        if (!rule.isScheduled) return Timing.NOT_DUE
        val created = createdAt ?: return Timing.STAMP
        val overdue = now - created - dueAfterSeconds(rule, window)
        if (overdue < 0) return Timing.NOT_DUE
        if (!inWindow(window, now, zone)) return Timing.WAIT_WINDOW
        return if (liveAndBusy && overdue < FORCE_AFTER_S) Timing.POSTPONE else Timing.ROTATE
    }

    /** PURE: whether any protocol on a schedule is inside its window now: when none is, a pass has nothing to do and asks no server. */
    fun anyWindowOpen(rules: Map<String, RoomRule>, windows: Map<String, RoomWindow>, now: Long, zone: ZoneId): Boolean =
        rules.any { (carrier, rule) -> rule.isScheduled && inWindow(RoomRules.windowOf(windows, carrier), now, zone) }

    /** PURE: the wait between passes: short while a scheduled rule has a window, the hour otherwise. */
    fun sweepIntervalMs(rules: Map<String, RoomRule>, windows: Map<String, RoomWindow>): Long =
        if (rules.any { (carrier, rule) -> rule.isScheduled && RoomRules.windowOf(windows, carrier) != RoomWindow.Anytime }) WINDOWED_SWEEP_MS else SWEEP_MS
    /** PURE: whether a measured rate (bytes per second, in and out together) makes the tunnel busy. */
    fun isBusy(bytesPerSecond: Double): Boolean = bytesPerSecond >= BUSY_BYTES_PER_SECOND
}
