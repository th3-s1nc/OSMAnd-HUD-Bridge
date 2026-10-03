package io.github.th3s1nc.osmandhudbridge.limit

import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Eine Karten-Kachel (ca. 2,2 x 2,1 km): Index in Breiten- und Längenrichtung. */
data class TileKey(val latIdx: Int, val lonIdx: Int) {
    val south get() = latIdx * TileMath.TILE_LAT
    val north get() = (latIdx + 1) * TileMath.TILE_LAT
    val west get() = lonIdx * TileMath.TILE_LON
    val east get() = (lonIdx + 1) * TileMath.TILE_LON
    val fileName get() = "tile2_${latIdx}_$lonIdx.json" // "2": enthält auch Ortsschilder, ältere Dateien (tile_*) werden gelöscht
}

/** Kachel-Rechnung ohne Android-Abhängigkeit (testbar). */
object TileMath {
    const val TILE_LAT = 0.02
    const val TILE_LON = 0.03
    /** Überlappung beim Laden, damit Straßen an der Kachelgrenze in beiden Kacheln liegen (ca. 55 m). */
    const val MARGIN_DEG = 0.0005
    /** Ab dieser Entfernung zur Kachelkante wird die Nachbarkachel in Fahrtrichtung vorgeladen. */
    const val PREFETCH_M = 3000.0

    private const val M_PER_DEG_LAT = 110_540.0

    fun tileOf(lat: Double, lon: Double) = TileKey(floor(lat / TILE_LAT).toInt(), floor(lon / TILE_LON).toInt())

    /** Alle Kacheln, deren Ladebereich (mit Überlappung) die Position enthält: 1 bis 4 Stück, die eigene zuerst. */
    fun covering(lat: Double, lon: Double): List<TileKey> {
        val own = tileOf(lat, lon)
        val out = LinkedHashSet<TileKey>()
        out += own
        for (dLat in doubleArrayOf(-MARGIN_DEG, MARGIN_DEG)) for (dLon in doubleArrayOf(-MARGIN_DEG, MARGIN_DEG)) {
            out += tileOf(lat + dLat, lon + dLon)
        }
        return out.toList()
    }

    /**
     * Kacheln, die jetzt da sein sollten: die eigene und, bei bekannter Fahrtrichtung und Tempo, die Nachbarkacheln,
     * auf deren Kante man in [PREFETCH_M] zufährt (Diagonale inklusive). Reihenfolge = Wichtigkeit.
     */
    fun wanted(lat: Double, lon: Double, bearingDeg: Double?, speedMs: Float): List<TileKey> {
        val own = tileOf(lat, lon)
        val out = ArrayList<TileKey>()
        out += own
        if (bearingDeg == null || speedMs < 2f) return out
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        val n = cos(Math.toRadians(bearingDeg))
        val e = sin(Math.toRadians(bearingDeg))
        var dLat = 0
        var dLon = 0
        val toNorth = (own.north - lat) * M_PER_DEG_LAT
        val toSouth = (lat - own.south) * M_PER_DEG_LAT
        val toEast = (own.east - lon) * mLon
        val toWest = (lon - own.west) * mLon
        if (n > 0.3 && toNorth < PREFETCH_M) dLat = 1
        if (n < -0.3 && toSouth < PREFETCH_M) dLat = -1
        if (e > 0.3 && toEast < PREFETCH_M) dLon = 1
        if (e < -0.3 && toWest < PREFETCH_M) dLon = -1
        if (dLat != 0) out += TileKey(own.latIdx + dLat, own.lonIdx)
        if (dLon != 0) out += TileKey(own.latIdx, own.lonIdx + dLon)
        if (dLat != 0 && dLon != 0) out += TileKey(own.latIdx + dLat, own.lonIdx + dLon)
        return out
    }
}

/** Wartezeit nach fehlgeschlagenen Abfragen: kurz, höchstens 15 s (vorher bis zu 80 s). */
object Backoff {
    const val MAX_MS = 15_000L
    fun delayMs(failures: Int): Long = if (failures <= 0) 0L else minOf(2_000L shl minOf(failures - 1, 3), MAX_MS)
}

/**
 * Liste der Karten-Server mit Pause für Ausfälle: ein Server, der fehlschlägt, wird [cooldownMs] lang übersprungen.
 * Sind alle in Pause, werden trotzdem alle probiert (sonst käme nie wieder eine Anfrage raus).
 */
