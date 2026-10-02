package io.github.th3s1nc.osmandhudbridge.limit

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Eine OSM-Straße: Punkte als [lat, lon] und ihre Tags. */
class Way(val id: Long, val points: List<DoubleArray>, val tags: Map<String, String>)

/** Ein Ortsschild (OSM-Punkt mit traffic_sign=city_limit). */
class Sign(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>)

/** Inhalt einer Kachel. */
class TileData(val ways: List<Way>, val signs: List<Sign>)

/** Innerorts oder außerorts, so wie es das zuletzt passierte Ortsschild sagt. */
enum class Zone { INNER, OUTER }

/** Schalter für die Zuordnung. */
class MatchOptions(
    /** Zusätzliche Karten-Hinweise (Tempo-Zone, Quellenangabe) als echtes Limit nutzen. */
    val extraTags: Boolean = false,
    /** Fehlendes Limit schätzen (gilt auch für Nachbarabschnitte und Ortsschilder). */
    val guess: Boolean = false,
    val neighbors: NeighborIndex? = null,
    val zone: Zone? = null
)

/** Findet bei einer Straße ohne Limit das Limit der anschließenden Abschnitte derselben Straße (gleicher Name oder Ref). */
class NeighborIndex(ways: List<Way>) {
    private val byEnd = HashMap<Pair<Long, Long>, MutableList<Way>>()

    init {
        for (w in ways) {
            if (w.points.size < 2) continue
            for (p in listOf(w.points.first(), w.points.last())) byEnd.getOrPut(key(p)) { ArrayList(2) }.add(w)
        }
    }

    private fun key(p: DoubleArray) = Pair(Math.round(p[0] * 1e6), Math.round(p[1] * 1e6))

    private fun roadName(w: Way): String? = (w.tags["name"] ?: w.tags["ref"])?.takeIf { it.isNotBlank() }

    /** Limit der Nachbarabschnitte (nur wenn eindeutig), sonst null. */
    fun limitFor(w: Way): Int? {
        val name = roadName(w) ?: return null
        if (w.points.size < 2) return null
        val found = HashSet<Int>()
        for (p in listOf(w.points.first(), w.points.last())) {
            for (n in byEnd[key(p)] ?: continue) {
                if (n === w || roadName(n) != name) continue
                SpeedLimitMatcher.parseMaxspeed(n.tags["maxspeed"])?.let { found += it }
            }
        }
        return if (found.size == 1) found.first() else null
    }
}

/**
 * Merkt sich anhand der passierten Ortsschilder, ob man innerorts oder außerorts fährt.
 * Richtungsangabe des Schildes: "forward"/"backward" (zeigt stadteinwärts, relativ zur Straßenrichtung) oder ein Winkel/eine
 * Himmelsrichtung (zeigt stadtauswärts, das Schild schaut also den Einfahrenden an). Schilder ohne brauchbare Richtung
 * werden ignoriert. Nach [expireM] Metern ohne neues Schild gilt die Zone als unbekannt.
 */
class SignTracker(private val expireM: Double = 2_500.0) {
    var zone: Zone? = null
        private set
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var travelled = 0.0
    private val used = HashMap<Long, Long>()

    fun reset() { zone = null; travelled = 0.0; lastLat = Double.NaN; used.clear() }

