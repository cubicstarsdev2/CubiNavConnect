package com.tahvia.nav

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
        ScreenTitle(TIcons.Bluetooth, "Device", "Your TahVia display")

        Section("Connection", TIcons.Bluetooth) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(TIcons.Bluetooth, bleLabel(state), tint = bleColor(state))
                if (state == BleState.CONNECTED) InfoChip(null, "Battery ${if (batt >= 0) "$batt %" else "unknown"}")
            }
            if (s.deviceAddress.isNotEmpty())
                Text("Saved: ${s.deviceName.ifEmpty { "TahVia" }} (${s.deviceAddress})", style = MaterialTheme.typography.bodyMedium)
            if (state == BleState.BONDING)
                Text("Enter the passkey shown on the TahVia screen when Android asks for it.", color = MaterialTheme.colorScheme.tertiary)
            if (state == BleState.OFF)
                Text("Bluetooth is off. Turn it on in Android settings.", color = MaterialTheme.colorScheme.error)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { NavController.scan() }, enabled = !scanning) {
                    BtnContent(TIcons.Search, if (scanning) "Scanning..." else "Scan")
                }
                if (s.deviceAddress.isNotEmpty()) {
                    FilledTonalButton(onClick = { NavController.connect(s.deviceAddress, s.deviceName) }) { BtnContent(TIcons.Refresh, "Reconnect") }
                    OutlinedButton(onClick = { NavController.forgetDevice() }) { BtnContent(TIcons.Delete, "Forget") }
                }
            }
        }

        Section("Nearby displays", TIcons.Search) {
            if (found.isEmpty()) Text("None yet. Tap Scan with the TahVia powered on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            found.forEach { d ->
                Card(
                    Modifier.fillMaxWidth().clickable { NavController.connect(d.address, d.name) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(TIcons.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(d.name ?: "(unnamed)", fontWeight = FontWeight.Medium)
                            Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${d.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        OtaSection(state == BleState.CONNECTED, s.binUrl, s.readmeUrl, s.advanced)

        Text(
            "Tip: disable battery optimization for TahviaNav, or Android may drop the BLE link with the screen off.",
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

    Section("Firmware", TIcons.Refresh) {
        Text("Installed version: ${if (fw >= 0) Ota.fmtVersion(fw) else "unknown (connect the device first)"}")
        val canReadme = connected && fw >= 0 && !busy && navPhase == Phase.IDLE && readmeUrl.isNotBlank() && binUrl.isNotBlank()
        val canDirect = connected && fw >= 0 && !busy && navPhase == Phase.IDLE && binUrl.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { Ota.updateFromReadme() }, enabled = canReadme) { BtnContent(TIcons.Refresh, "Check & update") }
            if (busy && ui.phase != OtaPhase.REBOOTING) OutlinedButton(onClick = { Ota.cancel() }) { BtnContent(TIcons.Close, "Cancel") }
        }
        if (advanced)
            OutlinedButton(onClick = { Ota.installDirect() }, enabled = canDirect) { BtnContent(TIcons.Warning, "Force install .bin") }

        if (ui.message.isNotEmpty())
            Text(ui.message, color = if (ui.phase == OtaPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (ui.phase == OtaPhase.DOWNLOADING || ui.phase == OtaPhase.TRANSFERRING || ui.phase == OtaPhase.REBOOTING) {
            if (ui.progress < 0f || ui.phase == OtaPhase.REBOOTING) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
        }
        if (ui.phase == OtaPhase.DONE || ui.phase == OtaPhase.ERROR)
            TextButton(onClick = { Ota.dismiss() }) { Text("Dismiss") }
        if (trace.isNotEmpty() && (advanced || ui.phase == OtaPhase.ERROR)) {
            Text("Update log (also in logcat, tag TahviaOta)", style = MaterialTheme.typography.titleSmall)
            trace.takeLast(14).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        if (!advanced)
            Text("Firmware sources can be changed in Settings > Advanced configuration.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Keep the app open and the device above 30% battery during an update. The old firmware stays in place until the new image is fully verified.",
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
        ScreenTitle(TIcons.Settings, "Settings")

        Section("Allowed road types", TIcons.Navigation) {
            val universe = caps?.classes?.takeIf { it.isNotEmpty() } ?: if (s.allRoads) Roads.ALL else Roads.STOCK
            Roads.ALL.filter { it in universe }.forEach { c ->
                SwitchRow(Roads.LABELS[c] ?: c, c in s.allowed) { on ->
                    prefs.update { it.copy(allowed = if (on) it.allowed + c else it.allowed - c) }
                }
            }
            caps?.let { c ->
                val missing = Roads.ALL.filter { it !in c.classes }
                if (c.classes.isNotEmpty() && missing.isNotEmpty())
                    Text("This OSRM server cannot avoid: ${missing.joinToString { Roads.LABELS[it] ?: it }}. That needs a custom OSRM profile (see osrm-setup.md).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            chk.message?.let { Text(it, color = if (chk.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            caps?.presets?.takeIf { it.isNotEmpty() }?.let { presets ->
                HorizontalDivider()
                Text("Road presets", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.presetId.isEmpty(), onClick = { prefs.update { it.copy(presetId = "") } })
                    Text("None (use class filters)")
                }
                presets.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.presetId == p.id, onClick = { prefs.update { it.copy(presetId = p.id) } })
                        Text(p.label)
                    }
                }
            }
            if (caps == null)
                Text("No capabilities.json found next to OSRM: filters are sent unverified.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Display", TIcons.Map) {
            SwitchRow("Imperial units", s.imperial, "mi / ft / mph") { v -> prefs.update { it.copy(imperial = v) }; NavController.onSettingsChanged() }
            SwitchRow("Heading-up map", s.headingUp) { v -> prefs.update { it.copy(headingUp = v) }; NavController.onSettingsChanged() }
            SwitchRow("Invert device colors", s.invert) { v -> prefs.update { it.copy(invert = v) }; NavController.onSettingsChanged() }
            SwitchRow("Show FPS on device", s.showFps) { v -> prefs.update { it.copy(showFps = v) }; NavController.onSettingsChanged() }
            HorizontalDivider()
            Text("Device zoom: ${s.zoom} px per 100 m")
            Slider(value = s.zoom.toFloat(), onValueChange = { v -> prefs.update { it.copy(zoom = v.toInt()) } },
                onValueChangeFinished = { NavController.onSettingsChanged() }, valueRange = 20f..200f)
        }

        Section("GPS", TIcons.MyLocation) {
            Text("Drop fixes worse than ${s.maxAccuracy} m")
            Slider(value = s.maxAccuracy.toFloat(), onValueChange = { v -> prefs.update { it.copy(maxAccuracy = v.toInt()) } }, valueRange = 10f..100f)
        }

        Section("About", TIcons.Info) {
            Text(
                "Map data © OpenStreetMap contributors (ODbL). Destinations are sent only to the geocoder and router configured in Advanced. Recents and favorites stay on this phone.",
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
                    Text("Advanced configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Server links, firmware sources and developer tools",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                androidx.compose.material3.Switch(checked = s.advanced, onCheckedChange = { v ->
                    prefs.update { it.copy(advanced = v, debug = if (v) it.debug else false) }
                    if (!v) { NavController.stopSimulation(); NavController.stopReplay() }
                })
            }
        }

        if (s.advanced) {
            Section("Servers & links", TIcons.Map) {
                UrlField("OSRM URL", s.osrmUrl) { v -> prefs.update { it.copy(osrmUrl = v.trim()) } }
                UrlField("OSRM profile", s.profile) { v -> prefs.update { it.copy(profile = v.trim()) } }
                UrlField("Capabilities URL (empty = OSRM URL + /capabilities.json)", s.capsUrl) { v -> prefs.update { it.copy(capsUrl = v.trim()) } }
                UrlField("Nominatim URL", s.nominatimUrl) { v -> prefs.update { it.copy(nominatimUrl = v.trim()) } }
                UrlField("User-Agent (include contact)", s.userAgent) { v -> prefs.update { it.copy(userAgent = v) } }
                UrlField("Map style URL", s.styleUrl) { v -> prefs.update { it.copy(styleUrl = v.trim()) } }
                Text(
                    "The default OSRM server and map style are demos only. Self-host OSRM (road filters need it) and use a real tile provider. Nominatim: 1 request/s, search on button press only.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { NavController.onSettingsChanged() }) { BtnContent(TIcons.Check, "Apply") }
                    OutlinedButton(onClick = { NavController.onSettingsChanged(); NavController.refreshCapabilities() }) {
                        BtnContent(TIcons.Refresh, "Reload capabilities")
                    }
                }
                SwitchRow("Show all road types without capabilities.json", s.allRoads) { v -> prefs.update { it.copy(allRoads = v) } }
            }

            Section("Firmware sources", TIcons.Refresh) {
                UrlField("README URL (first \"## x.y.z\" line = latest version)", s.readmeUrl) { v -> prefs.update { it.copy(readmeUrl = v) } }
                UrlField("Firmware .bin URL (raw.githubusercontent.com)", s.binUrl) { v -> prefs.update { it.copy(binUrl = v) } }
            }

            Section("Developer", TIcons.Build) {
                SwitchRow("Debug mode", s.debug, "Enables the GPS simulator, GPX replay and logs") { v ->
                    prefs.update { it.copy(debug = v) }
                    if (!v) { NavController.stopSimulation(); NavController.stopReplay() }
                }
            }
            if (s.debug) DebugPanel()
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