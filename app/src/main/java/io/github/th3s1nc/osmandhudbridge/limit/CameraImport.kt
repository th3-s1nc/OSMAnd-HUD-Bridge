package io.github.th3s1nc.osmandhudbridge.limit

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** Art eines importierten Blitzers. */
object CameraKind {
    const val SPEED = 0      // Tempoblitzer (auch "Kamera" und "Tempo variabel")
    const val RED_LIGHT = 1  // Ampelblitzer
    const val SECTION = 2    // Abschnittskontrolle
    const val TUNNEL = 3     // Tunnel
}

/** Ein Blitzer aus einer eigenen Liste (zum Beispiel SCDB als GPX). [limitKmh] 0 = unbekannt oder variabel. */
class ImportedCamera(val id: Long, val lat: Double, val lon: Double, val kind: Int, val limitKmh: Int)

/**
 * Eigene Blitzer-Liste: GPX lesen (Waypoints; bei SCDB steht Art und Limit im Kategorienamen, zum Beispiel
 * "SCDB_Tempo_50", "SCDB_Ampel_30", "SCDB_Abschnitt_80", "SCDB_Tunnel"), Doppelte zusammenlegen, als kleine
 * Textdatei speichern. Reine Logik ohne Android, damit sie getestet werden kann.
 */
object CameraImport {
    /** Zwischenergebnis: [generic] = nur die allgemeine "Kamera"-Kategorie ohne Art und Limit. */
    class Parsed(val id: Long, val lat: Double, val lon: Double, val kind: Int, val limitKmh: Int, val generic: Boolean) {
        val score: Int get() = (if (generic) 0 else 4) + (if (limitKmh > 0) 2 else 0)
    }

