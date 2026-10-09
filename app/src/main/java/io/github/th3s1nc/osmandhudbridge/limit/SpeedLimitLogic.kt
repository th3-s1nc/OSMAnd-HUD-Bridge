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
    val fileName get() = "tile3_${latIdx}_$lonIdx.json" // "3": enthält auch Blitzer, "2" nur Ortsschilder, ältere (tile_*) werden gelöscht
    /** Datei der Vorversion (ohne Blitzer): nur als Notlösung ohne Netz zu gebrauchen. */
    val legacyFileName get() = "tile2_${latIdx}_$lonIdx.json"
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

/**
 * Festplatten-Zwischenspeicher für Kachel-Antworten (eine Datei pro Kachel, gepackt mit gzip; alte unkomprimierte
 * Dateien werden weiter gelesen). Nur java.io.
 */
private val CAMERA_NODE = Regex("\\{[^{}]*\"id\"\\s*:\\s*(\\d+)[^{}]*\"tags\"\\s*:\\s*\\{[^{}]*\"highway\"\\s*:\\s*\"speed_camera\"")

/** Alle Blitzer-IDs (highway=speed_camera) aus dem Overpass-Text einer Kachel. */
fun cameraIds(text: String): Set<Long> {
    if (!text.contains("speed_camera")) return emptySet()
    return CAMERA_NODE.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.toSet()
}

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

    /** Kachel der Vorversion (ohne Blitzerdaten), nur als Notlösung ohne Netz. */
    fun readLegacy(key: TileKey, nowMs: Long): Entry? = try {
        val f = File(dir, key.legacyFileName)
        if (f.isFile && f.length() > 0) {
            val raw = f.readBytes()
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
            Entry(String(bytes, Charsets.UTF_8), (nowMs - f.lastModified()).coerceAtLeast(0))
        } else null
    } catch (_: Exception) { null }

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

    /** Ergebnis von [countCameras]: gespeicherte Kacheln mit Blitzerdaten, davon mit mindestens einem Blitzer, und die Zahl der Blitzer. */
    class CameraCount(val tilesWithData: Int, val tilesWithCameras: Int, val cameras: Int, val legacyOnly: Int)

    /** Zählt die Blitzer in allen gespeicherten Kacheln (liest jede Datei, nur im Hintergrund-Thread aufrufen). */
    fun countCameras(): CameraCount {
        val files = dir.listFiles { x -> x.name.startsWith("tile3_") && x.name.endsWith(".json") } ?: emptyArray()
        val ids = HashSet<Long>()
        var withCams = 0
        for (f in files) {
            try {
                val raw = f.readBytes()
                val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                    java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
                val found = cameraIds(String(bytes, Charsets.UTF_8))
                if (found.isNotEmpty()) withCams++
                ids += found
            } catch (_: Exception) { }
        }
        val legacy = dir.listFiles { x -> x.name.startsWith("tile2_") && x.name.endsWith(".json") }
            ?.count { !File(dir, it.name.replaceFirst("tile2_", "tile3_")).isFile } ?: 0
        return CameraCount(files.size, withCams, ids.size, legacy)
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
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val TRIM_EVERY = 20
    }
}