class ServerPool(private val endpoints: List<String>, private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    companion object { const val DEFAULT_COOLDOWN_MS = 3 * 60_000L }
    private val badUntil = HashMap<String, Long>()
    private var rr = 0

    @Synchronized
    fun usable(now: Long): List<String> {
        val ok = endpoints.filter { (badUntil[it] ?: 0L) <= now }
        return ok.ifEmpty { endpoints }
    }

    /** Die ersten [n] brauchbaren Server (für den Wettlauf; weiter hinten stehende rücken nach, wenn vorn welche ausfallen). */
    fun race(now: Long, n: Int): List<String> = usable(now).take(n)

    /** Reihum der nächste brauchbare Server (für das höfliche Vorladen: immer nur einer). */
    @Synchronized
    fun next(now: Long): String {
        val list = usable(now)
        rr = (rr + 1) % list.size
        return list[rr]
    }

    @Synchronized fun fail(endpoint: String, now: Long, pauseMs: Long = cooldownMs) { badUntil[endpoint] = now + pauseMs }
    @Synchronized fun ok(endpoint: String) { badUntil.remove(endpoint) }
    @Synchronized fun pausedCount(now: Long): Int = endpoints.count { (badUntil[it] ?: 0L) > now }
}

/**
 * Fasst wiederholte Fehler zu einer Protokollzeile zusammen: "7 Fehlversuche (6x timeout, 1x HTTP 403)".
 * [add] sammelt, [take] gibt die Zusammenfassung zurück und setzt zurück (null, wenn nichts gesammelt wurde).
 */
class RepeatSummary {
    private val reasons = LinkedHashMap<String, Int>()
    private var count = 0

    fun add(reason: String) { reasons[reason] = (reasons[reason] ?: 0) + 1; count++ }
    fun isEmpty() = count == 0
    fun count() = count

    fun take(): String? {
        if (count == 0) return null
        val text = "$count Fehlversuche (" + reasons.entries.joinToString(", ") { "${it.value}x ${it.key}" } + ")"
        reasons.clear(); count = 0
        return text
    }
}

/** Welche Kacheln für ein Vorladen im Umkreis nötig sind (nächste zuerst). */
object PreloadPlanner {
    const val MAX_TILES = 3000

    /**
     * Kacheln in einem Streifen vor der Position in Fahrtrichtung ([bearingDeg], 0 = Nord): drei Linien (Mitte, links und rechts
     * je [sideM] daneben), alle [stepM] ein Punkt, bis [lengthKm]. Die nächsten zuerst, ohne Doppelte.
     */
    fun corridor(lat: Double, lon: Double, bearingDeg: Double, lengthKm: Int, stepM: Double = 1000.0, sideM: Double = 1500.0): List<TileKey> {
        if (lengthKm <= 0) return emptyList()
        val out = LinkedHashSet<TileKey>()
        val b = Math.toRadians(bearingDeg)
        var d = 0.0
        while (d <= lengthKm * 1000.0) {
            for (side in doubleArrayOf(0.0, -sideM, sideM)) {
                val east = d * sin(b) + side * cos(b)
                val north = d * cos(b) - side * sin(b)
                val la = lat + north / 110_540.0
                val lo = lon + east / (111_320.0 * cos(Math.toRadians(la)).coerceAtLeast(0.05))
                out += TileMath.tileOf(la, lo)
            }
            d += stepM
        }
        return out.toList()
    }

