package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.track.DemoRides
import io.github.th3s1nc.osmandhudbridge.track.MapMath
import io.github.th3s1nc.osmandhudbridge.track.RideAnalysis
import io.github.th3s1nc.osmandhudbridge.track.TrackPoint
import io.github.th3s1nc.osmandhudbridge.track.TrackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDetailTest {
    @Test
    fun kachelnWieOpenStreetMap() {
        // Berlin (52,52 N / 13,405 O) liegt bei Zoom 10 auf Kachel 550/335
        assertEquals(550, MapMath.tileX(13.405, 10))
        assertEquals(335, MapMath.tileY(52.52, 10))
        assertEquals(0, MapMath.tileX(-180.0, 3))
        assertEquals(7, MapMath.tileX(179.9, 3))
    }

    @Test
    fun zoomPasstDenAusschnitt() {
        // ca. 10 km x 6 km in 984 x 570 Pixeln, Kachel 256 px
        val z = MapMath.chooseZoom(48.00, 48.054, 12.00, 12.134, 984.0, 570.0, 256.0)
        val w = MapMath.worldX(12.134, z, 256.0) - MapMath.worldX(12.0, z, 256.0)
        val h = MapMath.worldY(48.0, z, 256.0) - MapMath.worldY(48.054, z, 256.0)
        assertTrue(w <= 984 * 0.85 && h <= 570 * 0.85)
        val w2 = MapMath.worldX(12.134, z + 1, 256.0) - MapMath.worldX(12.0, z + 1, 256.0)
        val h2 = MapMath.worldY(48.0, z + 1, 256.0) - MapMath.worldY(48.054, z + 1, 256.0)
        assertTrue(z == 16 || w2 > 984 * 0.85 || h2 > 570 * 0.85) // nächste Stufe wäre zu groß
    }

    @Test
    fun beschleunigungAusTempo() {
        val t0 = 1_700_000_000_000L
        // 0 -> 36 km/h in 10 s = 1 m/s²
        val pts = (0..10).map { TrackPoint(t0 + it * 1000L, 48.0 + it * 0.0001, 12.0, null, it * 3.6f) } +
            (11..20).map { TrackPoint(t0 + it * 1000L, 48.0 + it * 0.0001, 12.0, null, 36f - (it - 10) * 7.2f) } // bremsen mit 2 m/s²
        val s = RideAnalysis.series(pts)
        assertTrue(Math.abs(s.accel[5] - 1f) < 0.05f)
        assertTrue(Math.abs(s.accel[15] + 2f) < 0.05f)
        assertTrue(s.maxAccel in 0.9f..1.1f)
        assertTrue(s.maxBrake in -2.1f..-1.9f)
        assertFalse(RideAnalysis.hasData(s.ele))
    }

    @Test
    fun testfahrtenSindPlausibel() {
        val t0 = 1_700_000_000_000L
        for (spec in DemoRides.specs) {
            val pts = DemoRides.generate(spec, t0)
            assertEquals(spec.minutes * 60, pts.size)
            val st = TrackSession().also { it.restore(pts) }.stats()
            assertTrue(spec.name + " Strecke " + st.distanceM, st.distanceM in 8_000..130_000)
            assertTrue(spec.name + " max " + st.maxKmh, st.maxKmh in 60..140)
            assertTrue(spec.name + " Schräglage " + st.maxLeanDeg, st.maxLeanDeg in 10..60)
            assertTrue(spec.name + " Überschreitungen " + st.overCount, st.overCount >= 1)
            assertTrue(st.hasEle && st.hasLimit && st.hasLean)
            val s = RideAnalysis.series(pts)
            assertTrue(s.maxAccel > 0.5f && s.maxBrake < -0.5f)
        }
        // Pause: Lücke von 12 Minuten zwischen zwei Punkten
        val pts = DemoRides.generate(DemoRides.specs[1], t0)
        assertTrue(pts.zipWithNext().any { (a, b) -> b.timeMs - a.timeMs > 600_000 })
    }
}
