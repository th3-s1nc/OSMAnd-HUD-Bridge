package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.CurveIndex
import io.github.th3s1nc.osmandhudbridge.limit.CurveLevel
import io.github.th3s1nc.osmandhudbridge.limit.CurveWarn
import io.github.th3s1nc.osmandhudbridge.limit.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveWarnTest {
    private val lat0 = 49.0
    private val lon0 = 12.0
    private val kx = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private val ky = 110_540.0

    private fun pt(x: Double, y: Double) = doubleArrayOf(lat0 + y / ky, lon0 + x / kx)

    /** Straße nach Norden von y=0 bis y=[lead], dann Rechtskurve (90 Grad, Radius [r]) nach Osten, dann [tail] m gerade. */
    private fun bendRoad(r: Double, lead: Double = 400.0, tail: Double = 300.0): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var y = 0.0
        while (y < lead) { out += pt(0.0, y); y += 5.0 }
        // Kreisbogen: Mittelpunkt (r, lead)
        var a = 0.0
        val da = 4.0 / r
        while (a < Math.PI / 2) { out += pt(r - r * Math.cos(a), lead + r * Math.sin(a)); a += da }
        val ex = r; val ey = lead + r
        var x = 0.0
        while (x <= tail) { out += pt(ex + x, ey); x += 5.0 }
        return out
    }

    private fun way(id: Long, pts: List<DoubleArray>, tags: Map<String, String> = mapOf("highway" to "tertiary", "name" to "Testweg")) = Way(id, pts, tags)

    /** Fährt von y=[fromY] nach Norden mit [kmh]; gibt zurück, bei welchem y der Ton kam (null = nie), und die Zahl der Töne. */
    private fun drive(ways: List<Way>, kmh: Double, level: Int = 1, fromY: Double = 0.0, toY: Double = 420.0, limit: Int = 0, inner: Boolean = false): Pair<Double?, Int> {
        val w = CurveWarn(); val idx = CurveIndex(ways)
        var first: Double? = null; var n = 0
        var y = fromY
        while (y < toY) {
            val hit = w.update(ways, idx, lat0 + y / ky, lon0, 0.0, (kmh / 3.6).toFloat(), level, limit, inner)
            if (hit) { n++; if (first == null) first = y }
            y += kmh / 3.6 // 1 Hz
        }
        return first to n
    }

    @Test
    fun scharfeKurveWarntEinmalUndRechtzeitig() {
        val ways = listOf(way(1, bendRoad(25.0)))
        val (at, n) = drive(ways, 80.0)
        assertEquals(1, n)
        // Kurve beginnt bei y=400; bei 80 km/h und Kurventempo ca. 36 km/h: Bremsweg ca. 150 m
        assertTrue("Ton bei y=$at", at != null && at in 150.0..380.0)
    }

    @Test
    fun langsamOderWeiteKurveOderGeradeKeinTon() {
        assertEquals(0, drive(listOf(way(1, bendRoad(25.0))), 40.0).second)   // schon langsam genug
        assertEquals(0, drive(listOf(way(1, bendRoad(150.0))), 90.0).second)  // weite Kurve
        val straight = (0..100).map { pt(0.0, it * 5.0) }
        assertEquals(0, drive(listOf(way(1, straight)), 100.0).second)
    }

    @Test
    fun imOrtKeinTon() {
        val road = bendRoad(25.0)
        assertEquals(0, drive(listOf(way(1, road, mapOf("highway" to "residential", "name" to "A"))), 80.0).second)
        assertEquals(0, drive(listOf(way(1, road, mapOf("highway" to "tertiary", "lit" to "yes"))), 80.0).second)
        assertEquals(0, drive(listOf(way(1, road, mapOf("highway" to "tertiary", "maxspeed" to "50"))), 80.0).second)
        assertEquals(0, drive(listOf(way(1, road)), 80.0, limit = 50).second)
        assertEquals(0, drive(listOf(way(1, road)), 80.0, inner = true).second)
    }

    @Test
    fun empfindlichkeitStufen() {
        // R=45 m: nur bei "Normal" (bis 50 m) und "Viel" (bis 60 m), nicht bei "Wenig" (bis 40 m)
        val ways = listOf(way(1, bendRoad(45.0)))
        assertEquals(0, drive(ways, 90.0, level = 0).second)
        assertEquals(1, drive(ways, 90.0, level = 1).second)
        assertEquals(1, drive(ways, 90.0, level = 2).second)
        assertTrue(CurveLevel.accel(0) > CurveLevel.accel(1) && CurveLevel.accel(1) > CurveLevel.accel(2))
    }

    @Test
    fun straszeInZweiStueckenMitGleichemNamen() {
        val all = bendRoad(25.0)
        val cut = all.indexOfFirst { it[0] > lon0 + 0.00001 } // kurz hinter dem Bogenanfang teilen? lieber vor der Kurve
        val splitAt = 60 // Punkt auf der Geraden, 300 m
        val a = way(1, all.subList(0, splitAt + 1))
        val b = way(2, all.subList(splitAt, all.size))
        assertEquals(1, drive(listOf(a, b), 80.0).second)
        // anderer Name: kein Weiterverfolgen, die Kurve liegt außerhalb von Stück a
        val b2 = way(2, all.subList(splitAt, all.size), mapOf("highway" to "tertiary", "name" to "Anderer"))
        assertEquals(0, drive(listOf(a, b2), 80.0, toY = 290.0).second)
    }

    @Test
    fun gegenrichtungUndKeineRichtungKeinFehler() {
        val ways = listOf(way(1, bendRoad(25.0).reversed()))
        // Straße ist umgekehrt gezeichnet, Fahrt nach Norden: gleiches Ergebnis
        assertEquals(1, drive(ways, 80.0).second)
        val w = CurveWarn()
        assertFalse(w.update(ways, CurveIndex(ways), lat0, lon0, null, 20f, 1))
    }
}
