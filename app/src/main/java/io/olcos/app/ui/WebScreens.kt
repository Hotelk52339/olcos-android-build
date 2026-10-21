package io.olcos.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.core.AppLog
import io.olcos.app.core.MtsLearn
import io.olcos.app.core.MtsLinkDesktop
import io.olcos.app.core.MtsLinkScripts
import io.olcos.app.core.MtsMeetingCheck
import io.olcos.app.core.MtsRequest
import io.olcos.app.core.RoomServices
import io.olcos.app.core.WebCapture
import io.olcos.app.data.MTSLinkSession
import io.olcos.app.data.VKSession
import io.olcos.app.data.WebSessions
import io.olcos.app.data.YandexSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val YANDEX_PAGE = "https://passport.yandex.ru/auth?retpath=https%3A%2F%2Ftelemost.yandex.ru%2F"
private const val VK_PAGE = "https://vk.ru/"

/**
 * The Yandex sign-in, once, in a real web view: passport with a return path
 * to Telemost, so a finished sign-in lands on the domain the cookie is scoped
 * to. The `Session_id` cookie (and its `sessionid2` twin) is adopted only
 * once the page has left passport, like the iOS YandexLoginView. The page
 * keeps its own copy of the sign-in in the web view, so "Sign out of Yandex"
 * clears that copy and starts passport over.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YandexLoginScreen(onSession: (session: String, twin: String?) -> Unit, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    var currentURL by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    val page = remember { PageRef() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        while (!done) {
            delay(500)
            if (currentURL.isNotEmpty() && !currentURL.contains("passport.yandex.", ignoreCase = true)) {
                val jar = CookieManager.getInstance().getCookie(YandexSession.JAR_URL)
                val session = YandexSession.cookieValue(jar, YandexSession.SESSION_COOKIE)
                if (session != null) {
                    done = true
                    onSession(session, YandexSession.cookieValue(jar, YandexSession.TWIN_COOKIE))
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        WebHeader(stringResource(R.string.yandex_login_title), p, onBack)
        Text(stringResource(R.string.yandex_login_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            // The page's own sign-in survives the app forgetting it, so this clears the web view's Yandex cookies and page storage and starts passport over.
            ActionButton(stringResource(R.string.yandex_login_sign_out), p, primary = false) {
                if (!done) {
                    WebSessions.clearYandex()
                    currentURL = ""
                    page.view?.loadUrl(YANDEX_PAGE)
                }
            }
        }
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
                    page.view = this
                    loadUrl(YANDEX_PAGE)
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

private const val MTS_PAGE = "https://my.mts-link.ru/"
private const val MTS_TAG = "olcos.mtslearn"

/** True when [url] is one the sheet's web view cannot open (the site wanted the MTS Link app): the navigation is cancelled, the page stays, one line goes to the log. */
private fun refuseLink(url: String): Boolean {
    val kind = MtsLinkDesktop.blockedLinkKind(url) ?: return false
    AppLog.i(MTS_TAG, "blocked $kind link (the site wanted the MTS Link app)")
    return true
}

