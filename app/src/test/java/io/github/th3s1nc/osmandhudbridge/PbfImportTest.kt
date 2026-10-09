package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.ImportCancelled
import io.github.th3s1nc.osmandhudbridge.limit.ImportListener
import io.github.th3s1nc.osmandhudbridge.limit.ImportLog
import io.github.th3s1nc.osmandhudbridge.limit.ImportStats
import io.github.th3s1nc.osmandhudbridge.limit.PbfImport
import io.github.th3s1nc.osmandhudbridge.limit.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Baut kleine PBF-Dateien von Hand (Protobuf), damit der Import ohne echte Kartendatei geprüft werden kann. */
private class Pbw {
    val out = ByteArrayOutputStream()
    fun varint(v0: Long): Pbw { var v = v0; while (true) { val b = (v and 0x7f).toInt(); v = v ushr 7; if (v == 0L) { out.write(b); break } else out.write(b or 0x80) }; return this }
    fun tag(f: Int, w: Int) = varint(((f shl 3) or w).toLong())
    fun vint(f: Int, v: Long): Pbw { tag(f, 0); return varint(v) }
    fun bytes(f: Int, b: ByteArray): Pbw { tag(f, 2); varint(b.size.toLong()); out.write(b); return this }
    fun str(f: Int, s: String) = bytes(f, s.toByteArray())
    fun packed(f: Int, vals: List<Long>): Pbw { val p = Pbw(); for (v in vals) p.varint(v); return bytes(f, p.out.toByteArray()) }
    fun done(): ByteArray = out.toByteArray()
}

private fun zig(v: Long): Long = (v shl 1) xor (v shr 63)

private class MiniPbf {
    private val file = ByteArrayOutputStream()

    private fun blob(type: String, raw: ByteArray) {
        val def = Deflater()
        def.setInput(raw); def.finish()
        val buf = ByteArray(raw.size + 64)
        val n = def.deflate(buf)
        def.end()
        val blob = Pbw().vint(2, raw.size.toLong()).bytes(3, buf.copyOf(n)).done()
        val hdr = Pbw().str(1, type).vint(3, blob.size.toLong()).done()
        file.write(byteArrayOf((hdr.size ushr 24).toByte(), (hdr.size ushr 16).toByte(), (hdr.size ushr 8).toByte(), hdr.size.toByte()))
        file.write(hdr); file.write(blob)
    }

    fun header() {
        blob("OSMHeader", Pbw().str(4, "OsmSchema-V0.6").str(4, "DenseNodes").str(5, "Sort.Type_then_ID").done())
    }

    class N(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String> = emptyMap())
    class W(val id: Long, val refs: List<Long>, val tags: Map<String, String>)

    private fun table(all: Collection<String>): Pair<ByteArray, Map<String, Int>> {
        val list = listOf("") + all.distinct()
        val t = Pbw()
        for (s in list) t.str(1, s)
        return t.done() to list.withIndex().associate { it.value to it.index }
    }

    fun nodes(nodes: List<N>) {
        val (tab, idx) = table(nodes.flatMap { it.tags.keys + it.tags.values })
        val ids = ArrayList<Long>(); val lats = ArrayList<Long>(); val lons = ArrayList<Long>(); val kv = ArrayList<Long>()
        var pi = 0L; var pla = 0L; var plo = 0L
        for (n in nodes) {
            ids += zig(n.id - pi); pi = n.id
            val la = Math.round(n.lat * 1e7); val lo = Math.round(n.lon * 1e7)
            lats += zig(la - pla); pla = la; lons += zig(lo - plo); plo = lo
            for ((k, v) in n.tags) { kv += idx[k]!!.toLong(); kv += idx[v]!!.toLong() }
            kv += 0L
        }
        val dense = Pbw().packed(1, ids).packed(8, lats).packed(9, lons).packed(10, kv).done()
        val group = Pbw().bytes(2, dense).done()
        blob("OSMData", Pbw().bytes(1, tab).bytes(2, group).done())
    }

