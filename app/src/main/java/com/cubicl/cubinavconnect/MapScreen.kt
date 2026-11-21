package com.cubicl.cubinavconnect

import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos

@Composable
private fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val mv = remember { MapView(ctx).also { it.onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    return mv
}

private const val OSM_STYLE = """
{"version":8,
 "sources":{"osm":{"type":"raster","tileSize":256,"maxzoom":19,
   "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
   "attribution":"© OpenStreetMap contributors"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]}
"""

private fun styleBuilder(url: String): Style.Builder =
    if (url.isBlank() || url == "osm" || url.contains("demotiles.maplibre.org"))
        Style.Builder().fromJson(OSM_STYLE)
    else
        Style.Builder().fromUri(url)

/** Teal pin with the waypoint number; drawn as a bitmap because the OSM raster style has no glyphs for text. */
private fun numberedPin(n: Int, density: Float): Bitmap {
    val size = (30 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = 0xFF00696B.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 3 * density, p)
    p.color = Color.WHITE
    p.textSize = 15 * density
    p.textAlign = Paint.Align.CENTER
    p.isFakeBoldText = true
    c.drawText(n.toString(), size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
    return bmp
}

private fun emptyFc() = FeatureCollection.fromFeatures(emptyList<Feature>())

private fun setupLayers(st: Style) {
    st.addSource(GeoJsonSource("route"))
    st.addSource(GeoJsonSource("acc"))
    st.addSource(GeoJsonSource("snap"))
    st.addSource(GeoJsonSource("pos"))
    st.addSource(GeoJsonSource("dest"))
    st.addSource(GeoJsonSource("destsnap"))
    st.addSource(GeoJsonSource("via"))
    st.addLayer(LineLayer("route-casing", "route").withProperties(
        PropertyFactory.lineColor("#0B3D91"), PropertyFactory.lineWidth(10f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(LineLayer("route-line", "route").withProperties(
        PropertyFactory.lineColor("#2F80FF"), PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    st.addLayer(CircleLayer("acc", "acc").withProperties(
        PropertyFactory.circleColor("#2979FF"), PropertyFactory.circleOpacity(0.15f), PropertyFactory.circleRadius(0f)))
    st.addLayer(CircleLayer("destsnap", "destsnap").withProperties(
        PropertyFactory.circleColor("#FF9800"), PropertyFactory.circleRadius(6f),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
    st.addLayer(CircleLayer("dest", "dest").withProperties(
        PropertyFactory.circleColor("#E53935"), PropertyFactory.circleRadius(9f),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
    st.addLayer(SymbolLayer("via", "via").withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    st.addLayer(CircleLayer("snap", "snap").withProperties(
        PropertyFactory.circleColor("#FF9800"), PropertyFactory.circleRadius(4f)))
    st.addLayer(CircleLayer("pos", "pos").withProperties(
        PropertyFactory.circleColor("#2979FF"), PropertyFactory.circleRadius(8f),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
}

private fun Style.setPoint(id: String, lat: Double?, lon: Double?) {
    val src = getSourceAs<GeoJsonSource>(id) ?: return
    if (lat == null || lon == null) src.setGeoJson(emptyFc())
    else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
}

// ---------------------------------------------------------------------------------------------
// UI pieces
// ---------------------------------------------------------------------------------------------

/** Big "next turn" card shown while navigating. */
@Composable
private fun TurnBanner(progress: Progress?, phase: Phase, imperial: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        val tint = MaterialTheme.colorScheme.onPrimaryContainer
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Crossfade(
                targetState = if (phase == Phase.ARRIVED) 10 else progress?.icon ?: 1,
                animationSpec = tween(Motion.SHORT), label = "maneuver",
            ) { code -> ManeuverIcon(code, tint, 46.dp) }
            Column(Modifier.weight(1f)) {
                val head = when {
                    phase == Phase.ARRIVED -> stringResource(R.string.nav_arrived)
                    progress == null -> stringResource(R.string.nav_starting)
                    progress.distToTurn != null -> fmtDist(progress.distToTurn.toDouble(), imperial)
                    else -> stringResource(R.string.nav_continue)
                }
                Text(head, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = tint)
                if (phase != Phase.ARRIVED && progress != null && progress.street.isNotEmpty())
                    Text(progress.street, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NoteBar(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = container, shadowElevation = 3.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = content)
            Text(text, color = content, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen() {
    val mapView = rememberMapView()
    val clipboard = LocalClipboardManager.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var zoomedOnce by remember { mutableStateOf(false) }
    var debugDots by remember { mutableStateOf(true) }
    var addingStop by remember { mutableStateOf(false) }

    val settings by NavController.prefs.settings.collectAsState()
    val fix by NavController.fix.collectAsState()
    val snapped by NavController.snapped.collectAsState()
    val heading by NavController.heading.collectAsState()
    val dest by NavController.dest.collectAsState()
    val destSnapped by NavController.destSnapped.collectAsState()
    val route by NavController.route.collectAsState()
    val phase by NavController.phase.collectAsState()
    val progress by NavController.progress.collectAsState()
    val speedLimit by NavController.speedLimit.collectAsState()
    val banner by NavController.banner.collectAsState()
    val note by NavController.note.collectAsState()
    val results by NavController.results.collectAsState()
    val searching by NavController.searching.collectAsState()
    val swap by NavController.swapPrompt.collectAsState()
    val bleState by NavController.bleState.collectAsState()
    val caps by NavController.caps.collectAsState()
    val noRoute by NavController.noRouteHint.collectAsState()
    val sim by NavController.simulating.collectAsState()
    val phoneOnly by NavController.phoneOnly.collectAsState()
    val waypoints by NavController.waypoints.collectAsState()
    val planDirty by NavController.planDirty.collectAsState()
    val density = LocalContext.current.resources.displayMetrics.density
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) NavController.saveRoute(uri)
    }
    val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) NavController.loadRoute(uri)
    }
    val recents by NavController.prefs.recents.collectAsState()
    val favorites by NavController.prefs.favorites.collectAsState()
    var query by remember { mutableStateOf("") }
    val road = NavController.roadCheck(settings, caps)
    val showSnap = debugDots && settings.debug

    // listeners (once)
    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            map = m
            m.addOnMapLongClickListener { ll ->
                val lbl = "%.5f, %.5f".format(java.util.Locale.US, ll.latitude, ll.longitude)
                if (addingStop) NavController.addWaypoint(ll.latitude, ll.longitude, lbl)
                else NavController.setDestination(ll.latitude, ll.longitude, lbl)
                true
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
        }
    }
    // style
    LaunchedEffect(map, settings.styleUrl) {
        val m = map ?: return@LaunchedEffect
        styleReady = false
        m.setStyle(styleBuilder(settings.styleUrl)) { st ->
            setupLayers(st)
            styleReady = true
        }
    }
    // route line
    LaunchedEffect(styleReady, route) {
        if (!styleReady) return@LaunchedEffect
        val src = map?.style?.getSourceAs<GeoJsonSource>("route") ?: return@LaunchedEffect
        val r = route
        if (r == null) src.setGeoJson(emptyFc())
        else src.setGeoJson(Feature.fromGeometry(
            LineString.fromLngLats((0 until r.n).map { Point.fromLngLat(r.lon[it], r.lat[it]) })))
    }
    // destination
    LaunchedEffect(styleReady, dest, destSnapped) {
        if (!styleReady) return@LaunchedEffect
        val st = map?.style ?: return@LaunchedEffect
        st.setPoint("dest", dest?.lat, dest?.lon)
        st.setPoint("destsnap", destSnapped?.lat, destSnapped?.lon)
    }
    // waypoints
    LaunchedEffect(styleReady, waypoints) {
        if (!styleReady) return@LaunchedEffect
        val st = map?.style ?: return@LaunchedEffect
        val src = st.getSourceAs<GeoJsonSource>("via") ?: return@LaunchedEffect
        for (i in 1..waypoints.size) if (st.getImage("wp-$i") == null) st.addImage("wp-$i", numberedPin(i, density))
        src.setGeoJson(FeatureCollection.fromFeatures(waypoints.mapIndexed { i, w ->
            Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)).also { it.addStringProperty("icon", "wp-${i + 1}") }
        }))
    }
    LaunchedEffect(waypoints.size) { if (addingStop) { addingStop = false; query = "" } }
    LaunchedEffect(dest?.lat, dest?.lon) {
        val d = dest ?: return@LaunchedEffect
        if (phase == Phase.IDLE) { follow = false; map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(d.lat, d.lon), 15.0), 600) }
    }
    // position, accuracy circle, snapped dot, follow camera
    LaunchedEffect(styleReady, fix, snapped, showSnap) {
        val f = fix ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (styleReady) {
            val st = m.style ?: return@LaunchedEffect
            st.setPoint("pos", f.lat, f.lon)
            st.setPoint("acc", f.lat, f.lon)
            st.setPoint("snap", if (showSnap) snapped?.lat else null, if (showSnap) snapped?.lon else null)
            val base = f.acc / (78271.517 * cos(Math.toRadians(f.lat)))
            st.getLayer("acc")?.setProperties(PropertyFactory.circleRadius(
                Expression.interpolate(Expression.exponential(2), Expression.zoom(),
                    Expression.stop(0, Expression.literal(base)),
                    Expression.stop(22, Expression.literal(base * 4194304.0)))))
        }
        if (follow) {
            val navigating = phase == Phase.NAVIGATING || phase == Phase.REROUTING
            val bearing = if (settings.headingUp && navigating) heading else 0.0
            val zoom = if (!zoomedOnce) 16.0 else m.cameraPosition.zoom
            zoomedOnce = true
            m.easeCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom(zoom).bearing(bearing).tilt(0.0).build()), 900)
        }
    }

    val navigating = phase == Phase.NAVIGATING || phase == Phase.REROUTING || phase == Phase.ARRIVED
    val cs = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // ---- top: turn banner or search, results, notices ----
        // No spacedBy on this Column: a hidden Reveal would still reserve a gap. Each Reveal carries its own bottom gap.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp)) {
            val bannerProgress = rememberWhileVisible(progress, navigating)
            val bannerPhase = rememberWhileVisible(phase, navigating)
            Reveal(navigating, gap = 8.dp, clip = false) { TurnBanner(bannerProgress, bannerPhase, settings.imperial) }

            val showSpeed = navigating && phase != Phase.ARRIVED && speedLimit > 0
            val shownSpeed = rememberWhileVisible(speedLimit, showSpeed)
            Reveal(showSpeed, gap = 8.dp, clip = false) { SpeedSign(shownSpeed, settings.imperial) }

            Reveal(!navigating || addingStop, gap = 8.dp, clip = false) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = CircleShape, shadowElevation = 8.dp, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (addingStop) TIcons.Add else TIcons.Pin, null, tint = cs.primary)
                            TextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(if (addingStop) R.string.map_add_waypoint else R.string.map_where_to)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { NavController.submitQuery(query, addingStop) }),
                            )
                            if (searching) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(24.dp), strokeWidth = 2.dp)
                            else if (query.isNotBlank()) {
                                IconButton(onClick = { query = "" }) { Icon(TIcons.Clear, stringResource(R.string.common_clear)) }
                                FilledIconButton(onClick = { NavController.submitQuery(query, addingStop) }) { Icon(TIcons.Search, stringResource(R.string.common_search)) }
                            }
                        }
                    }
                    if (addingStop || phase == Phase.IDLE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val chipColors = AssistChipDefaults.assistChipColors(containerColor = cs.surface)
                            if (addingStop) AssistChip(onClick = { addingStop = false; query = "" }, colors = chipColors,
                                label = { Text(stringResource(R.string.map_cancel_waypoint)) }, leadingIcon = { Icon(TIcons.Close, null, Modifier.size(18.dp)) })
                            else AssistChip(onClick = { loader.launch(arrayOf("*/*")) }, colors = chipColors,
                                label = { Text(stringResource(R.string.map_load_route)) }, leadingIcon = { Icon(TIcons.List, null, Modifier.size(18.dp)) })
                        }
                    }
                    // search results <-> recent places cross-fade (and the area eases to the new height) instead of popping
                    val shownResults = rememberWhileVisible(results, results.isNotEmpty())
                    val showRecents = results.isEmpty() && query.isBlank() && dest == null && (recents.isNotEmpty() || favorites.isNotEmpty())
                    SoftSwap(if (results.isNotEmpty()) 1 else if (showRecents) 2 else 0, label = "searchList") { mode ->
                        when (mode) {
                            1 -> Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
                                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                                    shownResults.forEach { p ->
                                        ListItem(
                                            headlineContent = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                            supportingContent = { Text(p.type) },
                                            leadingContent = { Icon(TIcons.Pin, null, tint = cs.primary) },
                                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                            modifier = Modifier.clickable {
                                                if (addingStop) NavController.addWaypoint(p.lat, p.lon, p.name)
                                                else NavController.setDestination(p.lat, p.lon, p.name)
                                            },
                                        )
                                    }
                                }
                            }
                            2 -> Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
                                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                                    (favorites.map { true to it } + recents.map { false to it }).take(8).forEach { (fav, d) ->
                                        ListItem(
                                            headlineContent = { Text(d.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingContent = {
                                                Icon(if (fav) TIcons.Star else TIcons.Pin, null,
                                                    tint = if (fav) cs.tertiary else cs.onSurfaceVariant)
                                            },
                                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                            modifier = Modifier.clickable { NavController.setDestination(d.lat, d.lon, d.label) },
                                        )
                                    }
                                }
                            }
                            else -> Spacer(Modifier.size(0.dp))
                        }
                    }
                }
            }

            val shownBanner = rememberWhileVisible(banner, banner != null)
            Reveal(banner != null, gap = 8.dp, clip = false) {
                shownBanner?.let { NoteBar(TIcons.Warning, it, cs.errorContainer, cs.onErrorContainer) }
            }
            Reveal(phase == Phase.REROUTING, gap = 8.dp, clip = false) {
                NoteBar(TIcons.Refresh, stringResource(R.string.status_rerouting), cs.tertiaryContainer, cs.onTertiaryContainer)
            }
        }

        // ---- bottom: destination / trip card ----
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pop(visible = !follow, modifier = Modifier.align(Alignment.End)) {
                SmallFloatingActionButton(
                    onClick = { follow = true; fix?.let { map?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(it.lat, it.lon)), 400) } },
                    containerColor = cs.surface, contentColor = cs.primary,
                ) { Icon(TIcons.MyLocation, stringResource(R.string.map_recenter)) }
            }

            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 10.dp, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                // smoothSize is on the Column *inside* the Surface so the card's shadow is not clipped while it resizes.
                // No spacedBy: each Reveal below carries its own 10dp gap, so hidden sections leave no hole.
                Column(Modifier.smoothSize().padding(16.dp)) {
                    val d = dest

                    // -- destination preview --
                    val showDest = d != null && (phase == Phase.IDLE || phase == Phase.ROUTING)
                    val destShown = rememberWhileVisible(d, showDest)
                    val destPhase = rememberWhileVisible(phase, showDest)
                    val destNote = rememberWhileVisible(note, showDest)
                    val destRoad = rememberWhileVisible(road, showDest)
                    val destNoRoute = rememberWhileVisible(noRoute, showDest)
                    Reveal(showDest, gap = 10.dp) {
                        destShown?.let { dd ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(TIcons.Pin, null, tint = cs.error)
                                    Column(Modifier.weight(1f)) {
                                        Text(dd.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        destNote?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                                        destRoad.message?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    val isFav = remember(favorites, dd) { NavController.prefs.isFavorite(dd) }
                                    IconButton(onClick = { NavController.prefs.toggleFavorite(dd) }) {
                                        Icon(if (isFav) TIcons.Favorite else TIcons.FavoriteBorder, stringResource(if (isFav) R.string.map_unstar else R.string.map_star),
                                            tint = if (isFav) cs.error else cs.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { NavController.clearDestination() }) { Icon(TIcons.Close, stringResource(R.string.common_clear)) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!dd.confirmed) Button(onClick = { NavController.confirmDestination() }, modifier = Modifier.weight(1f)) {
                                        BtnContent(TIcons.Check, stringResource(R.string.map_confirm_point))
                                    } else Button(
                                        onClick = { NavController.startNavigation() }, modifier = Modifier.weight(1f),
                                        enabled = destPhase == Phase.IDLE && fix != null && !destRoad.blocking,
                                    ) { BtnContent(TIcons.Navigation, stringResource(if (destPhase == Phase.ROUTING) R.string.map_routing else R.string.common_start)) }
                                    FilledTonalButton(
                                        onClick = { NavController.startNavigation(phoneOnly = true) }, modifier = Modifier.weight(1f),
                                        enabled = destPhase == Phase.IDLE && fix != null && !destRoad.blocking,
                                    ) { BtnContent(TIcons.Map, stringResource(R.string.map_route_only)) }
                                }
                                if (destNoRoute) Button(onClick = { NavController.relaxFilters() }, modifier = Modifier.fillMaxWidth()) {
                                    BtnContent(TIcons.Refresh, stringResource(R.string.map_relax))
                                }
                            }
                        }
                    }

                    // -- waypoints --
                    val showWp = d != null && phase != Phase.ARRIVED
                    val wpPhase = rememberWhileVisible(phase, showWp)
                    val wpList = rememberWhileVisible(waypoints, showWp)
                    val wpDirty = rememberWhileVisible(planDirty, showWp)
                    Reveal(showWp, gap = 10.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (wpPhase == Phase.IDLE || wpPhase == Phase.ROUTING) HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (wpList.isEmpty()) stringResource(R.string.map_no_waypoints) else stringResource(R.string.map_waypoints, wpList.size),
                                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { saver.launch(NavController.suggestedRouteName()) }) { Icon(TIcons.Save, stringResource(R.string.map_save_route)) }
                                TextButton(onClick = { addingStop = !addingStop }) {
                                    BtnContent(if (addingStop) TIcons.Close else TIcons.Add, stringResource(if (addingStop) R.string.common_cancel else R.string.map_waypoint_btn))
                                }
                            }
                            if (wpList.isNotEmpty()) Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                                wpList.forEachIndexed { i, w ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(shape = CircleShape, color = cs.primary) {
                                            Text("${i + 1}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                color = cs.onPrimary, style = MaterialTheme.typography.labelMedium)
                                        }
                                        Text(w.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (i > 0) IconButton(onClick = { NavController.moveWaypointUp(i) }) { Icon(TIcons.Up, stringResource(R.string.map_move_up)) }
                                        IconButton(onClick = { NavController.removeWaypoint(i) }) { Icon(TIcons.Delete, stringResource(R.string.map_remove)) }
                                    }
                                }
                            }
                            if (wpDirty && (wpPhase == Phase.NAVIGATING || wpPhase == Phase.REROUTING))
                                Button(onClick = { NavController.applyPlan() }, modifier = Modifier.fillMaxWidth()) {
                                    BtnContent(TIcons.Refresh, stringResource(R.string.map_apply_changes))
                                }
                        }
                    }

                    // -- trip summary --
                    val showTrip = phase != Phase.IDLE && phase != Phase.ROUTING
                    val tripPhase = rememberWhileVisible(phase, showTrip)
                    val tripProgress = rememberWhileVisible(progress, showTrip)
                    val tripHasDest = rememberWhileVisible(d != null, showTrip)
                    Reveal(showTrip, gap = 10.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (tripHasDest && tripPhase != Phase.ARRIVED) HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    tripProgress?.let {
                                        Text(fmtDist(it.remainingM, settings.imperial), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.map_time_left, fmtDuration(it.remainingS)), color = cs.onSurfaceVariant)
                                    } ?: Text(stringResource(R.string.map_calculating), color = cs.onSurfaceVariant)
                                }
                                Button(
                                    onClick = { NavController.stopNavigation() },
                                    colors = if (tripPhase == Phase.ARRIVED) ButtonDefaults.buttonColors()
                                    else ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                                ) { BtnContent(if (tripPhase == Phase.ARRIVED) TIcons.Done else TIcons.Close, stringResource(if (tripPhase == Phase.ARRIVED) R.string.common_finish else R.string.common_stop)) }
                            }
                        }
                    }

                    // -- always-visible status line (README 4) --
                    val f = fix
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoChip(TIcons.MyLocation, if (f != null) "%.5f, %.5f".format(java.util.Locale.US, f.lat, f.lon) else stringResource(R.string.map_no_fix),
                            onClick = { f?.let { clipboard.setText(AnnotatedString("%.6f, %.6f".format(java.util.Locale.US, it.lat, it.lon))) } })
                        if (f != null) InfoChip(null, "±${f.acc.toInt()} m  ${(f.speed * if (settings.imperial) 2.236936f else 3.6f).toInt()} ${if (settings.imperial) "mph" else "km/h"}")
                        if (phoneOnly) InfoChip(TIcons.Warning, stringResource(R.string.map_phone_only), tint = cs.error)
                        if (sim) InfoChip(TIcons.Play, stringResource(R.string.map_simulated), tint = cs.error)
                        InfoChip(TIcons.Bluetooth, bleLabel(bleState), tint = bleColor(bleState))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        if (settings.debug) Text(if (debugDots) "hide snapped dot" else "show snapped dot",
                            style = MaterialTheme.typography.labelSmall, color = cs.primary,
                            modifier = Modifier.clickable { debugDots = !debugDots })
                    }
                }
            }
        }
    }

    swap?.let { s ->
        AlertDialog(
            onDismissRequest = { NavController.dismissSwapPrompt() },
            icon = { Icon(TIcons.Refresh, null) },
            title = { Text(stringResource(R.string.swap_title)) },
            text = { Text(stringResource(R.string.swap_text, "%.5f".format(java.util.Locale.US, s.lat), "%.5f".format(java.util.Locale.US, s.lon))) },
            confirmButton = { TextButton(onClick = { NavController.acceptSwap() }) { Text(stringResource(R.string.swap_use)) } },
            dismissButton = { TextButton(onClick = { NavController.dismissSwapPrompt() }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}