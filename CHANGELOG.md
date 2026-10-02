# Changelog

(Deutsch. Die Versionsnummern bis 0.9.x gehören zur Entwicklung vor dem Paketwechsel.)

## v0.11.9
- Vorladen: Schlägt ein Server fehl, wird sofort der nächste der drei Server probiert (nacheinander).
- Das Protokoll nennt bei fehlgeschlagenem Vorladen jetzt den genauen Grund (zum Beispiel "HTTP 429").

## v0.11.8
- Behebt einen Build-Fehler (Funktionsname im Vorlade-Status).
- Anzeige: "Pfeil anzeigen ab" heißt jetzt **Navigationsanweisung**. Reihenfolge: Modus, Navigationsanweisung, Meldungen, Helligkeit, Justage.
- Die Tempo-Quelle wanderte von Tempolimit nach Werkzeuge und heißt dort "Tempo für das HUD".

## v0.11.7
- Neuer Reiter **Tempolimit** (ersetzt "Einstellungen"), mit Live-Kachel oben: Verkehrsschild mit dem aktuellen Limit, darunter die Herkunft (aus den Kartendaten / geschätzt / unbekannt) und das aktuelle Tempo.
- Im Reiter Tempolimit: Tempowarnung (von Anzeige hierher), Tempo-Quelle, Datenquelle, Fehlende Limits schätzen, Gebiet vorladen.
- Meldungen sind jetzt im Reiter Anzeige, "OSMAnd-Navigation übernehmen" im Reiter Werkzeuge.

## v0.11.6
- Einstellungen: neuer Abschnitt **Tempolimit** ganz oben, alle Punkte einzeln schaltbar:
  - "Tempolimit aus OSM-Daten" (Hauptschalter) und "Zusätzliche Hinweise der Karte nutzen" (Tempo-Zonen, Quellenangaben wie innerorts/außerorts, echte Kartendaten, an).
  - "Fehlende Limits schätzen" mit den Unterpunkten "Ortsschilder nutzen" und "Nachbarabschnitte nutzen" (beide an, wirken nur beim Schätzen). Ortsschilder: Eingang = innerorts 50, Ausgang = außerorts 100, gilt bis zum nächsten Schild, höchstens 2,5 km. Nachbarabschnitte: Limit der anschließenden Abschnitte derselben Straße. Beides sind Schätzwerte und lösen keine Tempowarnung aus. Jedes erkannte Ortsschild steht im Protokoll.
  - "Gebiet vorladen" mit neuem Schalter "Auch über mobile Daten vorladen" (aus, nie im Roaming).
- Die übrigen Einstellungen (Meldungen, OSMAnd, Tempo-Quelle) stehen darunter unter "Weitere Einstellungen".
- Kacheln enthalten jetzt auch Ortsschilder. Der Zwischenspeicher wird neu aufgebaut (alte Dateien werden gelöscht).

## v0.11.5
- Tempolimit, Server: Kacheln, die gerade gebraucht werden, werden bei allen drei Servern gleichzeitig angefragt (der schnellste gewinnt, die übrigen werden abgebrochen). Zeitlimit pro Server 20 s statt 9 s.
- Zwischenspeicher: gepackt (gzip), gültig 180 Tage statt 7. Ist eine Kachel älter als 30 Tage, wird sie sofort benutzt und im Hintergrund erneuert. Platz für bis zu 3000 Kacheln.
- Neu in Einstellungen, Datenquellen: Regler "Gebiet vorladen" (Aus, 10, 25, 50 km, Standard 25 km). Im WLAN lädt die App rund um den letzten Standort von selbst langsam im Voraus (ein Server, 2,5 s Pause zwischen den Kacheln, setzt später fort, nutzt keine mobilen Daten). Der Fortschritt steht unter dem Regler. Solange vorgeladen wird, beendet sich der Dienst nicht wegen fehlendem HUD.
- Neue Berechtigung "Netzwerkstatus" (nur um zu erkennen, ob WLAN aktiv ist).

## v0.11.4
- Schreibweise überall "OSMAnd" (App-Name, Benachrichtigung, Texte in der App, README, Hinweise). Interne Namen und der Quellcode von OsmAnd selbst bleiben unverändert.