    /** Gibt eine Protokollzeile zurück, wenn gerade ein Schild ausgewertet wurde. */
    fun update(lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, signs: List<Sign>, ways: List<Way>, nowMs: Long): String? {
        if (!lastLat.isNaN()) {
            val dx = (lon - lastLon) * 111_320.0 * cos(Math.toRadians(lat))
            val dy = (lat - lastLat) * 110_540.0
            travelled += hypot(dx, dy)
        }
        lastLat = lat; lastLon = lon
        if (zone != null && travelled > expireM) zone = null
        if (headingDeg == null || speedMs < 2f || signs.isEmpty()) return null
        val radius = maxOf(25.0, speedMs * 1.5)
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        for (s in signs) {
            if (hypot((s.lon - lon) * kx, (s.lat - lat) * 110_540.0) > radius) continue
            val last = used[s.id]
            if (last != null && nowMs - last < 120_000) continue
            val inward = inwardTravel(s, ways) ?: continue
            val diff = angleDiff(headingDeg, inward)
            val entering = when {
                diff < 60.0 -> true
                diff > 120.0 -> false
                else -> continue
            }
            used[s.id] = nowMs
            zone = if (entering) Zone.INNER else Zone.OUTER
            travelled = 0.0
            return "Ortsschild ${s.tags["name"] ?: ""}: ${if (entering) "Eingang, innerorts" else "Ausgang, außerorts"}".replace("  ", " ")
        }
        return null
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Fahrtrichtung (Grad), mit der man durch dieses Schild in den Ort hineinfährt; null = unklar. */
    private fun inwardTravel(s: Sign, ways: List<Way>): Double? {
        val dir = (s.tags["direction"] ?: s.tags["traffic_sign:direction"])?.trim()?.lowercase() ?: return null
        if (dir == "forward" || dir == "backward") {
            val b = SpeedLimitMatcher.wayBearingAt(ways, s.lat, s.lon, 20.0) ?: return null
            return if (dir == "forward") b else (b + 180.0) % 360.0
        }
        val deg = dir.toDoubleOrNull() ?: CARDINAL[dir] ?: return null
        return (deg + 180.0) % 360.0
    }

    private companion object {
        val CARDINAL = mapOf(
            "n" to 0.0, "nne" to 22.5, "ne" to 45.0, "ene" to 67.5, "e" to 90.0, "ese" to 112.5, "se" to 135.0, "sse" to 157.5,
            "s" to 180.0, "ssw" to 202.5, "sw" to 225.0, "wsw" to 247.5, "w" to 270.0, "wnw" to 292.5, "nw" to 315.0, "nnw" to 337.5
        )
    }
}

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
        Regex("""^[a-z]{2}:(\d{1,3})$""").matchEntire(s)?.let { m -> m.groupValues[1].toInt().let { v -> return if (v in 5..200) v else null } }
        return when (s) {
            "de:urban", "at:urban", "ch:urban" -> 50
            "de:rural", "at:rural" -> 100
            "ch:rural" -> 80
            "de:bicycle_road" -> 30
            else -> null // none, signals, variable, walk, de:motorway ... -> kein Limit anzeigen
        }
    }

    /** Geschätzte Limits werden im Tracker als Wert + dieser Offset geführt, damit "50 echt" und "50 geschätzt" verschieden sind. */
    const val ESTIMATE_OFFSET = 1000

    /** Gibt es ein ausdrückliches Tempo-Tag (auch "none"/"signals")? Dann wird nie geraten. */
    fun hasExplicitMaxspeed(tags: Map<String, String>): Boolean =
        tags.keys.any { it.startsWith("maxspeed") && it != "maxspeed:type" }

    /**
     * Zusätzliche Hinweise der Karte, wenn kein Zahlen-Limit eingetragen ist: "zone:maxspeed=DE:30" (Tempo-Zone) sowie
     * "maxspeed:type" / "source:maxspeed" (DE:urban = 50, DE:rural = 100, DE:zone30 = 30, DE:bicycle_road = 30).
     */
    fun extraTagLimit(tags: Map<String, String>): Int? {
        tags["zone:maxspeed"]?.trim()?.let { v ->
            Regex("^[A-Za-z]{2}:(\\d{1,3})$").matchEntire(v)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 5..60 }?.let { return it }
        }
        for (k in listOf("maxspeed:type", "source:maxspeed")) {
            val v = tags[k]?.trim()?.lowercase() ?: continue
            parseMaxspeed(v)?.let { return it }
            Regex("zone:?(\\d{2})$").find(v)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 5..60 }?.let { return it }
        }
        return null
    }

    /**
     * Annahme für Straßen ganz ohne Tempo-Tag (Deutschland). Ohne Zone: Wohnstraße 50, beleuchtete Straße 50, unbeleuchtete
     * Straße der Klassen primary/secondary/tertiary/unclassified 100. Mit Zone (aus Ortsschildern): innerorts 50 (außer Autobahn,
     * trunk, Zufahrten, Verbindungsstücke, Wohn-/Spielstraße), außerorts 100 für primary bis unclassified. null = keine Annahme.
     */
    fun guessLimit(tags: Map<String, String>, zone: Zone? = null): Int? {
        if (hasExplicitMaxspeed(tags)) return null
        val hw = tags["highway"]
        return when (zone) {
            Zone.INNER -> when (hw) {
                "residential", "unclassified", "tertiary", "secondary", "primary", "road" -> 50
                else -> null
            }
            Zone.OUTER -> when (hw) {
                "primary", "secondary", "tertiary", "unclassified" -> 100
                "residential" -> 50
                else -> null
            }
            null -> when (hw) {
                "residential" -> 50
                "primary", "secondary", "tertiary", "unclassified" -> if (tags["lit"] == "yes") 50 else 100
                else -> null
            }
        }
    }

    /** Fahrtrichtung der nächsten Straße (Richtung der Linie, 0 = Nord) an dieser Stelle; null, wenn keine Straße näher als [maxDistM]. */
    fun wayBearingAt(ways: List<Way>, lat: Double, lon: Double, maxDistM: Double): Double? =
        ways.mapNotNull { nearest(it, lat, lon) }.filter { it.distM <= maxDistM }.minByOrNull { it.distM }?.bearing

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

    class MatchResult(val limit: Int?, val street: String?, val noCandidates: Boolean = false)

    private class Cand(val score: Double, val limit: Int?, val street: String?)

    private fun candidates(ways: List<Way>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, accuracyM: Float, opts: MatchOptions): List<Cand> {
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
            var lim = dir ?: parseMaxspeed(w.tags["maxspeed"])
            if (lim == null && opts.extraTags && !hasExplicitMaxspeed(w.tags)) lim = extraTagLimit(w.tags)
            if (lim == null && opts.guess && !hasExplicitMaxspeed(w.tags)) {
                val nb = opts.neighbors?.limitFor(w)
                lim = if (nb != null) nb + ESTIMATE_OFFSET else guessLimit(w.tags, opts.zone)?.let { it + ESTIMATE_OFFSET }
            }
            cands += Cand(score, lim, name)
        }
        cands.sortBy { it.score }
        return cands
    }

    /** Tempolimit und Strassenname der aktuellen Strasse; jeweils null, wenn nicht eindeutig. */
    fun matchAll(
        ways: List<Way>, lat: Double, lon: Double, headingDeg: Double?, speedMs: Float, accuracyM: Float,
        preferStreet: String? = null, opts: MatchOptions = MatchOptions()
    ): MatchResult {
        val cands = candidates(ways, lat, lon, headingDeg, speedMs, accuracyM, opts)
        if (cands.isEmpty()) return MatchResult(null, null, noCandidates = true)
        val best = cands[0]
        // Kreuzung / Parallelstraße: bei fast gleichem Abstand gewinnt die Straße, auf der wir schon fahren
        if (preferStreet != null) {
            val tied = cands.filter { it.score - best.score < AMBIGUITY_M }
            if (tied.size > 1) {
                val same = tied.filter { it.street == preferStreet }
                if (same.isNotEmpty() && same.all { it.limit == same[0].limit }) return MatchResult(same[0].limit, same[0].street)
            }
        }
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
    companion object {
        /** Gleiche Straße (gleicher Name/Ref) ohne eigenes Limit-Tag im Abschnitt: Limit lange behalten. */
        const val HOLD_SAME_ROAD_MS = 90_000L
        /** Gar keine Straße in der Nähe (GPS-Ausreißer, Tunnel): etwas länger überbrücken als sonst. */
        const val HOLD_NO_MATCH_MS = 15_000L
    }

    var current: Int = 0
        private set
    private var cand: Int? = null
    private var candN = 0
    private var lastGoodAt = 0L

    /** [holdOverrideMs]: Haltezeit für diesen Aufruf (sonst die Standardzeit). */
    fun update(raw: Int?, nowMs: Long, holdOverrideMs: Long? = null): Int {
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
            if (current != 0 && nowMs - lastGoodAt > (holdOverrideMs ?: holdMs)) current = 0
        }
        return current
    }

    fun reset() {
        current = 0; cand = null; candN = 0
    }
}
