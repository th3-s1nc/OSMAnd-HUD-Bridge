# Changelog

(Deutsch. Die Versionsnummern bis 0.9.x gehören zur Entwicklung vor dem Paketwechsel.)

## v0.11.78
- Unter dem Schieber bei "Blitzer warnen" steht jetzt statt des allgemeinen Beispiels der echte ungefähre Abstand in Metern bei 50, 100 und 130 km/h. Der Text ändert sich mit dem Schieber (Kurz / Normal / Lang). Die Zahlen kommen aus derselben Rechnung wie die Warnung selbst, auf 10 m gerundet.
- Oberfläche nicht kompiliert getestet.

## v0.11.77
- **Fehler behoben:** Mit ausgeschaltetem "HUD verbinden" beendete sich der Dienst sofort, deshalb gab es kein Tempolimit, keinen Straßennamen und keine Töne ("Live" zeigte überall "–"). Das war eine Nebenwirkung aus 0.11.72: Damals ließ das Vorladen den Dienst ungewollt weiterlaufen, und mit dem Entfernen des Vorladens fiel das weg.
- **Jetzt:** Mit "App aktiv" an und "HUD verbinden" aus läuft der Dienst weiter, nur ohne Bluetooth: GPS, Tempolimit, Straßenname, Live-Anzeige und die Töne (Blitzer, Bahnübergang, Zebrastreifen, Verkehrsberuhigung, Kurven). Der Ton bei Überschreitung bleibt an ein verbundenes HUD gebunden. "App aktiv" aus lässt die App weiter komplett ruhen. Ist ein HUD gewünscht, wird aber 10 Minuten lang nicht gefunden, beendet sich der Dienst wie bisher.
- Fehlt die Bluetooth-Berechtigung, startet der Dienst trotzdem (nur mit Standort), statt abzubrechen.
- Die Beschreibungen bei "HUD verbinden" und "App aktiv" sind angepasst.
- Oberfläche und Dienst sind nicht kompiliert getestet.

## v0.11.76
- **Neu: Warnung vor sehr scharfen Kurven** (Karte "Hinweise mit Ton", Schalter "Scharfe Kurven", Standard aus). Nur Ton (derselbe einzelne Ton wie bei Bahnübergang usw.), nichts am HUD.
- **So funktioniert es:** Aus der Straßenform in den Kartendaten wird die engste Kurve vor dir gesucht (aktuelle Straße, bei gleichem Namen auch die anschließenden Stücke, bis etwa 450 m). Aus dem Radius folgt ein Kurventempo (Wurzel aus Querbeschleunigung mal Radius). Bist du mindestens 10 km/h schneller und kannst gerade noch bequem bremsen (2 s Reaktion, 2,5 m/s²), kommt einmal der Ton.
- **Regler "Empfindlichkeit":** Wenig (Radius bis 30 m, 5,0 m/s²), Normal (bis 45 m, 4,0 m/s², Standard), Viel (bis 60 m, 3,0 m/s²). Gemessen an zwei aufgezeichneten Fahrten (452 km, Oberpfalz) gab das etwa 3, 5 und 7 Töne je 100 km. Die engsten Kehren sind in allen Stufen dabei.
- **Nicht gewarnt wird:** innerorts (Ortsschild-Zone, Limit bis 50, beleuchtete Straße), in Wohn-, Zufahrts- und Fußwegen, unter etwa 40 km/h.
- Geht auch mit online geladenen Kacheln (die Straßenform ist dort gleich). Wo OSM grob gezeichnet ist, kann eine Kurve schärfer wirken, als sie ist.
- Oberfläche und Dienst sind nicht kompiliert getestet.

## v0.11.75
- **Neu: Karte "Hinweise mit Ton"** (Tab Tempolimit, unter der Tempowarnung): drei Schalter, alle standardmäßig aus: **Bahnübergänge**, **Zebrastreifen**, **Verkehrsberuhigung**. Es kommt ein einzelner Ton (höher als das dreifache "Dü", 780 Hz, 0,34 s), nichts am HUD. Das dreifache "Dü" bleibt für Tempoüberschreitung und Blitzer.
- **Wann der Ton kommt:** einmal pro Punkt, nur für Punkte vor dir auf deiner Linie, nur ab etwa 20 km/h. Der Abstand hängt vom Tempo ab: Zeit zum Reagieren (2 s) plus bequemes Bremsen (2,5 m/s²), also etwa 70 m bei 50 km/h und 210 m bei 100 km/h (mindestens 40 m, höchstens 250 m). Bei Bahnübergang und Verkehrsberuhigung kommt er etwas später (60 %), weil man dort nicht anhalten muss. Liegen mehrere Punkte dicht beieinander (Zebrastreifen mit Insel), gibt es nur einen Ton.
- **Nur mit importierten Straßendaten:** Online geladene Kacheln kennen diese Punkte nicht. Beim Einschalten ohne Import gibt es einen Hinweis. Kacheln aus dem Import von 0.11.71 oder neuer enthalten die Punkte schon, ein neuer Import ist nicht nötig.
- Kommt später: Warnung vor scharfen Kurven (mit Regler wenig / normal / viel).
- Oberfläche und Dienst sind nicht kompiliert getestet.

