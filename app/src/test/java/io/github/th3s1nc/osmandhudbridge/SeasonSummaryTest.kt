package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.track.RideStats
import io.github.th3s1nc.osmandhudbridge.track.SeasonSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class SeasonSummaryTest {
    private val utc = ZoneOffset.UTC
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(10, 0).toInstant(utc).toEpochMilli()
    private fun ride(y: Int, m: Int, d: Int, km: Int, max: Int, limit: Boolean = true, over: Int = 0) =
        RideStats(ms(y, m, d), ms(y, m, d) + 3_600_000, 3_000_000, km * 1000, max, 100, hasLimit = limit, overCount = over)

    @Test fun saisonMaerzBisOktober() {
        val p = SeasonSummary.period(2026, true, 3, 10)
        assertEquals("2026", p.label)
        assertEquals(8, p.months.size)
        val rides = listOf(
            ride(2026, 3, 1, 50, 100, over = 2), ride(2026, 8, 15, 100, 168, over = 5), ride(2026, 8, 20, 30, 90),
            ride(2026, 11, 2, 40, 80), ride(2025, 6, 1, 70, 110)
        )
        val s = SeasonSummary.summarize(rides, p, utc)
        assertEquals(3, s.rides)
        assertEquals(180_000L, s.distanceM)
        assertEquals(168, s.maxKmh)
        assertEquals(7, s.overCount)
        assertEquals(2, s.ridesWithOver)
        assertEquals(130_000L, s.months.first { it.ym == YearMonth.of(2026, 8) }.distanceM)
        assertEquals(0L, s.months.first { it.ym == YearMonth.of(2026, 5) }.distanceM)
    }

    @Test fun ueberschreitungenNurMitLimitDaten() {
        val s = SeasonSummary.summarize(listOf(ride(2026, 5, 1, 10, 90, limit = false, over = 9), ride(2026, 5, 2, 10, 90, over = 1)), SeasonSummary.period(2026, true, 3, 10), utc)
        assertEquals(1, s.overCount)
        assertEquals(1, s.ridesWithLimit)
    }

    @Test fun saisonUeberJahreswechsel() {
        val p = SeasonSummary.period(2025, true, 11, 2)
        assertEquals("2025/26", p.label)
        assertEquals(4, p.months.size)
        assertEquals(2025, SeasonSummary.yearOf(YearMonth.of(2026, 1), true, 11, 2))
        assertEquals(2025, SeasonSummary.yearOf(YearMonth.of(2025, 12), true, 11, 2))
        assertNull(SeasonSummary.yearOf(YearMonth.of(2026, 6), true, 11, 2))
        assertEquals(1, SeasonSummary.summarize(listOf(ride(2026, 1, 5, 20, 80)), p, utc).rides)
    }

    @Test fun ohneSaisonZaehltDasKalenderjahr() {
        val p = SeasonSummary.period(2026, false, 3, 10)
        assertEquals(12, p.months.size)
        assertEquals(2026, SeasonSummary.yearOf(YearMonth.of(2026, 12), false, 3, 10))
    }

    @Test fun jahreListeUndAktuelleSaison() {
        val rides = listOf(ride(2024, 5, 1, 10, 80), ride(2026, 5, 1, 10, 80), ride(2024, 12, 1, 10, 80))
        assertEquals(listOf(2024, 2026), SeasonSummary.years(rides, 2026, true, 3, 10, utc))
        assertEquals(2026, SeasonSummary.currentYear(LocalDate.of(2026, 10, 6), true, 3, 10))
        assertEquals(2026, SeasonSummary.currentYear(LocalDate.of(2026, 12, 1), true, 3, 10)) // nach der Saison: die gerade beendete
        assertEquals(2025, SeasonSummary.currentYear(LocalDate.of(2026, 1, 10), true, 11, 2))
        assertTrue(SeasonSummary.currentYear(LocalDate.of(2026, 4, 1), true, 11, 2) in 2025..2026)
    }
}
