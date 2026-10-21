package io.olcos.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.core.AppLog
import io.olcos.app.core.Maintenance
import io.olcos.app.core.RoomRotation
import io.olcos.app.core.RoomRule
import io.olcos.app.core.RoomRules
import io.olcos.app.core.RoomWindow
import io.olcos.app.core.RoomServices
import io.olcos.app.core.TelegramProxyLink
import io.olcos.app.core.TunnelManager
import io.olcos.app.data.AppSettings
import io.olcos.app.data.Appearance
import io.olcos.app.data.TunnelMode
import io.olcos.app.data.WebSessions
import io.olcos.app.model.DirectRoutes
import io.olcos.app.model.OlcrtcLink

/**
 * The third tab, in eight groups and a folded one at the end: connection, rooms,
 * accounts, network, speed, diagnostics, look, about; advanced holds what few
 * people touch. Each setting has one home here.
 */
@Composable
fun SettingsScreen(onOpenLogs: () -> Unit) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val settings = AppGraph.settings
    val mode by settings.mode.collectAsStateWithLifecycle()
    val port by settings.socksPort.collectAsStateWithLifecycle()
    val dns by settings.dns.collectAsStateWithLifecycle()
    val socksUser by settings.socksUser.collectAsStateWithLifecycle()
    val socksPass by settings.socksPass.collectAsStateWithLifecycle()
    val vp8FPS by settings.vp8FPS.collectAsStateWithLifecycle()
    val vp8Batch by settings.vp8Batch.collectAsStateWithLifecycle()
    val listDirect by settings.routingListDirect.collectAsStateWithLifecycle()
    val russian by settings.routingRussian.collectAsStateWithLifecycle()
    val own by settings.routingOwn.collectAsStateWithLifecycle()
    val state by TunnelManager.state.collectAsStateWithLifecycle()
    val activeMode by TunnelManager.activeMode.collectAsStateWithLifecycle()
    val session by TunnelManager.session.collectAsStateWithLifecycle()
    val locked = state !is TunnelManager.State.Disconnected && state !is TunnelManager.State.Failed
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var dnsText by remember(dns) { mutableStateOf(dns) }
    var userText by remember(socksUser) { mutableStateOf(socksUser) }
    var passText by remember(socksPass) { mutableStateOf(socksPass) }
    var ownText by remember(own) { mutableStateOf(own) }
    // The sign-in pages the Accounts rows open, the way the server page and the install form do.
    var yandexLogin by remember { mutableStateOf(false) }
    var vkLogin by remember { mutableStateOf(false) }
    var mtsLogin by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val clipboard = LocalClipboardManager.current
    val proxyLive = state is TunnelManager.State.Connected && activeMode == TunnelMode.PROXY && session != null

    if (yandexLogin) {
        YandexLoginScreen(
            onSession = { session, twin ->
                AppGraph.yandex.store(session, twin)
                yandexLogin = false
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
            },
            onBack = { vkLogin = false },
        )
        return
    }
    if (mtsLogin) {
        // From Settings there is no room to fill: the sheet signs in and, when the owner ends a lesson, learns.
        MTSLinkLoginScreen(
            onSession = { credential -> AppGraph.mts.store(credential) },
            onMeeting = null,
            onBack = { mtsLogin = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
    OlcLargeTitle(stringResource(R.string.tab_settings))
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Metrics.s4),
    ) {
        Spacer(Modifier.height(Metrics.s2))

        // 1. Connection: how the tunnel runs, and what happens when the app opens or a connection stops working.
        val autoConnect by settings.autoConnect.collectAsStateWithLifecycle()
        val checkOnOpen by settings.checkOnOpen.collectAsStateWithLifecycle()
        val autoFailover by settings.autoFailover.collectAsStateWithLifecycle()
        OlcGroup(
            header = stringResource(R.string.settings_section_connection),
            footer = if (locked) stringResource(R.string.settings_mode_locked) else null,
        ) {
            ModeRow(stringResource(R.string.settings_mode_vpn), stringResource(R.string.settings_mode_vpn_hint),
                selected = mode == TunnelMode.VPN, enabled = !locked, p = p) { settings.setMode(TunnelMode.VPN) }
            OlcRowDivider()
            ModeRow(stringResource(R.string.settings_mode_proxy), stringResource(R.string.settings_mode_proxy_hint),
                selected = mode == TunnelMode.PROXY, enabled = !locked, p = p) { settings.setMode(TunnelMode.PROXY) }
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_auto_connect), autoConnect, settings::setAutoConnect,
                subtitle = stringResource(R.string.settings_auto_connect_hint))
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_check_on_open), checkOnOpen, settings::setCheckOnOpen,
                subtitle = stringResource(R.string.settings_check_on_open_hint))
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_auto_failover), autoFailover, settings::setAutoFailover,
                subtitle = stringResource(R.string.settings_auto_failover_hint))
        }
        Spacer(Modifier.height(Metrics.s6))
        // 2. Rooms: how often a protocol's rooms are made fresh, and the connections left alone.
        // A protocol shows only the options it really has (RoomRules).
        val roomRules by settings.roomRules.collectAsStateWithLifecycle()
        val roomWindows by settings.roomWindows.collectAsStateWithLifecycle()
        var openRule by rememberSaveable { mutableStateOf<String?>(null) }
        val skipped by settings.roomRuleExceptions.collectAsStateWithLifecycle()
        val records by AppGraph.store.records.collectAsStateWithLifecycle()
        val servers by AppGraph.servers.servers.collectAsStateWithLifecycle()
        val covered = remember(records, servers, roomRules) { RoomRotation.coveredConnections(records, servers, roomRules) }
        OlcGroup(header = stringResource(R.string.settings_section_rotation), footer = stringResource(R.string.rotation_rooms_footer)) {
            RoomRules.PROTOCOLS.forEachIndexed { index, carrier ->
                if (index > 0) OlcRowDivider()
                RuleRow(carrier, RoomRules.ruleOf(roomRules, carrier), RoomRules.windowOf(roomWindows, carrier), expanded = openRule == carrier, p = p,
                    onToggle = { openRule = if (openRule == carrier) null else carrier },
                    onRule = { rule -> settings.setRoomRule(carrier, rule) },
                    onWindow = { window -> settings.setRoomWindow(carrier, window) })
            }
        }
        Spacer(Modifier.height(Metrics.s4))
        OlcGroup(header = stringResource(R.string.rotation_exceptions), footer = stringResource(R.string.rotation_footer)) {
            if (covered.isEmpty()) {
                Text(
                    stringResource(R.string.rotation_exceptions_empty),
                    style = Type.caption, color = p.textSecondary,
                    modifier = Modifier.padding(Metrics.s4),
                )
            } else {
                covered.forEachIndexed { index, record ->
                    if (index > 0) OlcRowDivider()
                    OlcToggleRow(
                        record.displayName,
                        record.id in skipped,
                        { skip -> settings.setRoomRuleException(record.id, skip) },
                        subtitle = stringResource(R.string.rotation_skip),
                    )
                }
            }
        }
        Spacer(Modifier.height(Metrics.s6))

        SectionTitle(stringResource(R.string.settings_section_accounts), p)
        val yandexSignedIn by AppGraph.yandex.signedIn.collectAsStateWithLifecycle()
        val vkSignedIn by AppGraph.vk.signedIn.collectAsStateWithLifecycle()
        val mtsSignedIn by AppGraph.mts.signedIn.collectAsStateWithLifecycle()
        val mtsLearned by AppGraph.mtsTemplate.learned.collectAsStateWithLifecycle()
        OlcCard(padding = 0.dp) {
            Column(Modifier.padding(Metrics.s4)) {
                Text(stringResource(R.string.accounts_hint), style = Type.caption, color = p.textSecondary)
                Spacer(Modifier.height(Metrics.s3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_yandex), style = Type.bodyStrong, color = p.textPrimary)
                        Text(
                            stringResource(if (yandexSignedIn) R.string.accounts_signed_in else R.string.accounts_signed_out),
                            style = Type.caption, color = p.textSecondary,
                        )
                        // The sign-in page keeps its own copy of the sign-in, so the reset does not wait for a saved session.
                        if (!yandexSignedIn) {
                            Text(stringResource(R.string.accounts_reset_web), style = Type.label, color = p.accentTint,
                                modifier = Modifier.olcClickable {
                                    AppGraph.yandex.clear()
                                    WebSessions.clearYandex()
                                    Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 6.dp))
                        }
                    }
                    if (yandexSignedIn) {
                        Text(stringResource(R.string.action_sign_out), style = Type.label, color = p.red,
                            modifier = Modifier.olcClickable {
                                AppGraph.yandex.clear()
                                WebSessions.clearAll()
                                Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 4.dp))
                    } else {
                        Text(stringResource(R.string.action_sign_in), style = Type.label, color = p.accentTint,
                            modifier = Modifier.olcClickable { yandexLogin = true }.padding(vertical = 4.dp))
                    }
                }
                HorizontalDivider(color = p.separator, modifier = Modifier.padding(vertical = Metrics.s3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_vk), style = Type.bodyStrong, color = p.textPrimary)
                        Text(
                            stringResource(if (vkSignedIn) R.string.accounts_vk_signed_in else R.string.accounts_vk_signed_out),
                            style = Type.caption, color = p.textSecondary,
                        )
                        // The sign-in page keeps its own copy of the sign-in, so "not signed in" here does not mean the page is signed out: the reset is always on offer.
                        if (!vkSignedIn) {
                            Text(stringResource(R.string.accounts_reset_web), style = Type.label, color = p.accentTint,
                                modifier = Modifier.olcClickable {
                                    AppGraph.vk.clear()
                                    WebSessions.clearVK()
                                    Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 6.dp))
                        }
                    }
                    if (vkSignedIn) {
                        Text(stringResource(R.string.action_sign_out), style = Type.label, color = p.red,
                            modifier = Modifier.olcClickable {
                                AppGraph.vk.clear()
                                WebSessions.clearAll()
                                Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 4.dp))
                    } else {
                        Text(stringResource(R.string.action_sign_in), style = Type.label, color = p.accentTint,
                            modifier = Modifier.olcClickable { vkLogin = true }.padding(vertical = 4.dp))
                    }
                }
                HorizontalDivider(color = p.separator, modifier = Modifier.padding(vertical = Metrics.s3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_mtslink), style = Type.bodyStrong, color = p.textPrimary)
                        Text(
                            stringResource(if (mtsSignedIn) R.string.accounts_mtslink_signed_in else R.string.accounts_mtslink_signed_out),
                            style = Type.caption, color = p.textSecondary,
                        )
                        Text(
                            stringResource(if (mtsLearned) R.string.accounts_mtslink_learned else R.string.accounts_mtslink_not_learned),
                            style = Type.caption, color = if (mtsLearned) p.green else p.textSecondary,
                        )
                        if (mtsLearned) {
                            Text(stringResource(R.string.accounts_mtslink_forget), style = Type.label, color = p.accentTint,
                                modifier = Modifier.olcClickable {
                                    AppGraph.mtsTemplate.forget()
                                    // Nothing to replay any more: the schedule goes back to Off with it.
                                    settings.setRoomRule("mtslink", RoomRule.OFF)
                                    AppLog.i("olcos.mtslearn", "forgot what the app learned")
                                    Toast.makeText(context, R.string.accounts_mtslink_forgot, Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 6.dp))
                        }
                        // The page's own sign-in outlives the saved one, so the reset does not wait for a saved session.
                        if (!mtsSignedIn) {
                            Text(stringResource(R.string.accounts_reset_web), style = Type.label, color = p.accentTint,
                                modifier = Modifier.olcClickable {
                                    AppGraph.mts.clear()
                                    WebSessions.clearMTS()
                                    Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 6.dp))
                        }
                    }
                    if (mtsSignedIn) {
                        Text(stringResource(R.string.action_sign_out), style = Type.label, color = p.red,
                            modifier = Modifier.olcClickable {
                                AppGraph.mts.clear()
                                WebSessions.clearAll()
                                Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 4.dp))
                    } else {
                        Text(stringResource(R.string.action_sign_in), style = Type.label, color = p.accentTint,
                            modifier = Modifier.olcClickable { mtsLogin = true }.padding(vertical = 4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(Metrics.s6))
        // 4. Network: what goes direct, the DNS, and the local proxy's login with its Telegram button.
        OlcGroup(header = stringResource(R.string.settings_section_network), footer = stringResource(R.string.routing_applies_next)) {
            ModeRow(stringResource(R.string.routing_all), stringResource(R.string.routing_all_hint),
                selected = !listDirect, enabled = true, p = p) { settings.setRouting(false, russian, own) }
            OlcRowDivider()
            ModeRow(stringResource(R.string.routing_list), stringResource(R.string.routing_list_hint),
                selected = listDirect, enabled = true, p = p) { settings.setRouting(true, russian, own) }
            if (listDirect) {
                OlcRowDivider()
                Column(Modifier.padding(Metrics.s4)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.routing_russian), style = Type.bodyStrong, color = p.textPrimary)
                            Text(stringResource(R.string.routing_russian_hint_fmt, DirectRoutes.russian.size), style = Type.caption, color = p.textSecondary)
                        }
                        OlcSwitch(checked = russian, onCheckedChange = { settings.setRouting(true, it, own) })
                    }
                    Spacer(Modifier.height(Metrics.s3))
                    OutlinedTextField(
                        value = ownText,
                        onValueChange = { ownText = it; settings.setRouting(true, russian, it) },
                        label = { Text(stringResource(R.string.routing_own)) },
                        minLines = 3, colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                    )
                    val bad = remember(ownText) { DirectRoutes.parse(ownText).badLine }
                    Spacer(Modifier.height(Metrics.s1))
                    Text(
                        if (bad != null) stringResource(R.string.routing_bad_line_fmt, bad) else stringResource(R.string.routing_own_hint),
                        style = Type.caption, color = if (bad != null) p.orange else p.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(Metrics.s4))
        OlcGroup {
            Column(Modifier.padding(Metrics.s4)) {
                OutlinedTextField(
                    value = dnsText,
                    onValueChange = { typed ->
                        dnsText = typed
                        if (typed.isNotBlank()) settings.setDns(typed)
                    },
                    label = { Text(stringResource(R.string.settings_dns)) },
                    singleLine = true, enabled = !locked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                )
                val dnsBad = dnsText.isNotBlank() && !AppSettings.isValidDns(dnsText)
                Spacer(Modifier.height(Metrics.s1))
                Text(
                    stringResource(if (dnsBad) R.string.settings_dns_bad else R.string.settings_dns_hint),
                    style = Type.caption, color = if (dnsBad) p.orange else p.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(Metrics.s4))
        OlcGroup {
            Column(Modifier.padding(Metrics.s4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_socks_auth), style = Type.bodyStrong, color = p.textPrimary)
                        Text(stringResource(R.string.settings_socks_auth_hint), style = Type.caption, color = p.textSecondary)
                    }
                    OlcSwitch(
                        checked = socksUser.isNotEmpty(), enabled = !locked,
                        onCheckedChange = { on -> if (on) settings.setSocksLogin("olcos", randomPassword()) else settings.setSocksLogin("", "") },
                    )
                }
                if (socksUser.isNotEmpty()) {
                    var showPass by rememberSaveable { mutableStateOf(false) }
                    Spacer(Modifier.height(Metrics.s3))
                    OutlinedTextField(
                        value = userText,
                        onValueChange = { userText = it; if (it.isNotBlank()) settings.setSocksLogin(it, passText) },
                        label = { Text(stringResource(R.string.settings_socks_user)) }, singleLine = true, enabled = !locked,
                        colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Metrics.s2))
                    OutlinedTextField(
                        value = passText,
                        onValueChange = { passText = it; settings.setSocksLogin(userText, it) },
                        label = { Text(stringResource(R.string.settings_socks_pass)) }, singleLine = true, enabled = !locked,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(if (showPass) R.string.action_hide_password else R.string.action_show_password),
                                    tint = p.textSecondary,
                                )
                            }
                        },
                        colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(Metrics.s2))
                Text(stringResource(R.string.settings_socks_apps_hint), style = Type.caption, color = p.textSecondary)
                Spacer(Modifier.height(Metrics.s4))
                ActionButton(stringResource(R.string.action_use_in_telegram), p, primary = proxyLive) {
                    if (proxyLive) {
                        TelegramProxyLink.open(context, session?.port ?: port, socksUser, socksPass)
                    } else {
                        Toast.makeText(context, R.string.telegram_needs_proxy, Toast.LENGTH_LONG).show()
                    }
                }
                Spacer(Modifier.height(Metrics.s2))
                Text(stringResource(R.string.telegram_hint), style = Type.caption, color = p.textSecondary)
            }
        }
        Spacer(Modifier.height(Metrics.s6))
        // 5. Speed: the numbers for rooms carried as video.
        OlcGroup(header = stringResource(R.string.settings_section_speed), footer = stringResource(R.string.settings_vp8_hint)) {
            Column(Modifier.padding(Metrics.s4)) {
                Text(stringResource(R.string.settings_vp8_fps_fmt, vp8FPS), style = Type.body, color = p.textPrimary)
                Slider(
                    value = vp8FPS.toFloat(), onValueChange = { settings.setVP8(it.toInt(), vp8Batch) },
                    valueRange = 5f..60f, steps = 10, enabled = !locked,
                    colors = SliderDefaults.colors(thumbColor = p.accentTint, activeTrackColor = p.accentTint, inactiveTrackColor = p.separator),
                )
                Text(stringResource(R.string.settings_vp8_batch_fmt, vp8Batch), style = Type.body, color = p.textPrimary)
                Slider(
                    value = vp8Batch.toFloat(), onValueChange = { settings.setVP8(vp8FPS, it.toInt()) },
                    valueRange = 1f..256f, enabled = !locked,
                    colors = SliderDefaults.colors(thumbColor = p.accentTint, activeTrackColor = p.accentTint, inactiveTrackColor = p.separator),
                )
                Text(stringResource(R.string.settings_vp8_mtslink_note), style = Type.caption, color = p.textSecondary,
                    modifier = Modifier.padding(top = Metrics.s1))
            }
        }
        Spacer(Modifier.height(Metrics.s6))
        // 6. Diagnostics: the log.
        OlcGroup(header = stringResource(R.string.settings_section_diagnostics)) {
            OlcRow(
                title = stringResource(R.string.logs_title),
                subtitle = stringResource(R.string.logs_lines_fmt, AppLog.count()),
                chevron = true,
                onClick = onOpenLogs,
            )
            OlcRowDivider()
            OlcRow(
                title = stringResource(R.string.action_copy_logs),
                titleColor = p.accentTint,
                onClick = {
                    clipboard.setText(AnnotatedString(AppLog.dump()))
                    Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                },
            )
        }
        Spacer(Modifier.height(Metrics.s6))
        // 7. Look: the theme, the language, and hiding IP addresses for screenshots.
        val appearance by settings.appearance.collectAsStateWithLifecycle()
        val maskIPs by settings.maskIPs.collectAsStateWithLifecycle()
        OlcGroup(header = stringResource(R.string.settings_section_appearance)) {
            Column(Modifier.padding(Metrics.s4), verticalArrangement = Arrangement.spacedBy(Metrics.s2)) {
                Text(stringResource(R.string.appearance_theme), style = Type.body, color = p.textPrimary)
                OlcSegmented(
                    selection = appearance,
                    options = listOf(
                        OlcOption(Appearance.SYSTEM, stringResource(R.string.appearance_system)),
                        OlcOption(Appearance.LIGHT, stringResource(R.string.appearance_light)),
                        OlcOption(Appearance.DARK, stringResource(R.string.appearance_dark)),
                    ),
                    onSelect = settings::setAppearance,
                )
            }
            // The language is the system's own per-app choice (Android 13+).
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                OlcRowDivider()
                OlcRow(
                    title = stringResource(R.string.settings_language),
                    subtitle = stringResource(R.string.settings_language_hint),
                    chevron = true,
                    onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", context.packageName, null)))
                        }
                    },
                )
            }
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_mask_ips), maskIPs, settings::setMaskIPs,
                subtitle = stringResource(R.string.settings_mask_ips_hint))
        }
        Spacer(Modifier.height(Metrics.s6))
        // 8. About.
        OlcGroup(header = stringResource(R.string.settings_section_about)) {
            OlcRow(title = stringResource(R.string.settings_about, version))
        }
        Spacer(Modifier.height(Metrics.s6))
        // Advanced, at the very end and folded: what few people touch.
        var advanced by rememberSaveable { mutableStateOf(false) }
        val keepAlive by settings.keepAlive.collectAsStateWithLifecycle()
        OlcGroup(header = stringResource(R.string.settings_section_advanced)) {
            OlcRow(
                title = stringResource(if (advanced) R.string.settings_advanced_hide else R.string.settings_advanced_show),
                titleColor = p.accentTint,
                onClick = { advanced = !advanced },
            )
            if (advanced) {
                OlcRowDivider()
                Column(Modifier.padding(Metrics.s4)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { typed ->
                            val digits = typed.filter { it.isDigit() }.take(5)
                            portText = digits
                            digits.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(settings::setSocksPort)
                        },
                        label = { Text(stringResource(R.string.settings_socks_port)) },
                        singleLine = true, enabled = !locked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                    )
                    // A port outside the range is not kept: say so, instead of keeping quiet.
                    val portBad = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: portText.isNotEmpty()
                    if (portBad) {
                        Spacer(Modifier.height(Metrics.s1))
                        Text(stringResource(R.string.settings_port_bad), style = Type.caption, color = p.orange)
                    }
                }
                OlcRowDivider()
                OlcToggleRow(stringResource(R.string.settings_keep_alive), keepAlive, settings::setKeepAlive,
                    subtitle = stringResource(R.string.settings_keep_alive_hint))
            }
        }
        Spacer(Modifier.height(Metrics.s7))
    }
    }
}

