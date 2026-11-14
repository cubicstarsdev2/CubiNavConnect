package com.cubicl.cubinavapp

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Speed limit table (res/raw/speed_arg.bin, magic "TVSL"):
 *   header 13 B: "TVSL" | version u8 (1) | recordCount u32 | 4 B reserved
 *   record  6 B: OSM node id (u40, little endian, sorted ascending) | speed km/h u8 (255 = none/unknown)
 * Looked up by binary search straight in the byte array (9 MB, read once).
 */
object SpeedDb {
    private const val HEADER = 13
    private const val REC = 6

    @Volatile private var data: ByteArray? = null
    private var count = 0
    private val mutex = Mutex()

    val available: Boolean get() = data != null

    suspend fun ensureLoaded(ctx: Context) {
        if (data != null) return
        mutex.withLock {
            if (data != null) return
            withContext(Dispatchers.IO) {
                try {
                    val b = ctx.resources.openRawResource(R.raw.speed_arg).use { it.readBytes() }
                    val ok = b.size >= HEADER && b[0] == 'T'.code.toByte() && b[1] == 'V'.code.toByte() &&
                            b[2] == 'S'.code.toByte() && b[3] == 'L'.code.toByte() && b[4].toInt() == 1
                    val n = if (ok) ((b[5].toLong() and 0xFF) or ((b[6].toLong() and 0xFF) shl 8) or
                            ((b[7].toLong() and 0xFF) shl 16) or ((b[8].toLong() and 0xFF) shl 24)).toInt() else -1
                    if (n < 0 || b.size.toLong() != HEADER + n.toLong() * REC) {
                        Log.e("SpeedDb", "bad speed table (size ${b.size}, count $n)")
                    } else {
                        count = n
                        data = b
                        Log.i("SpeedDb", "loaded $n nodes")
                    }
                } catch (e: Exception) {
                    Log.e("SpeedDb", "cannot load speed table: $e")
                }
            }
        }
    }

    private fun key(b: ByteArray, i: Int): Long {
        val o = HEADER + i * REC
        return (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24) or
                ((b[o + 4].toLong() and 0xFF) shl 32)
    }

    /** Limit in km/h for an OSM node id, 0 = unknown / no limit. */
    fun lookup(nodeId: Long): Int {
        val b = data ?: return 0
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = key(b, mid)
            if (k < nodeId) lo = mid + 1
            else if (k > nodeId) hi = mid - 1
            else {
                val v = b[HEADER + mid * REC + 5].toInt() and 0xFF
                return if (v in 1..250) v else 0
            }
        }
        return 0
    }
}

/** European style sign: white disc, red ring. [kmh] is converted for imperial users (rounded to 5 mph). */
@Composable
fun SpeedSign(kmh: Int, imperial: Boolean, modifier: Modifier = Modifier) {
    val v = if (imperial) ((kmh / 1.609344) / 5.0).roundToInt() * 5 else kmh
    Box(
        modifier.size(60.dp).shadow(6.dp, CircleShape).background(Color.White, CircleShape)
            .border(6.dp, Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(v.toString(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = if (v >= 100) 20.sp else 24.sp)
    }
}