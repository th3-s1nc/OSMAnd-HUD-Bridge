package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.track.GpxWriter
import io.github.th3s1nc.osmandhudbridge.track.RideFormat
import io.github.th3s1nc.osmandhudbridge.track.TrackPoint
import io.github.th3s1nc.osmandhudbridge.track.TrackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSessionTest {
    private val t0 = 1_700_000_000_000L

    // 0,0005 Grad Breite = ca. 55,6 m, bei 1 Punkt pro Sekunde ca. 200 km/h; hier nur für die Rechnung
    private fun p(sec: Int, latStep: Int, kmh: Float, ele: Double? = null) =
        TrackPoint(t0 + sec * 1000L, 48.0 + latStep * 0.0001, 12.0, ele, kmh)

    @Test
    fun streckeZeitUndMaximum() {
        val s = TrackSession()
        for (i in 0..10) s.onFix(p(i, i, 40f + i)) // 11,1 m pro Sekunde
        val st = s.stats()
        assertEquals(11, st.points)
        assertTrue(Math.abs(st.distanceM - 111) <= 2)
        assertEquals(10_000L, st.movingMs)
        assertEquals(50, st.maxKmh)
        assertEquals(10_000L, st.totalMs)
        assertEquals(40, st.avgKmh) // 111 m in 10 s = 40 km/h
    }

    @Test
    fun hoechstensEinPunktProSekunde() {
        val s = TrackSession()
        assertTrue(s.onFix(TrackPoint(t0, 48.0, 12.0, null, 30f)))
        assertFalse(s.onFix(TrackPoint(t0 + 400, 48.00001, 12.0, null, 30f)))
        assertTrue(s.onFix(TrackPoint(t0 + 1000, 48.0001, 12.0, null, 30f)))
    }

    @Test
    fun autopauseNachDreiMinutenUndWeiterBeimLosfahren() {
        val s = TrackSession()
        s.onFix(p(0, 0, 30f))
        s.onFix(p(1, 1, 30f))
        // Stand: ab 3 Minuten ohne Bewegung pausiert die Aufnahme
        var n = 0
        for (i in 2..300) if (s.onFix(p(i, 1, 0f))) n++
        assertTrue(s.paused)
        assertTrue(n < 190) // danach wird nichts mehr aufgenommen
        val before = s.points.size
        assertFalse(s.onFix(p(301, 1, 3f))) // unter 5 km/h: bleibt pausiert
        assertTrue(s.onFix(p(302, 2, 8f))) // ab 5 km/h geht es weiter
        assertFalse(s.paused)
        assertEquals(before + 1, s.points.size)
        // über die Pause hinweg keine Strecke und keine Bewegungszeit dazu
        assertTrue(s.stats().distanceM < 30)
    }

    @Test
    fun standGpsRauschenZaehltNichtAlsStrecke() {
        val s = TrackSession()
        s.onFix(p(0, 0, 0f))
        s.onFix(p(1, 1, 1f)) // 11 m "Sprung" bei Stand
        s.onFix(p(2, 0, 0f))
        assertEquals(0, s.stats().distanceM)
        assertEquals(0L, s.stats().movingMs)
    }

    @Test
    fun wiederherstellenErgibtDieselbenKennzahlen() {
        val a = TrackSession()
        for (i in 0..20) a.onFix(p(i, i, 36f))
        val b = TrackSession()
        b.restore(a.points)
        assertEquals(a.stats(), b.stats())
    }

    @Test
    fun gpxMitTempoErweiterungUndPausenLuecke() {
        val pts = listOf(p(0, 0, 36f, 400.0), p(1, 1, 36f, 401.5), p(200, 5, 72f))
        val x = GpxWriter.write("Test & <Fahrt>", pts)
        assertTrue(x.contains("<name>Test &amp; &lt;Fahrt&gt;</name>"))
        assertTrue(x.contains("<trkpt lat=\"48.000000\" lon=\"12.000000\"><ele>400.0</ele><time>2023-11-14T22:13:20Z</time>"))
        assertTrue(x.contains("<gpxtpx:speed>10.00</gpxtpx:speed>"))
        assertTrue(x.contains("<gpxtpx:speed>20.00</gpxtpx:speed>"))
        assertEquals(2, Regex("<trkseg>").findAll(x).count()) // Lücke über 60 s = neues Teilstück
        assertEquals(3, Regex("<trkpt ").findAll(x).count())
        assertTrue(x.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun texte() {
        assertEquals("1:25 h", RideFormat.duration(85 * 60_000L))
        assertEquals("7 min", RideFormat.duration(7 * 60_000L))
        assertEquals("850 m", RideFormat.distance(850))
        assertEquals("38,4 km", RideFormat.distance(38_420))
        assertEquals("Fahrt_04_10_Süd", RideFormat.fileSafe("Fahrt 04.10. Süd"))
        assertEquals("Fahrt", RideFormat.fileSafe("???"))
    }
}
