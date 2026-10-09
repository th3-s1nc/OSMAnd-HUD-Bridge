package io.github.th3s1nc.osmandhudbridge.limit

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Offline-Straßendaten: liest eine OSM-Datei im PBF-Format (z. B. von Geofabrik) und schreibt daraus Kacheln im gleichen
 * Format wie der Zwischenspeicher der App (Overpass-JSON, gzip, ein Element pro Zeile). Nur java.io, damit es ohne Android testbar ist.
 *
 * Ablauf in drei Schritten, damit auch ganz Bayern mit wenig Arbeitsspeicher geht:
 *  1. Straßen suchen: alle befahrbaren Wege in eine Zwischendatei, dazu die Liste der gebrauchten Punkt-Nummern.
 *  2. Punkte lesen: Koordinaten der gebrauchten Punkte, dazu Ortsschilder, Bahnübergänge, Zebrastreifen usw.
 *  3. Kacheln schreiben: die Wege streifenweise (je ein Band aus Kachelreihen) in Kacheln sortieren und speichern.
 * Bereits vorhandene Kacheln aus einem früheren Import werden zusammengeführt (Nachbarregionen liegen an der Kante in derselben Kachel).
 */
interface ImportListener {
    /** [step] von [steps], [fraction] 0..1 für den ganzen Import, [tilesDone] bisher geschriebene Kacheln. */
    fun progress(step: Int, steps: Int, fraction: Double, text: String, tilesDone: Int)
    fun cancelled(): Boolean
}

class ImportCancelled : Exception("abgebrochen")

/** Was beim Einlesen einer Datei gefunden wurde (Zahlen aus der Datei, nicht aus den Kacheln). */
class ImportStats {
    var roads = 0
    var withLimit = 0
    var withZone = 0
    var lit = 0
    var tunnels = 0
    var bridges = 0
    var citySigns = 0
    var speedSigns = 0
    var cameras = 0
    var levelCrossings = 0
    var crossings = 0
    var zebras = 0
    var calming = 0

    private fun values() = intArrayOf(roads, withLimit, withZone, lit, tunnels, bridges, citySigns, speedSigns, cameras, levelCrossings, crossings, zebras, calming)

    /** Summe zweier Zählungen (zum Beispiel mehrere Dateien). */
    fun plus(o: ImportStats): ImportStats {
        val a = values(); val b = o.values()
        return fromLine(a.indices.joinToString(",") { (a[it] + b[it]).toString() })!!
    }

    /** Eine Zeile zum Speichern: Zahlen durch Komma getrennt. */
    fun toLine(): String = values().joinToString(",")

    /** Lesbare Zusammenfassung (deutsche Zahlen). */
    fun describe(): String {
        fun n(v: Int) = String.format(java.util.Locale.GERMANY, "%,d", v)
        return "Straßen: ${n(roads)}\n" +
            "  mit Tempolimit: ${n(withLimit)}\n" +
            "  mit Tempo-Zone (z. B. Tempo 30): ${n(withZone)}\n" +
            "  beleuchtet: ${n(lit)}\n" +
            "  Tunnel: ${n(tunnels)}, Brücken: ${n(bridges)}\n" +
            "Ortsschilder: ${n(citySigns)}\n" +
            "Temposchilder: ${n(speedSigns)}\n" +
            "Blitzer: ${n(cameras)}\n" +
            "Bahnübergänge: ${n(levelCrossings)}\n" +
            "Fußgängerüberwege: ${n(crossings)}, davon Zebrastreifen: ${n(zebras)}\n" +
            "Verkehrsberuhigung: ${n(calming)}"
    }

    companion object {
        fun fromLine(line: String): ImportStats? {
            val p = line.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (p.size != 13) return null
            return ImportStats().also {
                it.roads = p[0]; it.withLimit = p[1]; it.withZone = p[2]; it.lit = p[3]; it.tunnels = p[4]; it.bridges = p[5]
                it.citySigns = p[6]; it.speedSigns = p[7]; it.cameras = p[8]; it.levelCrossings = p[9]; it.crossings = p[10]
                it.zebras = p[11]; it.calming = p[12]
            }
        }
    }
}

/** Merkt sich pro eingelesener Datei die Zahlen (für die Zusammenfassung). Eine Zeile je Datei: Name, Datum, Zahlen. */
object ImportLog {
    class Entry(val name: String, val date: String, val stats: ImportStats)

    fun parse(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            ImportStats.fromLine(p[2])?.let { Entry(p[0], p[1], it) }
        }
    }

    /** Neuer Eintrag nach vorn; gleicher Dateiname wird ersetzt; höchstens [max] Einträge. */
    fun add(text: String?, e: Entry, max: Int = 8): String =
        (listOf(e) + parse(text).filter { it.name != e.name }).take(max)
            .joinToString("\n") { it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.date + "\t" + it.stats.toLine() }

    /** Alle Dateien zusammengezählt (Überschneidungen von Nachbarregionen können doppelt zählen). */
    fun total(entries: List<Entry>): ImportStats = entries.fold(ImportStats()) { acc, e -> acc.plus(e.stats) }

    /** Nach so vielen Tagen soll ein neuer Import empfohlen werden (ca. 6 Monate). */
    const val STALE_DAYS = 183L

    /** Alter der ältesten Datei in Tagen und ihr Datum; null wenn keine Einträge oder Datum nicht lesbar. */
    fun oldest(entries: List<Entry>, today: java.time.LocalDate): Pair<Long, String>? {
        val f = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
        return entries.mapNotNull { e ->
            try { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(e.date, f), today) to e.date } catch (_: Exception) { null }
        }.maxByOrNull { it.first }
    }

    /** Hinweistext, wenn die Daten alt sind; sonst null. */
    fun staleHint(entries: List<Entry>, today: java.time.LocalDate): String? {
        val o = oldest(entries, today) ?: return null
        return if (o.first >= STALE_DAYS) "Daten vom ${o.second}: ein neuer Import wäre gut." else null
    }

    fun describe(entries: List<Entry>): String =
        entries.joinToString("\n\n") { "${it.name} (${it.date})\n${it.stats.describe()}" }
}

