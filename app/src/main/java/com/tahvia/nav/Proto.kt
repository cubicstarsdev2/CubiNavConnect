package com.tahvia.nav

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HelloAck(val proto: Int, val fw: Int, val screenW: Int, val screenH: Int, val maxPoints: Int)
data class RouteAck(val routeId: Int, val status: Int, val expectedIdx: Int, val freeSlots: Int)
data class OtaAck(val status: Int, val state: Int, val next: Long, val window: Int, val ackEvery: Int)

/** BLE protocol v1 (README section 6). All integers little-endian. Mirrors firmware/protocol.h. */
object Proto {
    const val VERSION = 1

    const val HELLO = 0x01
    const val REBASE = 0x03
    const val POSE = 0x10
    const val ROUTE_BEGIN = 0x20
    const val ROUTE_CHUNK = 0x21
    const val ROUTE_COMMIT = 0x22
    const val ROUTE_CLEAR = 0x23
    const val MANEUVER = 0x30
    const val CONFIG = 0x40
    const val NAV_STATE = 0x50
    const val OTA_BEGIN = 0x60
    const val OTA_CHUNK = 0x61
    const val OTA_END = 0x62
    const val OTA_ABORT = 0x63
    const val HELLO_ACK = 0x81
    const val ROUTE_ACK = 0x82
    const val OTA_ACK = 0x83

    // OTA_ACK status codes
    const val OTA_OK = 0
    const val OTA_E_STATE = 1
    const val OTA_E_SIZE = 2
    const val OTA_E_GAP = 3
    const val OTA_E_FLASH = 4
    const val OTA_E_MD5 = 5
    const val OTA_E_BATTERY = 6
    const val OTA_E_TIMEOUT = 7
    const val OTA_E_ARGS = 8
    const val OTA_E_ABORTED = 9
    const val OTA_E_NOT_SECURE = 10
    const val OTA_E_IMAGE = 11

    // OTA_ACK device states
    const val OTA_ST_IDLE = 0
    const val OTA_ST_RECEIVING = 1
    const val OTA_ST_VERIFYING = 2
    const val OTA_ST_DONE = 3
    const val OTA_ST_ERROR = 4

    const val OTA_MAX_DATA = 239

    const val ACK_OK = 0
    const val ACK_GAP = 1
    const val ACK_FULL = 2
    const val ACK_UNKNOWN = 3

    const val NAV_IDLE = 0
    const val NAV_NAVIGATING = 1
    const val NAV_REROUTING = 2
    const val NAV_ARRIVED = 3
    const val NAV_NO_GPS = 4

    const val CFG_HEADING_UP = 1
    const val CFG_INVERT = 2
    const val CFG_SHOW_FPS = 4
    const val CFG_IMPERIAL = 8

    const val MAX_CHUNK_POINTS = 59
    const val MAX_STREET_BYTES = 40

    private fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun u8(v: Int) = (v and 0xFF).toByte()
    private fun u16(v: Int) = (v and 0xFFFF).toShort()

    fun hello() = byteArrayOf(u8(HELLO), u8(VERSION))

    fun rebase(dx: Int, dy: Int): ByteArray =
        buf(9).put(u8(REBASE)).putInt(dx).putInt(dy).array()

    fun pose(x: Int, y: Int, headingDeci: Int, speedCms: Int, distToTurn: Int, valid: Boolean): ByteArray =
        buf(16).apply {
            put(u8(POSE)); putInt(x); putInt(y)
            putShort(u16(headingDeci.coerceIn(0, 3599)))
            putShort(u16(speedCms.coerceIn(0, 0xFFFF)))
            putShort(u16(distToTurn.coerceIn(0, 0xFFFF)))
            put(if (valid) 1 else 0)
        }.array()

    fun routeBegin(id: Int, startIdx: Int, x0: Int, y0: Int): ByteArray =
        buf(14).put(u8(ROUTE_BEGIN)).put(u8(id)).putInt(startIdx).putInt(x0).putInt(y0).array()

    /** [deltas] = dx0, dy0, dx1, dy1, ... in decimeters (each must fit an int16). */
    fun routeChunk(id: Int, startIdx: Int, deltas: IntArray, count: Int): ByteArray {
        require(count in 0..MAX_CHUNK_POINTS && deltas.size >= count * 2)
        val b = buf(7 + 4 * count)
        b.put(u8(ROUTE_CHUNK)).put(u8(id)).putInt(startIdx).put(u8(count))
        for (i in 0 until count * 2) b.putShort(deltas[i].coerceIn(-32768, 32767).toShort())
        return b.array()
    }