## v0.11.74
- **Tab Tempolimit neu geordnet** (nach Themen): Aktuell (Schild oben, Beschreibung darunter), Warnung (Tempowarnung), Tempolimit (Limit-Erkennung), Blitzer (alles in einer Karte: Warnung, Warnabstand, Ton, Ampelblitzer/Abschnitt/Tunnel, eigene Blitzer-Liste mit Import), Straßendaten (Import, Offline-Daten, Gespeicherte Daten).
- **Neu: eigener Schalter "Ton bei Blitzern"**, unabhängig von der Tempowarnung. Der bisherige Schalter heißt "Ton bei Überschreitung" und gilt nur noch dafür. Beide starten mit deiner bisherigen Einstellung.
- **Karte "Gespeicherte Daten"** enthält jetzt auch den Datenspeicher (Regler für das Speicherlimit, belegter Platz).
- **Entfernt:** der Schalter "Tempolimit aus Kartendaten" (Hauptschalter). Die Karten "Datenquelle" und "Fehlende Limits" sind jetzt die Karte "Limit-Erkennung". Wer nichts aus dem Internet laden will, nutzt "Offline-Daten".
- Oberfläche und Dienst sind nicht kompiliert getestet.

## v0.11.73
- **Tab Tempolimit neu geordnet:** Die Karte "Tempowarnung" enthält jetzt nur die Überschreitung (Schalter, Schwelle in km/h direkt darunter, Akustische Warnung). Die Blitzer-Warnung hat eine eigene Karte mit Warnabstand.
- **Reihenfolge der Karten:** Tempowarnung, Blitzer-Warnung, Straßendaten importieren, Datenspeicher, Blitzer-Liste, Gespeicherte Daten, danach Datenquelle (mit "Fehlende Limits" in einer Karte zusammengelegt).
- **"Gespeicherte Daten"** zeigt die Statistik jetzt immer an (ohne i-Knopf): Zahl der Kacheln (importiert und online geladen), belegter Platz und die Summe aus allen Importen (Straßen, Tempolimits, Zonen, Ortsschilder, Bahnübergänge, Zebrastreifen, Verkehrsberuhigung …) sowie die Blitzer-Zählung. Bei mehreren Dateien können Überschneidungen an den Rändern doppelt zählen. Der i-Knopf bei "Straßendaten importieren" zeigt weiter die Zahlen je Datei.
- **Hinweis zum Aktualisieren:** Sind die importierten Daten älter als etwa 6 Monate, steht bei "Straßendaten importieren" eine orange Zeile "Daten vom … : ein neuer Import wäre gut." Nur ein Hinweis, keine Benachrichtigung.
- Der Schalter heißt jetzt "Tempolimit aus Kartendaten" (statt "aus OSM-Daten"), der Erklärungstext passt zu Import und Online-Laden.
- Oberfläche und Dienst sind nicht kompiliert getestet.

## v0.11.72
- **Neu: i-Knopf bei "Straßendaten importieren".** Er zeigt eine Zusammenfassung je eingelesener Datei: Zahl der Straßen (davon mit Tempolimit, mit Tempo-Zone, beleuchtet, Tunnel, Brücken), Ortsschilder, Temposchilder, Blitzer, Bahnübergänge, Fußgängerüberwege (davon Zebrastreifen) und Verkehrsberuhigung. Gemerkt werden die letzten 8 Dateien; "Importierte Daten entfernen" löscht auch die Zusammenfassung.
- **Neu: Fortschrittsbalken beim Blitzer-Import** (GPX-Dateien): "Datei n von N", danach "Zusammenführen …". Der Knopf ist währenddessen gesperrt.
- **Neue Texte:** Karte "Straßendaten importieren", Knopf "Datei wählen", kürzere Beschreibung (.osm.pbf, z. B. von Geofabrik, mehrere Regionen nacheinander möglich). Neue Karte "Gespeicherte Straßendaten" mit der Blitzer-Zählung.
- **Entfernt (der Import ersetzt es):** "Straßendaten laden ab x km" (Vorladen), "Tour laden" (GPX-Route), Laden im Hintergrund, Schalter für mobile Daten und der Saison-Vorlauf (das Vorladen vor Saisonbeginn entfällt; die Saison selbst bleibt). Die Abhängigkeit androidx.work wurde entfernt.
- **Bleibt:** Laden unterwegs (aktuelle Kachel und die nächste in Fahrtrichtung, ca. 3 km voraus) und die Erneuerung online geladener Kacheln nach 30 Tagen. **Importierte Kacheln werden nie online erneuert**, sie gelten immer als gültig.
- Am Gerät noch nicht erprobt (Oberfläche und Dienst sind nicht kompiliert getestet).

