package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.GpxParser
import io.github.th3s1nc.osmandhudbridge.limit.PreloadPlanner
import io.github.th3s1nc.osmandhudbridge.limit.TileCache
import io.github.th3s1nc.osmandhudbridge.limit.TileKey
import io.github.th3s1nc.osmandhudbridge.limit.TileMath
import io.github.th3s1nc.osmandhudbridge.limit.TourStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxImportTest {
    private val calimoto = """<?xml version="1.0"?>
<gpx xmlns="http://www.topografix.com/GPX/1/1" creator="calimoto">
  <metadata><name>Tour vom 2026 - 10 - 02</name><author><name>jemand</name></author></metadata>
  <wpt lon="12.15" lat="48.91"></wpt>
  <rte><name>Tour vom 2026 - 10 - 02</name>
    <rtept lon="12.150" lat="48.910"></rtept>
    <rtept lon="12.155" lat="48.912"></rtept>
    <rtept lon="12.160" lat="48.914"></rtept>
  </rte></gpx>"""

    private val kurviger = """<gpx version="1.1" creator="Kurviger"><metadata><name>Thal &amp; Berg</name></metadata>
<rte><rtept lat="48.9104" lon="12.1502"><name>Start</name></rtept><rtept lat="49.03052" lon="12.46871"/></rte>
<trk><trkseg>
<trkpt lat="48.9104" lon="12.1502"><ele>1</ele></trkpt>
<trkpt lat="48.9110" lon="12.1520"><ele>1</ele></trkpt>
<trkpt lat="48.9120" lon="12.1540"><ele>1</ele></trkpt>
</trkseg></trk></gpx>"""

    private val motobit = """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
<metadata><desc>planned with Motobit</desc><name>-</name></metadata>
<rte><rtept lat="48.910408" lon="12.150206"/><rtept lat="48.910353" lon="12.150374"/></rte></gpx>"""

    @Test fun routeIsReadWhenThereIsNoTrack() {
        val r = GpxParser.parse(calimoto)!!
        assertEquals("Tour vom 2026 - 10 - 02", r.name)
        assertEquals(3, r.points.size)
        assertTrue(Math.abs(r.points[0][0] - 48.910) < 1e-9)
        assertTrue(Math.abs(r.points[0][1] - 12.150) < 1e-9)
        assertFalse(r.sparse)
    }

    @Test fun trackWinsOverSparseRoute() {
        val r = GpxParser.parse(kurviger)!!
        assertEquals(3, r.points.size) // nicht die 2 Stützpunkte der Route
        assertEquals("Thal & Berg", r.name)
    }

    @Test fun attributeOrderAndDashNameAreHandled() {
        val r = GpxParser.parse(motobit)!!
        assertEquals(2, r.points.size)
        assertEquals("Tour", r.name)
    }

    @Test fun sparseRouteIsFlagged() {
        val txt = "<gpx><rte><rtept lat=\"48.0\" lon=\"11.0\"/><rtept lat=\"48.2\" lon=\"11.4\"/><rtept lat=\"48.5\" lon=\"11.9\"/></rte></gpx>"
        assertTrue(GpxParser.parse(txt)!!.sparse)
    }

    @Test fun emptyOrBrokenFilesGiveNull() {
        assertNull(GpxParser.parse(""))
        assertNull(GpxParser.parse("<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>"))
        assertNull(GpxParser.parse("<gpx><rte><rtept lat=\"1\" lon=\"2\"/></rte></gpx>"))
    }

    @Test fun routeTilesFollowTheLine() {
        // 10 km nach Osten bei 48,01 N
        val pts = listOf(doubleArrayOf(48.0107, 11.0121), doubleArrayOf(48.0107, 11.0121 + 10_000.0 / (111_320.0 * Math.cos(Math.toRadians(48.0107)))))
        val tiles = PreloadPlanner.route(pts)
        assertEquals(tiles.size, tiles.toSet().size)
        val here = TileMath.tileOf(48.0107, 11.0121)
        assertTrue(tiles.any { it.lonIdx >= here.lonIdx + 4 })
        assertTrue(tiles.all { it.latIdx in (here.latIdx - 1)..(here.latIdx + 1) })
        assertTrue(tiles.size in 8..30)
    }

    @Test fun tourStoreKeepsTourUntilAllTilesAreCached() {
        val base = File(System.getProperty("java.io.tmpdir"), "hudtour_${System.nanoTime()}")
        try {
            val cache = TileCache(File(base, "limits"))
            val store = TourStore(File(base, "tours"))
            val tiles = listOf(TileKey(1, 1), TileKey(1, 2), TileKey(1, 3))
            store.add("Test", tiles)
            val now = System.currentTimeMillis()
            assertEquals(3, store.status(cache, now).single().missing)
            assertEquals(TileKey(1, 1), store.nextMissing(cache, now))
            cache.write(TileKey(1, 1), "x")
            assertEquals(2, store.status(cache, now).single().missing)
            assertEquals(TileKey(1, 2), store.nextMissing(cache, now))
            cache.write(TileKey(1, 2), "x"); cache.write(TileKey(1, 3), "x")
            assertTrue(store.status(cache, now).isEmpty())
            assertNull(store.nextMissing(cache, now))
            assertTrue(store.list().isEmpty()) // fertige Tour wurde entfernt
            store.add("Zwei", tiles); store.clear()
            assertTrue(store.list().isEmpty())
        } finally { base.deleteRecursively() }
    }
}