/**
 * The MTS Link sign-in sheet, which is also where the app learns how a
 * meeting is made. The owner signs in (the whole my.mts-link.ru jar becomes
 * the saved sign-in when he taps "Use this account", the way the VK sheet
 * keeps its jar), creates a permanent meeting on the site himself, and ends
 * the lesson with the Finish button. Until then nothing is learned, saved,
 * returned or closed: the page hook only records the page's own calls and
 * addresses (WebCapture, MtsLearn.Sightings) and the bar says when a meeting
 * looks created. On Finish the app reads only what this sheet recorded
 * (MtsLearn.finish) and asks MTS Link about every meeting it found, the way
 * the core will (MtsMeetingCheck): only a permanent meeting with a session
 * is offered. It waits for "Use"; only that saves the template (when one
 * create call is unambiguous), hands the link to [onMeeting] and closes. A
 * quick meeting, no meeting or several save nothing. Cancel leaves with
 * nothing. What is recorded is scrubbed where it is kept and travels by
 * clipboard alone: nothing captured ever goes to a network.
 *
 * The page takes almost all of the screen: one address line on top (the
 * real host behind a lock, the sign-in state, ⓘ), one short line about the
 * sign-in, and one bar at the bottom (a line and its buttons). Everything
 * longer — the full texts, the account buttons, the paste field, what the
 * app saw — is behind ⓘ. [notice] is the line the sheet opens with when the
 * silent attempt could not make a usable room.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MTSLinkLoginScreen(
    onSession: (credential: String) -> Unit,
    onMeeting: ((link: String) -> Unit)?,
    onBack: () -> Unit,
    notice: Int? = null,
) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // The jar the page holds right now, null while it holds none: what "Use this account" would take.
    var pageJar by remember { mutableStateOf<String?>(null) }
    // The calls and pages of this sheet; the bridge reports on one of the web view's own threads.
    val capture = remember { WebCapture.Buffer() }
    val sightings = remember { MtsLearn.Sightings() }
    var captured by remember { mutableIntStateOf(0) }
    var showCapture by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<MtsMeetingCheck.Lesson?>(null) }
    var address by remember { mutableStateOf<MtsLinkDesktop.Address?>(MtsLinkDesktop.address(MTS_PAGE)) }
    val learned by AppGraph.mtsTemplate.learned.collectAsStateWithLifecycle()
    val signedIn by AppGraph.mts.signedIn.collectAsStateWithLifecycle()
    val page = remember { PageRef() }

    fun cancel() {
        AppLog.i(MTS_TAG, "lesson: cancelled, nothing learned")
        onBack()
    }

    fun finish() {
        if (checking) return
        val entries = capture.entries()
        val seen = sightings.all()
        val result = MtsLearn.finish(entries, seen, System.currentTimeMillis() / 1000)
        AppLog.i(MTS_TAG, "lesson: Finish pressed; ${entries.size} calls, ${seen.size} page sightings; ${describeResult(result)}")
        val asked = MtsMeetingCheck.candidates(result)
        if (asked.isEmpty()) {
            review = MtsMeetingCheck.Lesson.Ready(result)
            return
        }
        // Nothing is offered before MTS Link says the server can enter it.
        checking = true
        scope.launch {
            val lesson = MtsMeetingCheck.lesson(result, MtsMeetingCheck.checkAll(asked))
            AppLog.i(MTS_TAG, "lesson: " + describeLesson(lesson))
            checking = false
            review = lesson
        }
    }

    fun useMeeting(found: MtsLearn.Result.One) {
        val template = found.template
        if (template != null) {
            AppGraph.mtsTemplate.save(template)
            AppLog.i(MTS_TAG, "lesson: learned ${template.shape()}; ${describeTemplate(template)}")
            // A meeting made through the account proves the jar is the account's own.
            pageJar?.let {
                onSession(it)
                AppLog.i(MTS_TAG, "lesson: sign-in kept")
            }
            Toast.makeText(context, R.string.mts_learned, Toast.LENGTH_SHORT).show()
        } else {
            AppLog.i(MTS_TAG, "lesson: link used, nothing learned (${found.why ?: "no reason"})")
        }
        if (found.madeByApp) AppGraph.mtsMeetings.noteCreated(found.meeting.link)
        onMeeting?.invoke(found.meeting.link)
        onBack()
    }

    BackHandler {
        if (review != null) review = null else cancel()
    }
    LaunchedEffect(Unit) {
        AppLog.i(MTS_TAG, "lesson: sheet opened (" + (if (onMeeting != null) "for a room" else "from settings") + ", learned=" + learned + ")")
        var seenVersion = -1L
        while (true) {
            delay(500)
            val jar = MTSLinkSession.jar(CookieManager.getInstance().getCookie(MTSLinkSession.JAR_URL))
            if (jar != pageJar) pageJar = jar
            val count = capture.count()
            if (count != captured) captured = count
            val version = (capture.version().toLong() shl 32) or sightings.count().toLong()
            if (version != seenVersion) {
                seenVersion = version
                val looks = MtsLearn.looksCreated(capture.entries(), sightings.all())
                if (looks != created) {
                    created = looks
                    if (looks) AppLog.i(MTS_TAG, "lesson: a created meeting looks recorded (hint shown, nothing done)")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        // The address line: the page's real host (the guard against a page that only looks like MTS Link), the sign-in, ⓘ.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 40.dp).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val secure = address?.secure != false
            Icon(
                if (secure) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null,
                tint = if (secure) p.textSecondary else p.orange, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                address?.host ?: "", style = MaterialTheme.typography.labelLarge, color = p.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (checking) {
                Spacer(Modifier.width(8.dp))
                OlcSpinner(size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.mts_status_checking), style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 1)
            } else if (signedIn) {
                Text(
                    " · " + stringResource(R.string.mts_status_signed_in), style = MaterialTheme.typography.bodySmall, color = p.green,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.mts_info), tint = p.accentTint)
            }
        }
        // Seen before any password is typed: where the sign-in goes. The full text is behind ⓘ.
        Text(
            stringResource(R.string.mts_cookie_line), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // The site must take this for a desktop browser: its user agent, a wide viewport that can be pinched, no touch screen in the page.
                    settings.userAgentString = MtsLinkDesktop.USER_AGENT
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(CaptureReports(capture, sightings), "olcosCapture")
                    // The shim and the hook live in one document only; they go in as early as the view allows and again on every load.
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            AppLog.i(MTS_TAG, "page: " + MtsLinkDesktop.logTarget(url))
                            address = MtsLinkDesktop.address(url) ?: address
                            view.evaluateJavascript(MtsLinkDesktop.SHIM, null)
                            view.evaluateJavascript(MtsLinkScripts.HOOK, null)
                        }
                        override fun onPageCommitVisible(view: WebView, url: String) {
                            view.evaluateJavascript(MtsLinkDesktop.SHIM, null)
                            view.evaluateJavascript(MtsLinkScripts.HOOK, null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(MtsLinkDesktop.SHIM, null)
                            view.evaluateJavascript(MtsLinkScripts.HOOK, null)
                        }
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            address = MtsLinkDesktop.address(url) ?: address
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            refuseLink(request.url.toString())
                    }
                    webChromeClient = object : WebChromeClient() {
                        // The room may ask for the camera and the microphone; this sheet never joins a call with the phone's.
                        override fun onPermissionRequest(request: PermissionRequest) {
                            val what = request.resources.joinToString(",") { it.substringAfterLast('.') }
                            AppLog.i(MTS_TAG, "permission denied: $what (${MtsLinkDesktop.logTarget(request.origin.toString())})")
                            request.deny()
                        }
                        // window.open / target=_blank: the target loads in this view, as it would in a desktop tab.
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val carrier = WebView(view.context)
                            var handedOver = false
                            // The new window's first real address (a blank one is a script about to set it) goes to this view; the window is dropped.
                            val handOver = { v: WebView, target: String ->
                                if (!handedOver && !target.startsWith("about:")) {
                                    handedOver = true
                                    if (!refuseLink(target)) {
                                        AppLog.i(MTS_TAG, "new window → same view: " + MtsLinkDesktop.logTarget(target))
                                        view.loadUrl(target)
                                    }
                                    v.post { runCatching { v.destroy() } }
                                }
                            }
                            carrier.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                    val target = request.url.toString()
                                    handOver(v, target)
                                    return !target.startsWith("about:")
                                }
                                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                                    handOver(v, url)
                                }
                            }
                            transport.webView = carrier
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    page.view = this
                    loadUrl(MTS_PAGE)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        // The bar: one line and its buttons, in every state.
        Column(Modifier.fillMaxWidth().background(p.background).padding(horizontal = 12.dp, vertical = 8.dp)) {
            when (val state = review) {
                null -> {
                    val (line, color) = when {
                        created -> R.string.mts_hint_created to p.green
                        notice != null -> notice to p.orange
                        else -> R.string.mts_instruction_line to p.textSecondary
                    }
                    BarLine(stringResource(line), color)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OlcButton(stringResource(R.string.mts_action_cancel), { cancel() }, modifier = Modifier.weight(1f), role = OlcRole.SECONDARY)
                        OlcButton(stringResource(R.string.mts_action_finish), { finish() }, modifier = Modifier.weight(1f), role = OlcRole.PRIMARY, busy = checking)
                    }
                }
                is MtsMeetingCheck.Lesson.Ready -> when (val found = state.result) {
                    is MtsLearn.Result.One -> {
                        BarLine(
                            stringResource(if (found.template != null) R.string.mts_review_room_fmt else R.string.mts_review_link_only_fmt, found.meeting.link),
                            p.textPrimary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OlcButton(
                                stringResource(R.string.mts_not_this),
                                {
                                    AppLog.i(MTS_TAG, "lesson: the owner said it is not this one")
                                    review = null
                                },
                                modifier = Modifier.weight(1f), role = OlcRole.SECONDARY,
                            )
                            OlcButton(stringResource(R.string.mts_use_it), { useMeeting(found) }, modifier = Modifier.weight(1f), role = OlcRole.PRIMARY)
                        }
                    }
                    is MtsLearn.Result.Several -> {
                        BarLine(stringResource(R.string.mts_finish_several), p.orange)
                        Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                            for (meeting in found.meetings) {
                                Text(meeting.link, style = MaterialTheme.typography.bodyMedium, color = p.accentTint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        AppLog.i(MTS_TAG, "lesson: one of several picked from the list, nothing learned")
                                        review = MtsMeetingCheck.Lesson.Ready(MtsLearn.Result.One(meeting, madeByApp = false, template = null, why = "picked from a list"))
                                    }.padding(vertical = 6.dp))
                            }
                        }
                        FailureButtons(onMeeting != null, { review = null }, { showPaste = true })
                    }
                    MtsLearn.Result.None -> {
                        BarLine(stringResource(R.string.mts_finish_none), p.orange)
                        FailureButtons(onMeeting != null, { review = null }, { showPaste = true })
                    }
                }
                is MtsMeetingCheck.Lesson.Refused -> {
                    BarLine(stringResource(MtsMeetingCheck.refusalText(state.verdict) ?: R.string.mts_check_unknown), p.orange)
                    FailureButtons(onMeeting != null, { review = null }, { showPaste = true })
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.mts_login_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mts_login_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    Text(stringResource(R.string.mts_create_banner), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    if (learned) Text(stringResource(R.string.mts_known), style = MaterialTheme.typography.bodySmall, color = p.green)
                    OlcButton(
                        stringResource(R.string.mts_login_use),
                        {
                            pageJar?.let {
                                onSession(it)
                                AppLog.i(MTS_TAG, "lesson: sign-in kept")
                                Toast.makeText(context, R.string.mts_sign_in_kept, Toast.LENGTH_SHORT).show()
                            }
                        },
                        role = OlcRole.SECONDARY, enabled = pageJar != null, fillWidth = true,
                    )
                    // The page's own sign-in survives the app forgetting it, so this clears the web view's MTS Link cookies and page storage and starts the page over.
                    OlcButton(
                        stringResource(R.string.mts_login_sign_out),
                        {
                            WebSessions.clearMTS()
                            pageJar = null
                            page.view?.loadUrl(MTS_PAGE)
                            showInfo = false
                        },
                        role = OlcRole.SECONDARY, fillWidth = true,
                    )
                    if (onMeeting != null) {
                        OlcButton(stringResource(R.string.mts_paste_open), { showInfo = false; showPaste = true }, role = OlcRole.SECONDARY, fillWidth = true)
                    }
                    Text(
                        stringResource(R.string.mts_capture_title) + " · " + captured,
                        style = MaterialTheme.typography.labelMedium, color = p.accentTint,
                        modifier = Modifier.clickable { showCapture = !showCapture }.padding(vertical = 6.dp),
                    )
                    if (showCapture) {
                        if (captured == 0) {
                            Text(stringResource(R.string.mts_capture_empty), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                        } else {
                            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                Text(captureDump(capture, sightings), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = p.textSecondary)
                            }
                            OlcButton(
                                stringResource(R.string.mts_capture_copy),
                                {
                                    clipboard.setText(AnnotatedString(captureDump(capture, sightings)))
                                    Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                                },
                                role = OlcRole.SECONDARY, fillWidth = true,
                            )
                        }
                    }
                }
            },
            confirmButton = { OlcTextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    if (showPaste && onMeeting != null) {
        PasteDialog(p, onDismiss = { showPaste = false }) { link ->
            AppLog.i(MTS_TAG, "lesson: link pasted and checked, nothing learned")
            showPaste = false
            onMeeting(link)
            onBack()
        }
    }
}

/** The bar's one line: short, and never more than three lines on a narrow screen. */
@Composable
private fun BarLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 6.dp))
}

