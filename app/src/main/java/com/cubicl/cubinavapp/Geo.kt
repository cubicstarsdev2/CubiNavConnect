package com.cubicl.cubinavapp

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val R = 6371000.0
    const val M_PER_DEG = R * PI / 180.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing in degrees [0,360), clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

/**
 * README 6.2: equirectangular projection into decimeters. x = east, y = north.
 * [cosRef] is fixed for the whole trip, so a REBASE is a pure translation and points already
 * stored on the device stay consistent with newly streamed ones.
 */
class Projector(
    val lat0: Double,
    val lon0: Double,
    val cosRef: Double = cos(Math.toRadians(lat0)),
) {
    fun x(lon: Double): Int = (Geo.R * cosRef * Math.toRadians(lon - lon0) * 10.0).roundToInt()
    fun y(lat: Double): Int = (Geo.R * Math.toRadians(lat - lat0) * 10.0).roundToInt()

    /** New origin = old origin translated by (dx, dy) decimeters (the same values sent in REBASE). */
    fun rebased(dx: Int, dy: Int) = Projector(
        lat0 + Math.toDegrees(dy / 10.0 / Geo.R),
        lon0 + Math.toDegrees(dx / 10.0 / (Geo.R * cosRef)),
        cosRef,
    )
}

object Polyline {
    /** Decodes a precision-6 encoded polyline into (lat[], lon[]). */
    fun decode6(s: String): Pair<DoubleArray, DoubleArray> {
        val la = DoubleArray(s.length / 2 + 1)
        val lo = DoubleArray(la.size)
        var i = 0
        var n = 0
        var lat = 0
        var lon = 0
        while (i < s.length) {
            var shift = 0
            var res = 0
            var b: Int
            do {
                b = s[i++].code - 63
                res = res or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((res and 1) != 0) (res shr 1).inv() else (res shr 1)
            shift = 0
            res = 0
            do {
                b = s[i++].code - 63
                res = res or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((res and 1) != 0) (res shr 1).inv() else (res shr 1)
            la[n] = lat / 1e6
            lo[n] = lon / 1e6
            n++
        }
        return la.copyOf(n) to lo.copyOf(n)
    }

    /**
     * Douglas-Peucker (iterative). Indices in [anchors] plus first/last are always kept, so step
     * maneuver points survive simplification. Returns the keep mask.
     */
    fun simplify(lat: DoubleArray, lon: DoubleArray, anchors: IntArray, tolM: Double): BooleanArray {
        val n = lat.size
        val keep = BooleanArray(n)
        if (n == 0) return keep
        val cosL = cos(Math.toRadians(lat[0]))
        val x = DoubleArray(n) { (lon[it] - lon[0]) * cosL * Geo.M_PER_DEG }
        val y = DoubleArray(n) { (lat[it] - lat[0]) * Geo.M_PER_DEG }
        val fixed = java.util.TreeSet<Int>()
        fixed.add(0); fixed.add(n - 1)
        for (a in anchors) if (a in 0 until n) fixed.add(a)
        for (a in fixed) keep[a] = true

        val stack = ArrayDeque<IntArray>()
        val it = fixed.iterator()
        var prev = it.next()
        while (it.hasNext()) {
            val cur = it.next()
            stack.push(intArrayOf(prev, cur))
            while (stack.isNotEmpty()) {
                val (a, b) = stack.pop()
                if (b <= a + 1) continue
                var best = -1
                var bestD = tolM
                for (i in a + 1 until b) {
                    val d = segDist(x[i], y[i], x[a], y[a], x[b], y[b])
                    if (d > bestD) { bestD = d; best = i }
                }
                if (best >= 0) {
                    keep[best] = true
                    stack.push(intArrayOf(a, best))
                    stack.push(intArrayOf(best, b))
                }
            }
            prev = cur
        }
        return keep
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }
}