## v0.11.71
- **Neu: Straßenkarten importieren** (Tab Tempolimit, neue Karte "Straßenkarten importieren"). Du lädst am Handy eine Karte als Datei (Endung .osm.pbf, zum Beispiel von Geofabrik, auch einzelne Regierungsbezirke) und wählst sie mit "Kartendatei wählen". Die App rechnet sie selbst in Kacheln um, ohne PC und ohne Internet. Fortschritt als Balken mit Prozent, Schritt (1 Straßen suchen, 2 Punkte lesen, 3 Kacheln schreiben), Zahl der fertigen Kacheln und grober Restzeit, auch in der Benachrichtigung (mit "Abbrechen"). Die Umrechnung läuft als Dienst und arbeitet in Streifen, damit wenig Arbeitsspeicher reicht (mit der Oberpfalz-Datei, 85 MB, in 15 Sekunden am Rechner, 2.186 Kacheln, 14 MB; das Handy ist am Gerät noch nicht gemessen, auch ganz Bayern ist noch nicht erprobt).
- Die Kacheln haben das gleiche Format wie die online geladenen. Gespeichert werden befahrbare Straßen mit Tempo-Angaben (maxspeed, Richtungen, Zonen), Ortsschilder, Blitzer sowie für spätere Warnungen Bahnübergänge, Zebrastreifen, Verkehrsberuhigung und Temposchilder (diese werden noch nicht benutzt). Mehrere Regionen nacheinander werden an der Kante zusammengeführt (gleiche Straßen-Nummer: das Neue gewinnt). "Importierte Daten entfernen" löscht nur importierte Kacheln, online geladene bleiben.
- **Neuer Schalter "Offline-Daten"** (Standard aus). An: die App lädt nichts mehr aus dem Internet (weder unterwegs noch im Voraus noch im Hintergrund) und nutzt nur gespeicherte und importierte Kacheln, auch alte. Fehlende Gebiete gelten als leer. Aus: wie bisher.
- Die App darf jetzt mehr Arbeitsspeicher nutzen (largeHeap), neuer Dienst-Typ "Datenübertragung" für den Import. Kartendaten © OpenStreetMap-Mitwirkende (ODbL).

## v0.11.70
- **Neu: eigene Blitzer-Liste** (Tab Tempolimit, Karte "Blitzer-Liste"). Mit "GPX-Dateien wählen" importierst du eine eigene Liste, zum Beispiel von SCDB, mehrere Dateien auf einmal. Bei SCDB steht Art und Limit im Namen der Datei beziehungsweise der Kategorie (Tempo_50, Ampel_30, Abschnitt_80, Tunnel, Tempo_variabel, Kamera). Gleiche Nummern werden zusammengelegt (die Datei mit Art und Limit gewinnt gegen die allgemeine "Kamera"-Datei), Punkte unter 30 m Abstand ebenfalls. Mit den echten SCDB-Dateien ergibt das rund 6.700 Blitzer. Die Liste liegt nur im App-Speicher auf dem Handy (nicht im Projekt) und wird bei einer neuen Auswahl ersetzt; "Liste entfernen" löscht sie.
- **Warnung mit der Liste:** Die Blitzer-Warnung nutzt die Liste zusätzlich zu den OSM-Blitzern (OSM-Blitzer näher als 30 m an einem eigenen werden weggelassen). Schalter für Ampelblitzer, Abschnittskontrolle und Tunnel (Standard an); Tempoblitzer warnen immer. Am HUD zeigt der Gefahren-Bildschirm das Tempolimit des Blitzers, wenn die Liste eines kennt.
- Ohne Richtungsangabe in den Daten gilt wie bisher: nur Blitzer vor dir auf deiner Linie. Auf Autobahnen kann deshalb ab und zu auch ein Blitzer der Gegenseite warnen.

## v0.11.69
- **Blitzer-Zählung über alle gespeicherten Kacheln:** Unter "Straßendaten laden" steht jetzt zusätzlich eine zweite Zeile "Alle gespeicherten Kacheln: x Blitzer in y von z Kacheln mit Blitzerdaten". Sie zählt im Hintergrund (höchstens einmal pro Minute) alle gespeicherten Kacheln, auch wenn der Dienst nicht läuft, und rechnet einen Blitzer an der Kachelgrenze nur einmal. Ältere Kacheln aus der Zeit vor 0.11.66 haben noch keine Blitzerdaten und werden getrennt genannt. So siehst du, wie dicht die festen Blitzer in OpenStreetMap bei dir eingetragen sind.

