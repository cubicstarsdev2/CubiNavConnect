package com.cubicl.cubinavapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Xml
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Phase { IDLE, ROUTING, NAVIGATING, REROUTING, ARRIVED }

data class Fix(
    val lat: Double,
    val lon: Double,
    val acc: Float,
    val speed: Float,
    val bearing: Float?,
    val timeMs: Long,
)

data class Progress(
    val remainingM: Double,
    val remainingS: Double,
    val distToTurn: Int?,
    val icon: Int,
    val street: String,
)

data class RoadCheck(val message: String?, val blocking: Boolean)

/** Everything the foreground-service notification shows, in one snapshot. */
data class NavNotif(
    val phase: Phase,
    val ble: BleState,
    val remainingM: Double,
    val remainingS: Double,
    val distToTurn: Int?,
    val icon: Int,
    val street: String,
    val dest: String,
    val hasProgress: Boolean,
    val totalM: Double,
    val limitKmh: Int = 0,
)

/**
 * App-scoped brain: location -> map matching -> POSE/route streaming over BLE, rerouting, replay.
 * The UI and the foreground service only observe its flows. Everything runs on the main thread.
 */
object NavController : BleListener {
    private const val REBASE_DM = 1_500_000          // 150 km; int32 decimeters reach +-214 km
    private val BACKOFF_S = longArrayOf(5, 10, 20, 30)

    private var inited = false
    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    private lateinit var ble: BleLink
    private lateinit var streamer: RouteStreamer
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(app) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- observable state -----------------------------------------------------------------------
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix = _fix.asStateFlow()
    private val _snapped = MutableStateFlow<LatLon?>(null)
    val snapped = _snapped.asStateFlow()
    private val _heading = MutableStateFlow(0.0)
    val heading = _heading.asStateFlow()
    private val _dest = MutableStateFlow<Dest?>(null)
    val dest = _dest.asStateFlow()
    private val _destSnapped = MutableStateFlow<LatLon?>(null)
    val destSnapped = _destSnapped.asStateFlow()
    private val _route = MutableStateFlow<Route?>(null)
    val route = _route.asStateFlow()
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase = _phase.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()
    private val _banner = MutableStateFlow<String?>(null)
    val banner = _banner.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note = _note.asStateFlow()
    private val _battery = MutableStateFlow(-1)
    val battery = _battery.asStateFlow()
    private val _caps = MutableStateFlow<Capabilities?>(null)
    val caps = _caps.asStateFlow()
    private val _results = MutableStateFlow<List<Place>>(emptyList())
    val results = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _swapPrompt = MutableStateFlow<LatLon?>(null)
    val swapPrompt = _swapPrompt.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()
    private val _lastPose = MutableStateFlow("-")
    val lastPose = _lastPose.asStateFlow()
    private val _lastAck = MutableStateFlow("-")
    val lastAck = _lastAck.asStateFlow()
    private val _replaying = MutableStateFlow(false)
    val replaying = _replaying.asStateFlow()
    private val _noRouteHint = MutableStateFlow(false)
    val noRouteHint = _noRouteHint.asStateFlow()

    /** True while navigating "phone only": the route is computed and followed on the phone, nothing is sent to the device. */
    private val _phoneOnly = MutableStateFlow(false)
    val phoneOnly = _phoneOnly.asStateFlow()

    /** Firmware version reported in HELLO_ACK ((major << 8) | minor); -1 while unknown / disconnected. */
    private val _deviceFw = MutableStateFlow(-1)
    val deviceFw = _deviceFw.asStateFlow()
    fun resetDeviceFw() { _deviceFw.value = -1 }
    internal fun link(): BleLink = ble

    val bleState: StateFlow<BleState> get() = ble.state
    val bleScanning: StateFlow<Boolean> get() = ble.scanning
    val bleFound: StateFlow<List<FoundDevice>> get() = ble.found

    lateinit var statusText: StateFlow<String>
        private set

    lateinit var notif: StateFlow<NavNotif>
        private set

    // ---- internals ------------------------------------------------------------------------------
    private var engine: NavEngine? = null
    private var heldLimit = 0                         // km/h, kept for a while when the data has a gap
    private var heldLimitAlong = 0.0
    private val _speedLimit = MutableStateFlow(0)
    val speedLimit = _speedLimit.asStateFlow()
    private var projector: Projector? = null
    private var routeId = 0
    private var helloAcked = false
    private var gpsHeading = 0.0
    private var lastFixAt = 0L
    private var gpsLost = false
    private var offRouteSince = 0L
    private var rerouting = false
    private var nextRerouteAt = 0L
    private var backoffIdx = 0
    private var lastManeuverKey: String? = null
    private var currentAlong = 0.0
    private var lastPoseX = 0
    private var lastPoseY = 0
    private var fastMode = false
    private var locationOn = false
    private var replayJob: Job? = null
    private var bannerJob: Job? = null
    private var capsUrl = ""
    private var lastGoodFixAt = 0L                    // last fix that passed the accuracy filter
    private var lastPoseBytes: ByteArray? = null
    private var lastPoseSentAt = 0L