## v0.11.4
- Einstellungen: Abschnitt "Verbindung" entfällt. Automatisch verbinden hängt jetzt fest an "Bridge aktiv", das Beenden nach 10 Minuten ohne HUD ist ein fester Standard.
- Werkzeuge, Einrichtung: Benachrichtigungszugriff und Akku-Optimierung sind jetzt Schalter, die den echten Zustand der Berechtigung zeigen (beim Öffnen der App neu geprüft). Antippen öffnet die passende Android-Seite, denn setzen oder entziehen darf nur der Nutzer.
- Protokoll (Werkzeuge) und "Letzte Meldungen" (Start) in Orange, Größe unverändert.
- Schreibweise "OSMAnd" auch in internen Bezeichnungen (Log-Kennung, User-Agent).

## v0.11.3
- Start-Reiter neu gestaltet: eine große Statuskarte (großer Statuspunkt, Meldung, darunter die Schalter "HUD verbinden" und "Bridge aktiv"), "Live" mit beschrifteten Werten (GPS und Tempo, Tempolimit, OsmAnd) und die letzten Meldungen. Die Knöpfe Start/Beenden sind durch den Schalter "HUD verbinden" ersetzt.

## v0.11.2
- Helligkeit korrigiert: dunkel und hell waren vertauscht.
- "Pfeil anzeigen ab" ist jetzt ein Regler (Kurz, Normal, Lang, Immer) im Anzeigen-Reiter, über der Tempowarnung. Darunter stehen die Entfernungen für Stadt, Landstraße und schnelle Straße. "Ab 1 km" entfällt (gespeichertes "Ab 1 km" wird zu "Normal").

## v0.11.1
- Neuer Schalter "Fehlendes Limit schätzen" (Einstellungen, Datenquellen, Standard aus): ohne Limit in den Kartendaten wird in Deutschland 50 (Wohnstraße oder beleuchtet) bzw. 100 (Landstraße) angenommen, nie auf Autobahnen. Geschätzte Limits lösen keine Tempowarnung aus.

## v0.11.0
- Anzeigen-Reiter: neue Karte **Tempowarnung** (Schalter und Regler 0 bis 30 km/h Überschreitung, Standard 10). Zu schnell = das HUD zeigt Tempo und Limit dicker. Aus = nie.
- Anzeigen-Reiter: neue Karte **Helligkeit** (Schalter "Automatische Helligkeit", sonst Regler dunkel/mittel/hell). Gilt sofort und wird beim Verbinden gesetzt. Die Zuordnung der Stufen stammt aus einem Mitschnitt und ist am HUD zu prüfen.
- Anzeigen-Reiter: neue Karte **Justage** (Schalter "Justage-Modus"): zeigt das Einstellbild am HUD, beim Ausschalten kehrt das HUD zum gewählten Modus zurück. Nur bei verbundenem HUD, wird nie über einen Neustart hinweg gespeichert.
- Tempolimit deutlich zuverlässiger: Karte wird in Kacheln (ca. 2 x 2 km) geladen, die Kachel in Fahrtrichtung schon vorab, alles auf dem Gerät zwischengespeichert (7 Tage, funktioniert danach auch ohne Netz). Schnellere Wiederholung bei Fehlern (höchstens 15 s statt bis zu 80 s), kürzere Zeitlimits, ein weiterer Server. Auf der gleichen Straße (gleicher Name/Ref) bleibt das Limit bis zu 90 s erhalten, an Kreuzungen und Parallelstraßen gewinnt die Straße, auf der man schon fährt.

## v0.10.8
- Überschrift in der App in der Schreibweise "OSMAnd HUD Bridge".

## v0.10.7
- Überschrift in der App heißt jetzt "OsmAnd HUD Bridge" (stand noch mit dem Herstellernamen darin).

## v0.10.6
- Start-Reiter: Karte "Bridge aktiv" und Statuskarte haben jetzt dieselbe Schriftgröße und denselben Aufbau, der Schalter sitzt rechts.

## v0.10.5
- Neuer Schalter "Bridge aktiv" (Start): aus = die App nutzt weder Bluetooth noch GPS oder Internet und liest keine Benachrichtigungen, das HUD bleibt für die Original-App frei. Bleibt gespeichert.
- Neue Einstellung "Dienst automatisch beenden": Der Dienst beendet sich, wenn das HUD 10 Minuten lang nicht verbunden war (Standard: an).

