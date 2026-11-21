package com.cubicl.cubinavconnect

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/**
 * OTA update (see OTA protocol addendum): read the latest version from the README ("## x.y.z"), download the raw
 * .bin, stream it to the device over BLE with a sliding window, then confirm the new version after the reboot.
 */

enum class OtaPhase { IDLE, CHECKING, AVAILABLE, UP_TO_DATE, DOWNLOADING, TRANSFERRING, REBOOTING, DONE, ERROR }

/** The image being installed. */
class OtaManifest(
    val version: String,
    val versionCode: Int,
    val size: Long,
    val md5: String,
)

data class OtaUi(
    val phase: OtaPhase = OtaPhase.IDLE,
    val message: String = "",
    val progress: Float = 0f,          // 0..1, or -1 = indeterminate
    val manifest: OtaManifest? = null,
)

/** One firmware transfer. Event driven: acks and write-idle callbacks call [onAck] / [pumpNow]; a ticker handles timeouts. */
class OtaTransfer(
    private val image: ByteArray,
    private val md5: ByteArray,
    private val version: Int,
    private val send: (ByteArray) -> Unit,
    private val queued: () -> Int,
    private val mtu: () -> Int,
    private val clearQueue: () -> Unit,
    private val linkUp: () -> Boolean,
    private val log: (String) -> Unit,
    private val onProgress: (Long) -> Unit,
) {
    companion object {
        const val NO_SUPPORT = -1
        const val LINK_LOST = -2
        const val NO_REPLY = -3
        const val CANCELLED = -4
        const val LINK_DROP_AT_END = -5      // link died after OTA_END: the device may have rebooted into the new image
        private const val MAX_QUEUE = 6      // writes allowed to wait in BleLink
        private const val STALL_RESEND_MS = 1500L    // data outstanding but the acked byte did not move: resend from it
        private const val NO_PROGRESS_MS = 12_000L   // give up (the device's own idle timeout is 15 s)
    }

    private enum class S { BEGIN, SENDING, END, FINISHED }

    /** Completes with Proto.OTA_OK (device confirmed DONE) or an OTA_E_* / negative code above. */
    val result = CompletableDeferred<Int>()

    private val size = image.size.toLong()
    private var s = S.BEGIN
    private var window = 16
    private var chunk = Proto.OTA_MAX_DATA
    private var sent = 0L
    private var acked = 0L
    private var lastRx = 0L
    private var endSentAt = 0L
    private var lastEndSend = 0L
    private var lastRewind = -1L
    private var lastRewindAt = 0L
    private var lastProgressAt = 0L      // last time the acked byte moved (idle re-ACKs must not count as progress)
    private var lastResendAt = 0L

    private fun now() = SystemClock.elapsedRealtime()

    fun start(scope: CoroutineScope) {
        lastRx = now()
        lastProgressAt = lastRx
        log("OTA: -> BEGIN size=$size version=0x${version.toString(16)} mtu=${mtu()} queued=${queued()} link=${linkUp()}")
        send(Proto.otaBegin(size, md5, version))
        scope.launch(Dispatchers.Main) {      // same thread as BleLink callbacks / onAck: no data races on sent/acked
            while (s != S.FINISHED) {
                delay(250)
                tick()
            }
        }
    }

    fun cancel() {
        if (s == S.FINISHED) return
        send(Proto.otaAbort())
        finish(CANCELLED)
    }

    fun onAck(a: OtaAck) {
        if (s == S.FINISHED) return
        lastRx = now()
        val routine = s == S.SENDING && a.status == Proto.OTA_OK && a.state == Proto.OTA_ST_RECEIVING &&
                (a.next / 16384) == (acked / 16384)
        if (!routine) log("OTA: <- ACK status=${a.status} state=${a.state} next=${a.next} window=${a.window} every=${a.ackEvery} (phase $s)")
        when (s) {
            S.BEGIN -> {
                if (a.status != Proto.OTA_OK) { finish(a.status); return }
                window = a.window.coerceAtLeast(1)
                chunk = minOf(Proto.OTA_MAX_DATA, mtu() - 8)
                if (chunk < 20) {
                    send(Proto.otaAbort())
                    log("OTA: MTU too small (${mtu()})")
                    finish(Proto.OTA_E_ARGS)
                    return
                }
                log("OTA: accepted window=$window ackEvery=${a.ackEvery} chunk=$chunk")
                s = S.SENDING
                lastProgressAt = now()
                pump()
            }
            S.SENDING -> when (a.status) {
                Proto.OTA_OK -> {
                    if (a.next > acked) { acked = a.next; lastProgressAt = now() }
                    if (sent < acked) sent = acked            // the device is ahead of what we thought: skip forward
                    onProgress(acked)
                    if (acked >= size) sendEnd() else pump()
                }
                Proto.OTA_E_GAP -> rewind(a.next)
                else -> finish(a.status)
            }
            S.END -> when {
                a.status == Proto.OTA_OK && a.state == Proto.OTA_ST_DONE -> { onProgress(size); finish(Proto.OTA_OK) }
                a.status == Proto.OTA_OK -> {}                       // VERIFYING: keep waiting
                a.status == Proto.OTA_E_GAP && a.next < size -> { s = S.SENDING; rewind(a.next) }
                else -> finish(a.status)
            }
            S.FINISHED -> {}
        }
    }

    /** Called when BleLink has drained a write. */
    fun pumpNow() = pump()

    private fun rewind(next: Long) {
        val t = now()
        // chunks already in flight make the device answer E_GAP again; ignore the repeats
        if (next == lastRewind && t - lastRewindAt < 600) return
        lastRewind = next
        lastRewindAt = t
        log("OTA: gap, rewind to $next")
        if (next > acked) lastProgressAt = t
        resendFrom(next)
    }

    private fun resendFrom(pos: Long) {
        clearQueue()
        sent = pos
        acked = pos
        pump()
    }

    private fun pump() {
        if (s != S.SENDING) return
        while (sent < size && (sent - acked) < window.toLong() * chunk && queued() < MAX_QUEUE) {
            val n = minOf(chunk.toLong(), size - sent).toInt()
            send(Proto.otaChunk(sent, image, sent.toInt(), n))
            sent += n
        }
    }

    private fun sendEnd() {
        s = S.END
        endSentAt = now()
        lastEndSend = endSentAt
        log("OTA: END")
        send(Proto.otaEnd())
    }

    private fun tick() {
        val t = now()
        if (s == S.FINISHED) return
        if (!linkUp()) {
            finish(if (s == S.END) LINK_DROP_AT_END else LINK_LOST)
            return
        }
        when (s) {
            S.BEGIN -> if (t - lastRx > 3000) finish(NO_SUPPORT)
            S.SENDING -> {
                pump()
                val idle = t - lastProgressAt
                // An ACK (or the chunk that triggers it) got lost: the device ignores everything out of order and
                // only re-ACKs now and then, so go back to the last acked byte instead of waiting forever.
                if (sent > acked && idle > STALL_RESEND_MS && t - lastResendAt > STALL_RESEND_MS) {
                    lastResendAt = t
                    log("OTA: stalled ${idle} ms (sent=$sent acked=$acked queued=${queued()}), resending from $acked")
                    resendFrom(acked)
                }
                if (idle > NO_PROGRESS_MS) {
                    log("OTA: no progress for ${idle} ms (sent=$sent acked=$acked queued=${queued()})")
                    send(Proto.otaAbort())
                    finish(NO_REPLY)
                }
            }
            S.END -> {
                if (t - endSentAt >= 10_000) finish(NO_REPLY)
                else if (t - lastEndSend >= 2000) { lastEndSend = t; send(Proto.otaEnd()) }   // idempotent
            }
            S.FINISHED -> {}
        }
    }

    private fun finish(code: Int) {
        if (s == S.FINISHED) return
        log("OTA: transfer finished code=$code (was $s, sent=$sent acked=$acked of $size)")
        s = S.FINISHED
        result.complete(code)
    }
}

