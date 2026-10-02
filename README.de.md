# OsmAnd HUD Bridge

[English version](README.md)

Inoffizielle Android-App für den privaten Gebrauch. Sie steuert das Bluetooth-LE-Head-up-Display (*Tilsberk / DVision* für Motorradhelme) direkt an, gespeist von den Navigationsdaten von [OsmAnd](https://osmand.net). Kein Herstellerkonto, keine Hersteller-App und kein Server nötig.

> **Keine Verbindung** zu Tilsberk, Digades oder OsmAnd. Alle Produktnamen und Marken gehören ihren Inhabern und dienen nur der Beschreibung der Kompatibilität. **Keine Gewähr.** Die Anzeige kann ausfallen oder falsch sein. Verlass dich beim Fahren nicht darauf, Verkehrsschilder und -regeln haben Vorrang. Nutzung auf eigene Gefahr.

## Was die App tut
- Zeigt am HUD Tempo, Tempolimit (aus OpenStreetMap), Abbiegepfeil und Entfernung, Restweg, Restzeit und Ankunft, nächste und aktuelle Straße, Kompass sowie eingehende Anrufe und WhatsApp-Nachrichten.
- Vier Anzeigemodi (Navigator, Minimalist, Explorer, City), in der App wählbar und gespeichert.
- Läuft als Vordergrunddienst auch bei ausgeschaltetem Display, verbindet sich selbst neu und zeigt lieber nichts als etwas Falsches.
- Navigationsdaten: OsmAnd-Schnittstelle (Pfeil, Entfernung, Ziel erreicht, Zwischenziel) und OsmAnds Navigationsbenachrichtigung (Restweg/-zeit, Ankunft, Straße, Kreisverkehr-Ausfahrt). Ist die Schnittstelle nicht verfügbar (z. B. App in OsmAnd nicht freigegeben), kommen Pfeil und Entfernung ersatzweise aus der Benachrichtigung.
- Tempo vom Handy-GPS, Tempolimit aus OpenStreetMap über Overpass (online, auch ohne Navigation).

## Voraussetzungen
- Android 8.0+, Bluetooth LE, gekoppeltes HUD
- [OsmAnd](https://osmand.net) (Google Play, F-Droid "OsmAnd~" oder APK). In OsmAnd diese App unter *Verbundene Apps* freigeben (Menüname je nach Version).
- Benachrichtigungszugriff für diese App (für OsmAnd, WhatsApp, Anrufe)

Vom Autor mit einem HUD auf einem Xiaomi 10T (LineageOS) getestet. Andere Geräte, Firmwarestände und Android-Versionen sind ungetestet.

**Sprache:** Die Oberfläche der App ist derzeit **nur auf Deutsch**. Das HUD selbst zeigt keine App-Texte, nur Symbole, Zahlen und Straßennamen. Eine englische Übersetzung ist geplant, Beiträge sind willkommen.

**Neben der Original-App nutzen:** Das HUD akzeptiert nur eine Bluetooth-Verbindung gleichzeitig. Schalte **Bridge aktiv** (Reiter Start) aus, bevor du die Hersteller-App benutzt. Solange er aus ist, nutzt diese App weder Bluetooth noch GPS oder Netz und liest keine Benachrichtigungen.

## Bauen
Ordner in Android Studio öffnen (JDK 17, Gradle 8.13 / AGP 8.9.2) oder:

```
./gradlew assembleDebug
```

Protokoll und Parser haben Unit-Tests (`HudProtocolTest`, `OsmAndNotificationParserTest`, `SpeedLimitMatcherTest`). BLE, Dienst und Oberfläche sind nur manuell getestet.

## Einrichten
1. HUD einschalten und in den Android-Bluetooth-Einstellungen koppeln.
2. App öffnen, "HUD verbinden / Dienst starten" tippen, Berechtigungen erlauben.
3. "Akku-Optimierung ausschalten" tippen und den Benachrichtigungszugriff erlauben (Reiter Werkzeuge).
4. Navigation in OsmAnd starten.

## Fehlerbehebung (APK installieren)
- **„App wurde zum Schutz deines Geräts blockiert" (Google Play Protect):** *Weitere Details* antippen, dann *Trotzdem installieren*. Play Protect kennt den Signaturschlüssel dieser Open-Source-App noch nicht.
- **Benachrichtigungszugriff ausgegraut („Eingeschränkte Einstellung"):** Ab Android 13 ist das für Apps gesperrt, die per Datei oder Browser installiert wurden. *Einstellungen → Apps → OsmAnd HUD Bridge* öffnen, oben rechts das **⋮-Menü** antippen, **Eingeschränkte Einstellungen zulassen** wählen und bestätigen, danach den Benachrichtigungszugriff einschalten. Fehlt das Menü, den ausgegrauten Schalter einmal antippen und erneut nachsehen. Menünamen unterscheiden sich je nach Hersteller.
- **Keine Daten von OsmAnd:** Die App in OsmAnd unter *Verbundene Apps* aktivieren (nach jeder Neuinstallation mit anderer Signatur erneut).

## Datenschutz
- Benachrichtigungen von OsmAnd, WhatsApp und Anrufen werden nur gelesen, um sie am HUD anzuzeigen. Nichts wird gespeichert oder gesendet.
- Für Straßennamen und Tempolimits wird die ungefähre Position an einen Overpass-Server (OpenStreetMap) gesendet. Abschaltbar unter Einstellungen → Datenquellen.
- Das Protokoll liegt nur auf dem Gerät. Es kann Namen von Anrufern oder Absendern enthalten, vor dem Teilen prüfen.

## Protokoll
Das Bluetooth-Protokoll, soweit bekannt, steht in [PROTOCOL.md](PROTOCOL.md) (englisch).

## Lizenzen
MIT für den eigenen Code ([LICENSE](LICENSE)). Fremde Bestandteile und Hinweise: [NOTICE.md](NOTICE.md). Kartendaten © OpenStreetMap-Mitwirkende (ODbL).

## Mitmachen
Issues und Pull Requests sind willkommen: Tests mit anderer HUD-Firmware, andere Navi-Apps, **Übersetzungen (zuerst eine englische Oberfläche)**.
