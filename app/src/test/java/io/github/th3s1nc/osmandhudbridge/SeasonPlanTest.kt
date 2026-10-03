package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan
import io.github.th3s1nc.osmandhudbridge.limit.SeasonPlan.State
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonPlanTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun maerzBisOktober() {
        val p = SeasonPlan(3, 10, 3)
        assertEquals(State.IN_SEASON, p.state(d(2026, 3, 1)))
        assertEquals(State.IN_SEASON, p.state(d(2026, 10, 31)))
        assertEquals(State.OFF, p.state(d(2026, 11, 1)))
        assertEquals(State.OFF, p.state(d(2027, 2, 1)))
        assertEquals(State.LEAD, p.state(d(2027, 2, 8))) // genau 3 Wochen vor dem 1. März
        assertEquals(State.LEAD, p.state(d(2027, 2, 28)))
        assertEquals(State.OFF, p.state(d(2027, 2, 7)))
        assertEquals(State.IN_SEASON, p.state(d(2027, 3, 1)))
    }

    @Test
    fun nextStartUndLoadStart() {
        val p = SeasonPlan(3, 10, 3)
        assertNull(p.nextStart(d(2026, 6, 1)))
        assertEquals(d(2027, 3, 1), p.nextStart(d(2026, 12, 24)))
        assertEquals(d(2027, 2, 8), p.loadStart(d(2026, 12, 24)))
        assertEquals(d(2026, 3, 1), p.nextStart(d(2026, 1, 15))) // Start noch im selben Jahr
    }

    @Test
    fun saisonUeberDenJahreswechsel() {
        val p = SeasonPlan(11, 2, 2)
        assertEquals(State.IN_SEASON, p.state(d(2026, 12, 31)))
        assertEquals(State.IN_SEASON, p.state(d(2027, 2, 28)))
        assertEquals(State.OFF, p.state(d(2027, 3, 1)))
        assertEquals(State.OFF, p.state(d(2027, 10, 1)))
        assertEquals(State.LEAD, p.state(d(2027, 10, 20))) // ab 18. Oktober
        assertEquals(State.OFF, p.state(d(2027, 10, 17)))
    }

    @Test
    fun ohneVorlaufUndGanzesJahr() {
        assertEquals(State.OFF, SeasonPlan(3, 10, 0).state(d(2027, 2, 28)))
        assertEquals(State.IN_SEASON, SeasonPlan(1, 12, 3).state(d(2027, 1, 1)))
        assertEquals(State.IN_SEASON, SeasonPlan(5, 5, 1).state(d(2027, 5, 15)))
    }
}
