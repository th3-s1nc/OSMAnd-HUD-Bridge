package io.github.th3s1nc.osmandhudbridge.protocol

/** Gewünschter Inhalt des HUD (Navigations-Bildschirm). null = "leer". */
data class HudState(
    /** GPS-Geschwindigkeit in km/h (Rohwert; die App zeigt ganze km/h, abgeschnitten). */
    val speedKmh: Float = 0f,
    /** Tempolimit in km/h, 0 = unbekannt. */
    val speedLimitKmh: Int = 0,
    val camera: Int = CameraType.NONE,
    /** Entfernung zum Blitzer vor dir in Metern (nur gesetzt, solange gewarnt wird). */
    val cameraDistanceM: Int? = null,
    /** Tempolimit am Blitzer (aus der eigenen Liste); 0 = unbekannt. Gilt nur, solange gewarnt wird. */
    val cameraLimitKmh: Int = 0,
    /** Distanz zum nächsten Manöver in Metern. */
    val partDistanceM: Int? = null,
    val command: NavCommand? = null,
    val roundaboutExit: Int? = null,
    /** Restdistanz zum Ziel in Metern. */
    val routeDistanceM: Int? = null,
    val routeMinutes: Int? = null,
    val lanes: List<Lane> = emptyList(),
    val hour: Int = 0,
    val minute: Int = 0,
    /** Fahrtrichtung in Grad (Kompass im Explorer-Modus), null = unbekannt. */
    val headingDeg: Int? = null,
    val arrivalHour: Int? = null,
    val arrivalMinute: Int? = null,
    val nextStreet: String? = null,
    val currentStreet: String? = null,
    /** Tracking-Testmodus: gefahrene Strecke in Metern, gefahrene Zeit in Minuten (null = leer). */
    val tripDistanceM: Int? = null,
    val tripMinutes: Int? = null,
    /** Höhe in Metern für die wählbaren Zeilen; null = leer. */
    val elevationM: Int? = null,
    /** Handy-Akku unter der gewählten Grenze: Warnsymbol (nur Guide, Cruiser, Explorer). */
    val phoneBatteryLow: Boolean = false,
    /** Tempowarnung: fett ab Limit + Toleranz. Aus = HUD bekommt immer "ok". */
    /** Limit ist nur geschätzt (nicht aus den Kartendaten): dann nie warnen. */
    val limitEstimated: Boolean = false,
    val warnEnabled: Boolean = true,
    val warnToleranceKmh: Int = 0
)

/** Ab welcher Entfernung ein Manöver gezeigt wird (Einstellung "Abstand der Anweisungen"). */
enum class ThresholdMode(val meters: IntArray?) {
    ALWAYS(null),
    SHORT(intArrayOf(300, 600, 1000)),
    NORMAL(intArrayOf(500, 1000, 2000)),
    LONG(intArrayOf(800, 2000, 4000));

    /** true = Manöver noch zu weit weg, nicht anzeigen. Schwelle hängt vom Tempolimit ab (<=50, <=100, sonst/unbekannt). */
    fun isOver(speedLimitKmh: Int, distanceM: Int): Boolean {
        val t = meters ?: return false
        val limit = when {
            speedLimitKmh <= 0 -> t[2]
            speedLimitKmh <= 50 -> t[0]
            speedLimitKmh <= 100 -> t[1]
            else -> t[2]
        }
        return distanceM >= limit
    }
}
