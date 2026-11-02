package com.tahvia.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun App() {
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