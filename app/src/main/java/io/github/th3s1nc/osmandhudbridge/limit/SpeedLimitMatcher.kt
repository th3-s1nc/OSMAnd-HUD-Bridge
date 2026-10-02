package io.github.th3s1nc.osmandhudbridge.limit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Eine OSM-Straße: Punkte als [lat, lon] und ihre Tags. */
class Way(val id: Long, val points: List<DoubleArray>, val tags: Map<String, String>)

/**
 * Ordnet eine GPS-Position einer OSM-Straße zu und liest deren Tempolimit.
 * Grundsatz: lieber kein Limit als ein falsches. Bei mehrdeutiger Zuordnung kommt null.
 */
object SpeedLimitMatcher {
    private const val MOVING_MS = 2.5f
    private const val MAX_ANGLE_ALONG = 40.0
    private const val AMBIGUITY_M = 8.0

    fun parseMaxspeed(raw: String?): Int? {
        val s = raw?.trim()?.lowercase() ?: return null
        s.toIntOrNull()?.let { return if (it in 5..200) it else null }
        Regex("""^(\d+)\s*mph$""").matchEntire(s)?.let { return (it.groupValues[1].toInt() * 1.609).roundToInt() }
        return when (s) {
            "de:urban", "at:urban", "ch:urban" -> 50
            "de:rural", "at:rural" -> 100
            "ch:rural" -> 80
            "de:bicycle_road" -> 30
            else -> null // none, signals, variable, walk, de:motorway ... -> kein Limit anzeigen
        }
    }

    private class Hit(val way: Way, val distM: Double, val bearing: Double)

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun nearest(way: Way, lat: Double, lon: Double): Hit? {
        if (way.points.size < 2) return null
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        var bestBearing = 0.0
        for (i in 0 until way.points.size - 1) {
            val ax = (way.points[i][1] - lon) * kx
            val ay = (way.points[i][0] - lat) * ky
            val bx = (way.points[i + 1][1] - lon) * kx
            val by = (way.points[i + 1][0] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) {
                best = d
                bestBearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
            }
        }
        return Hit(way, best, bestBearing)
    }

    class MatchResult(val limit: Int?, val street: String?)

    private class Cand(val score: Double, val limit: Int?, val street: String?)

    private fun candidates(ways: List<Way>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, accuracyM: Float): List<Cand> {
        val maxDist = (accuracyM * 1.5).coerceIn(15.0, 35.0)
        val moving = headingDeg != null && speedMs > MOVING_MS
        val cands = ArrayList<Cand>()
        for (w in ways) {
            val h = nearest(w, lat, lon) ?: continue
            if (h.distM > maxDist) continue
            var score = h.distM
            var forward: Boolean? = null
            if (moving) {
                val diff = angleDiff(headingDeg!!, h.bearing)
                val along = minOf(diff, 180.0 - diff)
                if (along > MAX_ANGLE_ALONG) continue // kreuzende Straße
                score += along * 0.2
                forward = diff < 90.0
            }
            val dir = when (forward) {
                true -> parseMaxspeed(w.tags["maxspeed:forward"])
                false -> parseMaxspeed(w.tags["maxspeed:backward"])
                null -> null
            }
            val name = (w.tags["name"] ?: w.tags["ref"])?.takeIf { it.isNotBlank() }
            cands += Cand(score, dir ?: parseMaxspeed(w.tags["maxspeed"]), name)
        }
        cands.sortBy { it.score }
        return cands
    }

    /** Tempolimit und Strassenname der aktuellen Strasse; jeweils null, wenn nicht eindeutig. */
    fun matchAll(ways: List<Way>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, accuracyM: Float): MatchResult {
        val cands = candidates(ways, lat, lon, headingDeg, speedMs, accuracyM)
        if (cands.isEmpty()) return MatchResult(null, null)
        val best = cands[0]
        val limitClash = cands.drop(1).any { it.score - best.score < AMBIGUITY_M && it.limit != best.limit }
        val nameClash = cands.drop(1).any { it.score - best.score < AMBIGUITY_M && it.street != best.street }
        return MatchResult(if (limitClash) null else best.limit, if (nameClash) null else best.street)
    }

    /** headingDeg: Fahrtrichtung (null = unbekannt), speedMs: Tempo in m/s, accuracyM: GPS-Genauigkeit. */
    fun match(ways: List<Way>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, accuracyM: Float): Int? =
        matchAll(ways, lat, lon, headingDeg, speedMs, accuracyM).limit
}

/** Glättet die Treffer: neues Limit erst nach zwei gleichen Treffern, kurze Lücken werden überbrückt. */
class LimitTracker(private val confirmCount: Int = 2, private val holdMs: Long = 8_000) {
    var current: Int = 0
        private set
    private var cand: Int? = null
    private var candN = 0
    private var lastGoodAt = 0L

    fun update(raw: Int?, nowMs: Long): Int {
        if (raw != null) {
            if (raw == current) {
                cand = null; candN = 0; lastGoodAt = nowMs
            } else {
                if (raw == cand) candN++ else { cand = raw; candN = 1 }
                if (candN >= confirmCount) {
                    current = raw; cand = null; candN = 0; lastGoodAt = nowMs
                }
            }
        } else {
            cand = null; candN = 0
            if (current != 0 && nowMs - lastGoodAt > holdMs) current = 0
        }
        return current
    }

    fun reset() {
        current = 0; cand = null; candN = 0
    }
}
