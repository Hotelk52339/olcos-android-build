package io.olcos.app.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.core.IPMask
import io.olcos.app.R
import io.olcos.app.core.AgentClient
import io.olcos.app.core.Diagnostics
import io.olcos.app.core.Health
import io.olcos.app.core.ServerPairing
import io.olcos.app.core.TunnelManager
import io.olcos.app.core.UserFacing
import io.olcos.app.data.ConnectionStore
import io.olcos.app.data.SubscriptionFetcher
import io.olcos.app.data.SubscriptionSource
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.InviteCipher
import io.olcos.app.model.InviteLink
import io.olcos.app.model.ServerRecord
import io.olcos.app.model.SubscriptionList
import kotlinx.coroutines.launch
import java.net.URI

/** Turns a pasted text into connections: one link, a list to fetch, or a list itself. Returns what to tell. */
suspend fun importText(context: Context, store: ConnectionStore, raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return context.getString(R.string.connections_clipboard_empty)
    return when (val input = SubscriptionList.detectImport(text)) {
        is SubscriptionList.Import.Connection -> {
            // The same link twice is the same connection: the saved one is kept.
            if (store.find(input.details) != null) {
                context.getString(R.string.connections_already_saved)
            } else {
                store.add(input.details, input.name)
                context.getString(R.string.connections_pasted)
            }
        }
        is SubscriptionList.Import.Url -> importSource(context, store, input.source)
        is SubscriptionList.Import.Invite -> importSource(context, store, text)
        is SubscriptionList.Import.Body -> {
            val list = SubscriptionList.parse(text)
            if (list.entries.isEmpty()) {
                context.getString(R.string.subscription_empty)
            } else {
                for (entry in list.entries) store.add(entry.details, entry.recordName)
                context.resources.getQuantityString(R.plurals.subscription_imported, list.entries.size, list.entries.size)
            }
        }
        is SubscriptionList.Import.Unsupported -> context.getString(R.string.connections_vless_unsupported_fmt, input.reason)
        SubscriptionList.Import.Unrecognized -> context.getString(R.string.connections_bad_link)
    }
}

/**
 * Fetches a subscription (an https list, or an invite's encrypted file with
 * the key from the link or the one kept from before) and brings its records
 * up to date. Returns what to tell.
 */
suspend fun importSource(context: Context, store: ConnectionStore, link: String): String {
    return try {
        val invite = InviteLink.parse(link)
        val body: String
        val source: String
        var inviteKey: String? = null
        if (invite != null) {
            val key = invite.key ?: store.source(link)?.inviteKey?.let { InviteLink.decodeKey(it) }
                ?: return context.getString(R.string.invite_error_missing_key)
            body = SubscriptionFetcher.fetchInvite(invite, key)
            source = invite.source
            inviteKey = InviteLink.encodeKey(key)
        } else {
            val https = SubscriptionList.httpsUrl(link) ?: return context.getString(R.string.connections_bad_link)
            body = SubscriptionFetcher.fetch(https)
            source = link
        }
        val list = SubscriptionList.parse(body)
        if (list.entries.isEmpty()) {
            context.getString(R.string.subscription_empty)
        } else {
            val count = store.importSubscription(source, list, inviteKey)
            var message = context.resources.getQuantityString(R.plurals.subscription_imported, count, count)
            // The ages the list knows, for the Telemost renewal.
            for (entry in list.entries) {
                val created = entry.createdAt ?: continue
                entry.olcrtc?.roomID?.let { AppGraph.roomAges.set(it, created) }
            }
            // An invite can also hand over the server: register this device with its agent.
            list.agent?.let { offer ->
                val outcome = ServerPairing.take(offer, list.name ?: offer.host, source, AppGraph.servers)
                message += when (outcome) {
                    is ServerPairing.Outcome.Paired -> "\n" + context.getString(R.string.server_access_granted_fmt,
                        context.getString(if (outcome.server.isOwner) R.string.server_role_owner else R.string.server_role_member))
                    is ServerPairing.Outcome.Pending -> "\n" + context.getString(R.string.server_access_pending_fmt, outcome.reason) +
                        "\n" + context.getString(R.string.server_pending_hint)
                    ServerPairing.Outcome.AlreadyPaired -> ""
                }
            }
            message
        }
    } catch (e: SubscriptionFetcher.Revoked) {
        context.getString(R.string.invite_error_revoked)
    } catch (e: InviteCipher.NotAnInvite) {
        context.getString(R.string.invite_error_not_invite)
    } catch (e: InviteCipher.WrongKey) {
        context.getString(R.string.invite_error_wrong_key)
    } catch (e: Exception) {
        context.getString(R.string.subscription_failed_fmt, UserFacing.describe(context, e))
    }
}

