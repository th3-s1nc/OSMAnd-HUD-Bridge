package io.github.th3s1nc.osmandhudbridge.limit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Empfindlichkeit der Kurven-Warnung: 0 = wenig, 1 = normal, 2 = viel. */
object CurveLevel {
    /** Querbeschleunigung in m/s², ab der ein Kurventempo als zu schnell gilt (kleiner = früher/öfter warnen). */
    fun accel(level: Int): Double = when (level) { 0 -> 5.0; 2 -> 3.0; else -> 4.0 }
    /** Nur Kurven bis zu diesem Radius (m) zählen als "sehr scharf". */
    fun maxRadius(level: Int): Double = when (level) { 0 -> 30.0; 2 -> 60.0; else -> 45.0 }
    fun name(level: Int): String = when (level) { 0 -> "Wenig"; 2 -> "Viel"; else -> "Normal" }
}

/** Für Straßenstücke: finden, wo andere Straßen an einem Ende anschließen. */
class CurveIndex(ways: List<Way>) {
    private val byEnd = HashMap<Pair<Long, Long>, MutableList<Way>>()
    init {
        for (w in ways) {
            if (w.points.size < 2) continue
            byEnd.getOrPut(key(w.points.first())) { ArrayList(2) }.add(w)
            byEnd.getOrPut(key(w.points.last())) { ArrayList(2) }.add(w)
        }
    }
    private fun key(p: DoubleArray) = Pair(Math.round(p[0] * 1e6), Math.round(p[1] * 1e6))
    fun at(p: DoubleArray): List<Way> = byEnd[key(p)] ?: emptyList()
}

/** Die Straße vor dir als Linie in Metern (x = Ost, y = Nord, Punkt 0 = deine Position) in [STEP]-m-Abständen. */
class RoadAhead(val x: DoubleArray, val y: DoubleArray, val startTags: Map<String, String>, val lat: Double, val lon: Double)

class Bend(val apexM: Double, val radiusM: Double, val lat: Double, val lon: Double)

/**
 * Warnung vor sehr scharfen Kurven (nur Ton). Aus der Straßenform vor dir (aktuelle Straße, bei gleichem Namen auch die
 * anschließenden Stücke) wird die engste Kurve gesucht. Daraus folgt ein Kurventempo (Wurzel aus Querbeschleunigung mal Radius).
 * Ist man deutlich schneller und kann gerade noch bequem bremsen (2 s Reaktion, 2,5 m/s²), kommt einmal der Ton.
 * Nicht gewarnt wird innerorts (Ortsschild-Zone, Limit bis 50, beleuchtet, Wohn- und Zufahrtsstraßen).
 * Reine Logik ohne Android, damit sie getestet werden kann.
 */
class CurveWarn {
    private class Seen(val lat: Double, val lon: Double)
    private val warned = ArrayList<Seen>()

    /** Die Kurve, für die zuletzt gewarnt wurde (für Protokoll und Tests). */
    var lastBend: Bend? = null
        private set
    var lastSafeMs = 0.0
        private set

    fun reset() { warned.clear() }

    /**
     * [limitKmh]: aktuell erkanntes Limit (0 = unbekannt), [innerorts]: Zone laut Ortsschildern.
     * Rückgabe: true = jetzt Ton.
     */
    fun update(ways: List<Way>, index: CurveIndex, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float,
               level: Int, limitKmh: Int = 0, innerorts: Boolean = false): Boolean {
        forgetFar(lat, lon)
        if (headingDeg == null || speedMs < MIN_SPEED_MS || innerorts || (limitKmh in 1..50)) return false
        val lookahead = (speedMs * 14.0).coerceIn(150.0, MAX_LOOKAHEAD_M)
        val road = roadAhead(ways, index, lat, lon, headingDeg, lookahead) ?: return false
        if (isTownRoad(road.startTags)) return false
        val bend = sharpestBend(road, CurveLevel.maxRadius(level)) ?: return false
        // dieselbe Kurve (der engste Punkt wandert beim Näherkommen um ein paar Meter)
        val kx = M_PER_DEG * cos(Math.toRadians(lat))
        if (warned.any { hypot((it.lon - bend.lon) * kx, (it.lat - bend.lat) * M_PER_DEG) < SAME_BEND_M }) return false
        val safe = sqrt(CurveLevel.accel(level) * bend.radiusM)
        val v = speedMs.toDouble()
        if (v < safe + MARGIN_MS) return false
        val entry = bend.apexM - HALF_WINDOW_M
        val need = v * REACTION_S + (v * v - safe * safe) / (2 * BRAKE_MS2)
        if (entry > need) return false
        warned += Seen(bend.lat, bend.lon)
        lastBend = bend; lastSafeMs = safe
        return true
    }

