package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.Camera
import io.github.th3s1nc.osmandhudbridge.limit.CameraImport
import io.github.th3s1nc.osmandhudbridge.limit.CameraKind
import io.github.th3s1nc.osmandhudbridge.limit.CameraStore
import io.github.th3s1nc.osmandhudbridge.limit.CameraWarn
import io.github.th3s1nc.osmandhudbridge.limit.ImportedCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImportTest {
    private fun wpt(lat: Double, lon: Double, id: Int, cat: String) = """<gpx:wpt lat="$lat" lon="$lon">
<gpx:name>[$id]</gpx:name>
<gpx:extensions><gpxd:WptExtension><gpxd:POICategory Cat="$cat"></gpxd:POICategory></gpxd:WptExtension></gpx:extensions>
</gpx:wpt>
"""

    @Test
    fun artUndLimitAusDemKategorienamen() {
        assertEquals(Triple(CameraKind.SPEED, 50, false), CameraImport.classify("SCDB_Tempo_50"))
        assertEquals(Triple(CameraKind.SPEED, 0, false), CameraImport.classify("SCDB_Tempo_variabel"))
        assertEquals(Triple(CameraKind.RED_LIGHT, 30, false), CameraImport.classify("SCDB_Ampel_30"))
        assertEquals(Triple(CameraKind.RED_LIGHT, 0, false), CameraImport.classify("SCDB_Ampel"))
        assertEquals(Triple(CameraKind.SECTION, 80, false), CameraImport.classify("SCDB_Abschnitt_80"))
        assertEquals(Triple(CameraKind.TUNNEL, 0, false), CameraImport.classify("SCDB_Tunnel"))
        assertEquals(Triple(CameraKind.SPEED, 0, true), CameraImport.classify("SCDB_Kamera"))
    }

    @Test
    fun gpxLesenUndZusammenlegen() {
        val kam = "<gpx>" + wpt(50.1, 8.1, 7, "SCDB_Kamera") + wpt(50.2, 8.2, 8, "SCDB_Kamera") + "</gpx>"
        val tempo = "<gpx>" + wpt(50.1, 8.1, 7, "SCDB_Tempo_50") + "</gpx>"
        val ampel = "<gpx>" + wpt(50.2, 8.2, 8, "SCDB_Ampel_30") + wpt(50.3, 8.3, 9, "SCDB_Tunnel") + "</gpx>"
        val all = CameraImport.parseGpx(kam) + CameraImport.parseGpx(tempo) + CameraImport.parseGpx(ampel)
        assertEquals(5, all.size)
        val m = CameraImport.merge(all).sortedBy { it.id }
        assertEquals(3, m.size)                                  // Nummer 7 und 8 je einmal, dazu 9
        assertEquals(CameraKind.SPEED, m[0].kind); assertEquals(50, m[0].limitKmh)        // Tempo_50 gewinnt gegen Kamera
        assertEquals(CameraKind.RED_LIGHT, m[1].kind); assertEquals(30, m[1].limitKmh)    // Ampel_30 gewinnt gegen Kamera
        assertEquals(CameraKind.TUNNEL, m[2].kind)
    }

    @Test
    fun nahePunkteOhneNummerWerdenZusammengelegt() {
        val a = "<gpx>" + wpt(50.0, 8.0, 1, "SCDB_Tempo_50") + wpt(50.0001, 8.0, 2, "SCDB_Tempo_50") + wpt(50.01, 8.0, 3, "SCDB_Tempo_50") + "</gpx>"
        val m = CameraImport.merge(CameraImport.parseGpx(a))
        assertEquals(2, m.size)  // 1 und 2 liegen ca. 11 m auseinander
    }

    @Test
    fun csvHinUndZurueck() {
        val list = listOf(ImportedCamera(7, 50.12345, 8.54321, CameraKind.SECTION, 80), ImportedCamera(-1_000_000_000L, 47.0, 9.0, CameraKind.SPEED, 0))
        val back = CameraImport.fromCsv(CameraImport.toCsv(list))
        assertEquals(2, back.size)
        assertEquals(7L, back[0].id); assertTrue(Math.abs(back[0].lat - 50.12345) < 1e-9); assertEquals(80, back[0].limitKmh)
        assertEquals(CameraKind.SPEED, back[1].kind)
    }

    @Test
    fun speichernUndLaden() {
        val dir = java.io.File.createTempFile("camstore", "").also { it.delete(); it.mkdirs() }
        try {
            assertEquals(0, CameraStore.load(dir).size)
            CameraStore.save(dir, listOf(ImportedCamera(1, 50.0, 8.0, CameraKind.SPEED, 50)))
            assertEquals(1, CameraStore.load(dir).size)
            CameraStore.clear(dir)
            assertEquals(0, CameraStore.load(dir).size)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun kombinierenFiltertArtenUndLegtMitOsmZusammen() {
        val own = listOf(
            ImportedCamera(1, 50.0000, 8.0, CameraKind.SPEED, 50),
            ImportedCamera(2, 50.0020, 8.0, CameraKind.RED_LIGHT, 30),
            ImportedCamera(3, 50.0030, 8.0, CameraKind.TUNNEL, 0)
        )
        val osm = listOf(Camera(900, 50.00001, 8.0, emptyMap()), Camera(901, 50.0040, 8.0, mapOf("maxspeed" to "70")))
        val all = CameraImport.combine(osm, own, 50.0, 8.0) { it != CameraKind.TUNNEL }
        assertEquals(setOf(-1L, -2L, 901L), all.map { it.id }.toSet())  // OSM 900 liegt auf Nr. 1, Tunnel ist aus
        assertEquals("50", all.first { it.id == -1L }.tags["scdb_limit"])
    }

    @Test
    fun warnungKenntDasLimitAmBlitzer() {
        val w = CameraWarn()
        val cams = CameraImport.combine(emptyList(), listOf(ImportedCamera(5, 50.002, 8.0, CameraKind.SPEED, 70)), 50.0, 8.0) { true }
        val hit = w.update(cams, 50.0, 8.0, 0.0, 27.8f, 1)
        assertTrue(hit.distanceM != null)
        assertEquals(70, hit.limitKmh)
    }
}
