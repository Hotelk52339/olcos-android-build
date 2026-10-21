package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord

/**
 * What the app does when a protocol's rooms get old. [AUTOMATIC] is
 * Telemost's: its rooms die on the service's own clock (24 hours), so the app
 * renews them ahead of it and the owner only chooses whether it does.
 * The rest are for the protocols whose rooms do not expire by themselves: a
 * room is changed once it is older than [hours], for the owner who wants that
 * now and then. [DAILY] and [WEEKLY] are what earlier versions saved and keep
 * their names and meanings; a name no version knows reads as Off.
 */
enum class RoomRule(val hours: Int) {
    OFF(0),
    AUTOMATIC(0),
    EVERY_6_HOURS(6),
    EVERY_12_HOURS(12),
    DAILY(24),
    EVERY_3_DAYS(72),
    WEEKLY(168),
    ;

    /** Whether the rule changes a room by its age ([hours]); Automatic leaves the age to the service's own clock. */
    val isScheduled: Boolean get() = hours > 0
}

/**
 * WHEN in the day a scheduled change may happen, in the phone's local time:
 * any time (what earlier versions did), at night (02:00-06:00) or within a
 * chosen hour. A due room that misses its window waits for the next one.
 */
sealed class RoomWindow {
    data object Anytime : RoomWindow()
    data object Night : RoomWindow()
    data class Hour(val hour: Int) : RoomWindow()

    /** Whether the local [hourOfDay] (0-23) is inside the window. */
    fun contains(hourOfDay: Int): Boolean = when (this) {
        Anytime -> true
        Night -> hourOfDay in NIGHT_START until NIGHT_END
        is Hour -> hourOfDay == hour
    }

    /** `ANYTIME`, `NIGHT` or `HOUR:3`: what the settings keep. */
    fun encode(): String = when (this) {
        Anytime -> "ANYTIME"
        Night -> "NIGHT"
        is Hour -> "HOUR:$hour"
    }

    companion object {
        const val NIGHT_START = 2
        const val NIGHT_END = 6

        /** A chosen hour held inside the day. */
        fun hour(h: Int): Hour = Hour(h.coerceIn(0, 23))

        /** A kept window back; nothing kept, or anything no version writes, is any time (what the rule did before windows). */
        fun parse(text: String?): RoomWindow = when {
            text == "NIGHT" -> Night
            text != null && text.startsWith("HOUR:") ->
                text.removePrefix("HOUR:").toIntOrNull()?.takeIf { it in 0..23 }?.let { Hour(it) } ?: Anytime
            else -> Anytime
        }
    }
}

/**
 * The room auto-update's rule per protocol, as pure decisions: which options
 * a protocol has, what it starts as and which connections a rule reaches.
 * Settings keeps the owner's choice (AppSettings); TelemostRenewal and
 * RoomRotation act on it.
 */
object RoomRules {
    /** The protocols Settings lists, in the order it lists them. */
    val PROTOCOLS = listOf("telemost", "vkcalls", "salutejazz", "jitsi", "mtslink")

    private val SCHEDULE = listOf(
        RoomRule.OFF, RoomRule.EVERY_6_HOURS, RoomRule.EVERY_12_HOURS, RoomRule.DAILY, RoomRule.EVERY_3_DAYS, RoomRule.WEEKLY,
    )

    /**
     * The options a protocol really has. Telemost renews by its own clock or
     * not at all; VK Calls, SaluteJazz and Jitsi by a schedule or not at all;
     * MTS Link by a schedule once the app makes its meetings itself
     * (RoomServices.MTSLink.canCreate), and never before. Any other protocol
     * (an old saved WB Stream room) has no schedule.
     */
    fun allowed(
        carrier: String,
        mtsCreatable: Boolean = RoomServices.MTSLink.canCreate,
    ): List<RoomRule> = when (carrier) {
        "telemost" -> listOf(RoomRule.OFF, RoomRule.AUTOMATIC)
        "vkcalls", "salutejazz", "jitsi" -> SCHEDULE
        "mtslink" -> if (mtsCreatable) SCHEDULE else listOf(RoomRule.OFF)
        else -> listOf(RoomRule.OFF)
    }

