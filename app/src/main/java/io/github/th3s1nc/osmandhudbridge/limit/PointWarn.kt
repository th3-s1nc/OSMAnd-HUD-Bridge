package io.github.th3s1nc.osmandhudbridge.limit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Ein Punkt, vor dem nur mit einem Ton gewarnt wird (kommt aus importierten Straßendaten). [kind] ist eines der Bits aus [PointKind]. */
class WarnPoint(val id: Long, val lat: Double, val lon: Double, val kind: Int)

object PointKind {
    const val LEVEL_CROSSING = 1
    const val ZEBRA = 2
    const val CALMING = 4
    /** Nur für den Ton: scharfe Kurve (kommt aus [CurveWarn], nicht aus einem Kartenpunkt). */
    const val CURVE = 8

    /** Aus den Tags eines Kartenpunkts die Art bestimmen; 0 = kein Warnpunkt. */
    fun of(tags: Map<String, String>): Int = when {
        tags["railway"] == "level_crossing" -> LEVEL_CROSSING
        tags["highway"] == "crossing" && (tags["crossing"] == "zebra" || tags["crossing:markings"] == "zebra") -> ZEBRA
        tags["traffic_calming"]?.let { it.isNotBlank() && it != "no" } == true -> CALMING
        else -> 0
    }
}

/**
 * Warnton vor Bahnübergang, Zebrastreifen und Verkehrsberuhigung (nur Ton, keine Anzeige am HUD). Wie bei den Blitzern zählt nur,
 * was VOR dir liegt (Fahrtrichtung, schmaler Streifen um deine Linie). Der Ton kommt einmal pro Punkt, und zwar so früh,
 * dass man in Ruhe anhalten könnte: 2 s Reaktionszeit plus Bremsen mit 2,5 m/s². Bahnübergang und Verkehrsberuhigung
 * brauchen nur ein Langsamerwerden (60 % davon). Ein Ton gilt für alle Punkte, die gerade in Reichweite sind (Zebrastreifen
 * mit Verkehrsinsel nicht doppelt). Reine Logik ohne Android, damit sie getestet werden kann.
 */
class PointWarn {
    private val warned = HashSet<Long>()

    fun reset() { warned.clear() }

    /** [mask]: welche Arten gewarnt werden sollen (Bits aus [PointKind]). Rückgabe: Art des Tons oder 0 = kein Ton. */
    fun update(points: List<WarnPoint>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, mask: Int): Int {
        if (mask == 0 || headingDeg == null || speedMs < MIN_SPEED_MS) return 0
        val cosLat = cos(Math.toRadians(lat))
        var bestKind = 0
        var bestD = Double.MAX_VALUE
        val inReach = ArrayList<Long>()
        val dists = HashMap<Long, Double>()
        for (p in points) {
            val dN = (p.lat - lat) * M_PER_DEG
            val dE = (p.lon - lon) * M_PER_DEG * cosLat
            val d = hypot(dN, dE)
            dists[p.id] = d
            if (p.kind and mask == 0 || p.id in warned) continue
            val diff = angleDiff(Math.toDegrees(atan2(dE, dN)), headingDeg)
            val across = abs(d * sin(Math.toRadians(diff)))
            if (abs(diff) > MAX_ANGLE_DEG || across > MAX_ACROSS_M) continue
            if (d <= leadDistance(speedMs, p.kind)) {
                inReach += p.id
                if (d < bestD) { bestD = d; bestKind = p.kind }
            }
        }
        // weit entfernte Punkte wieder "scharf" machen (Umkehren direkt hinter dem Punkt warnt nicht neu)
        val it = warned.iterator()
        while (it.hasNext()) { val d = dists[it.next()]; if (d == null || d > FORGET_M) it.remove() }
        if (bestKind == 0) return 0
        warned.addAll(inReach)
        return bestKind
    }

    companion object {
        const val MIN_SPEED_MS = 5.5f // unter ca. 20 km/h keine Warnung
        const val MAX_ANGLE_DEG = 35.0
        const val MAX_ACROSS_M = 25.0
        const val FORGET_M = 600.0
        const val MIN_LEAD_M = 40.0
        const val MAX_LEAD_M = 250.0
        private const val M_PER_DEG = 111_320.0

        /** Abstand in m, ab dem der Ton kommt. */
        fun leadDistance(speedMs: Float, kind: Int): Double {
            val stop = speedMs * 2.0 + speedMs.toDouble() * speedMs / (2 * 2.5)
            val f = if (kind == PointKind.ZEBRA) 1.0 else 0.6
            return (stop * f).coerceIn(MIN_LEAD_M, MAX_LEAD_M)
        }

        private fun angleDiff(a: Double, b: Double): Double {
            var d = (a - b) % 360.0
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return d
        }
    }
}
