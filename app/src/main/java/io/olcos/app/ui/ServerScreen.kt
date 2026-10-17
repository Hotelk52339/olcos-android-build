package io.olcos.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.core.IPMask
import io.olcos.app.R
import io.olcos.app.core.AgentClient
import io.olcos.app.core.AgentKey
import io.olcos.app.core.AppLog
import io.olcos.app.core.Invites
import io.olcos.app.core.Maintenance
import io.olcos.app.core.RoomServices
import io.olcos.app.core.ServerBond
import io.olcos.app.core.ServerInstall
import io.olcos.app.core.ServerPairing
import io.olcos.app.core.ServerStatus
import io.olcos.app.core.TelemostRenewal
import io.olcos.app.core.RoomRotation
import io.olcos.app.core.UserFacing
import io.olcos.app.model.Details
import io.olcos.app.model.DeviceInvite
import io.olcos.app.model.OlcrtcLink
import io.olcos.app.model.ServerRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One managed server: the agent's status at once and then as it changes
 * (events long-poll), the rooms with start/stop/restart and a log, the Xray
 * deployments, the devices with access (owner), and the pairing retry while
 * the server has not confirmed this device yet. An owner also changes rooms,
 * adds and removes protocols and rotates the key: the same scripts the iOS
 * app runs over SSH, run here through the agent.
 */
