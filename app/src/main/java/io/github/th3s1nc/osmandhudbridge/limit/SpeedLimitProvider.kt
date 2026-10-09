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
 * bei Fehlern wird schnell wiederholt (höchstens 15 s Pause). Auf der gleichen Straße bleibt das Limit
 * erhalten, auch wenn ein Abschnitt kein Tag hat oder eine Kreuzung unklar ist. Nur auf dem Main-Thread benutzen.
 */
class SpeedLimitProvider(
    cacheDir: File?,
    private val onLimit: (Int, Boolean) -> Unit,
    private val onStreet: (String?) -> Unit = {},
    /** Blitzer vor dir: Entfernung in m (null = keiner) und ob jetzt der Ton kommen soll. */
    private val onCamera: (Int?, Boolean, Int) -> Unit = { _, _, _ -> },
    /** Ton vor Bahnübergang, Zebrastreifen oder Verkehrsberuhigung (Art aus [PointKind]). */
    private val onPoint: (Int) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor()
    /** Eigener Thread für Aufräumarbeiten im Speicher, damit eine gebrauchte Kachel nie dahinter wartet. */
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = LimitTracker()
    private val signTracker = SignTracker()
    private var tilesVersion = 0
    private var indexKey: Pair<List<TileKey>, Int>? = null
    private var index: NeighborIndex? = null
    private val cache = cacheDir?.let { TileCache(it) }

    /** Nur gespeicherte und importierte Kacheln benutzen: nichts laden, nichts vorladen, nichts erneuern. */
    @Volatile var offlineOnly = false
        set(v) { if (v != field) { field = v; reloadTiles() } }

    /** Kacheln im Speicher verwerfen und beim nächsten Standort neu lesen (nach einem Import oder beim Wechsel der Datenquelle). */
    fun reloadTiles() { tiles.clear(); tilesVersion++ }

    /** Obergrenze für den Zwischenspeicher in MB. */
    var cacheMaxMb = 2048
        set(v) {
            field = v
            val c = cache ?: return
            c.maxBytes = v.toLong() * 1024 * 1024
            bgExecutor.execute { c.trimNow() }
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

    // Fahrtrichtung und Tempo (für Blitzer-Warnung und die Kachel in Fahrtrichtung)
    private var heading: Double? = null
    private var movingSpeed = 0f

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

    /** Blitzer-Warnung: -1 = aus, sonst 0 = Kurz, 1 = Normal, 2 = Lang. Braucht die Straßendaten (Tempolimit aus OSM-Daten an). */
    var cameraLevel = -1
        set(v) {
            if (field == v) return
            field = v
            if (v < 0) { camWarn.reset(); if (camShown != null) { camShown = null; onCamera(null, false, 0) } }
        }
    private val camWarn = CameraWarn()
    /** Eigene Blitzer-Liste (zum Beispiel SCDB) und welche Arten warnen sollen. */
    @Volatile var importedCameras: List<ImportedCamera> = emptyList()
    @Volatile var warnRedLight = true
    @Volatile var warnSection = true
    @Volatile var warnTunnel = true
    private fun allowKind(kind: Int) = when (kind) { CameraKind.RED_LIGHT -> warnRedLight; CameraKind.SECTION -> warnSection; CameraKind.TUNNEL -> warnTunnel; else -> true }
    private var camShown: Int? = null

    /** Welche Punkt-Warnungen an sind (Bits aus [PointKind]); 0 = alle aus. Braucht importierte Straßendaten. */
    @Volatile var pointMask = 0
        set(v) { if (v != field) { field = v; pointWarn.reset() } }
    private val pointWarn = PointWarn()

    /** Warnung vor sehr scharfen Kurven: -1 = aus, sonst 0 = wenig, 1 = normal, 2 = viel (siehe [CurveLevel]). */
    @Volatile var curveLevel = -1
        set(v) { if (v != field) { field = v; curveWarn.reset() } }
    private val curveWarn = CurveWarn()
    private var curveIndexKey: Pair<List<TileKey>, Int>? = null
    private var curveIndex: CurveIndex? = null

    var enabled = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) {
                tracker.reset()
                lastReported = -1
                heldStreet = null
                onLimit(0, false)
                if (camShown != null) { camShown = null; onCamera(null, false, 0) }
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
        bgExecutor.shutdownNow()
    }

    fun onLocation(loc: Location) {
        if (!enabled) return
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
        if (cameraLevel >= 0) {
            val osmCams = datas.flatMap { it.cameras }.distinctBy { it.id }
            val cams = if (importedCameras.isEmpty()) osmCams else CameraImport.combine(osmCams, importedCameras, loc.latitude, loc.longitude, ::allowKind)
            val hit = camWarn.update(cams, loc.latitude, loc.longitude, heading, speed, cameraLevel)
            if (hit.sound) BridgeBus.log("Blitzer in ${hit.distanceM} m voraus")
            val shown = hit.distanceM?.let { it / 10 * 10 }
            if (shown != camShown || hit.sound) { camShown = shown; onCamera(shown, hit.sound, hit.limitKmh) }
        }
        if (pointMask != 0) {
            val kind = pointWarn.update(datas.flatMap { it.points }.distinctBy { it.id }, loc.latitude, loc.longitude, heading, speed, pointMask)
            if (kind != 0) { BridgeBus.log("Hinweis voraus: " + when (kind) { PointKind.LEVEL_CROSSING -> "Bahnübergang"; PointKind.ZEBRA -> "Zebrastreifen"; else -> "Verkehrsberuhigung" }); onPoint(kind) }
        }
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
        if (curveLevel >= 0 && ways.isNotEmpty()) {
            val k = keys to tilesVersion
            if (curveIndexKey != k) { curveIndex = CurveIndex(ways); curveIndexKey = k }
            if (curveWarn.update(ways, curveIndex!!, loc.latitude, loc.longitude, heading, speed, curveLevel, limit, zone == Zone.INNER)) {
                BridgeBus.log("Scharfe Kurve voraus (Radius ${curveWarn.lastBend?.radiusM?.toInt()} m)")
                onPoint(PointKind.CURVE)
            }
        }
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
                        "${r.data.ways.count { w -> w.tags.keys.any { k -> k.startsWith("maxspeed") } }} mit Limit, ${r.data.signs.size} Ortsschilder, " +
                        (if (r.data.hasCameras) "${r.data.cameras.size} Blitzer" else "keine Blitzerdaten (alte Kachel)"))
                    updateCameraStat()
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
        if (offlineOnly) {
            // nur Speicher, egal wie alt; fehlt die Kachel, gilt sie als leer (kein Wiederholen, kein Netz)
            if (cached != null) try { return Loaded(parse(cached.text), "aus Speicher (Offline-Daten)", false) } catch (_: Exception) { }
            cache?.readLegacy(key, now)?.let { old ->
                try { return Loaded(parse(old.text).let { TileData(it.ways, it.signs, emptyList(), false) }, "aus Speicher der Vorversion (Offline-Daten)", false) } catch (_: Exception) { }
            }
            return Loaded(TileData(emptyList(), emptyList(), emptyList(), false), "nicht gespeichert (Offline-Daten)", false)
        }
        // Importierte Kacheln (aus einer Kartendatei) gelten immer und werden nie online erneuert: ein neuer Import ist der Weg zu neuen Daten
        val imported = cached != null && cached.text.startsWith(PbfImport.MARKER)
        if (cached != null && (imported || cached.ageMs < TileCache.VALID_MS)) {
            try {
                return Loaded(parse(cached.text), if (imported) "aus Import" else "aus Zwischenspeicher", !imported && cached.ageMs >= TileCache.REFRESH_MS)
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
            // Kachel der Vorversion (ohne Blitzer): Straßen und Limits stimmen weiter, Blitzer sind dann unbekannt
            cache?.readLegacy(key, now)?.let { old ->
                try { return Loaded(parse(old.text).let { TileData(it.ways, it.signs, emptyList(), false) }, "aus Zwischenspeicher der Vorversion (kein Netz)", false) } catch (_: Exception) { }
            }
            throw e
        }
    }

    private fun query(key: TileKey): String = TileDownloader.query(key)

    /** Blitzer in den gerade geladenen Kacheln (für die Anzeige im Tab Werkzeuge). */
    private fun updateCameraStat() {
        val withData = tiles.values.filter { it.hasCameras }
        val n = withData.flatMap { it.cameras }.distinctBy { it.id }.size
        BridgeBus.cameras = if (tiles.isEmpty()) "–" else "$n in ${withData.size} von ${tiles.size} geladenen Kacheln"
    }

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

    private fun parse(text: String): TileData {
        val ways = ArrayList<Way>()
        val signs = ArrayList<Sign>()
        val cameras = ArrayList<Camera>()
        val points = ArrayList<WarnPoint>()
        val els = JSONObject(text).optJSONArray("elements") ?: return TileData(ways, signs, cameras)
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
                "node" -> if (e.has("lat") && e.has("lon")) {
                    if (tags["traffic_sign"] == "city_limit") signs += Sign(e.optLong("id"), e.getDouble("lat"), e.getDouble("lon"), tags)
                    else if (tags["highway"] == "speed_camera") cameras += Camera(e.optLong("id"), e.getDouble("lat"), e.getDouble("lon"), tags)
                    else PointKind.of(tags).let { k -> if (k != 0) points += WarnPoint(e.optLong("id"), e.getDouble("lat"), e.getDouble("lon"), k) }
                }
            }
        }
        return TileData(ways, signs, cameras, true, points)
    }

    private companion object {
        const val MAX_TILES = 6
        const val STREET_HOLD_MS = 8_000L
        const val RACE_BUDGET_MS = 25_000L
        const val AHEAD_MIN_SPEED = 3f // m/s, darunter gilt man als stehend
    }
}