    // waypoints (stops between the start and the destination), in driving order
    private val _waypoints = MutableStateFlow<List<Dest>>(emptyList())
    val waypoints = _waypoints.asStateFlow()
    private var viaPassed = 0
    private var swapAsStop = false
    private var pendingReroute = false
    private val _planDirty = MutableStateFlow(false)       // destination/waypoints edited mid-trip, not applied yet
    val planDirty = _planDirty.asStateFlow()
    private var reqWps: List<Dest> = emptyList()           // waypoints used by the last route request
    private var routeWps: List<Dest> = emptyList()         // waypoints the active route was built with

    // simulator state (debug mode)
    private val _simulating = MutableStateFlow(false)
    val simulating = _simulating.asStateFlow()
    private val _simPaused = MutableStateFlow(false)
    val simPaused = _simPaused.asStateFlow()
    private var simJob: Job? = null
    private var simLat = 0.0
    private var simLon = 0.0
    private var simKmh = 40f
    private var simBearing = 0.0
    private var simRoute: Route? = null
    private var simAlong = 0.0
    private var simSeg = 0
    private var simFree = false                       // driving straight, off the route (to test rerouting)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        ble = BleLink(app, this)
        streamer = RouteStreamer(
            send = { sendCmd(it) },
            projector = { projector!! },
            chunkMax = { ((ble.mtu - 3 - 7) / 4).coerceIn(1, Proto.MAX_CHUNK_POINTS) },
            log = { log(it) },
        )
        statusText = combine(_phase, ble.state, _progress) { p, b, pr ->
            val nav = when (p) {
                Phase.IDLE -> "Idle"
                Phase.ROUTING -> "Calculating route..."
                Phase.NAVIGATING -> pr?.let { "Navigating, ${fmtDist(it.remainingM, false)} left" } ?: "Navigating"
                Phase.REROUTING -> "Rerouting..."
                Phase.ARRIVED -> "Arrived"
            }
            "$nav  |  device: ${b.name.lowercase()}"
        }.stateIn(scope, SharingStarted.Eagerly, "CubiNav")

        notif = combine(_phase, ble.state, _progress, _dest, combine(_route, speedLimit) { r, lim -> r to lim }) { p, b, pr, d, (r, lim) ->
            NavNotif(p, b, pr?.remainingM ?: 0.0, pr?.remainingS ?: 0.0, pr?.distToTurn,
                pr?.icon ?: 1, pr?.street.orEmpty(), d?.label.orEmpty(), pr != null, r?.totalM ?: 0.0, lim)
        }.stateIn(scope, SharingStarted.Eagerly,
            NavNotif(Phase.IDLE, BleState.IDLE, 0.0, 0.0, null, 1, "", "", false, 0.0, 0))

