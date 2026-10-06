package io.github.th3s1nc.osmandhudbridge.track

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Schreibt eine Fahrt als GPX 1.1. Tempo steht zusätzlich in der Garmin-Erweiterung TrackPointExtension (m/s), die viele
 * Apps lesen. Tempolimit (hb:limit, km/h) und Schräglage (hb:lean, Grad, rechts positiv) stehen in eigenen Feldern, die
 * andere Apps einfach überspringen. Bei einer Lücke von mehr als [GAP_MS] zwischen zwei Punkten (Autopause) beginnt ein neues Teilstück.
 */
object GpxWriter {
    const val GAP_MS = 60_000L

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    fun write(name: String, points: List<TrackPoint>): String {
        val sb = StringBuilder(points.size * 170 + 600)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OSMAnd HUD Bridge\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" ")
        sb.append("xmlns:hb=\"https://github.com/th3-s1nc/OSMAnd-HUD-Bridge\">\n")
        sb.append("<metadata><name>").append(esc(name)).append("</name>")
        points.firstOrNull()?.let { sb.append("<time>").append(iso(it.timeMs)).append("</time>") }
        sb.append("</metadata>\n<trk><name>").append(esc(name)).append("</name>\n")
        var prev: TrackPoint? = null
        var open = false
        for (p in points) {
            if (prev == null || p.timeMs - prev.timeMs > GAP_MS) {
                if (open) sb.append("</trkseg>\n")
                sb.append("<trkseg>\n")
                open = true
            }
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.6f", p.lat))
                .append("\" lon=\"").append(String.format(Locale.US, "%.6f", p.lon)).append("\">")
            p.ele?.let { sb.append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
            sb.append("<time>").append(iso(p.timeMs)).append("</time>")
            sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:speed>")
                .append(String.format(Locale.US, "%.2f", p.speedKmh / 3.6f))
                .append("</gpxtpx:speed></gpxtpx:TrackPointExtension>")
            if (p.limitKmh > 0) sb.append("<hb:limit>").append(p.limitKmh).append("</hb:limit>")
            p.leanDeg?.let { sb.append("<hb:lean>").append(String.format(Locale.US, "%.0f", it)).append("</hb:lean>") }
            sb.append("</extensions></trkpt>\n")
            prev = p
        }
        if (open) sb.append("</trkseg>\n")
        sb.append("</trk>\n</gpx>\n")
        return sb.toString()
    }
}
