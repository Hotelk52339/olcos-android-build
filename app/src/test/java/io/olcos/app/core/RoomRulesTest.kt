package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import io.olcos.app.model.XrayLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room auto-update's rule per protocol: which options each protocol
 * has, what it starts as, how a kept choice is read back, and which
 * connections a rule reaches. All pure: no settings, no server, no clock.
 */
class RoomRulesTest {
    private fun record(
        id: String,
        carrier: String,
        room: String = "room-$id",
        extra: List<String> = emptyList(),
        joined: List<Lane> = emptyList(),
    ) = ConnectionRecord(
        id = id,
        name = "Connection $id",
        details = Details.Olcrtc(OlcrtcLink(carrier, "datachannel", room, "0".repeat(64), extraRooms = extra, with = joined)),
    )

    private fun ids(records: List<ConnectionRecord>) = records.map { it.id }

    // MARK: the protocols

    @Test fun settingsListsEveryProtocolInOrder() {
        assertEquals(listOf("telemost", "vkcalls", "salutejazz", "jitsi", "mtslink"), RoomRules.PROTOCOLS)
    }

    // MARK: the options a protocol has

    @Test fun telemostHasOffAndAutomaticOnly() {
        assertEquals(listOf(RoomRule.OFF, RoomRule.AUTOMATIC), RoomRules.allowed("telemost"))
    }

    private val schedule = listOf(RoomRule.OFF, RoomRule.EVERY_6_HOURS, RoomRule.EVERY_12_HOURS, RoomRule.DAILY, RoomRule.EVERY_3_DAYS, RoomRule.WEEKLY)

    @Test fun vkSaluteJazzAndJitsiHaveOffAndEveryIntervalFromSixHoursToAWeek() {
        for (carrier in listOf("vkcalls", "salutejazz", "jitsi")) {
            assertEquals(carrier, schedule, RoomRules.allowed(carrier))
        }
    }
    @Test fun anOldWbStreamRoomHasOnlyOff() {
        assertEquals(listOf(RoomRule.OFF), RoomRules.allowed("wbstream"))
    }

    @Test fun nobodyOffersAutomaticButTelemost() {
        for (carrier in RoomRules.PROTOCOLS - "telemost") {
            assertFalse(carrier, RoomRule.AUTOMATIC in RoomRules.allowed(carrier))
        }
    }

    @Test fun anUnknownProtocolHasOnlyOff() {
        assertEquals(listOf(RoomRule.OFF), RoomRules.allowed("unknown"))
    }

    // MARK: what a protocol starts as

    @Test fun telemostStartsAutomaticAsItAlwaysRenewed() {
        assertEquals(RoomRule.AUTOMATIC, RoomRules.default("telemost"))
    }

    @Test fun everyOtherProtocolStartsOff() {
        for (carrier in listOf("vkcalls", "salutejazz", "jitsi", "mtslink")) {
            assertEquals(carrier, RoomRule.OFF, RoomRules.default(carrier))
        }
    }

    @Test fun everyDefaultIsOneOfTheProtocolsOwnOptions() {
        for (carrier in RoomRules.PROTOCOLS) {
            assertTrue(carrier, RoomRules.default(carrier) in RoomRules.allowed(carrier))
        }
    }

    // MARK: reading a kept choice

    @Test fun aChoiceTheProtocolOffersIsKept() {
        assertEquals(RoomRule.OFF, RoomRules.effective("telemost", RoomRule.OFF))
        assertEquals(RoomRule.AUTOMATIC, RoomRules.effective("telemost", RoomRule.AUTOMATIC))
        assertEquals(RoomRule.WEEKLY, RoomRules.effective("vkcalls", RoomRule.WEEKLY))
        assertEquals(RoomRule.DAILY, RoomRules.effective("jitsi", RoomRule.DAILY))
        assertEquals(RoomRule.DAILY, RoomRules.effective("salutejazz", RoomRule.DAILY))
    }

    @Test fun noChoiceReadsAsTheDefault() {
        assertEquals(RoomRule.AUTOMATIC, RoomRules.effective("telemost", null))
        assertEquals(RoomRule.OFF, RoomRules.effective("vkcalls", null))
    }

    @Test fun aChoiceTheProtocolDoesNotOfferReadsAsTheDefault() {
        assertEquals(RoomRule.AUTOMATIC, RoomRules.effective("telemost", RoomRule.WEEKLY))
        assertEquals(RoomRule.OFF, RoomRules.effective("vkcalls", RoomRule.AUTOMATIC))
        assertEquals(RoomRule.OFF, RoomRules.effective("jitsi", RoomRule.AUTOMATIC))
    }