    /**
     * Kacheln entlang einer Strecke ([points] als Breite/Länge): alle [stepM] ein Punkt, dazu je ein Raster von [sideM] links,
     * rechts, vor und hinter dem Punkt. In Reihenfolge der Strecke, ohne Doppelte, höchstens [MAX_TILES].
     */
    fun route(points: List<DoubleArray>, sideM: Double = 1500.0, stepM: Double = 500.0): List<TileKey> {
        val out = LinkedHashSet<TileKey>()
        fun add(la: Double, lo: Double) {
            val c = cos(Math.toRadians(la)).coerceAtLeast(0.05)
            for (dy in doubleArrayOf(0.0, -sideM, sideM)) for (dx in doubleArrayOf(0.0, -sideM, sideM)) {
                if (out.size >= MAX_TILES) return
                out += TileMath.tileOf(la + dy / 110_540.0, lo + dx / (111_320.0 * c))
            }
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val c = cos(Math.toRadians(a[0])).coerceAtLeast(0.05)
            val len = Math.hypot((b[1] - a[1]) * 111_320.0 * c, (b[0] - a[0]) * 110_540.0)
            val n = maxOf(1, (len / stepM).toInt())
            for (k in 0 until n) add(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
        }
        if (points.isNotEmpty()) points.last().let { add(it[0], it[1]) }
        return out.toList()
    }

    fun tilesInRadius(lat: Double, lon: Double, radiusKm: Int): List<TileKey> {
        if (radiusKm <= 0) return emptyList()
        val dLat = radiusKm / 110.54
        val dLon = radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        val lo = TileMath.tileOf(lat - dLat, lon - dLon)
        val hi = TileMath.tileOf(lat + dLat, lon + dLon)
        val mLon = 111_320.0 * cos(Math.toRadians(lat))
        val out = ArrayList<Pair<Double, TileKey>>()
        for (la in lo.latIdx..hi.latIdx) for (lo2 in lo.lonIdx..hi.lonIdx) {
            val k = TileKey(la, lo2)
            val nLat = lat.coerceIn(k.south, k.north)
            val nLon = lon.coerceIn(k.west, k.east)
            val d = Math.hypot((nLon - lon) * mLon, (nLat - lat) * 110_540.0)
            if (d <= radiusKm * 1000.0) out += d to k
        }
        return out.sortedBy { it.first }.take(MAX_TILES).map { it.second }
    }
}

/**
 * Festplatten-Zwischenspeicher für Kachel-Antworten (eine Datei pro Kachel, gepackt mit gzip; alte unkomprimierte
 * Dateien werden weiter gelesen). Nur java.io.
 */
class TileCache(private val dir: File, private val maxFiles: Int = 200_000) {
    class Entry(val text: String, val ageMs: Long)

    /** Höchstens so viele Bytes im Zwischenspeicher, die ältesten Kacheln werden zuerst gelöscht. */
    @Volatile var maxBytes: Long = DEFAULT_MAX_BYTES
    private var writes = 0

    fun read(key: TileKey, nowMs: Long): Entry? = try {
        val f = File(dir, key.fileName)
        if (f.isFile && f.length() > 0) {
            val raw = f.readBytes()
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
            Entry(String(bytes, Charsets.UTF_8), (nowMs - f.lastModified()).coerceAtLeast(0))
        } else null
    } catch (_: Exception) { null }

    /** Liegt die Kachel vor und ist jünger als [maxAgeMs]? (ohne sie zu lesen) */
    fun isFresh(key: TileKey, nowMs: Long, maxAgeMs: Long): Boolean {
        val f = File(dir, key.fileName)
        return f.isFile && f.length() > 0 && nowMs - f.lastModified() < maxAgeMs
    }

    fun write(key: TileKey, text: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, key.fileName + ".tmp")
            java.util.zip.GZIPOutputStream(tmp.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val f = File(dir, key.fileName)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            if (writes++ % TRIM_EVERY == 0) trimNow()
        } catch (_: Exception) { }
    }

    /** Anzahl und Größe aller gespeicherten Kacheln (kann bei vielen Dateien etwas dauern, nicht im Main-Thread). */
    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles { x -> x.name.endsWith(".json") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    /** Zu viele Dateien oder zu viele Bytes: die ältesten löschen. */
    fun trimNow() {
        dir.listFiles { x -> x.name.startsWith("tile_") }?.forEach { it.delete() } // alte Version ohne Ortsschilder
        val files = dir.listFiles { x -> x.name.endsWith(".json") } ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        if (count <= maxFiles && bytes <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (count <= maxFiles && bytes <= maxBytes) break
            val len = f.length()
            if (f.delete()) { count--; bytes -= len }
        }
    }

    companion object {
        /** Älter als das: ohne Netz nur Notlösung, mit Netz wird neu geladen. */
        const val VALID_MS = 180L * 24 * 3600 * 1000
        /** Älter als das (aber noch gültig): sofort benutzen und im Hintergrund erneuern. */
        const val REFRESH_MS = 30L * 24 * 3600 * 1000
        /** Beim Vorladen werden Kacheln, die jünger sind, übersprungen. */
        const val PRELOAD_SKIP_MS = 90L * 24 * 3600 * 1000
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val TRIM_EVERY = 20
    }
}
