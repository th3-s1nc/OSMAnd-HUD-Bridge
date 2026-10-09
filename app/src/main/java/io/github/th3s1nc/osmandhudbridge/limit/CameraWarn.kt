package io.github.th3s1nc.osmandhudbridge.limit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Ergebnis eines Durchlaufs: Entfernung zum Blitzer vor dir (null = keiner im Warnbereich) und ob jetzt der Ton kommen soll. */
class CameraHit(val distanceM: Int?, val sound: Boolean, val limitKmh: Int = 0)

/**
 * Blitzer-Warnung aus den OSM-Daten. Gewarnt wird nur vor einem Blitzer VOR dir (Fahrtrichtung, schmaler Streifen
 * um deine Linie), nicht vor Gegenfahrbahn oder Parallelstraßen. Der Warnabstand hängt vom Tempo ab:
 * Kurz/Normal/Lang = 6/10/15 Sekunden vorher, mindestens 100 m, höchstens 600 m.
 * Pro Blitzer kommt der Ton nur einmal. Reine Logik ohne Android, damit sie getestet werden kann.
 */
class CameraWarn {
    private val warned = HashSet<Long>()

    fun reset() { warned.clear() }

    fun update(cameras: List<io.github.th3s1nc.osmandhudbridge.limit.Camera>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, level: Int): CameraHit {
        val none = CameraHit(null, false)
        if (headingDeg == null || speedMs < MIN_SPEED_MS) return none
        val lead = leadDistance(speedMs, level)
        var best: io.github.th3s1nc.osmandhudbridge.limit.Camera? = null
        var bestD = Double.MAX_VALUE
        val it = warned.iterator()
        val near = HashMap<Long, Double>()
        for (c in cameras) {
            val dN = (c.lat - lat) * M_PER_DEG
            val dE = (c.lon - lon) * M_PER_DEG * cos(Math.toRadians(lat))
            val d = hypot(dN, dE)
            near[c.id] = d
            val bearing = Math.toDegrees(atan2(dE, dN))
            val diff = angleDiff(bearing, headingDeg)
            val across = abs(d * sin(Math.toRadians(diff)))
            val ahead = abs(diff) <= MAX_ANGLE_DEG && across <= MAX_ACROSS_M
            if (ahead && d <= lead && d < bestD) { best = c; bestD = d }
        }
        // weit entfernte Blitzer wieder "scharf" machen (nur wer wirklich weit weg ist; Umkehren direkt hinter dem Blitzer warnt nicht neu)
        while (it.hasNext()) { val id = it.next(); val d = near[id]; if (d == null || d > FORGET_M) it.remove() }
        val b = best ?: return none
        val first = warned.add(b.id)
        val lim = b.tags["scdb_limit"]?.toIntOrNull() ?: b.tags["maxspeed"]?.trim()?.toIntOrNull() ?: 0
        return CameraHit(bestD.toInt(), first, lim)
    }

    companion object {
        const val MIN_SPEED_MS = 3.0f // unter ca. 11 km/h keine Warnung
        const val MAX_ANGLE_DEG = 35.0
        const val MAX_ACROSS_M = 30.0
        const val FORGET_M = 1000.0
        const val MIN_LEAD_M = 100.0
        const val MAX_LEAD_M = 600.0
        private const val M_PER_DEG = 111_320.0

        /** Der Gefahren-Bildschirm kommt nicht, wenn die nächste Abbiegung näher ist (Kreuzung: Pfeil soll sichtbar bleiben). */
        const val TURN_BLOCK_M = 300
        /** So lange (ms) zeigt das HUD den Gefahren-Bildschirm höchstens. */
        const val DANGER_SHOW_MS = 4_000L

        fun blockedByTurn(partDistanceM: Int?, hasCommand: Boolean): Boolean =
            hasCommand && partDistanceM != null && partDistanceM < TURN_BLOCK_M

        /** Sekunden vorher je Stufe: 0 = Kurz, 1 = Normal, 2 = Lang. */
        fun seconds(level: Int): Int = when (level) { 0 -> 6; 2 -> 15; else -> 10 }

        fun leadDistance(speedMs: Float, level: Int): Double =
            (speedMs * seconds(level)).toDouble().coerceIn(MIN_LEAD_M, MAX_LEAD_M)

        /** Warnabstand in m (auf 10 m gerundet) bei [kmh] für die Stufe [level]. */
        fun leadMeters(kmh: Int, level: Int): Int = (Math.round(leadDistance(kmh / 3.6f, level) / 10.0) * 10).toInt()

        /** Text unter dem Schieber: die echten Meter bei 50, 100 und 130 km/h. */
        fun leadHint(level: Int): String =
            "Die Warnung kommt etwa ${leadMeters(50, level)} m vor dem Blitzer bei 50 km/h, ${leadMeters(100, level)} m bei 100 km/h und ${leadMeters(130, level)} m bei 130 km/h. Je schneller du fährst, desto früher."

        private fun angleDiff(a: Double, b: Double): Double {
            var d = (a - b) % 360.0
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return d
        }
    }
}