        scope.launch {
            combine(_phase, ble.state) { p, s -> p != Phase.IDLE || s != BleState.IDLE && s != BleState.OFF }
                .distinctUntilChanged()
                .collect { updateService(it) }
        }
        scope.launch {                                            // the device version is only valid while the link is up
            ble.state.collect { if (it != BleState.CONNECTED) _deviceFw.value = -1 }
        }
        scope.launch {                                            // GPS-lost watchdog (README 10)
            while (true) {
                delay(1000)
                watchdog()
            }
        }
        refreshCapabilities()
    }

    // ---- logging / banners ----------------------------------------------------------------------
    override fun onLog(s: String) = log(s)

    private fun log(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.value = (_log.value + "$t $s").takeLast(300)
    }

    private fun banner(msg: String?) {
        _banner.value = msg
        bannerJob?.cancel()
        if (msg != null) bannerJob = scope.launch { delay(7000); _banner.value = null }
    }

    private fun updateService(want: Boolean) {
        val i = Intent(app, NavService::class.java)
        try {
            if (want) ContextCompat.startForegroundService(app, i) else app.stopService(i)
        } catch (e: Exception) {
            log("service: ${e.message}")
        }
    }

    // ---- BLE side -------------------------------------------------------------------------------
    fun scan() = ble.scan()
    fun stopScan() = ble.stopScan()

    fun connect(address: String, name: String?) {
        prefs.update { it.copy(deviceAddress = address, deviceName = name.orEmpty()) }
        ble.connect(address)
    }

    fun autoConnect() {
        val a = prefs.settings.value.deviceAddress
        if (a.isNotEmpty() && ble.state.value == BleState.IDLE) ble.connect(a)
    }

    /** Drops the BLE link but keeps the saved device (notification "Disconnect" action). */
    fun disconnectDevice() {
        ble.disconnect()
        helloAcked = false
    }

    fun forgetDevice() {
        ble.disconnect()
        helloAcked = false
        prefs.update { it.copy(deviceAddress = "", deviceName = "") }
    }

    private fun sendCmd(b: ByteArray) {
        if (_phoneOnly.value) return                              // phone-only mode: nothing goes to the device
        if (b[0].toInt() != Proto.POSE) log("> ${Proto.name(b[0].toInt() and 0xFF)} ${b.size}B")
        ble.send(b)
    }

    override fun onReady() {
        helloAcked = false
        ble.send(Proto.hello())
        log("> HELLO")
    }

    override fun onBattery(pct: Int) { _battery.value = pct }

    override fun onData(d: ByteArray) {
        if (d.isEmpty()) return
        when (d[0].toInt() and 0xFF) {
            Proto.HELLO_ACK -> Proto.parseHelloAck(d)?.let { onHelloAck(it) }
            Proto.OTA_ACK -> Proto.parseOtaAck(d)?.let { Ota.onAck(it) }
            Proto.ROUTE_ACK -> Proto.parseRouteAck(d)?.let {
                _lastAck.value = "route=${it.routeId} status=${it.status} expected=${it.expectedIdx} free=${it.freeSlots}"
                streamer.onAck(it)
                streamer.pump(currentAlong)
            }
        }
    }

    private fun onHelloAck(a: HelloAck) {
        log("< HELLO_ACK proto=${a.proto} fw=0x${a.fw.toString(16)} ${a.screenW}x${a.screenH} max=${a.maxPoints}")
        _deviceFw.value = a.fw
        if (a.proto != Proto.VERSION) {                           // README 6.8
            banner("Device firmware speaks protocol ${a.proto}; this app needs ${Proto.VERSION}. Update required.")
            ble.disconnect()
            return
        }
        helloAcked = true
        streamer.maxPoints = a.maxPoints
        pushConfig()
        uploadRoute()
    }

    /** README 6.3: after (re)connect, replay CONFIG, the route window and the maneuver. */
    private fun uploadRoute() {
        if (!helloAcked) return
        val r = route.value
        val p = _phase.value
        if (r == null || p == Phase.IDLE || p == Phase.ROUTING) {
            sendCmd(Proto.navState(Proto.NAV_IDLE))
            return
        }
        routeId = (routeId + 1) and 0xFF
        val start = max(0, (engine?.lastSeg ?: 0) - 8)
        streamer.start(r, routeId, start)
        streamer.pump(currentAlong)
        lastManeuverKey = null
        sendCmd(Proto.navState(navCode()))
    }

    private fun navCode() = when (_phase.value) {
        Phase.NAVIGATING -> Proto.NAV_NAVIGATING
        Phase.REROUTING -> Proto.NAV_REROUTING
        Phase.ARRIVED -> Proto.NAV_ARRIVED
        else -> Proto.NAV_IDLE
    }

    fun pushConfig() {
        val s = prefs.settings.value
        var f = 0
        if (s.headingUp) f = f or Proto.CFG_HEADING_UP
        if (s.invert) f = f or Proto.CFG_INVERT
        if (s.showFps) f = f or Proto.CFG_SHOW_FPS
        if (s.imperial) f = f or Proto.CFG_IMPERIAL
        if (helloAcked) sendCmd(Proto.config(s.zoom, f))
    }

    fun onSettingsChanged() {
        pushConfig()
        val s = prefs.settings.value
        if (capsKeyOf(s) != capsUrl) refreshCapabilities()
    }

    fun setContactEmail(email: String) {
        prefs.setContactEmail(email)
        onSettingsChanged()
    }

    fun refreshCapabilities() {
        val s = prefs.settings.value
        capsUrl = capsKeyOf(s)
        scope.launch {
            _caps.value = Router.capabilities(capsUrl, s.userAgent)
            if (_caps.value == null) log("capabilities not found at $capsUrl")
        }
    }

    private fun capsKeyOf(s: Settings) = s.capsUrl.trim().ifBlank { s.osrmUrl.trimEnd('/') + "/capabilities.json" }

    // ---- location -------------------------------------------------------------------------------
    private val locCb = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            if (_replaying.value || _simulating.value) return
            for (l in res.locations) {
                handleFix(Fix(l.latitude, l.longitude, l.accuracy,
                    if (l.hasSpeed()) l.speed else 0f,
                    if (l.hasBearing() && l.hasSpeed() && l.speed >= 1f) l.bearing else null,
                    System.currentTimeMillis()))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun ensureLocation(fast: Boolean = false) {
        if (locationOn && fast == fastMode) return
        try {
            // No removeLocationUpdates here: re-requesting with the same callback replaces the old request
            // without a gap in fixes (the gap used to trip the GPS-lost watchdog).
            val iv = if (fast) 200L else 1000L
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, iv)
                .setMinUpdateIntervalMillis(if (fast) 200L else 500L).build()
            fused.requestLocationUpdates(req, locCb, Looper.getMainLooper())
            locationOn = true
            fastMode = fast
        } catch (e: SecurityException) {
            log("location permission missing")
        }
    }

    fun maybeStopLocation() {
        if (_phase.value == Phase.IDLE && locationOn) {
            fused.removeLocationUpdates(locCb)
            locationOn = false
        }
    }

    // ---- destination ----------------------------------------------------------------------------
    fun setDestination(lat: Double, lon: Double, label: String, confirmed: Boolean = true) {
        _dest.value = Dest(lat, lon, label, confirmed)
        _destSnapped.value = null
        _note.value = null
        _results.value = emptyList()
        _noRouteHint.value = false
        markPlanDirty()                      // mid-trip: wait for "Apply changes"
    }

    fun confirmDestination() { _dest.value = _dest.value?.copy(confirmed = true) }
    fun clearDestination() { _dest.value = null; _destSnapped.value = null; _note.value = null; _waypoints.value = emptyList() }
    fun dismissSwapPrompt() { _swapPrompt.value = null }
    fun acceptSwap() {
        _swapPrompt.value?.let {
            val lbl = "%.5f, %.5f".format(it.lat, it.lon)
            if (swapAsStop) addWaypoint(it.lat, it.lon, lbl) else setDestination(it.lat, it.lon, lbl, false)
        }
        swapAsStop = false
        _swapPrompt.value = null
    }

    // ---- waypoints ------------------------------------------------------------------------------
    fun addWaypoint(lat: Double, lon: Double, label: String) {
        _waypoints.value = _waypoints.value + Dest(lat, lon, label)
        _results.value = emptyList()
        waypointsChanged()
    }

    fun removeWaypoint(i: Int) {
        val l = _waypoints.value
        if (i !in l.indices) return
        _waypoints.value = l.filterIndexed { k, _ -> k != i }
        waypointsChanged()
    }

    fun moveWaypointUp(i: Int) {
        val l = _waypoints.value.toMutableList()
        if (i < 1 || i >= l.size) return
        val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t
        _waypoints.value = l
        waypointsChanged()
    }

    private fun waypointsChanged() {
        _noRouteHint.value = false
        markPlanDirty()
    }

    private fun markPlanDirty() {
        val p = _phase.value
        if (p == Phase.NAVIGATING || p == Phase.REROUTING) _planDirty.value = true
    }

    /** "Apply changes": reroute from the current position with the edited destination / waypoints. */
    fun applyPlan() {
        _planDirty.value = false
        forceReroute()
    }

    private fun forceReroute() {
        val p = _phase.value
        if (p != Phase.NAVIGATING && p != Phase.REROUTING) return
        val fx = _fix.value ?: return
        if (rerouting) { pendingReroute = true; return }
        nextRerouteAt = 0
        maybeReroute(fx, SystemClock.elapsedRealtime())
    }

    /** README 3: one field. Raw coordinates are checked first (no network); otherwise Nominatim. */
    fun submitQuery(q: String, asStop: Boolean = false) {
        when (val c = CoordParser.parse(q)) {
            is CoordResult.Ok -> {
                val lbl = "%.5f, %.5f".format(c.lat, c.lon)
                if (asStop) addWaypoint(c.lat, c.lon, lbl) else setDestination(c.lat, c.lon, lbl, confirmed = false)
            }
            is CoordResult.Swapped -> { swapAsStop = asStop; _swapPrompt.value = LatLon(c.lat, c.lon) }
            CoordResult.Invalid -> banner("Coordinates out of range (|lat| <= 90, |lon| <= 180)")
            CoordResult.NotCoords -> {
                val s = prefs.settings.value
                scope.launch {
                    _searching.value = true
                    try {
                        val r = Geocoder.search(s.nominatimUrl, s.userAgent, q)
                        _results.value = r
                        if (r.isEmpty()) banner("No results. Try coordinates, e.g. -34.6037, -58.3816")
                    } catch (e: RateLimitedException) {
                        banner("Nominatim rate limit hit. Wait a few seconds.")
                    } catch (e: Exception) {
                        banner("Search failed: ${e.message}")
                    } finally {
                        _searching.value = false
                    }
                }
            }
        }
    }

    // ---- save route (GPX) -----------------------------------------------------------------------
    fun suggestedRouteName(): String =
        "cubinav-route-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".gpx"

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Waypoints in order, then the destination, as a GPX route (<rte>). */
    private fun routeGpx(): String? {
        val d = _dest.value ?: return null
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"CubiNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><time>").append(Instant.now()).append("</time></metadata>\n")
        sb.append("  <rte>\n    <name>").append(xmlEsc(d.label)).append("</name>\n")
        fun pt(p: Dest, desc: String) {
            sb.append("    <rtept lat=\"%.6f\" lon=\"%.6f\">\n".format(java.util.Locale.US, p.lat, p.lon))
            sb.append("      <name>").append(xmlEsc(p.label)).append("</name>\n")
            sb.append("      <desc>").append(desc).append("</desc>\n    </rtept>\n")
        }
        _waypoints.value.forEachIndexed { i, w -> pt(w, "waypoint ${i + 1}") }
        pt(d, "destination")
        sb.append("  </rte>\n</gpx>\n")
        return sb.toString()
    }

    fun saveRoute(uri: Uri) {
        val xml = routeGpx() ?: run { banner("Nothing to save: set a destination first"); return }
        val n = _waypoints.value.size
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                }
                banner("Route saved: $n waypoint(s) + destination")
            } catch (e: Exception) {
                banner("Save failed: ${e.message}")
            }
        }
    }

    // ---- load route (GPX) -----------------------------------------------------------------------
    class RoutePt(val lat: Double, val lon: Double, val label: String)

    /** Loads a GPX saved by [saveRoute] (or any GPX with route points): last point = destination, the rest = waypoints. */
    fun loadRoute(uri: Uri) {
        scope.launch {
            val pts = try {
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { parseGpxRoute(it) } }
            } catch (e: Exception) {
                banner("Load failed: ${e.message}"); return@launch
            }
            if (pts.isEmpty()) { banner("No route points (rtept / wpt) found in that file"); return@launch }
            val dst = pts.last()
            _waypoints.value = pts.dropLast(1).map { Dest(it.lat, it.lon, it.label) }
            setDestination(dst.lat, dst.lon, dst.label)
            banner("Route loaded: ${pts.size - 1} waypoint(s) + destination")
        }
    }

    /** Route points (<rtept>); falls back to waypoints (<wpt>) if the file has no route. Tracks are ignored on purpose. */
    private fun parseGpxRoute(ins: InputStream): List<RoutePt> {
        val rte = ArrayList<RoutePt>()
        val wpt = ArrayList<RoutePt>()
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        var cur: String? = null            // "rtept" or "wpt" while inside one
        var lat = 0.0
        var lon = 0.0
        var name = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "rtept" || p.name == "wpt") {
                    cur = p.name
                    lat = p.getAttributeValue(null, "lat").toDouble()
                    lon = p.getAttributeValue(null, "lon").toDouble()
                    name = ""
                } else if (cur != null && p.name == "name") {
                    name = p.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG && cur != null && p.name == cur) {
                val label = name.ifEmpty { "%.5f, %.5f".format(java.util.Locale.US, lat, lon) }
                (if (cur == "rtept") rte else wpt).add(RoutePt(lat, lon, label))
                cur = null
            }
            ev = p.next()
        }
        return if (rte.isNotEmpty()) rte else wpt
    }

    // ---- road filters (README 5.2) -------------------------------------------------------------------
    fun excludedFor(s: Settings, caps: Capabilities?): Set<String> {
        // Only manage classes the UI knows. Server-side extras (tunnel, restricted, ...) must never be excluded.
        val universe = (caps?.classes?.takeIf { it.isNotEmpty() } ?: if (s.allRoads) Roads.ALL else Roads.STOCK).intersect(Roads.ALL)
        return universe - s.allowed
    }

    fun roadCheck(s: Settings, caps: Capabilities?): RoadCheck {
        if (activePreset(s, caps) != null) return RoadCheck(null, false)
        val ex = excludedFor(s, caps)
        if (ex.isEmpty()) return RoadCheck(null, false)
        if (caps == null) return RoadCheck("Server capabilities unavailable: filters are sent unverified.", false)
        if (!caps.supports(ex)) return RoadCheck("This server cannot avoid exactly: ${ex.joinToString()}. Change the toggles or the server profile.", true)
        return RoadCheck(null, false)
    }

    private fun activePreset(s: Settings, caps: Capabilities?): Preset? =
        if (s.presetId.isEmpty()) null else caps?.presets?.firstOrNull { it.id == s.presetId }

    fun relaxFilters() {
        prefs.update { it.copy(allowed = Roads.ALL, presetId = "") }
        _noRouteHint.value = false
    }

    private suspend fun requestRoute(from: LatLon, to: LatLon, bearing: Int?): Route {
        val s = prefs.settings.value
        val c = _caps.value
        val preset = activePreset(s, c)
        val base = preset?.url ?: s.osrmUrl
        val ex = if (preset != null) emptySet() else excludedFor(s, c)
        reqWps = _waypoints.value
        SpeedDb.ensureLoaded(app)
        return Router.route(base, s.profile, s.userAgent, from, to, ex, bearing, reqWps.map { LatLon(it.lat, it.lon) }, withNodes = SpeedDb.available)
    }

    // ---- navigation lifecycle ---------------------------------------------------------------------------
    fun startNavigation(phoneOnly: Boolean = false) {
        val d = _dest.value ?: return
        if (Ota.busy) { banner("A firmware update is in progress"); return }
        val f = _fix.value
        if (f == null) { banner("Waiting for a GPS fix"); return }
        val chk = roadCheck(prefs.settings.value, _caps.value)
        if (chk.blocking) { banner(chk.message); return }
        _noRouteHint.value = false
        _phoneOnly.value = phoneOnly
        _phase.value = Phase.ROUTING
        scope.launch {
            try {
                val r = requestRoute(LatLon(f.lat, f.lon), LatLon(d.lat, d.lon), null)
                projector = Projector(f.lat, f.lon)
                prefs.addRecent(d)
                if (r.snapDestM > 100) {
                    _note.value = "Nearest reachable road is ${r.snapDestM.roundToInt()} m away"
                }
                _destSnapped.value = LatLon(r.lat.last(), r.lon.last())
                applyRoute(r, reroute = false)
            } catch (e: NoRouteException) {
                _phase.value = Phase.IDLE
                _phoneOnly.value = false
                _noRouteHint.value = true
                banner("No route with these road types")
            } catch (e: UnsupportedExcludeException) {
                _phase.value = Phase.IDLE
                _phoneOnly.value = false
                _noRouteHint.value = true
                banner("This OSRM server can't avoid that combination of road types")
            } catch (e: Exception) {
                _phase.value = Phase.IDLE
                _phoneOnly.value = false
                banner("Routing failed: ${e.message}")
            }
        }
    }

    private fun applyRoute(r: Route, reroute: Boolean) {
        _route.value = r
        engine = NavEngine(r)
        currentAlong = 0.0
        heldLimit = 0; heldLimitAlong = 0.0; _speedLimit.value = 0
        viaPassed = 0
        routeWps = reqWps
        _planDirty.value = false
        offRouteSince = 0
        _phase.value = Phase.NAVIGATING
        ensureLocation(false)
        if (helloAcked && !_phoneOnly.value) {
            routeId = (routeId + 1) and 0xFF
            streamer.start(r, routeId, 0)
            streamer.pump(0.0)
            lastManeuverKey = null
            sendCmd(Proto.navState(Proto.NAV_NAVIGATING))
        }
        log(if (reroute) "rerouted: ${r.n} pts, ${r.totalM.roundToInt()} m" else "route: ${r.n} pts, ${r.totalM.roundToInt()} m")
    }

    fun stopNavigation() {
        if (helloAcked) {
            streamer.clearOnDevice()
            sendCmd(Proto.navState(Proto.NAV_IDLE))
        } else streamer.stop()
        _phoneOnly.value = false
        stopReplay()
        _phase.value = Phase.IDLE
        _route.value = null
        _progress.value = null
        _snapped.value = null
        _destSnapped.value = null
        engine = null
        heldLimit = 0; _speedLimit.value = 0
        rerouting = false
        backoffIdx = 0
        lastManeuverKey = null
        _planDirty.value = false
    }

    // ---- fix handling ---------------------------------------------------------------------------------
    private fun handleFix(f: Fix) {
        _fix.value = f
        lastFixAt = SystemClock.elapsedRealtime()
        if (f.acc > prefs.settings.value.maxAccuracy) return        // README 7.1
        lastGoodFixAt = lastFixAt
        if (gpsLost) {
            gpsLost = false
            log("GPS usable again")
            if (_phase.value == Phase.NAVIGATING || _phase.value == Phase.REROUTING) sendCmd(Proto.navState(navCode()))
        }
        if (f.speed >= 2f && f.bearing != null) gpsHeading = f.bearing.toDouble()

        val p = _phase.value
        val r = route.value
        val eng = engine
        if (r == null || eng == null || projector == null || (p != Phase.NAVIGATING && p != Phase.REROUTING)) return
        navStep(f, r, eng)
    }

    private fun navStep(f: Fix, r: Route, eng: NavEngine) {
        val now = SystemClock.elapsedRealtime()
        val m = eng.match(f.lat, f.lon)                              // README 7.2
        val snapped = m.distM <= 25.0
        val lat = if (snapped) m.lat else f.lat
        val lon = if (snapped) m.lon else f.lon
        val heading = if (snapped) m.heading else gpsHeading
        currentAlong = m.along
        _snapped.value = LatLon(lat, lon)
        _heading.value = heading

        // posted speed limit of the matched segment; kept for 500 m when the data has a gap
        val segLim = r.limits?.let { it[m.seg.coerceIn(0, it.size - 1)].toInt() and 0xFF } ?: 0
        if (segLim > 0) { heldLimit = segLim; heldLimitAlong = m.along }
        else if (m.along - heldLimitAlong > 500.0) heldLimit = 0
        if (_speedLimit.value != heldLimit) _speedLimit.value = heldLimit

        // waypoints: once we are past one, drop it so a later reroute does not send us back
        val vias = r.steps.filter { it.type == "via" }
        while (viaPassed < vias.size && m.along >= vias[viaPassed].along - 15.0) {
            routeWps.getOrNull(viaPassed)?.let { gone ->
                log("passed waypoint: ${gone.label}")
                _waypoints.value = _waypoints.value - gone
            }
            viaPassed++
        }

        // off-route (README 7.4)
        if (m.distM > max(30.0, 2.0 * f.acc)) {
            if (offRouteSince == 0L) offRouteSince = now
            else if (now - offRouteSince >= 3000) maybeReroute(f, now)
        } else {
            offRouteSince = 0
            if (_phase.value == Phase.REROUTING && !rerouting) {
                _phase.value = Phase.NAVIGATING
                sendCmd(Proto.navState(Proto.NAV_NAVIGATING))
            }
        }

        // next maneuver (README 7.3)
        // waypoints only shape the route: no maneuver for the via itself or for the leg restart after it
        val next = r.steps.firstOrNull { it.pointIdx > 0 && it.along > m.along + 1.0 && it.type != "via" && it.type != "depart" }
        val remaining = r.totalM - m.along
        val dist = next?.let { (it.along - m.along).roundToInt() }
        val icon = next?.icon ?: 10
        val street = next?.street ?: ""
        _progress.value = Progress(remaining, r.durationS * (1.0 - m.along / r.totalM), dist, icon, street)

        // arrival (README 7.6)
        if (remaining <= 20.0 && _phase.value != Phase.ARRIVED) {
            _phase.value = Phase.ARRIVED
            sendCmd(Proto.maneuver(10, Maneuvers.ascii(_dest.value?.label.orEmpty())))
            sendCmd(Proto.navState(Proto.NAV_ARRIVED))
            banner("Arrived")
        }
        if (_phase.value == Phase.ARRIVED) return

        val key = "$icon|$street"
        if (key != lastManeuverKey) {
            lastManeuverKey = key
            sendCmd(Proto.maneuver(icon, street))
        }

        // POSE
        var pr = projector!!
        var x = pr.x(lon)
        var y = pr.y(lat)
        if (abs(x) > REBASE_DM || abs(y) > REBASE_DM) {
            sendCmd(Proto.rebase(x, y))                              // README 6.2 / 6.4
            pr = pr.rebased(x, y)
            projector = pr
            x = pr.x(lon); y = pr.y(lat)
        }
        lastPoseX = x; lastPoseY = y
        val hdgDeci = (((heading * 10).roundToInt() % 3600) + 3600) % 3600
        val speedCms = if (f.speed < 0.5f) 0 else (f.speed * 100).roundToInt()
        if (helloAcked && !_phoneOnly.value) {
            val pb = Proto.pose(x, y, hdgDeci, speedCms, dist ?: 0xFFFF, true, heldLimit)
            lastPoseBytes = pb
            lastPoseSentAt = now
            ble.sendPose(pb)
            _lastPose.value = "x=$x y=$y hdg=${hdgDeci / 10.0} v=${speedCms}cm/s d=${dist ?: -1}m"
            streamer.pump(m.along)
        }

        // 1 Hz normally, 5 Hz near a turn (README 2)
        val wantFast = dist != null && dist < 300
        if (wantFast != fastMode && !_replaying.value && !_simulating.value) ensureLocation(wantFast)
    }

    private fun maybeReroute(f: Fix, now: Long) {
        if (rerouting || now < nextRerouteAt) return
        rerouting = true
        _phase.value = Phase.REROUTING
        sendCmd(Proto.navState(Proto.NAV_REROUTING))
        val d = _dest.value ?: run { rerouting = false; return }
        val hdg = if (f.speed >= 2f) gpsHeading.roundToInt() else null
        scope.launch {
            try {
                val r = try {
                    requestRoute(LatLon(f.lat, f.lon), LatLon(d.lat, d.lon), hdg)
                } catch (e: Exception) {
                    if (hdg != null && e !is NoRouteException && (e as? Net.HttpException) != null) {
                        requestRoute(LatLon(f.lat, f.lon), LatLon(d.lat, d.lon), null)
                    } else throw e
                }
                backoffIdx = 0
                nextRerouteAt = SystemClock.elapsedRealtime() + 5000
                applyRoute(r, reroute = true)
            } catch (e: NoRouteException) {
                banner("No route with these road types (keeping old route)")
                failReroute()
            } catch (e: UnsupportedExcludeException) {
                banner("This OSRM server can't avoid that combination of road types (keeping old route)")
                failReroute()
            } catch (e: Exception) {
                banner("OSRM unreachable. Keeping current route.")
                failReroute()
            } finally {
                rerouting = false
                if (pendingReroute) { pendingReroute = false; forceReroute() }
            }
        }
    }

    private fun failReroute() {
        nextRerouteAt = SystemClock.elapsedRealtime() + BACKOFF_S[minOf(backoffIdx, BACKOFF_S.size - 1)] * 1000
        backoffIdx++
    }

    private fun watchdog() {
        val p = _phase.value
        if (p != Phase.NAVIGATING && p != Phase.REROUTING) return
        if (_replaying.value || _simulating.value) return
        val now = SystemClock.elapsedRealtime()
        val noFix = now - lastFixAt
        val noGood = now - lastGoodFixAt
        val toDevice = helloAcked && !_phoneOnly.value
        if (!gpsLost && (noFix > 5000 || noGood > 10000)) {
            gpsLost = true
            log("GPS lost: last fix ${noFix} ms ago, last usable fix ${noGood} ms ago")
            if (toDevice) {
                ble.sendPose(Proto.pose(lastPoseX, lastPoseY, 0, 0, 0xFFFF, false))   // flags.bit0 = 0
                sendCmd(Proto.navState(Proto.NAV_NO_GPS))
            }
        } else if (!gpsLost && toDevice && now - lastPoseSentAt >= 900) {
            // Keepalive: fixes dropped by the accuracy filter or a late fused update must not starve the
            // device's own GPS timeout while the phone still considers GPS fine.
            lastPoseBytes?.let { ble.sendPose(it); lastPoseSentAt = now }
        }
    }

    // ---- simulator (debug mode) ---------------------------------------------------------------------
    fun startSimulation(text: String, kmh: Float) {
        val c = if (text.isBlank()) _fix.value?.let { CoordResult.Ok(it.lat, it.lon) } else CoordParser.parse(text)
        if (c !is CoordResult.Ok) { log("simulator: enter a start as 'lat, lon' (or wait for a GPS fix)"); return }
        stopReplay()
        simJob?.cancel()
        simLat = c.lat; simLon = c.lon; simKmh = kmh
        simBearing = 0.0; simRoute = null; simAlong = 0.0; simSeg = 0; simFree = false
        _simPaused.value = false
        _simulating.value = true
        log("simulator on at %.5f, %.5f".format(simLat, simLon))
        simJob = scope.launch {
            while (true) {
                simTick()
                delay(1000)
            }
        }
    }

    fun stopSimulation() {
        simJob?.cancel()
        simJob = null
        if (_simulating.value) log("simulator off")
        _simulating.value = false
        _simPaused.value = false
    }

    fun setSimSpeed(kmh: Float) { simKmh = kmh }
    fun toggleSimPause() { _simPaused.value = !_simPaused.value }

    /** Leave the route at a right angle and keep driving straight, so the off-route / reroute path can be tested. */
    fun simVeerOff() { simFree = true; simBearing = (simBearing + 90.0) % 360.0 }

    private fun simTick() {
        val r = _route.value
        val p = _phase.value
        val driving = !_simPaused.value && r != null && (p == Phase.NAVIGATING || p == Phase.REROUTING)
        val v = if (driving) simKmh / 3.6 else 0.0
        if (r !== simRoute) {                  // a new or rerouted route starts at the current position
            simRoute = r
            simAlong = 0.0
            simSeg = 0
            if (r != null) simFree = false
        }
        if (r != null && driving && !simFree) {
            simAlong = min(simAlong + v, r.totalM)
            while (simSeg < r.n - 2 && r.cum[simSeg + 1] < simAlong) simSeg++
            val len = r.cum[simSeg + 1] - r.cum[simSeg]
            val t = if (len < 1e-6) 0.0 else ((simAlong - r.cum[simSeg]) / len).coerceIn(0.0, 1.0)
            simLat = r.lat[simSeg] + t * (r.lat[simSeg + 1] - r.lat[simSeg])
            simLon = r.lon[simSeg] + t * (r.lon[simSeg + 1] - r.lon[simSeg])
            simBearing = Geo.bearing(r.lat[simSeg], r.lon[simSeg], r.lat[simSeg + 1], r.lon[simSeg + 1])
        } else if (driving && simFree) {
            val br = Math.toRadians(simBearing)
            simLat += Math.toDegrees(v * cos(br) / Geo.R)
            simLon += Math.toDegrees(v * sin(br) / (Geo.R * cos(Math.toRadians(simLat))))
        }
        handleFix(Fix(simLat, simLon, 5f, v.toFloat(), if (v >= 1.0) simBearing.toFloat() else null, System.currentTimeMillis()))
    }

    // ---- GPX replay (README 11) -----------------------------------------------------------------------------
    class GpxPoint(val lat: Double, val lon: Double, val t: Long?)

    fun startReplay(uri: Uri, mult: Float) {
        stopSimulation()
        stopReplay()
        replayJob = scope.launch {
            val pts = try {
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { parseGpx(it) } }
            } catch (e: Exception) {
                banner("GPX read failed: ${e.message}"); return@launch
            }
            if (pts.size < 2) { banner("GPX has fewer than 2 points"); return@launch }
            _replaying.value = true
            ensureLocation(false)
            handleFix(Fix(pts[0].lat, pts[0].lon, 5f, 0f, null, System.currentTimeMillis()))
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val dtMs = if (a.t != null && b.t != null && b.t > a.t) (b.t - a.t) else 1000L
                delay((dtMs / mult).toLong().coerceAtLeast(20))
                val d = Geo.distance(a.lat, a.lon, b.lat, b.lon)
                val speed = (d / (dtMs / 1000.0)).toFloat()
                handleFix(Fix(b.lat, b.lon, 5f, speed, Geo.bearing(a.lat, a.lon, b.lat, b.lon).toFloat(), System.currentTimeMillis()))
            }
            _replaying.value = false
        }
    }

    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _replaying.value = false
    }

    private fun parseGpx(ins: InputStream): List<GpxPoint> {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        val out = ArrayList<GpxPoint>()
        var lat = 0.0
        var lon = 0.0
        var t: Long? = null
        var inPt = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "trkpt" || p.name == "rtept") {
                    inPt = true
                    lat = p.getAttributeValue(null, "lat").toDouble()
                    lon = p.getAttributeValue(null, "lon").toDouble()
                    t = null
                } else if (inPt && p.name == "time") {
                    t = runCatching { Instant.parse(p.nextText().trim()).toEpochMilli() }.getOrNull()
                }
            } else if (ev == XmlPullParser.END_TAG && (p.name == "trkpt" || p.name == "rtept")) {
                out.add(GpxPoint(lat, lon, t))
                inPt = false
            }
            ev = p.next()
        }
        return out
    }
}

fun fmtDist(m: Double, imperial: Boolean): String =
    if (!imperial) {
        if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000.0)
    } else {
        val ft = m * 3.28084
        if (ft < 528) "${(ft / 50).roundToInt() * 50} ft" else "%.1f mi".format(m / 1609.344)
    }

fun fmtDuration(s: Double): String {
    val mins = (s / 60).roundToInt()
    return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
}