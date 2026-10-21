package io.github.th3s1nc.osmandhudbridge.limit

import java.util.Locale

/** Rechnungen für das Laden einer Region (ohne Android, damit sie getestet werden können). */
object RegionDownload {
    private const val MB = 1_048_576L

    /** "185 MB" oder "1,2 GB". */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "unbekannt"
        bytes < 1024 * MB -> String.format(Locale.GERMANY, "%,d MB", Math.max(1L, Math.round(bytes / MB.toDouble())))
        else -> String.format(Locale.GERMANY, "%.1f GB", bytes / (1024.0 * MB))
    }

    /** Anteil 0..1; bei unbekannter Gesamtgröße 0. */
    fun fraction(done: Long, total: Long): Double =
        if (total <= 0) 0.0 else (done.toDouble() / total).coerceIn(0.0, 1.0)

    /**
     * So viel Platz sollte frei sein: die Datei selbst, dazu Arbeitsraum und fertige Kacheln beim Einlesen
     * (grob geschätzt: 2,5-fache Größe plus 50 MB). Bei unbekannter Größe 500 MB.
     */
    fun neededBytes(total: Long): Long = if (total <= 0) 500 * MB else total * 5 / 2 + 50 * MB

    fun enoughSpace(freeBytes: Long, total: Long, alreadyHave: Long = 0): Boolean =
        freeBytes + alreadyHave >= neededBytes(total)

    /** Wo ein unterbrochener Download weitermacht: hinter der vorhandenen Teildatei, sonst von vorn. */
    fun resumeOffset(partLength: Long, total: Long): Long =
        if (partLength > 0 && (total <= 0 || partLength < total)) partLength else 0L

    /** Name für die Anzeige, falls das Verzeichnis keinen hat: aus "…/oberpfalz-latest.osm.pbf" wird "oberpfalz". */
    fun nameFromUrl(url: String): String =
        url.substringAfterLast('/').removeSuffix(".osm.pbf").removeSuffix("-latest")
}