/** When each list was last tried by [refreshDueSources], so one that cannot be fetched is not tried every minute. */
private val refreshTried = HashMap<String, Long>()
private const val REFRESH_RETRY_MS = 5 * 60_000L

/**
 * Fetches again, quietly, every list whose own `#refresh` interval (else
 * half an hour) has passed; one that failed waits five minutes before the
 * next try. Called when the app comes to the front and every minute while it
 * stays there.
 */
suspend fun refreshDueSources(context: Context, store: ConnectionStore) {
    val now = System.currentTimeMillis()
    for (source in store.sources.value) {
        if (!source.isRefreshDue(now)) continue
        val key = ConnectionStore.canonicalSource(source.link)
        val tried = synchronized(refreshTried) { refreshTried[key] }
        if (tried != null && now - tried < REFRESH_RETRY_MS) continue
        synchronized(refreshTried) { refreshTried[key] = now }
        importSource(context, store, source.link)
    }
}

/**
 * The second tab, laid out as the iOS app lays it out (ConnectionListView):
 * each server of one's own with the connections it gives and the way into
 * it; the links added by hand; and one row to the subscriptions. A tap on a
 * connection connects it; its ⋯ holds the rest.
 */
@Composable
fun ConnectionsScreen(
    onOpenServer: (ServerRecord) -> Unit,
    onScan: () -> Unit,
    onAddServer: () -> Unit,
    onConnect: (ConnectionRecord) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = AppGraph.store
    val records by store.records.collectAsStateWithLifecycle()
    val sources by store.sources.collectAsStateWithLifecycle()
    val servers by AppGraph.servers.servers.collectAsStateWithLifecycle()
    val primaryId by store.primaryId.collectAsStateWithLifecycle()
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    val liveRecord by TunnelManager.record.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    var busy by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var extrasOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = extrasOpen) { extrasOpen = false }

    // Imports run in the app's scope: leaving the tab or turning the phone does not cut them short.
    val take: (String?) -> Unit = { text ->
        AppGraph.scope.launch {
            busy = true
            val message = feelImport(view, app, store) { importText(app, store, text) }
            busy = false
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }
    val paste: () -> Unit = { take(clipboard.getText()?.text) }
    val refresh: (SubscriptionSource) -> Unit = { source ->
        AppGraph.scope.launch {
            busy = true
            val message = feelImport(view, app, store) { importSource(app, store, source.link) }
            busy = false
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }
    // The connection a tap on Connect would use: the live one, else the last chosen.
    val connected = state is TunnelManager.State.Connected
    val selected = if (connected) liveRecord?.id else (primaryId ?: records.firstOrNull()?.id)
    val liveID = if (connected) liveRecord?.id else null

    var qrFor by remember { mutableStateOf<ConnectionRecord?>(null) }
    var removeFor by remember { mutableStateOf<ConnectionRecord?>(null) }
    var removeSourceFor by remember { mutableStateOf<SubscriptionSource?>(null) }
    var problemFor by remember { mutableStateOf<Pair<ConnectionRecord, String>?>(null) }
    val health = rememberHealth(records)
    val actions = RowActions(
        selected = selected,
        liveID = liveID,
        health = health,
        onTap = { record ->
            store.setPrimary(record.id)
            onConnect(record)
        },
        onCheck = { record -> AppGraph.scope.launch { Diagnostics.check(record) } },
        onProblem = { record, text -> problemFor = record to text },
        onCopy = { record ->
            clipboard.setText(AnnotatedString(record.olcrtc?.encode() ?: record.xray?.encode(record.name) ?: ""))
            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
        },
        onQR = { qrFor = it },
        onRemove = { removeFor = it },
    )

    AnimatedContent(
        targetState = extrasOpen,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(320)) { it } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(320)) { -it / 3 } + fadeOut(tween(320)))
            } else {
                (slideInHorizontally(tween(320)) { -it / 3 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(320)) { it } + fadeOut(tween(160)))
            }
        },
        label = "extras",
    ) { extras ->
        if (extras) {
            ExtrasPage(records, sources, servers, actions, busy,
                onBack = { extrasOpen = false }, onRefresh = refresh,
                onRemoveSource = { removeSourceFor = it }, onAddSubscription = paste)
        } else {
            MainList(records, sources, servers, actions, busy, typed,
                onTyped = { typed = it },
                onAdd = {
                    take(typed)
                    typed = ""
                },
                onPaste = paste, onScan = onScan, onAddServer = onAddServer,
                onOpenServer = onOpenServer, onOpenExtras = { extrasOpen = true })
        }
    }

    // What a failed check said, one tap on its verdict: the row itself never grows to carry it.
    problemFor?.let { (record, text) ->
        AlertDialog(
            onDismissRequest = { problemFor = null },
            title = { Text(Naming.title(record), style = Type.title) },
            text = { Text(text, style = Type.body) },
            confirmButton = {
                OlcTextButton(onClick = {
                    problemFor = null
                    AppGraph.scope.launch { Diagnostics.check(record) }
                }) { Text(stringResource(R.string.action_check)) }
            },
            dismissButton = { OlcTextButton(onClick = { problemFor = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    qrFor?.let { record ->
        val link = record.olcrtc?.encode() ?: record.xray?.encode(record.name) ?: ""
        AlertDialog(
            onDismissRequest = { qrFor = null },
            title = { Text(record.displayName, style = Type.title) },
            text = { QrImage(link, Modifier.fillMaxWidth().aspectRatio(1f)) },
            confirmButton = { OlcTextButton(onClick = { qrFor = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    removeFor?.let { record ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text(stringResource(R.string.action_remove_connection), style = Type.title) },
            text = { Text(stringResource(R.string.connection_remove_confirm_fmt, record.displayName), style = Type.body) },
            confirmButton = {
                OlcTextButton(onClick = {
                    store.remove(record.id)
                    removeFor = null
                }) { Text(stringResource(R.string.action_remove), color = OlcosTheme.palette.destructive) }
            },
            dismissButton = { OlcTextButton(onClick = { removeFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    removeSourceFor?.let { source ->
        val canon = ConnectionStore.canonicalSource(source.link)
        val count = records.count { it.subSource?.let { s -> ConnectionStore.canonicalSource(s) } == canon }
        AlertDialog(
            onDismissRequest = { removeSourceFor = null },
            title = { Text(stringResource(R.string.action_remove_subscription), style = Type.title) },
            text = { Text(context.resources.getQuantityString(R.plurals.subscription_remove_confirm, count, count), style = Type.body) },
            confirmButton = {
                OlcTextButton(onClick = {
                    store.removeSource(source.link)
                    removeSourceFor = null
                }) { Text(stringResource(R.string.action_remove), color = OlcosTheme.palette.destructive) }
            },
            dismissButton = { OlcTextButton(onClick = { removeSourceFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The width of the verdict at the end of a row: the same for every state, so no check moves anything. */
private val VERDICT_SLOT = 108.dp

/**
 * Runs an import and gives the haptic of its outcome: success when it changed
 * the lists or found the link already saved, error otherwise. Returns what to tell.
 */
suspend fun feelImport(view: View, context: Context, store: ConnectionStore, run: suspend () -> String): String {
    val records = store.records.value
    val sources = store.sources.value
    val message = run()
    val done = store.records.value != records || store.sources.value != sources ||
        message == context.getString(R.string.connections_already_saved)
    if (done) Haptics.success(view) else Haptics.error(view)
    return message
}

/** What every connection row can do, decided once for the screen. */
private class RowActions(
    val selected: String?,
    val liveID: String?,
    val health: HealthView,
    val onTap: (ConnectionRecord) -> Unit,
    val onCheck: (ConnectionRecord) -> Unit,
    val onProblem: (ConnectionRecord, String) -> Unit,
    val onCopy: (ConnectionRecord) -> Unit,
    val onQR: (ConnectionRecord) -> Unit,
    val onRemove: (ConnectionRecord) -> Unit,
)

/** The records a server gives: the ones it made, its whole-server one, and those its lists brought. */
private fun recordsOf(server: ServerRecord, records: List<ConnectionRecord>): List<ConnectionRecord> =
    records.filter { AgentClient.isThrough(server, it.id, it.subSource) }

@Composable
private fun MainList(
    records: List<ConnectionRecord>,
    sources: List<SubscriptionSource>,
    servers: List<ServerRecord>,
    actions: RowActions,
    busy: Boolean,
    typed: String,
    onTyped: (String) -> Unit,
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onAddServer: () -> Unit,
    onOpenServer: (ServerRecord) -> Unit,
    onOpenExtras: () -> Unit,
) {
    val p = OlcosTheme.palette
    val checking by Diagnostics.busy.collectAsStateWithLifecycle()
    val byServer = servers.map { it to recordsOf(it, records) }
    val shownByServer = byServer.flatMap { (_, mine) -> mine.map { it.id } }.toSet()
    val ownLinks = records.filter { it.subSource == null && it.id !in shownByServer }
    val listed = records.count { it.subSource != null && it.id !in shownByServer }

    Column(Modifier.fillMaxSize().background(p.background)) {
        OlcLargeTitle(stringResource(R.string.tab_connections)) {
            // The spinner's place is always there: a check starting must not shift the buttons beside it.
            Box(Modifier.size(Metrics.s6), contentAlignment = Alignment.Center) {
                if (busy || checking.isNotEmpty()) OlcSpinner()
            }
            Spacer(Modifier.width(Metrics.s2))
            if (records.isNotEmpty()) {
                Box(Modifier.size(44.dp).olcPressable(enabled = checking.isEmpty()) {
                    AppGraph.scope.launch { Diagnostics.checkAll(records) }
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = stringResource(R.string.action_check_all), tint = p.accentTint)
                }
            }
            AddMenuButton(onPaste = onPaste, onScan = onScan, onAddServer = onAddServer)
        }
        if (records.isEmpty() && sources.isEmpty() && servers.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.s4)) {
                FirstRunCard(typed, onTyped = onTyped, onPaste = onPaste, onScan = onScan, onAddServer = onAddServer, onAdd = onAdd)
                Spacer(Modifier.height(Metrics.s6))
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Metrics.s4, end = Metrics.s4, top = Metrics.s2, bottom = Metrics.s6),
            verticalArrangement = Arrangement.spacedBy(Metrics.s6),
        ) {
            if (records.isEmpty()) {
                item(key = "empty") {
                    OlcCard { Text(stringResource(R.string.list_empty), style = Type.body, color = p.textSecondary) }
                }
            }
            items(byServer, key = { "server:" + it.first.id }) { (server, mine) ->
                val header = stringResource(if (server.isOwner) R.string.list_own_server_header_fmt else R.string.list_shared_server_header_fmt, server.name)
                OlcGroup(header = header) {
                    if (mine.isEmpty()) {
                        Text(stringResource(R.string.list_own_server_empty), style = Type.body, color = p.textSecondary,
                            modifier = Modifier.padding(Metrics.s4))
                        OlcRowDivider()
                    }
                    for (record in mine) {
                        ConnectionRow(record, actions, subtitle = ownServerSubtitle(record))
                        OlcRowDivider(inset = 52.dp)
                    }
                    OlcRow(
                        title = stringResource(R.string.list_open_server),
                        subtitle = if (server.isPaired) stringResource(R.string.list_open_server_detail) else stringResource(R.string.server_pending),
                        icon = Icons.Filled.Tune,
                        iconTint = if (server.isPaired) null else p.orange,
                        chevron = true,
                        onClick = { onOpenServer(server) },
                    )
                }
            }
            if (servers.isEmpty()) {
                item(key = "add-server") {
                    OlcGroup(footer = stringResource(R.string.list_my_servers_none)) {
                        AddRow(stringResource(R.string.list_add_own_server), Icons.Filled.Dns, onAddServer)
                    }
                }
            }
            if (ownLinks.isNotEmpty()) {
                item(key = "own-links") {
                    OlcGroup(header = stringResource(R.string.list_section_own_links), footer = stringResource(R.string.list_section_own_links_footer)) {
                        ownLinks.forEachIndexed { index, record ->
                            if (index > 0) OlcRowDivider(inset = 52.dp)
                            ConnectionRow(record, actions)
                        }
                    }
                }
            }
            item(key = "extras") {
                OlcGroup {
                    OlcRow(
                        title = stringResource(R.string.list_extras_title),
                        subtitle = if (sources.isEmpty()) stringResource(R.string.list_extras_empty)
                            else stringResource(R.string.list_extras_summary_fmt, sources.size, listed),
                        icon = Icons.Filled.Inbox,
                        chevron = true,
                        onClick = onOpenExtras,
                    )
                }
            }
        }
    }
}

/** The subscriptions page: each list with its connections, and the way to add one. */
@Composable
private fun ExtrasPage(
    records: List<ConnectionRecord>,
    sources: List<SubscriptionSource>,
    servers: List<ServerRecord>,
    actions: RowActions,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: (SubscriptionSource) -> Unit,
    onRemoveSource: (SubscriptionSource) -> Unit,
    onAddSubscription: () -> Unit,
) {
    val p = OlcosTheme.palette
    val byServer = servers.flatMap { recordsOf(it, records) }.map { it.id }.toSet()
    Column(Modifier.fillMaxSize().background(p.background)) {
        OlcTopBar(stringResource(R.string.list_extras_title), onBack = onBack, backLabel = stringResource(R.string.tab_connections)) {
            if (busy) OlcSpinner(Modifier.padding(end = Metrics.s3))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Metrics.s4, end = Metrics.s4, top = Metrics.s2, bottom = Metrics.s6),
            verticalArrangement = Arrangement.spacedBy(Metrics.s6),
        ) {
            items(sources, key = { "source:" + it.link }) { source ->
                val canon = ConnectionStore.canonicalSource(source.link)
                val mine = records.filter { r -> r.subSource?.let { ConnectionStore.canonicalSource(it) } == canon && r.id !in byServer }
                val maskIPs by AppGraph.settings.maskIPs.collectAsStateWithLifecycle()
                val host = remember(source.link, maskIPs) {
                    val shown = InviteLink.parse(source.link)?.host
                        ?: runCatching { URI(SubscriptionList.httpsUrl(source.link) ?: source.link).host }.getOrNull()
                    shown?.let { IPMask.display(it, maskIPs) } ?: IPMask.displayAll(source.link, maskIPs)
                }
                OlcGroup(header = source.name?.takeIf { it.isNotBlank() } ?: host) {
                    mine.forEachIndexed { index, record ->
                        if (index > 0) OlcRowDivider(inset = 52.dp)
                        ConnectionRow(record, actions)
                    }
                    if (mine.isNotEmpty()) OlcRowDivider()
                    OlcRow(
                        title = stringResource(R.string.subscription_connections_fmt, records.count { r ->
                            r.subSource?.let { ConnectionStore.canonicalSource(it) } == canon
                        }),
                        subtitle = host,
                        trailing = {
                            Box(Modifier.size(40.dp).olcPressable { onRefresh(source) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = p.accentTint)
                            }
                            OlcOverflowMenu(listOf(
                                OlcMenuItem(stringResource(R.string.action_refresh), Icons.Filled.Refresh) { onRefresh(source) },
                                OlcMenuItem(stringResource(R.string.action_remove_subscription), Icons.Filled.Delete, destructive = true) { onRemoveSource(source) },
                            ))
                        },
                    )
                }
            }
            item(key = "add-subscription") {
                OlcGroup(header = if (sources.isEmpty()) stringResource(R.string.list_section_subscriptions) else null) {
                    AddRow(stringResource(R.string.list_add_own_subscription), Icons.Filled.AddCircle, onAddSubscription)
                }
            }
        }
    }
}

/** The second line of a connection a server of one's own gives: how it travels, not the server again. */
@Composable
private fun ownServerSubtitle(record: ConnectionRecord): String {
    val olcrtc = record.olcrtc ?: return stringResource(R.string.list_direct_subtitle)
    return if (olcrtc.isBundle) stringResource(R.string.connection_rooms_fmt, olcrtc.lanes.size) + " · " +
        stringResource(if (olcrtc.bond != null) R.string.connection_bonded else R.string.connection_turns)
    else stringResource(R.string.list_via_call_subtitle)
}

/** A row that adds something: the glyph and the words in the accent colour. */
@Composable
private fun AddRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    val p = OlcosTheme.palette
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.controlHeight + 8.dp).olcClickable(onClick = onClick).padding(horizontal = Metrics.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.accentTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Metrics.s3))
        Text(title, style = Type.bodyStrong, color = p.accentTint)
    }
}

/** The «+»: everything that adds a connection. */
@Composable
private fun AddMenuButton(onPaste: () -> Unit, onScan: () -> Unit, onAddServer: () -> Unit) {
    val p = OlcosTheme.palette
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(44.dp).olcPressable { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_connection), tint = p.accentTint, modifier = Modifier.size(28.dp))
        }
        OlcDropdown(open, onDismiss = { open = false }, items = listOf(
            OlcMenuItem(stringResource(R.string.action_paste_link), Icons.Filled.ContentPaste, onClick = onPaste),
            OlcMenuItem(stringResource(R.string.action_scan_qr), Icons.Filled.QrCodeScanner, onClick = onScan),
            OlcMenuItem(stringResource(R.string.action_add_server), Icons.Filled.Dns, dividerBefore = true, onClick = onAddServer),
        ))
    }
}

/**
 * One connection: the choice mark, the service and whose server, the last
 * check (a tap checks again) or "Connected" for the live one, and the ⋯.
 * A tap on the words connects it.
 */
@Composable
private fun ConnectionRow(record: ConnectionRecord, actions: RowActions, subtitle: String? = null) {
    val p = OlcosTheme.palette
    val checking by Diagnostics.busy.collectAsStateWithLifecycle()
    val health = actions.health
    val check: Health.Check? = health.checks[record.id]
    val busy = record.id in checking
    val isSelected = record.id == actions.selected
    val isLive = record.id == actions.liveID
    // One verdict for the connection, from the evidence about its rooms (its own checks, the server's
    // other checks, the live tunnel): the same one the server page and the Home hero read.
    val verdict = health.verdict(record)
    val ms: Long? = Health.shownMs(check, health.now)
    // A running check only dims the last verdict and swaps the dot for the progress one.
    val tone = when (verdict.state) {
        Health.State.WORKING -> OlcTone.OK
        Health.State.FAILED -> OlcTone.ERROR
        Health.State.UNKNOWN, Health.State.STOPPED -> OlcTone.UNKNOWN
    }
    val failure: String? = if (tone == OlcTone.ERROR) check?.takeIf { !it.ok }?.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.health_not_working) else null
    // The row never changes shape with a check: the name column is as wide, and the second line is the same
    // line, whatever the check says. The verdict lives in a slot of one fixed width at the end.
    Row(Modifier.fillMaxWidth().padding(start = Metrics.s4, end = Metrics.s1, top = Metrics.s1, bottom = Metrics.s1), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = Metrics.rowMinHeight).olcClickable(role = null) { actions.onTap(record) }.padding(vertical = Metrics.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null,
                tint = if (isSelected) p.accentTint else p.textTertiary, modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(Metrics.s3))
            Column(Modifier.weight(1f)) {
                // Titles and subtitles wrap, never cut: no ellipsis anywhere on the row.
                Text(Naming.title(record), style = Type.bodyStrong, color = p.textPrimary)
                val line = (subtitle ?: Naming.subtitle(record)).takeIf { it.isNotEmpty() }
                if (line != null) {
                    Text(line, style = Type.body, color = p.textSecondary)
                }
                // What broke is named, not averaged: the rooms of a working connection that did not answer.
                if (verdict.failed.isNotEmpty()) {
                    Text(stringResource(R.string.health_unanswered_fmt, Health.failedNames(verdict.failed)), style = Type.caption, color = p.orange)
                }
            }
        }
        Box(
            Modifier
                .width(VERDICT_SLOT)
                .heightIn(min = Metrics.controlHeight)
                .olcClickable(enabled = !isLive, role = null) {
                    if (failure != null && !busy) actions.onProblem(record, failure) else actions.onCheck(record)
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (isLive) {
                Text(stringResource(R.string.list_row_connected), style = Type.caption, color = p.green, maxLines = 1)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OlcStatusDot(if (busy) OlcTone.PROGRESS else tone, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    // The last value stays while a new one is measured: a failed probe says so in its own words.
                    Text(
                        when (verdict.state) {
                            Health.State.WORKING -> if (ms != null) stringResource(R.string.health_ms_fmt, ms.toInt()) else stringResource(R.string.health_working)
                            Health.State.FAILED -> stringResource(R.string.health_not_working)
                            Health.State.UNKNOWN -> "—"
                            Health.State.STOPPED -> stringResource(R.string.health_stopped)
                        },
                        style = Type.caption, maxLines = 1,
                        color = when {
                            busy -> p.textTertiary
                            tone == OlcTone.ERROR -> p.red
                            tone == OlcTone.OK -> p.textPrimary
                            else -> p.textSecondary
                        },
                    )
                }
            }
        }
        OlcOverflowMenu(listOf(
            OlcMenuItem(stringResource(R.string.action_check), Icons.Filled.NetworkCheck) { actions.onCheck(record) },
            OlcMenuItem(stringResource(R.string.action_copy_link), Icons.Filled.ContentCopy) { actions.onCopy(record) },
            OlcMenuItem(stringResource(R.string.action_show_qr), Icons.Filled.QrCode) { actions.onQR(record) },
            OlcMenuItem(stringResource(R.string.action_remove_connection), Icons.Filled.Delete, destructive = true, dividerBefore = true) { actions.onRemove(record) },
        ))
    }
}

/** What a newcomer sees: what a link is, who sends it, and the ways to bring one in. */
@Composable
private fun FirstRunCard(
    typed: String,
    onTyped: (String) -> Unit,
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onAddServer: () -> Unit,
    onAdd: () -> Unit,
) {
    val p = OlcosTheme.palette
    OlcCard {
        Text(stringResource(R.string.first_run_title), style = Type.title, color = p.textPrimary)
        Spacer(Modifier.height(Metrics.s2))
        Text(stringResource(R.string.first_run_what_is_a_link), style = Type.body, color = p.textSecondary)
        Spacer(Modifier.height(Metrics.s2))
        Text(stringResource(R.string.first_run_kinds), style = Type.body, color = p.textSecondary)
        Spacer(Modifier.height(Metrics.s2))
        Text(stringResource(R.string.first_run_room), style = Type.caption, color = p.textSecondary)
        Spacer(Modifier.height(Metrics.s4))
        OlcButton(stringResource(R.string.action_paste_link), onPaste, icon = Icons.Filled.ContentPaste, role = OlcRole.PRIMARY, fillWidth = true)
        Spacer(Modifier.height(Metrics.s2))
        OlcButton(stringResource(R.string.action_scan_qr), onScan, icon = Icons.Filled.QrCodeScanner, fillWidth = true)
        Spacer(Modifier.height(Metrics.s4))
        Text(stringResource(R.string.first_run_type_hint), style = Type.caption, color = p.textSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = typed, onValueChange = onTyped, minLines = 2,
            label = { Text(stringResource(R.string.first_run_type_label)) },
            textStyle = Type.body,
            shape = RoundedCornerShape(Metrics.controlRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.accentTint, unfocusedBorderColor = p.fillBorder,
                focusedLabelColor = p.accentTint, unfocusedLabelColor = p.textSecondary,
                focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary, cursorColor = p.accentTint,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Metrics.s2))
        OlcButton(stringResource(R.string.action_add), { if (typed.isNotBlank()) onAdd() },
            role = if (typed.isNotBlank()) OlcRole.PRIMARY else OlcRole.SECONDARY, fillWidth = true, enabled = typed.isNotBlank())
        Spacer(Modifier.height(Metrics.s4))
        Text(stringResource(R.string.first_run_own_server), style = Type.caption, color = p.textSecondary)
        Spacer(Modifier.height(6.dp))
        OlcButton(stringResource(R.string.action_add_server), onAddServer, icon = Icons.Filled.Dns, fillWidth = true)
    }
}
