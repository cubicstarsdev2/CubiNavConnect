package com.tahvia.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.PathParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * README 9: keeps GPS and the BLE link alive with the screen off. The service owns no logic;
 * [NavController] does the work and this just anchors the process with a persistent notification.
 *
 * While a trip is active the notification is a media-player style card: turn icon, instruction,
 * street, route progress bar and a row of controls. When idle it is a plain status notification.
 */
class NavService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NavController.init(applicationContext)      // idempotent; the service may outlive the activity
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.deleteNotificationChannel(OLD_CH)    // channel settings are frozen once created: use a fresh id
            nm.createNotificationChannel(
                NotificationChannel(CH, "Navigation status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Trip progress and quick controls while TahviaNav is running"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
        }
        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else 0
        try {
            ServiceCompat.startForeground(this, ID, build(NavController.notif.value), type)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        scope.launch {
            NavController.notif.collect { nm.notify(ID, build(it)) }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------
    private fun action(name: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, NavService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun verb(code: Int) = when (code) {
        1 -> "Continue straight"
        2 -> "Turn left"
        3 -> "Turn right"
        4 -> "Bear left"
        5 -> "Bear right"
        6 -> "Sharp left"
        7 -> "Sharp right"
        8 -> "Make a U-turn"
        9 -> "Take the roundabout"
        10 -> "Arrive at destination"
        else -> "Continue"
    }

    private fun etaText(n: NavNotif): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (n.remainingS * 1000).toLong()))

    // ---- turn icon, drawn once per maneuver ------------------------------------------------------
    private var cachedCode = -1
    private var cachedBmp: Bitmap? = null

    private fun turnBitmap(code: Int): Bitmap {
        cachedBmp?.let { if (code == cachedCode) return it }
        val s = 128
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF00696B.toInt()
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 30f, 30f, p)
        val data = when (code) {
            9 -> ROUNDABOUT_PATH
            10 -> PIN_PATH
            else -> ARROW_PATH
        }
        val path = PathParser.createPathFromPathData(data)
        val scale = s * 0.6f / 24f
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate((s - 24 * scale) / 2f, (s - 24 * scale) / 2f)
        if (code != 9 && code != 10) {
            val deg = when (code) { 2 -> 270f; 3 -> 90f; 4 -> 315f; 5 -> 45f; 6 -> 225f; 7 -> 135f; 8 -> 180f; else -> 0f }
            m.postRotate(deg, s / 2f, s / 2f)
        }
        path.transform(m)
        p.color = Color.WHITE
        c.drawPath(path, p)
        cachedCode = code
        cachedBmp = bmp
        return bmp
    }

    // ---- notification -----------------------------------------------------------------------------
    private fun build(n: NavNotif): Notification =
        if (n.phase == Phase.IDLE) idleNotification(n) else tripNotification(n)

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setColor(0xFF00696B.toInt())
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private fun idleNotification(n: NavNotif): Notification {
        val b = baseBuilder()
            .setContentTitle("TahviaNav")
            .setContentText(if (n.ble == BleState.CONNECTED) "Display connected" else bleLabel(n.ble))
        if (n.ble != BleState.IDLE && n.ble != BleState.OFF) b.addAction(0, "Disconnect", action(ACTION_DISCONNECT, 2))
        b.addAction(0, "Open", openApp())
        return b.build()
    }

    private fun tripNotification(n: NavNotif): Notification {
        val imperial = NavController.prefs.settings.value.imperial
        val arrived = n.phase == Phase.ARRIVED
        val moving = n.phase == Phase.NAVIGATING || n.phase == Phase.REROUTING

        val title: String
        val subtitle: String
        when (n.phase) {
            Phase.ROUTING -> { title = "Calculating route..."; subtitle = n.dest.ifEmpty { "Destination" } }
            Phase.REROUTING -> { title = "Rerouting..."; subtitle = "Finding a new way from here" }
            Phase.ARRIVED -> { title = "You have arrived"; subtitle = n.dest.ifEmpty { "Destination" } }
            else -> {
                title = n.distToTurn?.let { "${fmtDist(it.toDouble(), imperial)}  ·  ${verb(n.icon)}" } ?: verb(n.icon)
                subtitle = n.street.ifEmpty { if (n.dest.isNotEmpty()) "To: ${n.dest}" else "Follow the route" }
            }
        }

        val traveled = (n.totalM - n.remainingM).coerceAtLeast(0.0)
        val pct = if (n.hasProgress && n.totalM > 0) ((traveled / n.totalM) * 1000).toInt().coerceIn(0, 1000) else 0
        val icon = turnBitmap(if (arrived) 10 else if (n.phase == Phase.ROUTING) 1 else n.icon)

        fun fillCommon(rv: RemoteViews) {
            rv.setImageViewBitmap(R.id.turn_icon, icon)
            rv.setTextViewText(R.id.title, title)
            rv.setTextViewText(R.id.subtitle, subtitle)
            rv.setProgressBar(R.id.bar, 1000, pct, false)
        }

        val small = RemoteViews(packageName, R.layout.notif_nav_small).also { fillCommon(it) }
        val big = RemoteViews(packageName, R.layout.notif_nav_big).also { rv ->
            fillCommon(rv)
            rv.setTextViewText(R.id.t_left, if (n.hasProgress) fmtDist(traveled, imperial) else "")
            rv.setTextViewText(R.id.t_right,
                if (n.hasProgress) "${fmtDist(n.remainingM, imperial)}  ·  ${fmtDuration(n.remainingS)}" else "")

            rv.setOnClickPendingIntent(R.id.b_open, openApp())
            rv.setContentDescription(R.id.b_open, "Open map")

            rv.setOnClickPendingIntent(R.id.b_recalc, action(ACTION_RECALC, 3))
            rv.setContentDescription(R.id.b_recalc, "Recalculate route")
            rv.setViewVisibility(R.id.w_recalc, if (moving) View.VISIBLE else View.GONE)

            rv.setOnClickPendingIntent(R.id.b_stop, action(ACTION_STOP, 1))
            rv.setImageViewResource(R.id.b_stop, if (arrived) R.drawable.ic_nav_done else R.drawable.ic_nav_stop)
            rv.setContentDescription(R.id.b_stop, if (arrived) "Finish" else "Stop navigation")

            rv.setOnClickPendingIntent(R.id.b_disc, action(ACTION_DISCONNECT, 2))
            rv.setContentDescription(R.id.b_disc, "Disconnect display")
            rv.setViewVisibility(R.id.w_disc,
                if (n.ble != BleState.IDLE && n.ble != BleState.OFF) View.VISIBLE else View.GONE)
        }

        val sub = buildString {
            if (moving && n.hasProgress && n.remainingS > 0) append("ETA ").append(etaText(n)).append("  ·  ")
            if (moving && n.limitKmh > 0) append("Limit ").append(limitValue(n.limitKmh, imperial)).append("  ·  ")
            append("Display ").append(bleLabel(n.ble).lowercase())
        }

        return baseBuilder()
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText(sub)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> NavController.stopNavigation()
            ACTION_RECALC -> NavController.applyPlan()
            ACTION_DISCONNECT -> NavController.disconnectDevice()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val OLD_CH = "nav"
        private const val CH = "nav_status"
        private const val ID = 1
        private const val ACTION_STOP = "com.tahvia.nav.action.STOP"
        private const val ACTION_RECALC = "com.tahvia.nav.action.RECALC"
        private const val ACTION_DISCONNECT = "com.tahvia.nav.action.DISCONNECT"

        private const val ARROW_PATH = "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z"
        private const val PIN_PATH = "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"
        private const val ROUNDABOUT_PATH = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
    }
}