object Ota {
    private val _ui = MutableStateFlow(OtaUi())
    val ui = _ui.asStateFlow()

    private var job: Job? = null
    private var transfer: OtaTransfer? = null

    private val BUSY = setOf(OtaPhase.CHECKING, OtaPhase.DOWNLOADING, OtaPhase.TRANSFERRING, OtaPhase.REBOOTING)
    val busy: Boolean get() = _ui.value.phase in BUSY

    private val scope get() = NavController.scope
    private const val TAG = "CubiNavOta"
    private var t0 = SystemClock.elapsedRealtime()
    private val _trace = MutableStateFlow<List<String>>(emptyList())
    /** Last lines of the current/most recent update, shown under the update button. */
    val trace = _trace.asStateFlow()

    private fun log(s: String) {
        Log.d(TAG, s)
        NavController.onLog(s)
        val line = String.format(java.util.Locale.US, "+%.1fs %s", (SystemClock.elapsedRealtime() - t0) / 1000.0, s)
        _trace.value = (_trace.value + line).takeLast(60)
    }

    private fun startTrace(title: String) {
        t0 = SystemClock.elapsedRealtime()
        _trace.value = emptyList()
        log("OTA: === $title ===")
    }

    private fun exText(e: Throwable): String {
        if (e is Net.HttpException && (e.code == 401 || e.code == 404)) {
            return L.s(R.string.ota_err_http_notoken, e.code)
        }
        return "${e.javaClass.simpleName}: ${e.message ?: L.s(R.string.ota_no_message)}"
    }
    private fun ua() = NavController.prefs.settings.value.userAgent

