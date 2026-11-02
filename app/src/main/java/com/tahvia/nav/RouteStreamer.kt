package com.tahvia.nav

import kotlin.math.max

/**
 * README 6.6: streams a rolling window of the route (about 2 km ahead of the marker) into the
 * device ring buffer, respecting the free slots the device reports in ROUTE_ACK.
 *
 * Deltas are computed from rounded absolute projections (delta = round(P[i]) - round(P[i-1])), so the
 * device's accumulated points never drift from the phone's projection.
 */
class RouteStreamer(
    private val send: (ByteArray) -> Unit,
    private val projector: () -> Projector,
    private val chunkMax: () -> Int,
    private val log: (String) -> Unit,
) {
    companion object {
        const val WINDOW_M = 2000.0
        const val COMMIT_AFTER_M = 500.0
    }

    var maxPoints = 1024
    var route: Route? = null
        private set
    var routeId = 0
        private set
    var nextIdx = 0
        private set
    var free = 0
        private set
    var active = false
        private set
    private var startIdx = 0
    private var committed = false

    /** Begin uploading [r] to the device's shadow buffer, first point = index [startAt]. */
    fun start(r: Route, id: Int, startAt: Int) {
        route = r
        routeId = id
        startIdx = startAt.coerceIn(0, r.n - 1)
        nextIdx = startIdx + 1
        free = maxPoints - 1
        committed = false
        active = true
        val p = projector()
        send(Proto.routeBegin(id, startIdx, p.x(r.lon[startIdx]), p.y(r.lat[startIdx])))
    }

    fun stop() {
        active = false
        route = null
    }

    fun clearOnDevice() {
        if (active) send(Proto.routeClear(routeId))
        stop()
    }

    fun onAck(a: RouteAck) {
        if (!active || a.routeId != routeId) return
        when (a.status) {
            Proto.ACK_GAP -> {
                log("gap: resend from ${a.expectedIdx}")
                nextIdx = a.expectedIdx.coerceIn(startIdx + 1, route?.n ?: 0)
                free = a.freeSlots
            }
            Proto.ACK_FULL -> free = a.freeSlots
            Proto.ACK_OK -> free = max(0, a.freeSlots - max(0, nextIdx - a.expectedIdx))
            else -> log("ack unknown route")
        }
    }

    /** Send whatever fits in the window ahead of [alongM] and the device's free slots. */
    fun pump(alongM: Double) {
        val r = route ?: return
        if (!active) return
        val limit = alongM + WINDOW_M
        val p = projector()
        while (nextIdx < r.n && free > 0 && r.cum[nextIdx] <= limit) {
            val maxC = minOf(chunkMax(), free, r.n - nextIdx, Proto.MAX_CHUNK_POINTS)
            val d = IntArray(maxC * 2)
            var px = p.x(r.lon[nextIdx - 1])
            var py = p.y(r.lat[nextIdx - 1])
            var cnt = 0
            while (cnt < maxC && nextIdx + cnt < r.n && r.cum[nextIdx + cnt] <= limit) {
                val x = p.x(r.lon[nextIdx + cnt])
                val y = p.y(r.lat[nextIdx + cnt])
                d[2 * cnt] = x - px
                d[2 * cnt + 1] = y - py
                px = x; py = y
                cnt++
            }
            if (cnt == 0) break
            send(Proto.routeChunk(routeId, nextIdx, d, cnt))
            nextIdx += cnt
            free -= cnt
        }
        if (!committed) {
            val uploaded = r.cum[nextIdx - 1] - r.cum[startIdx]
            if (uploaded >= COMMIT_AFTER_M || nextIdx >= r.n) {
                send(Proto.routeCommit(routeId))
                committed = true
            }
        }
    }
}