## v0.11.68
- **Blitzer-Warnung: HUD schaltet kurz um.** Kommt die Warnung vor einem Blitzer, wechselt das HUD für höchstens 4 Sekunden auf den Gefahren-Bildschirm (Tempolimit, Blitzer-Symbol, Entfernung) und geht dann von selbst zurück auf die gewählte Anzeige. Ist man vorher am Blitzer vorbei, geht es früher zurück. Nicht umgeschaltet wird, wenn die nächste Abbiegung näher als 300 m ist (Kreuzung: der Pfeil bleibt sichtbar, es gibt nur den Ton), im Element-Test und in der Justage. Am Gerät noch nicht erprobt: wie schnell das HUD umschaltet.

## v0.11.67
- **Neu: Blitzer-Warnung** (Tab Tempolimit, Karte "Tempowarnung"). Schalter, standardmäßig aus; beim Einschalten kommt erst ein Hinweis (in Deutschland und einigen anderen Ländern für Fahrer nicht erlaubt), dann "Trotzdem aktivieren" oder "Abbrechen". Der Regler "Warnabstand" hat Kurz, Normal und Lang (ca. 6, 10 und 15 Sekunden vor dem Blitzer, mindestens 100 m, höchstens 600 m). Je schneller du fährst, desto früher kommt die Warnung (Normal: bei 100 km/h ca. 280 m, bei 50 km/h ca. 140 m). Gewarnt wird nur vor festen Blitzern vor dir auf deiner Straße (kein Blitzer auf der Gegenseite oder an Parallelstraßen), pro Blitzer einmal. Die Daten kommen aus OpenStreetMap, mobile Blitzer und Abschnittskontrollen fehlen. Braucht "Tempolimit aus OSM-Daten".
- **HUD:** Das HUD bekommt während der Warnung den Kameratyp im Tempolimit-Feld und die Entfernung (Feld 25, am Gerät nur auf dem Gefahr-Bildschirm 22 bestätigt). Welche Anzeige das im Alltag zeigt, ist noch zu testen.
- **Ton:** Neuer Warnton "dü-dü-dü" (drei weiche Töne, selbst erzeugt). Er gilt für die Tempo-Überschreitung und für Blitzer. Der Schalter "Akustische Warnung" ist jetzt auch bei ausgeschalteter Tempowarnung bedienbar, wenn die Blitzer-Warnung an ist.

## v0.11.66
- **Blitzer-Test (erster Schritt):** Die Kartendaten von OpenStreetMap enthalten jetzt zusätzlich die festen Blitzer ("highway=speed_camera"). Im Tab Werkzeuge, Karte "Straßendaten laden", steht eine Zeile "Blitzer (nur feste, aus OpenStreetMap)" mit der Zahl in den gerade geladenen Kacheln. So lässt sich prüfen, wie vollständig die Daten für deine Gegend sind. Es gibt noch keine Warnung am HUD. Bereits geladene Kacheln der Vorversion haben keine Blitzerdaten und werden beim nächsten Laden (im Hintergrund oder unterwegs) neu geholt, auch beim Vorladen. Solange kein Netz da ist, werden sie weiter für Straßen und Limits benutzt, die Blitzer sind dann unbekannt. Der alte Speicher wird beim Aufräumen nach Alter mit entfernt.

## v0.11.65
- **Element-Test:** Neu mit Testwert: Entfernung Blitzer (101), Entfernung Zwischenziel (81) sowie Höhenmeter aufwärts (83) und abwärts (84). Die Feldnummern stammen aus dem SDK und sind noch nicht am Gerät bestätigt. Bisher hatte 101 keinen Wert und blieb deshalb leer.

## v0.11.64
- **Vollbild-Karte** in "Meine Fahrten": In einer Fahrt öffnet der Knopf oben rechts an der Karte die Karte im Vollbild. Verschieben mit einem Finger, zoomen mit zwei Fingern, Doppeltipp oder Plus und Minus, "⌖" zeigt wieder die ganze Route. Ein Tipp auf die Route zeigt Tempo und Höhe an der Stelle. Die Markierung aus den Diagrammen wird mitgenommen.
- **Element-Test:** Nicht gesehene Codes bekommen ein rotes ✗ (gesehene ein grünes ✓). Neu in der Bildschirm-Auswahl: **Gefahr (22)**, der Gefahren-Bildschirm des HUD, nur zum Ausprobieren (was er zeichnet, ist unbekannt).

