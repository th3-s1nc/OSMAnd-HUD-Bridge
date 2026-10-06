package io.github.th3s1nc.osmandhudbridge.track

import io.github.th3s1nc.osmandhudbridge.protocol.TripTracker

/**
 * Ein aufgezeichneter Punkt. [timeMs] = Unix-Zeit in Millisekunden, [ele] = Höhe in Metern (null = unbekannt),
 * [limitKmh] = Tempolimit (0 = unbekannt oder nicht aufgezeichnet), [leanDeg] = geschätzte Schräglage in Grad
 * (rechts positiv, null = unbekannt oder nicht aufgezeichnet).
 */
data class TrackPoint(
    val timeMs: Long, val lat: Double, val lon: Double, val ele: Double?, val speedKmh: Float,
    val limitKmh: Int = 0, val leanDeg: Float? = null
)

/**
 * Zählt Tempoüberschreitungen: "zu schnell" beginnt ab Limit + [MARGIN_KMH] und endet erst, wenn man wieder höchstens so
 * schnell wie das Limit fährt. Jeder Beginn zählt als eine Überschreitung.
 */
class OverTracker {
    var count = 0
        private set
    var over = false
        private set

    /** Gibt zurück, ob der Punkt zu schnell ist. */
    fun update(speedKmh: Float, limitKmh: Int): Boolean {
        if (limitKmh <= 0) { over = false; return false }
        if (!over && speedKmh > limitKmh + MARGIN_KMH) { over = true; count++ }
        else if (over && speedKmh <= limitKmh) over = false
        return over
    }

    companion object { const val MARGIN_KMH = 3 }
}

/** Kennzahlen einer Fahrt. Zeiten in Millisekunden, Strecke in Metern. */
data class RideStats(
    val startMs: Long,
    val endMs: Long,
    val movingMs: Long,
    val distanceM: Int,
    val maxKmh: Int,
    val points: Int,
    val ascentM: Int = 0,
    val descentM: Int = 0,
    /** Anzahl der Überschreitungen. */
    val overCount: Int = 0,
    val maxLeanDeg: Int = 0,
    val hasEle: Boolean = false,
    val hasLimit: Boolean = false,
    val hasLean: Boolean = false
) {
    val totalMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** Durchschnitt in km/h über die Zeit in Bewegung. */
    val avgKmh: Int get() = if (movingMs <= 0) 0 else Math.round(distanceM / 1000.0 / (movingMs / 3_600_000.0)).toInt()
}

/**
 * Sammelt Punkte einer Fahrt, mit Autopause: Steht man [PAUSE_AFTER_MS] lang (unter [STILL_KMH]), pausiert die Aufzeichnung,
 * ab [RESUME_KMH] geht sie von selbst weiter. Reine Rechnung ohne Android, deshalb testbar.
 */
class TrackSession {
    private val list = ArrayList<TrackPoint>()
    private var distM = 0.0
    private var movingMs = 0L
    private var maxKmh = 0f
    private var lastMoveMs = 0L
    private var prev: TrackPoint? = null
    private var ascent = 0.0
    private var descent = 0.0
    private var refEle: Double? = null
    private var over = OverTracker()
    private var maxLean = 0f
    private var hasEle = false
    private var hasLimit = false
    private var hasLean = false

    var paused = false
        private set

    val points: List<TrackPoint> get() = list

    /** Gibt true zurück, wenn der Punkt aufgenommen wurde. */
    fun onFix(p: TrackPoint): Boolean {
        if (list.isEmpty() && lastMoveMs == 0L) lastMoveMs = p.timeMs
        if (paused) {
            if (p.speedKmh <= RESUME_KMH) return false
            paused = false
            lastMoveMs = p.timeMs
            prev = null // über die Pause hinweg keine Strecke zählen
        } else if (p.speedKmh >= STILL_KMH) {
            lastMoveMs = p.timeMs
        } else if (p.timeMs - lastMoveMs >= PAUSE_AFTER_MS) {
            paused = true
            return false
        }
        val q = prev
        if (q != null && p.timeMs - q.timeMs < MIN_INTERVAL_MS) return false
        add(p)
        return true
    }

    /** Stellt den Stand aus gespeicherten Punkten wieder her (nach einer Unterbrechung). */
    fun restore(saved: List<TrackPoint>) {
        list.clear(); distM = 0.0; movingMs = 0L; maxKmh = 0f; prev = null; paused = false
        ascent = 0.0; descent = 0.0; refEle = null; over = OverTracker(); maxLean = 0f
        hasEle = false; hasLimit = false; hasLean = false
        for (p in saved) add(p)
        lastMoveMs = saved.lastOrNull()?.timeMs ?: 0L
    }

    private fun add(p: TrackPoint) {
        val q = prev
        if (q != null) {
            val dt = p.timeMs - q.timeMs
            val d = TripTracker.meters(q.lat, q.lon, p.lat, p.lon)
            if (p.speedKmh >= STILL_KMH && d in 0.5..500.0) distM += d
            if (p.speedKmh >= STILL_KMH && dt in 1..10_000) movingMs += dt
        }
        if (p.speedKmh > maxKmh) maxKmh = p.speedKmh
        p.ele?.let { e ->
            hasEle = true
            val r = refEle
            if (r == null) refEle = e
            else if (e - r >= ELE_STEP_M) { ascent += e - r; refEle = e }
            else if (r - e >= ELE_STEP_M) { descent += r - e; refEle = e }
        }
        if (p.limitKmh > 0) hasLimit = true
        over.update(p.speedKmh, p.limitKmh)
        p.leanDeg?.let { hasLean = true; if (Math.abs(it) > maxLean) maxLean = Math.abs(it) }
        list += p
        prev = p
    }

    fun stats(): RideStats {
        val first = list.firstOrNull()?.timeMs ?: 0L
        val last = list.lastOrNull()?.timeMs ?: 0L
        return RideStats(
            first, last, movingMs, distM.toInt(), maxKmh.toInt(), list.size,
            ascent.toInt(), descent.toInt(), over.count, Math.round(maxLean), hasEle, hasLimit, hasLean
        )
    }

    companion object {
        const val STILL_KMH = 2f
        const val RESUME_KMH = 5f
        const val PAUSE_AFTER_MS = 180_000L
        const val MIN_INTERVAL_MS = 1_000L

        /** Höhenänderungen unter diesem Wert zählen nicht als Auf- oder Abstieg (Rauschen). */
        const val ELE_STEP_M = 3.0
    }
}
