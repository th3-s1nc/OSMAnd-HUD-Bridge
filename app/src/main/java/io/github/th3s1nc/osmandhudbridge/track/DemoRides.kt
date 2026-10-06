package io.github.th3s1nc.osmandhudbridge.track

import java.util.Random

/**
 * Erfundene Testfahrten, um die Fahrt-Seite, Diagramme, Karte, Teilen und Löschen ohne echte Ausfahrt zu probieren.
 * Die Strecken sind frei erfunden (laufen nicht auf echten Straßen) und liegen in Gegenden, die nichts mit dem Nutzer zu tun haben.
 * Die Werte folgen der Physik grob: Tempo sinkt in Kurven, die Schräglage ergibt sich aus Tempo und Kurvenradius.
 */
object DemoRides {
    class Spec(val name: String, val lat: Double, val lon: Double, val minutes: Int, val seed: Long, val pauseAtMin: Int)

    val specs = listOf(
        Spec("Testfahrt Allgäu (kurz)", 47.57, 10.70, 25, 11L, -1),
        Spec("Testfahrt Eifel (mit Pause)", 50.37, 6.95, 55, 22L, 25),
        Spec("Testfahrt Schwäbische Alb (lang)", 48.40, 9.40, 95, 33L, -1)
    )

    /** Punkte einer Testfahrt, eine pro Sekunde (bei Pause eine Lücke), Start [startMs]. */
    fun generate(spec: Spec, startMs: Long): List<TrackPoint> {
        val rnd = Random(spec.seed)
        val out = ArrayList<TrackPoint>()
        var lat = spec.lat
        var lon = spec.lon
        var heading = rnd.nextDouble() * 2 * Math.PI
        var v = 0.0 // m/s
        var tMs = startMs
        val total = spec.minutes * 60
        var limit = 70
        var limitLeft = 120
        var phase1 = rnd.nextDouble() * 6.28
        var phase2 = rnd.nextDouble() * 6.28
        var phase3 = rnd.nextDouble() * 6.28
        var lean = 0.0
        var s = 0
        while (s < total) {
            if (spec.pauseAtMin > 0 && s == spec.pauseAtMin * 60) {
                tMs += 12 * 60_000L // 12 Minuten Pause: der Rekorder nimmt in der Zeit nichts auf
                v = 0.0
            }
            if (--limitLeft <= 0) {
                limit = intArrayOf(50, 70, 70, 60, 100, 80)[rnd.nextInt(6)]
                limitLeft = 60 + rnd.nextInt(240)
            }
            // Kurvigkeit: Überlagerung langsamer Wellen, ab und zu enge Kurven
            val t = s / 10.0
            var k = 0.004 * Math.sin(t / 7 + phase1) + 0.006 * Math.sin(t / 3.1 + phase2) * Math.max(0.0, Math.sin(t / 25 + phase3))
            if (s % 200 in 0..9) k += 0.02 * (if ((s / 200) % 2 == 0) 1 else -1)
            val kAbs = Math.abs(k)
            val vCurve = if (kAbs < 1e-4) 40.0 else Math.sqrt(4.5 / kAbs) // maximale Querbeschleunigung ca. 4,5 m/s²
            val vWant = Math.min(Math.min(limit / 3.6 * (if (s % 400 in 100..140) 1.12 else 1.0), vCurve), 38.0)
            val dv = (vWant - v).coerceIn(-3.5, 2.5)
            v = Math.max(0.0, v + dv * 0.6 + (rnd.nextDouble() - 0.5) * 0.3)
            heading += v * k // Richtungsänderung je Sekunde
            val dist = v
            lat += dist * Math.cos(heading) / 111_320.0
            lon += dist * Math.sin(heading) / (111_320.0 * Math.cos(Math.toRadians(lat)))
            val rawLean = Math.toDegrees(Math.atan(v * v * k / 9.81))
            lean += (rawLean - lean) * 0.5 + (rnd.nextDouble() - 0.5) * 2.0
            val ele = 420.0 + 150.0 * Math.sin(s / 260.0 + phase2) + 60.0 * Math.sin(s / 61.0 + phase1) + rnd.nextDouble()
            out += TrackPoint(
                tMs, lat, lon, ele, (v * 3.6).toFloat(), limit,
                if (v * 3.6 >= 15) lean.toFloat() else null
            )
            tMs += 1000
            s++
        }
        return out
    }
}
