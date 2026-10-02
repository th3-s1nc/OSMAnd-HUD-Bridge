package io.github.th3s1nc.osmandhudbridge.nav

import io.github.th3s1nc.osmandhudbridge.protocol.NavCommand

/** Was aus der OSMAnd-Navigations-Benachrichtigung gelesen wurde. null = nicht enthalten. */
data class OsmAndNotificationInfo(
    val turnDistanceM: Int?,
    val roundaboutExit: Int?,
    val routeDistanceM: Int?,
    val routeMinutes: Int?,
    /** Tempo laut OSMAnd (bei Simulation das simulierte Tempo), km/h */
    val speedKmh: Int? = null,
    /** Ankunftszeit (Uhrzeit-Teil der Restweg-Zeile), 24-Stunden-Format */
    val arrivalHour: Int? = null,
    val arrivalMinute: Int? = null,
    /** Strassenname aus der Anweisungs-Zeile; nur wenn der Anweisungstext sicher erkannt wurde */
    val nextStreet: String? = null,
    /** Abbiegeart aus dem Titel (Ersatz, falls die OSMAnd-Schnittstelle nichts liefert) */
    val turn: NavCommand? = null
)

/**
 * Format (deutsche OSMAnd-Oberfläche, am Gerät gesehen):
 *   Titel:  "2,3 km • Nehmen Sie die 2 Ausfahrt"
 *   Zeile:  "Nehmen Sie die 2 Ausfahrt St 2615 Hauptstraße 600 m"
 *   Zeile:  "2,9 km • Hauptstraße 7, Köfering"
 *   Zeile:  "8,5 km • 11 min • 15:35 • 68 km/h"   (Restweg • Restzeit • Ankunft • Tempo)
 */