    private fun forgetFar(lat: Double, lon: Double) {
        val kx = M_PER_DEG * cos(Math.toRadians(lat))
        val it = warned.iterator()
        while (it.hasNext()) { val s = it.next(); if (hypot((s.lon - lon) * kx, (s.lat - lat) * M_PER_DEG) > FORGET_M) it.remove() }
    }

    companion object {
        const val MIN_SPEED_MS = 11.0f // unter ca. 40 km/h keine Warnung
        const val STEP = 5.0
        const val HALF_WINDOW_M = 15.0
        const val MAX_LOOKAHEAD_M = 450.0
        const val REACTION_S = 2.0
        const val BRAKE_MS2 = 2.5
        const val MARGIN_MS = 10.0 / 3.6 // mindestens 10 km/h zu schnell
        const val FORGET_M = 500.0
        const val SAME_BEND_M = 60.0
        private const val M_PER_DEG = 111_320.0
        private const val MIN_TURN_DEG = 30.0

        private val TOWN_CLASSES = setOf("residential", "living_street", "service", "pedestrian", "track", "path", "footway", "cycleway")

        /** Straßen, auf denen nicht gewarnt wird (Ort, Wohnstraße, Zufahrt, Weg). */
        fun isTownRoad(tags: Map<String, String>): Boolean {
            if (tags["highway"] in TOWN_CLASSES) return true
            if (tags["lit"] == "yes") return true
            val ms = SpeedLimitMatcher.parseMaxspeed(tags["maxspeed"]) ?: SpeedLimitMatcher.extraTagLimit(tags)
            return ms != null && ms <= 50
        }

        private fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }

        private fun sameRoad(a: Way, b: Way): Boolean {
            if (a.tags["highway"] != b.tags["highway"]) return false
            val na = a.tags["name"]?.takeIf { it.isNotBlank() }; val nb = b.tags["name"]?.takeIf { it.isNotBlank() }
            if (na != null && na == nb) return true
            val ra = a.tags["ref"]?.takeIf { it.isNotBlank() }; val rb = b.tags["ref"]?.takeIf { it.isNotBlank() }
            return ra != null && ra == rb
        }

