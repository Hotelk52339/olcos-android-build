package io.olcos.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.olcos.app.AppGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * "Make a room" for MTS Link, silently: the request the app learned from the
 * owner's own lesson (MtsLearn.Template), sent from inside the signed-in
 * page. An offscreen web view (the global CookieManager, so it shares the
 * sign-in sheet's jar) loads my.mts-link.ru; the page hook records, in the
 * page's memory only, the credential headers the page itself sends; the
 * headers are rebuilt from where the original's came from; the request is a
 * `fetch` in the page; the answer comes back over a bridge, within 25
 * seconds. A credential the original had but cannot be rebuilt aborts the
 * replay: nothing is sent without it. Every step writes one scrubbed line to
 * the log, with names and statuses and never a value.
 */
object MtsLinkReplay {
    private const val TAG = "olcos.mtsreplay"
    private const val HOME = "https://my.mts-link.ru/"
    private const val LOAD_MS = 20_000L
    private const val SEEN_WAIT_MS = 6_000L
    private const val POLL_MS = 300L
    private const val ANSWER_MS = 25_000L

    sealed class Result {
        data class Made(val link: String) : Result()

        /** The page is a sign-in page, or the service refused the account: the saved sign-in is dead. */
        data object SignedOut : Result()

        /** A credential the original request had (a header's, or a cookie / storage value its URL or body used) could not be rebuilt in the page. */
        data class MissingToken(val header: String) : Result()

        /** Anything else; [reason] is what the log has. */
        data class Failed(val reason: String) : Result()
    }

    /** Answers from the page's own script, on one of the web view's threads. */
    private class Bridge {
        val answer = CompletableDeferred<Pair<Int, String>>()

        @JavascriptInterface
        fun done(status: Int, text: String) {
            answer.complete(status to text)
        }

        @JavascriptInterface
        fun failed(message: String) {
            answer.complete(-1 to message)
        }
    }

    /** A new meeting made with [template]; [current] is the room being replaced, which the answer must not be. */
    suspend fun make(template: MtsLearn.Template, current: String): Result {
        val context = AppGraph.appContext ?: return Result.Failed("the app has no context").also { AppLog.w(TAG, "replay: ${it.reason}") }
        AppLog.i(TAG, "replay: start ${template.shape()} (result ${template.kind.name.lowercase()} at '${MtsLearn.pathText(template.path)}')")
        val result = withContext(Dispatchers.Main) { run(context, template, current) }
        when (result) {
            is Result.Made -> AppLog.i(TAG, "replay: a new meeting was made")
            Result.SignedOut -> AppLog.w(TAG, "replay: signed out")
            is Result.MissingToken -> AppLog.w(TAG, "replay: no token for ${result.header.lowercase()}, nothing was sent")
            is Result.Failed -> AppLog.w(TAG, "replay: failed: ${result.reason}")
        }
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun run(context: Context, template: MtsLearn.Template, current: String): Result {
        val view = WebView(context)
        val bridge = Bridge()
        val loaded = CompletableDeferred<String>()
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            // The same identity as the sign-in sheet's, so the site sees one client in both.
            view.settings.userAgentString = MtsLinkDesktop.USER_AGENT
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.addJavascriptInterface(bridge, "olcosReplay")
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    v.evaluateJavascript(MtsLinkDesktop.SHIM, null)
                    v.evaluateJavascript(MtsLinkScripts.HOOK, null)
                }
                override fun onPageCommitVisible(v: WebView, url: String) {
                    v.evaluateJavascript(MtsLinkDesktop.SHIM, null)
                    v.evaluateJavascript(MtsLinkScripts.HOOK, null)
                }
                override fun onPageFinished(v: WebView, url: String) {
                    v.evaluateJavascript(MtsLinkDesktop.SHIM, null)
                    v.evaluateJavascript(MtsLinkScripts.HOOK, null)
                    loaded.complete(url)
                }
                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loaded.complete("error")
                }
            }
            view.loadUrl(HOME)
            val first = withTimeoutOrNull(LOAD_MS) { loaded.await() }
                ?: return Result.Failed("the page did not load in ${LOAD_MS / 1000} s")
            if (first == "error") return Result.Failed("the page could not be loaded")

            // The page's own calls fill window.__olcosSeen for a little while after it loads.
            val needs = MtsLearn.needs(template)
            val deadline = System.currentTimeMillis() + SEEN_WAIT_MS
            var assembled: MtsLearn.Assembled = MtsLearn.Assembled.Missing("?")
            var settled = false
            while (true) {
                val got = MtsLearn.parseCollected(view.eval(MtsLinkScripts.collectScript(needs)))
                if (got != null) {
                    if (MtsLearn.isSignInUrl(got.href)) {
                        AppLog.w(TAG, "replay: the page is a sign-in page (${hostAndPath(got.href)})")
                        return Result.SignedOut
                    }
                    assembled = MtsLearn.assemble(template, got.cookies, got.local, got.session, got.seen)
                    if (assembled is MtsLearn.Assembled.Ready) {
                        // A page that has not redirected to a sign-in by now will not: send.
                        if (settled || needs.seen.isEmpty()) break
                    }
                    settled = true
                }
                if (System.currentTimeMillis() >= deadline) break
                delay(POLL_MS)
            }
            val ready = when (val a = assembled) {
                is MtsLearn.Assembled.Ready -> a
                is MtsLearn.Assembled.Missing -> return Result.MissingToken(a.header)
            }
            AppLog.i(TAG, "replay: token sources resolved: " + ready.resolved.ifEmpty { listOf("(none needed)") }.joinToString(", "))

            view.evaluateJavascript(MtsLinkScripts.fetchScript(template.method, ready.url, ready.headers, ready.body), null)
            val (status, text) = withTimeoutOrNull(ANSWER_MS) { bridge.answer.await() }
                ?: return Result.Failed("no answer in ${ANSWER_MS / 1000} s")
            if (status < 0) return Result.Failed("script error: " + WebCapture.scrub(text).take(200))
            AppLog.i(TAG, "replay: HTTP $status: " + WebCapture.scrub(text).take(300).replace('\n', ' ').ifEmpty { "(empty)" })
            return when (val answer = MtsLearn.readAnswer(template, status, text, current)) {
                is MtsLearn.Answer.Made -> {
                    AppLog.i(TAG, "replay: event id found at '${MtsLearn.pathText(template.path).ifEmpty { "(whole text)" }}'")
                    Result.Made(answer.link)
                }
                MtsLearn.Answer.SignedOut -> Result.SignedOut
                is MtsLearn.Answer.Failed -> Result.Failed(answer.reason)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching {
                view.stopLoading()
                view.removeJavascriptInterface("olcosReplay")
                view.loadUrl("about:blank")
                view.destroy()
            }
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    private fun hostAndPath(url: String): String = url.substringBefore('?').substringBefore('#')

    /** `evaluateJavascript` as a suspend call: the value comes back JSON-encoded on the main thread. */
    private suspend fun WebView.eval(script: String): String? = suspendCancellableCoroutine { c ->
        evaluateJavascript(script) { c.resume(it) }
    }
}