    fun ways(ways: List<W>) {
        val (tab, idx) = table(ways.flatMap { it.tags.keys + it.tags.values })
        val group = Pbw()
        for (w in ways) {
            val deltas = ArrayList<Long>(); var p = 0L
            for (r in w.refs) { deltas += zig(r - p); p = r }
            val way = Pbw().vint(1, w.id)
                .packed(2, w.tags.keys.map { idx[it]!!.toLong() })
                .packed(3, w.tags.values.map { idx[it]!!.toLong() })
                .packed(8, deltas).done()
            group.bytes(3, way)
        }
        blob("OSMData", Pbw().bytes(1, tab).bytes(2, group.done()).done())
    }

    fun bytes(): ByteArray = file.toByteArray()
}

class PbfImportTest {
    private val quiet = object : ImportListener {
        override fun progress(step: Int, steps: Int, fraction: Double, text: String, tilesDone: Int) {}
        override fun cancelled() = false
    }

    private fun tmp(): File = File.createTempFile("pbf", "").let { it.delete(); it.mkdirs(); it }

    private fun run(pbf: ByteArray, out: File, listener: ImportListener = quiet) =
        PbfImport.run({ ByteArrayInputStream(pbf) }, pbf.size.toLong(), out, File(out.parentFile, out.name + "_tmp"), listener)

