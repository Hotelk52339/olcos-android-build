package io.olcos.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.olcos.app.R
import io.olcos.app.core.RoomServices
import io.olcos.app.data.VKSession
import io.olcos.app.data.WBSession
import io.olcos.app.data.WebSessions
import io.olcos.app.data.YandexSession
import kotlinx.coroutines.delay

private const val VK_PAGE = "https://vk.ru/"
private const val WB_PAGE = "https://stream.wb.ru/login"

/**
 * The Yandex sign-in, once, in a real web view: passport with a return path
 * to Telemost, so a finished sign-in lands on the domain the cookie is scoped
 * to. The `Session_id` cookie (and its `sessionid2` twin) is adopted only
 * once the page has left passport, like the iOS YandexLoginView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YandexLoginScreen(onSession: (session: String, twin: String?) -> Unit, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    var currentURL by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        while (!done) {
            delay(500)
            if (currentURL.isNotEmpty() && !currentURL.contains("passport.yandex.", ignoreCase = true)) {
                val jar = CookieManager.getInstance().getCookie("https://yandex.ru")
                val session = YandexSession.cookieValue(jar, "Session_id")
                if (session != null) {
                    done = true
                    onSession(session, YandexSession.cookieValue(jar, "sessionid2"))
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        WebHeader(stringResource(R.string.yandex_login_title), p, onBack)
        Text(stringResource(R.string.yandex_login_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            currentURL = url
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            currentURL = url
                        }
                    }
                    loadUrl("https://passport.yandex.ru/auth?retpath=https%3A%2F%2Ftelemost.yandex.ru%2F")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The VK sign-in, once, in a real web view: the account's own VK, as the
 * desktop page. The page's whole vk.ru cookie jar is what VK is asked about
 * (its own web client sends the jar, not `remixsid` alone), and it is
 * adopted only once VK itself accepts it — a guest cookie appears before any
 * password is typed. A refused jar is not asked about again until it changes,
 * which is exactly what a real sign-in does to it, and no jar is asked about
 * more often than every few seconds — like the iOS VKLoginView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VKLoginScreen(onSession: (credential: String) -> Unit, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    var done by remember { mutableStateOf(false) }
    // The jar the page holds right now, null while it holds no session cookie; it drives the status line.
    var pageJar by remember { mutableStateOf<String?>(null) }
    // The jar VK last refused (VKSession.refusalKey); not asked about again until it CHANGES.
    var refusedKey by remember { mutableStateOf<String?>(null) }
    // Bumped by "Sign out of VK": the poll starts over, which also drops an answer still on its way for the jar just removed.
    var round by remember { mutableIntStateOf(0) }
    val page = remember { PageRef() }

    BackHandler(onBack = onBack)
    LaunchedEffect(round) {
        // When the last attempt ended. The loop waits for each answer, so verifications never stack.
        var lastAttempt: Long? = null
        while (!done) {
            delay(500)
            // No URL gate here: vk.ru's front page IS the login form, so the
            // URL never tells the sign-in state. The jar, verified by VK, is
            // the only signal.
            val jar = VKSession.jar(CookieManager.getInstance().getCookie(VKSession.JAR_URL))
            pageJar = jar
            if (jar == null) continue
            if (!VKSession.shouldVerify(jar, refusedKey, lastAttempt, SystemClock.elapsedRealtime())) continue
            val verdict = RoomServices.VKCall.verifySession(jar)
            lastAttempt = SystemClock.elapsedRealtime()
            when (verdict) {
                RoomServices.VKCall.Verdict.Accepted -> {
                    done = true
                    onSession(jar)
                }
                is RoomServices.VKCall.Verdict.Refusal -> refusedKey = VKSession.refusalKey(jar)
                // No verdict on the jar: nothing is remembered, so it is asked about again after the gap.
                RoomServices.VKCall.Verdict.NoAnswer -> {}
            }
        }
    }

    val status = VKSession.signInStatus(pageJar, refusedKey)
    Column(Modifier.fillMaxSize().background(p.background)) {
        WebHeader(stringResource(R.string.vk_login_title), p, onBack)
        Text(stringResource(R.string.vk_login_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Text(
            stringResource(when (status) {
                VKSession.SignInStatus.NO_SESSION -> R.string.vk_login_status_none
                VKSession.SignInStatus.CHECKING -> R.string.vk_login_status_checking
                VKSession.SignInStatus.REFUSED -> R.string.vk_login_status_refused
            }),
            style = MaterialTheme.typography.bodySmall,
            color = if (status == VKSession.SignInStatus.REFUSED) p.orange else p.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            // The page's own sign-in survives the app forgetting it, so this clears the web view's vk.ru cookies and page storage and starts the page over.
            ActionButton(stringResource(R.string.vk_login_sign_out), p, primary = status == VKSession.SignInStatus.REFUSED) {
                if (!done) {
                    WebSessions.clearVK()
                    refusedKey = null
                    pageJar = null
                    round += 1
                    page.view?.loadUrl(VK_PAGE)
                }
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = RoomServices.BROWSER_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    // Only to keep the sign-in's navigations inside the view; the URL says nothing about the sign-in state.
                    webViewClient = WebViewClient()
                    page.view = this
                    loadUrl(VK_PAGE)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The WB Stream sign-in, once, in a real web view: the stream page's own
 * login. The service keeps its token in the `Authorization: Bearer` header
 * more reliably than in a cookie, so an injected script reports the headers
 * the page itself sends, and the cookie remains the fallback. Either is
 * adopted only once the page has left the login form — a guest token has the
 * same shape as an account one, so the URL is the only gate there is.
 * Entering a room by hand stays one tap away.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WBLoginScreen(onSession: (token: String) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    var currentURL by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    // What the injected script reports; the web view calls it on one of its own threads.
    val bearer = remember { BearerReports() }
    val page = remember { PageRef() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        while (!done) {
            delay(500)
            if (!RoomServices.WBStream.pastLogin(currentURL)) continue
            val header = bearer.latest()
            // The whole header arrives ("Bearer <token>"); only the prefix goes.
            val fromHeader = when {
                header == null -> null
                header.startsWith("Bearer ") -> header.substring(7)
                else -> header
            }
            val token = fromHeader ?: WBSession.cookieValue { CookieManager.getInstance().getCookie(it) }
            if (token != null) {
                done = true
                onSession(token)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        WebHeader(stringResource(R.string.wb_login_title), p, onBack)
        Text(stringResource(R.string.wb_login_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            ActionButton(stringResource(R.string.wb_enter_manually), p, primary = false) {
                if (!done) { done = true; onManual() }
            }
            Spacer(Modifier.height(8.dp))
            // The page's own sign-in survives the app forgetting it, so this clears the web view's WB cookies and storage and starts the page over.
            ActionButton(stringResource(R.string.wb_login_sign_out), p, primary = false) {
                if (!done) {
                    WebSessions.clearWB()
                    bearer.reset()
                    currentURL = ""
                    page.view?.loadUrl(WB_PAGE)
                }
            }
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = RoomServices.BROWSER_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(bearer, "olcosWBBearer")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            currentURL = url
                            if (RoomServices.WBStream.pastLogin(url)) bearer.pastLogin = true
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            currentURL = url
                            if (RoomServices.WBStream.pastLogin(url)) bearer.pastLogin = true
                            // The hook lives in one document only; every page
                            // load puts it back before the SPA's own calls run.
                            view.evaluateJavascript(BEARER_HOOK, null)
                        }
                    }
                    page.view = this
                    loadUrl(WB_PAGE)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Puts the `Authorization` headers the page sends into native hands: fetch
 * and `XMLHttpRequest.setRequestHeader` are wrapped once per document, and
 * each distinct value is reported at most once. Idempotent, so re-running
 * it on every page load is safe.
 */