    @Test fun anOldWbStreamChoiceIsForcedOffWhateverIsKept() {
        for (stored in RoomRule.entries + null) {
            assertEquals("kept $stored", RoomRule.OFF, RoomRules.effective("wbstream", stored))
        }
    }

    @Test fun telemostKeepsOnlyItsOwnOffAndAutomatic() {
        // Telemost's renewal is unchanged: none of the finer intervals is on offer, and a stored one reads as its default.
        for (rule in schedule - RoomRule.OFF) {
            assertFalse("$rule", rule in RoomRules.allowed("telemost"))
            assertEquals("$rule", RoomRule.AUTOMATIC, RoomRules.effective("telemost", rule))
        }
    }

    @Test fun theNewIntervalsAreKeptWhereTheProtocolOffersThem() {
        assertEquals(RoomRule.EVERY_6_HOURS, RoomRules.effective("vkcalls", RoomRule.EVERY_6_HOURS))
        assertEquals(RoomRule.EVERY_12_HOURS, RoomRules.effective("jitsi", RoomRule.EVERY_12_HOURS))
        assertEquals(RoomRule.EVERY_3_DAYS, RoomRules.effective("salutejazz", RoomRule.EVERY_3_DAYS))
    }

    @Test fun savedDailyAndWeeklyKeepTheirNamesAndMeanings() {
        // What an earlier version wrote still reads, and still means a day and a week.
        assertEquals(RoomRule.DAILY, RoomRules.parse("DAILY"))
        assertEquals(RoomRule.WEEKLY, RoomRules.parse("WEEKLY"))
        assertEquals(RoomRule.OFF, RoomRules.parse("OFF"))
        assertEquals(RoomRule.AUTOMATIC, RoomRules.parse("AUTOMATIC"))
        assertEquals(24, RoomRule.DAILY.hours)
        assertEquals(168, RoomRule.WEEKLY.hours)
    }

    @Test fun aValueNoVersionKnowsReadsAsOff() {
        for (stored in listOf("EVERY_WEEK", "MONTHLY", "6H", "daily", "")) {
            assertNull(stored, RoomRules.parse(stored))
            assertEquals(stored, RoomRule.OFF, RoomRules.effective("vkcalls", RoomRules.parse(stored)))
        }
    }

    @Test fun aStoredNameComesBackAsItsRule() {
        for (rule in RoomRule.entries) assertEquals(rule, RoomRules.parse(rule.name))
        assertNull(RoomRules.parse("HOURLY"))
        assertNull(RoomRules.parse("daily"))
        assertNull(RoomRules.parse(""))
        assertNull(RoomRules.parse(null))
    }

    @Test fun aRuleNotKeptReadsAsTheDefault() {
        val kept = mapOf("vkcalls" to RoomRule.DAILY)
        assertEquals(RoomRule.DAILY, RoomRules.ruleOf(kept, "vkcalls"))
        assertEquals(RoomRule.AUTOMATIC, RoomRules.ruleOf(kept, "telemost"))
        assertEquals(RoomRule.OFF, RoomRules.ruleOf(kept, "jitsi"))
    }

    // MARK: the schedule

    @Test fun everyIntervalButOffAndAutomaticIsOnAScheduleOfHours() {
        assertEquals(0, RoomRule.OFF.hours)
        assertEquals(0, RoomRule.AUTOMATIC.hours)
        assertEquals(listOf(6, 12, 24, 72, 168), listOf(RoomRule.EVERY_6_HOURS, RoomRule.EVERY_12_HOURS, RoomRule.DAILY, RoomRule.EVERY_3_DAYS, RoomRule.WEEKLY).map { it.hours })
        assertEquals(listOf(false, false, true, true, true, true, true), RoomRule.entries.map { it.isScheduled })
    }

    @Test fun nothingIsScheduledByDefault() {
        assertFalse(RoomRules.anyScheduled(emptyMap()))
        assertFalse(RoomRules.anyScheduled(mapOf("telemost" to RoomRule.AUTOMATIC)))
    }

    @Test fun oneScheduledProtocolIsEnough() {
        assertTrue(RoomRules.anyScheduled(mapOf("jitsi" to RoomRule.WEEKLY)))
    }

    // MARK: the window

    @Test fun aWindowIsKeptAndReadBackAsItWasWritten() {
        for (w in listOf(RoomWindow.Anytime, RoomWindow.Night, RoomWindow.Hour(0), RoomWindow.Hour(3), RoomWindow.Hour(23))) {
            assertEquals("$w", w, RoomWindow.parse(w.encode()))
        }
        assertEquals("HOUR:3", RoomWindow.Hour(3).encode())
    }