    /**
     * Device version code <-> "major.minor.patch": (major shl 8) or (minor shl 4) or patch
     * (minor and patch 0..15). 1.0.0 == 0x0100, so firmware that reports 0x0100 keeps working.
     * Set FW_VERSION in firmware/protocol.h to the same code on every release (1.2.3 -> 0x0123).
     */
    fun fmtVersion(code: Int): String {
        val n = (code shr 16) and 0x1F            // optional trailing letter: 1 = A ... 26 = Z, 0 = none
        val letter = if (n in 1..26) ('A' + (n - 1)).toString() else ""
        return "${(code shr 8) and 0xFF}.${(code shr 4) and 0xF}.${code and 0xF}$letter"
    }

    private val README_VERSION = Regex("""^##\s*v?(\d+)\.(\d+)\.(\d+)([A-Za-z]?)\s*$""")

    /**
     * First README line of the form "## 1.2.3" or "## 1.2.3R" ("##1.2.3" also works) as a device version code, or null.
     * A trailing letter (case-insensitive) is stored in bits 16..20: 1.0.0R == 0x120100.
     */
    fun parseReadmeVersion(text: String): Int? {
        for (line in text.removePrefix("\uFEFF").lineSequence()) {
            val m = README_VERSION.matchEntire(line.trim()) ?: continue
            val (a, b, c, l) = m.destructured
            val major = a.toIntOrNull() ?: return null
            val minor = b.toIntOrNull() ?: return null
            val patch = c.toIntOrNull() ?: return null
            if (major > 255 || minor > 15 || patch > 15) return null
            val letter = if (l.isEmpty()) 0 else l[0].uppercaseChar() - 'A' + 1
            return (letter shl 16) or (major shl 8) or (minor shl 4) or patch
        }
        return null
    }

    // ---- called from NavController ------------------------------------------------------------------
    fun onAck(a: OtaAck) {
        transfer?.onAck(a)
    }

