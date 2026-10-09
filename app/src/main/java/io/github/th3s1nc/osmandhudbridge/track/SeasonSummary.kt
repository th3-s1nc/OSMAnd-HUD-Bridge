package io.github.th3s1nc.osmandhudbridge.track

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Saison-Übersicht über die gespeicherten Fahrten. Eine Saison wird nach ihrem Startjahr benannt: bei März bis Oktober
 * ist "2026" der März 2026 bis Oktober 2026, bei November bis Februar der November 2025 bis Februar 2026 (Jahr 2025).
 * Ist die Saison-Einstellung aus, zählt das Kalenderjahr. Reine Rechnung ohne Android.
 */
object SeasonSummary {
    class Period(val year: Int, val first: YearMonth, val last: YearMonth) {
        val months: List<YearMonth> get() {
            val out = ArrayList<YearMonth>()
            var m = first
            while (!m.isAfter(last)) { out += m; m = m.plusMonths(1) }
            return out
        }
        fun contains(ym: YearMonth) = !ym.isBefore(first) && !ym.isAfter(last)
        val wraps: Boolean get() = last.year != first.year
        /** Beschriftung: "2026", bei Jahreswechsel "2025/26". */
        val label: String get() = if (wraps) "${first.year}/${(last.year % 100).toString().padStart(2, '0')}" else "${first.year}"
    }

    /** Zeitraum der Saison mit Startjahr [year]. [seasonOn] aus = Kalenderjahr. */
    fun period(year: Int, seasonOn: Boolean, fromMonth: Int, toMonth: Int): Period {
        if (!seasonOn) return Period(year, YearMonth.of(year, 1), YearMonth.of(year, 12))
        val f = fromMonth.coerceIn(1, 12)
        val t = toMonth.coerceIn(1, 12)
        val endYear = if (f <= t) year else year + 1
        return Period(year, YearMonth.of(year, f), YearMonth.of(endYear, t))
    }

    /** Startjahr der Saison, in die [ym] fällt; null, wenn der Monat in keiner Saison liegt. */
    fun yearOf(ym: YearMonth, seasonOn: Boolean, fromMonth: Int, toMonth: Int): Int? {
        for (y in intArrayOf(ym.year, ym.year - 1)) if (period(y, seasonOn, fromMonth, toMonth).contains(ym)) return y
        return null
    }

    class Month(val ym: YearMonth, val distanceM: Long)

    class Summary(
        val period: Period,
        val rides: Int,
        val distanceM: Long,
        val movingMs: Long,
        val maxKmh: Int,
        /** Überschreitungen insgesamt und Zahl der Fahrten mit mindestens einer. */
        val overCount: Int,
        val ridesWithOver: Int,
        /** Fahrten, bei denen Tempolimits bekannt waren (nur dort wird gezählt). */
        val ridesWithLimit: Int,
        val months: List<Month>
    )

    fun summarize(rides: List<RideStats>, period: Period, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val dist = HashMap<YearMonth, Long>()
        var n = 0; var d = 0L; var mv = 0L; var mx = 0; var over = 0; var withOver = 0; var withLimit = 0
        for (r in rides) {
            val ym = YearMonth.from(Instant.ofEpochMilli(r.startMs).atZone(zone))
            if (!period.contains(ym)) continue
            n++; d += r.distanceM; mv += r.movingMs
            if (r.maxKmh > mx) mx = r.maxKmh
            if (r.hasLimit) { withLimit++; over += r.overCount; if (r.overCount > 0) withOver++ }
            dist[ym] = (dist[ym] ?: 0L) + r.distanceM
        }
        return Summary(period, n, d, mv, mx, over, withOver, withLimit, period.months.map { Month(it, dist[it] ?: 0L) })
    }

    /** Startjahre aller Saisons mit Fahrten, aufsteigend; [current] ist immer dabei. */
    fun years(rides: List<RideStats>, current: Int, seasonOn: Boolean, fromMonth: Int, toMonth: Int, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
        val out = sortedSetOf(current)
        for (r in rides) {
            val ym = YearMonth.from(Instant.ofEpochMilli(r.startMs).atZone(zone))
            yearOf(ym, seasonOn, fromMonth, toMonth)?.let { out += it }
        }
        return out.toList()
    }

    /** Saison, die heute läuft (oder zuletzt lief): im Jahreswechsel-Fall das Vorjahr bis zum Ende der Saison. */
    fun currentYear(today: LocalDate, seasonOn: Boolean, fromMonth: Int, toMonth: Int): Int {
        val ym = YearMonth.from(today)
        return yearOf(ym, seasonOn, fromMonth, toMonth)
            ?: if (ym.monthValue > toMonth.coerceIn(1, 12) && fromMonth <= toMonth) ym.year else ym.year - (if (ym.monthValue < fromMonth.coerceIn(1, 12)) 1 else 0)
    }
}