private const val BEARER_HOOK = """
(function() {
  if (window.__wbBearerHooked) return;
  window.__wbBearerHooked = true;
  var send = function(value) {
    if (!value) return;
    if (window.__wbLastBearer === value) return;
    window.__wbLastBearer = value;
    try { window.olcosWBBearer.report(value); } catch (e) {}
  };
  var readHeaders = function(h) {
    try {
      if (!h) return;
      if (typeof h.get === 'function') { send(h.get('Authorization')); return; }
      send(h['Authorization'] || h['authorization']);
    } catch (e) {}
  };
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init) {
      try { readHeaders(init && init.headers); } catch (e) {}
      try { if (input && input.headers) readHeaders(input.headers); } catch (e) {}
      return of.apply(this, arguments);
    };
  }
  var origSet = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    try {
      if (String(name).toLowerCase() === 'authorization') send(String(value));
    } catch (e) {}
    return origSet.apply(this, arguments);
  };
})();
"""

/**
 * The bridge the injected script reports header values over. The web view
 * calls it on one of its own threads, so the value is volatile; a value that
 * arrived before the page left the login form is a guest token, and is
 * dropped rather than kept.
 */
private class BearerReports {
    /** Set once the page has left the login form: from then on, the headers the page sends are the account's own. */
    @Volatile var pastLogin = false
    @Volatile private var reported: String? = null

    @JavascriptInterface
    fun report(header: String) {
        if (pastLogin) reported = header
    }

    fun latest(): String? = reported

    /** Forgets what the page reported: a signed-out page starts again from the login form. */
    fun reset() {
        pastLogin = false
        reported = null
    }
}

/** The web view a sign-in page shows, for the actions that start it over. */
private class PageRef {
    var view: WebView? = null
}

@Composable
private fun WebHeader(title: String, @Suppress("UNUSED_PARAMETER") p: OlcosPalette, onBack: () -> Unit) {
    OlcTopBar(title, onBack = onBack, backLabel = stringResource(R.string.action_back))
}
