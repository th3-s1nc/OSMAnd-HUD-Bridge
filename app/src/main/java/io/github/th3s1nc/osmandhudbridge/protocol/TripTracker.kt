package io.github.th3s1nc.osmandhudbridge.protocol

/**
 * Rechnet aus GPS-Meldungen die Werte für den Tracking-Testmodus: gefahrene Strecke, gefahrene Zeit, Höhe.
 * Reine Rechnung ohne Android, deshalb testbar. Zeiten in Millisekunden.
 */
class TripTracker {
    private var startMs = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var distM = 0.0
    private var alt: Double? = null

    val distanceM: Int get() = distM.toInt()

    /** Höhe, geglättet, in Metern; null solange keine Höhe gemeldet wurde. */
    val elevationM: Int? get() = alt?.let { Math.round(it).toInt() }

    fun minutes(nowMs: Long): Int = if (startMs == 0L) 0 else ((nowMs - startMs) / 60_000L).toInt().coerceAtLeast(0)

    fun reset() {
        startMs = 0L
        lastLat = Double.NaN
        lastLon = Double.NaN
        distM = 0.0
        alt = null
    }

    /** [accuracyM] null = unbekannt; sehr ungenaue Fixe werden ignoriert. [altitudeM] null = keine Höhe. */
    fun onFix(nowMs: Long, lat: Double, lon: Double, altitudeM: Double?, speedKmh: Float, accuracyM: Float?) {
        if (accuracyM != null && accuracyM > 50f) return
        if (startMs == 0L) startMs = nowMs
        if (!lastLat.isNaN()) {
            val d = meters(lastLat, lastLon, lat, lon)
            // Stand und GPS-Rauschen zählen nicht mit
            if (speedKmh >= 3f && d in 1.0..500.0) distM += d
        }
        lastLat = lat
        lastLon = lon
        if (altitudeM != null) alt = alt?.let { it + (altitudeM - it) * 0.2 } ?: altitudeM
    }

    companion object {
        fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return 2 * r * Math.asin(Math.sqrt(a))
        }
    }
}