private fun randomPassword(): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return (1..12).map { alphabet.random() }.joinToString("")
}

@Composable
private fun SectionTitle(text: String, @Suppress("UNUSED_PARAMETER") p: OlcosPalette) {
    OlcSectionHeader(text)
}

@Composable
private fun ModeRow(title: String, hint: String, selected: Boolean, enabled: Boolean, p: OlcosPalette, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .olcClickable(enabled = enabled, selection = true, onClick = onSelect)
            .padding(start = Metrics.s4, top = Metrics.s3, end = Metrics.s4, bottom = Metrics.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = if (enabled) p.textPrimary else p.textSecondary)
            Text(hint, style = Type.caption, color = p.textSecondary)
        }
        if (selected) {
            Spacer(Modifier.width(Metrics.s2))
            Icon(Icons.Filled.Check, contentDescription = null, tint = p.accentTint, modifier = Modifier.size(22.dp))
        }
    }
}

/** How the "when" of a protocol is chosen: the three kinds of [RoomWindow]. */
private enum class WindowKind { ANY, NIGHT, HOUR }

private fun kindOf(window: RoomWindow): WindowKind = when (window) {
    RoomWindow.Anytime -> WindowKind.ANY
    RoomWindow.Night -> WindowKind.NIGHT
    is RoomWindow.Hour -> WindowKind.HOUR
}

