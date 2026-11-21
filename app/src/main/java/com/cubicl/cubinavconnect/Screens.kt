package com.cubicl.cubinavconnect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private val PAGE_PAD = 16.dp

// =============================================================================================
// Device tab
// =============================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen() {
    val state by NavController.bleState.collectAsState()
    val found by NavController.bleFound.collectAsState()
    val scanning by NavController.bleScanning.collectAsState()
    val batt by NavController.battery.collectAsState()
    val s by NavController.prefs.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PAGE_PAD),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(TIcons.Bluetooth, stringResource(R.string.tab_device), stringResource(R.string.device_subtitle))

        Section(stringResource(R.string.device_connection), TIcons.Bluetooth) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(TIcons.Bluetooth, bleLabel(state), tint = bleColor(state))
                if (state == BleState.CONNECTED) InfoChip(null, if (batt >= 0) stringResource(R.string.device_battery, batt) else stringResource(R.string.device_battery_unknown))
            }
            if (s.deviceAddress.isNotEmpty())
                Text(stringResource(R.string.device_saved, s.deviceName.ifEmpty { "CubiNav" }, s.deviceAddress), style = MaterialTheme.typography.bodyMedium)
            if (state == BleState.BONDING)
                Text(stringResource(R.string.find_passkey), color = MaterialTheme.colorScheme.tertiary)
            if (state == BleState.OFF)
                Text(stringResource(R.string.find_bt_off), color = MaterialTheme.colorScheme.error)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { NavController.scan() }, enabled = !scanning) {
                    BtnContent(TIcons.Search, stringResource(if (scanning) R.string.find_scanning else R.string.device_scan))
                }
                if (s.deviceAddress.isNotEmpty()) {
                    FilledTonalButton(onClick = { NavController.connect(s.deviceAddress, s.deviceName) }) { BtnContent(TIcons.Refresh, stringResource(R.string.device_reconnect)) }
                    if (state == BleState.CONNECTED)
                        FilledTonalButton(onClick = { NavController.beginSetupManually() }) { BtnContent(TIcons.Settings, stringResource(R.string.device_run_setup)) }
                    OutlinedButton(onClick = { NavController.forgetDevice() }) { BtnContent(TIcons.Delete, stringResource(R.string.device_forget)) }
                }
            }
        }

        Section(stringResource(R.string.device_nearby), TIcons.Search) {
            if (found.isEmpty()) Text(stringResource(R.string.device_none_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
            found.forEach { d ->
                androidx.compose.runtime.key(d.address) {
                    AppearOnEnter(Modifier.fillMaxWidth()) {
                        Card(
                            Modifier.fillMaxWidth().clickable { NavController.connect(d.address, d.name) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(TIcons.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(d.name ?: stringResource(R.string.device_unnamed), fontWeight = FontWeight.Medium)
                                    Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${d.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        OtaSection(state == BleState.CONNECTED, s.binUrl, s.readmeUrl, s.advanced)

        Text(
            stringResource(R.string.device_tip),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OtaSection(connected: Boolean, binUrl: String, readmeUrl: String, advanced: Boolean) {
    val ui by Ota.ui.collectAsState()
    val trace by Ota.trace.collectAsState()
    val fw by NavController.deviceFw.collectAsState()
    val navPhase by NavController.phase.collectAsState()
    val view = LocalView.current
    val busy = ui.phase == OtaPhase.CHECKING || ui.phase == OtaPhase.DOWNLOADING ||
            ui.phase == OtaPhase.TRANSFERRING || ui.phase == OtaPhase.REBOOTING

    // keep the screen on while an update runs (a sleeping phone slows or drops the BLE link)
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Section(stringResource(R.string.ota_title), TIcons.Refresh) {
        Text(stringResource(R.string.ota_installed, if (fw >= 0) Ota.fmtVersion(fw) else stringResource(R.string.ota_unknown)))
        val canReadme = connected && fw >= 0 && !busy && navPhase == Phase.IDLE && readmeUrl.isNotBlank() && binUrl.isNotBlank()
        val canDirect = connected && fw >= 0 && !busy && navPhase == Phase.IDLE && binUrl.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { Ota.updateFromReadme() }, enabled = canReadme) { BtnContent(TIcons.Refresh, stringResource(R.string.ota_check)) }
            if (busy && ui.phase != OtaPhase.REBOOTING) OutlinedButton(onClick = { Ota.cancel() }) { BtnContent(TIcons.Close, stringResource(R.string.common_cancel)) }
        }
        if (advanced)
            OutlinedButton(onClick = { Ota.installDirect() }, enabled = canDirect) { BtnContent(TIcons.Warning, stringResource(R.string.ota_force)) }

        if (ui.message.isNotEmpty())
            Text(ui.message, color = if (ui.phase == OtaPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (ui.phase == OtaPhase.DOWNLOADING || ui.phase == OtaPhase.TRANSFERRING || ui.phase == OtaPhase.REBOOTING) {
            if (ui.progress < 0f || ui.phase == OtaPhase.REBOOTING) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
        }
        if (ui.phase == OtaPhase.DONE || ui.phase == OtaPhase.ERROR)
            TextButton(onClick = { Ota.dismiss() }) { Text(stringResource(R.string.common_dismiss)) }
        if (trace.isNotEmpty() && (advanced || ui.phase == OtaPhase.ERROR)) {
            Text(stringResource(R.string.ota_log_title), style = MaterialTheme.typography.titleSmall)
            trace.takeLast(14).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        if (!advanced)
            Text(stringResource(R.string.ota_sources_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.ota_keep_open),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =============================================================================================
// Settings tab
// =============================================================================================

@Composable
fun SettingsScreen() {
    val s by NavController.prefs.settings.collectAsState()
    val caps by NavController.caps.collectAsState()
    val prefs = NavController.prefs
    val chk = NavController.roadCheck(s, caps)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PAGE_PAD),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(TIcons.Settings, stringResource(R.string.tab_settings))

        Section(stringResource(R.string.settings_language), TIcons.Settings) {
            val ctx = LocalContext.current
            val bleSt by NavController.bleState.collectAsState()
            val configured by NavController.deviceConfigured.collectAsState()
            var pendingLang by remember { mutableStateOf<Int?>(null) }
            LanguagePicker(s.language) { id ->
                if (id != s.language) {
                    // a connected, already-configured display can switch language too (it restarts)
                    if (bleSt == BleState.CONNECTED && configured == true) pendingLang = id
                    else { NavController.changeLanguage(id, alsoDevice = false); ctx.findActivity()?.recreate() }
                }
            }
            pendingLang?.let { id ->
                AlertDialog(
                    onDismissRequest = { pendingLang = null },
                    title = { Text(stringResource(R.string.lang_device_title, Lang.nativeName(id))) },
                    text = { Text(stringResource(R.string.lang_device_body)) },
                    confirmButton = {
                        TextButton(onClick = { pendingLang = null; NavController.changeLanguage(id, alsoDevice = true); ctx.findActivity()?.recreate() }) {
                            Text(stringResource(R.string.lang_device_both))
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { pendingLang = null; NavController.changeLanguage(id, alsoDevice = false); ctx.findActivity()?.recreate() }) {
                                Text(stringResource(R.string.lang_device_app_only))
                            }
                            TextButton(onClick = { pendingLang = null }) { Text(stringResource(R.string.common_cancel)) }
                        }
                    },
                )
            }
            Text(stringResource(R.string.settings_language_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(stringResource(R.string.settings_roads), TIcons.Navigation) {
            val universe = caps?.classes?.takeIf { it.isNotEmpty() } ?: if (s.allRoads) Roads.ALL else Roads.STOCK
            Roads.ALL.filter { it in universe }.forEach { c ->
                SwitchRow(Roads.label(c), c in s.allowed) { on ->
                    prefs.update { it.copy(allowed = if (on) it.allowed + c else it.allowed - c) }
                }
            }
            caps?.let { c ->
                val missing = Roads.ALL.filter { it !in c.classes }
                if (c.classes.isNotEmpty() && missing.isNotEmpty())
                    Text(stringResource(R.string.roads_missing, missing.joinToString { Roads.label(it) }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            chk.message?.let { Text(it, color = if (chk.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            caps?.presets?.takeIf { it.isNotEmpty() }?.let { presets ->
                HorizontalDivider()
                Text(stringResource(R.string.roads_presets), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.presetId.isEmpty(), onClick = { prefs.update { it.copy(presetId = "") } })
                    Text(stringResource(R.string.roads_presets_none))
                }
                presets.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.presetId == p.id, onClick = { prefs.update { it.copy(presetId = p.id) } })
                        Text(p.label)
                    }
                }
            }
            if (caps == null)
                Text(stringResource(R.string.roads_no_caps),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(stringResource(R.string.settings_display), TIcons.Map) {
            SwitchRow(stringResource(R.string.set_imperial), s.imperial, stringResource(R.string.set_imperial_hint)) { v -> prefs.update { it.copy(imperial = v) }; NavController.onSettingsChanged() }
            SwitchRow(stringResource(R.string.set_heading_up), s.headingUp) { v -> prefs.update { it.copy(headingUp = v) }; NavController.onSettingsChanged() }
            SwitchRow(stringResource(R.string.set_invert), s.invert) { v -> prefs.update { it.copy(invert = v) }; NavController.onSettingsChanged() }
            SwitchRow(stringResource(R.string.set_fps), s.showFps) { v -> prefs.update { it.copy(showFps = v) }; NavController.onSettingsChanged() }
            HorizontalDivider()
            Text(stringResource(R.string.set_zoom, s.zoom))
            Slider(value = s.zoom.toFloat(), onValueChange = { v -> prefs.update { it.copy(zoom = v.toInt()) } },
                onValueChangeFinished = { NavController.onSettingsChanged() }, valueRange = 20f..200f)
        }

        Section(stringResource(R.string.settings_gps), TIcons.MyLocation) {
            Text(stringResource(R.string.set_gps_acc, s.maxAccuracy))
            Slider(value = s.maxAccuracy.toFloat(), onValueChange = { v -> prefs.update { it.copy(maxAccuracy = v.toInt()) } }, valueRange = 10f..100f)
        }

        Section(stringResource(R.string.settings_contact), TIcons.Info) {
            var email by remember(s.contactEmail) { mutableStateOf(s.contactEmail) }
            val ok = Contact.isValid(email)
            OutlinedTextField(
                value = email,
                onValueChange = { v -> email = v; if (Contact.isValid(v)) NavController.setContactEmail(v) },
                label = { Text(stringResource(R.string.set_email_label)) }, singleLine = true,
                isError = !ok, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                supportingText = { if (!ok) Text(stringResource(R.string.set_email_error)) },
            )
        }

        Section(stringResource(R.string.settings_about), TIcons.Info) {
            Text(
                stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- one toggle hides every link, the firmware sources and the developer tools ----
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(TIcons.Build, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.adv_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(stringResource(R.string.adv_sub),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                androidx.compose.material3.Switch(checked = s.advanced, onCheckedChange = { v ->
                    prefs.update { it.copy(advanced = v, debug = if (v) it.debug else false) }
                    if (!v) { NavController.stopSimulation(); NavController.stopReplay() }
                })
            }
        }

        // Reveal carries its own animation; the Column keeps the 14dp rhythm between the cards inside it.
        Reveal(visible = s.advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Section(stringResource(R.string.adv_servers), TIcons.Map) {
                    UrlField(stringResource(R.string.adv_osrm_url), s.osrmUrl) { v -> prefs.update { it.copy(osrmUrl = v.trim()) } }
                    UrlField(stringResource(R.string.adv_osrm_profile), s.profile) { v -> prefs.update { it.copy(profile = v.trim()) } }
                    UrlField(stringResource(R.string.adv_caps_url), s.capsUrl) { v -> prefs.update { it.copy(capsUrl = v.trim()) } }
                    UrlField(stringResource(R.string.adv_nominatim_url), s.nominatimUrl) { v -> prefs.update { it.copy(nominatimUrl = v.trim()) } }
                    UrlField(stringResource(R.string.adv_user_agent), s.userAgent) { v -> prefs.update { it.copy(userAgent = v) } }
                    UrlField(stringResource(R.string.adv_style_url), s.styleUrl) { v -> prefs.update { it.copy(styleUrl = v.trim()) } }
                    Text(
                        stringResource(R.string.adv_servers_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { NavController.onSettingsChanged() }) { BtnContent(TIcons.Check, stringResource(R.string.common_apply)) }
                        OutlinedButton(onClick = { NavController.onSettingsChanged(); NavController.refreshCapabilities() }) {
                            BtnContent(TIcons.Refresh, stringResource(R.string.adv_reload_caps))
                        }
                    }
                    SwitchRow(stringResource(R.string.adv_all_roads), s.allRoads) { v -> prefs.update { it.copy(allRoads = v) } }
                }

                Section(stringResource(R.string.adv_fw_sources), TIcons.Refresh) {
                    UrlField(stringResource(R.string.adv_readme_url), s.readmeUrl) { v -> prefs.update { it.copy(readmeUrl = v) } }
                    UrlField(stringResource(R.string.adv_bin_url), s.binUrl) { v -> prefs.update { it.copy(binUrl = v) } }
                    OutlinedTextField(
                        value = s.githubToken, onValueChange = { v -> prefs.update { it.copy(githubToken = v.trim()) } },
                        label = { Text(stringResource(R.string.adv_token)) }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    )
                    Text(
                        stringResource(R.string.adv_token_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Section(stringResource(R.string.adv_developer), TIcons.Build) {
                    SwitchRow(stringResource(R.string.adv_debug), s.debug, stringResource(R.string.adv_debug_hint)) { v ->
                        prefs.update { it.copy(debug = v) }
                        if (!v) { NavController.stopSimulation(); NavController.stopReplay() }
                    }
                }
                Reveal(visible = s.debug) { DebugPanel() }
            }
        }
    }
}

// =============================================================================================
// Debug tools (shown inside Settings > Advanced > Debug mode)
// =============================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugPanel() {
    val log by NavController.log.collectAsState()
    val pose by NavController.lastPose.collectAsState()
    val ack by NavController.lastAck.collectAsState()
    val replaying by NavController.replaying.collectAsState()
    val phase by NavController.phase.collectAsState()
    val sim by NavController.simulating.collectAsState()
    val paused by NavController.simPaused.collectAsState()
    var mult by remember { mutableStateOf(1f) }
    var simKmh by remember { mutableStateOf(40f) }
    var simStart by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) NavController.startReplay(uri, mult)
    }

    Section("Debug state", TIcons.Info) {
        Text("phase: ${phase.name.lowercase()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("last POSE: $pose", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("last ACK: $ack", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }

    Section("GPS simulator", TIcons.Play) {
        Text("Replaces GPS with a virtual car. Start it, pick a destination on the Map tab and press Start there: the car then drives the route and the display gets normal POSE traffic.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!sim) {
            OutlinedTextField(value = simStart, onValueChange = { simStart = it }, singleLine = true,
                label = { Text("Start: lat, lon (empty = current position)") }, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium)
            Button(onClick = { NavController.startSimulation(simStart, simKmh) }, enabled = !replaying) { BtnContent(TIcons.Play, "Start simulator") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(20f, 40f, 60f, 100f).forEach { v ->
                FilterChip(selected = simKmh == v, onClick = { simKmh = v; NavController.setSimSpeed(v) }, label = { Text("${v.toInt()} km/h") })
            }
        }
        if (sim) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { NavController.toggleSimPause() }) { BtnContent(if (paused) TIcons.Play else TIcons.Close, if (paused) "Resume" else "Pause") }
            OutlinedButton(onClick = { NavController.simVeerOff() }) { BtnContent(TIcons.Navigation, "Veer off") }
            Button(onClick = { NavController.stopSimulation() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { BtnContent(TIcons.Close, "Stop") }
        }
    }

    Section("GPX replay", TIcons.List) {
        Text("Pick a file, wait for the position to appear on the map, set a destination and press Start. The track then replaces GPS.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1f, 5f, 10f).forEach { m ->
                FilterChip(selected = mult == m, onClick = { mult = m }, label = { Text("${m.toInt()}x") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !replaying && !sim) { BtnContent(TIcons.List, "Replay GPX") }
            if (replaying) OutlinedButton(onClick = { NavController.stopReplay() }) { BtnContent(TIcons.Close, "Stop") }
        }
    }

    Section("Log", TIcons.List) {
        if (log.isEmpty()) Text("Empty.", style = MaterialTheme.typography.bodySmall)
        log.takeLast(80).reversed().forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}