package io.olcos.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * The rooms the phone mints itself, as the iOS app does: a SaluteJazz meeting
 * (one anonymous POST), a Telemost conference (one POST with the Yandex
 * session cookie), a VK call (two POSTs with the VK session cookie), a WB
 * Stream room (one POST with the account token), and the shapes a typed or
 * pasted room must take. The requests go straight out: this app's own traffic
 * never rides the tunnel.
 */
object RoomServices {
    private const val LOG_TAG = "olcos.rooms"

    class Refused(val status: Int, val body: String = "") : IOException("HTTP $status")
    /** The saved sign-in the request rode on was refused: sign in again. */
    class SessionRejected : IOException("the saved session was rejected")

    /** The desktop browser the requests and the sign-in pages present themselves as. */
    const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    /** `<code>:<password>`, or a SaluteJazz link with the code and its `psw`. */
    object SaluteJazz {
        private const val CREATE_URL = "https://bk.salutejazz.ru/room/create-meeting"
        private const val ORIGIN = "https://salutejazz.ru"
        private const val USER_AGENT = BROWSER_UA
        private const val BODY = "{\"title\":\"Видеовстреча\",\"guestEnabled\":true,\"lobbyEnabled\":false," +
            "\"serverVideoRecordAutoStartEnabled\":false,\"sipEnabled\":false,\"moderatorEmails\":[]," +
            "\"summarizationEnabled\":false,\"interpretation\":null,\"room3dEnabled\":false,\"room3dScene\":\"XRLobby\"}"

        suspend fun create(): String = withContext(Dispatchers.IO) {
            val answer = post(CREATE_URL, BODY, mapOf("Origin" to ORIGIN, "User-Agent" to USER_AGENT))
            val json = JSONObject(answer)
            val code = json.keys().asSequence().firstOrNull { it.equals("roomid", ignoreCase = true) }?.let { json.optString(it) }
            val password = json.keys().asSequence().firstOrNull { it.equals("password", ignoreCase = true) }?.let { json.optString(it) }
            if (code.isNullOrEmpty() || password.isNullOrEmpty()) throw unreadable(answer)
            normalized("$code:$password") ?: throw unreadable(answer)
        }

