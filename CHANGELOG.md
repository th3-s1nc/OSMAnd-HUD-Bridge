# Changelog

(Deutsch. Die Versionsnummern bis 0.9.x gehören zur Entwicklung vor dem Paketwechsel.)

## v0.11.35
- Neu: Quadratischer Knopf mit Navigationspfeil rechts neben dem Status ("Verbunden und bereit") auf der Übersicht. Ein Tipp öffnet OSMAnd. Ist OSMAnd nicht installiert, erscheint eine kurze Meldung.

## v0.11.34
- Build-Fehler behoben ("Only one companion object is allowed per class" in der Benachrichtigungs-Klasse). Keine neue Funktion, v0.11.33 ist damit gebaut lauffähig.

## v0.11.33
- Neu: Schalter "Geradeaus-Pfeil dauerhaft" (Reiter Anzeige, Karte Navigationsanweisung, standardmäßig aus). Solange die Navigation läuft, zeigt das HUD zwischen den Abbiegungen einen Geradeaus-Pfeil ohne Distanz, bis die nächste Abbiegung nah genug ist. Hört OSMAnd auf zu melden (nach 10 s) oder ist das Ziel erreicht, verschwindet er.
- Späte OSMAnd-Meldungen überschreiben die Zielflagge nicht mehr.

## v0.11.32
- Vorschau Explorer: Der Platzhalter "Hauptstraße" ist weg, denn im Modus Explorer sendet die App keinen Straßennamen. README nennt jetzt, in welchen Modi was erscheint.

## v0.11.31
- Nach einem App-Update las die App die OSMAnd-Benachrichtigung nicht mehr (km/h, Gesamtstrecke, Restzeit, Ankunft fehlten), obwohl der Benachrichtigungszugriff "an" zeigte. Die App fordert die Verbindung jetzt beim Start selbst neu an und schreibt nach 10 s eine Warnzeile ins Protokoll, falls sie trotzdem nicht verbunden ist. Neu im Protokoll: "Benachrichtigungszugriff getrennt".

## v0.11.30
- Musik am HUD: Selbstausblendung des HUD jetzt 9 s (genau die Dauer der Anzeige), damit sich beim Weiterschalten Titel nicht überschneiden.

## v0.11.29
- Musik am HUD: Der Titel wurde nur 1 s gezeigt, weil sich das HUD nach 6 s selbst ausblendete. Selbstausblendung jetzt 15 s, das Wegnehmen macht die App.

## v0.11.28
- Spotify/Musik am HUD: Das HUD fror bei Texten über 19 Zeichen ein. Jetzt höchstens 19 Zeichen, nacheinander: Interpret 4 s, 1 s leer, Titel 4 s. Ein Anruf oder eine Nachricht bricht die Anzeige ab.

## v0.11.27
- Fehler behoben: In der Übersicht blieb "Karte wird geladen" stehen, obwohl die Karte längst da war und nur kein Limit gefunden wurde. Jetzt steht dort "Limit: unbekannt".
- Protokoll: Bei "Tempolimit: unbekannt" steht jetzt der Grund dabei (Karte fehlt noch / keine Straße in der Nähe / Straße ohne Tempo-Angabe).
- Vorausschau beim Fahren: Die nächste Kachel wird jetzt schon 3 km vor der Kante geholt (vorher 1 km).
- Live-Laden fragt bis zu 4 Server gleichzeitig (vorher 3).

## v0.11.26
- Protokoll (Werkzeuge) und "Letzte Meldungen" (Übersicht): die neueste Zeile steht jetzt oben. Die Datei zum Teilen bleibt wie bisher zeitlich geordnet (älteste zuerst).

## v0.11.25
- Spotify-Anzeige: der Wechsel Interpret/Titel alle 2 s funktioniert am HUD nicht und ist wieder raus. Stattdessen steht "Interpret - Titel" 16 s lang im Meldungsfeld. Testweise bis 40 Zeichen (Umlaute werden zu ae/oe/ue, andere Sonderzeichen entfallen). Im Protokoll steht die Zeichenzahl des gesendeten Textes.

## v0.11.24
- Spotify-Anzeige: Das HUD scrollt nicht, deshalb wechselt die App 16 s lang alle 2 s zwischen Interpret und Titel (je viermal). Anrufe und Nachrichten verdrängen die Anzeige.

## v0.11.23
- Spotify-Anzeige korrigiert: Der Titel steht jetzt im Meldungsfeld des HUD, dort wo auch Anrufe und Nachrichten erscheinen (nicht mehr an der Stelle des Straßennamens). Länge testweise bis 30 Zeichen (das Feld nahm bisher höchstens 19), 15 s.

