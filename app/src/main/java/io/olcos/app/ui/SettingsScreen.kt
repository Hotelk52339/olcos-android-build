package io.olcos.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.olcos.app.AppGraph
import io.olcos.app.R
import io.olcos.app.core.AppLog
import io.olcos.app.core.TelegramProxyLink
import io.olcos.app.core.TunnelManager
import io.olcos.app.data.AppSettings
import io.olcos.app.data.Appearance
import io.olcos.app.data.TunnelMode
import io.olcos.app.data.WebSessions
import io.olcos.app.model.DirectRoutes

/** The third tab: mode, the tunnel, the proxy and Telegram, routing, the log, the version. */
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
    var wbLogin by remember { mutableStateOf(false) }
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
    if (wbLogin) {
        WBLoginScreen(
            onSession = { token ->
                AppGraph.wb.store(token)
                wbLogin = false
            },
            // Nothing is entered by hand here: back to the settings.
            onManual = { wbLogin = false },
            onBack = { wbLogin = false },
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

        SectionTitle(stringResource(R.string.settings_mode), p)
        OlcCard(padding = 0.dp) {
            ModeRow(stringResource(R.string.settings_mode_vpn), stringResource(R.string.settings_mode_vpn_hint),
                selected = mode == TunnelMode.VPN, enabled = !locked, p = p) { settings.setMode(TunnelMode.VPN) }
            OlcRowDivider()
            ModeRow(stringResource(R.string.settings_mode_proxy), stringResource(R.string.settings_mode_proxy_hint),
                selected = mode == TunnelMode.PROXY, enabled = !locked, p = p) { settings.setMode(TunnelMode.PROXY) }
        }
        if (locked) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_mode_locked), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        Spacer(Modifier.height(24.dp))

        // Appearance, as on iOS: the system's look or always light or dark;
        // the language is the system's own per-app choice (Android 13+).
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
            // Screenshot-safe: only what the screens show is masked.
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_mask_ips), maskIPs, settings::setMaskIPs,
                subtitle = stringResource(R.string.settings_mask_ips_hint))
        }
        Spacer(Modifier.height(24.dp))

        // When the app opens, as on iOS: the chosen connection joined, and a
        // check of which connections work.
        val autoConnect by settings.autoConnect.collectAsStateWithLifecycle()
        val checkOnOpen by settings.checkOnOpen.collectAsStateWithLifecycle()
        OlcGroup(header = stringResource(R.string.settings_section_on_open)) {
            OlcToggleRow(stringResource(R.string.settings_auto_connect), autoConnect, settings::setAutoConnect,
                subtitle = stringResource(R.string.settings_auto_connect_hint))
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_check_on_open), checkOnOpen, settings::setCheckOnOpen,
                subtitle = stringResource(R.string.settings_check_on_open_hint))
        }
        Spacer(Modifier.height(24.dp))

        // Staying connected, as on iOS: the checks while a session runs and
        // the move to a working connection.
        val keepAlive by settings.keepAlive.collectAsStateWithLifecycle()
        val autoFailover by settings.autoFailover.collectAsStateWithLifecycle()
        OlcGroup(header = stringResource(R.string.settings_section_staying)) {
            OlcToggleRow(stringResource(R.string.settings_keep_alive), keepAlive, settings::setKeepAlive,
                subtitle = stringResource(R.string.settings_keep_alive_hint))
            OlcRowDivider()
            OlcToggleRow(stringResource(R.string.settings_auto_failover), autoFailover, settings::setAutoFailover,
                subtitle = stringResource(R.string.settings_auto_failover_hint))
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle(stringResource(R.string.settings_section_tunnel), p)
        OlcCard(padding = 0.dp) {
            Column(Modifier.padding(16.dp)) {
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
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_port_bad), style = MaterialTheme.typography.bodySmall, color = p.orange)
                }
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (dnsBad) R.string.settings_dns_bad else R.string.settings_dns_hint),
                    style = MaterialTheme.typography.bodySmall, color = if (dnsBad) p.orange else p.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle(stringResource(R.string.settings_section_proxy), p)
        OlcCard(padding = 0.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_socks_auth), style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(stringResource(R.string.settings_socks_auth_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    }
                    OlcSwitch(
                        checked = socksUser.isNotEmpty(), enabled = !locked,
                        onCheckedChange = { on -> if (on) settings.setSocksLogin("olcos", randomPassword()) else settings.setSocksLogin("", "") },
                    )
                }
                if (socksUser.isNotEmpty()) {
                    var showPass by rememberSaveable { mutableStateOf(false) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = userText,
                        onValueChange = { userText = it; if (it.isNotBlank()) settings.setSocksLogin(it, passText) },
                        label = { Text(stringResource(R.string.settings_socks_user)) }, singleLine = true, enabled = !locked,
                        colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_socks_apps_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                Spacer(Modifier.height(16.dp))
                ActionButton(stringResource(R.string.action_use_in_telegram), p, primary = proxyLive) {
                    if (proxyLive) {
                        TelegramProxyLink.open(context, session?.port ?: port, socksUser, socksPass)
                    } else {
                        Toast.makeText(context, R.string.telegram_needs_proxy, Toast.LENGTH_LONG).show()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.telegram_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle(stringResource(R.string.settings_section_routing), p)
        OlcCard(padding = 0.dp) {
            ModeRow(stringResource(R.string.routing_all), stringResource(R.string.routing_all_hint),
                selected = !listDirect, enabled = true, p = p) { settings.setRouting(false, russian, own) }
            OlcRowDivider()
            ModeRow(stringResource(R.string.routing_list), stringResource(R.string.routing_list_hint),
                selected = listDirect, enabled = true, p = p) { settings.setRouting(true, russian, own) }
            if (listDirect) {
                OlcRowDivider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.routing_russian), style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                            Text(stringResource(R.string.routing_russian_hint_fmt, DirectRoutes.russian.size), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                        }
                        OlcSwitch(checked = russian, onCheckedChange = { settings.setRouting(true, it, own) })
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = ownText,
                        onValueChange = { ownText = it; settings.setRouting(true, russian, it) },
                        label = { Text(stringResource(R.string.routing_own)) },
                        minLines = 3, colors = fieldColors(p), modifier = Modifier.fillMaxWidth(),
                    )
                    val bad = remember(ownText) { DirectRoutes.parse(ownText).badLine }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (bad != null) stringResource(R.string.routing_bad_line_fmt, bad) else stringResource(R.string.routing_own_hint),
                        style = MaterialTheme.typography.bodySmall, color = if (bad != null) p.orange else p.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.routing_applies_next), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        Spacer(Modifier.height(24.dp))

        SectionTitle(stringResource(R.string.settings_section_accounts), p)
        val yandexSignedIn by AppGraph.yandex.signedIn.collectAsStateWithLifecycle()
        val vkSignedIn by AppGraph.vk.signedIn.collectAsStateWithLifecycle()
        val wbSignedIn by AppGraph.wb.signedIn.collectAsStateWithLifecycle()
        OlcCard(padding = 0.dp) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.accounts_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_yandex), style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(
                            stringResource(if (yandexSignedIn) R.string.accounts_signed_in else R.string.accounts_signed_out),
                            style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                        )
                    }
                    if (yandexSignedIn) {
                        Text(stringResource(R.string.action_sign_out), style = MaterialTheme.typography.labelLarge, color = p.red,
                            modifier = Modifier.clickable {
                                AppGraph.yandex.clear()
                                WebSessions.clearAll()
                                Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 4.dp))
                    } else {
                        Text(stringResource(R.string.action_sign_in), style = MaterialTheme.typography.labelLarge, color = p.accentTint,
                            modifier = Modifier.clickable { yandexLogin = true }.padding(vertical = 4.dp))
                    }
                }
                HorizontalDivider(color = p.separator, modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_vk), style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(
                            stringResource(if (vkSignedIn) R.string.accounts_vk_signed_in else R.string.accounts_vk_signed_out),
                            style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                        )
                        // The sign-in page keeps its own copy of the sign-in, so "not signed in" here does not mean the page is signed out: the reset is always on offer.
                        if (!vkSignedIn) {
                            Text(stringResource(R.string.accounts_reset_web), style = MaterialTheme.typography.labelLarge, color = p.accentTint,
                                modifier = Modifier.clickable {
                                    AppGraph.vk.clear()
                                    WebSessions.clearVK()
                                    Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 6.dp))
                        }
                    }
                    if (vkSignedIn) {
                        Text(stringResource(R.string.action_sign_out), style = MaterialTheme.typography.labelLarge, color = p.red,
                            modifier = Modifier.clickable {
                                AppGraph.vk.clear()
                                WebSessions.clearAll()
                                Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 4.dp))
                    } else {
                        Text(stringResource(R.string.action_sign_in), style = MaterialTheme.typography.labelLarge, color = p.accentTint,
                            modifier = Modifier.clickable { vkLogin = true }.padding(vertical = 4.dp))
                    }
                }
                HorizontalDivider(color = p.separator, modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.accounts_wb), style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(
                            stringResource(if (wbSignedIn) R.string.accounts_wb_signed_in else R.string.accounts_wb_signed_out),
                            style = MaterialTheme.typography.bodySmall, color = p.textSecondary,
                        )
                        // Same as VK: the page's own sign-in outlives the saved one, so the reset does not wait for a saved session.
                        if (!wbSignedIn) {
                            Text(stringResource(R.string.accounts_reset_web), style = MaterialTheme.typography.labelLarge, color = p.accentTint,
                                modifier = Modifier.clickable {
                                    AppGraph.wb.clear()
                                    WebSessions.clearWB()
                                    Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 6.dp))
                        }
                    }
                    if (wbSignedIn) {
                        Text(stringResource(R.string.action_sign_out), style = MaterialTheme.typography.labelLarge, color = p.red,
                            modifier = Modifier.clickable {
                                AppGraph.wb.clear()
                                WebSessions.clearAll()
                                Toast.makeText(context, R.string.accounts_signed_out_done, Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 4.dp))
                    } else {
                        Text(stringResource(R.string.action_sign_in), style = MaterialTheme.typography.labelLarge, color = p.accentTint,
                            modifier = Modifier.clickable { wbLogin = true }.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // The phone-minted rooms kept fresh on the owner's schedule. Telemost
        // renews by its own day (TelemostRenewal), so only the connections
        // whose own carrier is VK Calls or WB Stream are offered.
        val rotationHours by settings.roomRotation.collectAsStateWithLifecycle()
        val rotationIDs by settings.roomRotationIDs.collectAsStateWithLifecycle()
        val records by AppGraph.store.records.collectAsStateWithLifecycle()
        val rotatable = remember(records) { records.filter { it.olcrtc?.carrier in setOf("vkcalls", "wbstream") } }
        OlcGroup(header = stringResource(R.string.settings_section_rotation)) {
            Column(Modifier.padding(Metrics.s4), verticalArrangement = Arrangement.spacedBy(Metrics.s2)) {
                Text(stringResource(R.string.rotation_interval), style = Type.body, color = p.textPrimary)
                OlcSegmented(
                    selection = rotationHours,
                    options = listOf(
                        OlcOption(0, stringResource(R.string.rotation_off)),
                        OlcOption(24, stringResource(R.string.rotation_daily)),
                        OlcOption(168, stringResource(R.string.rotation_weekly)),
                    ),
                    onSelect = { settings.setRoomRotation(it) },
                )
                Text(stringResource(R.string.rotation_hint), style = Type.caption, color = p.textSecondary)
            }
            if (rotatable.isEmpty()) {
                OlcRowDivider()
                Text(
                    stringResource(R.string.rotation_no_connections),
                    style = Type.caption, color = p.textSecondary,
                    modifier = Modifier.padding(start = Metrics.s4, end = Metrics.s4, top = Metrics.s2),
                )
            } else {
                for (record in rotatable) {
                    OlcRowDivider()
                    OlcToggleRow(
                        record.displayName,
                        record.id in rotationIDs,
                        { on -> settings.setRoomRotationEnabled(record.id, on) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle(stringResource(R.string.settings_section_diagnostics), p)
        OlcCard(padding = 0.dp) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenLogs).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleMedium, color = p.accentTint)
                        Text(stringResource(R.string.logs_lines_fmt, AppLog.count()), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    }
                }
                OlcRowDivider()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        clipboard.setText(AnnotatedString(AppLog.dump()))
                        Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.action_copy_logs), style = MaterialTheme.typography.titleMedium, color = p.accentTint)
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Tuning few people need, folded away: the VP8 frame and packet rates.
        var advanced by rememberSaveable { mutableStateOf(false) }
        SectionTitle(stringResource(R.string.settings_section_advanced), p)
        OlcCard(padding = 0.dp) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (advanced) R.string.settings_advanced_hide else R.string.settings_advanced_show),
                        style = MaterialTheme.typography.titleMedium, color = p.accentTint,
                    )
                }
                if (advanced) {
                    OlcRowDivider()
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_vp8_fps_fmt, vp8FPS), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                        Slider(
                            value = vp8FPS.toFloat(), onValueChange = { settings.setVP8(it.toInt(), vp8Batch) },
                            valueRange = 5f..60f, steps = 10, enabled = !locked,
                            colors = SliderDefaults.colors(thumbColor = p.accentTint, activeTrackColor = p.accentTint, inactiveTrackColor = p.separator),
                        )
                        Text(stringResource(R.string.settings_vp8_batch_fmt, vp8Batch), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                        Slider(
                            value = vp8Batch.toFloat(), onValueChange = { settings.setVP8(vp8FPS, it.toInt()) },
                            valueRange = 1f..256f, enabled = !locked,
                            colors = SliderDefaults.colors(thumbColor = p.accentTint, activeTrackColor = p.accentTint, inactiveTrackColor = p.separator),
                        )
                        Text(stringResource(R.string.settings_vp8_hint), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle(stringResource(R.string.settings_section_updates), p)
        val latest by AppGraph.updates.latest.collectAsStateWithLifecycle()
        val available = AppGraph.updates.available
        var checkingUpdate by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        OlcCard(padding = 0.dp) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_about, version), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        available != null -> stringResource(R.string.update_available_fmt, available.tag)
                        latest != null -> stringResource(R.string.update_none_fmt, latest!!.tag)
                        else -> stringResource(R.string.update_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (available != null) p.green else p.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                if (available != null) {
                    ActionButton(stringResource(R.string.action_download_update), p, primary = true) {
                        val url = available.apkURL ?: available.url
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ActionButton(stringResource(R.string.action_check_updates), p, primary = available == null) {
                    if (!checkingUpdate) scope.launch {
                        checkingUpdate = true
                        val reached = AppGraph.updates.refresh()
                        val found = AppGraph.updates.available
                        checkingUpdate = false
                        // An unreachable GitHub is not "the latest version": say which it was.
                        Toast.makeText(context, when {
                            !reached -> context.getString(R.string.update_unreachable)
                            found != null -> context.getString(R.string.update_available_fmt, found.tag)
                            else -> context.getString(R.string.update_none_fmt, AppGraph.updates.latest.value?.tag ?: version)
                        }, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
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
            .clickable(enabled = enabled, onClick = onSelect)
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
