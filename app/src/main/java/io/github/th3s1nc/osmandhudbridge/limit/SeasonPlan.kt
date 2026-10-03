package io.github.th3s1nc.osmandhudbridge.limit

import java.time.LocalDate

/**
 * Saison von Monat zu Monat (zum Beispiel März bis Oktober, auch über den Jahreswechsel wie November bis Februar).
 * IN_SEASON: die App läuft normal. LEAD: außerhalb, aber in den [leadWeeks] Wochen vor Saisonbeginn, dann werden
 * Straßendaten geladen. OFF: die App ruht. Reine Logik ohne Android.
 */
class SeasonPlan(val fromMonth: Int, val toMonth: Int, val leadWeeks: Int) {
    enum class State { IN_SEASON, LEAD, OFF }

    private val from = fromMonth.coerceIn(1, 12)
    private val to = toMonth.coerceIn(1, 12)

    fun inSeason(d: LocalDate): Boolean = if (from <= to) d.monthValue in from..to else d.monthValue >= from || d.monthValue <= to

    /** Der nächste Saisonbeginn (1. des Von-Monats) ab [d]; null, wenn gerade Saison ist. */
    fun nextStart(d: LocalDate): LocalDate? {
        if (inSeason(d)) return null
        var s = LocalDate.of(d.year, from, 1)
        if (!s.isAfter(d)) s = s.plusYears(1)
        return s
    }

    /** Ab wann im Vorlauf geladen wird; null, wenn gerade Saison ist. */
    fun loadStart(d: LocalDate): LocalDate? = nextStart(d)?.minusWeeks(leadWeeks.toLong())

    fun state(d: LocalDate): State {
        if (inSeason(d)) return State.IN_SEASON
        val ls = loadStart(d) ?: return State.OFF
        return if (leadWeeks > 0 && !d.isBefore(ls)) State.LEAD else State.OFF
    }
}
