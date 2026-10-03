package io.github.th3s1nc.osmandhudbridge

import io.github.th3s1nc.osmandhudbridge.limit.Backoff
import io.github.th3s1nc.osmandhudbridge.limit.LimitTracker
import io.github.th3s1nc.osmandhudbridge.limit.MatchOptions
import io.github.th3s1nc.osmandhudbridge.limit.NeighborIndex
import io.github.th3s1nc.osmandhudbridge.limit.RepeatSummary
import io.github.th3s1nc.osmandhudbridge.limit.ServerPool
import io.github.th3s1nc.osmandhudbridge.limit.Sign
import io.github.th3s1nc.osmandhudbridge.limit.SignTracker
import io.github.th3s1nc.osmandhudbridge.limit.Zone
import io.github.th3s1nc.osmandhudbridge.limit.SpeedLimitMatcher
import io.github.th3s1nc.osmandhudbridge.limit.TileCache
import io.github.th3s1nc.osmandhudbridge.limit.TileKey
import io.github.th3s1nc.osmandhudbridge.limit.TileMath
import io.github.th3s1nc.osmandhudbridge.limit.Way
import io.github.th3s1nc.osmandhudbridge.limit.PreloadPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos

class SpeedLimitLogicTest {
    @Test fun tileOfAndCovering() {
        val k = TileMath.tileOf(48.1, 11.5)
        assertTrue(48.1 >= k.south && 48.1 < k.north && 11.5 >= k.west && 11.5 < k.east)
        // mitten in der Kachel: nur die eigene
        val mid = TileKey(2405, 383)
        assertEquals(listOf(mid), TileMath.covering((mid.south + mid.north) / 2, (mid.west + mid.east) / 2))
        // direkt an der Ecke: bis zu vier Kacheln, die eigene zuerst
        val c = TileMath.covering(mid.north - 0.0001, mid.east - 0.0001)
        assertEquals(4, c.size)
        assertEquals(mid, c[0])
        // negative Koordinaten rechnen sauber (Floor, nicht Abschneiden)
        val w = TileMath.tileOf(-0.001, -0.001)
        assertEquals(TileKey(-1, -1), w)
    }

    @Test fun prefetchFollowsTravelDirection() {
        val t = TileKey(2405, 383)
        val lonMid = (t.west + t.east) / 2
        val latNearNorth = t.north - 0.003 // ca. 330 m vor der Nordkante
        // Fahrt nach Norden: Nordnachbar wird mitgeladen
        val n = TileMath.wanted(latNearNorth, lonMid, 0.0, 15f)
        assertEquals(listOf(t, TileKey(2406, 383)), n)
        // Fahrt nach Süden: der Südnachbar (nie der nördliche)
        assertEquals(listOf(t, TileKey(2404, 383)), TileMath.wanted(latNearNorth, lonMid, 180.0, 15f))
        // Stehen oder unbekannte Richtung: nur die eigene Kachel
        assertEquals(listOf(t), TileMath.wanted(latNearNorth, lonMid, 0.0, 0.5f))
        assertEquals(listOf(t), TileMath.wanted(latNearNorth, lonMid, null, 15f))
        // Vorausschau 3 km: schon früh wird der Nordnachbar geholt, aber nie bei Fahrt nach Süden aus der Südkante heraus
        assertEquals(listOf(t, TileKey(2406, 383)), TileMath.wanted(t.south + 0.002, lonMid, 0.0, 15f))
        assertEquals(listOf(t, TileKey(2404, 383)), TileMath.wanted(t.south + 0.018, lonMid, 180.0, 15f))
        // Nordosten in der Ecke: Nord-, Ost- und Diagonalkachel
        val ne = TileMath.wanted(t.north - 0.002, t.east - 0.003, 45.0, 15f)
        assertEquals(listOf(t, TileKey(2406, 383), TileKey(2405, 384), TileKey(2406, 384)), ne)
    }

