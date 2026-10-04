package io.github.th3s1nc.osmandhudbridge.limit

/**
 * Entscheidet, wann der Warnton kommt: einmal pro Überschreitung (Limit + Toleranz), erst nach [minOverMs]
 * ununterbrochener Überschreitung (kurze GPS-Ausreißer zählen nicht) und erst wieder, nachdem das Tempo
 * auf oder unter das Limit gefallen ist. Reine Logik ohne Android, damit sie getestet werden kann.
 */
class OverspeedAlarm(private val minOverMs: Long = 1_500L) {
    private var armed = true
    private var overSince = -1L

    /** @return true genau dann, wenn jetzt der Ton gespielt werden soll. limitKmh <= 0 = unbekannt (ändert nichts). */
    fun update(speedKmh: Float, limitKmh: Int, toleranceKmh: Int, nowMs: Long): Boolean {
        if (limitKmh <= 0) { overSince = -1L; return false }
        if (speedKmh <= limitKmh) armed = true
        if (speedKmh <= limitKmh + toleranceKmh) { overSince = -1L; return false }
        if (!armed) return false
        if (overSince < 0) overSince = nowMs
        if (nowMs - overSince >= minOverMs) {
            armed = false
            overSince = -1L
            return true
        }
        return false
    }

    /** Warnung ausgeschaltet oder Tempo unbrauchbar: laufende Zählung verwerfen (der Zustand "schon gewarnt" bleibt). */
    fun pause() { overSince = -1L }
}