    fun routeCommit(id: Int) = byteArrayOf(u8(ROUTE_COMMIT), u8(id))
    fun routeClear(id: Int) = byteArrayOf(u8(ROUTE_CLEAR), u8(id))

    fun maneuver(icon: Int, street: String): ByteArray {
        val s = street.toByteArray(Charsets.US_ASCII).let { if (it.size > MAX_STREET_BYTES) it.copyOf(MAX_STREET_BYTES) else it }
        val out = ByteArray(2 + s.size + 1)
        out[0] = u8(MANEUVER); out[1] = u8(icon)
        s.copyInto(out, 2)
        return out
    }

    fun config(zoom: Int, flags: Int): ByteArray =
        buf(4).put(u8(CONFIG)).putShort(u16(zoom)).put(u8(flags)).array()

    fun navState(state: Int) = byteArrayOf(u8(NAV_STATE), u8(state))

    fun otaBegin(size: Long, md5: ByteArray, version: Int): ByteArray {
        require(md5.size == 16)
        return buf(23).put(u8(OTA_BEGIN)).putInt(size.toInt()).put(md5).putShort(u16(version)).array()
    }

    fun otaChunk(offset: Long, data: ByteArray, from: Int, len: Int): ByteArray {
        require(len in 1..OTA_MAX_DATA)
        val b = buf(5 + len)
        b.put(u8(OTA_CHUNK)).putInt(offset.toInt()).put(data, from, len)
        return b.array()
    }

    fun otaEnd() = byteArrayOf(u8(OTA_END))
    fun otaAbort() = byteArrayOf(u8(OTA_ABORT))

    fun parseOtaAck(d: ByteArray): OtaAck? {
        if (d.size < 9 || (d[0].toInt() and 0xFF) != OTA_ACK) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        b.position(1)
        val st = b.get().toInt() and 0xFF
        val state = b.get().toInt() and 0xFF
        val next = b.int.toLong() and 0xFFFFFFFFL
        val win = b.get().toInt() and 0xFF
        val every = b.get().toInt() and 0xFF
        return OtaAck(st, state, next, win, every)
    }

    fun parseHelloAck(d: ByteArray): HelloAck? {
        if (d.size < 10 || (d[0].toInt() and 0xFF) != HELLO_ACK) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        b.position(1)
        val proto = b.get().toInt() and 0xFF
        val fw = b.short.toInt() and 0xFFFF
        val w = b.short.toInt() and 0xFFFF
        val h = b.short.toInt() and 0xFFFF
        val mp = b.short.toInt() and 0xFFFF
        return HelloAck(proto, fw, w, h, mp)
    }

    fun parseRouteAck(d: ByteArray): RouteAck? {
        if (d.size < 9 || (d[0].toInt() and 0xFF) != ROUTE_ACK) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        b.position(1)
        val id = b.get().toInt() and 0xFF
        val st = b.get().toInt() and 0xFF
        val exp = b.int
        val free = b.short.toInt() and 0xFFFF
        return RouteAck(id, st, exp, free)
    }

    fun name(type: Int): String = when (type) {
        HELLO -> "HELLO"; REBASE -> "REBASE"; POSE -> "POSE"
        ROUTE_BEGIN -> "ROUTE_BEGIN"; ROUTE_CHUNK -> "ROUTE_CHUNK"
        ROUTE_COMMIT -> "ROUTE_COMMIT"; ROUTE_CLEAR -> "ROUTE_CLEAR"
        MANEUVER -> "MANEUVER"; CONFIG -> "CONFIG"; NAV_STATE -> "NAV_STATE"
        HELLO_ACK -> "HELLO_ACK"; ROUTE_ACK -> "ROUTE_ACK"
        OTA_BEGIN -> "OTA_BEGIN"; OTA_CHUNK -> "OTA_CHUNK"; OTA_END -> "OTA_END"
        OTA_ABORT -> "OTA_ABORT"; OTA_ACK -> "OTA_ACK"
        else -> "0x%02X".format(type)
    }
}