package io.olcos.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The once-a-day gate of the silent account check, as a pure decision. */
class AccountCheckTest {
    @Test fun neverRanBeforeIsDue() {
        assertTrue(AccountCheck.due(lastRunMs = 0L, nowMs = 1L))
    }

    @Test fun aDayLaterIsDueAgain() {
        assertTrue(AccountCheck.due(lastRunMs = 1_000_000L, nowMs = 1_000_000L + 24 * 3600_000L))
    }

    @Test fun soonerThanADayIsNot() {
        assertFalse(AccountCheck.due(lastRunMs = 1_000_000L, nowMs = 1_000_000L + 24 * 3600_000L - 1))
    }
}