## v0.11.22
- Neu bei Meldungen: Schalter "Spotify" (standardmäßig aus). Beginnt in Spotify (oder einer anderen Musik-App) ein neuer Titel, zeigt das HUD 15 s lang "Interpret - Titel" an der Stelle des Straßennamens, ohne Symbol. Nur in Explorer und City, Anrufe haben Vorrang, Pausieren und Weiterspielen zählen nicht, Werbung wird übergangen. Sonderzeichen werden wie bei Straßennamen vereinfacht, höchstens 40 Zeichen. Braucht den Benachrichtigungszugriff, den die App ohnehin hat.
- Der Schalter "Name des Absenders anzeigen" ist entfernt, der Name wird immer angezeigt.
- Der Saison-Schalter heißt jetzt "Saisonfahrer".

## v0.11.21
- Neue Karte "Saison" (Werkzeuge, unter Einrichtung): "Nur in meiner Saison aktiv", Beginn- und Ende-Monat (zum Beispiel März bis Oktober, auch über den Jahreswechsel) und Vorlauf von 0 bis 6 Wochen. Außerhalb der Saison ruht die App komplett (kein Bluetooth, kein GPS, kein Dienst). Im Vorlauf vor Saisonbeginn lädt sie im Hintergrund (nur WLAN) die Straßendaten rund um den letzten Standort, auch wenn "Auch im Hintergrund vorladen" aus ist. Importierte Touren laden immer sofort. Endet die Saison, während die App läuft, beendet sie sich von selbst (Prüfung einmal pro Minute).
- Übersicht und Karte zeigen Klartext: "Außerhalb der Saison", "Die App ruht bis zum Saisonbeginn am 1. März. Straßendaten laden ab 8. Februar."

## v0.11.20
- Schalter neu aufgeteilt. "HUD verbinden" steuert nur noch die Bluetooth-Verbindung zum HUD und bleibt gespeichert. Ohne HUD läuft die App weiter und lädt Straßendaten (Umkreis und Touren). "Bridge aktiv" heißt jetzt "App aktiv": Aus = die App ruht komplett (kein Bluetooth, kein GPS, kein Laden), das HUD ist frei für die Tilsberk-App; nur das Laden im Hintergrund per WLAN läuft weiter, falls eingeschaltet.
- GPS in drei Stufen: mit HUD jede Sekunde; ohne HUD unterwegs alle 30 s, im Stand alle 5 min. Im Sparmodus hört die App zusätzlich auf Ortungen anderer Apps (zum Beispiel OSMAnd), das kostet nichts. Grobe Ortungen (über 200 m ungenau) werden ignoriert.
- Der Dienst läuft ohne HUD weiter, solange das Vorladen über 0 km steht oder eine Tour offen ist. Ist HUD verbinden aus und es gibt nichts zu laden, beendet er sich selbst. Ist das HUD an, aber nicht in Reichweite, und es gibt nichts zu laden, endet er wie bisher nach 10 Minuten.
- Statuszeile oben: "App ruht, HUD frei", "Kein HUD verbunden", "Kein HUD verbunden, Straßendaten laden läuft". "Dienst gestoppt" gibt es nicht mehr. "Dienst beendet" steht nur noch im Protokoll, wenn der Dienst wirklich lief.
- Die Benachrichtigung zeigt ohne HUD "Ohne HUD: lädt Straßendaten, Standort im Sparmodus".

## v0.11.19
- Übersicht, Live: "Straßendaten laden" ist jetzt ein echter Ticker. Es gibt eine Zeile pro offenem Punkt: "Straßendaten Tour „Name“" (darunter "27 von 123 Kacheln") für jede offene Tour, dann "Straßendaten Umkreis". Was gerade geladen wird, steht oben. Fertiges verschwindet, ist alles fertig, ist keine Zeile mehr da. Der Umkreis trägt den Zusatz "(wartet auf Tour)", solange eine Tour offen ist.
- Die Tour-Zahl zieht jetzt nach jeder geladenen Kachel nach.

## v0.11.18
- Protokoll: Wiederholungen werden zusammengefasst, statt sie wegzulassen. Beispiele: "OSMAnd: 48 Verbindungsversuche in 17 min", "Tour vorladen: 7 Fehlversuche (6x timeout, 1x HTTP 403), danach ging es weiter". Die erste Zeile jeder Störung steht weiter einzeln drin.
- Neuer Schalter "Ausführliches Protokoll" (Werkzeuge, Karte Protokoll): schreibt jede Zeile einzeln, für die Fehlersuche.
- Die Protokolldatei darf jetzt etwa 5 MB groß werden (vorher 1 MB). Danach wird wie bisher die ältere Hälfte verworfen.

## v0.11.17
- Protokoll zeigt den Fortschritt: "Tour „Name“: 37 von 123 Kacheln da" (alle 10 Kacheln), "Tour vorladen: alle Kacheln da", "Vorladen: 150 von 1711 Kacheln" (alle 50). Das Laden im Hintergrund schreibt Anfang und Ende ("Hintergrund-Laden beendet: 12 Kacheln neu geladen").
- Fehlermeldungen beim Laden nennen jetzt den Server (zum Beispiel "overpass-api.de: HTTP 403").
- Weist ein Server uns ab (HTTP 403 oder 429), wird er 15 Minuten pausiert statt 3.
- HUD: ein früher gemerktes Gerät, das kein HUD ist (zum Beispiel der Lautsprecher aus v0.11.15), wird vergessen. Der Dienst startet dann nicht mehr von selbst damit. Ein von Hand gewähltes Gerät bleibt gemerkt.

