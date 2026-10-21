package io.olcos.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A list's `#refresh` says how often it is fetched again (the iOS reading: Ns/Nm/Nh/Nd, a bare number in seconds). */
class SubscriptionRefreshTest {
    private val minute = 60_000L
    private val now = 1_000_000_000_000L

    @Test fun theIntervalReadsTheSubMdForms() {
        assertEquals(30 * minute, SubscriptionList.refreshIntervalMs("30m"))
        assertEquals(6 * 60 * minute, SubscriptionList.refreshIntervalMs("6h"))
        assertEquals(24 * 60 * minute, SubscriptionList.refreshIntervalMs("1d"))
        assertEquals(30_000L, SubscriptionList.refreshIntervalMs("30s"))
        assertEquals(45_000L, SubscriptionList.refreshIntervalMs("45"))
        assertEquals(2 * 60 * minute, SubscriptionList.refreshIntervalMs(" 2H "))
    }

    @Test fun noIntervalOrAnUnreadableOneIsNull() {
        assertEquals(null, SubscriptionList.refreshIntervalMs(null))
        assertEquals(null, SubscriptionList.refreshIntervalMs(""))
        assertEquals(null, SubscriptionList.refreshIntervalMs("soon"))
        assertEquals(null, SubscriptionList.refreshIntervalMs("0m"))
    }

    @Test fun aListIsDueOnceItsOwnIntervalHasPassed() {
        assertFalse(SubscriptionList.isRefreshDue("10m", now - 9 * minute, now))
        assertTrue(SubscriptionList.isRefreshDue("10m", now - 10 * minute, now))
        assertFalse(SubscriptionList.isRefreshDue("6h", now - 60 * minute, now))
    }

    @Test fun aListWithoutAnIntervalIsFetchedEveryHalfHourAsBefore() {
        assertFalse(SubscriptionList.isRefreshDue(null, now - 29 * minute, now))
        assertTrue(SubscriptionList.isRefreshDue(null, now - 31 * minute, now))
    }

    @Test fun aVeryShortIntervalIsHeldToOneMinute() {
        assertFalse(SubscriptionList.isRefreshDue("5s", now - 30_000, now))
        assertTrue(SubscriptionList.isRefreshDue("5s", now - minute, now))
    }

    @Test fun aListNeverFetchedIsDue() {
        assertTrue(SubscriptionList.isRefreshDue("1d", 0, now))
    }

    @Test fun aProviderUpdateIntervalReadsAsHours() {
        val list = SubscriptionList.parse("#profile-update-interval: 12\nolcrtc://jitsi?datachannel@room-a#" + "c".repeat(64))
        assertEquals(12 * 60 * minute, SubscriptionList.refreshIntervalMs(list.refresh))
    }
}