## v0.11.63
- **Neu: Saison-Übersicht** oben in "Meine Fahrten": Zahl der Fahrten, Strecke, Fahrzeit, Höchsttempo, "Zu schnell: x Mal in y Fahrten" und die Strecke pro Monat als kleine Balken (der stärkste Monat in Orange). Mit den Pfeilen neben der Überschrift blätterst du durch frühere Saisons. Die Zeitspanne folgt deiner Einstellung unter Werkzeuge → Saison (auch über den Jahreswechsel, dann "2025/26"); ist keine Saison eingestellt, zählt das Kalenderjahr. Alles wird aus den gespeicherten Fahrten gerechnet. Überschreitungen zählen nur bei Fahrten, bei denen Tempolimits aufgezeichnet wurden.

## v0.11.62
- **Neu: Handy-Akku-Warnung** (Tab Anzeige, Karte "Meldungen"). Mit Schalter (Standard aus) und Regler "Warnen ab" von 5 bis 50 % (Standard 20 %). Fällt der Handy-Akku auf oder unter die Grenze, zeigt das HUD sein Akku-Warnsymbol, dauerhaft. Es verschwindet wieder, wenn der Akku 5 % über der Grenze liegt oder das Handy lädt. Das HUD zeichnet das Symbol nur in **Guide, Cruiser und Explorer** (am Gerät mit dem Element-Test festgestellt). Das Senden an das HUD ist noch nicht am Gerät bestätigt.

## v0.11.61
- **Element-Test neu aufgebaut:** Beim Öffnen schaltet das HUD in den Bildschirm, den du im Test wählst (Navigator, Minimalist, Explorer, City, Guide, Cruiser), mit den echten Live-Werten. Ein angetippter Code **blinkt 5 Sekunden**, danach ist der Bildschirm wieder normal. Erst beim Schließen des Tests geht das HUD zurück in den Modus, der unter "Anzeige" gewählt ist (die Einstellung selbst wird nie verändert). Vergisst du das Schließen, stellt die App nach 3 Minuten Ruhe alles selbst zurück.
- **Protokoll** im Test: jede Antwort ("Gesehen" / "Nicht gesehen") mit Zeit, Modus, Code und Name, nur in diesem Menü (Teilen oder Leeren).
- **GPS-Warnsymbol entfernt:** Kein Bildschirm zeichnet es (am Gerät geprüft). Die Funktion und der Code 37 im Test sind weg.

## v0.11.60
- **Neu: Element-Test** (Werkzeuge, Karte "HUD"): einzelne Anzeige-Codes am HUD testen, mit Protokoll. Der Ablauf wurde in 0.11.61 überarbeitet.

## v0.11.59
- **Vorschauen:** Die Zahl im Tacho (Navigator, Explorer, City) saß zu weit links und verschwand teilweise im weißen Bogen. Sie ist jetzt mittig unter dem Bogen.

## v0.11.58
- **Vorschauen der Anzeigemodi neu:** Schwarz-Weiß mit feinem Innenrahmen, dickere Pfeile, Tempo als Tacho-Bogen wie beim Original (Navigator, Explorer, City), Straßenname fett, Telefonhörer beim Anruf.
- **Beschreibungen dauerhaft sichtbar:** Bei Navigationsanweisung, Meldungen, Straßendaten laden, Tour laden, Datenspeicher, Aufzeichnung, Zusatzaufzeichnung, Saison und den beiden HUD-Karten steht der Text direkt unter dem Strich, das i dahinter ist weg. Weitere Details gibt es per i bei der jeweiligen Zeile (Anrufe, WhatsApp, Hinweise zu den Werten).
- **Umbenannt:** "Straßendaten laden", "Tour laden", "Datenspeicher".
- **Datenspeicher:** Regler jetzt 0,25 / 0,5 / 1 / 2 GB (Standard 1 GB). Größere gespeicherte Werte werden auf 2 GB gesetzt. Die Größenangaben im Hilfetext sind realistischer.

## v0.11.57
- **Vorschauen der Anzeigemodi angepasst:** Die Fahrzeit sitzt rechts, die Spurpfeile stehen mittig unter dem Abbiege-Pfeil, und beim Navigator ist die Linie über dem Tempo weg.

## v0.11.56
- **Tempolimit, Datenquelle:** "Zusätzliche Hinweise der Karte nutzen" heißt jetzt "Zusatzhinweise nutzen", damit der Schalter sichtbar bleibt.

## v0.11.55
- **Werkzeuge, Einrichtung:** Der Hinweistext steht jetzt immer da, das i ist weg.
- **Tempolimit:** Die Karte heißt jetzt "Fehlende Limits".
- **i-Knöpfe sitzen wieder mittig** neben den Überschriften "Straßendaten vorladen" und "Navigationsanweisung".

## v0.11.54
- **Vorladen:** Die Zeilen heißen jetzt "Mobile Daten nutzen" und "Hintergrundladen", damit der Schalter nicht mehr abgeschnitten wird.