    @Test fun backoffIsShortAndCapped() {
        assertEquals(0L, Backoff.delayMs(0))
        assertEquals(2_000L, Backoff.delayMs(1))
        assertEquals(4_000L, Backoff.delayMs(2))
        assertEquals(8_000L, Backoff.delayMs(3))
        assertEquals(15_000L, Backoff.delayMs(4))
        assertEquals(15_000L, Backoff.delayMs(50))
    }

    @Test fun cacheStoresReadsAndTrims() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hudcache_${System.nanoTime()}")
        try {
            val cache = TileCache(dir, maxFiles = 3)
            val k = TileKey(1, 2)
            assertNull(cache.read(k, System.currentTimeMillis()))
            cache.write(k, "{\"elements\":[]}")
            val e = cache.read(k, System.currentTimeMillis())
            assertNotNull(e)
            assertEquals("{\"elements\":[]}", e!!.text)
            assertTrue(e.ageMs < TileCache.REFRESH_MS)
            // Alter wird aus der Dateizeit berechnet
            File(dir, k.fileName).setLastModified(System.currentTimeMillis() - 40L * 24 * 3600 * 1000)
            assertTrue(cache.read(k, System.currentTimeMillis())!!.ageMs > TileCache.REFRESH_MS)
            assertFalse(cache.isFresh(k, System.currentTimeMillis(), TileCache.REFRESH_MS))
            assertTrue(cache.isFresh(k, System.currentTimeMillis(), TileCache.VALID_MS))
            // mehr als maxFiles: die ältesten fliegen raus, die neueste bleibt
            for (i in 10..14) {
                cache.write(TileKey(i, i), "x$i")
                File(dir, TileKey(i, i).fileName).setLastModified(System.currentTimeMillis() - (20 - i) * 1000L)
            }
            cache.trimNow()
            assertTrue((dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 99) <= 3)
            assertNotNull(cache.read(TileKey(14, 14), System.currentTimeMillis()))
            assertNull(cache.read(k, System.currentTimeMillis()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cacheTrimsByBytesOldestFirst() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hudcache_b_${System.nanoTime()}")
        try {
            val cache = TileCache(dir)
            for (i in 1..6) {
                cache.write(TileKey(i, i), "x".repeat(1000) + i)
                File(dir, TileKey(i, i).fileName).setLastModified(System.currentTimeMillis() - (10 - i) * 60_000L)
            }
            val (n, bytes) = cache.stats()
            assertEquals(6, n)
            assertTrue(bytes > 0)
            cache.maxBytes = bytes / 2
            cache.trimNow()
            val (n2, bytes2) = cache.stats()
            assertTrue(n2 in 1..4)
            assertTrue(bytes2 <= bytes / 2)
            assertNotNull(cache.read(TileKey(6, 6), System.currentTimeMillis())) // neueste bleibt
            assertNull(cache.read(TileKey(1, 1), System.currentTimeMillis()))   // älteste weg
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------ gleiche Straße halten

    private val lat0 = 48.0
    private val lon0 = 11.0
    private fun pt(e: Double, n: Double) = doubleArrayOf(lat0 + n / 110_540.0, lon0 + e / (111_320.0 * cos(Math.toRadians(lat0))))
    private fun way(id: Long, a: Pair<Double, Double>, b: Pair<Double, Double>, tags: Map<String, String>) =
        Way(id, listOf(pt(a.first, a.second), pt(b.first, b.second)), tags)

    @Test fun sameRoadKeepsLimitLong() {
        val t = LimitTracker()
        assertEquals(0, t.update(70, 0))
        assertEquals(70, t.update(70, 1_000))
        // Abschnitt ohne Tag: normal nach 8 s weg
        assertEquals(70, t.update(null, 5_000))
        assertEquals(0, LimitTracker().also { it.update(70, 0); it.update(70, 1_000) }.update(null, 10_000))
        // gleiche Straße: 90 s halten, danach weg
        assertEquals(70, t.update(null, 60_000, LimitTracker.HOLD_SAME_ROAD_MS))
        assertEquals(0, t.update(null, 100_000, LimitTracker.HOLD_SAME_ROAD_MS))
    }

    @Test fun junctionPrefersRoadWeAreOn() {
        // Hauptstraße mit 70 und eine Parallelstraße mit 30 im gleichen Abstand: ohne Vorgabe unklar, mit Vorgabe eindeutig
        val main = way(1, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "primary", "name" to "Hauptstr", "maxspeed" to "70"))
        val side = way(2, -200.0 to 3.0, 200.0 to 3.0, mapOf("highway" to "residential", "name" to "Nebenweg", "maxspeed" to "30"))
        val p = pt(0.0, 1.5)
        val none = SpeedLimitMatcher.matchAll(listOf(main, side), p[0], p[1], 90.0, 12f, 5f)
        assertNull(none.limit)
        val pref = SpeedLimitMatcher.matchAll(listOf(main, side), p[0], p[1], 90.0, 12f, 5f, "Hauptstr")
        assertEquals(70, pref.limit)
        assertEquals("Hauptstr", pref.street)
        assertFalse(pref.noCandidates)
        // Vorgabe für eine Straße, die gar nicht dabei ist: wie ohne Vorgabe
        assertNull(SpeedLimitMatcher.matchAll(listOf(main, side), p[0], p[1], 90.0, 12f, 5f, "Anderswo").limit)
    }

    @Test fun reportsNoCandidates() {
        val main = way(1, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "primary", "maxspeed" to "70"))
        val p = pt(0.0, 300.0)
        val r = SpeedLimitMatcher.matchAll(listOf(main), p[0], p[1], 90.0, 12f, 5f)
        assertTrue(r.noCandidates)
        assertNull(r.limit)
    }

    @Test fun guessesOnlyWithoutTag() {
        assertEquals(50, SpeedLimitMatcher.guessLimit(mapOf("highway" to "residential")))
        assertEquals(50, SpeedLimitMatcher.guessLimit(mapOf("highway" to "secondary", "lit" to "yes")))
        assertEquals(100, SpeedLimitMatcher.guessLimit(mapOf("highway" to "secondary")))
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "motorway")))
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "trunk")))
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "service")))
        // vorhandenes Tag (auch "none"/"signals"): nie raten
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "primary", "maxspeed" to "none")))
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "primary", "maxspeed:forward" to "signals")))
    }

    @Test fun matcherMarksGuessedLimits() {
        val w = way(1, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "tertiary", "name" to "Landweg"))
        val p = pt(0.0, 1.0)
        assertNull(SpeedLimitMatcher.matchAll(listOf(w), p[0], p[1], 90.0, 12f, 5f).limit)
        val g = SpeedLimitMatcher.matchAll(listOf(w), p[0], p[1], 90.0, 12f, 5f, null, MatchOptions(guess = true)).limit
        assertEquals(100 + SpeedLimitMatcher.ESTIMATE_OFFSET, g)
        // echtes Tag schlägt Annahme
        val real = way(2, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "tertiary", "maxspeed" to "70"))
        assertEquals(70, SpeedLimitMatcher.matchAll(listOf(real), p[0], p[1], 90.0, 12f, 5f, null, MatchOptions(guess = true)).limit)
    }

    @Test fun cacheIsGzippedAndReadsOldPlainFiles() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hudcache2_${System.nanoTime()}")
        try {
            val cache = TileCache(dir)
            val k = TileKey(5, 6)
            val text = "{\"elements\":[" + "{\"type\":\"way\"},".repeat(500) + "{}]}"
            cache.write(k, text)
            val onDisk = File(dir, k.fileName)
            assertTrue(onDisk.length() < text.length / 4) // gepackt
            assertEquals(text, cache.read(k, System.currentTimeMillis())!!.text)
            // alte, unkomprimierte Datei aus Version 0.11.0 bis 0.11.4
            val k2 = TileKey(7, 8)
            File(dir, k2.fileName).writeText("{\"elements\":[]}")
            assertEquals("{\"elements\":[]}", cache.read(k2, System.currentTimeMillis())!!.text)
        } finally { dir.deleteRecursively() }
    }

    @Test fun preloadPlanCoversRadiusNearestFirst() {
        val lat = 48.0107
        val lon = 11.0121
        assertEquals(0, PreloadPlanner.tilesInRadius(lat, lon, 0).size)
        val t10 = PreloadPlanner.tilesInRadius(lat, lon, 10)
        val t50 = PreloadPlanner.tilesInRadius(lat, lon, 50)
        assertTrue("10 km: ${t10.size}", t10.size in 55..110)
        assertTrue("50 km: ${t50.size}", t50.size in 1400..2000)
        assertEquals(TileMath.tileOf(lat, lon), t10[0]) // eigene Kachel zuerst
        assertTrue(t10.toSet().size == t10.size)
        assertTrue(t10.all { it in t50 })
    }

    @Test fun extraTagsGiveRealLimits() {
        assertEquals(30, SpeedLimitMatcher.extraTagLimit(mapOf("highway" to "residential", "zone:maxspeed" to "DE:30")))
        assertEquals(50, SpeedLimitMatcher.extraTagLimit(mapOf("highway" to "residential", "source:maxspeed" to "DE:urban")))
        assertEquals(100, SpeedLimitMatcher.extraTagLimit(mapOf("highway" to "tertiary", "maxspeed:type" to "DE:rural")))
        assertEquals(30, SpeedLimitMatcher.extraTagLimit(mapOf("source:maxspeed" to "DE:zone30")))
        assertNull(SpeedLimitMatcher.extraTagLimit(mapOf("source:maxspeed" to "DE:motorway")))
        assertNull(SpeedLimitMatcher.extraTagLimit(mapOf("highway" to "primary")))
        // maxspeed:type allein ist kein ausdrückliches Tempo-Tag
        assertFalse(SpeedLimitMatcher.hasExplicitMaxspeed(mapOf("maxspeed:type" to "DE:urban")))
        assertTrue(SpeedLimitMatcher.hasExplicitMaxspeed(mapOf("maxspeed" to "none")))
        // wirkt nur mit Schalter und ist ein echtes (nicht geschätztes) Limit
        val w = way(1, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "residential", "zone:maxspeed" to "DE:30"))
        val p = pt(0.0, 1.0)
        assertNull(SpeedLimitMatcher.matchAll(listOf(w), p[0], p[1], 90.0, 12f, 5f).limit)
        assertEquals(30, SpeedLimitMatcher.matchAll(listOf(w), p[0], p[1], 90.0, 12f, 5f, null, MatchOptions(extraTags = true)).limit)
    }

    @Test fun neighborSectionsShareLimit() {
        val a = way(1, -400.0 to 0.0, -200.0 to 0.0, mapOf("highway" to "primary", "name" to "Landstr", "maxspeed" to "70"))
        val mid = way(2, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "primary", "name" to "Landstr"))
        val b = way(3, 200.0 to 0.0, 400.0 to 0.0, mapOf("highway" to "primary", "name" to "Landstr", "maxspeed" to "70"))
        val other = way(4, 200.0 to 0.0, 200.0 to 300.0, mapOf("highway" to "primary", "name" to "Andere", "maxspeed" to "30"))
        val all = listOf(a, mid, b, other)
        assertEquals(70, NeighborIndex(all).limitFor(mid))
        // unterschiedliche Limits der Nachbarn: unklar
        val b2 = way(3, 200.0 to 0.0, 400.0 to 0.0, mapOf("highway" to "primary", "name" to "Landstr", "maxspeed" to "50"))
        assertNull(NeighborIndex(listOf(a, mid, b2)).limitFor(mid))
        // andere Straße am selben Punkt zählt nicht
        assertNull(NeighborIndex(listOf(mid, other)).limitFor(mid))
        // ohne Namen keine Zuordnung
        val noName = way(5, -200.0 to 0.0, 200.0 to 0.0, mapOf("highway" to "primary"))
        assertNull(NeighborIndex(listOf(a, noName, b)).limitFor(noName))
        // im Matcher: nur mit Schalter, als Schätzwert markiert
        val p = pt(0.0, 1.0)
        val opts = MatchOptions(guess = true, neighbors = NeighborIndex(all))
        assertEquals(70 + SpeedLimitMatcher.ESTIMATE_OFFSET, SpeedLimitMatcher.matchAll(all.take(3), p[0], p[1], 90.0, 12f, 5f, null, opts).limit)
        assertNull(SpeedLimitMatcher.matchAll(all.take(3), p[0], p[1], 90.0, 12f, 5f).limit)
    }

    private fun sign(id: Long, e: Double, n: Double, dir: String?) =
        pt(e, n).let { Sign(id, it[0], it[1], buildMap { put("traffic_sign", "city_limit"); put("name", "Musterdorf"); if (dir != null) put("direction", dir) }) }

    @Test fun signsSetZoneByHeading() {
        // Straße von West nach Ost (Richtung 90 Grad), Schild mittendrin, direction=forward = stadteinwärts nach Osten
        val road = way(1, -500.0 to 0.0, 500.0 to 0.0, mapOf("highway" to "primary"))
        val s = sign(10, 0.0, 1.0, "forward")
        val t = SignTracker()
        val before = pt(-60.0, 0.0)
        assertNull(t.update(before[0], before[1], 90.0, 14f, listOf(s), listOf(road), 0))
        assertNull(t.zone)
        val at = pt(-5.0, 0.0)
        val msg = t.update(at[0], at[1], 90.0, 14f, listOf(s), listOf(road), 4_000)
        assertNotNull(msg)
        assertEquals(Zone.INNER, t.zone)
        // gleiches Schild nicht gleich noch einmal auswerten
        assertNull(t.update(at[0], at[1], 90.0, 14f, listOf(s), listOf(road), 5_000))
        // Gegenrichtung (nach Westen) am selben Schild: Ortsausgang
        val t2 = SignTracker()
        t2.update(at[0], at[1], 270.0, 14f, listOf(s), listOf(road), 0)
        assertEquals(Zone.OUTER, t2.zone)
        // quer zur Straße oder ohne Richtung: ignorieren
        val t3 = SignTracker()
        t3.update(at[0], at[1], 0.0, 14f, listOf(s), listOf(road), 0)
        assertNull(t3.zone)
        val t4 = SignTracker()
        t4.update(at[0], at[1], 90.0, 14f, listOf(sign(11, 0.0, 1.0, null)), listOf(road), 0)
        assertNull(t4.zone)
        // Winkel-Angabe (zeigt stadtauswärts nach Westen = 270 Grad): wer nach Osten fährt, fährt hinein
        val t5 = SignTracker()
        t5.update(at[0], at[1], 90.0, 14f, listOf(sign(12, 0.0, 1.0, "270")), listOf(road), 0)
        assertEquals(Zone.INNER, t5.zone)
    }

    @Test fun zoneExpiresAndDrivesGuess() {
        val road = way(1, -500.0 to 0.0, 5000.0 to 0.0, mapOf("highway" to "primary"))
        val s = sign(10, 0.0, 1.0, "forward")
        val t = SignTracker(expireM = 2_500.0)
        val at = pt(-5.0, 0.0)
        t.update(at[0], at[1], 90.0, 14f, listOf(s), listOf(road), 0)
        assertEquals(Zone.INNER, t.zone)
        val far = pt(3_000.0, 0.0)
        t.update(far[0], far[1], 90.0, 14f, emptyList(), listOf(road), 10_000)
        assertNull(t.zone) // nach 2,5 km ohne Schild unbekannt
        assertEquals(50, SpeedLimitMatcher.guessLimit(mapOf("highway" to "primary"), Zone.INNER))
        assertEquals(100, SpeedLimitMatcher.guessLimit(mapOf("highway" to "primary", "lit" to "no"), Zone.OUTER))
        assertEquals(50, SpeedLimitMatcher.guessLimit(mapOf("highway" to "residential"), Zone.OUTER))
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "motorway"), Zone.INNER))
        assertNull(SpeedLimitMatcher.guessLimit(mapOf("highway" to "service"), Zone.INNER))
        // Zone schlägt "beleuchtet/unbeleuchtet"
        assertEquals(100, SpeedLimitMatcher.guessLimit(mapOf("highway" to "primary", "lit" to "yes"), Zone.OUTER))
    }

    @Test
    fun corridorLiesAheadOfTheDriver() {
        val lat = 48.0107; val lon = 11.0121
        val here = TileMath.tileOf(lat, lon)
        val north = PreloadPlanner.corridor(lat, lon, 0.0, 20)
        assertEquals(here, north.first())
        assertEquals(north.size, north.toSet().size)
        assertTrue(north.all { it.latIdx >= here.latIdx - 1 })
        assertTrue(north.any { it.latIdx >= here.latIdx + 8 })
        assertTrue(north.size in 10..40)
        val east = PreloadPlanner.corridor(lat, lon, 90.0, 20)
        assertTrue(east.any { it.lonIdx >= here.lonIdx + 6 })
        assertTrue(east.all { it.lonIdx >= here.lonIdx })
        assertTrue(PreloadPlanner.corridor(lat, lon, 0.0, 0).isEmpty())
    }

    @Test
    fun serverPool_pausiertAusgefallene() {
        val p = ServerPool(listOf("a", "b", "c", "d"), 1000L)
        assertEquals(listOf("a", "b", "c"), p.race(0L, 3))
        p.fail("a", 0L)
        assertEquals(listOf("b", "c", "d"), p.race(10L, 3))
        assertEquals(1, p.pausedCount(10L))
        // nach der Pause ist a wieder dabei
        assertEquals(listOf("a", "b", "c"), p.race(1001L, 3))
        // Erfolg hebt die Pause sofort auf
        p.fail("b", 2000L)
        p.ok("b")
        assertEquals(0, p.pausedCount(2001L))
    }

    @Test
    fun serverPool_alleAusgefallen_trotzdemAlleProbieren() {
        val p = ServerPool(listOf("a", "b"), 1000L)
        p.fail("a", 0L); p.fail("b", 0L)
        assertEquals(listOf("a", "b"), p.usable(5L))
    }

    @Test
    fun serverPool_nextGehtReihumUndUeberspringtAusgefallene() {
        val p = ServerPool(listOf("a", "b", "c"), 1000L)
        p.fail("b", 0L)
        val seen = (1..4).map { p.next(5L) }
        assertTrue(!seen.contains("b"))
        assertTrue(seen.contains("a") && seen.contains("c"))
    }

    @Test
    fun serverPool_eigenePauseBeiAbweisung() {
        val p = ServerPool(listOf("a", "b"), 1000L)
        p.fail("a", 0L, 900_000L)
        assertEquals(listOf("b"), p.usable(500_000L))
        assertEquals(listOf("a", "b"), p.usable(900_001L))
    }

    @Test
    fun repeatSummary_zaehltGruende() {
        val r = RepeatSummary()
        assertEquals(null, r.take())
        repeat(6) { r.add("timeout") }
        r.add("overpass-api.de: HTTP 403".substringAfter(": "))
        assertEquals("7 Fehlversuche (6x timeout, 1x HTTP 403)", r.take())
        assertTrue(r.isEmpty())
        assertEquals(null, r.take())
    }
}
