package io.github.th3s1nc.osmandhudbridge.limit

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Holt Straßen samt maxspeed-Tag über die Overpass-API (OSM) und liefert das Limit zur aktuellen Position.
 *
 * Ablauf: Die Karte wird in Kacheln (ca. 2 x 2 km) geladen, auf dem Gerät zwischengespeichert (Festplatte, gepackt, 180 Tage,
 * nach 30 Tagen wird im Hintergrund erneuert) und die Kachel in Fahrtrichtung wird vorab geladen. Zugeordnet wird lokal bei
 * jedem GPS-Fix. Für Kacheln, die jetzt gebraucht werden, werden alle Server gleichzeitig gefragt (der schnellste gewinnt),
 * bei Fehlern wird schnell wiederholt (höchstens 15 s Pause). Optional lädt die App im WLAN die Umgebung im Voraus. Auf der gleichen Straße bleibt das Limit
 * erhalten, auch wenn ein Abschnitt kein Tag hat oder eine Kreuzung unklar ist. Nur auf dem Main-Thread benutzen.
 */
class SpeedLimitProvider(
    cacheDir: File?,
    private val onLimit: (Int, Boolean) -> Unit,
    private val onStreet: (String?) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor()
    /** Eigener Thread für das Vorladen, damit eine gebrauchte Kachel nie hinter einer Vorlade-Anfrage wartet. */
    private val preloadExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = LimitTracker()
    private val signTracker = SignTracker()
    private var tilesVersion = 0
    private var indexKey: Pair<List<TileKey>, Int>? = null
    private var index: NeighborIndex? = null
    private val cache = cacheDir?.let { TileCache(it) }
    private val tourStore = cacheDir?.parentFile?.let { TourStore(File(it, "tours")) }
    private var tourBusy = false
    private var nextTourAt = 0L
    private var tourFailures = 0
    private val tourFails = RepeatSummary()
    private val preFails = RepeatSummary()
    private var tourLoaded = 0 // in dieser Sitzung geladene Tour-Kacheln (nur Executor-Thread)
    /** true, solange Kacheln einer importierten Tour fehlen (dann wartet das Vorladen des Kreises). */
    @Volatile var tourPending = false
        private set

    /** Gibt es importierte Touren, bei denen noch Kacheln fehlen könnten? (Dateien lesen, nicht oft aufrufen) */
    fun toursOpen(): Boolean = try { tourStore?.list()?.isNotEmpty() == true } catch (_: Exception) { false }

    /** Obergrenze für den Zwischenspeicher in MB. */
    var cacheMaxMb = 2048
        set(v) {
            field = v
            val c = cache ?: return
            c.maxBytes = v.toLong() * 1024 * 1024
            preloadExecutor.execute { c.trimNow() }
        }

    /** Geladene Kacheln im Speicher (die zuletzt benutzten bleiben). */
    private val tiles = object : LinkedHashMap<TileKey, TileData>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, TileData>?) = size > MAX_TILES
    }
    private var fetching = false
    private var nextFetchAt = 0L
    private var failures = 0
    private var lastReported = -1
    private var lastStreet: String? = null
    private var streetGoodAt = 0L
    private var heldStreet: String? = null
    private var shutdown = false

    // Vorladen
    private var center: DoubleArray? = null
    private var planCenter: DoubleArray? = null
    private var planNeeded = true
    private val preloadQueue = java.util.ArrayDeque<TileKey>()
    private var preloadTotal = 0
    private var preloadDone = 0
    private var preloadBusy = false
    private var preloadFailures = 0
    private var nextPreloadAt = 0L
    // Streifen in Fahrtrichtung
    private var heading: Double? = null
    private var movingSpeed = 0f
    private var aheadFrom: DoubleArray? = null

    /** Radius des Vorladens in km, 0 = aus. */
    var preloadKm = 0
        set(v) {
            if (field == v) return
            field = v
            planNeeded = true
            if (v <= 0) { preloadQueue.clear(); preloadActive = false; BridgeBus.updatePreload("Aus") }
        }

    /** true, solange das Vorladen gerade arbeitet (dann beendet sich der Dienst nicht wegen fehlendem HUD). */
    @Volatile var preloadActive = false
        private set

    /** Straßen ohne Tempo-Tag: Limit schätzen (Deutschland 50/100), siehe [SpeedLimitMatcher.guessLimit]. */
    var guessMissing = false
        set(v) { field = v; if (!v) signTracker.reset() }

    /** Zusätzliche Karten-Hinweise (Tempo-Zone, Quellenangabe) als echtes Limit nutzen. */
    var useExtraTags = true
    /** Beim Schätzen: Limit der anschließenden Abschnitte derselben Straße übernehmen. */
    var useNeighbors = true
    /** Beim Schätzen: Ortsschilder auswerten (innerorts/außerorts). */
    var useSigns = true
        set(v) { field = v; if (!v) signTracker.reset() }

    var enabled = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) {
                tracker.reset()
                lastReported = -1
                heldStreet = null
                onLimit(0, false)
                lastStreet = null
                onStreet(null)
                BridgeBus.limit = "Limit: aus"
            } else {
                BridgeBus.limit = "Limit: warte auf Position"
            }
        }

    fun shutdown() {
        shutdown = true
        executor.shutdownNow()
        preloadExecutor.shutdownNow()
    }

    fun onLocation(loc: Location) {
        if (!enabled) return
        center = doubleArrayOf(loc.latitude, loc.longitude)
        heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > AHEAD_MIN_SPEED) loc.bearing.toDouble() else null
        movingSpeed = if (loc.hasSpeed()) loc.speed else 0f
        val now = SystemClock.elapsedRealtime()
        val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null
        val speed = if (loc.hasSpeed()) loc.speed else 0f

        // fehlende Kacheln (eigene, dann Vorausschau) nachladen, eine nach der anderen
        if (!fetching && now >= nextFetchAt) {
            TileMath.wanted(loc.latitude, loc.longitude, bearing, speed).firstOrNull { it !in tiles }?.let(::startFetch)
        }

        val keys = TileMath.covering(loc.latitude, loc.longitude)
        val datas = keys.mapNotNull { tiles[it] }
        val ways = datas.flatMap { it.ways }
        val haveData = ways.isNotEmpty() || TileMath.tileOf(loc.latitude, loc.longitude) in tiles
        var zone: Zone? = null
        if (guessMissing && useSigns && datas.isNotEmpty()) {
            val signs = datas.flatMap { it.signs }.distinctBy { it.id }
            signTracker.update(loc.latitude, loc.longitude, bearing, speed, signs, ways, now)?.let { BridgeBus.log(it) }
            zone = signTracker.zone
        }
        var nb: NeighborIndex? = null
        if (guessMissing && useNeighbors && ways.isNotEmpty()) {
            val k = keys to tilesVersion
            if (indexKey != k) { index = NeighborIndex(ways); indexKey = k }
            nb = index
        }
        val res = if (haveData) {
            SpeedLimitMatcher.matchAll(
                ways, loc.latitude, loc.longitude, bearing, speed,
                if (loc.hasAccuracy()) loc.accuracy else 20f, heldStreet,
                MatchOptions(extraTags = useExtraTags, guess = guessMissing, neighbors = nb, zone = zone)
            )
        } else null
        val raw = res?.limit
        val street = res?.street
        if (raw != null) heldStreet = street
        // Straßenname: kurze Lücken (Kreuzungen, unklare Zuordnung) überbrücken
        if (street != null) streetGoodAt = now
        val shownStreet = street ?: if (now - streetGoodAt <= STREET_HOLD_MS) lastStreet else null
        if (shownStreet != lastStreet) {
            lastStreet = shownStreet
            onStreet(shownStreet)
        }
        val hold = when {
            res == null -> null
            street != null && street == heldStreet -> LimitTracker.HOLD_SAME_ROAD_MS
            res.noCandidates -> LimitTracker.HOLD_NO_MATCH_MS
            else -> null
        }
        val coded = tracker.update(raw, now, hold)
        val est = coded >= SpeedLimitMatcher.ESTIMATE_OFFSET
        val limit = if (est) coded - SpeedLimitMatcher.ESTIMATE_OFFSET else coded
        if (coded != lastReported) {
            lastReported = coded
            val tag = if (est) " (geschätzt)" else ""
            BridgeBus.limit = if (limit > 0) "Limit: $limit km/h" + (if (est) " (geschätzt)" else " (OSM)") else if (haveData) "Limit: unbekannt" else "Limit: Karte wird geladen"
            val why = when {
                limit > 0 -> ""
                !haveData -> " (Karte fehlt noch)"
                res?.noCandidates == true -> " (keine Straße in der Nähe in den Kartendaten)"
                else -> " (Straße ohne Tempo-Angabe)"
            }
            BridgeBus.log("Tempolimit: ${if (limit > 0) "$limit km/h$tag" else "unbekannt"}$why")
            onLimit(limit, est)
        } else if (limit == 0) {
            // Text immer nachziehen, auch wenn sich das Limit nicht ändert (sonst bleibt "Karte wird geladen" stehen)
            BridgeBus.limit = if (haveData) "Limit: unbekannt"
                else if (failures > 0) "Limit: Abfrage fehlgeschlagen, neuer Versuch" else "Limit: Karte wird geladen"
        }
    }

    /** Letzte bekannte Position (z. B. aus dem Gedächtnis), damit auch ohne GPS-Fix vorgeladen werden kann. */
    fun setCenter(lat: Double, lon: Double) {
        if (center == null) { center = doubleArrayOf(lat, lon); planNeeded = true }
    }

    private class Loaded(val data: TileData, val source: String, val refresh: Boolean)

    private fun startFetch(key: TileKey) {
        fetching = true
        executor.execute {
            val result = try { Result.success(loadTile(key)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (shutdown) return@post
                fetching = false
                result.onSuccess { r ->
                    tiles[key] = r.data
                    tilesVersion++
                    failures = 0
                    nextFetchAt = SystemClock.elapsedRealtime() + 300
                    BridgeBus.log("OSM: Kachel ${key.latIdx}/${key.lonIdx} ${r.source}, ${r.data.ways.size} Straßen, " +
                        "${r.data.ways.count { w -> w.tags.keys.any { k -> k.startsWith("maxspeed") } }} mit Limit, ${r.data.signs.size} Ortsschilder")
                }.onFailure {
                    failures++
                    val wait = Backoff.delayMs(failures)
                    nextFetchAt = SystemClock.elapsedRealtime() + wait
                    BridgeBus.log("OSM-Abfrage fehlgeschlagen (${it.javaClass.simpleName}: ${it.message}), neuer Versuch in ${wait / 1000} s")
                }
            }
            // Kachel ist gültig, aber älter als 30 Tage: jetzt im Hintergrund still erneuern
            if (result.getOrNull()?.refresh == true) {
                try { cache?.write(key, TileDownloader.downloadSequential(query(key))) } catch (_: Exception) { }
            }
        }
    }

    /** Läuft im Hintergrund-Thread: erst Festplatte (gültig), dann Netz (alle Server gleichzeitig), bei Netzfehler notfalls alter Speicher. */
    private fun loadTile(key: TileKey): Loaded {
        val now = System.currentTimeMillis()
        val cached = cache?.read(key, now)
        if (cached != null && cached.ageMs < TileCache.VALID_MS) {
            try {
                return Loaded(parse(cached.text), "aus Zwischenspeicher", cached.ageMs >= TileCache.REFRESH_MS)
            } catch (_: Exception) { /* defekt: neu laden */ }
        }
        try {
            val text = downloadRace(query(key))
            val data = parse(text)
            cache?.write(key, text)
            return Loaded(data, "geladen", false)
        } catch (e: Exception) {
            if (cached != null) {
                try { return Loaded(parse(cached.text), "aus altem Zwischenspeicher (kein Netz)", false) } catch (_: Exception) { }
            }
            throw e
        }
    }

    private fun query(key: TileKey): String = TileDownloader.query(key)

    /** Eine Anfrage an einen Server. [conns]: damit unterlegene Anfragen des Wettlaufs abgebrochen werden können. */
    private fun downloadOne(endpoint: String, q: String, conns: MutableList<HttpURLConnection>? = null): String =
        TileDownloader.downloadOne(endpoint, q, conns)

    /** Alle Server gleichzeitig fragen, die erste gültige Antwort gewinnt, die übrigen Anfragen werden abgebrochen. */
    private fun downloadRace(q: String): String {
        val eps = TileDownloader.pool.race(System.currentTimeMillis(), TileDownloader.RACE_SIZE)
        val pool = Executors.newFixedThreadPool(eps.size)
        val cs = ExecutorCompletionService<String>(pool)
        val conns = java.util.concurrent.CopyOnWriteArrayList<HttpURLConnection>()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            eps.forEach { ep ->
                cs.submit {
                    try {
                        val t = downloadOne(ep, q, conns)
                        TileDownloader.pool.ok(ep)
                        t
                    } catch (e: Exception) {
                        // abgebrochene Verlierer des Wettlaufs zählen nicht als Ausfall
                        if (!finished.get()) TileDownloader.pool.fail(ep, System.currentTimeMillis(), TileDownloader.pauseFor(e))
                        throw e
                    }
                }
            }
            var last: Exception? = null
            repeat(eps.size) {
                val f = cs.poll(RACE_BUDGET_MS, TimeUnit.MILLISECONDS) ?: throw IllegalStateException("Zeitlimit, kein Server hat geantwortet")
                try {
                    val text = f.get()
                    finished.set(true)
                    return text
                } catch (e: ExecutionException) {
                    last = (e.cause as? Exception) ?: e
                }
            }
            throw last ?: IllegalStateException("keine Antwort")
        } finally {
            finished.set(true)
            conns.forEach { try { it.disconnect() } catch (_: Exception) { } }
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ Vorladen

    /** Sekündlich vom Dienst aufgerufen: lädt die Kacheln importierter Touren (GPX), eine nach der anderen, vor dem Kreis. */
    fun tickTours(networkOk: Boolean) {
        val store = tourStore ?: return
        val c = cache ?: return
        if (!enabled) { tourPending = false; return }
        val now = SystemClock.elapsedRealtime()
        if (tourBusy || fetching || now < nextTourAt || now < nextFetchAt) return
        tourBusy = true
        preloadExecutor.execute {
            val next = try { store.nextMissing(c, System.currentTimeMillis()) } catch (_: Exception) { null }
            if (next == null) {
                if (tourPending) BridgeBus.log("Tour vorladen: alle Kacheln da")
                main.post { tourPending = false; tourBusy = false; nextTourAt = SystemClock.elapsedRealtime() + 5_000L }
                return@execute
            }
            if (!networkOk) {
                main.post { tourPending = true; tourBusy = false; nextTourAt = SystemClock.elapsedRealtime() + 5_000L }
                return@execute
            }
            val q = query(next)
            var last: Exception? = null
            var text: String? = null
            try { text = TileDownloader.downloadSequential(q) } catch (e: Exception) { last = e }
            if (text != null) {
                c.write(next, text)
                BridgeBus.changed()
                tourLoaded++
                if (tourLoaded % 10 == 1) {
                    val st = try { store.status(c, System.currentTimeMillis()).firstOrNull() } catch (_: Exception) { null }
                    if (st != null) BridgeBus.log("Tour \u201e${st.name}\u201c: ${st.total - st.missing} von ${st.total} Kacheln da")
                }
            }
            main.post {
                if (shutdown) return@post
                tourPending = true
                tourBusy = false
                val t = SystemClock.elapsedRealtime()
                if (text != null) {
                    if (tourFailures > 0 && !BridgeBus.verbose) tourFails.take()?.let { BridgeBus.log("Tour vorladen: $it, danach ging es weiter") }
                    tourFailures = 0
                    nextTourAt = t + PRELOAD_GAP_MS
                } else {
                    tourFailures++
                    nextTourAt = t + minOf(Backoff.delayMs(tourFailures) * 4, 60_000L)
                    val why = last?.let { it.message ?: it.javaClass.simpleName } ?: "keine Antwort"
                    if (BridgeBus.verbose || tourFailures == 1) {
                        BridgeBus.log("Tour vorladen: Anfrage fehlgeschlagen ($why), neuer Versuch folgt")
                    } else {
                        tourFails.add(why.substringAfter(": ", why))
                        if (tourFailures % 10 == 0) tourFails.take()?.let { BridgeBus.log("Tour vorladen: noch immer Fehler, $it") }
                    }
                }
            }
        }
    }

    /** Sekündlich vom Dienst aufgerufen. [unmetered]: WLAN bzw. Netz ohne Volumenbegrenzung. */
    fun tickPreload(networkOk: Boolean, mobileAllowed: Boolean = false) {
        val c = center
        if (!enabled || preloadKm <= 0 || cache == null) { preloadActive = false; return }
        if (tourPending) { preloadActive = networkOk; return } // erst die Tour, dann der Kreis
        if (c == null) { preloadActive = false; BridgeBus.updatePreload("Wartet auf den Standort"); return }
        val now = SystemClock.elapsedRealtime()
        if (planNeeded || (planCenter != null && distM(planCenter!!, c) > REPLAN_M)) {
            planNeeded = false
            planCenter = c
            planPreload(c[0], c[1], preloadKm)
            return
        }
        if (preloadTotal == 0) { preloadActive = false; return } // Plan wird noch berechnet
        queueAhead(c)
        if (preloadQueue.isEmpty() && !preloadBusy) {
            preloadActive = false
            BridgeBus.updatePreload("Fertig: $preloadTotal Kacheln im Umkreis von $preloadKm km gespeichert")
            return
        }
        if (!networkOk) { preloadActive = false; BridgeBus.updatePreload((if (mobileAllowed) "Wartet auf Netz (nicht im Roaming)" else "Wartet auf WLAN") + " ($preloadDone von $preloadTotal Kacheln)"); return }
        preloadActive = true
        BridgeBus.updatePreload("Vorgeladen: $preloadDone von $preloadTotal Kacheln")
        if (fetching || preloadBusy || now < nextFetchAt || now < nextPreloadAt) return
        val key = preloadQueue.pollFirst() ?: return
        preloadBusy = true
        preloadExecutor.execute {
            val r = try {
                // höflich: immer nur ein Server und Pause zwischen den Kacheln
                // schlägt ein Server fehl, kommt gleich der nächste dran (nacheinander, nicht gleichzeitig)
                val q = query(key)
                Result.success(TileDownloader.downloadSequential(q))
            } catch (e: Exception) { Result.failure(e) }
            r.onSuccess { cache.write(key, it) }
            main.post {
                if (shutdown) return@post
                preloadBusy = false
                val t = SystemClock.elapsedRealtime()
                if (r.isSuccess) {
                    if (preloadFailures > 0 && !BridgeBus.verbose) preFails.take()?.let { BridgeBus.log("Vorladen: $it, danach ging es weiter") }
                    preloadFailures = 0
                    preloadDone++
                    if (preloadDone % 50 == 0) BridgeBus.log("Vorladen: $preloadDone von $preloadTotal Kacheln")
                    nextPreloadAt = t + PRELOAD_GAP_MS
                } else {
                    preloadFailures++
                    preloadQueue.addLast(key)
                    nextPreloadAt = t + minOf(Backoff.delayMs(preloadFailures) * 4, 60_000L)
                    val why = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "keine Antwort"
                    if (BridgeBus.verbose || preloadFailures == 1) {
                        BridgeBus.log("Vorladen: Anfrage fehlgeschlagen ($why), neuer Versuch folgt")
                    } else {
                        preFails.add(why.substringAfter(": ", why))
                        if (preloadFailures % 10 == 0) preFails.take()?.let { BridgeBus.log("Vorladen: noch immer Fehler, $it") }
                    }
                }
            }
        }
    }

    /** Unterwegs: die Kacheln in Fahrtrichtung (bis 20 km) vorne in die Warteschlange, alle 1,5 km neu. */
    private fun queueAhead(c: DoubleArray) {
        val b = heading ?: return
        val cache = cache ?: return
        val from = aheadFrom
        if (from != null && distM(from, c) < AHEAD_RECHECK_M) return
        aheadFrom = c
        val now = System.currentTimeMillis()
        val fresh = PreloadPlanner.corridor(c[0], c[1], b, AHEAD_KM)
            .filter { !cache.isFresh(it, now, TileCache.PRELOAD_SKIP_MS) && it !in preloadQueue }
        if (fresh.isEmpty()) return
        for (k in fresh.asReversed()) preloadQueue.addFirst(k)
        preloadTotal += fresh.size
    }

    private fun planPreload(lat: Double, lon: Double, km: Int) {
        val c = cache ?: return
        preloadExecutor.execute {
            val plan = PreloadPlanner.tilesInRadius(lat, lon, km)
            val now = System.currentTimeMillis()
            val missing = plan.filter { !c.isFresh(it, now, TileCache.PRELOAD_SKIP_MS) }
            main.post {
                if (shutdown || km != preloadKm) return@post
                preloadQueue.clear()
                preloadQueue.addAll(missing)
                preloadTotal = plan.size
                preloadDone = plan.size - missing.size
                BridgeBus.log("Vorladen: ${plan.size} Kacheln im Umkreis von $km km, ${missing.size} fehlen")
            }
        }
    }

    private fun distM(a: DoubleArray, b: DoubleArray): Double {
        val dx = (b[1] - a[1]) * 111_320.0 * Math.cos(Math.toRadians(a[0]))
        val dy = (b[0] - a[0]) * 110_540.0
        return Math.hypot(dx, dy)
    }

    private fun parse(text: String): TileData {
        val ways = ArrayList<Way>()
        val signs = ArrayList<Sign>()
        val els = JSONObject(text).optJSONArray("elements") ?: return TileData(ways, signs)
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = HashMap<String, String>()
            e.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.optString(k) } }
            when (e.optString("type")) {
                "way" -> {
                    val g = e.optJSONArray("geometry") ?: continue
                    val pts = ArrayList<DoubleArray>(g.length())
                    for (j in 0 until g.length()) {
                        val p = g.getJSONObject(j)
                        pts += doubleArrayOf(p.getDouble("lat"), p.getDouble("lon"))
                    }
                    ways += Way(e.optLong("id"), pts, tags)
                }
                "node" -> if (tags["traffic_sign"] == "city_limit" && e.has("lat") && e.has("lon")) {
                    signs += Sign(e.optLong("id"), e.getDouble("lat"), e.getDouble("lon"), tags)
                }
            }
        }
        return TileData(ways, signs)
    }

    private companion object {
        const val MAX_TILES = 6
        const val STREET_HOLD_MS = 8_000L
        const val RACE_BUDGET_MS = 25_000L
        const val PRELOAD_GAP_MS = 2_500L
        const val REPLAN_M = 3_000.0
        const val AHEAD_KM = 20
        const val AHEAD_RECHECK_M = 1_500.0
        const val AHEAD_MIN_SPEED = 3f // m/s, darunter gilt man als stehend
    }
}