## v0.11.53
- **Vorschauen der Anzeigemodi näher am Original:** Oben sind jetzt Uhrzeit, Bluetooth und HUD-Akku an den Original-Positionen zu sehen, der Inhalt ist wie auf dem HUD angeordnet (Navigator mit Spurpfeilen, Explorer mit Kompass, City mit Straßenname, Guide und Cruiser mit Statuszeile).
- **Alles weiß:** Symbole, Uhrzeiten, Linien und das Tempolimit-Schild (weiße 50 mit weißem Ring) sind weiß, passend zum HUD.
- **Anruf:** Ein Telefonhörer-Symbol vor dem Namen statt "Anruf:".
- Nur die Vorschau-Bilder in der App ändern sich, am HUD selbst nichts.

## v0.11.52
- **Übersichtlicher gegliedert:** Kleine orange Abschnittsüberschriften mit dünner Linie trennen die Karten: Anzeige (ANZEIGEMODI, NAVIGATION, HUD), Tempolimit (AKTUELL, WARNUNG, DATENQUELLE, STRASSENDATEN) und Werkzeuge (EINRICHTUNG, HUD, DIAGNOSE).
- **Neue Kartenüberschriften auf allen Seiten:** Orangebalken, größere Schrift und eine dünne Linie darunter.
- **Tracking:** Der Kopf heißt jetzt "Aufzeichnung" mit einem Status-Etikett (BEREIT, LÄUFT, PAUSE). Während der Aufnahme hat die Karte einen orangen Rand. Im Ruhezustand zeigt sie "0:00 h · 0,0 km".
- **Status oben auf der Übersicht** kürzer und englisch: Paused, Off-season, No HUD, Connecting …, Connected, Error, mit einer kurzen deutschen Zeile darunter. Die lange Erklärung zu "Paused" steht hinter dem i bei "App aktiv".
- Kleine Textänderungen: "Akku-Optimierung aus", "Geradeaus-Pfeil an", "Zusatzaufzeichnung" statt "Was aufgezeichnet wird", bei Guide und Cruiser kein Hinweis mehr auf das Zahnrad.

## v0.11.51
- **i-Knöpfe** auch auf den Seiten Tempolimit, Werkzeuge und Tracking: die Erklärungstexte unter Schaltern und Überschriften sind zugeklappt und öffnen sich per Tipp. Kurze Hinweise unter Schiebereglern bleiben stehen.
- Die Texte der Karten Guide und Cruiser sind stark gekürzt. Der Hinweis, dass Fahrzeit und Strecke bei null starten, steht jetzt im Fenster "Felder wählen". Cruiser ist nicht mehr auf "ohne Navigation" festgelegt.
- Der Knopf "3 Testfahrten anlegen" im Tracking-Tab ist wieder entfernt. Schon angelegte Testfahrten bleiben und lassen sich löschen.

## v0.11.50
- Auf der Seite Anzeige stehen die Erklärungstexte unter den Karten jetzt hinter kleinen grauen **i-Knöpfen** (wie auf der Übersicht): Navigationsanweisung, Geradeaus-Pfeil dauerhaft, Meldungen, Spotify, Automatische Helligkeit und Justage-Modus. Ein Tipp klappt den Text auf und wieder zu. Die Karten der Anzeigen mit ihren Vorschauen bleiben unverändert.

## v0.11.49
- Die Zeilenwahl bei Guide und Cruiser hat kein **Durchschnittstempo** mehr (und damit auch den Hinweis, dass es nicht zusammen mit dem Tempolimit geht). Eine früher gewählte Zeile "Durchschnitt" wird zurück auf den Standardwert gesetzt.

## v0.11.48
- **Zwei neue Namen und eine neue Anzeige:** Die Anzeige "Freies Fahren" heißt jetzt **Cruiser**. Neu ist **Guide**: Abbiegepfeil mit Entfernung und darunter zwei Zeilen (Standard: Reststrecke und Restzeit). Beide sind noch Tests am HUD.
- **Zeilen frei wählen:** An den Karten von Guide (2 Zeilen) und Cruiser (4 Zeilen) öffnet ein **Zahnrad** das Fenster "Felder wählen". Je Zeile gibt es eine Liste: Leer, Geschwindigkeit, Tempolimit, Durchschnittstempo, Reststrecke, Restzeit, Ankunft, Fahrzeit, Strecke, Höhe, nächste Straße. Die Vorschau auf der Karte zeigt die Wahl. Tempolimit und Durchschnitt teilen sich ein Feld am HUD, es geht nur eines von beiden. Werte ohne Daten (zum Beispiel Restzeit ohne Navigation) bleiben leer.
- Die Uhrzeit wird bei Guide und Cruiser jetzt regelmäßig mitgeschickt.

