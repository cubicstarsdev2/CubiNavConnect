package com.cubicl.cubinavapp

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream

/**
 * Port of boot_alpha.h (png2frames.py --format alpha4), decoding straight to ARGB pixels.
 * Run = one byte [level:4 | length-1:4]; level 0 = transparent/white, 15 = solid black.
 * Same math as the firmware: upscale by [scale], Bayer threshold at OUTPUT resolution,
 * `level * DN2 > th * 15 + 7`.
 */
object BootAlpha {
    private fun srTable(dn: Int): IntArray {
        var k = 0
        while ((1 shl k) < dn) k++
        return IntArray(dn) { n ->
            var v = 0
            for (j in 0 until k) v = v or (((n shr (k - 1 - j)) and 1) shl (2 * j))
            v
        }
    }

    fun decodeArgb(
        rle: ByteArray, srcW: Int, srcH: Int, scale: Int, dn: Int, ds: Int,
        sr: IntArray, ink: Int, paper: Int, out: IntArray,
    ) {
        val dn2 = dn * dn
        val mask = dn - 1
        val outW = srcW * scale
        val line = IntArray(srcW)
        val thr = IntArray(dn)
        var p = 0
        var lvl = 0
        var left = 0
        for (sy in 0 until srcH) {
            for (sx in 0 until srcW) {
                if (left == 0) {
                    val b = if (p < rle.size) rle[p++].toInt() and 0xFF else 0
                    lvl = b ushr 4
                    left = (b and 15) + 1
                }
                line[sx] = lvl * dn2
                left--
            }
            for (r in 0 until scale) {
                val dy = sy * scale + r
                val y = (dy / ds) and mask
                val srY = sr[y]
                for (c in 0 until dn) thr[c] = (((sr[c xor y] shl 1) or srY) * 15) + 7   // threshold per dither cell
                var o = dy * outW
                var dx = 0
                for (sx in 0 until srcW) {
                    val v = line[sx]
                    for (k in 0 until scale) {
                        out[o++] = if (v > thr[(dx / ds) and mask]) ink else paper
                        dx++
                    }
                }
            }
        }
    }

    fun table(dn: Int) = srTable(dn)
}

/**
 * Animation data. Binary format (little endian), written by convert_boot_frames.py:
 *   u16 srcW, u16 srcH, u8 scale, u8 fps, u8 ditherSize, u8 ditherScale, u16 frameCount,
 *   then per frame: u32 byteLength + RLE bytes.
 */
class BootFrames(
    val srcW: Int, val srcH: Int, val scale: Int, val fps: Int,
    val ditherSize: Int, val ditherScale: Int,
    val frames: List<ByteArray>,
) {
    val outW get() = srcW * scale
    val outH get() = srcH * scale

    companion object {
        fun fromRaw(ctx: Context, resId: Int = R.raw.boot_frames): BootFrames? = try {
            DataInputStream(ctx.resources.openRawResource(resId).buffered()).use { d ->
                fun u8() = d.readUnsignedByte()
                fun u16() = u8() or (u8() shl 8)
                fun u32() = u16().toLong() or (u16().toLong() shl 16)
                val w = u16(); val h = u16(); val sc = u8(); val fps = u8()
                val dsz = u8(); val dsc = u8(); val n = u16()
                val list = List(n) { ByteArray(u32().toInt()).also { d.readFully(it) } }
                BootFrames(w, h, sc, fps, dsz, dsc, list)
            }
        } catch (e: Exception) {
            Log.e("BootAnim", "cannot load raw resource $resId: $e")
            null
        }
    }
}

/**
 * Plays the boot animation once, holds the last frame, then calls [onDone]. Tap skips.
 * Frames are decoded ahead on a background thread (small buffer) and shown on an absolute clock,
 * so a slow decode never slows playback down. The whole screen fades in over [fadeInMs];
 * the fade out to the app is done by the caller (see MainActivity).
 */
@Composable
fun BootAnimation(
    anim: BootFrames,
    ink: Color = Color.Black,
    paper: Color = Color.White,
    holdMs: Long = 400,
    fadeInMs: Int = 350,
    onDone: () -> Unit,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(fadeInMs)) }

    LaunchedEffect(anim) {
        val frameMs = 1000L / anim.fps.coerceAtLeast(1)
        val buf = Channel<ImageBitmap>(capacity = 8)
        val w = anim.outW
        val h = anim.outH
        launch(Dispatchers.Default) {
            val sr = BootAlpha.table(anim.ditherSize)
            val inkI = ink.toArgb()
            val paperI = paper.toArgb()
            for (f in anim.frames) {
                val px = IntArray(w * h)
                BootAlpha.decodeArgb(f, anim.srcW, anim.srcH, anim.scale, anim.ditherSize, anim.ditherScale, sr, inkI, paperI, px)
                buf.send(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap())
            }
            buf.close()
        }
        var start = 0L
        var i = 0
        for (bmp in buf) {
            val now = SystemClock.uptimeMillis()
            if (i == 0) start = now                       // clock starts when frame 0 is ready
            val due = start + i * frameMs
            if (now < due) delay(due - now)
            else if (now - due > 2 * frameMs) start = now - i * frameMs   // after a stall resume normally, no burst
            frame = bmp
            i++
        }
        delay(holdMs)
        onDone()
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(paper)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { img ->
            Canvas(Modifier.fillMaxWidth().aspectRatio(anim.outW.toFloat() / anim.outH)) {
                drawImage(
                    img,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None,   // keep the hard 1-bit pixels
                )
            }
        }
    }
}