@Composable
fun ServerScreen(serverID: String, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val servers = AppGraph.servers
    val store = AppGraph.store
    val all by servers.servers.collectAsStateWithLifecycle()
    val server = all.firstOrNull { it.id == serverID }
    val clipboard = LocalClipboardManager.current

    // What an operation in flight writes to lives outside the composition: Back
    // or a turn of the phone makes a new screen, and it reads the same page.
    val page = remember(serverID) { ServerPageState.of(serverID) }
    var status by remember { mutableStateOf<ServerStatus?>(null) }
    // The dialogs survive a turn of the phone: the one the person had open comes back open.
    var roomDialog by remember { mutableStateOf<RoomIntent?>(null) }
    var forgetDialog by rememberSaveable { mutableStateOf(false) }
    var revokeDeviceFor by remember { mutableStateOf<AgentClient.Device?>(null) }
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    var yandexFor by remember { mutableStateOf<RoomIntent?>(null) }
    var vkLoginFor by remember { mutableStateOf<RoomIntent?>(null) }
    var wbLoginFor by remember { mutableStateOf<RoomIntent?>(null) }
    var updateDialog by rememberSaveable { mutableStateOf(false) }
    var inviteDialog by rememberSaveable { mutableStateOf(false) }
    var inviteLinkFor by remember { mutableStateOf<DeviceInvite?>(null) }
    var inviteRevokeFor by remember { mutableStateOf<DeviceInvite?>(null) }
    var reinstallDialog by rememberSaveable { mutableStateOf(false) }
    var bundleRemoveDialog by rememberSaveable { mutableStateOf(false) }
    val records by store.records.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    val client = remember(server?.id, server?.deviceID, server?.prefix, server?.certSHA256) {
        server?.takeIf { it.isPaired }?.let { AgentClient(it, AgentKey.loadOrCreate()) }
    }

    // Who of the invited devices used the server, and how much: once a minute
    // while the page is shown. An agent without the call answers 404: the rows
    // then stay as they were.
    var usage by remember { mutableStateOf<List<AgentClient.DeviceUsage>?>(null) }
    LaunchedEffect(client, server?.isOwner) {
        val c = client ?: return@LaunchedEffect
        if (server?.isOwner != true) return@LaunchedEffect
        while (isActive) {
            runCatching { c.usage() }.onSuccess { usage = it }
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Fresh status at once, then every change the agent reports, while on screen.
    LaunchedEffect(client, page.refreshTick) {
        val c = client ?: return@LaunchedEffect
        var since = 0L
        while (isActive) {
            try {
                val fresh = c.status()
                val parsed = ServerStatus.parse(fresh.outputs, fresh.version)
                status = parsed
                // The last good parse stands in when a later refetch has not
                // come back: an action that needs the server's shape still has one.
                page.lastStatus = parsed
                page.error = null
                since = fresh.version
                // The whole-server connection follows the rooms: a protocol or room added or changed elsewhere joins it.
                runCatching { servers.byId(serverID)?.let { ServerBond.sync(context, it, parsed.rooms, store, servers, make = false) } }
                // A Telemost room about to expire gets a new one while the page is open.
                runCatching { TelemostRenewal.consider(context, c, parsed) }
                // So does a room past its auto-update schedule, if the owner opted the connection in.
                runCatching { RoomRotation.consider(context, c, parsed) }
                while (isActive) {
                    val version = c.events(since)
                    if (version != since) {
                        since = version
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A screen that left or restarted its work: not a failure to show.
                throw e
            } catch (e: Exception) {
                page.error = UserFacing.describe(context, e)
                delay(5_000)
            }
        }
    }

    // The application context: a toast may come after this screen is gone.
    fun toast(text: String) = Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()

    fun act(container: String, action: String) {
        val c = client ?: return
        AppGraph.scope.launch {
            page.busy = container
            try {
                c.room(container, action)
            } catch (e: Exception) {
                page.error = UserFacing.describe(context, e)
            }
            page.busy = null
            page.refreshTick++
        }
    }

    fun showLog(container: String) {
        val c = client ?: return
        AppGraph.scope.launch {
            page.busy = container
            try {
                page.logText = container to c.log(container)
            } catch (e: Exception) {
                page.error = UserFacing.describe(context, e)
            }
            page.busy = null
        }
    }

    /** The lists the server's invites carry follow every change of its connections. */
    suspend fun syncInvites() {
        val c = client ?: return
        val fresh = servers.byId(serverID) ?: return
        if (fresh.invites.isEmpty()) return
        try {
            Invites.upload(c, fresh)
        } catch (e: Exception) {
            AppLog.w("olcos.invites", "lists for ${fresh.name} not updated: ${e.message}")
        }
    }
    // Lists that fell behind (a connection removed elsewhere, an upload that failed) go up when the page opens.
    LaunchedEffect(client) { syncInvites() }

    /** One maintenance script: runs it, tells the outcome, refreshes. */
    fun maintain(marker: String, script: String, okMarker: String?, onResult: (Maintenance.Result) -> String) {
        val c = client ?: return
        AppGraph.scope.launch {
            page.busy = marker
            try {
                val result = Maintenance.run(c, script, okMarker)
                toast(onResult(result))
                page.error = null
                syncInvites()
            } catch (e: Exception) {
                val reason = UserFacing.describe(context, e)
                page.error = reason
                toast(context.getString(R.string.maintenance_failed_fmt, reason))
            }
            page.busy = null
            page.refreshTick++
        }
    }

    fun changeRoom(room: ServerStatus.Room, newRoom: String, wbToken: String = "") {
        maintain(room.container, Maintenance.reconfigureScript(room.container, room.provider, room.transport, newRoom, wbToken), "OLCRTC_RECONFIGURED") { result ->
            val changed = result.uri?.let { store.replaceRoom(room.provider, room.room, it) } ?: 0
            context.getString(R.string.maintenance_done) + " · " + context.getString(R.string.records_updated_fmt, changed)
        }
    }

    /** The primary container the owner's scripts act through: the fresh status, or the last one the page parsed. */
    fun primaryBase(): String = (status?.rooms ?: page.lastStatus?.rooms)?.firstOrNull { it.isPrimary }?.container ?: ""

    fun addProtocol(carrier: String, transport: String, room: String, lane: Int? = null, anchorRoom: String = "", wbToken: String = "") {
        // The room was often just made; a refetch that has not come back must
        // not burn it silently — the last good parse stands in, and without
        // even that, the person is told instead of nothing happening.
        val base = primaryBase().ifEmpty {
            toast(context.getString(R.string.server_no_status_yet))
            return
        }
        val jitsiBase = (status?.rooms ?: page.lastStatus?.rooms)?.firstOrNull { it.provider == "jitsi" }?.room
            ?.let { Maintenance.freshJitsiRoom(it).substringBeforeLast('/') } ?: Maintenance.DEFAULT_JITSI_BASE
        val script = Maintenance.addCarrierScript(
            context, base, carrier, transport, room, jitsiBase,
            AppGraph.settings.dns.value, AppGraph.settings.vp8FPS.value, AppGraph.settings.vp8Batch.value, lane, wbToken)
        maintain("add", script, "OLCRTC_CARRIER_ADDED") { result ->
            val link = result.uri
            if (link != null) {
                // An extra room joins the records that ride the carrier; a new protocol becomes a record.
                val joined = if (lane != null) store.addRoom(carrier, link.transport, anchorRoom, link.roomID) else 0
                if (joined == 0) {
                    val record = store.add(Details.Olcrtc(link), (server?.name ?: "") + " · " + OlcrtcLink.carrierLabel(carrier))
                    // The record belongs to this server: its invites carry it.
                    servers.update(serverID) { it.copy(recordIDs = it.recordIDs + record.id) }
                }
            }
            context.getString(R.string.maintenance_done)
        }
    }

    fun updateCore() {
        val c = client ?: return
        val base = status?.rooms?.firstOrNull { it.isPrimary }?.container ?: return
        AppGraph.scope.launch {
            page.busy = "update"
            page.updateLog = ""
            try {
                Maintenance.runUpdate(c, context, base) { tail -> page.updateLog = tail }
                toast(context.getString(R.string.update_done))
                page.error = null
            } catch (e: Exception) {
                val reason = UserFacing.describe(context, e)
                page.error = reason
                toast(context.getString(R.string.maintenance_failed_fmt, reason))
            }
            page.updateLog = null
            page.busy = null
            page.refreshTick++
        }
    }

    /** A server this phone set up over SSH: the agent rebuilt from the sources this app carries. */
    fun reinstallAgent() {
        val target = server ?: return
        AppGraph.scope.launch {
            page.busy = "agent"
            try {
                ServerInstall.reinstallAgent(context, target)
                toast(context.getString(R.string.agent_reinstalled))
                page.error = null
            } catch (e: Exception) {
                val reason = UserFacing.describe(context, e)
                page.error = reason
                toast(context.getString(R.string.maintenance_failed_fmt, reason))
            }
            page.busy = null
            page.refreshTick++
        }
    }

    /** The whole-server connection: the bond endpoint on the server first, then the record from every room. */
    fun createBundle() {
        val target = server ?: return
        val rooms = status?.rooms ?: return
        AppGraph.scope.launch {
            page.busy = "bond"
            try {
                ServerBond.create(context, target, rooms)
                toast(context.getString(R.string.bundle_created))
                page.error = null
                syncInvites()
            } catch (e: Exception) {
                val reason = UserFacing.describe(context, e)
                page.error = reason
                toast(context.getString(R.string.maintenance_failed_fmt, reason))
            }
            page.busy = null
            page.refreshTick++
        }
    }

    /** Stops the endpoint on the server and deletes the whole-server connection from this phone. */
    fun removeBundle() {
        val target = server ?: return
        AppGraph.scope.launch {
            page.busy = "bond"
            try {
                ServerBond.remove(context, target)
                toast(context.getString(R.string.bundle_removed))
                page.error = null
                syncInvites()
            } catch (e: Exception) {
                val reason = UserFacing.describe(context, e)
                page.error = reason
                toast(context.getString(R.string.maintenance_failed_fmt, reason))
            }
            page.busy = null
            page.refreshTick++
        }
    }

    /** The room an intent ends with: the server change, or the new protocol or extra room. */
    fun finish(intent: RoomIntent, room: String, wbToken: String = "") {
        // The phone-made rooms' ages: the Telemost renewal and the room auto-update count by them.
        if (room.isNotEmpty() && (intent.carrier == "telemost" || intent.carrier == "vkcalls" || intent.carrier == "wbstream")) {
            AppGraph.roomAges.set(room, System.currentTimeMillis() / 1000)
        }
        when (intent) {
            is RoomIntent.Change -> changeRoom(intent.room, room, wbToken)
            is RoomIntent.Add -> addProtocol(intent.carrier, intent.transport, room, intent.lane, intent.anchorRoom, wbToken)
        }
    }

    /**
     * Mints the room the service needs: Jitsi at once, SaluteJazz with one
     * anonymous request, Telemost with the saved Yandex session, VK Calls and
     * WB Stream with the sign-ins the app kept (a sign-in first when there is
     * none).
     */
    fun newRoom(intent: RoomIntent) {
        val carrier = intent.carrier
        when (carrier) {
            "jitsi" -> finish(intent, (intent as? RoomIntent.Change)?.let { Maintenance.freshJitsiRoom(it.room.room) } ?: "")
            "salutejazz" -> AppGraph.scope.launch {
                page.busy = "room"
                try {
                    val room = RoomServices.SaluteJazz.create()
                    page.busy = null
                    finish(intent, room)
                } catch (e: Exception) {
                    page.busy = null
                    toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                }
            }
            "telemost" -> {
                val yandex = AppGraph.yandex
                val session = yandex.session
                if (session == null) {
                    yandexFor = intent
                    return
                }
                AppGraph.scope.launch {
                    page.busy = "room"
                    try {
                        val room = RoomServices.Telemost.create(session, yandex.twin)
                        page.busy = null
                        finish(intent, room)
                    } catch (e: RoomServices.SessionRejected) {
                        page.busy = null
                        yandex.clear()
                        toast(context.getString(R.string.yandex_session_rejected))
                        yandexFor = intent
                    } catch (e: Exception) {
                        page.busy = null
                        toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                    }
                }
            }
            "vkcalls" -> {
                val vk = AppGraph.vk
                val sid = vk.remixSid
                if (sid == null) {
                    vkLoginFor = intent
                    return
                }
                AppGraph.scope.launch {
                    page.busy = "room"
                    try {
                        val room = RoomServices.VKCall.create(sid)
                        page.busy = null
                        finish(intent, room)
                    } catch (e: RoomServices.SessionRejected) {
                        page.busy = null
                        vk.clear()
                        toast(context.getString(R.string.vk_session_rejected))
                        vkLoginFor = intent
                    } catch (e: Exception) {
                        page.busy = null
                        toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                    }
                }
            }
            "wbstream" -> {
                val wb = AppGraph.wb
                val token = wb.token
                if (token == null) {
                    wbLoginFor = intent
                    return
                }
                AppGraph.scope.launch {
                    page.busy = "room"
                    try {
                        // Both halves of the answer: the room and the account token that makes it the account's own.
                        val room = RoomServices.WBStream.create(token)
                        page.busy = null
                        finish(intent, room, token)
                    } catch (e: RoomServices.SessionRejected) {
                        page.busy = null
                        wb.clear()
                        toast(context.getString(R.string.wb_session_rejected))
                        wbLoginFor = intent
                    } catch (e: Exception) {
                        page.busy = null
                        toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                    }
                }
            }
            else -> (intent as? RoomIntent.Change)?.let { roomDialog = it }
        }
    }

    yandexFor?.let { intent ->
        YandexLoginScreen(
            onSession = { session, twin ->
                AppGraph.yandex.store(session, twin)
                yandexFor = null
                newRoom(intent)
            },
            onBack = { yandexFor = null },
        )
        return
    }
    vkLoginFor?.let { intent ->
        VKLoginScreen(
            onSession = { sid ->
                AppGraph.vk.store(sid)
                vkLoginFor = null
                newRoom(intent)
            },
            onBack = { vkLoginFor = null },
        )
        return
    }
    wbLoginFor?.let { intent ->
        WBLoginScreen(
            onSession = { token ->
                AppGraph.wb.store(token)
                wbLoginFor = null
                newRoom(intent)
            },
            // Typing the room by hand, in the dialog the way it used to be asked.
            onManual = {
                wbLoginFor = null
                roomDialog = intent
            },
            onBack = { wbLoginFor = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        OlcTopBar(server?.name ?: "", onBack = onBack, backLabel = stringResource(R.string.tab_connections)) {
            if (page.busy != null) OlcSpinner(Modifier.padding(end = Metrics.s3))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Metrics.s5, end = Metrics.s4, bottom = Metrics.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val maskIPs by AppGraph.settings.maskIPs.collectAsStateWithLifecycle()
                Text(
                    IPMask.display(server?.host ?: "", maskIPs) + " · " + stringResource(
                        when {
                            server?.isPaired != true -> R.string.server_pending
                            server?.isOwner == true -> R.string.server_role_owner
                            else -> R.string.server_role_member
                        }),
                    style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                )
                val ssh = server
                if (ssh != null && ssh.hasSSH) {
                    Text(
                        stringResource(R.string.server_ssh_fmt, ssh.sshUser.orEmpty() + "@" + IPMask.display(ssh.host, maskIPs) + (if (ssh.sshPort != 22) ":" + ssh.sshPort else "")),
                        style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                    )
                }
            }
        }
        if (server == null) return@Column

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (!server.isPaired) {
                PendingCard(server, p) {
                    AppGraph.scope.launch {
                        page.busy = "pair"
                        val outcome = ServerPairing.pair(server, servers)
                        page.busy = null
                        val message = when (outcome) {
                            is ServerPairing.Outcome.Paired -> context.getString(R.string.server_access_granted_fmt,
                                context.getString(if (outcome.server.isOwner) R.string.server_role_owner else R.string.server_role_member))
                            is ServerPairing.Outcome.Pending -> context.getString(R.string.server_access_pending_fmt, outcome.reason)
                            ServerPairing.Outcome.AlreadyPaired -> ""
                        }
                        if (message.isNotEmpty()) toast(message)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            page.error?.let {
                Text(stringResource(R.string.server_error_fmt, it), style = MaterialTheme.typography.bodySmall, color = p.red)
                Spacer(Modifier.height(12.dp))
            }

            val s = status
            val expectedCore = remember { Maintenance.expectedCoreIdentity(context) }
            if (server.isPaired) {
                StatusCard(s, p, expectedCore)
                Spacer(Modifier.height(20.dp))
            }

            page.updateLog?.let { tail ->
                OlcCard(padding = 0.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.update_running), style = MaterialTheme.typography.titleMedium, color = p.amber)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tail.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = p.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            val primary = s?.rooms?.firstOrNull { it.isPrimary }
            if (server.isOwner && primary != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        ActionButton(stringResource(R.string.action_add_protocol), p, primary = true) { addDialog = true }
                    }
                    Box(Modifier.weight(1f)) {
                        ActionButton(stringResource(R.string.action_new_key), p, primary = false) { keyDialog = true }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val core = s?.coreIdentity
                val outdated = core != null && core != expectedCore
                ActionButton(stringResource(R.string.action_update_core), p, primary = outdated) { updateDialog = true }
                Spacer(Modifier.height(20.dp))
            }
            if (server.hasSSH) {
                ActionButton(stringResource(R.string.action_reinstall_agent), p, primary = false) { if (page.busy == null) reinstallDialog = true }
                Spacer(Modifier.height(20.dp))
            }

            if (s != null && s.rooms.isNotEmpty()) {
                SectionTitle(stringResource(R.string.server_rooms), p)
                // One card per protocol: its own room, then its extra rooms (lanes), and "Add room".
                val byCarrier = s.rooms.groupBy { it.provider }
                for ((carrier, rooms) in byCarrier) {
                    val ordered = rooms.sortedWith(compareBy<ServerStatus.Room> { it.lane ?: 1 })
                    val anchor = ordered.firstOrNull { it.lane == null } ?: ordered.first()
                    OlcCard(padding = 0.dp) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(OlcrtcLink.carrierLabel(carrier), style = MaterialTheme.typography.titleMedium, color = p.textPrimary, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.rooms_count_fmt, ordered.size), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                            }
                            ordered.forEach { room ->
                                OlcRowDivider()
                                RoomRow(room, p, page.busy == room.container, owner = server.isOwner,
                                    onStart = { act(room.container, "start") },
                                    onStop = { act(room.container, "stop") },
                                    onRestart = { act(room.container, "restart") },
                                    onLog = { showLog(room.container) },
                                    onChangeRoom = { newRoom(RoomIntent.Change(room)) },
                                    onRemove = {
                                        // Captured when the dialog opens: a lost refetch or a turn of the phone cannot leave it baseless.
                                        page.removeFor = RemoveTarget.of(room, primaryBase())
                                    })
                            }
                            if (server.isOwner && ordered.size < 8 && !anchor.isMissing) {
                                OlcRowDivider()
                                Text(
                                    stringResource(R.string.action_add_room),
                                    style = MaterialTheme.typography.labelLarge, color = p.accentTint,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        val nextLane = ((ordered.mapNotNull { it.lane }.maxOrNull() ?: 1) + 1).coerceIn(2, 8)
                                        newRoom(RoomIntent.Add(carrier, anchor.transport, nextLane, anchor.room))
                                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            // Two rooms or more: the one connection that rides every protocol at once (owner only).
            val roomsPresent = s?.rooms?.count { !it.isMissing } ?: 0
            if (server.isOwner && roomsPresent >= 2) {
                val bundle = server.bundleRecordID?.let { id -> records.firstOrNull { it.id == id } }
                val bundleLink = bundle?.olcrtc
                SectionTitle(stringResource(R.string.bundle_name), p)
                OlcCard(padding = 0.dp) {
                    Column {
                        Text(stringResource(R.string.bundle_explanation), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
                        if (bundle == null || bundleLink == null) {
                            Text(
                                stringResource(R.string.action_create_bundle),
                                style = MaterialTheme.typography.titleMedium, color = p.accentTint,
                                modifier = Modifier.fillMaxWidth().clickable { if (page.busy == null) createBundle() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        } else {
                            val bonded = bundleLink.bond != null
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(if (bonded) p.green else p.orange))
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bundle.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                                    Text(
                                        bundleLink.servicesLine + " · " + stringResource(R.string.bundle_rooms_fmt, bundleLink.lanes.size),
                                        style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                                    )
                                    Text(
                                        stringResource(if (bonded) R.string.bundle_state_bonded else R.string.bundle_state_no_endpoint),
                                        style = MaterialTheme.typography.bodySmall, color = if (bonded) p.green else p.orange,
                                    )
                                }
                                if (page.busy == "bond") CircularProgressIndicator(Modifier.size(18.dp), color = p.accentTint, strokeWidth = 2.dp)
                            }
                            OlcRowDivider()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                if (!bonded) ActionWord(stringResource(R.string.action_set_up_bonding), p) { if (page.busy == null) createBundle() }
                                ActionWord(stringResource(R.string.action_remove), p, destructive = true) { if (page.busy == null) bundleRemoveDialog = true }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            if (s != null && s.xray.isNotEmpty()) {
                SectionTitle(stringResource(R.string.server_xray), p)
                OlcCard(padding = 0.dp) {
                    Column {
                        s.xray.forEachIndexed { index, x ->
                            if (index > 0) OlcRowDivider()
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(if (x.isUp) p.green else p.red))
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(x.container, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                                    Text(x.status, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                                }
                                ActionWord(stringResource(R.string.action_log), p) { showLog(x.container) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            if (server.isOwner) {
                SectionTitle(stringResource(R.string.server_invites), p)
                OlcCard(padding = 0.dp) {
                    Column {
                        Text(stringResource(R.string.invites_explanation), style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp))
                        Text(
                            stringResource(R.string.action_create_invite),
                            style = MaterialTheme.typography.titleMedium, color = p.accentTint,
                            modifier = Modifier.fillMaxWidth().clickable { inviteDialog = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        server.invites.forEach { invite ->
                            OlcRowDivider()
                            Row(Modifier.fillMaxWidth().clickable { inviteLinkFor = invite }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(invite.name.ifBlank { stringResource(R.string.invite_unnamed) }, style = Type.bodyStrong, color = p.textPrimary)
                                    Text(
                                        listOfNotNull(
                                            when (invite.agentRole) {
                                                "owner" -> stringResource(R.string.server_role_owner)
                                                "member" -> stringResource(R.string.server_role_member)
                                                else -> null
                                            },
                                            stringResource(if (invite.publishedAt != null) R.string.invite_published else R.string.invite_not_published),
                                        ).joinToString(" · "),
                                        style = Type.caption, color = p.textSecondary,
                                    )
                                    usage?.let { devices -> InviteUsageLines(Invites.usageOf(invite.token, devices)) }
                                }
                                ActionWord(stringResource(R.string.action_revoke), p, destructive = true) { inviteRevokeFor = invite }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))

                SectionTitle(stringResource(R.string.server_devices), p)
                OlcCard(padding = 0.dp) {
                    Column {
                        val list = page.devices
                        if (list == null) {
                            Text(
                                stringResource(R.string.action_show_devices),
                                style = MaterialTheme.typography.titleMedium, color = p.accentTint,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val c = client ?: return@clickable
                                    AppGraph.scope.launch {
                                        try {
                                            page.devices = withContext(Dispatchers.IO) { c.devices() }
                                        } catch (e: Exception) {
                                            page.error = UserFacing.describe(context, e)
                                        }
                                    }
                                }.padding(16.dp),
                            )
                        } else {
                            list.filterNot { it.revoked }.forEachIndexed { index, d ->
                                if (index > 0) OlcRowDivider()
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name.ifBlank { d.id }, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                                        Text(
                                            stringResource(if (d.role == "owner") R.string.server_role_owner else R.string.server_role_member) +
                                                if (d.id == server.deviceID) " · " + stringResource(R.string.server_this_device) else "",
                                            style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                                        )
                                    }
                                    if (d.id != server.deviceID) {
                                        ActionWord(stringResource(R.string.action_revoke), p, destructive = true) { revokeDeviceFor = d }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Text(
                stringResource(R.string.action_remove_server),
                style = MaterialTheme.typography.titleMedium, color = p.red,
                modifier = Modifier.fillMaxWidth().clickable { forgetDialog = true }.padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    page.logText?.let { (container, text) ->
        // Back closes the overlay, and a tap on it never reaches the page underneath.
        BackHandler { page.logText = null }
        Box(
            Modifier.fillMaxSize().background(p.background.copy(alpha = 0.96f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(20.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.server_log_title_fmt, container), style = MaterialTheme.typography.titleMedium,
                        color = p.textPrimary, modifier = Modifier.weight(1f))
                    ActionWord(stringResource(R.string.action_copy), p) {
                        // What leaves the phone is blanked the same way as the app's own log.
                        clipboard.setText(AnnotatedString(AppLog.redact(text)))
                        Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                    }
                    Spacer(Modifier.size(16.dp))
                    ActionWord(stringResource(R.string.action_close), p) { page.logText = null }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = p.textSecondary,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                )
            }
        }
    }

    if (forgetDialog && server != null) {
        AlertDialog(
            onDismissRequest = { forgetDialog = false },
            title = { Text(stringResource(R.string.action_remove_server)) },
            text = { Text(stringResource(R.string.forget_server_message)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetDialog = false
                    servers.remove(server.id)
                    onBack()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { forgetDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    revokeDeviceFor?.let { d ->
        AlertDialog(
            onDismissRequest = { revokeDeviceFor = null },
            title = { Text(stringResource(R.string.action_revoke)) },
            text = { Text(stringResource(R.string.device_revoke_message_fmt, d.name.ifBlank { d.id })) },
            confirmButton = {
                TextButton(onClick = {
                    val c = client ?: return@TextButton
                    revokeDeviceFor = null
                    AppGraph.scope.launch {
                        try {
                            c.revoke(d.id)
                            page.devices = withContext(Dispatchers.IO) { c.devices() }
                        } catch (e: Exception) {
                            page.error = UserFacing.describe(context, e)
                        }
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { revokeDeviceFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // A typed room, for a service that cannot make one or when one is made by hand.
    roomDialog?.let { intent ->
        var text by remember(intent) { mutableStateOf("") }
        val carrier = intent.carrier
        AlertDialog(
            onDismissRequest = { roomDialog = null },
            title = { Text(stringResource(R.string.dialog_room_title)) },
            text = {
                Column {
                    Text(
                        when (intent) {
                            is RoomIntent.Change -> intent.room.serviceName + " · " + intent.room.transportName
                            is RoomIntent.Add -> OlcrtcLink.carrierLabel(carrier) + " · " + OlcrtcLink.transportLabel(intent.transport)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(stringResource(R.string.dialog_room_hint)) })
                }
            },
            confirmButton = {
                TextButton(enabled = RoomServices.normalizedRoom(carrier, text).isNotEmpty(), onClick = {
                    val typed = RoomServices.normalizedRoom(carrier, text)
                    roomDialog = null
                    finish(intent, typed)
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { roomDialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (addDialog && server != null) {
        var carrier by remember { mutableStateOf("jitsi") }
        var transport by remember { mutableStateOf("datachannel") }
        var room by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text(stringResource(R.string.dialog_add_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.label_service), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (c in listOf("jitsi", "telemost", "vkcalls")) {
                            FilterChip(selected = carrier == c, onClick = { carrier = c }, label = { Text(OlcrtcLink.carrierLabel(c)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (c in listOf("salutejazz", "wbstream")) {
                            FilterChip(selected = carrier == c, onClick = { carrier = c }, label = { Text(OlcrtcLink.carrierLabel(c)) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.label_transport), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (t in listOf("datachannel", "vp8channel", "seichannel")) {
                            FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(OlcrtcLink.transportLabel(t)) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = room, onValueChange = { room = it }, singleLine = true,
                        label = { Text(stringResource(R.string.dialog_room_optional)) })
                    if (carrier == "vkcalls") {
                        Text(stringResource(R.string.dialog_room_vk_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosenCarrier = carrier
                    val chosenTransport = transport
                    val typed = RoomServices.normalizedRoom(chosenCarrier, room)
                    addDialog = false
                    val intent = RoomIntent.Add(chosenCarrier, chosenTransport)
                    // A typed room is used as is; an empty one is made by the service.
                    if (typed.isNotEmpty()) finish(intent, typed) else newRoom(intent)
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (keyDialog && server != null) {
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text(stringResource(R.string.action_new_key)) },
            text = { Text(stringResource(R.string.new_key_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val base = status?.rooms?.firstOrNull { it.isPrimary }?.container ?: return@TextButton
                    keyDialog = false
                    maintain("key", Maintenance.rotateKeyScript(context, base), null) { result ->
                        val changed = result.uri?.let { store.rekey(server.source, it.key, server.recordIDs) } ?: 0
                        context.getString(R.string.maintenance_done) + " · " + context.getString(R.string.records_updated_fmt, changed)
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { keyDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (inviteDialog && server != null) {
        var name by remember { mutableStateOf("") }
        var access by remember { mutableStateOf("member") }
        AlertDialog(
            onDismissRequest = { inviteDialog = false },
            title = { Text(stringResource(R.string.action_create_invite)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.invite_name_hint)) })
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.invite_access), style = MaterialTheme.typography.labelMedium)
                    for ((value, label) in listOf("none" to R.string.invite_access_none, "member" to R.string.invite_access_member, "owner" to R.string.invite_access_owner)) {
                        Row(Modifier.fillMaxWidth().clickable { access = value }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = access == value, onClick = { access = value })
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.invite_access_footer), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = client ?: return@TextButton
                    val chosenName = name.trim()
                    val chosenAccess = access
                    inviteDialog = false
                    AppGraph.scope.launch {
                        page.busy = "invite"
                        try {
                            val invite = Invites.create(c, server, chosenName, chosenAccess)
                            inviteLinkFor = invite
                        } catch (e: Exception) {
                            toast(context.getString(R.string.invite_failed_fmt, UserFacing.describe(context, e)))
                        }
                        page.busy = null
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { inviteDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    inviteLinkFor?.let { invite ->
        val fresh = server?.invites?.firstOrNull { it.id == invite.id } ?: invite
        val key = AppGraph.secrets.inviteKey(fresh.token)
        val sharePort = server?.sharePort
        val link = if (key != null && sharePort != null && server != null) Invites.link(server.host, sharePort, fresh.token, key) else null
        AlertDialog(
            onDismissRequest = { inviteLinkFor = null },
            title = { Text(fresh.name.ifBlank { stringResource(R.string.invite_unnamed) }) },
            text = {
                Column {
                    if (link != null) {
                        Text(stringResource(R.string.invite_link_explanation), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        QrImage(link, Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.action_copy_link), style = MaterialTheme.typography.titleMedium, color = p.accentTint,
                            modifier = Modifier.fillMaxWidth().clickable {
                                clipboard.setText(AnnotatedString(link))
                                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 8.dp))
                        Text(stringResource(R.string.invite_link_secret), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.invite_link_unavailable), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { inviteLinkFor = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    inviteRevokeFor?.let { invite ->
        AlertDialog(
            onDismissRequest = { inviteRevokeFor = null },
            title = { Text(stringResource(R.string.action_revoke)) },
            text = { Text(stringResource(R.string.invite_revoke_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val c = client ?: return@TextButton
                    val target = server ?: return@TextButton
                    inviteRevokeFor = null
                    AppGraph.scope.launch {
                        page.busy = "invite"
                        try {
                            Invites.revoke(c, target, invite)
                        } catch (e: Exception) {
                            toast(context.getString(R.string.invite_failed_fmt, UserFacing.describe(context, e)))
                        }
                        page.busy = null
                    }
                }) { Text(stringResource(R.string.action_revoke)) }
            },
            dismissButton = { TextButton(onClick = { inviteRevokeFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (reinstallDialog) {
        AlertDialog(
            onDismissRequest = { reinstallDialog = false },
            title = { Text(stringResource(R.string.action_reinstall_agent)) },
            text = { Text(stringResource(R.string.agent_reinstall_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    reinstallDialog = false
                    reinstallAgent()
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { reinstallDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (bundleRemoveDialog) {
        AlertDialog(
            onDismissRequest = { bundleRemoveDialog = false },
            title = { Text(stringResource(R.string.bundle_name)) },
            text = { Text(stringResource(R.string.bundle_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    bundleRemoveDialog = false
                    removeBundle()
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { bundleRemoveDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (updateDialog) {
        AlertDialog(
            onDismissRequest = { updateDialog = false },
            title = { Text(stringResource(R.string.action_update_core)) },
            text = { Text(stringResource(R.string.update_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    updateDialog = false
                    updateCore()
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { updateDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    page.removeFor?.let { target ->
        AlertDialog(
            onDismissRequest = { page.removeFor = null },
            title = { Text(stringResource(R.string.action_remove)) },
            text = { Text(target.line) },
            confirmButton = {
                TextButton(onClick = {
                    // Closed first, then acted on: a missing reading must not leave a dialog that cannot be dismissed.
                    page.removeFor = null
                    val base = target.base.ifEmpty { primaryBase() }
                    if (base.isEmpty()) {
                        toast(context.getString(R.string.server_no_status_yet))
                        return@TextButton
                    }
                    maintain(target.container, Maintenance.removeCarrierScript(base, target.container, target.file), "OLCRTC_CARRIER_REMOVED") {
                        val changed = store.dropRoom(target.provider, target.room)
                        context.getString(R.string.maintenance_done) + " · " + context.getString(R.string.records_updated_fmt, changed)
                    }
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { page.removeFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * What a server page shows of the work it started, kept per server for the
 * life of the process: an operation launched in the app's scope writes here,
 * and the screen that opens after Back or a rotation reads it, so a spinner
 * never sticks and a fetched log is not lost.
 */
class ServerPageState {
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var updateLog by mutableStateOf<String?>(null)
    var logText by mutableStateOf<Pair<String, String>?>(null)
    var devices by mutableStateOf<List<AgentClient.Device>?>(null)
    var refreshTick by mutableStateOf(0)
    /** The last status the page parsed, kept through a lost refetch and a turn of the phone. */
    var lastStatus by mutableStateOf<ServerStatus?>(null)
    /** The protocol the remove dialog is about, captured when it opened; lives here, so a rotation does not drop it. */
    var removeFor by mutableStateOf<RemoveTarget?>(null)

    companion object {
        private val pages = HashMap<String, ServerPageState>()

        fun of(serverID: String): ServerPageState = synchronized(pages) { pages.getOrPut(serverID) { ServerPageState() } }
    }
}

/**
 * What the remove-protocol dialog needs of the row it is about, with the
 * primary container captured when it opened: the dialog never depends on a
 * status refetch that may not have come back by the time it is confirmed.
 */
data class RemoveTarget(
    val base: String,
    val container: String,
    val file: String,
    val provider: String,
    val room: String,
    val line: String,
) {
    companion object {
        fun of(room: ServerStatus.Room, base: String) = RemoveTarget(
            base, room.container, room.file, room.provider, room.room,
            room.serviceName + " · " + room.shortRoom,
        )
    }
}

/** What a new room is for: a change of an existing room, or a protocol being added. */
sealed class RoomIntent {
    abstract val carrier: String

    data class Change(val room: ServerStatus.Room) : RoomIntent() {
        override val carrier: String get() = room.provider
    }

    /** `lane` 2..8 adds another room of a carrier the server already runs; null adds the protocol. */
    data class Add(override val carrier: String, val transport: String, val lane: Int? = null, val anchorRoom: String = "") : RoomIntent()
}

/**
 * When an invited device last used the server and how much it carried:
 * "Connected now" with the green mark, or the day and the time of the last
 * session (a time, never an age), and the traffic both ways.
 */
@Composable
private fun InviteUsageLines(usage: Invites.Usage?) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    Spacer(Modifier.height(6.dp))
    if (usage == null) {
        Text(stringResource(R.string.usage_never), style = Type.caption, color = p.textTertiary)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (usage.online) {
            OlcStatusDot(OlcTone.OK, size = 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.usage_online_now), style = Type.captionStrong, color = p.green)
        } else {
            Text(stringResource(R.string.usage_last_fmt, lastSeenPhrase(usage.lastSeen)), style = Type.caption, color = p.textSecondary)
        }
    }
    val down = android.text.format.Formatter.formatShortFileSize(context, usage.bytesDown)
    val up = android.text.format.Formatter.formatShortFileSize(context, usage.bytesUp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("↓ ", style = Type.captionStrong, color = p.signalCyan)
        Text(down, style = Type.captionDigits, color = p.textPrimary)
        Text("   ↑ ", style = Type.captionStrong, color = p.signalViolet)
        Text(up, style = Type.captionDigits, color = p.textPrimary)
        if (usage.installs > 1) {
            Text(
                "  · " + pluralStringResource(R.plurals.usage_installs, usage.installs, usage.installs),
                style = Type.caption, color = p.textSecondary,
            )
        }
    }
}

/** "today at 14:32", "yesterday at 09:10", "12 Oct at 18:05": the clock of the last session. */
@Composable
private fun lastSeenPhrase(unixSeconds: Long): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val then = java.util.Calendar.getInstance().apply { timeInMillis = unixSeconds * 1000 }
    val now = java.util.Calendar.getInstance()
    val time = java.text.SimpleDateFormat("HH:mm", locale).format(then.time)
    val sameDay = { a: java.util.Calendar, b: java.util.Calendar ->
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(then, now) -> stringResource(R.string.usage_today_fmt, time)
        sameDay(then, yesterday) -> stringResource(R.string.usage_yesterday_fmt, time)
        else -> stringResource(R.string.usage_date_fmt, java.text.SimpleDateFormat("d MMM", locale).format(then.time), time)
    }
}

@Composable
private fun PendingCard(server: ServerRecord, p: OlcosPalette, onRetry: () -> Unit) {
    OlcCard(padding = 0.dp) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.server_pending), style = MaterialTheme.typography.titleMedium, color = p.orange)
            server.lastError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.server_pending_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            Spacer(Modifier.height(12.dp))
            ActionButton(stringResource(R.string.action_retry), p, primary = true, onClick = onRetry)
        }
    }
}

@Composable
private fun StatusCard(s: ServerStatus?, p: OlcosPalette, expectedCore: String) {
    val (label, color) = when (s?.readiness) {
        ServerStatus.Readiness.RUNNING -> stringResource(R.string.server_status_running) to p.green
        ServerStatus.Readiness.STOPPED -> stringResource(R.string.server_status_stopped) to p.orange
        ServerStatus.Readiness.NO_CONTAINER -> stringResource(R.string.server_status_no_container) to p.textSecondary
        ServerStatus.Readiness.NO_IMAGE -> stringResource(R.string.server_status_no_image) to p.orange
        ServerStatus.Readiness.NO_PODMAN -> stringResource(R.string.server_status_no_podman) to p.red
        ServerStatus.Readiness.UNKNOWN, null -> stringResource(R.string.server_status_unknown) to p.textSecondary
    }
    OlcCard(padding = 0.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.size(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, color = color)
            }
            if (s != null && (s.disk.isNotEmpty() || s.ram.isNotEmpty() || s.uptime.isNotEmpty())) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.server_stats_fmt, s.disk.ifEmpty { "—" }, s.ram.ifEmpty { "—" }, s.uptime.ifEmpty { "—" }),
                    style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                )
            }
            s?.coreIdentity?.let { core ->
                Spacer(Modifier.height(4.dp))
                val outdated = core != expectedCore
                Text(
                    stringResource(R.string.server_core_fmt, Maintenance.shortCoreIdentity(core)) +
                        if (outdated) " · " + stringResource(R.string.server_core_outdated) else "",
                    style = MaterialTheme.typography.bodySmall, color = if (outdated) p.orange else p.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun RoomRow(
    room: ServerStatus.Room,
    p: OlcosPalette,
    busy: Boolean,
    owner: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onLog: () -> Unit,
    onChangeRoom: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(when {
                room.isUp -> p.green
                room.isMissing -> p.textSecondary
                else -> p.orange
            }))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(room.serviceName + " · " + room.transportName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
                Text(
                    room.shortRoom + " · " + stringResource(when {
                        room.isUp -> R.string.room_status_up
                        room.isMissing -> R.string.room_status_missing
                        else -> R.string.room_status_down
                    }),
                    style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = p.accentTint, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (room.isUp) {
                ActionWord(stringResource(R.string.action_restart), p, onClick = onRestart)
                ActionWord(stringResource(R.string.action_stop), p, onClick = onStop)
            } else if (!room.isMissing) {
                ActionWord(stringResource(R.string.action_start), p, onClick = onStart)
            }
            ActionWord(stringResource(R.string.action_log), p, onClick = onLog)
        }
        if (owner) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ActionWord(stringResource(R.string.action_change_room), p, onClick = onChangeRoom)
                if (!room.isPrimary) ActionWord(stringResource(R.string.action_remove), p, destructive = true, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun ActionWord(text: String, p: OlcosPalette, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (destructive) p.red else p.accentTint,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String, p: OlcosPalette) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium, color = p.textSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}
