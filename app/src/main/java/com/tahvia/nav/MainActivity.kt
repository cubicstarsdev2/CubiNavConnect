package com.tahvia.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavController.init(applicationContext)
        MapLibre.getInstance(this)
        setContent { TahviaTheme { App() } }
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
            Text("One last thing", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "The public routing, search and map servers ask every app to identify itself with a contact " +
                        "email, and they block requests that don't. Without it, routes and address search won't work.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Contact email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            )
            Text(
                "It is only sent as part of the User-Agent header to the servers you configure. " +
                        "You can change it later in Settings > Advanced configuration.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = valid,
                onClick = {
                    NavController.setContactEmail(email)   // stores it and rebuilds the User-Agent
                    NavController.refreshCapabilities()     // the first capabilities.json request went out with the placeholder
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Continue") }
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

    if (!granted) {
        // README 9: explain first, then ask; handle "denied" with a clear message.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            ) {
                Icon(TIcons.Navigation, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Welcome to TahviaNav", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("A few permissions are needed before you can drive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                PermissionRow(TIcons.MyLocation, "Location", "Shows your position and guides you along the route.")
                PermissionRow(TIcons.Bluetooth, "Nearby devices", "Finds and talks to your TahVia display.")
                PermissionRow(TIcons.Notifications, "Notifications", "Keeps navigation running with the screen off.")
                Text(
                    "Nothing leaves the phone except the destination you search for, sent to the geocoder and router you configure.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { launcher.launch(requiredPermissions()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(TIcons.Check, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Grant permissions")
                }
                Text(
                    "If you denied them before, enable them in Android Settings > Apps > TahviaNav > Permissions.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        NavController.ensureLocation()
        NavController.autoConnect()
    }

    // Contact email: required before the app is usable (the User-Agent is sent to OSRM, Nominatim, GitHub).
    val settings by NavController.prefs.settings.collectAsState()
    if (!hasContact(settings)) {
        ContactGate()
        return
    }

    var tab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "Map" to TIcons.Map,
        "Device" to TIcons.Bluetooth,
        "Settings" to TIcons.Settings,
    )
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
            if (tab != 0) {
                Surface(
                    Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (tab) {
                        1 -> DeviceScreen()
                        else -> SettingsScreen()
                    }
                }
            }
        }
    }
}