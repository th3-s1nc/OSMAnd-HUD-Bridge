package io.github.th3s1nc.osmandhudbridge.track

/**
 * Werte einer Fahrt für die Diagramme: je Punkt Tempo, Höhe, Schräglage, Beschleunigung. Fehlende Werte sind NaN.
 * Die Beschleunigung (m/s²) wird aus dem Tempo errechnet (Unterschied zum Nachbarpunkt, leicht geglättet);
 * über Lücken (Autopause) hinweg gibt es keine.
 */
class RideSeries(
    val speedKmh: FloatArray,
    val ele: FloatArray,
    val lean: FloatArray,
    val accel: FloatArray,
    val limit: FloatArray,
    val maxAccel: Float,
    val maxBrake: Float
) {
    val size: Int get() = speedKmh.size
}

object RideAnalysis {
    /** Lücken ab dieser Dauer zwischen zwei Punkten gelten als Pause. */
    const val GAP_MS = 5_000L

    fun series(points: List<TrackPoint>): RideSeries {
        val n = points.size
        val sp = FloatArray(n) { points[it].speedKmh }
        val ele = FloatArray(n) { points[it].ele?.toFloat() ?: Float.NaN }
        val lean = FloatArray(n) { points[it].leanDeg ?: Float.NaN }
        val limit = FloatArray(n) { if (points[it].limitKmh > 0) points[it].limitKmh.toFloat() else Float.NaN }
        val raw = FloatArray(n)
        for (i in 1 until n - 1) {
            val dt = points[i + 1].timeMs - points[i - 1].timeMs
            if (dt <= 0 || points[i].timeMs - points[i - 1].timeMs > GAP_MS || points[i + 1].timeMs - points[i].timeMs > GAP_MS) continue
            raw[i] = (sp[i + 1] - sp[i - 1]) / 3.6f / (dt / 1000f)
        }
        // leicht glätten (3 Punkte), sonst zappelt das GPS-Tempo
        val acc = FloatArray(n)
        for (i in 0 until n) {
            val a = raw.getOrElse(i - 1) { raw[i] }
            val b = raw.getOrElse(i + 1) { raw[i] }
            acc[i] = (a + raw[i] + b) / 3f
        }
        var mx = 0f
        var mn = 0f
        for (v in acc) { if (v > mx) mx = v; if (v < mn) mn = v }
        return RideSeries(sp, ele, lean, acc, limit, mx, mn)
    }

    /** Hat die Reihe mindestens einen Wert (nicht NaN)? */
    fun hasData(a: FloatArray): Boolean = a.any { !it.isNaN() }
}