object OsmAndNotificationParser {
    private val DIST = Regex("""^\s*([0-9]+(?:[.,][0-9]+)?)\s*(km|m)\s*$""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(?<![\d:])(\d+)\s*h\b""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)\s*min\b""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""^\s*\d{1,2}:\d{2}\s*(?:[AP]M)?\s*$""", RegexOption.IGNORE_CASE)
    private val SPEED = Regex("""^\s*(\d+)\s*km/h\s*$""", RegexOption.IGNORE_CASE)
    private val TRAILING_DIST = Regex("""\s+[0-9]+(?:[.,][0-9]+)?\s*(?:km|m)\s*$""", RegexOption.IGNORE_CASE)
    private val INSTRUCTION = Regex(
        """^\s*(?:nehmen sie die \d+\.?\s*ausfahrt|halten sie sich (?:links|rechts)|(?:scharf |leicht )?(?:links|rechts) (?:abbiegen|halten)|""" +
            """(?:bitte )?wenden|geradeaus(?: weiter)?|""" +
            """take the \d+(?:st|nd|rd|th)? exit|turn (?:sharp |slight )?(?:left|right)|keep (?:left|right)|continue straight|make a u-turn)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val CLOCK_PARTS = Regex("""^\s*(\d{1,2}):(\d{2})\s*([AP]M)?\s*$""", RegexOption.IGNORE_CASE)
    private val EXIT_DE = Regex("""(\d+)\.?\s*Ausfahrt""", RegexOption.IGNORE_CASE)
    private val EXIT_EN = Regex("""(\d+)(?:st|nd|rd|th)?\s+exit""", RegexOption.IGNORE_CASE)

    fun parseDistanceM(token: String): Int? {
        val m = DIST.matchEntire(token) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (m.groupValues[2].equals("km", true)) Math.round(v * 1000).toInt() else Math.round(v).toInt()
    }

    fun parseMinutes(token: String): Int? {
        val h = HOURS.find(token)?.groupValues?.get(1)?.toIntOrNull()
        val m = MINUTES.find(token)?.groupValues?.get(1)?.toIntOrNull()
        if (h == null && m == null) return null
        return (h ?: 0) * 60 + (m ?: 0)
    }

    private fun tokens(line: String) = line.split('•', '·').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Abbiegeart aus dem Text der Benachrichtigung ("rechts abbiegen, dann", "Geradeaus", "halb links abbiegen", ...).
     * Deutsch und Englisch. Unbekannter Text -> null (lieber kein Pfeil als ein falscher).
     */
    fun parseTurn(text: String?, exit: Int?): NavCommand? {
        if (text == null) return null
        if (exit != null) return NavCommand.ROUNDABOUT
        val t = text.substringBefore(',').lowercase().trim()
        val left = "links" in t || "left" in t
        val right = "rechts" in t || "right" in t
        return when {
            "wenden" in t || "u-turn" in t || "u turn" in t -> if (right) NavCommand.UTURN_RIGHT else NavCommand.UTURN_LEFT
            "scharf" in t || "sharp" in t -> if (left) NavCommand.SHARP_LEFT else if (right) NavCommand.SHARP_RIGHT else null
            "halten" in t || "keep" in t -> if (left) NavCommand.KEEP_LEFT else if (right) NavCommand.KEEP_RIGHT else null
            // "halb/leicht": sanfte Richtungsänderung, wie in OsmAndTurns (Typ 3/6) als "halten"-Pfeil
            "halb" in t || "leicht" in t || "slight" in t || "bear" in t ->
                if (left) NavCommand.KEEP_LEFT else if (right) NavCommand.KEEP_RIGHT else null
            "abbiegen" in t || "turn" in t -> if (left) NavCommand.TURN_LEFT else if (right) NavCommand.TURN_RIGHT else null
            "geradeaus" in t || "straight" in t || "continue" in t -> NavCommand.STRAIGHT
            else -> null
        }
    }

    fun parseClock(token: String): Pair<Int, Int>? {
        val m = CLOCK_PARTS.matchEntire(token) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[3].uppercase()) {
            "PM" -> if (h < 12) h += 12
            "AM" -> if (h == 12) h = 0
        }
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /** "Nehmen Sie die 2 Ausfahrt St 2615 Hauptstrasse 600 m" -> "St 2615 Hauptstrasse". Unbekannter Anweisungstext -> null. */
    private val THEN = Regex("""^[\s,;:.\-\u2013]*(?:dann|danach|anschlie\u00dfend|then|and then)\b[\s,;:]*""", RegexOption.IGNORE_CASE)

    fun parseStreet(line: String): String? {
        if (line.contains('\u2022') || line.contains('\u00b7')) return null
        if (!TRAILING_DIST.containsMatchIn(line)) return null
        var rest = line.replace(TRAILING_DIST, "")
        var matched = false
        // "links abbiegen, dann St 2615 Landshuter Strasse": Anweisung(en) und "dann" abschneiden
        while (true) {
            val m = INSTRUCTION.find(rest) ?: break
            matched = true
            rest = rest.substring(m.range.last + 1)
            rest = THEN.replaceFirst(rest, "")
            rest = rest.trimStart(' ', ',', ';', ':')
        }
        if (!matched) return null
        return rest.trim().takeIf { it.length >= 2 }
    }

    fun parse(title: String?, body: List<String>): OsmAndNotificationInfo {
        var turnDist: Int? = null
        var exit: Int? = null
        if (!title.isNullOrBlank()) {
            turnDist = tokens(title).firstOrNull()?.let { parseDistanceM(it) }
            exit = (EXIT_DE.find(title) ?: EXIT_EN.find(title))?.groupValues?.get(1)?.toIntOrNull()
        }
        var routeDist: Int? = null
        var routeMin: Int? = null
        var arrival: Pair<Int, Int>? = null
        for (line in body) {
            val t = tokens(line)
            if (t.size < 2) continue
            val dist = parseDistanceM(t[0]) ?: continue
            // Restweg-Zeile: zweites Feld ist eine Dauer, danach meist die Ankunftszeit
            val dur = parseMinutes(t[1]) ?: continue
            if (t.drop(2).any { CLOCK.matches(it) } || t.size == 2) {
                routeDist = dist
                routeMin = dur
                arrival = t.drop(2).firstNotNullOfOrNull { parseClock(it) }
                break
            }
        }
        val speed = body.asSequence().flatMap { tokens(it).asSequence() }
            .mapNotNull { SPEED.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
        val street = body.asSequence().mapNotNull { parseStreet(it) }.firstOrNull()
        val turnText = title?.let { tokens(it).drop(1).firstOrNull() }
        return OsmAndNotificationInfo(
            turnDist, exit, routeDist, routeMin, speed, arrival?.first, arrival?.second, street,
            parseTurn(turnText ?: title, exit)
        )
    }
}

/** Ansagen des OSMAnd-Sprachrouters (Callback registerForVoiceRouterMessages). */
object OsmAndVoice {
    /** "reached_destination" kommt, wenn das Ziel erreicht ist (am Gerät gesehen: cmds=[reached_destination, Arbeit]). */
    fun isDestinationReached(commands: List<String>?): Boolean =
        commands?.any { it == "reached_destination" } == true

    /**
     * Zwischenziel erreicht. OSMAnd nennt das Kommando vermutlich "reached_intermediate"
     * (nicht am Gerät gesehen), daher tolerant: "reached_*" mit intermediate/via/waypoint.
     */
    fun isViaReached(commands: List<String>?): Boolean =
        commands?.any {
            val c = it.lowercase()
            (c.startsWith("reached") || c.startsWith("arrive")) &&
                (c.contains("intermediate") || c.contains("via") || c.contains("waypoint"))
        } == true
}