    // ---- public actions -----------------------------------------------------------------------------
    /**
     * Installs the .bin behind a direct link (e.g. raw.githubusercontent.com/<user>/<repo>/<branch>/firmware.bin).
     * Size and MD5 are computed from the downloaded bytes and the version comes from
     * the image's app descriptor when it looks like "major.minor".
     */
    fun installDirect() {
        if (busy) return
        startTrace("force install .bin")
        log("OTA: device fw=${NavController.deviceFw.value} ble=${NavController.bleState.value} mtu=${NavController.link().mtu}")
        precondition()?.let { fail(it); return }
        val url = sanitizeFirmwareUrl(NavController.prefs.settings.value.binUrl)
        if (url.isEmpty()) { fail(L.s(R.string.ota_set_bin_url)); return }
        job = scope.launch {
            try {
                run(downloadDirect(url)) { downloadDirect(url) }
            } catch (e: CancellationException) {
                transfer?.cancel()
                set(OtaPhase.ERROR, L.s(R.string.ota_cancelled), 0f, null)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "update failed", e)
                fail(exText(e), null)
            } finally {
                NavController.link().onWriteIdle = null
                transfer = null
            }
        }
    }

    /**
     * README-driven update: read "## x.y.z" from the README, compare with the version the device reported in
     * HELLO_ACK and, if they differ, download the .bin, chunk it and run the OTA. After the reboot the device
     * must report the README version, otherwise the result is an error (no automatic retry loop).
     */
    fun updateFromReadme() {
        if (busy) return
        startTrace("update from README")
        val st = NavController.prefs.settings.value
        val readme = sanitizeFirmwareUrl(st.readmeUrl)
        val bin = sanitizeFirmwareUrl(st.binUrl)
        val fw = NavController.deviceFw.value
        log("OTA: device fw=${if (fw >= 0) "${fmtVersion(fw)} (0x${fw.toString(16)})" else "unknown"} " +
                "ble=${NavController.bleState.value} mtu=${NavController.link().mtu} nav=${NavController.phase.value}")
        log("OTA: readme=$readme")
        log("OTA: bin=$bin")
        precondition()?.let { fail(it); return }
        if (readme.isEmpty() || bin.isEmpty()) { fail(L.s(R.string.ota_set_urls)); return }
        job = scope.launch {
            try {
                set(OtaPhase.CHECKING, L.s(R.string.ota_reading_readme), -1f, null)
                log("OTA: GET README")
                val text = Net.get(bust(readme), ua())
                log("OTA: README ${text.length} chars; first lines: " +
                        text.lineSequence().take(4).joinToString(" | ") { it.trim().take(50) })
                val remote = parseReadmeVersion(text)
                    ?: throw IllegalStateException(L.s(R.string.ota_no_version_line))
                log("OTA: README version ${fmtVersion(remote)} (0x${remote.toString(16)})")
                if (remote == fw) {
                    log("OTA: versions match, nothing to do")
                    set(OtaPhase.UP_TO_DATE, L.s(R.string.ota_up_to_date, fmtVersion(fw)), 0f, null)
                    return@launch
                }
                val label = fmtVersion(remote)
                log("OTA: mismatch: device ${fmtVersion(fw)} != README $label -> updating")
                run(downloadDirect(bin, label, remote)) { downloadDirect(bin, label, remote) }
            } catch (e: CancellationException) {
                log("OTA: cancelled")
                transfer?.cancel()
                set(OtaPhase.ERROR, L.s(R.string.ota_cancelled), 0f, null)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "update failed", e)
                fail(exText(e), null)
            } finally {
                NavController.link().onWriteIdle = null
                transfer = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!busy) _ui.value = OtaUi()
    }

    // ---- internals ------------------------------------------------------------------------------------
    private fun precondition(): String? {
        if (NavController.bleState.value != BleState.CONNECTED || NavController.deviceFw.value < 0)
            return L.s(R.string.ota_connect_first)
        if (NavController.phase.value != Phase.IDLE) return L.s(R.string.ota_stop_nav)
        return null
    }

    private fun set(p: OtaPhase, msg: String, progress: Float = 0f, m: OtaManifest? = _ui.value.manifest) {
        _ui.value = OtaUi(p, msg, progress, m)
    }

    private fun fail(msg: String, m: OtaManifest? = _ui.value.manifest) {
        log("OTA: $msg")
        set(OtaPhase.ERROR, msg, 0f, m)
    }

    private fun bust(url: String): String =
        url.toHttpUrl().newBuilder().addQueryParameter("t", (System.currentTimeMillis() / 1000).toString()).build().toString()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun digest(alg: String, b: ByteArray) = hex(MessageDigest.getInstance(alg).digest(b))

    private suspend fun transferOnce(m: OtaManifest, image: ByteArray): Int {
        val link = NavController.link()
        set(OtaPhase.TRANSFERRING, L.s(R.string.ota_starting), 0f, m)
        log("OTA: starting transfer, ${image.size} B, version 0x${m.versionCode.toString(16)}, md5 ${m.md5}, link=${link.state.value}")
        link.requestFastLink()
        val t = OtaTransfer(
            image = image, md5 = unhex(m.md5), version = m.versionCode.coerceAtLeast(0),
            send = { link.send(it) },
            queued = { link.queued() },
            mtu = { link.mtu },
            clearQueue = { link.clearQueue() },
            linkUp = { link.state.value == BleState.CONNECTED },
            log = { log(it) },
            onProgress = { done ->
                set(OtaPhase.TRANSFERRING, L.s(R.string.ota_sending, done / 1024, image.size / 1024),
                    (done.toFloat() / image.size).coerceIn(0f, 1f), m)
            },
        )
        transfer = t
        link.onWriteIdle = { t.pumpNow() }
        try {
            t.start(scope)
            return t.result.await()
        } finally {
            link.onWriteIdle = null
            if (!t.result.isCompleted) t.cancel()
            transfer = null
        }
    }

    private const val MAX_DIRECT_BYTES = 4L * 1024 * 1024

    /** Version string from esp_app_desc_t (image header 24 B + first segment header 8 B, then magic 0xABCD5432). */
    private fun appDescVersion(b: ByteArray): String? {
        if (b.size < 80) return null
        val magic = (b[32].toInt() and 0xFF) or ((b[33].toInt() and 0xFF) shl 8) or
                ((b[34].toInt() and 0xFF) shl 16) or ((b[35].toInt() and 0xFF) shl 24)
        if (magic != 0xABCD5432.toInt()) return null
        var end = 48
        while (end < 80 && b[end].toInt() != 0) end++
        return String(b, 48, end - 48, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
    }

    private fun versionCodeOf(v: String): Int {
        val m = Regex("^v?(\\d+)\\.(\\d+)").find(v) ?: return -1
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return if (major < 256 && minor < 256) (major shl 8) or minor else -1
    }

    /** [version]/[code] override the version that would be read from the image (used by the README flow). */
    private suspend fun downloadDirect(url: String, version: String? = null, code: Int = -1): Pair<OtaManifest, ByteArray> {
        set(OtaPhase.DOWNLOADING, L.s(R.string.ota_downloading), -1f, null)
        log("OTA: GET $url")
        val dl0 = SystemClock.elapsedRealtime()
        val bytes = Net.getBytes(bust(url), ua(), MAX_DIRECT_BYTES) { n, total ->
            set(OtaPhase.DOWNLOADING, L.s(R.string.ota_downloading_kb, n / 1024),
                if (total > 0) (n.toFloat() / total).coerceIn(0f, 1f) else -1f, null)
        }
        log("OTA: downloaded ${bytes.size} B in ${SystemClock.elapsedRealtime() - dl0} ms, " +
                "first byte " + (if (bytes.isNotEmpty()) "0x%02X".format(bytes[0]) else "-"))
        if (bytes.size < 1024) throw IllegalStateException(L.s(R.string.ota_too_small))
        if (bytes[0] != 0xE9.toByte())
            throw IllegalStateException(L.s(R.string.ota_not_image))
        set(OtaPhase.DOWNLOADING, L.s(R.string.ota_verifying), -1f, null)
        val md5 = withContext(Dispatchers.Default) { digest("MD5", bytes) }
        val ver = version ?: appDescVersion(bytes)
        val m = OtaManifest(
            version = ver ?: "firmware.bin",
            versionCode = if (version != null) code else ver?.let { versionCodeOf(it) } ?: -1,
            size = bytes.size.toLong(), md5 = md5,
        )
        log("OTA: direct image ${bytes.size} B, version ${ver ?: "unknown"}, md5 $md5")
        return m to bytes
    }

    private suspend fun run(first: Pair<OtaManifest, ByteArray>, reload: suspend () -> Pair<OtaManifest, ByteArray>) {
        var (m, image) = first
        var retried = false
        while (true) {
            val code = transferOnce(m, image)
            log("OTA: transfer result code=$code (${describe(code)})")
            if (code == Proto.OTA_OK || code == OtaTransfer.LINK_DROP_AT_END) { awaitReboot(m); return }
            val retry = !retried && (code == Proto.OTA_E_MD5 || code == Proto.OTA_E_STATE || code == Proto.OTA_E_TIMEOUT || code == OtaTransfer.NO_REPLY)
            if (!retry) { fail(describe(code), m); return }
            retried = true
            log("OTA: retrying once after code $code")
            NavController.link().send(Proto.otaAbort())
            delay(800)
            if (code == Proto.OTA_E_MD5) { val r = reload(); m = r.first; image = r.second }
        }
    }

    private suspend fun awaitReboot(m: OtaManifest) {
        set(OtaPhase.REBOOTING, L.s(R.string.ota_rebooting, m.version), -1f, m)
        NavController.resetDeviceFw()
        val fw = withTimeoutOrNull(60_000) { NavController.deviceFw.first { it >= 0 } }
        when {
            fw == null -> fail(L.s(R.string.ota_no_reconnect), m)
            m.versionCode < 0 -> {
                log("OTA: device back, reports ${fmtVersion(fw)}")
                set(OtaPhase.DONE, L.s(R.string.ota_back_unverified, fmtVersion(fw)), 1f, null)
            }
            fw == m.versionCode -> {
                log("OTA: device now reports ${fmtVersion(fw)}")
                set(OtaPhase.DONE, L.s(R.string.ota_updated, m.version), 1f, m)
            }
            else -> fail(L.s(R.string.ota_wrong_version, fmtVersion(fw), m.version), m)
        }
    }

    private fun describe(code: Int): String = when (code) {
        Proto.OTA_E_STATE -> L.s(R.string.ota_e_state)
        Proto.OTA_E_SIZE -> L.s(R.string.ota_e_size)
        Proto.OTA_E_FLASH -> L.s(R.string.ota_e_flash)
        Proto.OTA_E_MD5 -> L.s(R.string.ota_e_md5)
        Proto.OTA_E_BATTERY -> L.s(R.string.ota_e_battery)
        Proto.OTA_E_TIMEOUT -> L.s(R.string.ota_e_timeout)
        Proto.OTA_E_ARGS -> L.s(R.string.ota_e_args)
        Proto.OTA_E_ABORTED -> L.s(R.string.ota_e_aborted)
        Proto.OTA_E_NOT_SECURE -> L.s(R.string.ota_e_not_secure)
        Proto.OTA_E_IMAGE -> L.s(R.string.ota_e_image)
        OtaTransfer.NO_SUPPORT -> L.s(R.string.ota_e_no_support)
        OtaTransfer.LINK_LOST -> L.s(R.string.ota_e_link_lost)
        OtaTransfer.NO_REPLY -> L.s(R.string.ota_e_no_reply)
        OtaTransfer.CANCELLED -> L.s(R.string.ota_cancelled)
        else -> L.s(R.string.ota_e_generic, code)
    }
}