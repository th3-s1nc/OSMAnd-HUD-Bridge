package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.OverspeedAlarm
import org.junit.Assert.assertEquals
import org.junit.Test

class OverspeedAlarmTest {
    @Test
    fun einmalProUeberschreitung() {
        val a = OverspeedAlarm(1_500L)
        assertEquals(false, a.update(65f, 50, 10, 0L))      // gerade drüber, noch nicht lange genug
        assertEquals(true, a.update(65f, 50, 10, 1_600L))   // jetzt Ton
        assertEquals(false, a.update(70f, 50, 10, 5_000L))  // weiter zu schnell: kein zweiter Ton
        assertEquals(false, a.update(58f, 50, 10, 6_000L))  // über Limit, unter Toleranz: noch nicht neu scharf
        assertEquals(false, a.update(66f, 50, 10, 9_000L))  // wieder drüber, aber nie unter dem Limit gewesen
    }

    @Test
    fun nachUnterschreitenWiederScharf() {
        val a = OverspeedAlarm(1_000L)
        a.update(70f, 50, 10, 0L)
        assertEquals(true, a.update(70f, 50, 10, 1_100L))
        assertEquals(false, a.update(48f, 50, 10, 2_000L))  // unter dem Limit: scharf
        assertEquals(false, a.update(70f, 50, 10, 3_000L))
        assertEquals(true, a.update(70f, 50, 10, 4_100L))
    }

    @Test
    fun kurzerAusreisserLoestNichtAus() {
        val a = OverspeedAlarm(1_500L)
        assertEquals(false, a.update(80f, 50, 10, 0L))
        assertEquals(false, a.update(52f, 50, 10, 500L))    // wieder ok: Zählung verworfen
        assertEquals(false, a.update(80f, 50, 10, 1_000L))
        assertEquals(false, a.update(80f, 50, 10, 2_000L))  // erst seit 1 s über
        assertEquals(true, a.update(80f, 50, 10, 2_600L))
    }

    @Test
    fun limitWechselKannAusloesen_unbekanntNicht() {
        val a = OverspeedAlarm(1_000L)
        assertEquals(false, a.update(80f, 100, 10, 0L))
        assertEquals(false, a.update(80f, 0, 10, 500L))     // Limit unbekannt: nichts
        assertEquals(false, a.update(80f, 50, 10, 1_000L))  // Ortseinfahrt: Zählung beginnt
        assertEquals(true, a.update(80f, 50, 10, 2_100L))
    }
}
