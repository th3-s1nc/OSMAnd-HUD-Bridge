package io.github.th3s1nc.osmandhudbridge.protocol

/**
 * Werte, die in den Textzeilen von Guide und Cruiser stehen können.
 * [element]: Element-Nummer für die Textfeld-Konfiguration des HUD, [label]: Beschriftung unter dem Wert,
 * [field]: Feld der UpdateMessage, aus dem das HUD den Wert nimmt (0 = keins).
 * Das Tempolimit nutzt Element 24 und Feld 29 (im HUD als "durchschnittliches Tempo" vorgesehen).
 */
enum class HudValue(
    val key: String,
    val title: String,
    val element: Int,
    val label: String,
    val field: Int,
    /** Beispieltext für die Vorschau in der App. */
    val sample: String
) {
    EMPTY("empty", "– Leer –", 0, "", 0, ""),
    SPEED("speed", "Geschwindigkeit", 80, "GESCHWINDIGKEIT", HudProtocol.F_SPEED, "48 km/h"),
    LIMIT("limit", "Tempolimit", 24, "LIMIT", HudProtocol.F_LIMIT_SLOT, "50 km/h"),
    ROUTE_DISTANCE("rdist", "Reststrecke", 4, "VERBLEIBEND", HudProtocol.F_ROUTE_DISTANCE, "267 km"),
    ROUTE_TIME("rtime", "Restzeit", 5, "VERBLEIBEND", HudProtocol.F_ROUTE_DURATION, "3:45 h"),
    ARRIVAL("arr", "Ankunft", 21, "ANKUNFT", HudProtocol.F_ARRIVAL_TIME, "17:30"),
    TRIP_TIME("ttime", "Fahrzeit", 23, "FAHRZEIT", HudProtocol.F_TRIP_DURATION, "1:25 h"),
    TRIP_DISTANCE("tdist", "Strecke", 22, "STRECKE", HudProtocol.F_TRIP_DISTANCE, "38 km"),
    ELEVATION("ele", "Höhe", 82, "HOEHE", HudProtocol.F_ELEVATION, "412 m"),
    NEXT_STREET("street", "Nächste Straße", 7, "NAECHSTE STRASSE", HudProtocol.F_NEXT_STREET, "Hauptstr.");

    companion object {
        fun fromKey(key: String?): HudValue? = values().firstOrNull { it.key == key }
    }
}

/** Gewählte Werte je Anzeige (Guide: 2 Zeilen, Cruiser: 4 Zeilen). Wird von der App-Einstellung befüllt. */
object SlotConfig {
    val GUIDE_DEFAULT = listOf(HudValue.ROUTE_DISTANCE, HudValue.ROUTE_TIME)
    val CRUISER_DEFAULT = listOf(HudValue.SPEED, HudValue.LIMIT, HudValue.TRIP_TIME, HudValue.TRIP_DISTANCE)

    @Volatile private var guide: List<HudValue> = GUIDE_DEFAULT
    @Volatile private var cruiser: List<HudValue> = CRUISER_DEFAULT

    fun defaults(mode: DisplayMode): List<HudValue> = when (mode) {
        DisplayMode.GUIDE -> GUIDE_DEFAULT
        DisplayMode.TRACKING -> CRUISER_DEFAULT
        else -> emptyList()
    }

    fun slots(mode: DisplayMode): List<HudValue> = when (mode) {
        DisplayMode.GUIDE -> guide
        DisplayMode.TRACKING -> cruiser
        else -> emptyList()
    }

    /** Text aus den Einstellungen ("speed,limit,ttime,tdist") -> Liste; fehlende oder unbekannte Einträge = Standard. */
    fun parse(mode: DisplayMode, text: String?): List<HudValue> {
        val def = defaults(mode)
        if (text.isNullOrBlank()) return def
        val parts = text.split(',')
        return def.indices.map { i -> HudValue.fromKey(parts.getOrNull(i)?.trim()) ?: def[i] }
    }

    fun encode(list: List<HudValue>): String = list.joinToString(",") { it.key }

    /** true, wenn sich etwas geändert hat. */
    fun set(mode: DisplayMode, list: List<HudValue>): Boolean {
        if (list == slots(mode)) return false
        when (mode) {
            DisplayMode.GUIDE -> guide = list
            DisplayMode.TRACKING -> cruiser = list
            else -> return false
        }
        return true
    }
}
