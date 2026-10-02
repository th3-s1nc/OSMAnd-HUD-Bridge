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
    );

    /** Anruf-/Nachrichten-Anzeige: laut Test am HUD nur in Explorer und City sichtbar. */
    val supportsEvents: Boolean get() = this == EXPLORER || this == CITY

    companion object {
        fun fromName(name: String?): DisplayMode = values().firstOrNull { it.name == name } ?: NAVIGATOR
    }
}