    @Test fun noWindowOrAnUnknownOneReadsAsAnyTime() {
        // What an earlier version saved has no window: the rule keeps meaning what it meant.
        for (stored in listOf(null, "", "DAY", "HOUR", "HOUR:", "HOUR:x", "HOUR:24", "HOUR:-1", "night")) {
            assertEquals("$stored", RoomWindow.Anytime, RoomWindow.parse(stored))
        }
    }

    @Test fun aChosenHourStaysInsideTheDay() {
        assertEquals(RoomWindow.Hour(0), RoomWindow.hour(-5))
        assertEquals(RoomWindow.Hour(23), RoomWindow.hour(40))
        assertEquals(RoomWindow.Hour(7), RoomWindow.hour(7))
    }

    @Test fun aWindowNotKeptReadsAsAnyTime() {
        val kept = mapOf("vkcalls" to RoomWindow.Night)
        assertEquals(RoomWindow.Night, RoomRules.windowOf(kept, "vkcalls"))
        assertEquals(RoomWindow.Anytime, RoomRules.windowOf(kept, "jitsi"))
    }

    @Test fun onlyAScheduledRuleHasAWindow() {
        assertFalse(RoomRules.hasWindow(RoomRule.OFF))
        assertFalse(RoomRules.hasWindow(RoomRule.AUTOMATIC))
        for (rule in schedule - RoomRule.OFF) assertTrue("$rule", RoomRules.hasWindow(rule))
    }

    // MARK: the exceptions

    @Test fun skippingAConnectionAddsItOnce() {
        val once = RoomRules.withSkip(emptySet(), "a", true)
        assertEquals(setOf("a"), once)
        assertEquals(setOf("a"), RoomRules.withSkip(once, "a", true))
        assertEquals(setOf("a", "b"), RoomRules.withSkip(once, "b", true))
    }

    @Test fun unskippingTakesItBack() {
        assertEquals(setOf("b"), RoomRules.withSkip(setOf("a", "b"), "a", false))
        assertEquals(emptySet<String>(), RoomRules.withSkip(setOf("a"), "a", false))
    }

    @Test fun unskippingAStrangerChangesNothing() {
        assertEquals(setOf("a"), RoomRules.withSkip(setOf("a"), "z", false))
    }

    @Test fun theSetYouPassInIsLeftAlone() {
        val before = setOf("a")
        RoomRules.withSkip(before, "b", true)
        assertEquals(setOf("a"), before)
    }

    // MARK: which connections a rule reaches

    private val rules = mapOf(
        "vkcalls" to RoomRule.DAILY,
        "jitsi" to RoomRule.OFF,
        "salutejazz" to RoomRule.WEEKLY,
    )

    @Test fun coveredIsTheManagedConnectionsWhoseProtocolRuleIsOn() {
        val records = listOf(
            record("vk", "vkcalls"),
            record("jitsi", "jitsi"),
            record("tm", "telemost"),
            record("wb", "wbstream"),
            record("sj", "salutejazz"),
        )
        // Telemost is on by default (Automatic); Jitsi was switched off; an old WB Stream connection is never reached.
        assertEquals(listOf("vk", "tm", "sj"), ids(RoomRules.covered(records, rules, emptySet()) { true }))
    }

    @Test fun coveredLeavesOutWhatThisPhoneDoesNotManage() {
        val records = listOf(record("mine", "vkcalls"), record("theirs", "vkcalls"))
        assertEquals(listOf("mine"), ids(RoomRules.covered(records, rules, emptySet()) { it.id == "mine" }))
    }

    @Test fun coveredLeavesOutWholeServerConnections() {
        val joined = record("bundle", "vkcalls", joined = listOf(Lane("jitsi", "datachannel", "https://meet.example/r")))
        // A whole-server connection of one protocol with several rooms has no joined lane, so the server's record names it.
        val severalRooms = record("rooms", "vkcalls", extra = listOf("https://vk.ru/call/join/b"))
        val plain = record("plain", "vkcalls")
        val covered = RoomRules.covered(listOf(joined, severalRooms, plain), rules, setOf("rooms")) { true }
        assertEquals(listOf("plain"), ids(covered))
    }

    @Test fun coveredHoldsOnlyOlcrtcConnections() {
        val xray = ConnectionRecord(
            id = "xray", name = "Xray",
            details = Details.Xray(XrayLink(host = "example.org", uuid = "u", sni = "s", publicKey = "k")),
        )
        assertEquals(emptyList<String>(), ids(RoomRules.covered(listOf(xray), rules, emptySet()) { true }))
    }

