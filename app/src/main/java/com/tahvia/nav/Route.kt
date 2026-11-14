package com.tahvia.nav

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.ceil

class NoRouteException : Exception("No route")
class UnsupportedExcludeException : Exception("Exclude combination not supported by this server")

class Step(
    val type: String,
    val modifier: String?,
    val name: String,
    val pointIdx: Int,
    val along: Double,
) {
    val icon: Int = Maneuvers.icon(type, modifier)
    val street: String = Maneuvers.ascii(name)
}

/** A simplified route: points, cumulative along-route distance (m) and steps anchored to point indices. */
class Route(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cum: DoubleArray,
    val steps: List<Step>,
    val durationS: Double,
    val snapDestM: Double,
    /** Speed limit in km/h for the segment starting at point i (0 = unknown). Null = no data. */
    val limits: ByteArray? = null,
) {
    val n get() = lat.size
    val totalM get() = cum[n - 1]
}

object Maneuvers {
    /** README 5.4. */
    fun icon(type: String, modifier: String?): Int {
        if (type == "arrive") return 10
        when (type) {
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> return 9
        }
        return when (modifier) {
            "uturn" -> 8
            "slight left" -> 4
            "slight right" -> 5
            "sharp left" -> 6
            "sharp right" -> 7
            "left" -> 2
            "right" -> 3
            "straight" -> 1
            else -> if (type == "depart") 1 else 0
        }
    }

    /** README 5.4: device font is ASCII only. Transliterate and cut to 40 bytes. */
    fun ascii(s: String, maxBytes: Int = Proto.MAX_STREET_BYTES): String {
        var t = s.replace("ß", "ss").replace("Æ", "AE").replace("æ", "ae")
            .replace("Ø", "O").replace("ø", "o").replace("Đ", "D").replace("đ", "d")
            .replace("Ł", "L").replace("ł", "l").replace("Œ", "OE").replace("œ", "oe")
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder()
        for (c in t) sb.append(if (c.code in 32..126) c else '?')
        return sb.toString().take(maxBytes)
    }
}

object RouteParser {
    private const val SIMPLIFY_TOL_M = 1.5
    private const val MAX_SEG_M = 3000.0

    fun parse(json: String, limitOf: ((Long) -> Int)? = null): Route {
        val root = JSONObject(json)
        when (val code = root.optString("code")) {
            "Ok" -> {}
            "NoRoute", "NoSegment" -> throw NoRouteException()
            "InvalidValue" -> if (root.optString("message").contains("xclude", true)) throw UnsupportedExcludeException()
            else throw IllegalStateException("OSRM: $code ${root.optString("message")}")
            else -> throw IllegalStateException("OSRM: $code ${root.optString("message")}")
        }
        val rt = root.getJSONArray("routes").getJSONObject(0)
        val (fl, fo) = Polyline.decode6(rt.getString("geometry"))
        val nFull = fl.size
        if (nFull < 2) throw IllegalStateException("Route too short")

        // ---- steps ----
        class Raw(val type: String, val mod: String?, val name: String, val lat: Double, val lon: Double)
        val raws = ArrayList<Raw>()
        val legs = rt.getJSONArray("legs")
        val nodeIds = ArrayList<Long>()
        var nodesOk = limitOf != null
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            // annotation.nodes = OSM node id of every geometry vertex of the leg; the first vertex of a later leg repeats the last of the previous one
            val nd = leg.optJSONObject("annotation")?.optJSONArray("nodes")
            if (nd == null) nodesOk = false else for (q in (if (li == 0) 0 else 1) until nd.length()) nodeIds.add(nd.getLong(q))
            val steps = leg.getJSONArray("steps")
            for (si in 0 until steps.length()) {
                val s = steps.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                // the end of every leg except the last is a stop on the way, not the destination
                val isVia = m.getString("type") == "arrive" && li < legs.length() - 1
                raws.add(Raw(if (isVia) "via" else m.getString("type"),
                    if (isVia) "straight" else m.optString("modifier").ifEmpty { null },
                    if (isVia) "Waypoint" else s.optString("name"), loc.getDouble(1), loc.getDouble(0)))
            }
        }

        // ---- locate each step on the full-resolution geometry ----
        val stepIdx = IntArray(raws.size)
        var prev = 0
        val cosL = Math.cos(Math.toRadians(fl[0]))
        for (k in raws.indices) {
            val idx = when {
                k == 0 -> 0
                k == raws.lastIndex -> nFull - 1
                else -> {
                    var best = prev
                    var bd = Double.MAX_VALUE
                    var i = prev
                    while (i < nFull) {
                        val dx = (fo[i] - raws[k].lon) * cosL * Geo.M_PER_DEG
                        val dy = (fl[i] - raws[k].lat) * Geo.M_PER_DEG
                        val d = dx * dx + dy * dy
                        if (d < bd) { bd = d; best = i }
                        if (d < 0.25) { best = i; break }
                        i++
                    }
                    best
                }
            }
            stepIdx[k] = idx
            prev = idx
        }

        // ---- Douglas-Peucker (steps pinned), then split long segments ----
        val keep = Polyline.simplify(fl, fo, stepIdx, SIMPLIFY_TOL_M)
        val oLat = ArrayList<Double>()
        val oLon = ArrayList<Double>()
        val newIdx = IntArray(nFull) { -1 }
        var last = -1
        val kept = ArrayList<Int>()
        for (i in 0 until nFull) {
            if (!keep[i]) continue
            kept.add(i)
            if (last >= 0) {
                val d = Geo.distance(fl[last], fo[last], fl[i], fo[i])
                if (d > MAX_SEG_M) {
                    val parts = ceil(d / MAX_SEG_M).toInt()
                    for (p in 1 until parts) {
                        val t = p.toDouble() / parts
                        oLat.add(fl[last] + t * (fl[i] - fl[last]))
                        oLon.add(fo[last] + t * (fo[i] - fo[last]))
                    }
                }
            }
            newIdx[i] = oLat.size
            oLat.add(fl[i]); oLon.add(fo[i])
            last = i
        }
        val lat = oLat.toDoubleArray()
        val lon = oLon.toDoubleArray()
        if (lat.size < 2) throw IllegalStateException("Route too short")

        val cum = DoubleArray(lat.size)
        for (i in 1 until lat.size) cum[i] = cum[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i])

        val steps = raws.indices.map { k ->
            val pi = newIdx[stepIdx[k]]
            Step(raws[k].type, raws[k].mod, raws[k].name, pi, cum[pi])
        }
        val wps = root.optJSONArray("waypoints")
        val snap = wps?.optJSONObject(wps.length() - 1)?.optDouble("distance", 0.0) ?: 0.0
        // ---- speed limit per simplified segment: most common known limit among the full-resolution vertices it spans ----
        var limits: ByteArray? = null
        if (nodesOk && limitOf != null && nodeIds.size == nFull && kept.size >= 2) {
            val vl = IntArray(nFull) { limitOf(nodeIds[it]) }
            val out = ByteArray(lat.size)
            for (q in 0 until kept.size - 1) {
                val a = kept[q]
                val b = kept[q + 1]
                val seen = HashMap<Int, Int>()
                for (j in a until b) if (vl[j] > 0) seen[vl[j]] = (seen[vl[j]] ?: 0) + 1
                val v = seen.maxByOrNull { it.value }?.key ?: vl[b]
                for (idx in newIdx[a] until newIdx[b]) out[idx] = v.toByte()
            }
            out[out.size - 1] = out[maxOf(0, out.size - 2)]
            limits = out
        }
        return Route(lat, lon, cum, steps, rt.optDouble("duration", 0.0), snap, limits)
    }
}