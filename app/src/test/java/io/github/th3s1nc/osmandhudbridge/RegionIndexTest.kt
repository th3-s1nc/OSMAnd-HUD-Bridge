package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.RegionDownload
import io.github.th3s1nc.osmandhudbridge.limit.RegionIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionIndexTest {
    // Aufbau wie bei Geofabrik (index-v1-nogeom.json): Features mit properties id, name, parent, urls
    private val json = """
    {"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"id":"europe","name":"Europe","urls":{"pbf":"https://download.geofabrik.de/europe-latest.osm.pbf"}}},
      {"type":"Feature","properties":{"id":"germany","parent":"europe","name":"Germany","iso3166-1:alpha2":["DE"],"urls":{"pbf":"https://download.geofabrik.de/europe/germany-latest.osm.pbf"}}},
      {"type":"Feature","properties":{"id":"bayern","parent":"germany","name":"Bayern","urls":{"pbf":"https://download.geofabrik.de/europe/germany/bayern-latest.osm.pbf"}}},
      {"type":"Feature","properties":{"id":"oberpfalz","parent":"bayern","name":"Oberpfalz","urls":{"pbf":"https://download.geofabrik.de/europe/germany/bayern/oberpfalz-latest.osm.pbf","bz2":"x"}}},
      {"type":"Feature","properties":{"id":"oberbayern","parent":"bayern","name":"Oberbayern","urls":{"pbf":"https://download.geofabrik.de/europe/germany/bayern/oberbayern-latest.osm.pbf"}}},
      {"type":"Feature","properties":{"id":"asia","name":"Asia","urls":{"pbf":"https://download.geofabrik.de/asia-latest.osm.pbf"}}},
      {"type":"Feature","properties":{"id":"kaputt"}},
      {"type":"Feature","properties":{"id":"ohnepbf","parent":"europe","name":"Ohne Datei","urls":{"bz2":"http://x"}}}
    ]}
    """.trimIndent()

    @Test
    fun baumWirdAufgebaut() {
        val ix = RegionIndex.parse(json)
        assertEquals("europe", ix.startId())
        assertEquals(listOf("Germany", "Ohne Datei"), ix.children("europe").map { it.name })
        assertEquals(listOf("Oberbayern", "Oberpfalz"), ix.children("bayern").map { it.name })
        assertTrue(ix.children("oberpfalz").isEmpty())
    }

    @Test
    fun wegUndUrl() {
        val ix = RegionIndex.parse(json)
        assertEquals(listOf("Europe", "Germany", "Bayern", "Oberpfalz"), ix.path("oberpfalz").map { it.name })
        assertEquals("https://download.geofabrik.de/europe/germany/bayern/oberpfalz-latest.osm.pbf", ix.get("oberpfalz")!!.pbfUrl)
        assertNull(ix.get("ohnepbf")!!.pbfUrl)
        assertNull(ix.get("gibtsnicht"))
    }

    @Test
    fun kaputtesJsonWirftFehler() {
        var fehler = false
        try { RegionIndex.parse("{\"features\": [") } catch (e: Exception) { fehler = true }
        assertTrue(fehler)
    }

    @Test
    fun ohneEuropaFaengtDieAuswahlObenAn() {
        val ix = RegionIndex.parse("""{"features":[{"properties":{"id":"a","name":"A"}}]}""")
        assertNull(ix.startId())
        assertEquals(listOf("A"), ix.children(null).map { it.name })
    }

    @Test
    fun groessenText() {
        assertEquals("185 MB", RegionDownload.sizeText(185L * 1_048_576L))
        assertEquals("1,5 GB", RegionDownload.sizeText(1_610_612_736L))
        assertEquals("unbekannt", RegionDownload.sizeText(0))
    }

    @Test
    fun anteilUndPlatz() {
        assertTrue(Math.abs(RegionDownload.fraction(42, 100) - 0.42) < 1e-9)
        assertTrue(RegionDownload.fraction(5, 0) == 0.0)
        val mb = 1_048_576L
        assertTrue(RegionDownload.enoughSpace(1000 * mb, 185 * mb))
        assertTrue(!RegionDownload.enoughSpace(300 * mb, 185 * mb))
        // schon vorhandene Teildatei zählt mit
        assertTrue(RegionDownload.enoughSpace(300 * mb, 185 * mb, 250 * mb))
    }

    @Test
    fun weitermachenHinterTeildatei() {
        assertEquals(500L, RegionDownload.resumeOffset(500, 1000))
        assertEquals(0L, RegionDownload.resumeOffset(1000, 1000)) // schon voll: von vorn
        assertEquals(0L, RegionDownload.resumeOffset(0, 1000))
        assertEquals(700L, RegionDownload.resumeOffset(700, 0)) // Gesamtgröße unbekannt
        assertEquals("oberpfalz", RegionDownload.nameFromUrl("https://download.geofabrik.de/europe/germany/bayern/oberpfalz-latest.osm.pbf"))
    }
}
