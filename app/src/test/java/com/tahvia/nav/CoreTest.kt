package com.tahvia.nav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun poseGolden() {
        // x=1 y=-1 heading=900 (90.0 deg) speed=500 cm/s dist=250 m valid
        val got = Proto.pose(1, -1, 900, 500, 250, true)
        assertArrayEquals(b(0x10, 1, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0x84, 0x03, 0xF4, 0x01, 0xFA, 0x00, 1, 0), got)
        assertEquals(17, got.size)
    }

    @Test fun helloConfigNavState() {
        assertArrayEquals(b(0x01, 1), Proto.hello())
        assertArrayEquals(b(0x40, 60, 0, 1), Proto.config(60, Proto.CFG_HEADING_UP))
        assertArrayEquals(b(0x50, 4), Proto.navState(Proto.NAV_NO_GPS))
    }

    @Test fun routeBeginAndChunk() {
        assertArrayEquals(b(0x20, 7, 0, 0, 0, 0, 10, 0, 0, 0, 0xF6, 0xFF, 0xFF, 0xFF), Proto.routeBegin(7, 0, 10, -10))
        val c = Proto.routeChunk(7, 1, intArrayOf(5, -5, 300, 0), 2)
        assertArrayEquals(b(0x21, 7, 1, 0, 0, 0, 2, 5, 0, 0xFB, 0xFF, 0x2C, 0x01, 0, 0), c)
        assertEquals(7 + 4 * Proto.MAX_CHUNK_POINTS, Proto.routeChunk(1, 0, IntArray(118), 59).size)
        assertTrue(7 + 4 * Proto.MAX_CHUNK_POINTS <= 244)
    }

    @Test fun maneuverIsNulTerminatedAndCapped() {
        val m = Proto.maneuver(2, "Av. Corrientes")
        assertEquals(0x30, m[0].toInt()); assertEquals(2, m[1].toInt()); assertEquals(0, m.last().toInt())
        assertEquals(2 + 40 + 1, Proto.maneuver(1, "x".repeat(100)).size)
    }

    @Test fun parseAcks() {
        val ha = Proto.parseHelloAck(b(0x81, 1, 0x00, 0x01, 0xE0, 0x01, 0x40, 0x01, 0x00, 0x04))!!
        assertEquals(HelloAck(1, 0x0100, 480, 320, 1024), ha)
        val ra = Proto.parseRouteAck(b(0x82, 3, 1, 5, 0, 0, 0, 0xFF, 0x03))!!
        assertEquals(RouteAck(3, 1, 5, 1023), ra)
    }
}

class CoordParserTest {
    @Test fun decimal() {
        assertEquals(CoordResult.Ok(-34.6037, -58.3816), CoordParser.parse("-34.6037, -58.3816"))
        assertEquals(CoordResult.Ok(-34.6037, -58.3816), CoordParser.parse("-34.6037 -58.3816"))
        assertEquals(CoordResult.Ok(-34.6037, -58.3816), CoordParser.parse("-34.6037;-58.3816"))
    }

    @Test fun hemisphereAndDms() {
        assertEquals(CoordResult.Ok(-34.6037, -58.3816), CoordParser.parse("34.6037 S, 58.3816 W"))
        val r = CoordParser.parse("34°36'13\"S 58°22'54\"W") as CoordResult.Ok
        assertEquals(-34.60361, r.lat, 1e-4)
        assertEquals(-58.38167, r.lon, 1e-4)
    }

    @Test fun swappedAndInvalidAndAddress() {
        assertEquals(CoordResult.Swapped(40.0, 120.0), CoordParser.parse("120, 40"))
        assertEquals(CoordResult.Invalid, CoordParser.parse("200, 300"))
        assertEquals(CoordResult.NotCoords, CoordParser.parse("Plaza de Mayo, Buenos Aires"))
    }
}

class GeoTest {
    @Test fun projectorRoundTripAndRebase() {
        val p = Projector(-34.6037, -58.3816)
        assertEquals(0, p.x(-58.3816)); assertEquals(0, p.y(-34.6037))
        val x = p.x(-58.3716); val y = p.y(-34.5937)
        assertTrue(x > 8000 && y > 10000)          // ~0.01 deg is ~0.9 km east and ~1.1 km north (dm)
        val q = p.rebased(x, y)
        assertTrue(Math.abs(q.x(-58.3716)) <= 1 && Math.abs(q.y(-34.5937)) <= 1)
        // a pure translation: a third point keeps the same relative offset in both frames
        val a = p.x(-58.3616) - x
        val b = q.x(-58.3616)
        assertTrue(Math.abs(a - b) <= 1)
    }

    @Test fun polylineDecode6() {
        // Google's example string, precision 5 values scaled to 6 would differ; encode our own point instead.
        val (la, lo) = Polyline.decode6("_p~iF~ps|U")        // (38.5, -120.2) at precision 5
        assertEquals(1, la.size)
        assertEquals(3.85, la[0], 1e-9)                      // read as precision 6: 38.5e5 / 1e6
        assertEquals(-12.02, lo[0], 1e-9)
    }

    @Test fun simplifyKeepsAnchorsAndDropsCollinear() {
        val lat = DoubleArray(5) { it * 0.0001 }
        val lon = DoubleArray(5) { 0.0 }
        val keep = Polyline.simplify(lat, lon, intArrayOf(2), 1.5)
        assertTrue(keep[0] && keep[2] && keep[4])
        assertTrue(!keep[1] && !keep[3])
    }
}

class FirmwareUrlTest {
    @Test fun sanitizeRefsHeads() {
        val input = "https://raw.githubusercontent.com/cubicstarsdev2/TahVia/refs/heads/main/README.md"
        val expected = "https://raw.githubusercontent.com/cubicstarsdev2/TahVia/main/README.md"
        assertEquals(expected, sanitizeFirmwareUrl(input))
    }

    @Test fun defaultUrlsAreValidRawGithubUrls() {
        val settings = Settings()
        assertTrue(!settings.readmeUrl.contains("refs/heads"))
        assertTrue(!settings.binUrl.contains("refs/heads"))
    }
}

class NetTest {
    @Test fun githubAuthorizationHeaderAttachedWhenTokenSet() {
        Net.githubToken = { "my_secret_token" }
        val req = Net.request("https://raw.githubusercontent.com/cubicstarsdev2/TahVia/main/README.md", "ua")
        assertEquals("Bearer my_secret_token", req.header("Authorization"))
    }

    @Test fun githubAuthorizationHeaderNotAttachedToOtherHosts() {
        Net.githubToken = { "my_secret_token" }
        val req = Net.request("https://router.project-osrm.org/route/v1/driving", "ua")
        assertEquals(null, req.header("Authorization"))
    }
}

