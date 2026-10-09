# OSMAnd HUD Bridge

[English version](README.md)

Inoffizielle Android-App für den privaten Gebrauch. Sie steuert das Bluetooth-LE-Head-up-Display (*Tilsberk / DVision* für Motorradhelme) direkt an, gespeist von den Navigationsdaten von [OSMAnd](https://osmand.net). Kein Herstellerkonto, keine Hersteller-App und kein Server nötig.

> **Keine Verbindung** zu Tilsberk, Digades oder OSMAnd. Alle Produktnamen und Marken gehören ihren Inhabern und dienen nur der Beschreibung der Kompatibilität. **Keine Gewähr.** Die Anzeige kann ausfallen oder falsch sein. Verlass dich beim Fahren nicht darauf, Verkehrsschilder und -regeln haben Vorrang. Nutzung auf eigene Gefahr.

## Was die App tut
- **Freies Fahren ohne Navigation:** Auch ohne Ziel zeigt das HUD das aktuelle Tempolimit und (im Modus City) den Straßennamen, soweit in OpenStreetMap vorhanden. Das Original-HUD zeigt das nur bei aktiver Navigation. Auf der Übersicht trennt der Schalter **Freies Fahren** nur die Verbindung zu OSMAnd (GPS, Tempolimit und Straßenname laufen weiter, ebenso Anrufe, WhatsApp und Spotify). Unter jeder Überschrift steht eine kurze Beschreibung, kleine graue i-Knöpfe erklären Einzelheiten bei den Schaltern. Die Info-Seite öffnet das i oben rechts.
- Zeigt am HUD Tempo, Tempolimit (aus OpenStreetMap), Abbiegepfeil und Entfernung, Restweg, Restzeit und Ankunft, Kompass sowie eingehende Anrufe, WhatsApp-Nachrichten und auf Wunsch den laufenden Musiktitel (Interpret, dann Titel, je 19 Zeichen, nur Explorer und City).
- Sechs Anzeigen: Navigator, Minimalist, Explorer, City sowie **Guide** (Pfeil mit Entfernung und zwei frei wählbare Zeilen) und **Cruiser** (vier frei wählbare Zeilen, für freies Fahren). Bei Guide und Cruiser wählt ein Zahnrad an der Karte die Werte je Zeile (Geschwindigkeit, Tempolimit, Reststrecke, Restzeit, Ankunft, Fahrzeit, Strecke, Höhe, nächste Straße oder leer). Beide sind noch Tests. Die Auswahl wird gespeichert. Straßennamen zeigt das HUD nur im Modus City, Anrufe, Nachrichten und Musik nur in Explorer und City.
- **Fahrten aufzeichnen:** Der Tab Tracking ist immer anwählbar, auch beim Navigieren. Aufzeichnung per Knopf, Autopause nach 3 Minuten Stand (geht beim Losfahren von selbst weiter), Ende nur durch dich. Gespeichert wird als GPX und CSV in Download/GPX-Tracking (GPX mit Tempo je Punkt, auf Wunsch auch Höhe per Barometer, Tempolimit mit Überschreitungen und geschätzter Schräglage); die Fahrten stehen in der Liste "Meine Fahrten". Eine Fahrt öffnet eine eigene Seite mit Karte der Route (OpenStreetMap-Kacheln, beim ersten Öffnen aus dem Internet), Kennzahlen und Diagrammen mit Markierung, außerdem GPX in einer Karten-App ansehen, teilen und löschen. Eine unterbrochene Aufnahme wird beim nächsten Öffnen zum Speichern angeboten.
- Optional: dauerhafter Geradeaus-Pfeil zwischen den Abbiegungen, damit man auf langen Geraden sieht, dass die Navigation läuft.
- Auf der Übersicht öffnet ein Knopf neben dem Status OSMAnd direkt.
- Optional: akustische Warnung (dreifaches "Dü", einmal pro Überschreitung) über das Handy, z. B. zur Intercom.
- **Blitzer-Warnung** (aus, mit Hinweis beim Einschalten; in Deutschland und einigen Ländern für Fahrer nicht erlaubt): aus OpenStreetMap und aus einer eigenen GPX-Liste (z. B. SCDB, nicht enthalten). Eigener Schalter für den Ton, das HUD zeigt kurz Blitzer-Symbol und Entfernung.
- **Hinweistöne** (einzeln schaltbar, ein einzelner Ton): Bahnübergänge, Zebrastreifen, Verkehrsberuhigung und sehr scharfe Kurven.
- **Straßendaten importieren:** Eine Kartendatei (.osm.pbf, z. B. von Geofabrik) einmal einlesen. Tempolimits, Zonen, Ortsschilder und Straßennamen liegen dann auf dem Handy, auch ohne Internet. Dazu ein Schalter "Offline-Daten". Ohne Import lädt die App die Daten unterwegs (aktuelle Kachel und die nächste) von einem Overpass-Server und speichert sie 30 Tage.
- Läuft als Vordergrunddienst auch bei ausgeschaltetem Display, verbindet sich selbst neu und zeigt lieber nichts als etwas Falsches.
- Der Dienst läuft auch ohne verbundenes HUD (GPS, Tempolimit, Straßenname, Töne; Datenspeicher einstellbar). Mit HUD fragt die App das GPS jede Sekunde ab, sonst seltener.
- **Saisonfahrer:** Außerhalb der Saison ruht die App und verbraucht keine Energie. Die Saison-Übersicht in "Meine Fahrten" zeigt Fahrten, Strecke und Fahrzeit.
- Der Reiter Übersicht zeigt live Tempolimit und Straßenname. Das Protokoll steht mit der neuesten Meldung oben, ein Schalter macht es ausführlicher.
- Optional: Handy-Akku-Warnung am HUD. Im Werkzeuge-Reiter gibt es einen Element-Test für HUD-Anzeigecodes.
- Navigationsdaten: OSMAnd-Schnittstelle (Pfeil, Entfernung, Ziel erreicht, Zwischenziel) und OsmAnds Navigationsbenachrichtigung (Restweg/-zeit, Ankunft, Straße, Kreisverkehr-Ausfahrt). Ist die Schnittstelle nicht verfügbar (z. B. App in OSMAnd nicht freigegeben), kommen Pfeil und Entfernung ersatzweise aus der Benachrichtigung.
- Tempo vom Handy-GPS, Tempolimit aus OpenStreetMap (importiert oder online über Overpass, auch ohne Navigation; fehlende Limits lassen sich optional aus Ortsschildern und Nachbarabschnitten schätzen).

## Voraussetzungen
- Android 8.0+, Bluetooth LE, gekoppeltes HUD
- [OSMAnd](https://osmand.net) (Google Play, F-Droid "OSMAnd~" oder APK). In OSMAnd diese App unter *Verbundene Apps* freigeben (Menüname je nach Version).
- Benachrichtigungszugriff für diese App (für OSMAnd, WhatsApp, Anrufe und Musiktitel)

Vom Autor mit einem HUD auf einem Xiaomi 10T (LineageOS) getestet. Andere Geräte, Firmwarestände und Android-Versionen sind ungetestet.

**Sprache:** Die Oberfläche der App ist derzeit **nur auf Deutsch**. Das HUD selbst zeigt keine App-Texte, nur Symbole, Zahlen und Straßennamen. Eine englische Übersetzung ist geplant, Beiträge sind willkommen.

**Neben der Original-App nutzen:** Das HUD akzeptiert nur eine Bluetooth-Verbindung gleichzeitig. Schalte **HUD verbinden** (Reiter Start) aus, bevor du die Hersteller-App benutzt. Dann nutzt diese App kein Bluetooth zum HUD mehr, Tempolimit, Straßennamen und Töne laufen aber weiter. **App aktiv** schaltet dagegen alles ab.

## Bauen
Ordner in Android Studio öffnen (JDK 17, Gradle 8.13 / AGP 8.9.2) oder:

```
./gradlew assembleDebug
```

Protokoll, Parser und die Logik für Tempolimit, GPS-Takt, Saison, Überschreitungs-Warnung, Fahrtaufzeichnung und Fahrtauswertung haben Unit-Tests (`HudProtocolTest`, `OsmAndNotificationParserTest`, `SpeedLimitMatcherTest`, `SpeedLimitLogicTest`, `GpsPolicyTest`, `SeasonPlanTest`, `NavExtrapolationTest`, `OverspeedAlarmTest`, `BatteryWarnTest`, `CameraWarnTest`, `CameraImportTest`, `PbfImportTest`, `PointWarnTest`, `CurveWarnTest`, `SeasonSummaryTest`, `TripTrackerTest`, `TrackSessionTest`, `TrackExtrasTest`, `RideDetailTest`). Sie laufen ohne Android-SDK als reine Kotlin-Tests. BLE, Dienst und Oberfläche sind nur manuell getestet.

## Einrichten
1. HUD einschalten und in den Android-Bluetooth-Einstellungen koppeln.
2. App öffnen, den Schalter "HUD verbinden" einschalten, Berechtigungen erlauben.
3. Im Reiter Werkzeuge die Schalter "Benachrichtigungszugriff" und "Akku-Optimierung aus" antippen und in den Android-Einstellungen erlauben (die Schalter zeigen danach "an").
4. Navigation in OSMAnd starten.

## Fehlerbehebung (APK installieren)
- **„App wurde zum Schutz deines Geräts blockiert" (Google Play Protect):** *Weitere Details* antippen, dann *Trotzdem installieren*. Play Protect kennt den Signaturschlüssel dieser Open-Source-App noch nicht.
- **Benachrichtigungszugriff ausgegraut („Eingeschränkte Einstellung"):** Ab Android 13 ist das für Apps gesperrt, die per Datei oder Browser installiert wurden. *Einstellungen → Apps → OSMAnd HUD Bridge* öffnen, oben rechts das **⋮-Menü** antippen, **Eingeschränkte Einstellungen zulassen** wählen und bestätigen, danach den Benachrichtigungszugriff einschalten. Fehlt das Menü, den ausgegrauten Schalter einmal antippen und erneut nachsehen. Menünamen unterscheiden sich je nach Hersteller.
- **Keine Daten von OSMAnd:** Die App in OSMAnd unter *Verbundene Apps* aktivieren (nach jeder Neuinstallation mit anderer Signatur erneut).

## Datenschutz
- Benachrichtigungen von OSMAnd, WhatsApp und Anrufen sowie der Musiktitel (Interpret, Titel) werden nur gelesen, um sie am HUD anzuzeigen. Nichts wird gespeichert oder gesendet.
- Ohne importierte Daten wird für Straßennamen und Tempolimits die ungefähre Position an einen Overpass-Server (OpenStreetMap) gesendet. Mit dem Schalter *Offline-Daten* (Reiter Tempolimit) wird nichts gesendet.
- Das Protokoll liegt nur auf dem Gerät. Es kann Namen von Anrufern oder Absendern enthalten, vor dem Teilen prüfen.

## Protokoll
Das Bluetooth-Protokoll, soweit bekannt, steht in [PROTOCOL.md](PROTOCOL.md) (englisch).

## Lizenzen
MIT für den eigenen Code ([LICENSE](LICENSE)). Fremde Bestandteile und Hinweise: [NOTICE.md](NOTICE.md). Kartendaten © OpenStreetMap-Mitwirkende (ODbL).

## Mitmachen
Issues und Pull Requests sind willkommen: Tests mit anderer HUD-Firmware, andere Navi-Apps, **Übersetzungen (zuerst eine englische Oberfläche)**.
