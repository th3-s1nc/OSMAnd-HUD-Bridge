package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.PointKind
import io.github.th3s1nc.osmandhudbridge.limit.PointWarn
import io.github.th3s1nc.osmandhudbridge.limit.WarnPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointWarnTest {
    private val lat0 = 49.0
    private val lon0 = 12.0
    private val mPerDeg = 111_320.0

    /** Punkt [m] Meter nördlich von (lat0, lon0), [side] Meter nach Osten. */
    private fun p(id: Long, kind: Int, m: Double, side: Double = 0.0) =
        WarnPoint(id, lat0 + m / mPerDeg, lon0 + side / (mPerDeg * Math.cos(Math.toRadians(lat0))), kind)

    private val all = PointKind.LEVEL_CROSSING or PointKind.ZEBRA or PointKind.CALMING

    @Test
    fun artAusTags() {
        assertEquals(PointKind.LEVEL_CROSSING, PointKind.of(mapOf("railway" to "level_crossing")))
        assertEquals(PointKind.ZEBRA, PointKind.of(mapOf("highway" to "crossing", "crossing" to "zebra")))
        assertEquals(PointKind.ZEBRA, PointKind.of(mapOf("highway" to "crossing", "crossing:markings" to "zebra")))
        assertEquals(0, PointKind.of(mapOf("highway" to "crossing", "crossing" to "traffic_signals")))
        assertEquals(PointKind.CALMING, PointKind.of(mapOf("traffic_calming" to "bump")))
        assertEquals(0, PointKind.of(mapOf("traffic_calming" to "no")))
        assertEquals(0, PointKind.of(mapOf("amenity" to "cafe")))
    }

    @Test
    fun abstandHaengtVomTempoUndDerArtAb() {
        // 50 km/h = 13,9 m/s: Zebrastreifen ca. 67 m, Bahnübergang/Verkehrsberuhigung 60 % davon
        assertTrue(Math.abs(PointWarn.leadDistance(13.9f, PointKind.ZEBRA) - 67.0) <= 2.0)
        assertTrue(Math.abs(PointWarn.leadDistance(13.9f, PointKind.LEVEL_CROSSING) - 40.0) <= 1.0)
        // 100 km/h = 27,8 m/s: ca. 210 m
        assertTrue(Math.abs(PointWarn.leadDistance(27.8f, PointKind.ZEBRA) - 210.0) <= 5.0)
        assertTrue(PointWarn.leadDistance(60f, PointKind.ZEBRA) <= PointWarn.MAX_LEAD_M)
        assertTrue(PointWarn.leadDistance(6f, PointKind.ZEBRA) >= PointWarn.MIN_LEAD_M)
    }

    @Test
    fun tonEinmalImRichtigenAbstand() {
        val w = PointWarn()
        val pts = listOf(p(1, PointKind.ZEBRA, 300.0))
        // 50 km/h, Fahrt nach Norden: bei 200 m noch nichts, bei 60 m Ton, danach nicht noch einmal
        assertEquals(0, w.update(pts, lat0 + 100.0 / mPerDeg, lon0, 0.0, 13.9f, all))
        assertEquals(PointKind.ZEBRA, w.update(pts, lat0 + 240.0 / mPerDeg, lon0, 0.0, 13.9f, all))
        assertEquals(0, w.update(pts, lat0 + 250.0 / mPerDeg, lon0, 0.0, 13.9f, all))
    }

    @Test
    fun nichtHinterDirNichtSeitlichUndNurAktiveArten() {
        val w = PointWarn()
        val behind = listOf(p(1, PointKind.ZEBRA, -40.0))
        assertEquals(0, w.update(behind, lat0, lon0, 0.0, 13.9f, all))
        val side = listOf(p(2, PointKind.ZEBRA, 40.0, 60.0))
        assertEquals(0, w.update(side, lat0, lon0, 0.0, 13.9f, all))
        val ahead = listOf(p(3, PointKind.CALMING, 30.0))
        assertEquals(0, w.update(ahead, lat0, lon0, 0.0, 13.9f, PointKind.ZEBRA))
        assertEquals(PointKind.CALMING, w.update(ahead, lat0, lon0, 0.0, 13.9f, PointKind.CALMING))
    }

    @Test
    fun langsamOhneRichtungOderAusKeinTon() {
        val w = PointWarn()
        val pts = listOf(p(1, PointKind.LEVEL_CROSSING, 30.0))
        assertEquals(0, w.update(pts, lat0, lon0, 0.0, 3f, all))      // zu langsam
        assertEquals(0, w.update(pts, lat0, lon0, null, 13.9f, all))  // keine Richtung
        assertEquals(0, w.update(pts, lat0, lon0, 0.0, 13.9f, 0))     // alles aus
        assertEquals(PointKind.LEVEL_CROSSING, w.update(pts, lat0, lon0, 0.0, 13.9f, all))
    }

    @Test
    fun einTonFuerNahBeieinanderLiegendePunkte() {
        val w = PointWarn()
        val pts = listOf(p(1, PointKind.ZEBRA, 50.0), p(2, PointKind.CALMING, 55.0))
        assertEquals(PointKind.ZEBRA, w.update(pts, lat0, lon0, 0.0, 13.9f, all))
        assertEquals(0, w.update(pts, lat0 + 2.0 / mPerDeg, lon0, 0.0, 13.9f, all))
    }

    @Test
    fun nachUmkehrenWeitWegWirdNeuGewarnt() {
        val w = PointWarn()
        val pts = listOf(p(1, PointKind.ZEBRA, 50.0))
        assertEquals(PointKind.ZEBRA, w.update(pts, lat0, lon0, 0.0, 13.9f, all))
        // 800 m weg (Süden), dann wieder nach Norden
        assertEquals(0, w.update(pts, lat0 - 750.0 / mPerDeg, lon0, 180.0, 13.9f, all))
        assertEquals(PointKind.ZEBRA, w.update(pts, lat0, lon0, 0.0, 13.9f, all))
    }
}
