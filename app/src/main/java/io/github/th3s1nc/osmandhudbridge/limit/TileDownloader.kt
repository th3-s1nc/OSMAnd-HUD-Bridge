package io.github.th3s1nc.osmandhudbridge.limit

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Anfrage an die Overpass-Server (OSM). Wird vom Dienst und vom Hintergrund-Vorladen gemeinsam benutzt. */
object TileDownloader {
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /** Ausgefallene Server werden ein paar Minuten übersprungen. */
    val pool = ServerPool(ENDPOINTS)

    /** So viele Server fragt der Wettlauf (Kachel für die aktuelle Fahrt) gleichzeitig. */
    const val RACE_SIZE = 4

    /**
     * Höflich: ein Server nach dem anderen (reihum). Fällt einer aus, kommt der nächste dran und der ausgefallene
     * wird pausiert. Wirft den letzten Fehler, wenn alle versagen.
     */
    fun downloadSequential(q: String): String {
        var last: Exception? = null
        repeat(minOf(ENDPOINTS.size, 4)) {
            val ep = pool.next(System.currentTimeMillis())
            try {
                val t = downloadOne(ep, q, null, 15_000)
                pool.ok(ep)
                return t
            } catch (e: Exception) {
                last = IllegalStateException("${hostOf(ep)}: ${e.message ?: e.javaClass.simpleName}")
                pool.fail(ep, System.currentTimeMillis(), pauseFor(e))
            }
        }
        throw last ?: IllegalStateException("keine Antwort")
    }

    fun hostOf(endpoint: String): String = endpoint.removePrefix("https://").substringBefore('/')

    /** Weist ein Server uns ab (403 gesperrt, 429 zu viele Anfragen), bleibt er länger in Pause. */
    fun pauseFor(e: Exception): Long {
        val m = e.message.orEmpty()
        return if (m.startsWith("HTTP 403") || m.startsWith("HTTP 429")) LONG_PAUSE_MS else ServerPool.DEFAULT_COOLDOWN_MS
    }

    const val LONG_PAUSE_MS = 15 * 60_000L

    fun query(key: TileKey): String = String.format(
        Locale.US,
        "[out:json][timeout:20];(way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|" +
            "unclassified|residential|living_street|service|road|motorway_link|trunk_link|primary_link|" +
            "secondary_link|tertiary_link)\$\"](%1\$.6f,%2\$.6f,%3\$.6f,%4\$.6f);" +
            "node[\"traffic_sign\"=\"city_limit\"](%1\$.6f,%2\$.6f,%3\$.6f,%4\$.6f););out tags geom;",
        key.south - TileMath.MARGIN_DEG, key.west - TileMath.MARGIN_DEG,
        key.north + TileMath.MARGIN_DEG, key.east + TileMath.MARGIN_DEG
    )

    /** Eine Anfrage an einen Server. [conns]: damit unterlegene Anfragen eines Wettlaufs abgebrochen werden können. */
    fun downloadOne(endpoint: String, q: String, conns: MutableList<HttpURLConnection>? = null, readTimeoutMs: Int = 20_000): String {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        conns?.add(c)
        c.requestMethod = "POST"
        c.connectTimeout = 4_000
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.setRequestProperty("User-Agent", "OSMAndHudBridge/0.11 (Hobbyprojekt, Kachel-Cache, geringe Last)")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        if (!text.contains("\"elements\"")) throw IllegalStateException("leere Antwort")
        return text
    }
}
