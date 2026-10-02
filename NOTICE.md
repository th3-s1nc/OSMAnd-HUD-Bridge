# Hinweise (OSMAnd HUD Bridge)

Inoffizielles Hobbyprojekt. Keine Verbindung zu Tilsberk, Digades oder OSMAnd.
Genannte Namen und Marken gehören ihren Inhabern. "Tilsberk" und "DVision" dienen nur der Beschreibung der Geräte-Kompatibilität.

## Haftung
Keine Gewähr. Anzeigefehler und Ausfälle sind möglich. Nutzung beim Fahren auf eigene Gefahr. Siehe auch Haftungsausschluss in der MIT-Lizenz (`LICENSE`).

## Eigene Teile (MIT, `LICENSE`)
Quellcode der App, Zuordnungstabellen, Parser, Oberfläche, Vorschaubilder und App-Symbol (eigene Zeichnungen), Dokumentation. Urheber: th3_s1nc.
Es sind keine Programmteile, Grafiken, Schriften oder Texte der Hersteller-App enthalten.

## Fremde Bestandteile

| Teil | Quelle / Lizenz | Hinweis |
|---|---|---|
| OSMAnd-Schnittstelle (`app/src/main/aidl`, `app/src/main/java/net/osmand`) | von OSMAnd. Laut OSMAnd-Dokumentation ist die AIDL-API "No License issues - available for all possible purposes". Das Full-Library-SDK (GPLv3) wird nicht verwendet | Nicht von der MIT-Lizenz dieses Projekts erfasst. Die Dateien tragen keinen eigenen Lizenzkopf, die Aussage steht in der OSMAnd-Doku (osmand.net, "OSMAnd API / SDK"). OSMAnd-Code (GPLv3) und -Grafiken (CC BY-NC-ND) sind nicht enthalten |
| Kartendaten (Straßennamen, Tempolimits) | © OpenStreetMap-Mitwirkende, ODbL (openstreetmap.org/copyright) | Hinweis in der App (Reiter Info) |
| Overpass API | öffentliche Server, moderate Nutzung | für wenige Nutzer gedacht |
| AndroidX, Material Components | Apache License 2.0 | Abhängigkeiten, werden beim Bauen geladen |

## Nennung der Produktnamen
"Tilsberk" und "DVision" werden nur beschreibend genannt, um zu sagen, mit welchem Gerät die App zusammenarbeitet (vgl. § 23 Abs. 1 Nr. 3 MarkenG, "Bestimmungshinweis"). Sie stehen nicht im App-Namen, Paketnamen oder Repository-Namen, es werden keine Logos oder Bildmarken verwendet, und es soll nicht der Eindruck einer Verbindung zu den Herstellern entstehen. Der Hersteller (Digades GmbH) hat sich Ende 2024 aus dem Endkundengeschäft zurückgezogen; wer heute Rechte an den Marken hält, wurde nicht geprüft.

## HUD-Protokoll
Das Funkprotokoll wurde allein zur Interoperabilität ermittelt: durch Beobachten des Funkverkehrs (Mitschnitte) und durch Untersuchung der Hersteller-App, um Nachrichtenformate zu verstehen. Es ist in `PROTOCOL.md` beschrieben. Es ist kein Code, keine Grafik und kein sonstiges Material der Hersteller-App übernommen. Die Rechte an Namen, Marken und der Hardware liegen beim jeweiligen Inhaber. Ob und wie weit an einer Funkschnittstelle Schutzrechte bestehen, wurde nicht abschließend geklärt; die rechtliche Einordnung liegt bei dem, der das Projekt weitergibt oder nutzt.
