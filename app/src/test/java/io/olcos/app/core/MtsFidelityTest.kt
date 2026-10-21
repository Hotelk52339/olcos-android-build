package io.olcos.app.core

import io.olcos.app.core.MtsLearn.Assembled
import io.olcos.app.core.MtsLearn.Result
import io.olcos.app.core.MtsLearn.Sighting
import io.olcos.app.core.MtsLearn.Source
import io.olcos.app.core.MtsRequest.Body
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The template keeps the request as the page sent it, not as the sheet shows
 * it: a UUID in a path or a body survives, a value the page took from a cookie
 * or storage is a marker resolved again at replay, and the body is rebuilt in
 * the page as the same kind (string, URLSearchParams, FormData). The display
 * copy stays scrubbed. Ids are made up.
 */
class MtsFidelityTest {
    private val owner = "100200300"
    private val event = "400500600700"
    private val uuid = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    private val roomPage = "https://my.mts-link.ru/j/$owner/$event/stream-new/700800900100"
    private val answer = "{\"data\":{\"id\":$event}}"

    private fun learn(url: String, headers: List<Pair<String, String>>, body: Body, response: String = answer, why: String? = null): Result.One {
        val display = headers.associate { it.first to it.second }
        val raw = MtsRequest.Copy(url, headers, body, why)
        val shown = when (body) {
            Body.None -> ""
            is Body.Text -> body.text
            is Body.Form -> body.fields.joinToString("&") { it.first + "=" + it.second }
            is Body.Multipart -> body.fields.joinToString("&") { it.first + "=" + it.second }
        }
        val entry = WebCapture.answered(WebCapture.entry("POST", url, display, shown, 1, 1_000L, raw), 201, response, 1_100L)
        return MtsLearn.finish(listOf(entry), listOf(Sighting(roomPage, 1_500L, fromDom = false)), 9L) as Result.One
    }

    private fun assembled(t: MtsLearn.Template, cookies: Map<String, String> = emptyMap(), local: Map<String, String> = emptyMap(), session: Map<String, String> = emptyMap()) =
        MtsLearn.assemble(t, cookies, local, session, emptyMap())

    // MARK: fidelity

    @Test fun aUuidInThePathAndInTheJsonBodySurvivesIntoTheTemplateAndTheReplay() {
        val url = "https://gw.mts-link.ru/api/events/$uuid/create?trace=$uuid"
        val body = "{\"requestId\":\"$uuid\",\"title\":\"Room\"}"
        val result = learn(url, listOf("Content-Type" to "application/json"), Body.Text(body, true))
        val t = result.template!!
        assertEquals(url, t.url)
        assertEquals(Body.Text(body, true), t.body)
        val ready = assembled(t) as Assembled.Ready
        assertEquals(url, ready.url)
        assertEquals(Body.Text(body, true), ready.body)
        // The sheet's own copy of the same call is still scrubbed.
        val shown = WebCapture.entry("POST", url, emptyMap(), body)
        assertFalse(shown.url.contains(uuid))
        assertFalse(shown.body.contains(uuid))
    }