        /** What a person pastes → `<code>:<password>`, or null. */
        fun normalized(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            if (text.contains("://") || text.contains("salutejazz.ru/")) {
                val code = Regex("/meet/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
                val psw = Regex("[?&](?:psw|password)=([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
                return if (code != null && psw != null) "$code:$psw" else null
            }
            val parts = text.split(':')
            if (parts.size != 2 || parts.any { it.isEmpty() || !it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }) return null
            return text
        }
    }

    /** A Telemost conference; the bare id is what the server takes in `room.id`. */
    object Telemost {
        private const val CREATE_URL = "https://cloud-api.yandex.ru/telemost_front/v2/telemost/conferences?next_gen_media_platform_allowed=true"
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0"
        private const val CLIENT_VERSION = "187.1.0"
        private const val ORIGIN = "https://telemost.yandex.ru"
        private const val REFERER = "https://telemost.yandex.ru/"

        /** The new room's id. `session` is the raw `Session_id` value, `twin` the `sessionid2` one. */
        suspend fun create(session: String, twin: String?): String = withContext(Dispatchers.IO) {
            val cookie = "Session_id=$session" + (if (!twin.isNullOrEmpty()) "; sessionid2=$twin" else "")
            val answer = post(CREATE_URL, "{}", mapOf(
                "Cookie" to cookie, "User-Agent" to USER_AGENT, "Accept" to "*/*",
                "Origin" to ORIGIN, "Referer" to REFERER, "X-Telemost-Client-Version" to CLIENT_VERSION,
                "Client-Instance-Id" to UUID.randomUUID().toString(), "Idempotency-Key" to UUID.randomUUID().toString(),
            ))
            val uri = JSONObject(answer).optString("uri")
            roomID(uri) ?: throw unreadable(uri)
        }

        /** `https://telemost.yandex.ru/j/1234567890[/?...]` → `1234567890`. */
        fun roomID(uri: String): String? {
            val marker = uri.indexOf("/j/")
            if (marker < 0) return null
            val id = uri.substring(marker + 3).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return id.ifEmpty { null }
        }

        /** A link, a link without a scheme, or the id with grouping spaces → the bare id. */
        fun normalizedInput(raw: String): String = roomID(raw) ?: raw.filterNot { it.isWhitespace() }
    }

    /**
     * `https://vk.ru/call/join/<id>`, minted with the owner's own VK session
     * (the vk.ru cookie jar, `remixsid` among it): the web token the VK web
     * client takes, then a call of the account's own. Both services answer
     * their refusals as HTTP 200 with an envelope of their own, so the body —
     * never the status — says what happened. The endpoints are VK's own
     * web-client API — unofficial, and they may change; a failure is
     * reported, never papered over.
     */
    object VKCall {
        private const val TOKEN_URL = "https://login.vk.ru/?act=web_token"
        private const val START_URL = "https://api.vk.ru/method/calls.start"
        private const val API_VERSION = "5.282"
        private const val APP_ID = "6287487"
        private const val ORIGIN = "https://vk.ru"
        private const val REFERER = "https://vk.ru/"
        private val pattern = Regex("(https?://)?(m\\.)?vk\\.(com|ru)/call/join/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        /** The only shape of refusal reason a log line carries: a short run of lowercase words. */
        private val plainReason = Regex("[a-z][a-z _-]{0,39}")

        fun normalized(input: String): String? {
            val match = pattern.find(input.trim()) ?: return null
            val host = "vk." + match.groupValues[3].lowercase()
            return "https://$host/call/join/${match.groupValues[4]}"
        }

        /** A bare call id → `https://vk.ru/call/join/<id>`, or null for anything else. */
        fun linkFromID(id: String): String? {
            val bare = id.trim()
            if (bare.isEmpty() || !bare.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }) return null
            return "https://vk.ru/call/join/$bare"
        }

        /** The new room's link. `credential` is the saved VK sign-in: the vk.ru jar, or the bare `remixsid` value an earlier build kept. */
        suspend fun create(credential: String): String = withContext(Dispatchers.IO) {
            val tokenAnswer = webToken(credential)
            // The login service answers its refusals as HTTP 200 with its own
            // envelope: "unauthorized" is the missing- or guest-cookie answer,
            // and a retry cannot fix a cookie — sign in again. Any other
            // reason is named in the log and stays an unreadable answer.
            webTokenRefusal(tokenAnswer)?.let { refusal ->
                if (refusal == "unauthorized") throw SessionRejected()
                AppLog.w(LOG_TAG, "web_token refused the exchange: $refusal")
                throw IOException("unreadable answer")
            }
            val token = accessToken(tokenAnswer) ?: throw SessionRejected()
            val answer = callStart(token)
            // The method API answers its errors as HTTP 200 as well; the code
            // says which kind of failure it is.
            errorCode(answer)?.let { throw errorFor(it) }
            createdRoom(answer) ?: throw unreadable(answer)
        }

        /** What VK says to a credential. */
        sealed class Verdict {
            /** A real access token came back: the credential is a live session. */
            data object Accepted : Verdict()

            /** VK refused it; `reason` is the `error_info` it gave. Signing in again is the only cure. */
            data class Refusal(val reason: String) : Verdict()

            /** Nothing readable came back (the network, a hiccup, an answer in another shape): no verdict on the credential at all. */
            data object NoAnswer : Verdict()
        }

        /**
         * One web_token exchange with no side effects, and what VK made of
         * it. The sign-in sheet verifies a captured jar with this before it
         * hands it up — a guest cookie looks the same in the jar, and only VK
         * can tell them apart — and the daily check asks it of the saved one.
         * One diagnostics line per verdict ([verdictLine]); the credential
         * never reaches the log.
         */
        suspend fun verifySession(credential: String): Verdict = withContext(Dispatchers.IO) {
            val outcome = try {
                verdictOf(webToken(credential))
            } catch (e: SessionRejected) {
                // Not an answer the login service is seen to give, but if it ever does, it is a no.
                Verdict.Refusal("http unauthorized")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Verdict.NoAnswer
            }
            val line = verdictLine(outcome)
            if (outcome == Verdict.Accepted) AppLog.i(LOG_TAG, line) else AppLog.w(LOG_TAG, line)
            outcome
        }

        /**
         * PURE: the verdict a web_token answer carries. The login service
         * answers its refusals as HTTP 200 with an envelope of its own, so
         * only the body says: an `error` type is a refusal (its `error_info`
         * the reason), a non-empty `access_token` an acceptance, and
         * anything else — not JSON, no token, another envelope — is silence.
         */
        fun verdictOf(answer: String): Verdict {
            val refusal = webTokenRefusal(answer)
            if (refusal != null) return Verdict.Refusal(refusal)
            if (field(answer, "type") == "error") return Verdict.Refusal("unknown")
            return if (accessToken(answer).isNullOrEmpty()) Verdict.NoAnswer else Verdict.Accepted
        }

        /**
         * PURE: the one diagnostics line a verdict writes. The reason is
         * VK's own `error_info`, shown only when it is a short lowercase
         * phrase (`unauthorized`, `wrong origin`): nothing shaped like a
         * cookie or a token can ride into the log on it. The credential
         * itself is never part of a line.
         */
        fun verdictLine(verdict: Verdict): String = when (verdict) {
            Verdict.Accepted -> "VK sign-in check: accepted"
            is Verdict.Refusal -> "VK sign-in check: refused (" + (if (plainReason.matches(verdict.reason)) verdict.reason else "other") + ")"
            Verdict.NoAnswer -> "VK sign-in check: no answer"
        }

        /**
         * The `Cookie` header a saved credential rides on. VK's web client
         * sends its whole jar, so a jar (it has an `=`) goes out as it is; a
         * bare value — all that earlier builds kept — is the `remixsid`
         * alone and gains its name here, in the one place that builds the
         * header.
         */
        fun cookieHeader(credential: String): String =
            if (credential.contains('=')) credential else "remixsid=$credential"

        /** The `access_token` of a web_token answer. */
        fun accessToken(answer: String): String? = field(answer, "access_token")

        /** `join_link`, falling back to `ok_join_link`, as the answer carries it. */
        fun joinLink(answer: String): String? = field(answer, "join_link") ?: field(answer, "ok_join_link")

        /** What `calls.start` answered → the normalized link (a bare id becomes one), or null. */
        fun createdRoom(answer: String): String? {
            val value = joinLink(answer) ?: return null
            return normalized(value) ?: linkFromID(value)
        }

        /**
         * The `error_info` of a web_token refusal, or null when the answer is
         * not one: the login service answers its refusals as HTTP 200 with
         * `{"type":"error","error_code":"","error_info":"…"}` — "unauthorized"
         * for a missing or guest cookie, "wrong origin" for a missing Origin
         * header. Read with `field`, like every other answer here: org.json is
         * not on the JVM unit-test classpath.
         */
        fun webTokenRefusal(answer: String): String? {
            if (field(answer, "type") != "error") return null
            return field(answer, "error_info")?.trim()?.ifEmpty { null }
        }

        /** The `error_code` of a method-API envelope, or null when the answer is not one — the method API answers its errors as HTTP 200 too. The web_token refusal's `error_code` is an empty string, so only a NUMBER is a method error. */
        fun errorCode(answer: String): Int? = field(answer, "error_code")?.toIntOrNull()

        /**
         * A method-API error code as the failure the caller acts on: 5 is the
         * dead-session answer (sign in again); 6/9/14/29 are the flood and
         * captcha gates, where waiting is all the app can offer; 15 ("token
         * required") means the token did not get attached — a bug of ours, not
         * a sign-in — and everything else is reported by its code.
         */
        fun errorFor(code: Int): IOException = when (code) {
            5 -> SessionRejected()
            6, 9, 14, 29 -> Refused(429)
            else -> Refused(code)
        }

        /** The `calls.start` form: version only. The production reference sends no `peer_id`, and the account id already rides the web token. */
        fun startForm(): List<Pair<String, String>> = listOf("v" to API_VERSION)

        private fun webToken(credential: String): String = form(
            TOKEN_URL,
            listOf("version" to "1", "app_id" to APP_ID),
            mapOf("Cookie" to cookieHeader(credential), "Origin" to ORIGIN, "Referer" to REFERER, "User-Agent" to BROWSER_UA),
        )

        private fun callStart(token: String): String = form(START_URL, startForm(), headers(token))

        private fun headers(token: String): Map<String, String> =
            mapOf("Authorization" to "Bearer $token", "Origin" to ORIGIN, "Referer" to REFERER, "User-Agent" to BROWSER_UA)
    }

    /**
     * The room id of a WB Stream broadcast, made with the owner's own account
     * (the `wb_access_token` cookie). The same token goes to the server as
     * `auth.token`, so the room is the account's own and not a guest's.
     */
    object WBStream {
        private const val CREATE_URL = "https://stream.wb.ru/api-room/api/v2/room"
        private const val USER_INFO_URL = "https://stream.wb.ru/auth/api/v1/user/info"
        private const val ORIGIN = "https://stream.wb.ru"
        private const val REFERER = "https://stream.wb.ru/"
        private const val BODY = "{\"roomType\":\"ROOM_TYPE_ALL_ON_SCREEN\",\"roomPrivacy\":\"ROOM_PRIVACY_FREE\"}"

        /** What a probe of the saved token says: alive, dead, or not an answer (the network, a hiccup). */
        enum class SessionState { ALIVE, DEAD, UNKNOWN }

        /** The new room's id. `token` is the raw `wb_access_token` value. */
        suspend fun create(token: String): String = withContext(Dispatchers.IO) {
            try {
                val answer = post(CREATE_URL, BODY, mapOf(
                    "Authorization" to "Bearer $token",
                    "Origin" to ORIGIN, "Referer" to REFERER, "User-Agent" to BROWSER_UA,
                ))
                roomID(answer) ?: throw unreadable(answer)
            } catch (e: Refused) {
                // A guest token (or an account the service bars from making
                // rooms) is told apart only by the body: retrying the same
                // token cannot fix it, so it reads as the sign-in it is.
                if (guestRefusal(e.status, e.body)) throw SessionRejected()
                throw e
            }
        }

        /**
         * PURE: whether a 400/403 body is the guest/permission refusal —
         * the account behind the token may not make rooms, and no retry
         * fixes that. The create gate answers 400 with code 3 ("Guests are
         * not allowed to create room"); the same refusal on the join path
         * is a 403 with code 7. Read with `field`, like every other answer
         * here: org.json is not on the JVM unit-test classpath.
         */
        fun guestRefusal(status: Int, body: String): Boolean {
            if (status != 400 && status != 403) return false
            val code = field(body, "code")?.toIntOrNull() ?: return false
            val message = field(body, "message")?.lowercase() ?: ""
            return code == 7 || (code == 3 && "guest" in message)
        }

        /**
         * One cheap GET with the account's token (`user/info`): the status is
         * all that is read — 200 the token lives, 401 it is dead, anything
         * else not an answer. The token itself never reaches the log.
         */
        suspend fun checkSession(token: String): SessionState = withContext(Dispatchers.IO) {
            val connection = (URL(USER_INFO_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 20_000
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            try {
                sessionState(connection.responseCode)
            } catch (e: Exception) {
                AppLog.w(LOG_TAG, "user/info did not answer: ${e.message}")
                SessionState.UNKNOWN
            } finally {
                connection.disconnect()
            }
        }

        /** PURE: the status the probe maps to. */
        fun sessionState(status: Int): SessionState = when (status) {
            in 200..299 -> SessionState.ALIVE
            401 -> SessionState.DEAD
            else -> SessionState.UNKNOWN
        }

        /** `{"roomId":"…"}` → the room id. */
        fun roomID(answer: String): String? = field(answer, "roomId")

        /**
         * PURE: whether the page has left the login form — the stream host
         * itself, on a path other than `/login`. That is the one signal a
         * guest token cannot pass (the login form is where it is handed
         * out); a "login" anywhere else in the URL — a query, another path
         * segment — means nothing, and neither does any other host.
         */
        fun pastLogin(url: String): Boolean {
            if (url.isEmpty()) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (!uri.host.equals("stream.wb.ru", ignoreCase = true)) return false
            return !(uri.path ?: "").startsWith("/login")
        }
    }

    /** What a room field accepts, per carrier: the value the server gets, "" when unusable. */
    fun normalizedRoom(carrier: String, raw: String): String = when (carrier) {
        "telemost" -> Telemost.normalizedInput(raw)
        "vkcalls" -> VKCall.normalized(raw) ?: ""
        "salutejazz" -> SaluteJazz.normalized(raw) ?: ""
        else -> raw.filterNot { it.isWhitespace() }
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String =
        post(url, body, "application/json", headers)

    /** `application/x-www-form-urlencoded`, for the services that take a form. */
    private fun form(url: String, values: List<Pair<String, String>>, headers: Map<String, String>): String {
        val body = values.joinToString("&") { (name, value) ->
            URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return post(url, body, "application/x-www-form-urlencoded", headers)
    }

    private fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            useCaches = false
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", contentType)
            for ((name, value) in headers) setRequestProperty(name, value)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 401) throw SessionRejected()
            if (status !in 200..299) {
                // The refusal's body says which kind of refusal it is (WB's guest gate), so it travels with the exception.
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
                    .getOrDefault("")
                AppLog.w(LOG_TAG, "HTTP $status: " + errorBody.take(200).ifEmpty { "no body" })
                throw Refused(status, errorBody)
            }
            return connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One value out of a flat JSON answer, without a parser: the value of the
     * first `"name"` key, quoted or a number, with `\/` unescaped. Enough for
     * the flat answers these services give.
     */
    private fun field(json: String, name: String): String? {
        val quoted = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"")
        quoted.find(json)?.let { return it.groupValues[1].replace("\\/", "/") }
        val bare = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(-?\\d+)")
        return bare.find(json)?.groupValues?.get(1)
    }

    /**
     * The answer could not be read into a room. The person reads the
     * localized sentence (UserFacing); the answer's head stays in the log,
     * where it can be told apart from any other unreadable one.
     */
    private fun unreadable(answer: String): IOException {
        AppLog.w(LOG_TAG, "unreadable answer: " + answer.take(200).ifEmpty { "empty" })
        return IOException("unreadable answer")
    }
}