        /** Die Straße vor dir bis [lookaheadM]; null, wenn keine eindeutig passende Straße unter dir liegt. */
        fun roadAhead(ways: List<Way>, index: CurveIndex, lat: Double, lon: Double, headingDeg: Double, lookaheadM: Double): RoadAhead? {
            val kx = M_PER_DEG * cos(Math.toRadians(lat))
            val ky = 110_540.0
            var bestW: Way? = null; var bestI = 0; var bestT = 0.0; var bestScore = Double.MAX_VALUE; var bestFwd = true
            for (w in ways) {
                if (w.points.size < 2) continue
                for (i in 0 until w.points.size - 1) {
                    val ax = (w.points[i][1] - lon) * kx; val ay = (w.points[i][0] - lat) * ky
                    val bx = (w.points[i + 1][1] - lon) * kx; val by = (w.points[i + 1][0] - lat) * ky
                    val dx = bx - ax; val dy = by - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                    val d = hypot(ax + t * dx, ay + t * dy)
                    if (d > MAX_ON_ROAD_M) continue
                    val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                    val diff = angleDiff(headingDeg, bearing)
                    val along = minOf(diff, 180.0 - diff)
                    if (along > 40.0) continue
                    val score = d + along * 0.2
                    if (score < bestScore) { bestScore = score; bestW = w; bestI = i; bestT = t; bestFwd = diff < 90.0 }
                }
            }
            val w0 = bestW ?: return null
            val px = ArrayList<Double>(); val py = ArrayList<Double>()
            fun add(p: DoubleArray) { px += (p[1] - lon) * kx; py += (p[0] - lat) * ky }
            // erster Punkt: deine Position auf der Linie
            val a = w0.points[bestI]; val b = w0.points[bestI + 1]
            add(doubleArrayOf(a[0] + (b[0] - a[0]) * bestT, a[1] + (b[1] - a[1]) * bestT))
            var cur = w0; var fwd = bestFwd
            if (fwd) for (j in bestI + 1 until cur.points.size) add(cur.points[j]) else for (j in bestI downTo 0) add(cur.points[j])
            var length = polyLength(px, py); var hops = 0
            while (length < lookaheadM && hops < MAX_HOPS) {
                val endP = if (fwd) cur.points.last() else cur.points.first()
                val next = index.at(endP).filter { it !== cur && sameRoad(it, cur) }
                if (next.size != 1) break
                val n = next[0]
                val nFwd = sameP(n.points.first(), endP)
                if (!nFwd && !sameP(n.points.last(), endP)) break
                if (nFwd) for (j in 1 until n.points.size) add(n.points[j]) else for (j in n.points.size - 2 downTo 0) add(n.points[j])
                cur = n; fwd = nFwd; hops++
                length = polyLength(px, py)
            }
            // gleichmäßig neu abtasten
            val n = max(2, (minOf(length, lookaheadM) / STEP).toInt() + 1)
            val xs = DoubleArray(n); val ys = DoubleArray(n)
            var seg = 0; var segStart = 0.0
            for (k in 0 until n) {
                val s = k * STEP
                while (seg < px.size - 2 && segStart + hypot(px[seg + 1] - px[seg], py[seg + 1] - py[seg]) < s) {
                    segStart += hypot(px[seg + 1] - px[seg], py[seg + 1] - py[seg]); seg++
                }
                val sl = hypot(px[seg + 1] - px[seg], py[seg + 1] - py[seg])
                val f = if (sl == 0.0) 0.0 else ((s - segStart) / sl).coerceIn(0.0, 1.0)
                xs[k] = px[seg] + (px[seg + 1] - px[seg]) * f
                ys[k] = py[seg] + (py[seg + 1] - py[seg]) * f
            }
            return RoadAhead(xs, ys, w0.tags, lat, lon)
        }

        private const val MAX_ON_ROAD_M = 25.0
        private const val MAX_HOPS = 4
        private fun sameP(a: DoubleArray, b: DoubleArray) = Math.round(a[0] * 1e6) == Math.round(b[0] * 1e6) && Math.round(a[1] * 1e6) == Math.round(b[1] * 1e6)
        private fun polyLength(x: List<Double>, y: List<Double>): Double {
            var s = 0.0
            for (i in 1 until x.size) s += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            return s
        }

        /** Engste Kurve auf der Linie (Radius höchstens [maxRadius], Richtungsänderung über 60 m mindestens 30 Grad); null, wenn keine. */
        fun sharpestBend(r: RoadAhead, maxRadius: Double): Bend? {
            val k = (HALF_WINDOW_M / STEP).toInt() // 3 Punkte = 15 m
            val k2 = 2 * k
            val n = r.x.size
            var bestI = -1; var bestR = Double.MAX_VALUE
            for (i in k2..n - 1 - k2) {
                val rad = circumRadius(r.x[i - k], r.y[i - k], r.x[i], r.y[i], r.x[i + k], r.y[i + k])
                if (rad > maxRadius) continue
                val b1 = Math.toDegrees(atan2(r.x[i] - r.x[i - k2], r.y[i] - r.y[i - k2]))
                val b2 = Math.toDegrees(atan2(r.x[i + k2] - r.x[i], r.y[i + k2] - r.y[i]))
                if (angleDiff((b1 + 360) % 360, (b2 + 360) % 360) < MIN_TURN_DEG) continue
                if (i * STEP < 20.0) continue // schon mitten drin
                if (rad < bestR) { bestR = rad; bestI = i }
            }
            if (bestI < 0) return null
            val kx = M_PER_DEG * cos(Math.toRadians(r.lat)); val ky = 110_540.0
            return Bend(bestI * STEP, bestR, r.lat + r.y[bestI] / ky, r.lon + r.x[bestI] / kx)
        }

        private fun circumRadius(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            val ab = hypot(bx - ax, by - ay); val bc = hypot(cx - bx, cy - by); val ca = hypot(ax - cx, ay - cy)
            val cross = abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
            return if (cross < 1e-6) 1e6 else ab * bc * ca / (2 * cross)
        }
    }
}
