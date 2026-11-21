package com.cubicl.cubinavconnect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    /** The app language is chosen in the app (setup wizard / Settings), independent of the phone's locale. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavController.init(applicationContext)
        MapLibre.getInstance(this)
        setContent { CubiNavTheme { App() } }
    }

    override fun onStop() {
        super.onStop()
        NavController.maybeStopLocation()
    }
}

fun requiredPermissions(): Array<String> {
    val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        l += Manifest.permission.BLUETOOTH_SCAN
        l += Manifest.permission.BLUETOOTH_CONNECT
    }
    if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
    return l.toTypedArray()
}

/** True once a real contact e-mail is stored (or an older build already put one into the User-Agent). */
fun hasContact(s: Settings) =
    Contact.isValid(s.contactEmail) || Contact.isValid(Contact.emailOf(s.userAgent))

/** The boot animation plays once per process (not again on rotation or when the activity is recreated). */
private var bootPlayed = false

@Composable
private fun PermissionRow(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shown right after the permissions. OSRM, Nominatim and the firmware host all receive the User-Agent,
 * and the public servers reject requests that do not identify the app with a real contact.
 */
@Composable
private fun ContactGate() {
    var email by remember { mutableStateOf("") }
    val valid = Contact.isValid(email)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.contact_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.contact_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.contact_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            )
            Text(
                stringResource(R.string.contact_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = valid,
                onClick = {
                    NavController.setContactEmail(email)   // stores it and rebuilds the User-Agent
                    NavController.refreshCapabilities()     // the first capabilities.json request went out with the placeholder
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.common_continue)) }
        }
    }
}

/**
 * Boot animation first (cold start only), then the app. The app is only composed when the animation
 * ends or is skipped, so the MapView is not created while the frames are playing. A missing or
 * unreadable res/raw/boot_frames skips the animation.
 */
@Composable
fun App() {
    val ctx = LocalContext.current
    val anim = remember { if (bootPlayed) null else BootFrames.fromRaw(ctx) }
    var booting by remember { mutableStateOf(anim != null) }

    Crossfade(targetState = booting, animationSpec = tween(400), label = "boot") { showBoot ->
        if (showBoot && anim != null) {
            BootAnimation(anim, onDone = { bootPlayed = true; booting = false })
        } else {
            AppContent()
        }
    }
}

private enum class Stage { PERMISSIONS, CONTACT, SETUP, FIND, MAIN }

/** README 9: explain first, then ask; handle "denied" with a clear message. */
@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Icon(TIcons.Navigation, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.perm_welcome), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.perm_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            PermissionRow(TIcons.MyLocation, stringResource(R.string.perm_location), stringResource(R.string.perm_location_body))
            PermissionRow(TIcons.Bluetooth, stringResource(R.string.perm_nearby), stringResource(R.string.perm_nearby_body))
            PermissionRow(TIcons.Notifications, stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_body))
            Text(
                stringResource(R.string.perm_privacy),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(TIcons.Check, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.perm_grant))
            }
            Text(
                stringResource(R.string.perm_denied_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppContent() {
    val ctx = LocalContext.current
    fun allGranted() = requiredPermissions().all {
        // notifications are optional: the foreground service still runs without them
        it == Manifest.permission.POST_NOTIFICATIONS ||
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(allGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = allGranted()
    }

    LaunchedEffect(granted) {
        if (granted) {
            NavController.ensureLocation()
            NavController.autoConnect()
        }
    }

    // Contact email: required before the app is usable (the User-Agent is sent to OSRM, Nominatim, GitHub).
    val settings by NavController.prefs.settings.collectAsState()

    // First-time setup: the device says "not configured" in HELLO_ACK (or a setup transfer is running).
    val deviceConfigured by NavController.deviceConfigured.collectAsState()
    val setupUi by NavController.setup.collectAsState()
    // First run (no saved device): "find your CubiNav" first. Existing users with a saved device never see it.
    var findActive by remember { mutableStateOf(settings.deviceAddress.isEmpty() && !settings.onboardSkipped) }
    LaunchedEffect(deviceConfigured) { if (deviceConfigured != null) findActive = false }

    val stage = when {
        !granted -> Stage.PERMISSIONS
        !hasContact(settings) -> Stage.CONTACT
        deviceConfigured == false || setupUi.state != SetupState.NOT_STARTED -> Stage.SETUP
        findActive -> Stage.FIND
        else -> Stage.MAIN
    }

    // One switch for the whole gate chain, so moving between stages fades through instead of cutting.
    AnimatedContent(targetState = stage, transitionSpec = { fadeThrough() }, label = "stage") { s ->
        // The wizard reads global state; once it is finished that state resets, so freeze it while it fades out.
        val leaving = transition.targetState == EnterExitState.PostExit
        when (s) {
            Stage.PERMISSIONS -> PermissionScreen { launcher.launch(requiredPermissions()) }
            Stage.CONTACT -> ContactGate()
            Stage.SETUP -> SetupWizard(frozen = leaving)
            Stage.FIND -> FindDeviceScreen(onSkip = { findActive = false; NavController.skipOnboarding() })
            Stage.MAIN -> MainTabs()
        }
    }
}

@Composable
private fun MainTabs() {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tab_map) to TIcons.Map,
        stringResource(R.string.tab_device) to TIcons.Bluetooth,
        stringResource(R.string.tab_settings) to TIcons.Settings,
    )
    // The page behind Device/Settings fades in with the tab instead of popping over the map.
    val scrim by animateFloatAsState(if (tab != 0) 1f else 0f, tween(Motion.MEDIUM), label = "tabScrim")
    val bg = MaterialTheme.colorScheme.background

    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                )
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // The map stays composed under the other tabs so the MapView keeps its lifecycle and state.
            MapScreen()
            // Not a Surface: M3's Surface always installs a pointerInput that swallows touches, which would
            // make this full-screen layer block the map even on the Map tab. Only block when another tab is up.
            Box(
                Modifier.fillMaxSize()
                    .background(bg.copy(alpha = scrim))
                    .then(if (tab != 0) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
            ) {
                // Tabs slide a little toward the direction you are going (Map < Device < Settings).
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { slideThrough(forward = targetState > initialState) },
                    label = "tab",
                ) { t ->
                    when (t) {
                        0 -> Spacer(Modifier.fillMaxSize())   // map tab: nothing on top of the map
                        1 -> DeviceScreen()
                        else -> SettingsScreen()
                    }
                }
            }
        }
    }
}