    const val MERGE_M = 30.0
    private val WPT = Regex("<(?:\\w+:)?wpt\\s[^>]*?lat=\"([-\\d.]+)\"[^>]*?lon=\"([-\\d.]+)\"[^>]*>(.*?)</(?:\\w+:)?wpt>", RegexOption.DOT_MATCHES_ALL)
    private val WPT_LON_FIRST = Regex("<(?:\\w+:)?wpt\\s[^>]*?lon=\"([-\\d.]+)\"[^>]*?lat=\"([-\\d.]+)\"[^>]*>(.*?)</(?:\\w+:)?wpt>", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("<(?:\\w+:)?name>\\s*\\[?(\\d+)\\]?\\s*</")
    private val CAT = Regex("Cat=\"([^\"]*)\"")

    /** Art und Limit aus dem Kategorienamen. */
    fun classify(cat: String?): Triple<Int, Int, Boolean> {
        val c = (cat ?: "").lowercase().removePrefix("scdb_")
        val limit = Regex("(\\d{2,3})\\s*$").find(c)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when {
            c.startsWith("tempo") -> Triple(CameraKind.SPEED, limit, false)
            c.startsWith("ampel") -> Triple(CameraKind.RED_LIGHT, limit, false)
            c.startsWith("abschnitt") -> Triple(CameraKind.SECTION, limit, false)
            c.startsWith("tunnel") -> Triple(CameraKind.TUNNEL, limit, false)
            else -> Triple(CameraKind.SPEED, 0, true) // "Kamera" oder unbekannt
        }
    }

    fun parseGpx(text: String): List<Parsed> {
        val out = ArrayList<Parsed>()
        var n = 0
        fun add(lat: Double, lon: Double, body: String) {
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            val id = NAME.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: -(++n).toLong()
            val (kind, limit, generic) = classify(CAT.find(body)?.groupValues?.get(1))
            out += Parsed(id, lat, lon, kind, limit, generic)
        }
        for (m in WPT.findAll(text)) add(m.groupValues[1].toDoubleOrNull() ?: continue, m.groupValues[2].toDoubleOrNull() ?: continue, m.groupValues[3])
        if (out.isEmpty()) for (m in WPT_LON_FIRST.findAll(text)) add(m.groupValues[2].toDoubleOrNull() ?: continue, m.groupValues[1].toDoubleOrNull() ?: continue, m.groupValues[3])
        return out
    }

    /** Gleiche Nummer = ein Blitzer (der mit mehr Angaben gewinnt), dann alles unter [MERGE_M] Abstand zu einem Blitzer. */
    fun merge(all: List<Parsed>): List<ImportedCamera> {
        val byId = HashMap<Long, Parsed>()
        val noId = ArrayList<Parsed>()
        for (p in all) {
            if (p.id <= 0) { noId += p; continue }
            val old = byId[p.id]
            if (old == null || p.score > old.score) byId[p.id] = p
        }
        val sorted = (byId.values + noId).sortedWith(compareByDescending<Parsed> { it.score }.thenBy { it.id })
        val grid = HashMap<Pair<Int, Int>, MutableList<Parsed>>()
        val kept = ArrayList<Parsed>()
        fun cell(v: Double) = Math.floor(v / 0.0005).toInt()
        for (p in sorted) {
            val cy = cell(p.lat); val cx = cell(p.lon)
            var dup = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val l = grid[Pair(cy + dy, cx + dx)] ?: continue
                for (q in l) if (distM(p.lat, p.lon, q.lat, q.lon) < MERGE_M) { dup = true; break@loop }
            }
            if (!dup) { kept += p; grid.getOrPut(Pair(cy, cx)) { ArrayList() } += p }
        }
        return kept.mapIndexed { i, p -> ImportedCamera(if (p.id > 0) p.id else -(1_000_000_000L + i), p.lat, p.lon, p.kind, p.limitKmh) }
    }

    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dN = (lat2 - lat1) * 111_320.0
        val dE = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return hypot(dN, dE)
    }

    /**
     * Blitzer für die Warnung: eigene Liste in der Nähe (Umkreis ca. 1 km, nur erlaubte Arten) plus die OSM-Blitzer,
     * die nicht näher als [MERGE_M] an einem eigenen liegen. Eigene bekommen eine negative Nummer.
     */
    fun combine(osm: List<Camera>, imported: List<ImportedCamera>, lat: Double, lon: Double, allow: (Int) -> Boolean): List<Camera> {
        val out = ArrayList<Camera>()
        val own = ArrayList<ImportedCamera>()
        for (c in imported) {
            if (abs(c.lat - lat) > RADIUS_DEG || abs(c.lon - lon) > RADIUS_DEG * 1.6) continue
            if (!allow(c.kind)) continue
            own += c
            val tags = HashMap<String, String>()
            tags["scdb_kind"] = c.kind.toString()
            if (c.limitKmh > 0) tags["scdb_limit"] = c.limitKmh.toString()
            out += Camera(if (c.id > 0) -c.id else c.id, c.lat, c.lon, tags)
        }
        for (c in osm) if (own.none { distM(it.lat, it.lon, c.lat, c.lon) < MERGE_M }) out += c
        return out
    }

    private const val RADIUS_DEG = 0.01

    // ---- Speichern und Laden (eine Zeile pro Blitzer: id,breite,länge,art,limit) ----

    fun toCsv(list: List<ImportedCamera>): String {
        val sb = StringBuilder(list.size * 32)
        for (c in list) sb.append(c.id).append(',').append(c.lat).append(',').append(c.lon).append(',').append(c.kind).append(',').append(c.limitKmh).append('\n')
        return sb.toString()
    }

    fun fromCsv(text: String): List<ImportedCamera> {
        val out = ArrayList<ImportedCamera>()
        for (line in text.lineSequence()) {
            val p = line.split(',')
            if (p.size < 5) continue
            try { out += ImportedCamera(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toInt()) } catch (_: Exception) { }
        }
        return out
    }
}

/** Die importierte Liste als Datei im App-Speicher (nicht im Kachel-Speicher, damit sie beim Aufräumen bleibt). */
object CameraStore {
    private var cachedKey: String? = null
    private var cached: List<ImportedCamera> = emptyList()

    fun file(dir: File) = File(dir, "cameras_import.csv")

    fun save(dir: File, list: List<ImportedCamera>) {
        val f = file(dir)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(CameraImport.toCsv(list), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        cachedKey = null
    }

    @Synchronized
    fun load(dir: File): List<ImportedCamera> {
        val f = file(dir)
        if (!f.isFile) { cachedKey = null; cached = emptyList(); return cached }
        val key = f.absolutePath + ":" + f.lastModified() + ":" + f.length()
        if (key != cachedKey) {
            cached = try { CameraImport.fromCsv(f.readText(Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
            cachedKey = key
        }
        return cached
    }

    fun clear(dir: File) { file(dir).delete(); cachedKey = null; cached = emptyList() }
}
