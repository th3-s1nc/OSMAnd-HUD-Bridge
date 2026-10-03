package io.github.th3s1nc.osmandhudbridge.limit

import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/** Eine eingelesene Strecke: [points] als (Breite, Länge). [sparse]: nur wenige, weit auseinanderliegende Punkte. */
class GpxRoute(val name: String, val points: List<DoubleArray>, val sparse: Boolean)

/**
 * Liest GPX-Dateien (Calimoto, Kurviger, Motobit, …). Regel: gibt es eine Aufzeichnung (trkpt), gilt sie, sonst die Route (rtept).
 * Nur java, ohne Android.
 */
object GpxParser {
    private val TRKPT = Regex("<trkpt\\b([^>]*)>")
    private val RTEPT = Regex("<rtept\\b([^>]*)>")
    private val LAT = Regex("\\blat\\s*=\\s*[\"']([-+0-9.eE]+)[\"']")
    private val LON = Regex("\\blon\\s*=\\s*[\"']([-+0-9.eE]+)[\"']")
    private val NAME = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)

    /** Ab diesem mittleren Punktabstand gilt die Datei als "wenige Punkte". */
    const val SPARSE_M = 2000.0

    fun parse(text: String): GpxRoute? {
        var pts = points(TRKPT, text)
        if (pts.size < 2) pts = points(RTEPT, text)
        if (pts.size < 2) return null
        val len = lengthM(pts)
        val sparse = len / (pts.size - 1) > SPARSE_M
        return GpxRoute(name(text), pts, sparse)
    }

    private fun points(re: Regex, text: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (m in re.findAll(text)) {
            val a = m.groupValues[1]
            val la = LAT.find(a)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lo = LON.find(a)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (la in -90.0..90.0 && lo in -180.0..180.0) out += doubleArrayOf(la, lo)
        }
        return out
    }

    private fun name(text: String): String {
        val meta = Regex("<metadata>(.*?)</metadata>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
        val raw = (meta?.let { NAME.find(it)?.groupValues?.get(1) } ?: NAME.find(text)?.groupValues?.get(1)) ?: ""
        val clean = raw.replace("<![CDATA[", "").replace("]]>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            .trim()
        return if (clean.isBlank() || clean == "-") "Tour" else clean.take(60)
    }

    fun lengthM(pts: List<DoubleArray>): Double {
        var s = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            s += hypot((b[1] - a[1]) * 111_320.0 * cos(Math.toRadians(a[0])), (b[0] - a[0]) * 110_540.0)
        }
        return s
    }
}

/**
 * Gemerkte Touren: pro Tour eine Datei mit dem Namen (erste Zeile) und den Kacheln ("Breitenindex,Längenindex" je Zeile).
 * Eine Tour bleibt, bis alle ihre Kacheln gespeichert sind. Nur java.io.
 */
class TourStore(private val dir: File) {
    class Tour(val id: String, val name: String, val tiles: List<TileKey>)
    class Status(val name: String, val total: Int, val missing: Int)

    fun add(name: String, tiles: List<TileKey>) {
        if (tiles.isEmpty()) return
        try {
            dir.mkdirs()
            val id = System.currentTimeMillis().toString() + "_" + (dir.listFiles()?.size ?: 0)
            File(dir, "$id.tour").writeText(name.replace('\n', ' ') + "\n" + tiles.joinToString("\n") { "${it.latIdx},${it.lonIdx}" })
        } catch (_: Exception) { }
    }

    fun list(): List<Tour> {
        val files = dir.listFiles { f -> f.name.endsWith(".tour") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            try {
                val lines = f.readLines()
                val tiles = lines.drop(1).mapNotNull { l ->
                    val p = l.split(",")
                    val a = p.getOrNull(0)?.toIntOrNull(); val b = p.getOrNull(1)?.toIntOrNull()
                    if (a != null && b != null) TileKey(a, b) else null
                }
                Tour(f.name.removeSuffix(".tour"), lines.firstOrNull() ?: "Tour", tiles)
            } catch (_: Exception) { null }
        }
    }

    fun clear() { dir.listFiles { f -> f.name.endsWith(".tour") }?.forEach { it.delete() } }

    /** Zählt die fehlenden Kacheln je Tour und löscht fertige Touren. */
    fun status(cache: TileCache, nowMs: Long): List<Status> {
        val out = ArrayList<Status>()
        for (t in list()) {
            val missing = t.tiles.count { !cache.isFresh(it, nowMs, TileCache.PRELOAD_SKIP_MS) }
            if (missing == 0) File(dir, t.id + ".tour").delete() else out += Status(t.name, t.tiles.size, missing)
        }
        return out
    }

    /** Die nächste fehlende Kachel (Reihenfolge der Strecke, ältere Touren zuerst), null wenn alles da ist. */
    fun nextMissing(cache: TileCache, nowMs: Long): TileKey? {
        for (t in list()) {
            val k = t.tiles.firstOrNull { !cache.isFresh(it, nowMs, TileCache.PRELOAD_SKIP_MS) }
            if (k != null) return k
            File(dir, t.id + ".tour").delete()
        }
        return null
    }
}
