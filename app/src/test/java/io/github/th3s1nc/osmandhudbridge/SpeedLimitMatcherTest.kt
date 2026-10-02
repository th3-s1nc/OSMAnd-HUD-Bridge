package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.LimitTracker
import io.github.th3s1nc.osmandhudbridge.limit.SpeedLimitMatcher
import io.github.th3s1nc.osmandhudbridge.limit.Way
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class SpeedLimitMatcherTest {
    private val lat0 = 48.0
    private val lon0 = 11.0
    private fun pt(eastM: Double, northM: Double) =
        doubleArrayOf(lat0 + northM / 110_540.0, lon0 + eastM / (111_320.0 * cos(Math.toRadians(lat0))))

    private fun way(id: Long, vararg p: Pair<Double, Double>, tags: Map<String, String>) =
        Way(id, p.map { pt(it.first, it.second) }, tags)

    private fun at(e: Double, n: Double) = pt(e, n).let { it[0] to it[1] }

    @Test fun parsesMaxspeedValues() {
        assertEquals(50, SpeedLimitMatcher.parseMaxspeed("50"))
        assertEquals(50, SpeedLimitMatcher.parseMaxspeed("DE:urban"))
        assertEquals(100, SpeedLimitMatcher.parseMaxspeed("DE:rural"))
        assertEquals(80, SpeedLimitMatcher.parseMaxspeed("50 mph"))
        assertEquals(null, SpeedLimitMatcher.parseMaxspeed("none"))
        assertEquals(null, SpeedLimitMatcher.parseMaxspeed("signals"))
        assertEquals(null, SpeedLimitMatcher.parseMaxspeed(null))
    }

    @Test fun matchesOwnRoadAndIgnoresCrossingRoad() {
        val a = way(1, -200.0 to 0.0, 200.0 to 0.0, tags = mapOf("highway" to "residential", "maxspeed" to "50"))
        val cross = way(2, 0.0 to -200.0, 0.0 to 200.0, tags = mapOf("highway" to "primary", "maxspeed" to "100"))
        val (lat, lon) = at(0.0, 2.0)
        assertEquals(50, SpeedLimitMatcher.match(listOf(a, cross), lat, lon, 90.0, 12f, 5f))
        // Querstraße ist in Fahrtrichtung Norden die richtige
        assertEquals(100, SpeedLimitMatcher.match(listOf(a, cross), lat, lon, 0.0, 12f, 5f))
    }

    @Test fun ambiguousWhenStandingAtJunction() {
        val a = way(1, -200.0 to 0.0, 200.0 to 0.0, tags = mapOf("highway" to "residential", "maxspeed" to "50"))
        val cross = way(2, 0.0 to -200.0, 0.0 to 200.0, tags = mapOf("highway" to "primary", "maxspeed" to "100"))
        val (lat, lon) = at(1.0, 1.0)
        assertEquals(null, SpeedLimitMatcher.match(listOf(a, cross), lat, lon, null, 0f, 5f))
    }

    @Test fun roadWithoutLimitBlocksParallelRoadsLimit() {
        val near = way(1, -200.0 to 0.0, 200.0 to 0.0, tags = mapOf("highway" to "residential"))
        val far = way(2, -200.0 to 12.0, 200.0 to 12.0, tags = mapOf("highway" to "residential", "maxspeed" to "30"))
        val (lat, lon) = at(0.0, 1.0)
        assertEquals(null, SpeedLimitMatcher.match(listOf(near, far), lat, lon, 90.0, 12f, 5f))
    }

    @Test fun streetNameMatchedAndAmbiguityGivesNull() {
        val a = way(1, -200.0 to 0.0, 200.0 to 0.0, tags = mapOf("highway" to "residential", "name" to "Dorfstra\u00dfe"))
        val cross = way(2, 0.0 to -200.0, 0.0 to 200.0, tags = mapOf("highway" to "primary", "ref" to "St 2615"))
        val (lat, lon) = at(0.0, 2.0)
        assertEquals("Dorfstra\u00dfe", SpeedLimitMatcher.matchAll(listOf(a, cross), lat, lon, 90.0, 12f, 5f).street)
        assertEquals("St 2615", SpeedLimitMatcher.matchAll(listOf(a, cross), lat, lon, 0.0, 12f, 5f).street)
        val (l2, o2) = at(1.0, 1.0)
        assertEquals(null, SpeedLimitMatcher.matchAll(listOf(a, cross), l2, o2, null, 0f, 5f).street)
    }

    @Test fun directionalMaxspeed() {
        val w = way(1, -200.0 to 0.0, 200.0 to 0.0, tags = mapOf("highway" to "primary", "maxspeed:forward" to "70"))
        val (lat, lon) = at(0.0, 1.0)
        assertEquals(70, SpeedLimitMatcher.match(listOf(w), lat, lon, 90.0, 12f, 5f))
        assertEquals(null, SpeedLimitMatcher.match(listOf(w), lat, lon, 270.0, 12f, 5f))
    }

    @Test fun farAwayRoadIsIgnored() {
        val w = way(1, -200.0 to 60.0, 200.0 to 60.0, tags = mapOf("highway" to "primary", "maxspeed" to "100"))
        val (lat, lon) = at(0.0, 0.0)
        assertEquals(null, SpeedLimitMatcher.match(listOf(w), lat, lon, 90.0, 12f, 5f))
    }

    @Test fun trackerConfirmsAndHolds() {
        val t = LimitTracker(confirmCount = 2, holdMs = 8000)
        assertEquals(0, t.update(50, 0))
        assertEquals(50, t.update(50, 1000))
        assertEquals(50, t.update(70, 2000)) // erst ein Treffer
        assertEquals(50, t.update(null, 3000)) // Lücke überbrückt
        assertEquals(50, t.update(70, 4000)) // Zähler wurde zurückgesetzt
        assertEquals(70, t.update(70, 5000))
        assertEquals(70, t.update(null, 9000))
        assertEquals(0, t.update(null, 14000)) // Lücke zu lang
    }
}
