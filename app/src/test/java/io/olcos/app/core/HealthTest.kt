package io.olcos.app.core

import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.Details
import io.olcos.app.model.Lane
import io.olcos.app.model.OlcrtcLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One answer to "does it work": the server's headline, its protocols, its
 * rooms and the connection rows all read the same evidence — the checks, and
 * the live tunnel with the rooms it carries.
 */
class HealthTest {
    private val now = 10_000_000L
    private val key = "a".repeat(64)

    private fun room(provider: String, id: String, up: Boolean = true, primary: Boolean = false) =
        ServerStatus.Room(if (primary) "server.yaml" else "server-$provider.yaml", provider, "datachannel", id, "olcrtc-$provider",
            if (up) "Up 3 days" else "Exited (0) 2 hours ago", primary)

    private fun link(vararg rooms: Pair<String, String>): OlcrtcLink {
        val (first, rest) = rooms.first() to rooms.drop(1)
        return OlcrtcLink(first.first, "datachannel", first.second, key, with = rest.map { Lane(it.first, "datachannel", it.second) })
    }

    private fun record(id: String, l: OlcrtcLink) = ConnectionRecord(id = id, name = id, details = Details.Olcrtc(l))

    private fun check(l: OlcrtcLink, at: Long, vararg answers: Boolean) = Health.Check(
        ms = if (answers.any { it }) 120L else null, lanesOK = answers.count { it }, lanesTotal = answers.size,
        error = if (answers.none { it }) "no answer" else null, at = at,
        lanes = l.lanes.mapIndexed { i, lane -> Health.LaneReading(lane.carrier, lane.roomID, if (answers[i]) 120L else null, if (answers[i]) null else "no answer") },
    )

    /** A check of each one-room record, so each protocol has the evidence its answer gives. */
    private fun proofsOf(answers: List<Triple<String, String, Pair<Boolean, Long>>>): Map<Health.Room, Health.Proof> {
        val records = answers.mapIndexed { i, (carrier, id, _) -> record("r$i", link(carrier to id)) }
        val checks = records.mapIndexed { i, r -> r.id to check(r.olcrtc!!, answers[i].third.second, answers[i].third.first) }.toMap()
        return Health.proofs(records, checks)
    }

    // MARK: The server's headline

    @Test
    fun fiveVerifiedAndOneFailedReadsWorkingAndNamesTheFailedOne() {
        val rooms = listOf(
            room("jitsi", "j1", primary = true), room("vkcalls", "v1"), room("salutejazz", "s1"),
            room("mtslink", "m1"), room("alpha", "a1"), room("telemost", "t1"),
        )
        val proofs = proofsOf(listOf(
            Triple("jitsi", "j1", true to now - 60_000), Triple("vkcalls", "v1", true to now - 60_000),
            Triple("salutejazz", "s1", true to now - 60_000), Triple("mtslink", "m1", true to now - 60_000),
            Triple("alpha", "a1", true to now - 60_000), Triple("telemost", "t1", false to now - 60_000),
        ))
        val headline = Health.serverHeadline(rooms, proofs, emptySet(), now)
        assertEquals(Health.State.WORKING, headline.state)
        assertEquals(listOf("telemost"), headline.failed)
    }

