package com.cubicl.cubinavconnect

import kotlin.math.roundToInt

/** Speed limit as shown to the user: km/h, or mph when [imperial]. */
fun limitValue(limitKmh: Int, imperial: Boolean = false): Int =
    if (imperial) (limitKmh / 1.609344).roundToInt() else limitKmh