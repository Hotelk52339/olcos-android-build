package io.olcos.app.core

import io.olcos.app.core.RoomRotationPolicy.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The pure decisions of the room auto-update's schedule: whether a room is
 * due, what to do with a room of unknown age, and when a due room waits for
 * a quiet tunnel and when it is changed anyway. No server, no store, no
 * clock, no tunnel. The time window (any time, at night, at a chosen hour)
 * is read in the phone's local time, so the tests pin a zone.
 */
class RoomRotationTest {
    private val day = 24 * 3600L
    private val week = 168 * 3600L
    private val created = 1_000_000L

    // MARK: due

    @Test fun offAndAutomaticHaveNoSchedule() {
        for (rule in listOf(RoomRule.OFF, RoomRule.AUTOMATIC)) {
            assertEquals("$rule", Timing.NOT_DUE, RoomRotationPolicy.timing(rule, created, created + 10 * week, liveAndBusy = false))
        }
    }

    @Test fun aRoomWithNoScheduleIsLeftAloneEvenWhenItsAgeIsUnknown() {
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.OFF, null, created, liveAndBusy = false))
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.AUTOMATIC, null, created, liveAndBusy = false))
    }

    @Test fun aRoomYoungerThanItsIntervalIsLeftAlone() {
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.DAILY, created, created + day - 1, liveAndBusy = false))
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.WEEKLY, created, created + week - 1, liveAndBusy = false))
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.WEEKLY, created, created + 2 * day, liveAndBusy = false))
    }

    @Test fun aRoomAtItsIntervalIsDue() {
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, created, created + day, liveAndBusy = false))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.WEEKLY, created, created + week, liveAndBusy = false))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, created, created + 3 * day, liveAndBusy = false))
    }

    @Test fun aRoomFromTheFutureIsNotDue() {
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.DAILY, created + day, created, liveAndBusy = false))
    }

    // MARK: unknown age

    @Test fun aRoomOfUnknownAgeIsStampedQuietlyFirst() {
        assertEquals(Timing.STAMP, RoomRotationPolicy.timing(RoomRule.DAILY, null, created, liveAndBusy = false))
        assertEquals(Timing.STAMP, RoomRotationPolicy.timing(RoomRule.WEEKLY, null, created, liveAndBusy = false))
    }

    @Test fun aRoomOfUnknownAgeIsStampedEvenOnABusyTunnel() {
        assertEquals(Timing.STAMP, RoomRotationPolicy.timing(RoomRule.DAILY, null, created, liveAndBusy = true))
    }

    // MARK: the courtesy to a live, busy tunnel

    @Test fun aBusyTunnelHoldsADueLiveRoom() {
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.DAILY, created, created + day, liveAndBusy = true))
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.WEEKLY, created, created + week + 3600, liveAndBusy = true))
    }

    @Test fun aQuietTunnelHoldsNothing() {
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, created, created + day, liveAndBusy = false))
    }

    @Test fun aBusyTunnelHoldsNothingThatIsNotDue() {
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.DAILY, created, created + day - 1, liveAndBusy = true))
    }

    @Test fun aRoomIsChangedOnceItIsSixHoursOverdueWhateverTheTunnelDoes() {
        val due = created + day
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.DAILY, created, due + 6 * 3600L - 1, liveAndBusy = true))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, created, due + 6 * 3600L, liveAndBusy = true))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, created, due + 7 * 3600L, liveAndBusy = true))
    }

    @Test fun theSixHoursCountFromWhenTheRoomBecameDueNotFromItsCreation() {
        val due = created + week
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.WEEKLY, created, due + 5 * 3600L, liveAndBusy = true))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.WEEKLY, created, due + 6 * 3600L, liveAndBusy = true))
    }

    @Test fun theForceTimeIsSixHours() {
        assertEquals(6 * 3600L, RoomRotationPolicy.FORCE_AFTER_S)
    }

    // MARK: busy

    @Test fun theTunnelIsBusyFromTheThreshold() {
        assertTrue(RoomRotationPolicy.isBusy(RoomRotationPolicy.BUSY_BYTES_PER_SECOND))
        assertTrue(RoomRotationPolicy.isBusy(5_000_000.0))
        assertFalse(RoomRotationPolicy.isBusy(RoomRotationPolicy.BUSY_BYTES_PER_SECOND - 1))
        assertFalse(RoomRotationPolicy.isBusy(0.0))
    }

    @Test fun anUnreadableRateIsNotBusy() {
        assertFalse(RoomRotationPolicy.isBusy(Double.NaN))
    }

    // MARK: the finer intervals

    private val hour = 3600L

    @Test fun everySixAndTwelveHoursAndThreeDaysAreDueAtTheirInterval() {
        for ((rule, hours) in listOf(RoomRule.EVERY_6_HOURS to 6L, RoomRule.EVERY_12_HOURS to 12L, RoomRule.EVERY_3_DAYS to 72L)) {
            assertEquals("$rule", hours.toInt(), rule.hours)
            assertEquals("$rule young", Timing.NOT_DUE, RoomRotationPolicy.timing(rule, created, created + hours * hour - 1, liveAndBusy = false))
            assertEquals("$rule due", Timing.ROTATE, RoomRotationPolicy.timing(rule, created, created + hours * hour, liveAndBusy = false))
        }
    }

    // MARK: the window, in local time

    private val moscow = ZoneId.of("Europe/Moscow")
    private val utc = ZoneOffset.UTC

    /** Unix seconds of a local wall-clock time on 2026-10-21 plus [days] days. */
    private fun at(h: Int, m: Int = 0, days: Long = 0, zone: ZoneId = moscow): Long =
        LocalDate.of(2026, 10, 21).plusDays(days).atTime(h, m).atZone(zone).toEpochSecond()

    @Test fun anyTimeIsAlwaysInsideTheWindow() {
        for (h in 0..23) assertTrue("$h", RoomRotationPolicy.inWindow(RoomWindow.Anytime, at(h), moscow))
    }

    @Test fun nightIsTwoToSixLocal() {
        assertFalse(RoomRotationPolicy.inWindow(RoomWindow.Night, at(1, 59), moscow))
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Night, at(2, 0), moscow))
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Night, at(5, 59), moscow))
        assertFalse(RoomRotationPolicy.inWindow(RoomWindow.Night, at(6, 0), moscow))
        assertFalse(RoomRotationPolicy.inWindow(RoomWindow.Night, at(12, 0), moscow))
        assertFalse(RoomRotationPolicy.inWindow(RoomWindow.Night, at(23, 30), moscow))
    }

    @Test fun aChosenHourIsThatWholeHourOnly() {
        val w = RoomWindow.Hour(3)
        assertFalse(RoomRotationPolicy.inWindow(w, at(2, 59), moscow))
        assertTrue(RoomRotationPolicy.inWindow(w, at(3, 0), moscow))
        assertTrue(RoomRotationPolicy.inWindow(w, at(3, 59), moscow))
        assertFalse(RoomRotationPolicy.inWindow(w, at(4, 0), moscow))
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Hour(0), at(0, 30), moscow))
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Hour(23), at(23, 59), moscow))
    }

    @Test fun theWindowIsReadInTheZoneGiven() {
        // 23:30 UTC is 02:30 in Moscow: inside the night there, outside it in UTC.
        val instant = at(23, 30, zone = utc)
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Night, instant, moscow))
        assertFalse(RoomRotationPolicy.inWindow(RoomWindow.Night, instant, utc))
    }

    @Test fun theNightFollowsTheClockAcrossAChangeOfSummerTime() {
        val berlin = ZoneId.of("Europe/Berlin")
        fun utcAt(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 25, h, m, 0, 0, utc).toEpochSecond()
        // 2026-10-25: 03:00 CEST becomes 02:00 CET, so 02:30 happens twice; both are night, 06:00 CET is not.
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Night, utcAt(0, 30), berlin))
        assertTrue(RoomRotationPolicy.inWindow(RoomWindow.Night, utcAt(1, 30), berlin))
        assertFalse(RoomRotationPolicy.inWindow(RoomWindow.Night, utcAt(5, 0), berlin))
    }

    // MARK: a due room and its window

    @Test fun aDueRoomOutsideItsWindowWaitsAndDoesNotChange() {
        val made = at(3, 30, days = -1)
        val now = at(15, 0)
        assertEquals(Timing.WAIT_WINDOW, RoomRotationPolicy.timing(RoomRule.DAILY, made, now, liveAndBusy = false, window = RoomWindow.Night, zone = moscow))
        assertEquals(Timing.WAIT_WINDOW, RoomRotationPolicy.timing(RoomRule.DAILY, made, now, liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
    }

    @Test fun aDueRoomThatMissedItsWindowIsChangedInTheNextOne() {
        val made = at(3, 30, days = -1)
        val night = RoomWindow.Night
        // Due since 03:30 yesterday; at 10:00 it waits, at 01:59 the next night it still waits, from 02:00 it is changed.
        assertEquals(Timing.WAIT_WINDOW, RoomRotationPolicy.timing(RoomRule.EVERY_6_HOURS, made, at(10, 0), liveAndBusy = false, window = night, zone = moscow))
        assertEquals(Timing.WAIT_WINDOW, RoomRotationPolicy.timing(RoomRule.EVERY_6_HOURS, made, at(1, 59, days = 1), liveAndBusy = false, window = night, zone = moscow))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.EVERY_6_HOURS, made, at(2, 0, days = 1), liveAndBusy = false, window = night, zone = moscow))
    }

    @Test fun aDueRoomInsideItsWindowIsChangedAtOnce() {
        val made = at(3, 5, days = -1)
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(3, 10), liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(4, 0, days = -1) + day, liveAndBusy = false, window = RoomWindow.Night, zone = moscow))
    }

    @Test fun aRoomThatIsNotDueIsLeftAloneInsideAndOutsideItsWindow() {
        val made = at(3, 0)
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(3, 30), liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(15, 0), liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
    }

    @Test fun aRoomOfUnknownAgeIsStampedWhateverTheWindow() {
        assertEquals(Timing.STAMP, RoomRotationPolicy.timing(RoomRule.DAILY, null, at(15, 0), liveAndBusy = false, window = RoomWindow.Night, zone = moscow))
    }

    @Test fun aRoomWithNoScheduleIgnoresTheWindow() {
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.OFF, created, at(15, 0), liveAndBusy = false, window = RoomWindow.Night, zone = moscow))
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.AUTOMATIC, created, at(3, 0), liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
    }

    @Test fun aChosenHourCountsTheHourBeforeItsEdgeAsDueSoThatItDoesNotSlipADay() {
        // Changed at 03:50 yesterday: by 03:00 today it is 23h10 old, and the hour is today's only chance for a daily change.
        val made = at(3, 50, days = -1)
        assertEquals(RoomRotationPolicy.dueAfterSeconds(RoomRule.DAILY, RoomWindow.Hour(3)), day - hour)
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(3, 0), liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
        // Younger than that: not yet.
        assertEquals(Timing.NOT_DUE, RoomRotationPolicy.timing(RoomRule.DAILY, at(5, 0, days = -1), at(3, 0), liveAndBusy = false, window = RoomWindow.Hour(3), zone = moscow))
        // Any time and the night take the interval as it is.
        assertEquals(day, RoomRotationPolicy.dueAfterSeconds(RoomRule.DAILY, RoomWindow.Anytime))
        assertEquals(day, RoomRotationPolicy.dueAfterSeconds(RoomRule.DAILY, RoomWindow.Night))
        assertEquals(0L, RoomRotationPolicy.dueAfterSeconds(RoomRule.OFF, RoomWindow.Hour(3)))
    }

    // MARK: busy, the six hours, and the window

    @Test fun aBusyLiveRoomInsideItsWindowWaitsUntilSixHoursOverdueThenChanges() {
        // Every 6 h at night; made at 20:00, due at 02:00: the night is 4 hours, so the cap cannot be reached inside it.
        val made = at(20, 0, days = -1)
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.EVERY_6_HOURS, made, at(2, 0), liveAndBusy = true, window = RoomWindow.Night, zone = moscow))
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.EVERY_6_HOURS, made, at(5, 59), liveAndBusy = true, window = RoomWindow.Night, zone = moscow))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.EVERY_6_HOURS, made, at(2, 0), liveAndBusy = false, window = RoomWindow.Night, zone = moscow))
    }

    @Test fun theSixHourCapNeverFiresOutsideTheWindow() {
        // Made two days ago, daily: 24 h overdue at 03:00 today. Busy at 15:00 (cap long passed) it still waits for the window.
        val made = at(3, 0, days = -2)
        assertEquals(Timing.WAIT_WINDOW, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(15, 0), liveAndBusy = true, window = RoomWindow.Hour(3), zone = moscow))
        assertEquals(Timing.WAIT_WINDOW, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(2, 59, days = 1), liveAndBusy = true, window = RoomWindow.Hour(3), zone = moscow))
    }

    @Test fun aBusyRoomThatMissedItsWindowIsChangedInTheNextOneWhateverTheTunnelDoes() {
        // The cap has long passed, so the next window changes it even on a busy tunnel.
        val made = at(3, 0, days = -2)
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, made, at(3, 0, days = 1), liveAndBusy = true, window = RoomWindow.Hour(3), zone = moscow))
    }

    @Test fun theCapCountsFromTheEffectiveDueTime() {
        // A chosen hour is due an hour early (see above); the six hours count from that.
        val made = at(10, 0, days = -1)
        val due = made + RoomRotationPolicy.dueAfterSeconds(RoomRule.DAILY, RoomWindow.Anytime)
        assertEquals(Timing.POSTPONE, RoomRotationPolicy.timing(RoomRule.DAILY, made, due + 6 * hour - 1, liveAndBusy = true, window = RoomWindow.Anytime, zone = moscow))
        assertEquals(Timing.ROTATE, RoomRotationPolicy.timing(RoomRule.DAILY, made, due + 6 * hour, liveAndBusy = true, window = RoomWindow.Anytime, zone = moscow))
    }

    // MARK: how often a pass looks

    @Test fun passesAreHourlyUnlessARuleHasAWindow() {
        val none = emptyMap<String, RoomWindow>()
        assertEquals(60 * 60_000L, RoomRotationPolicy.sweepIntervalMs(mapOf("vkcalls" to RoomRule.DAILY), none))
        assertEquals(60 * 60_000L, RoomRotationPolicy.sweepIntervalMs(mapOf("vkcalls" to RoomRule.DAILY), mapOf("vkcalls" to RoomWindow.Anytime)))
        assertEquals(10 * 60_000L, RoomRotationPolicy.sweepIntervalMs(mapOf("vkcalls" to RoomRule.DAILY), mapOf("vkcalls" to RoomWindow.Night)))
        assertEquals(10 * 60_000L, RoomRotationPolicy.sweepIntervalMs(mapOf("jitsi" to RoomRule.WEEKLY), mapOf("jitsi" to RoomWindow.Hour(4))))
    }

    @Test fun aWindowOnARuleThatIsOffDoesNotSpeedPassesUp() {
        assertEquals(60 * 60_000L, RoomRotationPolicy.sweepIntervalMs(mapOf("vkcalls" to RoomRule.OFF), mapOf("vkcalls" to RoomWindow.Night)))
        assertEquals(60 * 60_000L, RoomRotationPolicy.sweepIntervalMs(mapOf("telemost" to RoomRule.AUTOMATIC), mapOf("telemost" to RoomWindow.Night)))
    }

    @Test fun aPassHasNothingToDoWhileEveryScheduledProtocolIsOutsideItsWindow() {
        val rules = mapOf("vkcalls" to RoomRule.DAILY, "jitsi" to RoomRule.EVERY_12_HOURS)
        val windows = mapOf("vkcalls" to RoomWindow.Night, "jitsi" to RoomWindow.Hour(14))
        assertFalse(RoomRotationPolicy.anyWindowOpen(rules, windows, at(10, 0), moscow))
        assertTrue(RoomRotationPolicy.anyWindowOpen(rules, windows, at(3, 0), moscow))
        assertTrue(RoomRotationPolicy.anyWindowOpen(rules, windows, at(14, 30), moscow))
        // A protocol with no window is open all day.
        assertTrue(RoomRotationPolicy.anyWindowOpen(rules, mapOf("vkcalls" to RoomWindow.Night), at(10, 0), moscow))
        // Nothing scheduled: nothing is open.
        assertFalse(RoomRotationPolicy.anyWindowOpen(mapOf("vkcalls" to RoomRule.OFF), emptyMap(), at(3, 0), moscow))
    }
}
