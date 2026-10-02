# Changelog

(Deutsch. Die Versionsnummern bis 0.9.x gehören zur Entwicklung vor dem Paketwechsel.)

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
