package io.github.th3s1nc.osmandhudbridge.track

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt eine Fahrt als CSV für Excel und LibreOffice: Trenner Semikolon, Dezimalkomma, UTF-8 mit Kennung (BOM), damit
 * Umlaute stimmen. Eine Zeile je Punkt, ohne Zusammenfassung. Spalten für Höhe, Tempolimit und Schräglage gibt es nur,
 * wenn sie aufgezeichnet wurden. "Über Limit" folgt derselben Regel wie die Zählung der Überschreitungen.
 */
object CsvWriter {
    const val BOM = "﻿"

    private fun num(v: Double, digits: Int) = String.format(Locale.GERMANY, "%.${digits}f", v)

    fun write(points: List<TrackPoint>): String {
        val st = TrackSession().also { it.restore(points) }.stats()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
        val sb = StringBuilder(points.size * 70 + 200)
        sb.append(BOM)
        val head = arrayListOf("Zeit", "Breite", "Länge")
        if (st.hasEle) head += "Höhe m"
        head += "Tempo km/h"
        if (st.hasLimit) { head += "Limit km/h"; head += "Über Limit" }
        if (st.hasLean) head += "Schräglage °"
        sb.append(head.joinToString(";")).append("\r\n")
        val over = OverTracker()
        for (p in points) {
            val row = arrayListOf(fmt.format(Date(p.timeMs)), num(p.lat, 6), num(p.lon, 6))
            if (st.hasEle) row += (p.ele?.let { num(it, 0) } ?: "")
            row += Math.round(p.speedKmh).toString()
            val isOver = over.update(p.speedKmh, p.limitKmh)
            if (st.hasLimit) {
                row += (if (p.limitKmh > 0) p.limitKmh.toString() else "")
                row += (if (p.limitKmh > 0) (if (isOver) "ja" else "nein") else "")
            }
            if (st.hasLean) row += (p.leanDeg?.let { Math.round(it).toString() } ?: "")
            sb.append(row.joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }
}
