package io.github.th3s1nc.osmandhudbridge.limit

import java.util.Locale

/**
 * Sprache für Texte, die im Programm gebaut werden (Meldungen, Statuszeilen, Dialoge).
 * Feste Texte der Oberfläche stehen in den Textdateien (strings.xml). Standard ist Deutsch.
 */
object Tx {
    @Volatile var en: Boolean = false

    fun t(de: String, en: String): String = if (this.en) en else de

    /** Zahlenformat passend zur Sprache (Deutsch: 1.234,5 / English: 1,234.5). */
    fun loc(): Locale = if (en) Locale.US else Locale.GERMANY
}