    private fun text(f: File): String = GZIPInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }

    private fun tile(out: File, lat: Int, lon: Int) = File(out, TileKey(lat, lon).fileName)

    /** Region A: Straße über die Kachelgrenze bei 12,03 Grad, eine Nicht-Straße, Ortsschild, Zebrastreifen, Bahnübergang. */
    private fun regionA(): ByteArray {
        val p = MiniPbf()
        p.header()
        p.nodes(listOf(
            MiniPbf.N(1, 49.011, 12.020),
            MiniPbf.N(2, 49.011, 12.040),
            MiniPbf.N(3, 49.015, 12.045),
            MiniPbf.N(4, 49.011, 12.021, mapOf("traffic_sign" to "city_limit", "name" to "Teststadt", "wiki" to "x")),
            MiniPbf.N(5, 49.0112, 12.035, mapOf("highway" to "crossing", "crossing" to "zebra")),
            MiniPbf.N(6, 49.0111, 12.045, mapOf("railway" to "level_crossing")),
            MiniPbf.N(7, 49.0113, 12.036, mapOf("amenity" to "cafe")),
            MiniPbf.N(8, 49.0114, 12.037, mapOf("traffic_calming" to "bump"))
        ))
        p.ways(listOf(
            MiniPbf.W(100, listOf(1, 2), mapOf("highway" to "residential", "name" to "Hauptstr", "maxspeed" to "50", "foo" to "bar")),
            MiniPbf.W(101, listOf(1, 2), mapOf("highway" to "footway")),
            MiniPbf.W(102, listOf(2, 3), mapOf("highway" to "primary", "maxspeed:forward" to "70", "building" to "no"))
        ))
        return p.bytes()
    }

    @Test
    fun strasseUeberDieKachelgrenzeLiegtInBeidenKacheln() {
        val out = tmp()
        val r = run(regionA(), out)
        assertEquals(2, r.ways)
        assertEquals(2, r.tiles)
        val west = text(tile(out, 2450, 400))
        val east = text(tile(out, 2450, 401))
        assertTrue(west.startsWith(PbfImport.MARKER))
        assertTrue(west.contains("\"id\":100,"))
        assertTrue(east.contains("\"id\":100,"))
        assertTrue(east.contains("\"id\":102,"))
        assertFalse(west.contains("\"id\":102,"))
        out.deleteRecursively()
    }

    @Test
    fun nurBefahrbareStrassenMitDenGebrauchtenTags() {
        val out = tmp()
        run(regionA(), out)
        val west = text(tile(out, 2450, 400))
        assertTrue(west.contains("\"highway\":\"residential\""))
        assertTrue(west.contains("\"name\":\"Hauptstr\""))
        assertTrue(west.contains("\"maxspeed\":\"50\""))
        assertFalse(west.contains("footway"))
        assertFalse(west.contains("\"foo\""))
        assertTrue(text(tile(out, 2450, 401)).contains("\"maxspeed:forward\":\"70\""))
        out.deleteRecursively()
    }

    @Test
    fun punkteOrtsschildZebraBahnuebergangSchwelle() {
        val out = tmp()
        run(regionA(), out)
        val west = text(tile(out, 2450, 400))
        val east = text(tile(out, 2450, 401))
        assertTrue(west.contains("\"traffic_sign\":\"city_limit\""))
        assertTrue(west.contains("\"name\":\"Teststadt\""))
        assertFalse(west.contains("wiki"))
        assertTrue(east.contains("\"crossing\":\"zebra\""))
        assertTrue(east.contains("\"railway\":\"level_crossing\""))
        assertTrue(east.contains("\"traffic_calming\":\"bump\""))
        assertFalse(east.contains("cafe"))
        out.deleteRecursively()
    }

    @Test
    fun koordinatenStimmen() {
        val out = tmp()
        run(regionA(), out)
        val west = text(tile(out, 2450, 400))
        assertTrue(west.contains("{\"lat\":49.0110000,\"lon\":12.0200000}"))
        assertTrue(west.contains("{\"lat\":49.0110000,\"lon\":12.0400000}"))
        out.deleteRecursively()
    }

    @Test
    fun zweiteRegionWirdMitDerErstenZusammengefuehrt() {
        val out = tmp()
        run(regionA(), out)
        val p = MiniPbf()
        p.header()
        p.nodes(listOf(MiniPbf.N(2, 49.011, 12.040), MiniPbf.N(3, 49.015, 12.045), MiniPbf.N(9, 49.012, 12.050)))
        p.ways(listOf(
            MiniPbf.W(102, listOf(2, 3), mapOf("highway" to "primary", "maxspeed:forward" to "80")),
            MiniPbf.W(200, listOf(3, 9), mapOf("highway" to "tertiary"))
        ))
        run(p.bytes(), out)
        val east = text(tile(out, 2450, 401))
        // 100 (Region A) bleibt, 102 gibt es nur einmal (neu gewinnt), 200 kommt dazu
        assertTrue(east.contains("\"id\":100,"))
        assertEquals(1, Regex("\"type\":\"way\",\"id\":102,").findAll(east).count())
        assertTrue(east.contains("\"maxspeed:forward\":\"80\""))
        assertFalse(east.contains("\"maxspeed:forward\":\"70\""))
        assertTrue(east.contains("\"id\":200,"))
        assertTrue(east.contains("level_crossing")) // Punkt aus Region A bleibt
        out.deleteRecursively()
    }

    @Test
    fun gleichenImportZweimalIstHarmlos() {
        val out = tmp()
        run(regionA(), out)
        val first = text(tile(out, 2450, 401))
        run(regionA(), out)
        assertEquals(first, text(tile(out, 2450, 401)))
        out.deleteRecursively()
    }

    @Test
    fun alteOnlineKachelWirdErsetztUndEntfernenLoeschtNurImportierte() {
        val out = tmp()
        val old = tile(out, 2450, 400)
        GZIPOutputStream(old.outputStream()).use { it.write("{\"elements\":[{\"type\":\"way\",\"id\":5}]}".toByteArray()) }
        val other = tile(out, 1, 1)
        GZIPOutputStream(other.outputStream()).use { it.write("{\"elements\":[]}".toByteArray()) }
        run(regionA(), out)
        assertTrue(text(old).startsWith(PbfImport.MARKER))
        assertFalse(text(old).contains("\"id\":5}"))
        assertEquals(2, PbfImport.countImported(out))
        assertEquals(2, PbfImport.removeImported(out))
        assertTrue(other.isFile)
        assertFalse(old.isFile)
        out.deleteRecursively()
    }

    @Test
    fun abbruchWirftUndRaeumtAuf() {
        val out = tmp()
        var thrown = false
        val cancel = object : ImportListener {
            override fun progress(step: Int, steps: Int, fraction: Double, text: String, tilesDone: Int) {}
            override fun cancelled() = true
        }
        try { run(regionA(), out, cancel) } catch (_: ImportCancelled) { thrown = true }
        assertTrue(thrown)
        assertFalse(File(out.parentFile, out.name + "_tmp").exists())
        out.deleteRecursively()
    }

    @Test
    fun keinePbfDateiGibtFehler() {
        val out = tmp()
        var msg = ""
        try { run("das ist keine Karte, nur Text".toByteArray(), out) } catch (e: java.io.IOException) { msg = e.message ?: "" }
        assertTrue(msg.isNotEmpty())
        out.deleteRecursively()
    }

    @Test
    fun fortschrittSteigtBisEins() {
        val out = tmp()
        var last = -1.0
        var steps = HashSet<Int>()
        var monotone = true
        val l = object : ImportListener {
            override fun progress(step: Int, steps2: Int, fraction: Double, text: String, tilesDone: Int) {
                if (fraction + 1e-9 < last) monotone = false
                last = fraction; steps += step
            }
            override fun cancelled() = false
        }
        run(regionA(), out, l)
        assertTrue(monotone)
        assertTrue(last > 0.99)
        assertEquals(setOf(1, 2, 3), steps)
        out.deleteRecursively()
    }

    @Test
    fun zusammenfassungZaehltDieDaten() {
        val out = tmp()
        val st = run(regionA(), out).stats
        assertEquals(2, st.roads)
        assertEquals(2, st.withLimit)
        assertEquals(1, st.citySigns)
        assertEquals(1, st.levelCrossings)
        assertEquals(1, st.crossings)
        assertEquals(1, st.zebras)
        assertEquals(1, st.calming)
        assertTrue(st.describe().contains("Straßen: 2"))
        out.deleteRecursively()
    }

    @Test
    fun statistikZeileHinUndZurueck() {
        val a = ImportStats().also { it.roads = 1234; it.cameras = 4; it.calming = 410 }
        val b = ImportStats.fromLine(a.toLine())!!
        assertEquals(1234, b.roads); assertEquals(4, b.cameras); assertEquals(410, b.calming)
        assertEquals(null, ImportStats.fromLine("1,2,3"))
        assertEquals(null, ImportStats.fromLine("müll"))
    }

    @Test
    fun importLogNeuesVorneGleicherNameErsetztMaximumAchtEintraege() {
        var t: String? = null
        fun e(n: String, roads: Int) = ImportLog.Entry(n, "01.01.2026", ImportStats().also { it.roads = roads })
        t = ImportLog.add(t, e("a.pbf", 1))
        t = ImportLog.add(t, e("b.pbf", 2))
        var l = ImportLog.parse(t)
        assertEquals(listOf("b.pbf", "a.pbf"), l.map { it.name })
        t = ImportLog.add(t, e("a.pbf", 99))
        l = ImportLog.parse(t)
        assertEquals(listOf("a.pbf", "b.pbf"), l.map { it.name })
        assertEquals(99, l[0].stats.roads)
        for (i in 1..12) t = ImportLog.add(t, e("f$i.pbf", i))
        assertEquals(8, ImportLog.parse(t).size)
        assertEquals("f12.pbf", ImportLog.parse(t)[0].name)
        assertTrue(ImportLog.describe(ImportLog.parse(t)).contains("f12.pbf (01.01.2026)"))
        assertTrue(ImportLog.parse(null).isEmpty())
    }

    @Test
    fun summeUndAlterDerImporte() {
        fun e(n: String, d: String, r: Int, c: Int) = ImportLog.Entry(n, d, ImportStats().also { it.roads = r; it.citySigns = c })
        val l = listOf(e("a", "01.06.2026", 100, 3), e("b", "10.01.2026", 50, 2))
        val t = ImportLog.total(l)
        assertEquals(150, t.roads); assertEquals(5, t.citySigns)
        val today = java.time.LocalDate.of(2026, 10, 9)
        assertEquals(272L, ImportLog.oldest(l, today)!!.first)
        assertEquals("10.01.2026", ImportLog.oldest(l, today)!!.second)
        assertTrue(ImportLog.staleHint(l, today)!!.contains("10.01.2026"))
        assertEquals(null, ImportLog.staleHint(listOf(e("a", "01.06.2026", 1, 1)), today))
        assertEquals(null, ImportLog.staleHint(listOf(e("a", "kaputt", 1, 1)), today))
        assertEquals(null, ImportLog.staleHint(emptyList(), today))
    }

    /** Nur mit echter Datei (Umgebungsvariable PBF_TEST_FILE), zum Prüfen von Hand. */
    @Test
    fun echteDateiOptional() {
        val path = System.getenv("PBF_TEST_FILE") ?: return
        val f = File(path)
        if (!f.isFile) return
        val out = tmp()
        val r = PbfImport.run({ f.inputStream() }, f.length(), out, File(out.parentFile, out.name + "_tmp"), quiet)
        assertTrue(r.tiles > 0)
        assertTrue(r.ways > 0)
        out.deleteRecursively()
    }
}
