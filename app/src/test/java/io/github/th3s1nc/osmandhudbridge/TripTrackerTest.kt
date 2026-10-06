package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.protocol.TripTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {
    @Test
    fun streckeZeitUndHoehe() {
        val t = TripTracker()
        assertNull(t.elevationM)
        assertEquals(0, t.minutes(5_000))
        // ca. 111 m pro 0,001 Grad Breite, 36 km/h
        t.onFix(1_000, 48.000, 12.0, 400.0, 36f, 5f)
        t.onFix(2_000, 48.001, 12.0, 410.0, 36f, 5f)
        t.onFix(3_000, 48.002, 12.0, 410.0, 36f, 5f)
        assertTrue(Math.abs(t.distanceM - 222) <= 2)
        // Höhe wird geglättet: 400 -> 402 -> 403.6
        assertEquals(404, t.elevationM)
        assertEquals(2, t.minutes(1_000 + 2 * 60_000 + 500))
    }

    @Test
    fun standUndUngenauesGpsZaehlenNicht() {
        val t = TripTracker()
        t.onFix(1_000, 48.0, 12.0, null, 0f, 5f)
        t.onFix(2_000, 48.0005, 12.0, null, 1f, 5f) // Stand: kein Tempo -> nichts
        t.onFix(3_000, 48.002, 12.0, null, 30f, 80f) // zu ungenau -> ignoriert
        assertEquals(0, t.distanceM)
        t.reset()
        assertEquals(0, t.minutes(10_000))
        assertNull(t.elevationM)
    }
}
