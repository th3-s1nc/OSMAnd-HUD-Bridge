package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.protocol.NavExtrapolation
import org.junit.Assert.assertEquals
import org.junit.Test

class NavExtrapolationTest {
    @Test
    fun gpsTempoWirdGenommen_undVerzoegerungAbgezogen() {
        // 72 km/h = 20 m/s, Meldung gerade eben: 0,8 s Verzögerung = 16 m
        assertEquals(484, NavExtrapolation.distance(500, 0f, 72f, 0f))
        // 1 s später: 1,8 s = 36 m
        assertEquals(464, NavExtrapolation.distance(500, 1f, 72f, 0f))
    }

    @Test
    fun ohneTempo_dieMeldeGeschwindigkeit() {
        assertEquals(500 - 8, NavExtrapolation.distance(500, 0f, 0f, 10f)) // 10 m/s * 0,8 s
    }

    @Test
    fun stand_bleibtStehen() {
        assertEquals(300, NavExtrapolation.distance(300, 3f, 0f, 0f))
        assertEquals(300, NavExtrapolation.distance(300, 3f, 1f, 0f)) // unter 2 km/h
    }

    @Test
    fun begrenztUndNieNegativ() {
        // 10 s Funkstille mit GPS-Tempo: (10 + 0,8) s * 20 m/s = 216 m, nicht mehr bei 4 s eingefroren
        assertEquals(284, NavExtrapolation.distance(500, 10f, 72f, 0f))
        // ohne GPS-Tempo nur die ersten 4 s mit der Meldegeschwindigkeit (10 m/s)
        assertEquals(460, NavExtrapolation.distance(500, 10f, 0f, 10f))
        assertEquals(0, NavExtrapolation.distance(10, 2f, 72f, 0f))
    }
}
