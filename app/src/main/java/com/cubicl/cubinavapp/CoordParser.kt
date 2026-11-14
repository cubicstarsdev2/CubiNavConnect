package com.cubicl.cubinavapp

sealed class CoordResult {
    data class Ok(val lat: Double, val lon: Double) : CoordResult()
    /** |lat| > 90 but |lon| <= 90: probably swapped. Ask the user (README 3.1). */
    data class Swapped(val lat: Double, val lon: Double) : CoordResult()
    object Invalid : CoordResult()
    object NotCoords : CoordResult()
}

/** README 3.1: decimal degrees, hemisphere letters, degrees/minutes/seconds. Lat first. */
object CoordParser {
    private const val NUM = "[+-]?\\d+(?:\\.\\d+)?"
    private val dec = Regex("^\\s*($NUM)\\s*[,;\\s]\\s*($NUM)\\s*$")
    private val comp = Regex(
        "(\\d+(?:\\.\\d+)?)\\s*°?\\s*" +
            "(?:(\\d+(?:\\.\\d+)?)\\s*['′’]\\s*)?" +
            "(?:(\\d+(?:\\.\\d+)?)\\s*(?:\"|″|”|''|’’)\\s*)?" +
            "([NSEWOnsewo])"
    )

    fun parse(text: String): CoordResult {
        dec.matchEntire(text)?.let { m ->
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDouble()
            return check(a, b)
        }
        val ms = comp.findAll(text).toList()
        if (ms.size != 2) return CoordResult.NotCoords
        val rest = comp.replace(text, "").replace(Regex("[,;\\s]"), "")
        if (rest.isNotEmpty()) return CoordResult.NotCoords
        var lat: Double? = null
        var lon: Double? = null
        for (m in ms) {
            val deg = m.groupValues[1].toDouble()
            val min = m.groupValues[2].ifEmpty { "0" }.toDouble()
            val sec = m.groupValues[3].ifEmpty { "0" }.toDouble()
            var v = deg + min / 60.0 + sec / 3600.0
            when (m.groupValues[4].uppercase()) {
                "N" -> if (lat == null) lat = v else return CoordResult.NotCoords
                "S" -> if (lat == null) lat = -v else return CoordResult.NotCoords
                "E" -> if (lon == null) lon = v else return CoordResult.NotCoords
                "W", "O" -> if (lon == null) lon = -v else return CoordResult.NotCoords
            }
        }
        if (lat == null || lon == null) return CoordResult.NotCoords
        return check(lat, lon)
    }

    private fun check(lat: Double, lon: Double): CoordResult = when {
        Math.abs(lat) <= 90 && Math.abs(lon) <= 180 -> CoordResult.Ok(lat, lon)
        Math.abs(lat) > 90 && Math.abs(lon) <= 90 && Math.abs(lat) <= 180 -> CoordResult.Swapped(lon, lat)
        else -> CoordResult.Invalid
    }
}