## v0.11.47
- Der Tab **Tracking** ist jetzt immer anwählbar, auch beim Navigieren. Läuft eine Aufnahme, zeigt die Übersicht eine Zeile "Aufzeichnung läuft – zum Tracking" (Antippen öffnet den Tab).
- **Freies Fahren** lässt sich nur einschalten, solange "App aktiv" an ist.
- Die Anzeige "Tracking" heißt jetzt **Freies Fahren**.

## v0.11.46
- **Neue Fahrt-Seite:** Antippen einer Fahrt in "Meine Fahrten" öffnet eine eigene Seite nach dem Vorbild der Calimoto-Tourübersicht, ohne Veröffentlichen, Fotos, Bewertung und Eigenschaften: Name (mit Stift zum Umbenennen), Datum, Strecke und Zeit, **kleine Karte mit der Route** (Start grün, Ziel rot), Knöpfe **In Karten-App ansehen** (öffnet die GPX in einer Karten-App wie OSMAnd) und **Teilen** (GPX oder CSV), neun **Kacheln** (Durchschnitt, Maximum, Auf- und Abstieg, stärkstes Beschleunigen und Bremsen, max. Schräglage, Überschreitungen, Gesamtzeit) und **Diagramme** für Höhe, Schräglage (L oben, R unten), Beschleunigung und Geschwindigkeit (mit Tempolimit als orange Linie). Antippen oder Wischen über ein Diagramm setzt eine blaue Markierung mit Wert, die in allen Diagrammen und als orange Punkt auf der Karte mitläuft. Löschen steht oben rechts.
- Die Karte zeigt **OpenStreetMap-Kacheln**. Sie werden beim ersten Öffnen aus dem Internet geladen und auf dem Handy gemerkt (höchstens 40 MB). Dabei erfährt der Kartenserver, welches Gebiet du ansiehst. Ohne Netz bleibt die Karte hell und leer, die Route wird trotzdem gezeichnet.
- Die Rohpunkte jeder neuen Fahrt werden zusätzlich in einer eigenen kleinen Datei gespeichert (für die Fahrt-Seite). Fahrten aus früheren Versionen werden dafür nicht mehr unterstützt.
- Neuer Knopf **3 Testfahrten anlegen** in "Meine Fahrten": erfundene Fahrten (Allgäu kurz, Eifel mit Pause, Schwäbische Alb lang) zum Ausprobieren der neuen Seite, ohne echte Ausfahrt. Sie lassen sich wie echte Fahrten löschen.

## v0.11.45
- Die drei Aufzeichnungs-Schalter (Höhe, Tempolimit, Schräglage) sind jetzt **standardmäßig aus**. Sind sie an und läuft eine Aufnahme, erscheinen die **aktuellen Werte live** klein unter dem jeweiligen Schalter: Höhe mit Auf- und Abstieg, Limit mit Anzahl der Überschreitungen, Schräglage jetzt und maximal.

## v0.11.44
- **Tracking-Tab, Teil 2:** Drei Schalter "Was aufgezeichnet wird" (Standard an, während einer Aufnahme nicht änderbar): **Höhe** (Barometer, das GPS gibt das Niveau vor; ohne Barometer GPS-Höhe), **Tempolimit und Überschreitungen** und **Schräglage** (Schätzung aus Drehsensor und GPS-Tempo ab ca. 15 km/h, rechts positiv, egal wie das Handy liegt).
- Zusammenfassung zeigt zusätzlich Auf- und Abstieg (Höhenänderungen unter 3 m zählen nicht), wie oft das Tempolimit überschritten wurde (ab Limit + 3 km/h, eine Überschreitung zählt, bis man wieder auf Limit-Tempo ist; geschätzte Limits zählen nicht) und die maximale Schräglage.
- Zu jeder Fahrt gibt es jetzt auch eine **CSV-Datei** (Semikolon, Dezimalkomma, Umlaute korrekt, öffnet in Excel per Doppelklick; eine Zeile je Punkt, keine Zusammenfassung) in Download/GPX-Tracking. Teilen fragt, ob GPX oder CSV.
- In der GPX stehen Limit (`hb:limit`) und Schräglage (`hb:lean`) als eigene Felder, die andere Apps überspringen.