    @Test fun aLongIdAndAnythingElseInTheBodyIsNotBlanked() {
        val body = "{\"owner\":\"" + "A1".repeat(20) + "\",\"password\":\"hunter2\"}"
        val t = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.Text(body, true)).template!!
        assertEquals(Body.Text(body, true), t.body)
        assertFalse((assembled(t) as Assembled.Ready).body.toString().contains("<key>"))
    }

    @Test fun aCookieEqualBodyValueIsAMarkerAndResolvesAtReplay() {
        // What the page hook writes for a body value equal to the cookie `user_hash`.
        val marked = "{\"owner\":\"@cookie(user_hash)\",\"title\":\"Room\"}"
        val t = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.Text(marked, true)).template!!
        assertEquals(1, MtsLearn.takenFromStorage(t))
        assertEquals(setOf("user_hash"), MtsLearn.needs(t).cookies)
        val ready = assembled(t, cookies = mapOf("user_hash" to "abc\"def\\ghi1")) as Assembled.Ready
        // A JSON string is escaped when the value goes in.
        assertEquals(Body.Text("{\"owner\":\"abc\\\"def\\\\ghi1\",\"title\":\"Room\"}", true), ready.body)
        assertTrue(ready.resolved.contains("request<-@cookie(user_hash)"))
        assertFalse(ready.resolved.joinToString().contains("abc"))
    }

    @Test fun aMarkerWithNoValueAtReplayAbortsTheRequest() {
        val t = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.Text("{\"o\":\"@local(uid)\"}", true)).template!!
        assertEquals(Assembled.Missing("local uid"), assembled(t))
        assertEquals(Assembled.Missing("local uid"), assembled(t, local = mapOf("uid" to "")))
        assertTrue(assembled(t, local = mapOf("uid" to "u-12345678")) is Assembled.Ready)
    }

    @Test fun aMarkerInTheUrlAndInAHeaderResolvesToo() {
        val url = "https://gw.mts-link.ru/api/users/@session(sid)/events?k=@cookie(key)"
        val t = learn(url, listOf("X-Owner" to "u-@local(uid)"), Body.None).template!!
        assertEquals(Source.Literal("u-@local(uid)"), t.headers.single().source)
        assertEquals(3, MtsLearn.takenFromStorage(t))
        val ready = assembled(t, cookies = mapOf("key" to "a b/c"), local = mapOf("uid" to "42424242"), session = mapOf("sid" to "s&1")) as Assembled.Ready
        assertEquals("https://gw.mts-link.ru/api/users/s%261/events?k=a%20b%2Fc", ready.url)
        assertEquals(listOf("X-Owner" to "u-42424242"), ready.headers)
    }

    @Test fun anEmbeddedMarkerInPlainTextResolvesAsItIs() {
        val t = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.Text("owner=@cookie(c)&x=1", false)).template!!
        val ready = assembled(t, cookies = mapOf("c" to "v&v")) as Assembled.Ready
        assertEquals(Body.Text("owner=v&v&x=1", false), ready.body)
    }

    @Test fun aFormFieldMarkerIsFilledPerField() {
        val t = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.Form(listOf("owner" to "@cookie(c)", "title" to "Room"))).template!!
        val ready = assembled(t, cookies = mapOf("c" to "v&v=1")) as Assembled.Ready
        assertEquals(Body.Form(listOf("owner" to "v&v=1", "title" to "Room")), ready.body)
    }

    @Test fun theTemplateNeedsOnlyWhatItsMarkersName() {
        val t = learn("https://gw.mts-link.ru/a?k=@cookie(k)", listOf("Authorization" to "Bearer @local(tok)"), Body.Text("{\"o\":\"@session(s)\"}", true)).template!!
        val n = MtsLearn.needs(t)
        assertEquals(setOf("k"), n.cookies)
        assertEquals(setOf("tok"), n.local)
        assertEquals(setOf("s"), n.session)
    }

    // MARK: body kinds

    @Test fun aUrlSearchParamsBodyStaysAFormAndTheContentTypeIsTheBrowsers() {
        val fields = listOf("title" to "Room", "type" to "permanent")
        val t = learn("https://gw.mts-link.ru/api/events", listOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8", "Accept" to "*/*"), Body.Form(fields)).template!!
        assertEquals(Body.Form(fields), t.body)
        assertEquals("", t.contentType)
        val ready = assembled(t) as Assembled.Ready
        assertTrue(ready.headers.none { it.first.equals("content-type", ignoreCase = true) })
        val js = MtsLinkScripts.fetchScript(t.method, ready.url, ready.headers, ready.body)
        assertTrue(js, js.contains("\"kind\":\"form\""))
        assertTrue(js, js.contains("\"fields\":[[\"title\",\"Room\"],[\"type\",\"permanent\"]]"))
        assertTrue(js, js.contains("new URLSearchParams(s.fields)"))
        assertFalse(js.contains("Content-Type"))
        assertFalse(js.contains("\"kind\":\"multipart\""))
    }

    @Test fun aFormDataBodyStaysMultipartAndTheBoundaryIsTheBrowsers() {
        val fields = listOf("title" to "Room", "note" to "a\"b")
        val t = learn("https://gw.mts-link.ru/api/events", listOf("Content-Type" to "multipart/form-data; boundary=----x"), Body.Multipart(fields)).template!!
        assertEquals(Body.Multipart(fields), t.body)
        assertEquals("", t.contentType)
        val ready = assembled(t) as Assembled.Ready
        assertTrue(ready.headers.isEmpty())
        val js = MtsLinkScripts.fetchScript(t.method, ready.url, ready.headers, ready.body)
        assertTrue(js, js.contains("\"kind\":\"multipart\""))
        assertTrue(js, js.contains("new FormData()"))
        assertTrue(js, js.contains("fd.append(p[0],p[1])"))
        assertTrue(js, js.contains("[\"note\",\"a\\\"b\"]"))
        assertFalse(js.contains("Content-Type"))
        assertFalse(js.contains("multipart/form-data"))
    }

    @Test fun aStringBodyKeepsTheContentTypeThePageSetAndNeverForcesAnother() {
        val withType = learn("https://gw.mts-link.ru/api/events", listOf("Content-Type" to "application/json"), Body.Text("{\"a\":1}", true)).template!!
        assertEquals("application/json", withType.contentType)
        val ready = assembled(withType) as Assembled.Ready
        assertEquals(listOf("Content-Type" to "application/json"), ready.headers)
        val js = MtsLinkScripts.fetchScript("POST", ready.url, ready.headers, ready.body)
        assertTrue(js, js.contains("\"kind\":\"text\""))
        assertTrue(js, js.contains("o.body=s.text"))
        // A string the page sent with no content type of its own gets none: the browser's default applies, text/plain is not forced.
        val bare = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.Text("plain words", false)).template!!
        assertEquals("", bare.contentType)
        assertTrue((assembled(bare) as Assembled.Ready).headers.isEmpty())
    }

    @Test fun noBodyMeansNoBodyInTheReplay() {
        val t = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.None).template!!
        val ready = assembled(t) as Assembled.Ready
        val js = MtsLinkScripts.fetchScript(t.method, ready.url, ready.headers, ready.body)
        assertTrue(js, js.contains("\"kind\":\"none\""))
    }

    @Test fun aFileOrBinaryBodyIsNoTemplateAndSaysWhy() {
        for (why in listOf("a file part (doc) in a multipart form", "a Blob body", "a binary (ArrayBuffer) body")) {
            val r = learn("https://gw.mts-link.ru/api/events", emptyList(), Body.None, why = why)
            assertTrue(r.madeByApp)
            assertNull(r.template)
            assertEquals("the request carries $why", r.why)
        }
    }

    @Test fun theHooksJsonCopyIsRead() {
        val text = "{\"kind\":\"multipart\",\"fields\":[[\"a\",\"1\"],[\"b\",\"@cookie(c)\"]],\"url\":\"https://x/y\",\"headers\":[[\"Accept\",\"*/*\"],[\"Authorization\",\"Bearer @cookie(t)\"]]}"
        val copy = MtsRequest.parseCopy(text)!!
        assertEquals("https://x/y", copy.url)
        assertEquals(Body.Multipart(listOf("a" to "1", "b" to "@cookie(c)")), copy.body)
        assertEquals(listOf("Accept" to "*/*", "Authorization" to "Bearer @cookie(t)"), copy.headers)
        assertNull(copy.unsupported)
        assertEquals(Body.Text("{}", true), MtsRequest.parseCopy("{\"kind\":\"text\",\"json\":true,\"text\":\"{}\",\"url\":\"u\",\"headers\":[]}")!!.body)
        assertEquals(Body.Form(listOf("k" to "v")), MtsRequest.parseCopy("{\"kind\":\"form\",\"fields\":[[\"k\",\"v\"]],\"url\":\"u\",\"headers\":[]}")!!.body)
        assertEquals(Body.None, MtsRequest.parseCopy("{\"kind\":\"none\",\"url\":\"u\",\"headers\":[]}")!!.body)
        assertEquals("a Blob body", MtsRequest.parseCopy("{\"kind\":\"unsupported\",\"why\":\"a Blob body\",\"url\":\"u\",\"headers\":[]}")!!.unsupported)
        for (bad in listOf(null, "", "not json", "[]")) assertNull(bad, MtsRequest.parseCopy(bad))
    }

    // MARK: what is kept

    @Test fun everyBodyKindRoundTripsThroughTheKeptText() {
        for (body in listOf(Body.None, Body.Text("{\"a\":\"@cookie(c)\"}", true), Body.Text("x y", false), Body.Form(listOf("a" to "1", "b" to "é")), Body.Multipart(listOf("a" to "1")))) {
            val t = learn("https://gw.mts-link.ru/api/events/$uuid", listOf("Accept" to "*/*"), body).template!!
            assertEquals(t, MtsLearn.decode(MtsLearn.encode(t)))
        }
    }

    @Test fun anOlderKeptTemplateWithAScrubbedBodyIsNotRead() {
        val v1 = "{\"v\":1,\"method\":\"POST\",\"url\":\"https://gw.mts-link.ru/api/events\",\"contentType\":\"application/json\"," +
            "\"body\":\"{\\\"id\\\":\\\"<key>\\\"}\",\"headers\":[],\"result\":\"event\",\"path\":[\"data\",\"id\"],\"owner\":\"1\",\"learnedAt\":1}"
        assertNull(MtsLearn.decode(v1))
    }

    @Test fun theDisplayCopyNeverCarriesTheUnscrubbedOne() {
        val raw = MtsRequest.Copy("https://gw.mts-link.ru/api/events/$uuid", listOf("X-Id" to uuid), Body.Text("{\"r\":\"$uuid\"}", true), null)
        val entry = WebCapture.answered(WebCapture.entry("POST", "https://gw.mts-link.ru/api/events/$uuid", mapOf("X-Id" to uuid), "{\"r\":\"$uuid\"}", 1, 1L, raw), 201, answer, 2L)
        assertNotNull(entry.raw)
        assertFalse(entry.dump().contains(uuid))
        val buffer = WebCapture.Buffer()
        buffer.record("POST", "https://gw.mts-link.ru/api/events/$uuid", mapOf("X-Id" to uuid), "{\"r\":\"$uuid\"}", 1, 1L, raw)
        assertFalse(buffer.dump().contains(uuid))
        assertEquals(raw, buffer.entries().single().raw)
    }
}
