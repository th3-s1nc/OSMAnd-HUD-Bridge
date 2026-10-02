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
    const val PREFETCH_M = 1000.0

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

/** Welche Kacheln für ein Vorladen im Umkreis nötig sind (nächste zuerst). */
object PreloadPlanner {
    const val MAX_TILES = 3000

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
class TileCache(private val dir: File, private val maxFiles: Int = 3000) {
    class Entry(val text: String, val ageMs: Long)

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
            trim()
        } catch (_: Exception) { }
    }

    private fun trim() {
        dir.listFiles { x -> x.name.startsWith("tile_") }?.forEach { it.delete() } // alte Version ohne Ortsschilder
        val files = dir.listFiles { x -> x.name.endsWith(".json") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    companion object {
        /** Älter als das: ohne Netz nur Notlösung, mit Netz wird neu geladen. */
        const val VALID_MS = 180L * 24 * 3600 * 1000
        /** Älter als das (aber noch gültig): sofort benutzen und im Hintergrund erneuern. */
        const val REFRESH_MS = 30L * 24 * 3600 * 1000
    }
}
