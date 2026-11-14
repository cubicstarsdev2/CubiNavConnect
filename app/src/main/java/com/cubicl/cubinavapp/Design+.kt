package com.cubicl.cubinavapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add as MAdd
import androidx.compose.material.icons.filled.Build as MBuild
import androidx.compose.material.icons.filled.Check as MCheck
import androidx.compose.material.icons.filled.Clear as MClear
import androidx.compose.material.icons.filled.Close as MClose
import androidx.compose.material.icons.filled.Delete as MDelete
import androidx.compose.material.icons.filled.Done as MDone
import androidx.compose.material.icons.filled.Favorite as MFavorite
import androidx.compose.material.icons.filled.FavoriteBorder as MFavoriteBorder
import androidx.compose.material.icons.filled.Info as MInfo
import androidx.compose.material.icons.filled.KeyboardArrowUp as MKeyboardArrowUp
import androidx.compose.material.icons.filled.List as MList
import androidx.compose.material.icons.filled.Lock as MLock
import androidx.compose.material.icons.filled.Notifications as MNotifications
import androidx.compose.material.icons.filled.Place as MPlace
import androidx.compose.material.icons.filled.PlayArrow as MPlayArrow
import androidx.compose.material.icons.filled.Refresh as MRefresh
import androidx.compose.material.icons.filled.Search as MSearch
import androidx.compose.material.icons.filled.Settings as MSettings
import androidx.compose.material.icons.filled.Star as MStar
import androidx.compose.material.icons.filled.Warning as MWarning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------------------------

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00696B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA0F0F1),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6363),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFF4FAFA),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF4FAFA),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7979),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4CDADC),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFFA0F0F1),
    secondary = Color(0xFFB0CCCB),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDDE4E3),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDDE4E3),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC8C8),
    outline = Color(0xFF899392),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CubiNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        shapes = AppShapes,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// Icons. Everything comes from material-icons-core (already a dependency of material3),
// plus a few vectors drawn here so no extra artifact is needed.
// ---------------------------------------------------------------------------------------------

private fun vec(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        .build()

object TIcons {
    val Add get() = Icons.Default.MAdd
    val Build get() = Icons.Default.MBuild
    val Check get() = Icons.Default.MCheck
    val Clear get() = Icons.Default.MClear
    val Close get() = Icons.Default.MClose
    val Delete get() = Icons.Default.MDelete
    val Done get() = Icons.Default.MDone
    val Favorite get() = Icons.Default.MFavorite
    val FavoriteBorder get() = Icons.Default.MFavoriteBorder
    val Info get() = Icons.Default.MInfo
    val Up get() = Icons.Default.MKeyboardArrowUp
    val List get() = Icons.Default.MList
    val Lock get() = Icons.Default.MLock
    val Notifications get() = Icons.Default.MNotifications
    val Pin get() = Icons.Default.MPlace
    val Play get() = Icons.Default.MPlayArrow
    val Refresh get() = Icons.Default.MRefresh
    val Search get() = Icons.Default.MSearch
    val Settings get() = Icons.Default.MSettings
    val Star get() = Icons.Default.MStar
    val Warning get() = Icons.Default.MWarning

    val Bluetooth by lazy {
        vec("Bluetooth", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z")
    }
    val Map by lazy {
        vec("Map", "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5c0,-0.28 -0.22,-0.5 -0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z")
    }
    val MyLocation by lazy {
        vec("MyLocation", "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94V1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11H1v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94V23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94H23v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    }
    /** Arrow pointing up; rotated for turn icons. */
    val Navigation by lazy {
        vec("Navigation", "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z")
    }
    val Save by lazy {
        vec("Save", "M17,3L5,3c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,7l-4,-4zM12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9L5,9L5,5h10v4z")
    }
}

/** Turn icon for a maneuver code (README 5.4): one arrow, rotated. */
@Composable
fun ManeuverIcon(code: Int, tint: Color, size: Dp = 48.dp) {
    when (code) {
        9 -> Icon(TIcons.Refresh, null, Modifier.size(size), tint = tint)
        10 -> Icon(TIcons.Pin, null, Modifier.size(size), tint = tint)
        else -> {
            val deg = when (code) {
                2 -> 270f; 3 -> 90f; 4 -> 315f; 5 -> 45f; 6 -> 225f; 7 -> 135f; 8 -> 180f
                else -> 0f
            }
            Icon(TIcons.Navigation, null, Modifier.size(size).rotate(deg), tint = tint)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------------------------

@Composable
fun ScreenTitle(icon: ImageVector, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(12.dp).size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun UrlField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
    )
}

/** Icon + text inside a button, with a label that never wraps. */
@Composable
fun BtnContent(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Small rounded status chip. */
@Composable
fun InfoChip(
    icon: ImageVector?,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.clip(CircleShape).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
        }
    }
}

@Composable
fun bleColor(s: BleState): Color = when (s) {
    BleState.CONNECTED -> MaterialTheme.colorScheme.primary
    BleState.OFF -> MaterialTheme.colorScheme.error
    BleState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.tertiary
}

fun bleLabel(s: BleState): String = when (s) {
    BleState.OFF -> "Bluetooth off"
    BleState.IDLE -> "Disconnected"
    BleState.CONNECTING -> "Connecting"
    BleState.BONDING -> "Pairing"
    BleState.CONNECTED -> "Connected"
    BleState.RECONNECTING -> "Reconnecting"
}