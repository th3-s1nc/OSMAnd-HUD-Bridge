package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy
import io.github.th3s1nc.osmandhudbridge.limit.GpsPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsPolicyTest {
    @Test
    fun hudVerbunden_istSchnell() {
        val p = GpsPolicy()
        assertEquals(Mode.FAST, p.mode(0L, true))
        assertEquals(Mode.FAST, p.mode(500_000L, true))
    }

    @Test
    fun hudWeg_schnellBleibtEineMinute_dannSparmodus() {
        val p = GpsPolicy()
        p.mode(0L, true)
        assertEquals(Mode.FAST, p.mode(30_000L, false))
        assertEquals(Mode.MOVING, p.mode(61_000L, false)) // gerade erst gestartet: gilt als unterwegs
    }

    @Test
    fun stand_wirdLangsam_bewegungWiederSchneller() {
        val p = GpsPolicy()
        assertEquals(Mode.MOVING, p.mode(0L, false))
        assertEquals(Mode.MOVING, p.mode(100_000L, false))
        assertEquals(Mode.IDLE, p.mode(200_000L, false)) // 3 min ohne Bewegung
        p.onFix(210_000L, 0.2f)
        assertEquals(Mode.IDLE, p.mode(220_000L, false))
        p.onFix(500_000L, 12f) // fährt wieder
        assertEquals(Mode.MOVING, p.mode(505_000L, false))
        assertEquals(Mode.IDLE, p.mode(500_000L + 181_000L, false))
    }

    @Test
    fun intervalle() {
        assertEquals(1_000L, Mode.FAST.intervalMs)
        assertEquals(30_000L, Mode.MOVING.intervalMs)
        assertEquals(300_000L, Mode.IDLE.intervalMs)
    }
}
