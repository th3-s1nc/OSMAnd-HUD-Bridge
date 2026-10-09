package io.github.th3s1nc.osmandhudbridge.protocol

/**
 * Anzeigemodi des HUD. Die Hide/Show-Listen wurden beim Umschalten der Modi am Funkverkehr beobachtet:
 * je Wechsel eine ShowHide-Nachricht, danach "Bildschirm aktivieren".
 *
 * Abweichung vom beobachteten Verhalten: City zeigt hier zusätzlich Element 12 (aktuelle Straße).
 * [fields]: Felder der UpdateMessage, die der Modus anzeigt (nur diese werden gesendet).
 * [routeElements]: Elemente für Restweg/Restzeit bzw. Ankunft; werden ausgeblendet, solange es keine Werte gibt.
 */
enum class DisplayMode(
    val screenId: Int,
    val title: String,
    val hide: List<Int>,
    val show: List<Int>,
    val fields: Set<Int>,
    val routeElements: List<Int>
) {
    NAVIGATOR(
        13, "Navigator", listOf(21),
        listOf(8, 9, 10, 53, 16, 3, 51, 5, 4, 6, 17, 13, 52),
        setOf(1, 3, 4, 5, 8, 10, 11, 18), listOf(4, 5)
    ),
    MINIMALIST(
        12, "Minimalist", listOf(21),
        listOf(53, 16, 3, 51, 5, 4, 6, 17, 52),
        setOf(3, 4, 5, 8, 10, 11), listOf(4, 5)
    ),
    EXPLORER(
        11, "Explorer", listOf(5),
        listOf(2, 8, 9, 10, 53, 35, 16, 3, 51, 21, 4, 6, 17, 32, 48, 52),
        setOf(1, 2, 3, 4, 5, 8, 11, 12), listOf(4, 21)
    ),
    CITY(
        10, "City", listOf(21),
        listOf(8, 9, 10, 53, 16, 3, 51, 5, 4, 6, 7, 17, 32, 48, 52, 12),
        setOf(1, 3, 4, 5, 8, 10, 11, 15, 17), listOf(4, 5)
    ),

    /**
     * Guide (Bildschirm 20): Pfeil mit Entfernung und zwei frei wählbare Textzeilen. [fields] sind nur die festen Felder,
     * dazu kommen die Felder der gewählten Werte (siehe [SlotConfig]). HUD-Akku 107, Uhrzeit 104, Bluetooth 106, Pfeil 96/97,
     * Entfernung 98, Textzeilen 208/224 und 209/225.
     */
    GUIDE(
        20, "Guide", emptyList(),
        listOf(107, 104, 106, 96, 97, 98, 208, 224, 209, 225),
        setOf(4, 5, 8), emptyList()
    ),

    /**
     * Cruiser (Bildschirm 21, früher "Tracking"/"Freies Fahren"): vier frei wählbare Textzeilen.
     * Der Name TRACKING bleibt wegen der gespeicherten Einstellung.
     */
    TRACKING(
        21, "Cruiser", emptyList(),
        listOf(107, 104, 106, 210, 226, 211, 227, 212, 228, 213, 229),
        setOf(8), emptyList()
    );

    /** Dieser Modus braucht vor dem Aktivieren die Textfeld-Konfiguration. */
    val usesTextSlots: Boolean get() = this == TRACKING || this == GUIDE

    /** Erste Zeile dieses Modus in der Textfeld-Konfiguration mit sechs Zeilen (Guide 0-1, Cruiser 2-5). */
    val slotStart: Int get() = if (this == GUIDE) 0 else 2

    /** Handy-Akku-Warnsymbol: laut Test am HUD nur in Guide, Cruiser und Explorer sichtbar. */
    val supportsBatteryWarning: Boolean get() = this == GUIDE || this == TRACKING || this == EXPLORER

    /** Anruf-/Nachrichten-Anzeige: laut Test am HUD nur in Explorer und City sichtbar. */
    val supportsEvents: Boolean get() = this == EXPLORER || this == CITY

    companion object {
        fun fromName(name: String?): DisplayMode = values().firstOrNull { it.name == name } ?: NAVIGATOR
    }
}
