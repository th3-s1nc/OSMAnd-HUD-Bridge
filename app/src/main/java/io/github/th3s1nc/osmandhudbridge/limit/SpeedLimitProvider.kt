package io.github.th3s1nc.osmandhudbridge.limit

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Holt Straßen samt maxspeed-Tag über die Overpass-API (OSM) und liefert das Limit zur aktuellen Position.
 * Ablauf: Umkreis laden, zwischenspeichern, lokal pro GPS-Fix zuordnen. Nur auf dem Main-Thread benutzen.
 * Ohne Internet bleibt das Limit leer (kurze Lücken überbrückt der Zwischenspeicher).
 */
class SpeedLimitProvider(private val onLimit: (Int) -> Unit, private val onStreet: (String?) -> Unit = {}) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tracker = LimitTracker()

    private var ways: List<Way> = emptyList()
    private var haveData = false
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var fetching = false
    private var lastFetchAt = 0L
    private var failures = 0
    private var lastReported = -1
    private var lastStreet: String? = null
    private var streetGoodAt = 0L
    private var shutdown = false

    var enabled = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) {
                tracker.reset()
                lastReported = -1
                onLimit(0)
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
    }

    fun onLocation(loc: Location) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val dist = if (haveData) distanceM(centerLat, centerLon, loc.latitude, loc.longitude) else Double.MAX_VALUE
        val backoff = MIN_FETCH_INTERVAL_MS shl minOf(failures, 4)
        if ((!haveData || dist > REFETCH_M) && !fetching && now - lastFetchAt > backoff) {
            startFetch(loc.latitude, loc.longitude)
        }
        val res = if (haveData && dist < COVERAGE_M) {
            SpeedLimitMatcher.matchAll(
                ways, loc.latitude, loc.longitude,
                if (loc.hasBearing()) loc.bearing.toDouble() else null,
                if (loc.hasSpeed()) loc.speed else 0f,
                if (loc.hasAccuracy()) loc.accuracy else 20f
            )
        } else null
        val raw = res?.limit
        // Strassenname: kurze Luecken (Kreuzungen, unklare Zuordnung) ueberbruecken
        val street = res?.street
        if (street != null) streetGoodAt = now
        val shownStreet = street ?: if (now - streetGoodAt <= STREET_HOLD_MS) lastStreet else null
        if (shownStreet != lastStreet) {
            lastStreet = shownStreet
            onStreet(shownStreet)
        }
        val limit = tracker.update(raw, now)
        if (limit != lastReported) {
            lastReported = limit
            BridgeBus.limit = if (limit > 0) "Limit: $limit km/h (OSM)" else "Limit: unbekannt"
            BridgeBus.log("Tempolimit: ${if (limit > 0) "$limit km/h" else "unbekannt"}")
            onLimit(limit)
        }
    }

    private fun startFetch(lat: Double, lon: Double) {
        fetching = true
        lastFetchAt = SystemClock.elapsedRealtime()
        executor.execute {
            val result = try { Result.success(load(lat, lon)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (shutdown) return@post
                fetching = false
                result.onSuccess {
                    ways = it; haveData = true; centerLat = lat; centerLon = lon; failures = 0
                    BridgeBus.log("OSM: ${it.size} Straßen geladen, davon ${it.count { w -> w.tags.keys.any { k -> k.startsWith("maxspeed") } }} mit Limit")
                }.onFailure {
                    failures++
                    BridgeBus.log("OSM-Abfrage fehlgeschlagen (${it.javaClass.simpleName}: ${it.message})")
                    if (!haveData) BridgeBus.limit = "Limit: Abfrage fehlgeschlagen"
                }
            }
        }
    }

    private fun load(lat: Double, lon: Double): List<Way> {
        val q = String.format(
            Locale.US,
            "[out:json][timeout:10];way(around:%d,%.6f,%.6f)[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|" +
                "unclassified|residential|living_street|service|road|motorway_link|trunk_link|primary_link|" +
                "secondary_link|tertiary_link)\$\"];out tags geom;",
            RADIUS_M, lat, lon
        )
        var last: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                val c = URL(endpoint).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8_000
                c.readTimeout = 14_000
                c.doOutput = true
                c.setRequestProperty("User-Agent", "OsmAndHudBridge/0.9 (Hobbyprojekt, geringe Last)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                val text = c.inputStream.bufferedReader().use { it.readText() }
                return parse(text)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("keine Antwort")
    }

    private fun parse(text: String): List<Way> {
        val out = ArrayList<Way>()
        val els = JSONObject(text).optJSONArray("elements") ?: return out
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val g = e.optJSONArray("geometry") ?: continue
            val pts = ArrayList<DoubleArray>(g.length())
            for (j in 0 until g.length()) {
                val p = g.getJSONObject(j)
                pts += doubleArrayOf(p.getDouble("lat"), p.getDouble("lon"))
            }
            val tags = HashMap<String, String>()
            e.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.optString(k) } }
            out += Way(e.optLong("id"), pts, tags)
        }
        return out
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians(lat1))
        val dy = (lat2 - lat1) * 110_540.0
        return Math.hypot(dx, dy)
    }

    private companion object {
        const val RADIUS_M = 300
        const val STREET_HOLD_MS = 8_000L
        const val REFETCH_M = 120.0
        const val COVERAGE_M = 270.0
        const val MIN_FETCH_INTERVAL_MS = 5_000L
        val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
    }
}