## v0.10.4
- App-Symbol neu (Entwurf D): Brücke von vorn mit drei Bögen im HUD-Rahmen (Eckklammern), orange Pfeil darüber. Liegt komplett im sicheren Bereich des adaptiven Icons.

## v0.10.3
- App-Symbol: Brücke läuft von vorne links nach hinten rechts und ist undurchsichtig im Vordergrund, das HUD-Symbol (orange Umrandung, größer) sitzt rechts unten und tritt hinter der Brücke hervor.

## v0.10.2
- App-Symbol: stilisierte Steinbogenbrücke in Perspektive (Bögen werden nach hinten kleiner), HUD-Symbol rechts daneben.

## v0.10.1
- App-Symbol: filigrane, halbtransparente Steinbogenbrücke mit dünnen Linien, HUD dahinter.
- NOTICE.md: Hinweis zur Nennung der Produktnamen.

## v0.10.0
- Neuer Paketname `io.github.th3s1nc.osmandhudbridge` (frische Installation, alte App vorher deinstallieren; in OsmAnd die neue App unter "Verbundene Apps" freigeben).
- Klassen/Styles neutral benannt (`HudProtocol` statt Produktname), Kommentare ohne Verweise auf interne Namen des Herstellers.
- Lizenz MIT, README (EN/DE), PROTOCOL.md, `.gitignore`, Gradle-Wrapper-Skripte.
- Neues App-Symbol: Steinbogenbrücke im Vordergrund, schematisches HUD im Hintergrund.

## v0.9.2
- Vorschaubilder neu: echte Zahlen und Texte, eigene Symbole, gleiche Anordnung der Elemente wie im jeweiligen Modus.
- Eigener Reiter "Info" (Hinweise, Haftung, Datenschutz, Quellen/Lizenzen, Version).
- Neues App-Symbol (adaptiv, mit Monochrom-Variante).

## v0.9.1
- Lizenz der OsmAnd-Schnittstelle geklärt (laut OsmAnd-Doku frei verfügbar), Info-Seite und NOTICE.md angepasst.

## v0.9.0
- Neuer App-Name "OsmAnd HUD Bridge", eigene Vorschaubilder, Info-Seite, NOTICE.md. Protokolldatei heißt osmand-hud-bridge-log.txt.

## v0.8.3
- Zwischenziel erreicht: VIA-Symbol für 8 s (OsmAnd-Ansage "reached_intermediate", Name vermutet).

## v0.8.2
- "halb/leicht links/rechts" (OsmAnd Typ 3/6) wird als KEEP_LEFT/KEEP_RIGHT gesendet statt als Abbiegepfeil.

## v0.8.1
- Ersatz für fehlende OsmAnd-Schnittstelle (Callback-ID -1, z. B. OsmAnd~ aus F-Droid ohne Freigabe): Pfeil und Entfernung aus der Benachrichtigung (deutsch/englisch). Dann fehlen Zielflagge und Hochrechnung zwischen den Meldungen.

## v0.8
- Reiter Anzeige mit Vorschaubildern der vier Modi.
- City: nächste und aktuelle Straße liegen im HUD übereinander, deshalb wird immer nur eine gesendet (nächste Straße hat Vorrang).

## v0.7.3
- Schalter "OsmAnd-Navigation übernehmen" aus: auch die Daten aus der OsmAnd-Benachrichtigung werden ignoriert. Anrufe/WhatsApp unberührt.

## v0.7.2
- Log wird in eine Textdatei geschrieben (höchstens ca. 1 MB, danach wird die ältere Hälfte verworfen). Teilen und Löschen in der App.

## v0.7
- Zielflagge über die OsmAnd-Ansage `reached_destination`; die Abstands-Heuristik entfiel.
- WhatsApp-Nachrichten laufen über die Anruf-Anzeige (5 s); ein laufender Anruf hat Vorrang.

## v0.6 – Anrufe und WhatsApp am HUD
- Einstellungen > Meldungen: Schalter für Anrufe, WhatsApp-Nachrichten und Absendername. Gelesen wird über den Benachrichtigungszugriff.
- Anzeige nur in Explorer und City. Nachrichten 5 s, Anruf bis er endet (höchstens 120 s).

## v0.5
- Zielflagge (`GOAL`) per Heuristik (später ersetzt).

## v0.4
- Anzeigemodi Navigator, Minimalist, Explorer, City; zuletzt gewählter Modus wird gespeichert; automatisches Verbinden.
