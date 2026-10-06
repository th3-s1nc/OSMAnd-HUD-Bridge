package io.github.th3s1nc.osmandhudbridge.track

import java.util.Locale

/** Texte für Zeit und Strecke (Oberfläche und Dateinamen). */
object RideFormat {
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) String.format(Locale.GERMANY, "%d:%02d h", h, m) else String.format(Locale.GERMANY, "%d min", m)
    }

    fun distance(m: Int): String =
        if (m < 1000) "$m m" else String.format(Locale.GERMANY, "%.1f km", m / 1000.0)

    /** Dateiname ohne Endung: nur Buchstaben, Ziffern, Bindestrich und Unterstrich. */
    fun fileSafe(name: String): String {
        val t = name.trim().replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_-]+"), "_").trim('_')
        return t.take(60).ifEmpty { "Fahrt" }
    }
}