    @Test fun aScheduleReachesTheScheduledAndNotSkippedOnly() {
        val covered = listOf(record("vk", "vkcalls"), record("tm", "telemost"), record("sj", "salutejazz"))
        // Telemost renews by its own clock, so the schedule never takes it.
        assertEquals(listOf("vk", "sj"), ids(RoomRules.scheduled(covered, rules, emptySet())))
        assertEquals(listOf("sj"), ids(RoomRules.scheduled(covered, rules, setOf("vk"))))
        assertEquals(emptyList<String>(), ids(RoomRules.scheduled(covered, rules, setOf("vk", "sj"))))
    }

    @Test fun aSkippedConnectionKeepsEveryRoomItRides() {
        val own = record("own", "vkcalls", room = "r1", extra = listOf("r2"))
        val bundle = record("all", "vkcalls", room = "r1", joined = listOf(Lane("jitsi", "datachannel", "https://meet.example/j")))
        val records = listOf(own, bundle)
        // Its own room and its extra room.
        assertTrue(RoomRules.skipsRoom(records, setOf("own"), "vkcalls", "r1"))
        assertTrue(RoomRules.skipsRoom(records, setOf("own"), "vkcalls", "r2"))
        // A room it does not ride, or another protocol's room under the same id, is free.
        assertFalse(RoomRules.skipsRoom(records, setOf("own"), "vkcalls", "r3"))
        assertFalse(RoomRules.skipsRoom(records, setOf("own"), "jitsi", "r1"))
        // A joined lane of a skipped whole-server connection is held too.
        assertTrue(RoomRules.skipsRoom(records, setOf("all"), "jitsi", "https://meet.example/j"))
    }

    @Test fun nothingSkippedHoldsNoRoom() {
        assertFalse(RoomRules.skipsRoom(listOf(record("a", "vkcalls", room = "r1")), emptySet(), "vkcalls", "r1"))
    }

    @Test fun aRoomOnlyOthersRideIsNotHeld() {
        val records = listOf(record("a", "vkcalls", room = "r1"), record("b", "vkcalls", room = "r2"))
        assertFalse(RoomRules.skipsRoom(records, setOf("a"), "vkcalls", "r2"))
    }

    // MARK: MTS Link

    private val meeting = "https://my.mts-link.ru/j/100200300/400500600700"

    @Test fun mtsLinkIsListedAndHeldOffWhileItsMeetingsCannotBeMade() {
        assertTrue("mtslink" in RoomRules.PROTOCOLS)
        assertEquals(listOf(RoomRule.OFF), RoomRules.allowed("mtslink", mtsCreatable = false))
        for (stored in RoomRule.entries + null) {
            assertEquals("kept $stored", RoomRule.OFF, RoomRules.effective("mtslink", stored, mtsCreatable = false))
        }
    }

    @Test fun mtsLinkTakesAScheduleOnceTheAppMakesItsMeetings() {
        assertEquals(schedule, RoomRules.allowed("mtslink", mtsCreatable = true))
        assertEquals(RoomRule.WEEKLY, RoomRules.effective("mtslink", RoomRule.WEEKLY, mtsCreatable = true))
        // A choice the protocol never offers reads as its default.
        assertEquals(RoomRule.OFF, RoomRules.effective("mtslink", RoomRule.AUTOMATIC, mtsCreatable = true))
    }

    @Test fun anMtsLinkConnectionIsNeverCovered() {
        // The app makes no MTS Link room, so the auto-update has nothing to change it with.
        val records = listOf(record("mts", "mtslink", room = meeting), record("vk", "vkcalls"))
        assertEquals(listOf("vk"), ids(RoomRules.covered(records, rules, emptySet()) { true }))
        assertEquals(emptyList<String>(), ids(RoomRules.covered(records, emptyMap(), emptySet()) { true }))
    }

    @Test fun aSkippedMtsLinkConnectionHoldsItsRoomWithOrWithoutTheHint() {
        val hinted = listOf(record("mts", "mtslink", room = "$meeting?ice=tcp"))
        // The server names the room plain; the phone's link carries the hint.
        assertTrue(RoomRules.skipsRoom(hinted, setOf("mts"), "mtslink", meeting))
        assertTrue(RoomRules.skipsRoom(listOf(record("mts", "mtslink", room = meeting)), setOf("mts"), "mtslink", "$meeting?ice=both"))
        assertFalse(RoomRules.skipsRoom(hinted, setOf("mts"), "mtslink", "https://my.mts-link.ru/j/100200300/400500600701"))
    }
}
