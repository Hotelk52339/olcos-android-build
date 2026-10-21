package io.olcos.app.core

import io.olcos.app.core.MtsLearn.Assembled
import io.olcos.app.core.MtsLearn.Kind
import io.olcos.app.core.MtsLearn.Result
import io.olcos.app.core.MtsLearn.Seg
import io.olcos.app.core.MtsLearn.Sighting
import io.olcos.app.core.MtsLearn.Source
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app learns from the owner's own action in the sign-in sheet, and
 * what a replay rebuilds from it. Synthetic captures only; every id here is
 * made up. No network, no org.json.
 */
class MtsLearnTest {
    private val owner = "100200300"
    private val event = "400500600700"
    private val session = "700800900100"
    private val room = "https://my.mts-link.ru/j/$owner/$event"
    private val roomPage = "$room/stream-new/$session"
    private val create = "https://gw.mts-link.ru/api/events"
    private val jsonHeaders = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Authorization" to "Bearer @cookie(access)",
    )

    /** What the page hook would send as its second copy for a string body: the request as given, unscrubbed. */
    private fun copyOf(url: String, headers: Map<String, String>, body: String): MtsRequest.Copy {
        val json = runCatching { Json.parseToJsonElement(body) }.isSuccess
        return MtsRequest.Copy(url, headers.entries.map { it.key to it.value }, if (body.isEmpty()) MtsRequest.Body.None else MtsRequest.Body.Text(body, json), null)
    }

    private fun call(
        id: Int, method: String, url: String, headers: Map<String, String> = jsonHeaders, body: String = "{\"title\":\"Room\"}",
        status: Int = 200, response: String = "", at: Long = 1_000L, raw: MtsRequest.Copy? = copyOf(url, headers, body),
    ): WebCapture.Entry = WebCapture.answered(WebCapture.entry(method, url, headers, body, id, at, raw), status, response, at + 100)

    private fun page(url: String, at: Long) = Sighting(url, at, fromDom = false)
    private fun dom(text: String, at: Long) = Sighting(text, at, fromDom = true)

    private val created = call(1, "POST", create, status = 201, response = "{\"data\":{\"id\":$event,\"sessionId\":$session}}")

    // MARK: the page URL shapes

    @Test fun theEventIsTheSecondNumberAfterJ() {
        val m = RoomServices.MTSLink.meetingsIn(roomPage).single()
        assertEquals(owner, m.owner)
        assertEquals(event, m.event)
        assertEquals(room, m.link)
    }

    @Test fun everythingAfterTheEventIsTheSameMeeting() {
        for (tail in listOf("", "/", "/stream-new/$session", "/session/$session", "/a/b/c", "?x=1", "#chat", "/stream-new/$session?ice=tcp#x")) {
            assertEquals(tail, listOf(room), RoomServices.MTSLink.meetingsIn("$room$tail").map { it.link })
        }
    }

    @Test fun twoLinksWithNoSpaceBetweenThemAreBothFound() {
        val other = "https://my.mts-link.ru/j/$owner/400500600701"
        val json = "{\"a\":\"https:\\/\\/my.mts-link.ru\\/j\\/$owner\\/$event\",\"b\":\"$other\"}"
        assertEquals(listOf(event, "400500600701"), RoomServices.MTSLink.meetingsIn(json).map { it.event })
    }

    @Test fun anAliasOwnerReadsAsZero() {
        assertEquals("0", RoomServices.MTSLink.meetingsIn("https://my.mts-link.ru/j/supportwebinar/$event/stream-new/$session").single().owner)
    }

    // MARK: Finish — the lessons

    @Test fun finishWithNothingRecordedFindsNoMeeting() {
        assertEquals(Result.None, MtsLearn.finish(emptyList(), emptyList(), 5L))
        assertEquals(Result.None, MtsLearn.finish(listOf(call(1, "GET", "https://my.mts-link.ru/api/me", response = "{}")), listOf(page("https://my.mts-link.ru/", 1_100)), 5L))
    }

    @Test fun finishWithOneCreatedMeetingLearnsTheRequest() {
        val result = MtsLearn.finish(listOf(created), listOf(page("https://my.mts-link.ru/events", 900), page(roomPage, 1_500)), 42L) as Result.One
        assertEquals(room, result.meeting.link)
        assertTrue(result.madeByApp)
        val t = result.template!!
        assertEquals("POST", t.method)
        assertEquals(create, t.url)
        assertEquals(Kind.EVENT, t.kind)
        assertEquals("data.id", MtsLearn.pathText(t.path))
        assertEquals(owner, t.owner)
        assertEquals("application/json", t.contentType)
        assertEquals(42L, t.learnedAt)
        // The credential header is a provenance, the ordinary one a literal; the content type is its own field.
        assertEquals(listOf(Source.Literal("application/json"), Source.Cookie("access", "Bearer ")), t.headers.map { it.source })
        assertEquals(listOf("Accept", "Authorization"), t.headers.map { it.name })
    }

    @Test fun theSessionNumberIsNeverTheEvent() {
        // The answer mentions the session too; only the event the page named is a meeting.
        val result = MtsLearn.finish(listOf(created), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertEquals(event, result.meeting.event)
        assertEquals("data.id", MtsLearn.pathText(result.template!!.path))
    }

    @Test fun anAliasOwnerInThePageUrlIsStoredAsZero() {
        val result = MtsLearn.finish(listOf(created), listOf(page("https://my.mts-link.ru/j/supportwebinar/$event/stream-new/$session", 1_500)), 1L) as Result.One
        assertEquals("0", result.template!!.owner)
    }

    @Test fun finishWithOnlyAPageAndNoCreateCallLearnsNothing() {
        val result = MtsLearn.finish(emptyList(), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertEquals(room, result.meeting.link)
        assertFalse(result.madeByApp)
        assertNull(result.template)
    }

    @Test fun finishWithTwoCreatedMeetingsIsSeveralAndLearnsNothing() {
        val second = call(2, "POST", create, status = 201, response = "{\"data\":{\"id\":400500600701}}", at = 3_000L)
        val result = MtsLearn.finish(listOf(created, second), listOf(page(roomPage, 1_500), page("https://my.mts-link.ru/j/$owner/400500600701/stream-new/$session", 3_500)), 1L)
        assertEquals(listOf(event, "400500600701"), (result as Result.Several).meetings.map { it.event })
    }

    @Test fun twoMeetingsOnPagesAndNoCreateCallIsSeveral() {
        val result = MtsLearn.finish(emptyList(), listOf(dom("$room\nhttps://my.mts-link.ru/j/$owner/400500600701", 1_000)), 1L)
        assertEquals(2, (result as Result.Several).meetings.size)
    }

    @Test fun aGetListMentioningAnOldMeetingIsNotTheCreateCall() {
        val list = call(1, "GET", "https://gw.mts-link.ru/api/events?limit=20", emptyMap(), "", 200, "{\"items\":[{\"link\":\"$room\",\"id\":$event}]}")
        assertTrue(MtsLearn.created(listOf(list), listOf(page(roomPage, 1_500))).isEmpty())
        val result = MtsLearn.finish(listOf(list), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertFalse(result.madeByApp)
        assertNull(result.template)
    }

    @Test fun aGetListNextToTheRealCreateCallDoesNotChangeTheLesson() {
        val list = call(1, "GET", "https://gw.mts-link.ru/api/events?limit=20", emptyMap(), "", 200, "{\"items\":[{\"link\":\"https://my.mts-link.ru/j/$owner/400500600709\"}]}", at = 500L)
        val make = created.copy(id = 2, at = 1_000L)
        val result = MtsLearn.finish(listOf(list, make), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertEquals(event, result.meeting.event)
        assertEquals("POST", result.template!!.method)
    }

    @Test fun theEarliestCallThatMentionsTheMeetingIsTheCreateCall() {
        val later = call(2, "PATCH", "$create/settings", body = "{\"x\":1}", status = 200, response = "{\"data\":{\"id\":$event}}", at = 1_200L)
        val result = MtsLearn.finish(listOf(created, later), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertEquals(create, result.template!!.url)
    }

    @Test fun onlyAMutatingCallThatSucceededCounts() {
        val sightings = listOf(page(roomPage, 1_500))
        assertTrue(MtsLearn.created(listOf(created.copy(method = "GET")), sightings).isEmpty())
        assertTrue(MtsLearn.created(listOf(created.copy(method = "DELETE")), sightings).isEmpty())
        assertTrue(MtsLearn.created(listOf(created.copy(status = 500)), sightings).isEmpty())
        assertTrue(MtsLearn.created(listOf(created.copy(status = null)), sightings).isEmpty())
        assertEquals(1, MtsLearn.created(listOf(created.copy(method = "PUT")), sightings).size)
        assertEquals(1, MtsLearn.created(listOf(created.copy(method = "PATCH")), sightings).size)
    }

    @Test fun aCallThatCarriedTheMeetingItselfDidNotMakeIt() {
        val update = call(1, "PUT", "$create/$event", body = "{\"title\":\"New\"}", response = "{\"id\":$event,\"link\":\"$room\"}")
        assertTrue(MtsLearn.created(listOf(update), listOf(page(roomPage, 1_500))).isEmpty())
    }

    @Test fun anIdTheSheetShowsTooLateIsNotThisCall() {
        assertTrue(MtsLearn.created(listOf(created), listOf(page(roomPage, 1_100 + MtsLearn.SHOW_WINDOW_MS + 1))).isEmpty())
        assertEquals(1, MtsLearn.created(listOf(created), listOf(page(roomPage, 1_100 + MtsLearn.SHOW_WINDOW_MS))).size)
    }

    @Test fun anIdThePageNeverShowedIsNotAMeeting() {
        assertTrue(MtsLearn.created(listOf(created), listOf(page("https://my.mts-link.ru/events", 1_500))).isEmpty())
    }

    @Test fun theLinkInTheAnswerMakesTheMeetingEvenWithEscapedSlashes() {
        val answer = "{\"result\":{\"url\":\"https:\\/\\/my.mts-link.ru\\/j\\/$owner\\/$event\\/stream-new\\/$session\"}}"
        val linked = call(1, "POST", create, status = 201, response = answer)
        val result = MtsLearn.finish(listOf(linked), emptyList(), 7L) as Result.One
        assertTrue(result.madeByApp)
        val t = result.template!!
        assertEquals(Kind.LINK, t.kind)
        assertEquals("result.url", MtsLearn.pathText(t.path))
        // Another answer of the same shape gives another meeting's link.
        val next = "{\"result\":{\"url\":\"https:\\/\\/my.mts-link.ru\\/j\\/$owner\\/400500600701\"}}"
        assertEquals("https://my.mts-link.ru/j/$owner/400500600701", MtsLearn.resultLink(t, next))
        assertNull(MtsLearn.resultLink(t, "{\"result\":{}}"))
    }

    @Test fun aLinkInAnAnswerThatIsNotJsonIsReadFromItsText() {
        val linked = call(1, "POST", create, status = 201, response = "created: $room")
        val t = (MtsLearn.finish(listOf(linked), emptyList(), 7L) as Result.One).template!!
        assertEquals(emptyList<Seg>(), t.path)
        assertEquals("https://my.mts-link.ru/j/$owner/400500600701", MtsLearn.resultLink(t, "ok https://my.mts-link.ru/j/$owner/400500600701/"))
    }

    @Test fun theHintIsPassiveAndOnlyAnnouncesACreatedMeeting() {
        assertFalse(MtsLearn.looksCreated(emptyList(), emptyList()))
        assertFalse(MtsLearn.looksCreated(listOf(created), emptyList()))
        assertTrue(MtsLearn.looksCreated(listOf(created), listOf(page(roomPage, 1_500))))
    }

    // MARK: why no template

    @Test fun aBodyLongerThanTheDisplayLimitIsStillTheWholeBodyInTheTemplate() {
        val body = "{\"note\":\"" + "x".repeat(WebCapture.BODY_LIMIT + 5) + "\"}"
        val long = call(1, "POST", create, body = body, status = 201, response = "{\"data\":{\"id\":$event}}")
        assertTrue(long.bodyCut)
        val t = (MtsLearn.finish(listOf(long), listOf(page(roomPage, 1_500)), 1L) as Result.One).template!!
        assertEquals(MtsRequest.Body.Text(body, true), t.body)
    }

    @Test fun aRequestWithNoCopyFromThePageIsNoTemplate() {
        val bare = call(1, "POST", create, status = 201, response = "{\"data\":{\"id\":$event}}", raw = null)
        val result = MtsLearn.finish(listOf(bare), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertTrue(result.madeByApp)
        assertNull(result.template)
        assertNotNull(result.why)
    }

    @Test fun anAnswerThatIsNotJsonCannotGiveAnEventPath() {
        val text = call(1, "POST", create, status = 201, response = "id=$event")
        val result = MtsLearn.finish(listOf(text), listOf(page(roomPage, 1_500)), 1L) as Result.One
        assertTrue(result.madeByApp)
        assertNull(result.template)
    }

    // MARK: nothing secret is kept

    @Test fun aCredentialHeaderTheHookDidNotMarkBecomesSeenAndItsValueIsNotKept() {
        val bare = call(1, "POST", create, mapOf("Authorization" to "Bearer abc.secret-value", "X-CSRF-Token" to "csrf-9"), status = 201, response = "{\"data\":{\"id\":$event}}")
        val t = (MtsLearn.finish(listOf(bare), listOf(page(roomPage, 1_500)), 1L) as Result.One).template!!
        assertEquals(listOf(Source.Seen("authorization"), Source.Seen("x-csrf-token")), t.headers.map { it.source })
        val kept = MtsLearn.encode(t)
        assertFalse(kept.contains("secret-value"))
        assertFalse(kept.contains("csrf-9"))
    }

    @Test fun theDisplayCopyIsScrubbedWhileTheTemplateKeepsTheRequestAsSent() {
        val withSecret = call(1, "POST", create, body = "{\"title\":\"Room\",\"password\":\"hunter2\"}", status = 201, response = "{\"data\":{\"id\":$event}}")
        assertFalse(withSecret.body.contains("hunter2"))
        assertFalse(withSecret.dump().contains("hunter2"))
        val t = (MtsLearn.finish(listOf(withSecret), listOf(page(roomPage, 1_500)), 1L) as Result.One).template!!
        assertEquals(MtsRequest.Body.Text("{\"title\":\"Room\",\"password\":\"hunter2\"}", true), t.body)
        assertEquals(0, MtsLearn.takenFromStorage(t))
    }

    @Test fun anAnswerIsScrubbedBeforeItIsKept() {
        val answered = call(1, "POST", create, status = 201, response = "{\"accessToken\":\"tok-77\",\"data\":{\"id\":$event}}")
        assertFalse(answered.response.contains("tok-77"))
        assertTrue(answered.response.contains(event))
    }

    @Test fun anOrdinaryHeaderKeepsItsValueAsTheHeaderWasSent() {
        val id = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"
        val headers = jsonHeaders + ("X-Request-Id" to id)
        val entry = call(1, "POST", create, headers, status = 201, response = "{\"data\":{\"id\":$event}}")
        assertTrue(entry.headers.contains("X-Request-Id: <key>"))
        val t = (MtsLearn.finish(listOf(entry), listOf(page(roomPage, 1_500)), 1L) as Result.One).template!!
        assertEquals(Source.Literal(id), t.headers.single { it.name == "X-Request-Id" }.source)
    }

    @Test fun theLogNamesCarryNoValue() {
        val t = (MtsLearn.finish(listOf(created), listOf(page(roomPage, 1_500)), 1L) as Result.One).template!!
        assertEquals("POST $create", t.shape())
        assertEquals(listOf("authorization<-@cookie(access)"), MtsLearn.sourceNames(t))
    }

    // MARK: JSON paths

    private val json = Json.parseToJsonElement("{\"data\":{\"items\":[{\"id\":\"123456789\"},{\"id\":987654321}],\"id\":555666777},\"flag\":true}")

    @Test fun aPathIsEvaluatedThroughKeysAndIndexes() {
        assertEquals("555666777", MtsLearn.evaluate(json, listOf(Seg.Key("data"), Seg.Key("id"))))
        assertEquals("123456789", MtsLearn.evaluate(json, listOf(Seg.Key("data"), Seg.Key("items"), Seg.Index(0), Seg.Key("id"))))
        assertEquals("987654321", MtsLearn.evaluate(json, listOf(Seg.Key("data"), Seg.Key("items"), Seg.Index(1), Seg.Key("id"))))
        assertNull(MtsLearn.evaluate(json, listOf(Seg.Key("data"), Seg.Key("items"), Seg.Index(2))))
        assertNull(MtsLearn.evaluate(json, listOf(Seg.Key("nothing"))))
        assertNull(MtsLearn.evaluate(json, listOf(Seg.Key("data"))))
    }

    @Test fun anIdIsFoundAsAStringOrANumberAndTheShortestPathWins() {
        assertEquals("data.items[0].id", MtsLearn.pathText(MtsLearn.find(json) { it == "123456789" }!!))
        assertEquals("data.items[1].id", MtsLearn.pathText(MtsLearn.find(json) { it == "987654321" }!!))
        assertEquals("data.id", MtsLearn.pathText(MtsLearn.find(json) { it.length == 9 && it.startsWith("5") }!!))
        assertNull(MtsLearn.find(json) { it == "nope" })
    }

    // MARK: the link a replay yields

    private fun eventTemplate(path: List<Seg> = listOf(Seg.Key("data"), Seg.Key("id"))) = MtsLearn.Template(
        "POST", create, "application/json", MtsRequest.Body.Text("{}", true), emptyList(), Kind.EVENT, path, owner, 1L,
    )

    @Test fun theLinkIsBuiltFromTheRecipe() {
        assertEquals("https://my.mts-link.ru/j/$owner/400500600701", MtsLearn.resultLink(eventTemplate(), "{\"data\":{\"id\":400500600701}}"))
        assertEquals("https://my.mts-link.ru/j/$owner/400500600701", MtsLearn.resultLink(eventTemplate(), "{\"data\":{\"id\":\"400500600701\"}}"))
        assertNull(MtsLearn.resultLink(eventTemplate(), "{\"data\":{\"id\":\"abc\"}}"))
        assertNull(MtsLearn.resultLink(eventTemplate(), "{\"data\":{}}"))
        assertNull(MtsLearn.resultLink(eventTemplate(), "not json"))
    }

    @Test fun theRoomThatCameBackIsAcceptedOnlyWhenItIsNew() {
        assertFalse(MtsLearn.isNewRoom(room, room))
        assertFalse(MtsLearn.isNewRoom(room, "$room?ice=tcp"))
        assertTrue(MtsLearn.isNewRoom("https://my.mts-link.ru/j/$owner/400500600701", room))
        assertTrue(MtsLearn.isNewRoom(room, ""))
    }

    // MARK: headers from provenance

    private fun withHeaders(vararg headers: MtsLearn.Header) =
        eventTemplate().copy(headers = headers.toList())

    @Test fun everyKindOfSourceIsResolvedFromWhatThePageHandsOver() {
        val t = withHeaders(
            MtsLearn.Header("Accept", Source.Literal("application/json")),
            MtsLearn.Header("Authorization", Source.Cookie("access", "Bearer ")),
            MtsLearn.Header("X-Local", Source.Local("tok")),
            MtsLearn.Header("X-Session", Source.Session("sid")),
            MtsLearn.Header("X-Csrf-Token", Source.Seen("x-csrf-token")),
        )
        val ready = MtsLearn.assemble(t, mapOf("access" to "c-1"), mapOf("tok" to "l-2"), mapOf("sid" to "s-3"), mapOf("x-csrf-token" to "n-4")) as Assembled.Ready
        assertEquals(
            listOf("Accept" to "application/json", "Authorization" to "Bearer c-1", "X-Local" to "l-2", "X-Session" to "s-3", "X-Csrf-Token" to "n-4", "Content-Type" to "application/json"),
            ready.headers,
        )
        // Only names reach the log.
        assertEquals(
            listOf("authorization<-@cookie(access)", "x-local<-@local(tok)", "x-session<-@session(sid)", "x-csrf-token<-@seen(x-csrf-token)"),
            ready.resolved,
        )
        assertFalse(ready.resolved.joinToString().contains("c-1"))
    }

    @Test fun aCredentialThatCannotBeResolvedAbortsTheRequest() {
        val t = withHeaders(MtsLearn.Header("Authorization", Source.Cookie("access", "Bearer ")))
        assertEquals(Assembled.Missing("Authorization"), MtsLearn.assemble(t, emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        assertEquals(Assembled.Missing("Authorization"), MtsLearn.assemble(t, mapOf("access" to ""), emptyMap(), emptyMap(), emptyMap()))
        val seen = withHeaders(MtsLearn.Header("X-Token", Source.Seen("x-token")))
        assertEquals(Assembled.Missing("X-Token"), MtsLearn.assemble(seen, mapOf("x-token" to "no"), emptyMap(), emptyMap(), emptyMap()))
    }

    @Test fun theContentTypeIsNotSentTwice() {
        val t = withHeaders(MtsLearn.Header("content-type", Source.Literal("application/json")))
        val ready = MtsLearn.assemble(t, emptyMap(), emptyMap(), emptyMap(), emptyMap()) as Assembled.Ready
        assertEquals(1, ready.headers.size)
    }

    @Test fun theTemplateSaysWhatItNeedsFromThePage() {
        val t = withHeaders(
            MtsLearn.Header("A", Source.Cookie("c")), MtsLearn.Header("B", Source.Local("l")),
            MtsLearn.Header("C", Source.Session("s")), MtsLearn.Header("D", Source.Seen("d")), MtsLearn.Header("E", Source.Literal("x")),
        )
        val n = MtsLearn.needs(t)
        assertEquals(setOf("c"), n.cookies)
        assertEquals(setOf("l"), n.local)
        assertEquals(setOf("s"), n.session)
        assertEquals(setOf("d"), n.seen)
        assertFalse(n.onlySeen)
        assertTrue(MtsLearn.needs(withHeaders(MtsLearn.Header("D", Source.Seen("d")))).onlySeen)
    }

    // MARK: the marker

    @Test fun aMarkerNamesWhereAValueComesFrom() {
        assertEquals(Source.Cookie("access", "Bearer "), MtsLearn.parseMarker("Bearer @cookie(access)"))
        assertEquals(Source.Cookie("XSRF-TOKEN"), MtsLearn.parseMarker("@cookie(XSRF-TOKEN)"))
        assertEquals(Source.Local("auth.token"), MtsLearn.parseMarker("@local(auth.token)"))
        assertEquals(Source.Session("sid"), MtsLearn.parseMarker("@session(sid)"))
        assertEquals(Source.Seen("x-token"), MtsLearn.parseMarker("@seen(X-Token)"))
    }

    @Test fun anythingElseIsNoMarker() {
        for (value in listOf("", "Bearer abc.def", "<hidden>", "@cookie()", "@cookie(a b)", "@other(x)", "Bearer  @cookie(x)", "x @cookie(a)(b)")) {
            assertNull(value, MtsLearn.parseMarker(value))
        }
    }

    @Test fun aMarkerRoundTrips() {
        for (s in listOf(Source.Cookie("a", "Bearer "), Source.Local("b"), Source.Session("c", "Token "), Source.Seen("d"))) {
            assertEquals(s, MtsLearn.parseMarker(MtsLearn.markerOf(s)))
        }
    }

    @Test fun credentialHeadersAreToldByName() {
        for (name in listOf("Authorization", "X-Auth-Token", "x-csrf-token", "X-XSRF-TOKEN", "token", "X-Session-Id")) assertTrue(name, MtsLearn.isCredentialHeader(name))
        for (name in listOf("Content-Type", "Accept", "X-Requested-With", "Accept-Language", "X-Considered")) assertFalse(name, MtsLearn.isCredentialHeader(name))
    }

    // MARK: persistence

    @Test fun aTemplateSurvivesBeingKept() {
        val t = MtsLearn.Template(
            "POST", create, "application/json", MtsRequest.Body.Text("{\"title\":\"Room\"}", true),
            listOf(
                MtsLearn.Header("Accept", Source.Literal("application/json")), MtsLearn.Header("Authorization", Source.Cookie("access", "Bearer ")),
                MtsLearn.Header("X-L", Source.Local("k")), MtsLearn.Header("X-S", Source.Session("k")), MtsLearn.Header("X-T", Source.Seen("x-t")),
            ),
            Kind.LINK, listOf(Seg.Key("items"), Seg.Index(0), Seg.Key("link")), owner, 99L,
        )
        assertEquals(t, MtsLearn.decode(MtsLearn.encode(t)))
        assertEquals(t.copy(kind = Kind.EVENT, path = emptyList()), MtsLearn.decode(MtsLearn.encode(t.copy(kind = Kind.EVENT, path = emptyList()))))
    }

    @Test fun whatCannotBeReadIsNothingLearned() {
        for (text in listOf(null, "", "not json", "{}", "[]", "{\"method\":\"GET\",\"url\":\"https://x\",\"headers\":[],\"path\":[]}")) {
            assertNull(text, MtsLearn.decode(text))
        }
    }

    // MARK: the answer to a replay

    @Test fun aGoodAnswerIsTheNewRoom() {
        val answer = MtsLearn.readAnswer(eventTemplate(), 201, "{\"data\":{\"id\":400500600701}}", room)
        assertEquals(MtsLearn.Answer.Made("https://my.mts-link.ru/j/$owner/400500600701"), answer)
    }

    @Test fun theRoomThatIsCurrentComingBackIsAFailure() {
        val answer = MtsLearn.readAnswer(eventTemplate(), 200, "{\"data\":{\"id\":$event}}", "$room?ice=tcp")
        assertTrue(answer is MtsLearn.Answer.Failed)
    }

    @Test fun aRefusedAccountIsSignedOutAndAnythingElseIsAFailure() {
        assertEquals(MtsLearn.Answer.SignedOut, MtsLearn.readAnswer(eventTemplate(), 401, "", room))
        assertEquals(MtsLearn.Answer.SignedOut, MtsLearn.readAnswer(eventTemplate(), 403, "{}", room))
        assertEquals(MtsLearn.Answer.Failed("HTTP 429"), MtsLearn.readAnswer(eventTemplate(), 429, "{}", room))
        assertEquals(MtsLearn.Answer.Failed("HTTP 500"), MtsLearn.readAnswer(eventTemplate(), 500, "{}", room))
    }

    @Test fun anAnswerWithoutTheMeetingIsAFailure() {
        val answer = MtsLearn.readAnswer(eventTemplate(), 200, "{\"data\":{}}", room)
        assertEquals(MtsLearn.Answer.Failed("the answer holds no meeting at 'data.id'"), answer)
    }

    @Test fun whatThePageHandedOverIsRead() {
        val raw = "\"{\\\"cookies\\\":{\\\"access\\\":\\\"c-1\\\"},\\\"local\\\":{},\\\"session\\\":{\\\"sid\\\":\\\"s-3\\\"},\\\"seen\\\":{\\\"x-t\\\":\\\"n-4\\\"},\\\"href\\\":\\\"https://my.mts-link.ru/events\\\"}\""
        val got = MtsLearn.parseCollected(raw)!!
        assertEquals(mapOf("access" to "c-1"), got.cookies)
        assertEquals(emptyMap<String, String>(), got.local)
        assertEquals(mapOf("sid" to "s-3"), got.session)
        assertEquals(mapOf("x-t" to "n-4"), got.seen)
        assertEquals("https://my.mts-link.ru/events", got.href)
        for (bad in listOf(null, "", "null", "\"not json\"", "42")) assertNull(bad, MtsLearn.parseCollected(bad))
    }

    @Test fun theScriptsCarryTheirValuesAsJsonAndNothingElse() {
        val fetch = MtsLinkScripts.fetchScript("POST", "https://gw.mts-link.ru/api/events", listOf("X-A" to "a\"b\\c"), MtsRequest.Body.Text("{\"t\":\"q\"}", true))
        assertTrue(fetch.contains("\"url\":\"https://gw.mts-link.ru/api/events\""))
        assertTrue(fetch.contains("[\"X-A\",\"a\\\"b\\\\c\"]"))
        assertTrue(fetch.contains("\"text\":\"{\\\"t\\\":\\\"q\\\"}\""))
        assertTrue(fetch.contains("credentials:'include'"))
        val collect = MtsLinkScripts.collectScript(MtsLearn.Needs(setOf("access"), setOf("k"), emptySet(), setOf("X-T")))
        assertTrue(collect.contains("\"cookies\":[\"access\"]"))
        assertTrue(collect.contains("\"seen\":[\"x-t\"]"))
    }

    // MARK: the replay's page

    @Test fun aSignInPageMeansADeadSession() {
        for (url in listOf("https://my.mts-link.ru/login", "https://my.mts-link.ru/auth/login?next=/", "https://my.mts-link.ru/registration", "https://my.mts-link.ru/signin/", "https://sso.example.com/login", "https://id.example.com/")) {
            assertTrue(url, MtsLearn.isSignInUrl(url))
        }
    }

    @Test fun theSignedInPagesAreNotSignInPages() {
        for (url in listOf("https://my.mts-link.ru/", "https://my.mts-link.ru/events", "https://my.mts-link.ru/j/$owner/$event", "https://my.mts-link.ru/dashboard/author")) {
            assertFalse(url, MtsLearn.isSignInUrl(url))
        }
        assertFalse(MtsLearn.isSignInUrl(""))
    }
}