/** A failure's buttons: back to the page, and the paste fallback where a room is being asked for. */
@Composable
private fun FailureButtons(canPaste: Boolean, onBackToPage: () -> Unit, onPaste: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OlcButton(stringResource(R.string.mts_back_to_page), onBackToPage, modifier = Modifier.weight(1f), role = OlcRole.SECONDARY)
        if (canPaste) OlcButton(stringResource(R.string.mts_paste_open), onPaste, modifier = Modifier.weight(1f), role = OlcRole.SECONDARY)
    }
}

/**
 * The paste-a-link fallback: a meeting link typed or pasted by hand,
 * normalized the way every room field does and checked the way the core
 * will enter it; only a usable meeting is handed on.
 */
@Composable
private fun PasteDialog(p: OlcosPalette, onDismiss: () -> Unit, onLink: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mts_paste_label)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; problem = null }, singleLine = true,
                    label = { Text(stringResource(R.string.mts_paste_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = p.orange, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            OlcTextButton(enabled = !checking, onClick = {
                val link = RoomServices.normalizedRoom("mtslink", text)
                if (link.isEmpty()) {
                    problem = R.string.mts_paste_bad
                } else {
                    checking = true
                    scope.launch {
                        val verdict = MtsMeetingCheck.check(link)
                        checking = false
                        val refusal = MtsMeetingCheck.refusalText(verdict)
                        if (refusal == null) onLink(link) else problem = refusal
                    }
                }
            }) {
                if (checking) OlcSpinner(size = 16.dp) else Text(stringResource(R.string.mts_paste_use))
            }
        },
        dismissButton = { OlcTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The copyable text: the calls with their status and answers, then the addresses the page went through. */
private fun captureDump(capture: WebCapture.Buffer, sightings: MtsLearn.Sightings): String {
    val pages = sightings.all().joinToString("\n") { (if (it.fromDom) "links: " else "page: ") + WebCapture.scrub(it.text.replace('\n', ' ')) }
    return capture.dump() + (if (pages.isNotEmpty()) "\n\n" + pages else "")
}

/** One log line's words for what Finish found (no ids, no values). */
private fun describeResult(result: MtsLearn.Result): String = when (result) {
    MtsLearn.Result.None -> "no meeting found"
    is MtsLearn.Result.Several -> "${result.meetings.size} different meetings found, nothing to learn"
    is MtsLearn.Result.One ->
        if (result.madeByApp) {
            "one meeting made by a recorded call; " + (result.template?.let { "template built, ${it.shape()}" } ?: "no template (${result.why})")
        } else {
            "one meeting seen on a page only; no template (${result.why})"
        }
}

/** One log line's words for what the check left of a Finish (no ids, no values). */
private fun describeLesson(lesson: MtsMeetingCheck.Lesson): String = when (lesson) {
    is MtsMeetingCheck.Lesson.Refused -> "refused, the meeting is " + MtsMeetingCheck.logWord(lesson.verdict) + "; nothing learned"
    is MtsMeetingCheck.Lesson.Ready -> "checked; " + describeResult(lesson.result)
}

/** The learned shape for the log: where the answer holds the meeting, which credentials come from where (names only). */
private fun describeTemplate(template: MtsLearn.Template): String =
    "result ${template.kind.name.lowercase()} at '${MtsLearn.pathText(template.path)}', credential sources " +
        MtsLearn.sourceNames(template) + ", " + template.headers.size + " headers, body " + bodyKindName(template.body) + ", " +
        MtsLearn.takenFromStorage(template) + " values taken from cookies/storage"

private fun bodyKindName(body: MtsRequest.Body): String = when (body) {
    MtsRequest.Body.None -> "none"
    is MtsRequest.Body.Text -> if (body.json) "json text" else "text"
    is MtsRequest.Body.Form -> "form"
    is MtsRequest.Body.Multipart -> "multipart"
}

/**
 * The bridge the injected hook reports over: the web view calls it on one of
 * its own threads, so everything goes through the buffers' own locks and the
 * sheet follows them by version. Whatever arrives is scrubbed where it is
 * kept (WebCapture); nothing here goes anywhere.
 */
private class CaptureReports(private val capture: WebCapture.Buffer, private val sightings: MtsLearn.Sightings) {
    @JavascriptInterface
    fun request(id: Int, method: String, url: String, headers: String, body: String, copy: String) {
        // The first four are the display copy and are scrubbed where they are kept; `copy` is the template's, held unscrubbed in memory and never shown.
        capture.record(method, url, headerMap(headers), body, id, raw = MtsRequest.parseCopy(copy))
    }

    @JavascriptInterface
    fun response(id: Int, status: Int, text: String) {
        capture.respond(id, status, text)
    }

    @JavascriptInterface
    fun nav(url: String) {
        sightings.nav(url)
    }

    @JavascriptInterface
    fun links(text: String) {
        sightings.dom(text)
    }

    /** The hook's `Name: value` lines back into pairs, so the scrubbing can look at each name. */
    private fun headerMap(headers: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in headers.lines()) {
            val at = line.indexOf(':')
            if (at <= 0) continue
            out[line.substring(0, at).trim()] = line.substring(at + 1).trim()
        }
        return out
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