    /** What a protocol starts as: Telemost keeps renewing as it always did, nothing else changes by itself. */
    fun default(carrier: String): RoomRule = if (carrier == "telemost") RoomRule.AUTOMATIC else RoomRule.OFF

    /** The rule in force for a stored choice: the choice when the protocol offers it, else the protocol's default. */
    fun effective(
        carrier: String,
        stored: RoomRule?,
        mtsCreatable: Boolean = RoomServices.MTSLink.canCreate,
    ): RoomRule =
        if (stored != null && stored in allowed(carrier, mtsCreatable)) stored else default(carrier)

    /** A stored name back to its rule; null for anything else. */
    fun parse(name: String?): RoomRule? = RoomRule.entries.firstOrNull { it.name == name }

    /** The rule of a protocol among the kept ones, its default when none is kept. */
    fun ruleOf(rules: Map<String, RoomRule>, carrier: String): RoomRule = rules[carrier] ?: default(carrier)

    /** The window of a protocol among the kept ones; any time when none is kept. */
    fun windowOf(windows: Map<String, RoomWindow>, carrier: String): RoomWindow = windows[carrier] ?: RoomWindow.Anytime

    /** Only a rule on a schedule has a window to choose; Telemost's Automatic keeps its own clock. */
    fun hasWindow(rule: RoomRule): Boolean = rule.isScheduled

    /** Whether any protocol changes its rooms by age: when none does, there is nothing to look at. */
    fun anyScheduled(rules: Map<String, RoomRule>): Boolean = PROTOCOLS.any { ruleOf(rules, it).isScheduled }

    /** The skipped connections after the owner switches [id]. */
    fun withSkip(skipped: Set<String>, id: String, skip: Boolean): Set<String> = if (skip) skipped + id else skipped - id

    /**
     * The connections the auto-update can reach, skipped or not: plain
     * olcrtc connections of a managed server on a protocol whose rule is on.
     * A whole-server connection is left out: its rooms belong to the
     * protocol connections it was made from, and those are what change.
     * [bundleIDs] are the servers' whole-server records; [managed] says
     * whether this phone administers the connection's server.
     */
    fun covered(
        records: List<ConnectionRecord>,
        rules: Map<String, RoomRule>,
        bundleIDs: Set<String>,
        managed: (ConnectionRecord) -> Boolean,
    ): List<ConnectionRecord> = records.filter { record ->
        val link = record.olcrtc ?: return@filter false
        !link.isBundle && record.id !in bundleIDs && ruleOf(rules, link.carrier) != RoomRule.OFF && managed(record)
    }

    /** Of the covered connections, the ones a pass may change by age: on a schedule, and not skipped. */
    fun scheduled(covered: List<ConnectionRecord>, rules: Map<String, RoomRule>, skipped: Set<String>): List<ConnectionRecord> =
        covered.filter { record ->
            val link = record.olcrtc ?: return@filter false
            record.id !in skipped && ruleOf(rules, link.carrier).isScheduled
        }

    /**
     * Whether a connection the owner skipped rides this room, as its own
     * room, an extra one or a joined lane. A room is one thing on the
     * server (an ICE hint on the phone's link makes no other one): changing
     * it changes every connection that rides it, so a skipped connection
     * keeps its room whoever else rides it.
     */
    fun skipsRoom(records: List<ConnectionRecord>, skipped: Set<String>, carrier: String, room: String): Boolean =
        skipped.isNotEmpty() && records.any { record ->
            record.id in skipped && record.olcrtc?.lanes?.any { it.carrier == carrier && RoomServices.sameRoom(carrier, it.roomID, room) } == true
        }
}