## v0.11.16
- Straßendaten laden: ein Server, der ausfällt oder zu langsam ist, wird ein paar Minuten übersprungen, statt ihn immer wieder zu probieren. Vorladen und Touren warten bei einem Server höchstens 15 s.
- Mehr Server in der Liste (lz4.overpass-api.de, z.overpass-api.de, overpass.openstreetmap.fr). Bei der Fahrt fragen die ersten drei brauchbaren gleichzeitig, fällt einer aus, rückt der nächste nach.
- Protokoll: "OSMAnd nicht erreichbar" steht nur noch einmal drin statt alle 10 Sekunden.
- HUD verbinden: Die App bietet nur noch Geräte mit HUD-Namen an (TILS, Head-Up, DVISION). Findet sie keins, kommt der Hinweis "Kein HUD gefunden"; alle gekoppelten Geräte (z. B. Lautsprecher) erscheinen nur noch auf Knopfdruck.

## v0.11.15
- Reiter Tempolimit: kurze Erklärkarte oben ("Diese App zeigt keine Karte. Sie lädt Straßendaten …"). "Karten" heißt jetzt überall "Straßendaten" (Straßendaten vorladen, Speicher für Straßendaten, Straßendaten laden).
- Übersicht, Live: die Zeile "OSMAnd" zeigt nur noch den Zustand (Verbunden, Verbinde, Nicht verbunden, Aus). Die Ansagen stehen weiter im Protokoll.
- Werkzeuge: "OSMAnd-Navigation übernehmen" heißt jetzt "Navigation aus OSMAnd" und steht direkt unter "Einrichtung".

## v0.11.14
- Neue Karte "Tour vorladen" (Tempolimit): GPX-Datei wählen (Calimoto, Kurviger, Motobit und andere), die App lädt die Karte entlang der Strecke (Rand 1,5 km, bei Dateien mit nur wenigen Punkten 3 km). Gibt es in der Datei eine Aufzeichnung (Track), gilt sie, sonst die Route. Schon gespeicherte Kacheln (unter 90 Tage) werden übersprungen. Die Tour hat Vorrang vor dem Kreis, lädt auch im Hintergrund (nur WLAN) und zeigt den Stand ("123 von 249 Kacheln"). Eine Tour verschwindet, sobald alle Kacheln da sind. "Touren in Arbeit abbrechen" entfernt die offenen Touren, gespeicherte Kacheln bleiben.

## v0.11.13
- Neue Karte "Speicher für Karten" (Tempolimit): Regler 0,5 / 1 / 2 / 5 / 10 GB (Standard 2 GB), darunter "Belegt: … MB, … Kacheln". Ist der Platz voll, werden die ältesten Kacheln gelöscht, bei kleinerer Einstellung sofort. Vorher gab es nur eine feste Grenze von 3000 Kacheln.
- Die Kacheln liegen jetzt im App-Speicher statt im Cache-Ordner. Android leert ihn nicht von selbst, auch "Cache leeren" löscht sie nicht mehr. Vorhandene Kacheln werden beim ersten Start verschoben.
- Vorladen überspringt Kacheln, die jünger als 90 Tage sind (vorher 30 Tage). Die Kacheln bleiben weiterhin 180 Tage gültig.

## v0.11.12
- Übersicht, Karte "Live": neue Zeile "Karte vorladen" mit dem Stand (zum Beispiel "123 von 452 Kacheln", "Fertig …", "Wartet auf WLAN").
- Vorladen: Der Kreis wird jetzt schon nach 3 km Fahrt neu um den aktuellen Standort gelegt (vorher 10 km).
- Vorladen: Unterwegs werden zusätzlich die Kacheln in einem Streifen von 20 km in Fahrtrichtung vorgezogen (nur über WLAN bzw. mit erlaubtem Mobilfunk, nicht im Roaming). Das Gebiet in Fahrtrichtung kommt damit vor dem Rest des Kreises.

## v0.11.11
- Das Vorladen im Hintergrund läuft jetzt auch, wenn "Bridge aktiv" aus ist (das HUD wird dabei nicht berührt, nur WLAN). Hinweistexte, README angepasst.

## v0.11.10
- Neuer Schalter "Auch im Hintergrund vorladen" (Tempolimit, Gebiet vorladen): Das Vorladen läuft dann auch ohne laufenden Dienst und ohne HUD, solange Android es erlaubt (nur WLAN, Akku nicht schwach, alle 6 Stunden und kurz nach dem Einschalten). Nutzt den zuletzt gemerkten Standort. Läuft der Dienst, übernimmt dieser.
- Neue Bibliothek: androidx.work (WorkManager) für den Hintergrund-Auftrag.

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