class ImportResult(val tiles: Int, val ways: Int, val points: Int, val bytes: Long, val seconds: Int, val stats: ImportStats = ImportStats())

object PbfImport {
    const val MARKER = "{\"generator\":\"hudbridge-import\",\"elements\":["
    /** Kachelreihen pro Streifen (5 Reihen = 0,1 Grad, ca. 11 km). */
    private const val BAND_ROWS = 5
    private const val STEPS = 3
    private const val W_ROADS = 0.25
    private const val W_NODES = 0.35
    private const val MISSING = Int.MIN_VALUE

    private val ROAD_CLASSES = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service",
        "road", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"
    )
    private val WAY_KEYS = setOf(
        "highway", "name", "ref", "lit", "junction", "tunnel", "bridge", "surface", "oneway",
        "zone:maxspeed", "zone:traffic", "source:maxspeed"
    )
    private val NODE_KEYS = setOf(
        "highway", "railway", "traffic_sign", "traffic_sign:direction", "direction", "name", "maxspeed", "enforcement",
        "speed_camera", "traffic_calming", "crossing", "crossing:markings", "crossing_ref", "crossing:signals"
    )
    private val NODE_TRIGGER = setOf("highway", "railway", "traffic_sign", "traffic_calming")

    // ---------------------------------------------------------------------------------------------------------------
    // Einstieg

    /**
     * @param open öffnet die Datei jedes Mal neu (der Import liest sie zweimal)
     * @param size Dateigröße in Bytes (für den Fortschritt), 0 = unbekannt
     */
    fun run(open: () -> InputStream, size: Long, outDir: File, tmpDir: File, listener: ImportListener, nowMs: Long = System.currentTimeMillis()): ImportResult {
        val t0 = System.currentTimeMillis()
        outDir.mkdirs()
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        try {
            val waysTmp = File(tmpDir, "ways.bin")
            val ids = LongList()
            var wayCount = 0
            val stats = ImportStats()
            val sizeD = if (size > 0) size.toDouble() else 1.0

            // Schritt 1: Straßen
            var sorted = false
            DataOutputStream(BufferedOutputStream(FileOutputStream(waysTmp), 1 shl 16)).use { out ->
                sorted = scanBlobs(open, size, listener, 1, 0.0, W_ROADS, "Straßen suchen", 0) { blk, _ ->
                    wayCount += collectWays(blk, out, ids, stats)
                    true
                }
            }
            ids.compact()
            val need = ids.a.copyOf(ids.n)
            ids.release()

            // Schritt 2: Punkte
            val lat = IntArray(need.size) { MISSING }
            val lon = IntArray(need.size)
            val pois = ArrayList<Poi>()
            scanBlobs(open, size, listener, 2, W_ROADS, W_NODES, "Punkte lesen", 0, stopAtWays = true) { blk, _ ->
                readNodes(blk, need, lat, lon, pois, stats)
                true
            }

            // Schritt 3: Kacheln
            val poiByTile = HashMap<Long, ArrayList<String>>()
            for (p in pois) for (k in TileMath.covering(p.lat / 1e7, p.lon / 1e7)) poiByTile.getOrPut(tileId(k.latIdx, k.lonIdx)) { ArrayList() }.add(p.line)
            val bandFiles = splitIntoBands(waysTmp, need, lat, lon, tmpDir, listener, W_ROADS + W_NODES)
            var tiles = 0
            var bytes = 0L
            val bands = bandFiles.keys.sorted()
            val base = W_ROADS + W_NODES + (1 - W_ROADS - W_NODES) * 0.15
            for ((bi, band) in bands.withIndex()) {
                if (listener.cancelled()) throw ImportCancelled()
                val r = writeBand(bandFiles[band]!!, band, poiByTile, outDir, nowMs)
                tiles += r.first
                bytes += r.second
                bandFiles[band]!!.delete()
                listener.progress(3, STEPS, base + (1 - base) * (bi + 1) / bands.size, "Kacheln schreiben", tiles)
            }
            return ImportResult(tiles, wayCount, pois.size, bytes, ((System.currentTimeMillis() - t0) / 1000).toInt(), stats)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Entfernt alle Kacheln, die von einem Import stammen. Gibt die Zahl der gelöschten Dateien zurück. */
    fun removeImported(dir: File): Int {
        var n = 0
        dir.listFiles { f -> f.name.startsWith("tile3_") && f.name.endsWith(".json") }?.forEach { f ->
            if (isImportTile(f) && f.delete()) n++
        }
        return n
    }

    /** Anzahl der aus einem Import stammenden Kacheln. */
    fun countImported(dir: File): Int =
        dir.listFiles { f -> f.name.startsWith("tile3_") && f.name.endsWith(".json") }?.count { isImportTile(it) } ?: 0

    fun isImportTile(f: File): Boolean = try {
        GZIPInputStream(FileInputStream(f)).use { gz ->
            val b = ByteArray(MARKER.length)
            var got = 0
            while (got < b.size) { val r = gz.read(b, got, b.size - got); if (r < 0) break; got += r }
            got == b.size && String(b, Charsets.UTF_8) == MARKER
        }
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------------------------------------------------------
    // Datei lesen

    private class Blob(val isData: Boolean, val raw: ByteArray)

    /** Liest die Blöcke der Datei der Reihe nach. [onBlock] gibt false zurück, um aufzuhören. Rückgabe: Datei ist nach Typ und Nummer sortiert. */
    private fun scanBlobs(
        open: () -> InputStream, size: Long, listener: ImportListener, step: Int, base: Double, weight: Double, text: String,
        tilesDone: Int, stopAtWays: Boolean = false, onBlock: (PrimBlock, Int) -> Boolean
    ): Boolean {
        var sorted = false
        val counting = CountingInput(BufferedInputStream(open(), 1 shl 16))
        counting.use { input ->
            val inflater = Inflater()
            var idx = 0
            try {
                while (true) {
                    if (listener.cancelled()) throw ImportCancelled()
                    val blob = readBlob(input, inflater) ?: break
                    if (!blob.isData) {
                        sorted = headerSorted(blob.raw)
                    } else {
                        val blk = PrimBlock(blob.raw)
                        if (stopAtWays && sorted && blk.hasWays && !blk.hasNodes) break
                        if (!onBlock(blk, idx)) break
                    }
                    idx++
                    val f = if (size > 0) min(1.0, counting.count.toDouble() / size) else 0.0
                    listener.progress(step, STEPS, base + weight * f, text, tilesDone)
                }
            } finally { inflater.end() }
        }
        if (size > 0) listener.progress(step, STEPS, base + weight, text, tilesDone)
        return sorted
    }

    private class CountingInput(val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int { val r = inner.read(); if (r >= 0) count++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = inner.read(b, off, len); if (r > 0) count += r; return r }
        override fun close() = inner.close()
    }

    private fun readFully(input: InputStream, b: ByteArray, len: Int): Boolean {
        var got = 0
        while (got < len) {
            val r = input.read(b, got, len - got)
            if (r < 0) { if (got == 0) return false; throw IOException("Datei ist abgeschnitten") }
            got += r
        }
        return true
    }

    private fun readBlob(input: InputStream, inflater: Inflater): Blob? {
        val lenBuf = ByteArray(4)
        if (!readFully(input, lenBuf, 4)) return null
        val hl = ((lenBuf[0].toInt() and 0xff) shl 24) or ((lenBuf[1].toInt() and 0xff) shl 16) or ((lenBuf[2].toInt() and 0xff) shl 8) or (lenBuf[3].toInt() and 0xff)
        if (hl <= 0 || hl > 1 shl 20) throw IOException("Das ist keine gültige PBF-Datei")
        val hdr = ByteArray(hl)
        readFully(input, hdr, hl)
        var type = ""
        var dataSize = 0
        val h = Pb(hdr, 0, hl)
        while (h.more()) {
            val tag = h.varint().toInt()
            when (tag) {
                (1 shl 3) or 2 -> { val l = h.varint().toInt(); type = String(hdr, h.p, l, Charsets.UTF_8); h.p += l }
                (3 shl 3) -> dataSize = h.varint().toInt()
                else -> h.skip(tag and 7)
            }
        }
        if (dataSize <= 0 || dataSize > 64 shl 20) throw IOException("Das ist keine gültige PBF-Datei")
        val data = ByteArray(dataSize)
        readFully(input, data, dataSize)
        var raw: ByteArray? = null
        var rawSize = 0
        var z: ByteArray? = null
        val b = Pb(data, 0, dataSize)
        while (b.more()) {
            val tag = b.varint().toInt()
            when (tag) {
                (1 shl 3) or 2 -> { val l = b.varint().toInt(); raw = data.copyOfRange(b.p, b.p + l); b.p += l }
                (2 shl 3) -> rawSize = b.varint().toInt()
                (3 shl 3) or 2 -> { val l = b.varint().toInt(); z = data.copyOfRange(b.p, b.p + l); b.p += l }
                else -> {
                    if ((tag shr 3) in 4..7 && (tag and 7) == 2) throw IOException("Diese Datei ist anders gepackt (nicht zlib) und wird nicht unterstützt")
                    b.skip(tag and 7)
                }
            }
        }
        val bytes = raw ?: run {
            val zz = z ?: throw IOException("Leerer Datenblock")
            val out = ByteArray(rawSize)
            inflater.reset()
            inflater.setInput(zz)
            var n = 0
            while (n < rawSize) {
                val r = inflater.inflate(out, n, rawSize - n)
                if (r == 0 && (inflater.finished() || inflater.needsInput())) break
                n += r
            }
            if (n != rawSize) throw IOException("Datenblock ist defekt")
            out
        }
        return Blob(type == "OSMData", bytes)
    }

    private fun headerSorted(raw: ByteArray): Boolean {
        val p = Pb(raw, 0, raw.size)
        while (p.more()) {
            val tag = p.varint().toInt()
            if ((tag shr 3) in 4..5 && (tag and 7) == 2) {
                val l = p.varint().toInt()
                if (String(raw, p.p, l, Charsets.UTF_8) == "Sort.Type_then_ID") return true
                p.p += l
            } else p.skip(tag and 7)
        }
        return false
    }

    /** Protobuf-Leser über einem Byte-Feld. */
    private class Pb(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val c = b[p++].toInt()
                r = r or ((c and 0x7f).toLong() shl s)
                if (c >= 0) return r
                s += 7
            }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                2 -> { val l = varint().toInt(); p += l }
                5 -> p += 4
                else -> throw IOException("Unbekanntes Format in der Datei")
            }
        }
    }

    private fun zz(v: Long): Long = (v ushr 1) xor -(v and 1)

    /** Ein Datenblock: Stringtabelle und Gruppen. Texte werden erst beim Gebrauch gelesen. */
    private class PrimBlock(val buf: ByteArray) {
        var strStart = IntArray(0)
        var strLen = IntArray(0)
        var nStr = 0
        var gran = 100L
        var latOff = 0L
        var lonOff = 0L
        val groups = ArrayList<IntArray>()
        var hasWays = false
        var hasNodes = false
        private var cache: Array<String?> = emptyArray()
        private val keyFlag = HashMap<Set<String>, ByteArray>()

        init {
            val p = Pb(buf, 0, buf.size)
            while (p.more()) {
                val tag = p.varint().toInt()
                when (tag) {
                    (1 shl 3) or 2 -> {
                        val l = p.varint().toInt()
                        val end = p.p + l
                        val s = Pb(buf, p.p, end)
                        var n = 0
                        var starts = IntArray(256)
                        var lens = IntArray(256)
                        while (s.more()) {
                            val t = s.varint().toInt()
                            if (t == (1 shl 3) or 2) {
                                val sl = s.varint().toInt()
                                if (n == starts.size) { starts = starts.copyOf(n * 2); lens = lens.copyOf(n * 2) }
                                starts[n] = s.p; lens[n] = sl; n++
                                s.p += sl
                            } else s.skip(t and 7)
                        }
                        strStart = starts; strLen = lens; nStr = n
                        cache = arrayOfNulls(n)
                        p.p = end
                    }
                    (2 shl 3) or 2 -> {
                        val l = p.varint().toInt()
                        groups += intArrayOf(p.p, p.p + l)
                        scanGroupKinds(p.p, p.p + l)
                        p.p += l
                    }
                    (17 shl 3) -> gran = p.varint()
                    (19 shl 3) -> latOff = p.varint()
                    (20 shl 3) -> lonOff = p.varint()
                    else -> p.skip(tag and 7)
                }
            }
        }

        private fun scanGroupKinds(start: Int, end: Int) {
            val g = Pb(buf, start, end)
            while (g.more()) {
                val tag = g.varint().toInt()
                val f = tag shr 3
                if (f == 1 || f == 2) hasNodes = true
                if (f == 3) hasWays = true
                g.skip(tag and 7)
            }
        }

        fun str(i: Int): String {
            cache[i]?.let { return it }
            val s = String(buf, strStart[i], strLen[i], Charsets.UTF_8)
            cache[i] = s
            return s
        }

        /** 0 = unbekannt, 1 = ja, 2 = nein: gehört der Text [i] der Stringtabelle zur Menge [set]? */
        fun inSet(set: Set<String>, i: Int): Boolean {
            val flags = keyFlag.getOrPut(set) { ByteArray(nStr) }
            val f = flags[i].toInt()
            if (f != 0) return f == 1
            val yes = set.contains(str(i))
            flags[i] = if (yes) 1 else 2
            return yes
        }

        /** Stringtabellen-Nummer des Textes [name], -1 wenn es ihn im Block nicht gibt. */
        fun indexOf(name: String): Int {
            val nb = name.toByteArray(Charsets.UTF_8)
            outer@ for (i in 0 until nStr) {
                if (strLen[i] != nb.size) continue
                for (j in nb.indices) if (buf[strStart[i] + j] != nb[j]) continue@outer
                return i
            }
            return -1
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Schritt 1: Wege

    private class LongList {
        var a = LongArray(1 shl 16)
        var n = 0
        fun add(v: Long) {
            if (n == a.size) {
                if (n >= 1 shl 20) compact()
                if (n > a.size * 7 / 10) a = a.copyOf(a.size * 3 / 2 + 16)
            }
            a[n++] = v
        }
        /** Sortieren und doppelte entfernen. */
        fun compact() {
            java.util.Arrays.sort(a, 0, n)
            var m = 0
            for (i in 0 until n) if (m == 0 || a[i] != a[m - 1]) a[m++] = a[i]
            n = m
        }
        fun release() { a = LongArray(0); n = 0 }
    }

    /** Schreibt die befahrbaren Wege des Blocks in die Zwischendatei, gibt ihre Zahl zurück. */
    private fun collectWays(blk: PrimBlock, out: DataOutputStream, ids: LongList, st: ImportStats): Int {
        if (!blk.hasWays) return 0
        val hw = blk.indexOf("highway")
        if (hw < 0) return 0
        var count = 0
        var scratch = LongArray(256)
        for (g in blk.groups) {
            val gp = Pb(blk.buf, g[0], g[1])
            while (gp.more()) {
                val tag = gp.varint().toInt()
                if (tag != (3 shl 3) or 2) { gp.skip(tag and 7); continue }
                val l = gp.varint().toInt()
                val wEnd = gp.p + l
                var id = 0L
                var kS = 0; var kE = 0; var vS = 0; var vE = 0; var rS = 0; var rE = 0
                val w = Pb(blk.buf, gp.p, wEnd)
                while (w.more()) {
                    val t = w.varint().toInt()
                    when (t) {
                        (1 shl 3) -> id = w.varint()
                        (2 shl 3) or 2 -> { val n = w.varint().toInt(); kS = w.p; kE = w.p + n; w.p = kE }
                        (3 shl 3) or 2 -> { val n = w.varint().toInt(); vS = w.p; vE = w.p + n; w.p = vE }
                        (8 shl 3) or 2 -> { val n = w.varint().toInt(); rS = w.p; rE = w.p + n; w.p = rE }
                        else -> w.skip(t and 7)
                    }
                }
                gp.p = wEnd
                // Ist es eine befahrbare Straße?
                var road = false
                val kp = Pb(blk.buf, kS, kE)
                val vp = Pb(blk.buf, vS, vE)
                while (kp.more() && vp.more()) {
                    val k = kp.varint().toInt()
                    val v = vp.varint().toInt()
                    if (k == hw) { road = blk.inSet(ROAD_CLASSES, v); break }
                }
                if (!road) continue
                // Tags (nur die gebrauchten) als fertiges JSON-Stück
                val sb = StringBuilder()
                val kp2 = Pb(blk.buf, kS, kE)
                val vp2 = Pb(blk.buf, vS, vE)
                var fLimit = false; var fZone = false; var fLit = false; var fTunnel = false; var fBridge = false
                while (kp2.more() && vp2.more()) {
                    val k = kp2.varint().toInt()
                    val v = vp2.varint().toInt()
                    val key = blk.str(k)
                    if (WAY_KEYS.contains(key) || key.startsWith("maxspeed")) {
                        when {
                            key == "maxspeed" || key == "maxspeed:forward" || key == "maxspeed:backward" -> fLimit = true
                            key == "zone:maxspeed" -> fZone = true
                            key == "lit" -> fLit = blk.str(v) == "yes"
                            key == "tunnel" -> fTunnel = blk.str(v) != "no"
                            key == "bridge" -> fBridge = blk.str(v) != "no"
                        }
                        if (sb.isNotEmpty()) sb.append(',')
                        sb.append('"'); esc(key, sb); sb.append("\":\""); esc(blk.str(v), sb); sb.append('"')
                    }
                }
                val refs = Pb(blk.buf, rS, rE)
                var n = 0
                var cur = 0L
                while (refs.more()) {
                    cur += zz(refs.varint())
                    if (n == scratch.size) scratch = scratch.copyOf(n * 2)
                    scratch[n++] = cur
                }
                if (n < 2) continue
                st.roads++
                if (fLimit) st.withLimit++
                if (fZone) st.withZone++
                if (fLit) st.lit++
                if (fTunnel) st.tunnels++
                if (fBridge) st.bridges++
                out.writeLong(id)
                val tb = sb.toString().toByteArray(Charsets.UTF_8)
                out.writeInt(tb.size); out.write(tb)
                out.writeInt(n)
                for (i in 0 until n) { out.writeLong(scratch[i]); ids.add(scratch[i]) }
                count++
            }
        }
        return count
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Schritt 2: Punkte

    private class Poi(val lat: Int, val lon: Int, val line: String)

    private fun readNodes(blk: PrimBlock, need: LongArray, lat: IntArray, lon: IntArray, pois: ArrayList<Poi>, stats: ImportStats) {
        if (!blk.hasNodes) return
        val keyIdx = IntArray(64)
        val valIdx = IntArray(64)
        val state = NodeState(need, lat, lon, stats)
        for (g in blk.groups) {
            val gp = Pb(blk.buf, g[0], g[1])
            while (gp.more()) {
                val tag = gp.varint().toInt()
                when (tag) {
                    (2 shl 3) or 2 -> {
                        val l = gp.varint().toInt()
                        val end = gp.p + l
                        var iS = 0; var iE = 0; var aS = 0; var aE = 0; var oS = 0; var oE = 0; var kvS = 0; var kvE = 0
                        val d = Pb(blk.buf, gp.p, end)
                        while (d.more()) {
                            val t = d.varint().toInt()
                            when (t) {
                                (1 shl 3) or 2 -> { val n = d.varint().toInt(); iS = d.p; iE = d.p + n; d.p = iE }
                                (8 shl 3) or 2 -> { val n = d.varint().toInt(); aS = d.p; aE = d.p + n; d.p = aE }
                                (9 shl 3) or 2 -> { val n = d.varint().toInt(); oS = d.p; oE = d.p + n; d.p = oE }
                                (10 shl 3) or 2 -> { val n = d.varint().toInt(); kvS = d.p; kvE = d.p + n; d.p = kvE }
                                else -> d.skip(t and 7)
                            }
                        }
                        val ip = Pb(blk.buf, iS, iE)
                        val ap = Pb(blk.buf, aS, aE)
                        val op = Pb(blk.buf, oS, oE)
                        val kv = Pb(blk.buf, kvS, kvE)
                        var id = 0L; var la = 0L; var lo = 0L
                        while (ip.more()) {
                            id += zz(ip.varint()); la += zz(ap.varint()); lo += zz(op.varint())
                            var n = 0
                            if (kv.more()) {
                                while (true) {
                                    val k = kv.varint().toInt()
                                    if (k == 0) break
                                    val v = kv.varint().toInt()
                                    if (n < 64) { keyIdx[n] = k; valIdx[n] = v; n++ }
                                }
                            }
                            node(blk, state, id, la, lo, keyIdx, valIdx, n, pois)
                        }
                        gp.p = end
                    }
                    (1 shl 3) or 2 -> {
                        val l = gp.varint().toInt()
                        val end = gp.p + l
                        var id = 0L; var la = 0L; var lo = 0L
                        var kS = 0; var kE = 0; var vS = 0; var vE = 0
                        val d = Pb(blk.buf, gp.p, end)
                        while (d.more()) {
                            val t = d.varint().toInt()
                            when (t) {
                                (1 shl 3) -> id = zz(d.varint())
                                (2 shl 3) or 2 -> { val n = d.varint().toInt(); kS = d.p; kE = d.p + n; d.p = kE }
                                (3 shl 3) or 2 -> { val n = d.varint().toInt(); vS = d.p; vE = d.p + n; d.p = vE }
                                (8 shl 3) -> la = zz(d.varint())
                                (9 shl 3) -> lo = zz(d.varint())
                                else -> d.skip(t and 7)
                            }
                        }
                        var n = 0
                        val kp = Pb(blk.buf, kS, kE)
                        val vp = Pb(blk.buf, vS, vE)
                        while (kp.more() && vp.more() && n < 64) { keyIdx[n] = kp.varint().toInt(); valIdx[n] = vp.varint().toInt(); n++ }
                        node(blk, state, id, la, lo, keyIdx, valIdx, n, pois, absolute = true)
                        gp.p = end
                    }
                    else -> gp.skip(tag and 7)
                }
            }
        }
    }

    private class NodeState(val need: LongArray, val lat: IntArray, val lon: IntArray, val stats: ImportStats) {
        var ptr = 0
        var lastId = Long.MIN_VALUE
        var sorted = true
    }

    /** Koordinate in 1e-7 Grad. */
    private fun e7(blk: PrimBlock, off: Long, raw: Long): Int = ((off + blk.gran * raw) / 100L).toInt()

    private fun node(blk: PrimBlock, st: NodeState, id: Long, rawLat: Long, rawLon: Long, keyIdx: IntArray, valIdx: IntArray, n: Int, pois: ArrayList<Poi>, absolute: Boolean = false) {
        // gebraucht für eine Straße?
        val need = st.need
        if (need.isNotEmpty()) {
            if (id < st.lastId) st.sorted = false
            st.lastId = id
            var at = -1
            if (st.sorted) {
                var p = st.ptr
                while (p < need.size && need[p] < id) p++
                st.ptr = p
                if (p < need.size && need[p] == id) at = p
            } else {
                val b = java.util.Arrays.binarySearch(need, id)
                if (b >= 0) at = b
            }
            if (at >= 0) { st.lat[at] = e7(blk, blk.latOff, rawLat); st.lon[at] = e7(blk, blk.lonOff, rawLon) }
        }
        if (n == 0) return
        // Punkt von Interesse?
        var trigger = false
        for (i in 0 until n) if (blk.inSet(NODE_TRIGGER, keyIdx[i])) { trigger = true; break }
        if (!trigger) return
        var hw: String? = null; var rail: String? = null; var sign: String? = null; var calming = false
        var crossing: String? = null; var maxspeed = false; var markings: String? = null
        for (i in 0 until n) {
            when (blk.str(keyIdx[i])) {
                "highway" -> hw = blk.str(valIdx[i])
                "railway" -> rail = blk.str(valIdx[i])
                "traffic_sign" -> sign = blk.str(valIdx[i])
                "traffic_calming" -> calming = true
                "crossing" -> crossing = blk.str(valIdx[i])
                "crossing:markings" -> markings = blk.str(valIdx[i])
                "maxspeed" -> maxspeed = true
            }
        }
        val keep = sign == "city_limit" || hw == "speed_camera" || rail == "level_crossing" || hw == "crossing" || calming ||
            (maxspeed && sign != null)
        if (!keep) return
        val c = st.stats
        when {
            sign == "city_limit" -> c.citySigns++
            hw == "speed_camera" -> c.cameras++
            rail == "level_crossing" -> c.levelCrossings++
            hw == "crossing" -> { c.crossings++; if (crossing == "zebra" || markings == "zebra") c.zebras++ }
            calming -> c.calming++
            else -> c.speedSigns++
        }
        val la = e7(blk, blk.latOff, rawLat)
        val lo = e7(blk, blk.lonOff, rawLon)
        val sb = StringBuilder(160)
        sb.append("{\"type\":\"node\",\"id\":").append(id).append(",\"lat\":"); fmtE7(la, sb); sb.append(",\"lon\":"); fmtE7(lo, sb)
        sb.append(",\"tags\":{")
        var first = true
        for (i in 0 until n) {
            val key = blk.str(keyIdx[i])
            if (!NODE_KEYS.contains(key)) continue
            if (!first) sb.append(',')
            first = false
            sb.append('"'); esc(key, sb); sb.append("\":\""); esc(blk.str(valIdx[i]), sb); sb.append('"')
        }
        sb.append("}}")
        pois += Poi(la, lo, sb.toString())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Schritt 3: Kacheln

    private fun tileId(latIdx: Int, lonIdx: Int): Long = (latIdx.toLong() shl 32) or (lonIdx.toLong() and 0xffffffffL)

    private fun bandOf(latIdx: Int): Int = Math.floorDiv(latIdx, BAND_ROWS)

    /** Löst die Punkt-Nummern der Wege in Koordinaten auf und sortiert die Wege in Streifen-Dateien. */
    private fun splitIntoBands(waysTmp: File, need: LongArray, lat: IntArray, lon: IntArray, tmpDir: File, listener: ImportListener, base: Double): HashMap<Int, File> {
        val files = HashMap<Int, File>()
        val outs = HashMap<Int, DataOutputStream>()
        val total = max(1L, waysTmp.length()).toDouble()
        val weight = (1 - base) * 0.15
        try {
            DataInputStream(BufferedInputStream(FileInputStream(waysTmp), 1 shl 16)).use { inp ->
                var read = 0L
                var counter = 0
                var pl = IntArray(256)
                while (true) {
                    val id = try { inp.readLong() } catch (_: java.io.EOFException) { break }
                    val tl = inp.readInt()
                    val tb = ByteArray(tl); inp.readFully(tb)
                    val n = inp.readInt()
                    if (pl.size < n * 2) pl = IntArray(n * 2)
                    var m = 0
                    var minLa = Int.MAX_VALUE; var maxLa = Int.MIN_VALUE
                    for (i in 0 until n) {
                        val r = inp.readLong()
                        val at = java.util.Arrays.binarySearch(need, r)
                        if (at >= 0 && lat[at] != MISSING) {
                            pl[m * 2] = lat[at]; pl[m * 2 + 1] = lon[at]; m++
                            if (lat[at] < minLa) minLa = lat[at]
                            if (lat[at] > maxLa) maxLa = lat[at]
                        }
                    }
                    read += 8 + 4 + tl + 4 + 8L * n
                    if (m >= 2) {
                        val rLo = floor((minLa / 1e7 - TileMath.MARGIN_DEG) / TileMath.TILE_LAT).toInt()
                        val rHi = floor((maxLa / 1e7 + TileMath.MARGIN_DEG) / TileMath.TILE_LAT).toInt()
                        for (band in bandOf(rLo)..bandOf(rHi)) {
                            val o = outs.getOrPut(band) {
                                val f = File(tmpDir, "band_$band.bin")
                                files[band] = f
                                DataOutputStream(BufferedOutputStream(FileOutputStream(f), 1 shl 15))
                            }
                            o.writeLong(id); o.writeInt(tb.size); o.write(tb); o.writeInt(m)
                            for (i in 0 until m * 2) o.writeInt(pl[i])
                        }
                    }
                    if (++counter % 20000 == 0) {
                        if (listener.cancelled()) throw ImportCancelled()
                        listener.progress(3, STEPS, base + weight * min(1.0, read / total), "Kacheln schreiben", 0)
                    }
                }
            }
        } finally {
            for (o in outs.values) try { o.close() } catch (_: Exception) { }
        }
        return files
    }

    private class IntList { var a = IntArray(8); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun last(): Int = if (n == 0) -1 else a[n - 1] }

    private class BandWay(val id: Long, val tags: String, val xy: IntArray)

    /** Schreibt alle Kacheln eines Streifens. Gibt (Anzahl Kacheln, Bytes) zurück. */
    private fun writeBand(file: File, band: Int, poiByTile: HashMap<Long, ArrayList<String>>, outDir: File, nowMs: Long): Pair<Int, Long> {
        val ways = ArrayList<BandWay>()
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
            while (true) {
                val id = try { inp.readLong() } catch (_: java.io.EOFException) { break }
                val tl = inp.readInt()
                val tb = ByteArray(tl); inp.readFully(tb)
                val m = inp.readInt()
                val xy = IntArray(m * 2)
                for (i in xy.indices) xy[i] = inp.readInt()
                ways += BandWay(id, String(tb, Charsets.UTF_8), xy)
            }
        }
        val rowLo = band * BAND_ROWS
        val rowHi = rowLo + BAND_ROWS - 1
        val tl = HashMap<Long, IntList>()
        val M = TileMath.MARGIN_DEG
        val tmp = DoubleArray(2)
        for ((wi, w) in ways.withIndex()) {
            val xy = w.xy
            for (s in 0 until xy.size / 2 - 1) {
                val la0 = xy[s * 2] / 1e7; val lo0 = xy[s * 2 + 1] / 1e7
                val la1 = xy[s * 2 + 2] / 1e7; val lo1 = xy[s * 2 + 3] / 1e7
                val iLo = max(rowLo, floor((min(la0, la1) - M) / TileMath.TILE_LAT).toInt())
                val iHi = min(rowHi, floor((max(la0, la1) + M) / TileMath.TILE_LAT).toInt())
                val jLo = floor((min(lo0, lo1) - M) / TileMath.TILE_LON).toInt()
                val jHi = floor((max(lo0, lo1) + M) / TileMath.TILE_LON).toInt()
                for (i in iLo..iHi) for (j in jLo..jHi) {
                    val south = i * TileMath.TILE_LAT - M; val north = (i + 1) * TileMath.TILE_LAT + M
                    val west = j * TileMath.TILE_LON - M; val east = (j + 1) * TileMath.TILE_LON + M
                    if (segHitsRect(lo0, la0, lo1, la1, west, south, east, north, tmp)) {
                        val list = tl.getOrPut(tileId(i, j)) { IntList() }
                        if (list.last() != wi) list.add(wi)
                    }
                }
            }
        }
        var tiles = 0
        var bytes = 0L
        val sb = StringBuilder(1 shl 14)
        for (key in tl.keys.sorted()) {
            val latIdx = (key shr 32).toInt()
            val lonIdx = key.toInt()
            val list = tl[key]!!
            val lines = ArrayList<String>(list.n + 8)
            for (k in 0 until list.n) {
                val w = ways[list.a[k]]
                sb.setLength(0)
                sb.append("{\"type\":\"way\",\"id\":").append(w.id).append(",\"tags\":{").append(w.tags).append("},\"geometry\":[")
                for (p in 0 until w.xy.size / 2) {
                    if (p > 0) sb.append(',')
                    sb.append("{\"lat\":"); fmtE7(w.xy[p * 2], sb); sb.append(",\"lon\":"); fmtE7(w.xy[p * 2 + 1], sb); sb.append('}')
                }
                sb.append("]}")
                lines += sb.toString()
            }
            poiByTile[key]?.let { lines.addAll(it) }
            val f = File(outDir, TileKey(latIdx, lonIdx).fileName)
            val all = mergeExisting(f, lines)
            sb.setLength(0)
            sb.append(MARKER).append('\n')
            for ((i, l) in all.withIndex()) { sb.append(l); if (i < all.size - 1) sb.append(','); sb.append('\n') }
            sb.append("]}\n")
            val tmpFile = File(outDir, f.name + ".tmp")
            GZIPOutputStream(FileOutputStream(tmpFile)).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            if (!tmpFile.renameTo(f)) { f.delete(); tmpFile.renameTo(f) }
            f.setLastModified(nowMs)
            tiles++
            bytes += f.length()
        }
        return tiles to bytes
    }

    /** Führt die neuen Zeilen mit einer schon vorhandenen Import-Kachel zusammen (gleiche Nummer: neu gewinnt). Andere Dateien werden ersetzt. */
    private fun mergeExisting(f: File, fresh: List<String>): List<String> {
        if (!f.isFile || !isImportTile(f)) return fresh
        return try {
            val text = GZIPInputStream(FileInputStream(f)).use { String(it.readBytes(), Charsets.UTF_8) }
            val keys = HashSet<String>(fresh.size * 2)
            for (l in fresh) keys += lineKey(l)
            val out = ArrayList<String>(fresh.size + 16)
            for (raw in text.lineSequence().drop(1)) {
                if (raw.startsWith("]}") || raw.isBlank()) continue
                val line = raw.removeSuffix(",")
                if (lineKey(line) !in keys) out += line
            }
            out.addAll(fresh)
            out
        } catch (_: Exception) { fresh }
    }

    /** Schlüssel einer Element-Zeile: Art und Nummer, z. B. {"type":"way","id":123 */
    private fun lineKey(line: String): String {
        val a = line.indexOf(',')
        val b = if (a >= 0) line.indexOf(',', a + 1) else -1
        return if (b > 0) line.substring(0, b) else line
    }

    private fun segHitsRect(x0: Double, y0: Double, x1: Double, y1: Double, xmin: Double, ymin: Double, xmax: Double, ymax: Double, t: DoubleArray): Boolean {
        if (min(x0, x1) >= xmin && max(x0, x1) <= xmax && min(y0, y1) >= ymin && max(y0, y1) <= ymax) return true
        t[0] = 0.0; t[1] = 1.0
        val dx = x1 - x0
        val dy = y1 - y0
        return clip(-dx, x0 - xmin, t) && clip(dx, xmax - x0, t) && clip(-dy, y0 - ymin, t) && clip(dy, ymax - y0, t)
    }

    private fun clip(p: Double, q: Double, t: DoubleArray): Boolean {
        if (p == 0.0) return q >= 0
        val r = q / p
        if (p < 0) { if (r > t[1]) return false; if (r > t[0]) t[0] = r }
        else { if (r < t[0]) return false; if (r < t[1]) t[1] = r }
        return true
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Text

    /** 1e-7 Grad als Dezimalzahl mit 7 Nachkommastellen. */
    private fun fmtE7(v: Int, sb: StringBuilder) {
        var x = v.toLong()
        if (x < 0) { sb.append('-'); x = -x }
        sb.append(x / 10_000_000L).append('.')
        val frac = (x % 10_000_000L).toString()
        for (i in frac.length until 7) sb.append('0')
        sb.append(frac)
    }

    private fun esc(s: String, sb: StringBuilder) {
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(' ')
            else -> sb.append(c)
        }
    }
}
