package io.olcos.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.core.Maintenance
import io.olcos.app.core.RoomServices
import io.olcos.app.core.ServerInstall
import io.olcos.app.core.UserFacing
import io.olcos.app.data.ServerSecrets
import io.olcos.app.model.OlcrtcLink
import kotlinx.coroutines.launch

/**
 * Installing one's own server on one's own VPS: the SSH login, the server's
 * name, the protocol and its room, then the progress of ServerInstall with
 * the log's last lines. The work runs in the app's scope: Back and a turn
 * of the phone do not cut it short, and the form comes back with its
 * values. The screen stays on while the install runs.
 */
@Composable
fun AddServerScreen(onDone: (serverID: String) -> Unit, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val state by ServerInstall.state.collectAsStateWithLifecycle()

    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("root") }
    var useKey by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var privateKey by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var carrier by rememberSaveable { mutableStateOf("jitsi") }
    var transport by rememberSaveable { mutableStateOf("datachannel") }
    var room by rememberSaveable { mutableStateOf("") }
    var jitsi by rememberSaveable { mutableStateOf(Maintenance.DEFAULT_JITSI_BASE) }
    var wbToken by rememberSaveable { mutableStateOf("") }
    // Whether this screen saw the run start: only then a finished run opens the server by itself.
    var sawRunning by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var yandexLogin by remember { mutableStateOf(false) }
    var vkLogin by remember { mutableStateOf(false) }
    var wbLogin by remember { mutableStateOf(false) }
    var makingRoom by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    val running = state.phase == ServerInstall.Phase.RUNNING
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == ServerInstall.Phase.RUNNING) sawRunning = true
        if (state.phase == ServerInstall.Phase.DONE && sawRunning) {
            val id = state.serverID
            ServerInstall.reset()
            if (id != null) onDone(id)
        }
    }

    fun toast(text: String) = Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()

    /** The room the service needs, made the way the server page makes one. */
    fun makeRoom() {
        when (carrier) {
            "salutejazz" -> scope.launch {
                makingRoom = true
                try {
                    room = RoomServices.SaluteJazz.create()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // A screen that left or restarted its work: not a failure to show.
                    throw e
                } catch (e: Exception) {
                    toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                }
                makingRoom = false
            }
            "telemost" -> {
                val yandex = AppGraph.yandex
                val session = yandex.session
                if (session == null) {
                    yandexLogin = true
                    return
                }
                scope.launch {
                    makingRoom = true
                    try {
                        room = RoomServices.Telemost.create(session, yandex.twin)
                    } catch (e: RoomServices.SessionRejected) {
                        yandex.clear()
                        toast(context.getString(R.string.yandex_session_rejected))
                        yandexLogin = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A screen that left or restarted its work: not a failure to show.
                        throw e
                    } catch (e: Exception) {
                        toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                    }
                    makingRoom = false
                }
            }
            "vkcalls" -> {
                val vk = AppGraph.vk
                val sid = vk.remixSid
                if (sid == null) {
                    vkLogin = true
                    return
                }
                scope.launch {
                    makingRoom = true
                    try {
                        room = RoomServices.VKCall.create(sid)
                    } catch (e: RoomServices.SessionRejected) {
                        vk.clear()
                        toast(context.getString(R.string.vk_session_rejected))
                        vkLogin = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A screen that left or restarted its work: not a failure to show.
                        throw e
                    } catch (e: Exception) {
                        toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                    }
                    makingRoom = false
                }
            }
            "wbstream" -> {
                val wb = AppGraph.wb
                val token = wb.token
                if (token == null) {
                    wbLogin = true
                    return
                }
                scope.launch {
                    makingRoom = true
                    try {
                        // Both halves of the answer: the room and the account token that makes it the account's own.
                        room = RoomServices.WBStream.create(token)
                        wbToken = token
                    } catch (e: RoomServices.SessionRejected) {
                        wb.clear()
                        toast(context.getString(R.string.wb_session_rejected))
                        wbLogin = true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // A screen that left or restarted its work: not a failure to show.
                        throw e
                    } catch (e: Exception) {
                        toast(context.getString(R.string.room_create_failed_fmt, UserFacing.describe(context, e)))
                    }
                    makingRoom = false
                }
            }
        }
    }

    fun start() {
        val h = host.trim()
        val u = user.trim()
        val sshPort = port.trim().toIntOrNull()
        val loginMissing = if (useKey) privateKey.isBlank() else password.isEmpty()
        if (h.isEmpty() || u.isEmpty() || sshPort == null || sshPort !in 1..65535 || loginMissing) {
            problem = context.getString(R.string.add_server_missing_fields)
            return
        }
        val chosenRoom = RoomServices.normalizedRoom(carrier, room)
        if (carrier in Maintenance.ROOM_REQUIRED && chosenRoom.isEmpty()) {
            problem = context.getString(R.string.add_server_missing_room)
            return
        }
        problem = null
        val secret: ServerSecrets.Secret = if (useKey) {
            ServerSecrets.Secret.Key(privateKey.trim(), passphrase.takeIf { it.isNotEmpty() })
        } else {
            ServerSecrets.Secret.Password(password)
        }
        sawRunning = true
        ServerInstall.start(
            context,
            ServerInstall.Options(
                name = name.trim().ifEmpty { h }, host = h, sshPort = sshPort, user = u, secret = secret,
                carrier = carrier, transport = transport, roomID = chosenRoom,
                jitsiURL = jitsi.trim().ifEmpty { Maintenance.DEFAULT_JITSI_BASE }, wbToken = wbToken.trim(),
            ),
        )
    }

    if (yandexLogin) {
        YandexLoginScreen(
            onSession = { session, twin ->
                AppGraph.yandex.store(session, twin)
                yandexLogin = false
                makeRoom()
            },
            onBack = { yandexLogin = false },
        )
        return
    }
    if (vkLogin) {
        VKLoginScreen(
            onSession = { sid ->
                AppGraph.vk.store(sid)
                vkLogin = false
                makeRoom()
            },
            onBack = { vkLogin = false },
        )
        return
    }
    if (wbLogin) {
        WBLoginScreen(
            onSession = { token ->
                AppGraph.wb.store(token)
                wbLogin = false
                makeRoom()
            },
            // Back to the form, where the room id and the token are typed by hand.
            onManual = { wbLogin = false },
            onBack = { wbLogin = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(p.background).navigationBarsPadding()) {
        OlcTopBar(stringResource(R.string.add_server_title), onBack = onBack, backLabel = stringResource(R.string.action_back)) {
            if (running || makingRoom) OlcSpinner(Modifier.padding(end = Metrics.s3))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when (state.phase) {
                ServerInstall.Phase.RUNNING, ServerInstall.Phase.FAILED -> {
                    ProgressCard(state, p,
                        onRetry = { if (!ServerInstall.retry(context)) ServerInstall.reset() },
                        onChange = { ServerInstall.reset() })
                }
                ServerInstall.Phase.DONE -> {
                    // A run that ended while the screen was away: the result, and the way to the server.
                    OlcCard(padding = 0.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.add_server_done), style = MaterialTheme.typography.titleMedium, color = p.green)
                            state.agentPort?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.add_server_done_firewall_fmt, it), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                            }
                            Spacer(Modifier.height(12.dp))
                            ActionButton(stringResource(R.string.action_open_server), p, primary = true) {
                                val id = state.serverID
                                ServerInstall.reset()
                                if (id != null) onDone(id)
                            }
                        }
                    }
                }
                ServerInstall.Phase.IDLE -> {
                    Text(stringResource(R.string.add_server_intro), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                    Spacer(Modifier.height(16.dp))

                    Label(stringResource(R.string.add_server_section_access), p)
                    Field(host, { host = it }, stringResource(R.string.add_server_host), p, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Field(user, { user = it }, stringResource(R.string.add_server_user), p)
                        }
                        Column(Modifier.weight(1f)) {
                            Field(port, { port = it }, stringResource(R.string.add_server_ssh_port), p, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = !useKey, onClick = { useKey = false }, label = { Text(stringResource(R.string.add_server_auth_password)) })
                        FilterChip(selected = useKey, onClick = { useKey = true }, label = { Text(stringResource(R.string.add_server_auth_key)) })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (useKey) {
                        Field(privateKey, { privateKey = it }, stringResource(R.string.add_server_key), p, singleLine = false, minLines = 4)
                        Spacer(Modifier.height(8.dp))
                        Field(passphrase, { passphrase = it }, stringResource(R.string.add_server_passphrase), p,
                            visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                    } else {
                        Field(password, { password = it }, stringResource(R.string.add_server_password), p,
                            visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.action_hide_password else R.string.action_show_password), color = p.accentTint)
                    }
                    Spacer(Modifier.height(12.dp))

                    Label(stringResource(R.string.add_server_section_server), p)
                    Field(name, { name = it }, stringResource(R.string.add_server_name), p)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.label_service), style = MaterialTheme.typography.labelMedium, color = p.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (c in Maintenance.CARRIERS.take(3)) {
                            FilterChip(selected = carrier == c, onClick = { carrier = c; room = "" }, label = { Text(OlcrtcLink.carrierLabel(c)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (c in Maintenance.CARRIERS.drop(3)) {
                            FilterChip(selected = carrier == c, onClick = { carrier = c; room = "" }, label = { Text(OlcrtcLink.carrierLabel(c)) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.label_transport), style = MaterialTheme.typography.labelMedium, color = p.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        for (t in Maintenance.TRANSPORTS) {
                            FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(OlcrtcLink.transportLabel(t)) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    Field(room, { room = it }, stringResource(R.string.add_server_room), p)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(when (carrier) {
                            "telemost" -> R.string.add_server_room_telemost
                            "vkcalls" -> R.string.add_server_room_vk
                            "salutejazz" -> R.string.add_server_room_salutejazz
                            "wbstream" -> R.string.add_server_room_wbstream
                            else -> R.string.add_server_room_jitsi
                        }),
                        style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                    )
                    if (carrier in Maintenance.ROOM_REQUIRED) {
                        Spacer(Modifier.height(8.dp))
                        ActionButton(stringResource(R.string.action_make_room), p, primary = false) { if (!makingRoom) makeRoom() }
                    }
                    if (carrier == "jitsi") {
                        Spacer(Modifier.height(8.dp))
                        Field(jitsi, { jitsi = it }, stringResource(R.string.add_server_jitsi), p, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    }
                    if (carrier == "wbstream") {
                        Spacer(Modifier.height(8.dp))
                        Field(wbToken, { wbToken = it }, stringResource(R.string.add_server_wb_token), p)
                    }
                    Spacer(Modifier.height(16.dp))

                    problem?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = p.red)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.add_server_keep_open), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    ActionButton(stringResource(R.string.action_install), p, primary = true) { start() }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The step, the log's last lines, and after a failure the reason with the way back. */
@Composable
private fun ProgressCard(state: ServerInstall.State, p: OlcosPalette, onRetry: () -> Unit, onChange: () -> Unit) {
    val failed = state.phase == ServerInstall.Phase.FAILED
    OlcCard(padding = 0.dp) {
        Column(Modifier.padding(16.dp)) {
            if (failed) {
                Text(stringResource(R.string.add_server_failed_fmt, state.error ?: ""), style = MaterialTheme.typography.titleMedium, color = p.red)
            } else {
                Text(stringResource(R.string.add_server_running), style = MaterialTheme.typography.titleMedium, color = p.amber)
                Spacer(Modifier.height(4.dp))
                Text(state.step, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.add_server_keep_open), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
            val tail = state.log.lines().filter { it.isNotBlank() }.takeLast(8)
            if (tail.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    tail.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = p.textSecondary,
                )
            }
            if (failed) {
                Spacer(Modifier.height(12.dp))
                ActionButton(stringResource(R.string.action_retry), p, primary = true, onClick = onRetry)
                Spacer(Modifier.height(8.dp))
                ActionButton(stringResource(R.string.action_change_settings), p, primary = false, onClick = onChange)
            }
        }
    }
}

@Composable
private fun Label(text: String, p: OlcosPalette) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = p.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    p: OlcosPalette,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = singleLine, minLines = minLines,
        label = { Text(label) },
        keyboardOptions = keyboard,
        visualTransformation = visual,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accentTint, unfocusedBorderColor = p.separator,
            focusedLabelColor = p.accentTint, unfocusedLabelColor = p.textSecondary,
            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary, cursorColor = p.accentTint,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