    @Test
    fun verifiedNeverCheckedAndFadedProtocolsReadWorkingWithNoFailureLine() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("vkcalls", "v1"), room("salutejazz", "s1"))
        val proofs = proofsOf(listOf(
            Triple("jitsi", "j1", true to now - 60_000),
            Triple("salutejazz", "s1", true to now - Health.FADE_MS - 1),
        ))
        val headline = Health.serverHeadline(rooms, proofs, emptySet(), now)
        assertEquals(Health.State.WORKING, headline.state)
        assertTrue(headline.failed.isEmpty())
    }

    @Test
    fun aServerWhoseOnlyCheckedProtocolsAllFailedReadsNotWorking() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("telemost", "t1"), room("vkcalls", "v1"))
        val proofs = proofsOf(listOf(
            Triple("jitsi", "j1", false to now - 60_000), Triple("telemost", "t1", false to now - 60_000),
        ))
        val headline = Health.serverHeadline(rooms, proofs, emptySet(), now)
        assertEquals(Health.State.FAILED, headline.state)
    }

    @Test
    fun nothingCheckedReadsNotChecked() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("vkcalls", "v1"))
        val headline = Health.serverHeadline(rooms, emptyMap(), emptySet(), now)
        assertEquals(Health.State.UNKNOWN, headline.state)
        assertTrue(headline.failed.isEmpty())
    }

    @Test
    fun aProtocolCarryingTheLiveTunnelCountsAsWorkingWhateverItsLastCheckSaid() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("telemost", "t1"))
        val proofs = proofsOf(listOf(Triple("jitsi", "j1", false to now - 60_000), Triple("telemost", "t1", false to now - 60_000)))
        val live = setOf(Health.room("telemost", "t1"))
        val headline = Health.serverHeadline(rooms, proofs, live, now)
        assertEquals(Health.State.WORKING, headline.state)
        // The live protocol is not listed as failed; the one that did not answer still is.
        assertEquals(listOf("jitsi"), headline.failed)
    }

    @Test
    fun theLiveTunnelAloneIsEnoughWhenNothingWasEverChecked() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("vkcalls", "v1"))
        val headline = Health.serverHeadline(rooms, emptyMap(), setOf(Health.room("vkcalls", "v1")), now)
        assertEquals(Health.State.WORKING, headline.state)
        assertTrue(headline.failed.isEmpty())
    }

    @Test
    fun aStoppedRoomIsNotWorkingEvenOnAGoodCheckAndIsNotAFailureEither() {
        val rooms = listOf(room("jitsi", "j1", up = false, primary = true))
        val proofs = proofsOf(listOf(Triple("jitsi", "j1", true to now - 60_000)))
        val headline = Health.serverHeadline(rooms, proofs, emptySet(), now)
        assertEquals(Health.State.STOPPED, headline.state)
        assertTrue(headline.failed.isEmpty())
    }

    // MARK: A stopped room

    @Test
    fun aStoppedRoomReadsStoppedWhateverItsLastCheckSaid() {
        val r = room("telemost", "t1", up = false)
        val failed = proofsOf(listOf(Triple("telemost", "t1", false to now - 60_000)))
        assertEquals(Health.State.STOPPED, Health.serverRoomState(r, failed, emptySet(), now))
        assertEquals(Health.State.STOPPED, Health.serverRoomState(r, emptyMap(), emptySet(), now))
    }

    @Test
    fun aStoppedProtocolIsNeverNamedAsNotAnswering() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("telemost", "t1", up = false))
        val proofs = proofsOf(listOf(Triple("jitsi", "j1", true to now - 60_000), Triple("telemost", "t1", false to now - 60_000)))
        val headline = Health.serverHeadline(rooms, proofs, emptySet(), now)
        assertEquals(Health.State.WORKING, headline.state)
        assertTrue(headline.failed.isEmpty())
        assertEquals(Health.State.STOPPED, Health.serverProtocols(rooms, proofs, emptySet(), now)["telemost"])
    }

    @Test
    fun aProtocolWithOneStoppedRoomAndOneWorkingRoomIsWorking() {
        val rooms = listOf(room("jitsi", "j1", primary = true),
            ServerStatus.Room("server-jitsi-2.yaml", "jitsi", "datachannel", "j2", "olcrtc-jitsi-2", "Exited (0) 1 hour ago", false))
        val proofs = proofsOf(listOf(Triple("jitsi", "j1", true to now - 60_000)))
        assertEquals(Health.State.WORKING, Health.serverProtocols(rooms, proofs, emptySet(), now)["jitsi"])
    }

    @Test
    fun aStoppedProtocolBesideAnUncheckedOneReadsNotChecked() {
        val rooms = listOf(room("jitsi", "j1", primary = true), room("telemost", "t1", up = false))
        assertEquals(Health.State.UNKNOWN, Health.serverHeadline(rooms, emptyMap(), emptySet(), now).state)
    }

    @Test
    fun theStoppedRoomsOfAServerAreTheContainersNotRunning() {
        val missing = ServerStatus.Room("server-vkcalls.yaml", "vkcalls", "datachannel", "v1", "olcrtc-vkcalls", "missing", false)
        val rooms = listOf(room("jitsi", "j1", primary = true), room("telemost", "t1", up = false), missing)
        assertEquals(setOf(Health.room("telemost", "t1"), Health.room("vkcalls", "v1")), Health.stoppedRooms(rooms))
    }

    @Test
    fun aWholeServerConnectionWithAStoppedLaneStaysWorkingAndDoesNotNameIt() {
        val bundle = link("jitsi" to "j1", "telemost" to "t1", "vkcalls" to "v1")
        val r = record("all", bundle)
        val c = check(bundle, now - 60_000, true, false, true)
        val proofs = Health.proofs(listOf(r), mapOf("all" to c))
        val stopped = setOf(Health.room("telemost", "t1"))
        val verdict = Health.recordVerdict(r, c, proofs, emptySet(), isLive = false, now = now, stopped = stopped)
        assertEquals(Health.State.WORKING, verdict.state)
        assertTrue(verdict.failed.isEmpty())
    }

    @Test
    fun aConnectionWhoseEveryRoomIsStoppedReadsStopped() {
        val one = record("one", link("telemost" to "t1"))
        val failed = Health.proofs(listOf(one), mapOf("one" to check(one.olcrtc!!, now - 60_000, false)))
        val stopped = setOf(Health.room("telemost", "t1"))
        assertEquals(Health.State.STOPPED, Health.recordVerdict(one, null, failed, emptySet(), isLive = false, now = now, stopped = stopped).state)
        val bundle = record("all", link("jitsi" to "j1", "telemost" to "t1"))
        val both = setOf(Health.room("jitsi", "j1"), Health.room("telemost", "t1"))
        assertEquals(Health.State.STOPPED, Health.recordVerdict(bundle, null, emptyMap(), emptySet(), isLive = false, now = now, stopped = both).state)
    }

    @Test
    fun aRoomTheLiveTunnelCarriesIsWorkingEvenIfTheLastStatusSaidStopped() {
        val key = Health.room("telemost", "t1")
        assertEquals(Health.State.WORKING, Health.roomState(key, emptyMap(), setOf(key), now, stopped = setOf(key)))
    }

    @Test
    fun aStoppedRoomIsLeftOutOfAChecksRoomsUnlessEveryRoomIsStopped() {
        val bundle = link("jitsi" to "j1", "telemost" to "t1", "vkcalls" to "v1")
        val stopped = setOf(Health.room("telemost", "t1"))
        assertEquals(listOf("jitsi", "vkcalls"), Health.checkableLanes(bundle.lanes, stopped).map { it.carrier })
        assertEquals(bundle.lanes, Health.checkableLanes(bundle.lanes, emptySet()))
        assertTrue(Health.checkableLanes(link("telemost" to "t1").lanes, stopped).isEmpty())
    }

    @Test
    fun anAutomaticCheckSkipsAConnectionWhoseEveryRoomIsStopped() {
        val stopped = setOf(Health.room("telemost", "t1"))
        assertTrue(!Health.checksAutomatically(record("one", link("telemost" to "t1")), stopped))
        assertTrue(Health.checksAutomatically(record("all", link("jitsi" to "j1", "telemost" to "t1")), stopped))
        assertTrue(Health.checksAutomatically(record("j", link("jitsi" to "j1")), stopped))
        // Checks resume once the room runs again.
        assertTrue(Health.checksAutomatically(record("one", link("telemost" to "t1")), emptySet()))
    }

    // MARK: The rooms of a live whole-server session

    @Test
    fun aLiveLaneReadsByWhatTheCoreAndTheServerSay() {
        val lanes = link("jitsi" to "j1", "telemost" to "t1", "vkcalls" to "v1", "salutejazz" to "s1").lanes
        val stopped = setOf(Health.room("telemost", "t1"), Health.room("jitsi", "j1"))
        val states = listOf("running", "joining", "joining", "joining")
        val shown = lanes.indices.map { Health.laneShow(it, lanes, states, stopped, dead = setOf(3)) }
        assertEquals(listOf(Health.LaneShow.UP, Health.LaneShow.STOPPED, Health.LaneShow.JOINING, Health.LaneShow.NOT_ANSWERING), shown)
    }

    @Test
    fun aRoomWithNoContainerIsNotPartOfTheAnswer() {
        val missing = ServerStatus.Room("server-vkcalls.yaml", "vkcalls", "datachannel", "v1", "olcrtc-vkcalls", "missing", false)
        val proofs = proofsOf(listOf(Triple("vkcalls", "v1", false to now - 60_000)))
        val headline = Health.serverHeadline(listOf(room("jitsi", "j1", primary = true), missing), proofs, emptySet(), now)
        assertTrue(headline.failed.isEmpty())
    }

    // MARK: Fading and the newest evidence

    @Test
    fun aGoodCheckHoldsForThirtyMinutesThenFadesToNotChecked() {
        val proofs = proofsOf(listOf(Triple("jitsi", "j1", true to now - Health.FADE_MS)))
        val key = Health.room("jitsi", "j1")
        assertEquals(Health.State.WORKING, Health.roomState(key, proofs, emptySet(), now))
        assertEquals(Health.State.UNKNOWN, Health.roomState(key, proofs, emptySet(), now + 1))
    }

    @Test
    fun aFailureFadesTheSameWay() {
        val proofs = proofsOf(listOf(Triple("jitsi", "j1", false to now - Health.FADE_MS - 1)))
        assertEquals(Health.State.UNKNOWN, Health.roomState(Health.room("jitsi", "j1"), proofs, emptySet(), now))
    }

    @Test
    fun theNewestCheckOfARoomWins() {
        val l = link("jitsi" to "j1")
        val records = listOf(record("a", l), record("b", l))
        val failedLater = Health.proofs(records, mapOf("a" to check(l, now - 600_000, true), "b" to check(l, now - 60_000, false)))
        assertEquals(Health.State.FAILED, Health.roomState(Health.room("jitsi", "j1"), failedLater, emptySet(), now))
        val goodLater = Health.proofs(records, mapOf("a" to check(l, now - 60_000, true), "b" to check(l, now - 600_000, false)))
        assertEquals(Health.State.WORKING, Health.roomState(Health.room("jitsi", "j1"), goodLater, emptySet(), now))
    }

    @Test
    fun aCheckOfAWholeServerConnectionIsEvidenceForEachOfItsRooms() {
        val bundle = link("jitsi" to "j1", "telemost" to "t1", "vkcalls" to "v1")
        val proofs = Health.proofs(listOf(record("all", bundle)), mapOf("all" to check(bundle, now - 60_000, true, false, true)))
        assertEquals(Health.State.WORKING, Health.roomState(Health.room("jitsi", "j1"), proofs, emptySet(), now))
        assertEquals(Health.State.FAILED, Health.roomState(Health.room("telemost", "t1"), proofs, emptySet(), now))
        assertEquals(Health.State.WORKING, Health.roomState(Health.room("vkcalls", "v1"), proofs, emptySet(), now))
    }

    // MARK: The live tunnel's rooms

    @Test
    fun noRoomIsLiveUnlessTheSessionIsConnected() {
        assertTrue(Health.liveRooms(false, link("jitsi" to "j1"), null).isEmpty())
    }

    @Test
    fun aConnectedSingleRoomIsLive() {
        assertEquals(setOf(Health.room("jitsi", "j1")), Health.liveRooms(true, link("jitsi" to "j1"), null))
    }

    @Test
    fun aConnectedBundleCarriesOnlyTheRoomsTheCoreReportsRunning() {
        val bundle = link("jitsi" to "j1", "telemost" to "t1", "vkcalls" to "v1")
        val live = Health.liveRooms(true, bundle, listOf("running", "joining", "running"))
        assertEquals(setOf(Health.room("jitsi", "j1"), Health.room("vkcalls", "v1")), live)
    }

    @Test
    fun aBundleWhoseRoomStatesAreNotKnownYetClaimsNothing() {
        assertTrue(Health.liveRooms(true, link("jitsi" to "j1", "telemost" to "t1"), null).isEmpty())
    }

    // MARK: Connection rows

    @Test
    fun aBundleWithOneRoomNotAnsweringStaysWorkingAndNamesIt() {
        val bundle = link("jitsi" to "j1", "telemost" to "t1", "vkcalls" to "v1")
        val r = record("all", bundle)
        val c = check(bundle, now - 60_000, true, false, true)
        val proofs = Health.proofs(listOf(r), mapOf("all" to c))
        val verdict = Health.recordVerdict(r, c, proofs, emptySet(), isLive = false, now = now)
        assertEquals(Health.State.WORKING, verdict.state)
        assertEquals(listOf("telemost"), verdict.failed)
    }

    @Test
    fun aBundleCarryingTheLiveTunnelIsWorkingAndStillNamesTheRoomThatDoesNotAnswer() {
        val bundle = link("jitsi" to "j1", "telemost" to "t1")
        val r = record("all", bundle)
        val c = check(bundle, now - 60_000, true, false)
        val proofs = Health.proofs(listOf(r), mapOf("all" to c))
        val live = Health.liveRooms(true, bundle, listOf("running", "joining"))
        val verdict = Health.recordVerdict(r, c, proofs, live, isLive = true, now = now)
        assertEquals(Health.State.WORKING, verdict.state)
        assertEquals(listOf("telemost"), verdict.failed)
    }

    @Test
    fun theLiveRecordIsNeverRedWhateverItsLastCheckSaid() {
        val l = link("jitsi" to "j1")
        val r = record("one", l)
        val c = check(l, now - 60_000, false)
        val proofs = Health.proofs(listOf(r), mapOf("one" to c))
        val verdict = Health.recordVerdict(r, c, proofs, Health.liveRooms(true, l, null), isLive = true, now = now)
        assertEquals(Health.State.WORKING, verdict.state)
        assertTrue(verdict.failed.isEmpty())
    }

    @Test
    fun aSingleRoomWhoseCheckFailedIsNotWorkingAndNamesNothing() {
        val l = link("telemost" to "t1")
        val r = record("one", l)
        val c = check(l, now - 60_000, false)
        val proofs = Health.proofs(listOf(r), mapOf("one" to c))
        val verdict = Health.recordVerdict(r, c, proofs, emptySet(), isLive = false, now = now)
        assertEquals(Health.State.FAILED, verdict.state)
        assertTrue(verdict.failed.isEmpty())
    }

    @Test
    fun aSingleRoomTheServersWholeCheckProvedWorksWithoutACheckOfItsOwn() {
        val bundle = link("jitsi" to "j1", "vkcalls" to "v1")
        val whole = record("all", bundle)
        val one = record("one", link("vkcalls" to "v1"))
        val proofs = Health.proofs(listOf(whole, one), mapOf("all" to check(bundle, now - 60_000, true, true)))
        assertEquals(Health.State.WORKING, Health.recordVerdict(one, null, proofs, emptySet(), isLive = false, now = now).state)
    }

    @Test
    fun aDirectServerFollowsItsOwnCheckAndTheSameFade() {
        val direct = Health.Check(ms = 80, lanesOK = 1, lanesTotal = 1, error = null, at = now - 60_000)
        val failed = Health.Check(ms = null, lanesOK = 0, lanesTotal = 1, error = "no answer", at = now - 60_000)
        val old = direct.copy(at = now - Health.FADE_MS - 1)
        assertEquals(Health.State.WORKING, Health.directVerdict(direct, now))
        assertEquals(Health.State.FAILED, Health.directVerdict(failed, now))
        assertEquals(Health.State.UNKNOWN, Health.directVerdict(old, now))
        assertEquals(Health.State.UNKNOWN, Health.directVerdict(null, now))
    }

    @Test
    fun theMeasuredTimeShowsOnlyWhileTheCheckIsFresh() {
        val l = link("jitsi" to "j1")
        val c = check(l, now - 60_000, true)
        assertEquals(120L, Health.shownMs(c, now))
        assertEquals(null, Health.shownMs(c, now + Health.FADE_MS))
        assertEquals(null, Health.shownMs(null, now))
    }

    // MARK: The failure line

    @Test
    fun theFailureLineNamesTwoProtocolsThenCountsTheRest() {
        assertEquals("Yandex Telemost", Health.failedNames(listOf("telemost")))
        assertEquals("Yandex Telemost, VK Calls", Health.failedNames(listOf("telemost", "vkcalls")))
        assertEquals("Yandex Telemost, VK Calls +2", Health.failedNames(listOf("telemost", "vkcalls", "jitsi", "mtslink")))
    }
}