/**
 * One protocol of the room auto-update: its name and the choice in a line
 * («Раз в сутки · ночью»); one tap opens how often and, for a protocol on a
 * schedule, when. A protocol the app cannot make rooms for right now shows
 * what it is held at, dimmed, with nothing to change.
 */
@Composable
private fun RuleRow(
    carrier: String,
    rule: RoomRule,
    window: RoomWindow,
    expanded: Boolean,
    p: OlcosPalette,
    onToggle: () -> Unit,
    onRule: (RoomRule) -> Unit,
    onWindow: (RoomWindow) -> Unit,
) {
    val open = Maintenance.canMakeRoom(carrier) && (carrier != "mtslink" || RoomServices.MTSLink.canCreate)
    val summary = stringResource(ruleLabel(rule)) + if (RoomRules.hasWindow(rule)) when (window) {
        RoomWindow.Anytime -> ""
        RoomWindow.Night -> " · " + stringResource(R.string.rotation_when_night_short)
        is RoomWindow.Hour -> " · " + stringResource(R.string.rotation_when_hour_short_fmt, hourText(window.hour))
    } else ""
    Column(Modifier.fillMaxWidth()) {
        OlcRow(
            title = OlcrtcLink.carrierLabel(carrier),
            subtitle = summary,
            titleColor = if (open) null else p.textSecondary,
            chevron = true,
            onClick = onToggle,
        )
        if (expanded) {
            Column(
                Modifier.padding(start = Metrics.s4, end = Metrics.s4, bottom = Metrics.s4).alpha(if (open) 1f else 0.5f),
                verticalArrangement = Arrangement.spacedBy(Metrics.s2),
            ) {
                Text(stringResource(ruleNote(carrier)), style = Type.caption, color = p.textSecondary)
                if (RoomRules.allowed(carrier).size > 1 || !open) {
                    Text(stringResource(R.string.rotation_how_often), style = Type.captionStrong, color = p.textSecondary)
                    OlcChipPicker(
                        selection = rule,
                        options = RoomRules.allowed(carrier).map { OlcOption(it, stringResource(ruleLabel(it)), disabled = !open) },
                        onSelect = onRule,
                    )
                }
                if (open && RoomRules.hasWindow(rule)) {
                    Text(stringResource(R.string.rotation_when), style = Type.captionStrong, color = p.textSecondary)
                    OlcSegmented(
                        selection = kindOf(window),
                        options = listOf(
                            OlcOption(WindowKind.ANY, stringResource(R.string.rotation_when_any)),
                            OlcOption(WindowKind.NIGHT, stringResource(R.string.rotation_when_night)),
                            OlcOption(WindowKind.HOUR, stringResource(R.string.rotation_when_hour)),
                        ),
                        onSelect = { kind ->
                            onWindow(when (kind) {
                                WindowKind.ANY -> RoomWindow.Anytime
                                WindowKind.NIGHT -> RoomWindow.Night
                                WindowKind.HOUR -> window as? RoomWindow.Hour ?: RoomWindow.hour(DEFAULT_HOUR)
                            })
                        },
                    )
                    when (window) {
                        RoomWindow.Anytime -> {}
                        RoomWindow.Night -> Text(stringResource(R.string.rotation_night_note), style = Type.caption, color = p.textSecondary)
                        is RoomWindow.Hour -> {
                            Text(stringResource(R.string.rotation_hour_note), style = Type.caption, color = p.textSecondary)
                            OlcChipPicker(
                                selection = window.hour,
                                options = (0..23).map { OlcOption(it, hourText(it)) },
                                onSelect = { onWindow(RoomWindow.hour(it)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The hour offered first when "at a chosen hour" is picked: the middle of the night. */
private const val DEFAULT_HOUR = 3

private fun hourText(hour: Int): String = "%02d:00".format(hour)

/** How a protocol's rooms behave, in one line. */
private fun ruleNote(carrier: String): Int = when {
    carrier == "telemost" -> R.string.rotation_note_telemost
    carrier == "mtslink" -> if (RoomServices.MTSLink.canCreate) R.string.rotation_note_mtslink_limit else R.string.rotation_note_mtslink
    else -> R.string.rotation_note_lasting
}

private fun ruleLabel(rule: RoomRule): Int = when (rule) {
    RoomRule.OFF -> R.string.rotation_off
    RoomRule.AUTOMATIC -> R.string.rotation_automatic
    RoomRule.EVERY_6_HOURS -> R.string.rotation_every_6h
    RoomRule.EVERY_12_HOURS -> R.string.rotation_every_12h
    RoomRule.DAILY -> R.string.rotation_daily
    RoomRule.EVERY_3_DAYS -> R.string.rotation_every_3d
    RoomRule.WEEKLY -> R.string.rotation_weekly
}
@Composable
private fun fieldColors(p: OlcosPalette) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = p.accentTint,
    unfocusedBorderColor = p.fillBorder,
    focusedLabelColor = p.accentTint,
    unfocusedLabelColor = p.textSecondary,
    focusedTextColor = p.textPrimary,
    unfocusedTextColor = p.textPrimary,
    cursorColor = p.accentTint,
)
