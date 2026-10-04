package io.github.th3s1nc.osmandhudbridge.protocol

/**
 * Hochrechnung der Abbiegedistanz zwischen zwei OSMAnd-Meldungen (die nur etwa alle 2 bis 3 s kommen, manchmal viel seltener).
 * Nimmt das aktuelle Tempo, wenn es brauchbar ist (ab 2 km/h), sonst die aus den Meldungen errechnete Geschwindigkeit,
 * und zieht zusätzlich die Zeit ab, die eine Meldung schon unterwegs war ([lagS]). Reine Logik ohne Android.
 */
object NavExtrapolation {
    const val LAG_S = 0.8f
    const val MAX_S = 4f
    /** Bei langer Funkstille von OSMAnd weiterrechnen, aber nur noch mit echtem GPS-Tempo. */
    const val LONG_S = 60f

    fun speedMs(speedKmh: Float, reportRateMs: Float): Float =
        if (speedKmh >= 2f) (speedKmh / 3.6f).coerceAtMost(60f) else reportRateMs.coerceIn(0f, 60f)

    /** Distanz zum Manöver jetzt, [ageS] Sekunden nach der Meldung mit [refMeters]. */
    fun distance(refMeters: Int, ageS: Float, speedKmh: Float, reportRateMs: Float, lagS: Float = LAG_S, maxS: Float = MAX_S): Int {
        val v = speedMs(speedKmh, reportRateMs)
        if (v <= 0f) return refMeters
        val total = ageS.coerceAtLeast(0f) + lagS
        var m = v * minOf(total, maxS)
        if (speedKmh >= 2f && total > maxS) m += (speedKmh / 3.6f).coerceAtMost(60f) * (minOf(total, LONG_S) - maxS)
        return maxOf(0, refMeters - m.toInt())
    }
}
