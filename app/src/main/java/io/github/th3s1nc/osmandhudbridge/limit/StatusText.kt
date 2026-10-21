package io.github.th3s1nc.osmandhudbridge.limit

/**
 * Übersetzt die (intern deutschen) Statuszeilen und Protokollzeilen erst bei der Anzeige.
 * Intern bleiben die deutschen Wörter bestehen, weil andere Programmteile sie auswerten.
 */
object StatusText {
    private val table: List<Pair<String, String>> = listOf(
        "OSMAnd-Ansage" to "OSMAnd announcement",
        "OSMAnd: keine Antwort, versuche erneut" to "OSMAnd: no answer, trying again",
        "OSMAnd: Ansagen nicht verfügbar" to "OSMAnd: announcements not available",
        "OSMAnd: Bridge ist in OSMAnd nicht freigegeben" to "OSMAnd: bridge is not enabled in OSMAnd",
        "Pfeil kommt ersatzweise aus der Benachrichtigung" to "arrow comes from the notification instead",
        "OSMAnd: verbunden" to "OSMAnd: connected",
        "Navigation in OSMAnd starten" to "start navigation in OSMAnd",
        "OSMAnd: Anmeldung fehlgeschlagen" to "OSMAnd: registration failed",
        "OSMAnd: Verbindung verloren" to "OSMAnd: connection lost",
        "OSMAnd: Binding beendet" to "OSMAnd: binding ended",
        "OSMAnd: Schnittstelle liefert nichts" to "OSMAnd: interface delivers nothing",
        "OSMAnd: verbinde" to "OSMAnd: connecting",
        "OSMAnd nicht erreichbar, neuer Versuch in 10 s" to "OSMAnd not reachable, new attempt in 10 s",
        "OSMAnd: aus" to "OSMAnd: off",
        "Callback-ID" to "callback ID",
        "HUD: gestoppt" to "HUD: stopped",
        "Bluetooth ist aus" to "Bluetooth is off",
        "ungültige HUD-Adresse" to "invalid HUD address",
        "warte auf HUD (Hintergrundverbindung)" to "waiting for HUD (background connection)",
        "verbinde (Versuch " to "connecting (attempt ",
        "verbunden, suche Dienste" to "connected, searching services",
        "verbunden und bereit" to "connected and ready",
        "Dienst konnte nicht starten (Berechtigungen?)" to "service could not start (permissions?)",
        ", neuer Versuch in " to ", new attempt in ",
        "HUD: aus" to "HUD: off",
        "GPS: warte auf Fix" to "GPS: waiting for fix",
        "GPS: Sparmodus (ohne HUD)" to "GPS: power-saving mode (without HUD)",
        "GPS: kein aktueller Fix" to "GPS: no current fix",
        "GPS: Ortung ist ausgeschaltet" to "GPS: location is switched off",
        "GPS: keine Berechtigung (Tempo-Anzeige aus)" to "GPS: no permission (speed display off)",
        "GPS-Fehler" to "GPS error",
        "Tempo: " to "Speed: ",
        "Limit: aus" to "Limit: off",
        "Limit: warte auf Position" to "Limit: waiting for position",
        "Limit: unbekannt" to "Limit: unknown",
        "Limit: Karte wird geladen" to "Limit: map is loading",
        "Limit: Abfrage fehlgeschlagen, neuer Versuch" to "Limit: query failed, trying again",
        "(geschätzt)" to "(estimated)",
        "(Karte fehlt noch)" to "(map still missing)",
        "(keine Straße in der Nähe in den Kartendaten)" to "(no road nearby in the map data)",
        "(Straße ohne Tempo-Angabe)" to "(road without speed information)",
        "Tempolimit: " to "Speed limit: ",
        "unbekannt" to "unknown",
        "Blitzer in " to "Speed camera in ",
        " m voraus" to " m ahead",
        "Hinweis voraus: " to "Notice ahead: ",
        "Bahnübergang" to "level crossing",
        "Zebrastreifen" to "zebra crossing",
        "Verkehrsberuhigung" to "traffic calming",
        "Scharfe Kurve voraus (Radius " to "Sharp bend ahead (radius ",
        "OSM-Abfrage fehlgeschlagen" to "OSM query failed",
        "OSM: Kachel " to "OSM: tile ",
        " Straßen, " to " roads, ",
        " mit Limit, " to " with limit, ",
        " Ortsschilder, " to " town signs, ",
        " Blitzer" to " speed cameras",
        "keine Blitzerdaten (alte Kachel)" to "no camera data (old tile)",
        "Ortsschild " to "Town sign ",
        "Eingang, innerorts" to "entering, built-up area",
        "Ausgang, außerorts" to "leaving, outside built-up area",
        " Fehlversuche (" to " failed attempts (",
        "Dienst gestartet" to "Service started",
        ", ohne HUD" to ", without HUD",
        "Karte wird geladen" to "map is loading",
    )

    fun en(raw: String): String {
        if (!Tx.en) return raw
        var s = raw
        for ((de, en) in table) if (s.contains(de)) s = s.replace(de, en)
        return s
    }

    private fun swapSeps(n: String) = n.map { when (it) { '.' -> ','; ',' -> '.'; else -> it } }.joinToString("")

    private val doneDe = Regex("^Fertig: ([\\d.,]+) Kacheln, ([\\d.,]+) MB \\((\\d+) s\\)$")
    private val doneEn = Regex("^Done: ([\\d.,]+) tiles, ([\\d.,]+) MB \\((\\d+) s\\)$")
    private val readDe = Regex("^Eingelesen am (.+) aus (.+)$", RegexOption.DOT_MATCHES_ALL)
    private val readEn = Regex("^Read on (.+) from (.+)$", RegexOption.DOT_MATCHES_ALL)

    /** Gespeicherte Import-Zeilen (Ergebnis, "Eingelesen am ...") in der aktuellen Sprache anzeigen, auch wenn sie in der anderen gespeichert wurden. */
    fun stored(raw: String): String {
        if (Tx.en) {
            doneDe.find(raw)?.let { return "Done: ${swapSeps(it.groupValues[1])} tiles, ${swapSeps(it.groupValues[2])} MB (${it.groupValues[3]} s)" }
            readDe.find(raw)?.let { return "Read on ${it.groupValues[1]} from ${it.groupValues[2]}" }
        } else {
            doneEn.find(raw)?.let { return "Fertig: ${swapSeps(it.groupValues[1])} Kacheln, ${swapSeps(it.groupValues[2])} MB (${it.groupValues[3]} s)" }
            readEn.find(raw)?.let { return "Eingelesen am ${it.groupValues[1]} aus ${it.groupValues[2]}" }
        }
        return raw
    }
}
