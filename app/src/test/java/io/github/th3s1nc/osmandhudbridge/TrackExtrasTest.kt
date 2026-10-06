package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.track.AltitudeFilter
import io.github.th3s1nc.osmandhudbridge.track.CsvWriter
import io.github.th3s1nc.osmandhudbridge.track.GpxWriter
import io.github.th3s1nc.osmandhudbridge.track.LeanEstimator
import io.github.th3s1nc.osmandhudbridge.track.OverTracker
import io.github.th3s1nc.osmandhudbridge.track.TrackPoint
import io.github.th3s1nc.osmandhudbridge.track.TrackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackExtrasTest {
    private fun near(exp: Number, act: Number, tol: Number) = assertTrue("$act statt $exp", Math.abs(exp.toDouble() - act.toDouble()) <= tol.toDouble())

    private val t0 = 1_700_000_000_000L

    @Test
    fun ueberschreitungenWerdenGezaehlt() {
        val o = OverTracker()
        assertFalse(o.update(72f, 70)) // knapp drüber: noch ok (Spielraum 3 km/h)
        assertTrue(o.update(74f, 70)) // 1
        assertTrue(o.update(72f, 70)) // noch über dem Limit: weiter dieselbe Überschreitung
        assertFalse(o.update(69f, 70))
        assertTrue(o.update(80f, 70)) // 2
        assertFalse(o.update(80f, 0)) // Limit unbekannt: nie zu schnell
        assertEquals(2, o.count)
    }

    @Test
    fun auf_abstiegMitSchwelleUndFlags() {
        val s = TrackSession()
        val eles = listOf(400.0, 401.0, 399.5, 405.0, 410.0, 404.0, 400.0)
        eles.forEachIndexed { i, e -> s.onFix(TrackPoint(t0 + i * 1000L, 48.0 + i * 0.0001, 12.0, e, 40f, 70, 10f)) }
        val st = s.stats()
        assertTrue(st.hasEle && st.hasLimit && st.hasLean)
        assertEquals(10, st.ascentM) // 400 -> 410
        assertEquals(10, st.descentM) // 410 -> 400
        assertEquals(10, st.maxLeanDeg)
        // ohne Zusatzdaten keine Flags
        val s2 = TrackSession()
        s2.onFix(TrackPoint(t0, 48.0, 12.0, null, 40f))
        s2.onFix(TrackPoint(t0 + 1000, 48.0001, 12.0, null, 40f))
        assertFalse(s2.stats().hasEle || s2.stats().hasLimit || s2.stats().hasLean)
    }

    @Test
    fun hoeheAusBarometerFolgtDemGpsNiveau() {
        val f = AltitudeFilter()
        assertNull(f.altitude())
        f.onGps(500.0)
        near(500.0, f.altitude()!!, 0.01) // ohne Barometer: GPS
        // Standarddruck 1013,25 hPa = 0 m; wir sind laut GPS auf 500 m
        repeat(50) { f.onPressure(1013.25f); f.onGps(500.0) }
        near(500.0, f.altitude()!!, 1.0)
        // Druck fällt um ca. 1,2 hPa = ca. 10 m höher, GPS meldet weiter 500
        repeat(100) { f.onPressure(1012.05f) }
        near(510.0, f.altitude()!!, 2.5)
    }

    @Test
    fun schraeglageRechtsPositivUndErstAbTempo() {
        val l = LeanEstimator()
        // Handy flach: Schwerkraft zeigt nach oben (z = +9,81)
        repeat(30) { l.onGravity(0f, 0f, 9.81f) }
        assertNull(l.lean(2f)) // zu langsam
        // Rechtskurve = Drehung im Uhrzeigersinn von oben = Drehrate um z negativ
        repeat(100) { l.onGyro(0f, 0f, -0.3f) }
        val right = l.lean(20f)!!
        near(31.4f, right, 1.5f) // atan(20 * 0,3 / 9,81)
        val l2 = LeanEstimator()
        repeat(30) { l2.onGravity(0f, 0f, 9.81f) }
        repeat(100) { l2.onGyro(0f, 0f, 0.3f) }
        near(-31.4f, l2.lean(20f)!!, 1.5f)
        // Handy hochkant (y oben): dieselbe Kurve, andere Achse
        val l3 = LeanEstimator()
        repeat(30) { l3.onGravity(0f, 9.81f, 0f) }
        repeat(100) { l3.onGyro(0f, -0.3f, 0f) }
        near(31.4f, l3.lean(20f)!!, 1.5f)
    }

    @Test
    fun csvFuerExcel() {
        val pts = listOf(
            TrackPoint(t0, 48.5, 12.25, 412.4, 68.2f, 70, 12f),
            TrackPoint(t0 + 1000, 48.5001, 12.25, 412.0, 74.0f, 70, -18f)
        )
        val c = CsvWriter.write(pts)
        assertTrue(c.startsWith("﻿Zeit;Breite;Länge;Höhe m;Tempo km/h;Limit km/h;Über Limit;Schräglage °\r\n"))
        val rows = c.trim().split("\r\n")
        assertEquals(3, rows.size)
        assertTrue(rows[1].endsWith(";48,500000;12,250000;412;68;70;nein;12"))
        assertTrue(rows[2].endsWith(";48,500100;12,250000;412;74;70;ja;-18"))
        // ohne Zusatzdaten nur die Grundspalten
        val plain = CsvWriter.write(listOf(TrackPoint(t0, 48.0, 12.0, null, 30f), TrackPoint(t0 + 1000, 48.0, 12.0, null, 30f)))
        assertTrue(plain.startsWith("﻿Zeit;Breite;Länge;Tempo km/h\r\n"))
    }

    @Test
    fun gpxMitEigenenFeldern() {
        val x = GpxWriter.write("t", listOf(TrackPoint(t0, 48.0, 12.0, 400.0, 36f, 50, -7f)))
        assertTrue(x.contains("<hb:limit>50</hb:limit><hb:lean>-7</hb:lean>"))
        assertTrue(x.contains("xmlns:hb="))
        assertNotNull(x)
        assertFalse(GpxWriter.write("t", listOf(TrackPoint(t0, 48.0, 12.0, 400.0, 36f))).contains("hb:limit"))
    }
}