## v0.11.43
- **Tracking-Tab, Teil 1:** Aufzeichnung starten und beenden, mit Anzeige von Zeit in Bewegung, Strecke und Tempo. **Autopause**: nach 3 Minuten Stand pausiert die Aufnahme und geht ab 5 km/h von selbst weiter. Beenden nur durch dich, dann Zusammenfassung mit Name, **Speichern**, **Verwerfen** (mit Rückfrage) oder Weiter aufzeichnen.
- Gespeichert wird als **GPX** (mit Tempo je Punkt, Garmin-Erweiterung) in **Download/GPX-Tracking** und zusätzlich in der App. Die Liste **Meine Fahrten** zeigt alle Fahrten, Antippen öffnet die Zusammenfassung mit Teilen und Löschen (löscht auch die Datei in Downloads).
- Die Punkte werden während der Fahrt laufend gesichert. Wird die App oder der Dienst beendet, bietet die App die Fahrt beim nächsten Öffnen zum Speichern an.
- Während der Aufnahme fragt das GPS jede Sekunde, der Dienst bleibt an, und die Benachrichtigung zeigt "Aufzeichnung läuft". Der Tab bleibt anwählbar, solange aufgenommen wird.
- Noch nicht dabei (Teil 2): Höhe mit Barometer, Tempolimit und Überschreitungen, Schräglage, CSV.

## v0.11.42
- Anzeigemodus **Tracking** überarbeitet: zweites Feld ist jetzt das **Tempolimit** (statt Höhenmeter), Beschriftungen "FAHRZEIT" und "STRECKE". Eigene schematische Vorschau auf der Karte.

## v0.11.41
- Neuer Testmodus "Tracking (Test)" in der Anzeige: der Tracking-Bildschirm des HUD mit vier Feldern wie bei Calimoto (Tempo, Höhenmeter, gefahrene Zeit, gefahrene Strecke), Werte aus dem GPS des Handys.

## v0.11.40
- Text bei "Freies Fahren" klarer: Nur OSMAnd wird getrennt, GPS, Tempolimit und Straßenname laufen weiter.

## v0.11.39
- Übersicht: Der Schalter "Navigation aus OSMAnd" (Werkzeuge, Karte Datenquellen) ist jetzt der Schalter **Freies Fahren** auf der Übersicht, unter "App aktiv". An = OSMAnd komplett getrennt (die Einstellung bleibt dieselbe, nur umgekehrt benannt). Anrufe, WhatsApp und Spotify laufen weiter.
- Kleine graue **i-Knöpfe** hinter den Schaltern auf der Übersicht klappen die Beschreibung auf und zu. Die Übersicht ist dadurch kürzer.
- Der Info-Tab ist aus der unteren Leiste verschwunden (sie fasst höchstens fünf Tabs). Die Info-Seite öffnet das **i oben rechts**; Zurück-Taste oder Antippen eines Tabs führt zurück.
- Neuer Tab **Tracking** (Platzhalter, die Aufzeichnung folgt): grau und nicht anwählbar, solange Freies Fahren aus ist.

## v0.11.38
- Kreisverkehr und Abbiegungen bleiben am HUD stehen, auch wenn OSMAnd länger nichts meldet (im Hintergrund kamen Pausen bis über 30 s vor). Vorher wurde das HUD nach 10 s Stille geleert, und damit war auch die Ausfahrt-Nummer weg. Jetzt zählt die Entfernung mit dem GPS-Tempo weiter herunter (vorher nur 4 s lang) und wird erst nach 60 s ohne Meldung geleert.
- Log: "OSMAnd war X s still", wenn nach mehr als 10 s wieder eine Meldung kommt.

## v0.11.37
- Übersicht: Kommen die OSMAnd-Benachrichtigungen nicht an (Zugriff fehlt oder ist nach einem Update nicht verbunden), zeigt ein orange umrandeter Hinweis das an. Ein Tipp darauf öffnet die Android-Einstellung, dort den Zugriff für die App aus- und wieder einschalten. Der Hinweis verschwindet von selbst, sobald die Verbindung steht.
- Abbiegedistanz am HUD: Die Hochrechnung zwischen zwei OSMAnd-Meldungen nimmt jetzt das aktuelle Tempo (statt der aus den letzten Meldungen errechneten Geschwindigkeit) und zieht 0,8 s Meldeverzögerung ab. Die Anzeige hing sonst 10 bis 30 m hinterher. Bei frischen Meldungen springt die Anzeige nicht mehr zurück.

## v0.11.36
- Neu: Schalter "Akustische Warnung" bei der Tempowarnung (standardmäßig aus). Doppelpiepen, einmal pro Überschreitung (Limit + eingestellte Überschreitung, mindestens 1,5 s lang) und erst wieder, nachdem das Tempo auf oder unter das Limit gefallen ist. Der Ton läuft wie eine Navigationsansage (Musik wird kurz leiser) über das Handy, z. B. zur Intercom. Nur mit verbundenem HUD und nur bei einem Limit aus den Kartendaten, nicht bei einem geschätzten.
- Die App liest beim Start und beim Verbinden des Benachrichtigungszugriffs die schon vorhandene OSMAnd-Benachrichtigung nach. Damit klappt es auch, wenn OSMAnd vor der Bridge gestartet wurde.

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
