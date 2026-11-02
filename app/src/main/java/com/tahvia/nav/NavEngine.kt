package com.tahvia.nav

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Match(
    val lat: Double,
    val lon: Double,
    val distM: Double,
    val seg: Int,
    val along: Double,
    val heading: Double,
)

/** README 7.2: project a fix onto the route polyline near the last matched segment. */
class NavEngine(val route: Route) {
    var lastSeg = 0
        private set

    fun match(lat: Double, lon: Double): Match {
        val n = route.n
        var from = max(0, lastSeg - 3)
        val to = min(n - 2, lastSeg + 120)
        if (from > to) from = to
        var best = search(lat, lon, from, to)
        if (best.distM > 100.0) {                     // lost or jumped: look at the whole route
            val g = search(lat, lon, 0, n - 2)
            if (g.distM < best.distM) best = g
        }
        lastSeg = best.seg
        return best
    }

    private fun search(lat: Double, lon: Double, from: Int, to: Int): Match {
        val r = route
        val cosL = cos(Math.toRadians(lat))
        val k = Geo.M_PER_DEG
        var bestD = Double.MAX_VALUE
        var bestSeg = from
        var bestT = 0.0
        for (i in from..to) {
            val ax = (r.lon[i] - lon) * cosL * k
            val ay = (r.lat[i] - lat) * k
            val bx = (r.lon[i + 1] - lon) * cosL * k
            val by = (r.lat[i + 1] - lat) * k
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) { bestD = d; bestSeg = i; bestT = t }
        }
        val i = bestSeg
        val sLat = r.lat[i] + bestT * (r.lat[i + 1] - r.lat[i])
        val sLon = r.lon[i] + bestT * (r.lon[i + 1] - r.lon[i])
        val along = r.cum[i] + bestT * (r.cum[i + 1] - r.cum[i])
        val hdg = Geo.bearing(r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1])
        return Match(sLat, sLon, bestD, i, along, hdg)
    }
}
