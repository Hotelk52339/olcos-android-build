package io.olcos.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.olcos.app.core.TelemostRenewal
import io.olcos.app.core.RoomRotation
import io.olcos.app.core.RoomRotationPolicy
import io.olcos.app.core.AccountCheck
import io.olcos.app.core.Diagnostics
import io.olcos.app.core.SessionRefresh
import io.olcos.app.core.TunnelManager
import io.olcos.app.data.Appearance
import io.olcos.app.data.TunnelMode
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.InviteLink
import io.olcos.app.model.SubscriptionList
import io.olcos.app.ui.AddServerScreen
import io.olcos.app.ui.ConnectionsScreen
import io.olcos.app.ui.HomeScreen
import io.olcos.app.ui.LogsScreen
import io.olcos.app.ui.OlcTab
import io.olcos.app.ui.OlcTabBar
import io.olcos.app.ui.OlcosTheme
import io.olcos.app.ui.ScanScreen
import io.olcos.app.ui.ServerScreen
import io.olcos.app.ui.SettingsScreen
import io.olcos.app.ui.SpeedScreen
import io.olcos.app.ui.feelImport
import io.olcos.app.ui.importSource
import io.olcos.app.ui.importText
import io.olcos.app.ui.refreshDueSources
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    /** A tab asked for from outside the screens: a link opened in the app lands on Connections. */
    private val requestedTab = mutableStateOf<Int?>(null)
    /** The tile or the widget opened the app to connect what it could not by itself. */
    private val requestedConnect = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val appearance by AppGraph.settings.appearance.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance) {
                Appearance.SYSTEM -> systemDark
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            // The bars' icons follow the app's look, not the system's: dark
            // icons on a forced light screen and the other way round.
            LaunchedEffect(dark) {
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            OlcosTheme(dark = dark) { Root(requestedTab, requestedConnect) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A link opened from another app, or a text shared to olcOS: taken in like a pasted one. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_CONNECT -> requestedConnect.value = true
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { takeText(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { takeText(it) }
        }
    }

    private fun takeText(raw: String) {
        val app = applicationContext
        AppGraph.scope.launch {
            val view = window.decorView
            val message = feelImport(view, app, AppGraph.store) {
                var text = importText(app, AppGraph.store, raw)
                // A link that came through a browser may arrive percent-encoded.
                if (SubscriptionList.detectImport(raw) is SubscriptionList.Import.Unrecognized) {
                    val decoded = Uri.decode(raw)
                    if (decoded != raw) text = importText(app, AppGraph.store, decoded)
                }
                text
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            requestedTab.value = 1
        }
    }

    companion object {
        /** Opens the app to connect the chosen record, from a tile or a widget that could not. */
        const val ACTION_CONNECT = "io.olcos.app.CONNECT"
    }
}

private class Tab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
private fun Root(requestedTab: MutableState<Int?>, requestedConnect: MutableState<Boolean>) {
    val context = LocalContext.current
    val view = LocalView.current
    val p = OlcosTheme.palette
    val tabs = remember {
        // The iOS tab bar's glyphs: a house, a server rack, a gear; filled when chosen.
        listOf(
            Tab(R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
            Tab(R.string.tab_connections, Icons.Outlined.Dns, Icons.Filled.Dns),
            Tab(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Where the person is survives a turn of the phone.
    var openServer by rememberSaveable { mutableStateOf<String?>(null) }
    var openLogs by rememberSaveable { mutableStateOf(false) }
    var openSpeed by rememberSaveable { mutableStateOf(false) }
    var openScan by rememberSaveable { mutableStateOf(false) }
    var openAddServer by rememberSaveable { mutableStateOf(false) }
    val requested = requestedTab.value
    LaunchedEffect(requested) {
        if (requested != null) {
            tab = requested
            requestedTab.value = null
        }
    }

    // The VPN consent the system asks for once; the connect waits for the answer.
    // The record's id, not the record: it must outlive a rotation while the dialog is up.
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val record = pendingId?.let { id -> AppGraph.store.records.value.firstOrNull { it.id == id } }
        pendingId = null
        if (result.resultCode == Activity.RESULT_OK && record != null) {
            TunnelManager.connect(context, record, TunnelMode.VPN)
        } else {
            Toast.makeText(context, R.string.error_vpn_refused, Toast.LENGTH_LONG).show()
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Every hour while the app is open: a Telemost room on an owned server
    // that is about to expire gets a new one.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { TelemostRenewal.sweep(context) }
            kotlinx.coroutines.delay(60 * 60_000L)
        }
    }
    // A connection on a protocol with an auto-update schedule gets a fresh room
    // once its own is old enough and its window is open. A pass looks every hour,
    // every ten minutes while a rule has a window, so a one-hour window is not missed.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { RoomRotation.sweep(context) }
            kotlinx.coroutines.delay(RoomRotationPolicy.sweepIntervalMs(AppGraph.settings.roomRules.value, AppGraph.settings.roomWindows.value))
        }
    }
    // Once a day, always on: the saved VK and MTS Link sign-ins are checked
    // quietly, so a dead one is named before a room is needed.
    LaunchedEffect(Unit) { runCatching { AccountCheck.sweep(context) } }
    // Lists go stale: each one is fetched again, quietly, once its own
    // #refresh interval has passed (half an hour when it names none) — when
    // the app comes to the front, and every minute while it stays there.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inFront = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(inFront) {
        while (inFront) {
            runCatching { refreshDueSources(context, AppGraph.store) }
            kotlinx.coroutines.delay(60_000)
        }
    }
    // A connection from an invite that fails may be stale (a renewed room, a
    // rotated key): the list is fetched again, and the next try uses the update.
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is TunnelManager.State.Failed) {
            // A Telemost room past its day is the usual reason: renew what this device owns.
            if (TunnelManager.record.value?.olcrtc?.lanes?.any { it.carrier == "telemost" } == true) {
                runCatching { TelemostRenewal.sweep(context) }
            }
            // The rooms due on an auto-update schedule are changed here as well, as on the hourly pass.
            runCatching { RoomRotation.sweep(context) }
            val source = TunnelManager.record.value?.subSource ?: return@LaunchedEffect
            if (InviteLink.parse(source) != null) importSource(context, AppGraph.store, source)
        }
    }
    val mode by AppGraph.settings.mode.collectAsStateWithLifecycle()
    val connect: (ConnectionRecord) -> Unit = { record ->
        when (mode) {
            TunnelMode.PROXY -> TunnelManager.connect(context, record, TunnelMode.PROXY)
            TunnelMode.VPN -> {
                val ask = TunnelManager.vpnConsent(context)
                if (ask == null) {
                    TunnelManager.connect(context, record, TunnelMode.VPN)
                } else {
                    pendingId = record.id
                    consent.launch(ask)
                }
            }
        }
    }
    // As on iOS: the chosen connection is joined once when the app opens,
    // unless a session runs already, the tile or the widget asked for one, or
    // a VPN consent would have to be asked for first.
    LaunchedEffect(Unit) {
        if (AppGraph.didAutoConnect) return@LaunchedEffect
        AppGraph.didAutoConnect = true
        if (!AppGraph.settings.autoConnect.value || requestedConnect.value) return@LaunchedEffect
        if (TunnelManager.state.value !is TunnelManager.State.Disconnected) return@LaunchedEffect
        val record = AppGraph.store.primary ?: return@LaunchedEffect
        if (mode == TunnelMode.VPN && TunnelManager.vpnConsent(context) != null) return@LaunchedEffect
        connect(record)
    }
    // As on iOS: opening the app, or coming back to it, checks which
    // connections work (Settings → Check on opening). A connection checked a
    // moment ago is left alone, so going back and forth costs nothing.
    // The managed servers' status comes first (a few seconds at most), so a
    // stopped room is known as stopped and not checked; the records follow
    // the rooms that changed meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        AppGraph.scope.launch {
            val statuses = launch { runCatching { SessionRefresh.refreshServers(context) } }
            withTimeoutOrNull(5_000) { statuses.join() }
            if (!AppGraph.settings.checkOnOpen.value) return@launch
            val keep = setOfNotNull(AppGraph.store.primary?.id, TunnelManager.record.value?.id)
            Diagnostics.checkOnOpen(AppGraph.store.records.value, keep)
        }
    }
    // The tile or the widget sent the person here to connect: do it, once, when there is something to connect.
    val wantsConnect = requestedConnect.value
    LaunchedEffect(wantsConnect) {
        if (wantsConnect) {
            requestedConnect.value = false
            val record = AppGraph.store.primary
            if (record != null && (state is TunnelManager.State.Disconnected || state is TunnelManager.State.Failed)) {
                connect(record)
            } else if (record == null) {
                tab = 1
            }
        }
    }

    val clipboard = LocalClipboardManager.current
    // Text taken from the clipboard is imported in the app's scope: leaving the screen does not cut it short.
    val paste: () -> Unit = {
        val text = clipboard.getText()?.text
        val app = context.applicationContext
        AppGraph.scope.launch {
            val message = feelImport(view, app, AppGraph.store) { importText(app, AppGraph.store, text) }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    // One screen at a time: a tab, or a page pushed over the tabs. A page
    // slides in from the trailing edge and back out, the way iOS pushes one.
    val serverID = openServer
    val page: Page = when {
        openScan -> Page.Scan
        openAddServer -> Page.AddServer
        serverID != null -> Page.Server(serverID)
        openLogs -> Page.Logs
        openSpeed -> Page.Speed
        else -> Page.Tabs
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState.depth > initialState.depth ||
                (targetState.depth == initialState.depth && targetState != Page.Tabs)
            if (forward) {
                (slideInHorizontally(tween(320)) { it } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(320)) { -it / 3 } + fadeOut(tween(320)))
            } else {
                (slideInHorizontally(tween(320)) { -it / 3 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(160)))
            }
        },
        contentKey = { it.key },
        label = "page",
    ) { shown ->
        when (shown) {
            Page.Scan -> ScanScreen(
                onResult = { text ->
                    openScan = false
                    val app = context.applicationContext
                    AppGraph.scope.launch {
                        val message = feelImport(view, app, AppGraph.store) { importText(app, AppGraph.store, text) }
                        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                    }
                },
                onBack = { openScan = false },
            )
            Page.AddServer -> AddServerScreen(
                onDone = { id ->
                    openAddServer = false
                    openServer = id
                },
                onBack = { openAddServer = false },
            )
            is Page.Server -> ServerScreen(serverID = shown.id, onBack = { openServer = null })
            Page.Logs -> LogsScreen(onBack = { openLogs = false })
            Page.Speed -> SpeedScreen(onBack = { openSpeed = false })
            Page.Tabs -> Scaffold(
                containerColor = p.background,
                bottomBar = {
                    OlcTabBar(
                        tabs = tabs.map { OlcTab(stringResource(it.label), it.icon, it.selectedIcon) },
                        selected = tab,
                        onSelect = { tab = it },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (tab) {
                        0 -> HomeScreen(
                            onConnect = connect,
                            onDisconnect = { TunnelManager.disconnect(context) },
                            onGoToConnections = { tab = 1 },
                            onOpenSpeed = { openSpeed = true },
                            onPaste = paste,
                            onScan = { openScan = true },
                            onAddServer = { openAddServer = true },
                        )
                        1 -> ConnectionsScreen(
                            onOpenServer = { openServer = it.id },
                            onScan = { openScan = true },
                            onAddServer = { openAddServer = true },
                            // A tap on a connection connects it and shows the result on Home, as on iOS.
                            onConnect = { record ->
                                connect(record)
                                tab = 0
                            },
                        )
                        else -> SettingsScreen(onOpenLogs = { openLogs = true })
                    }
                }
            }
        }
    }
}

/** What the app shows: the tabs, or one page pushed over them. */
private sealed class Page(val key: String, val depth: Int) {
    data object Tabs : Page("tabs", 0)
    data object Scan : Page("scan", 1)
    data object AddServer : Page("add-server", 1)
    data class Server(val id: String) : Page("server-$id", 2)
    data object Logs : Page("logs", 1)
    data object Speed : Page("speed", 1)
}
