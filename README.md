# OSMAnd HUD Bridge

[Deutsche Version](README.de.md)

An unofficial, private-use Android app that drives a Bluetooth LE head-up display (the *Tilsberk / DVision* HUD for motorcycle helmets) directly, fed by the navigation data of [OSMAnd](https://osmand.net). No vendor account, app or server needed.

> **Not affiliated** with Tilsberk, Digades or OSMAnd. All product names and trademarks belong to their owners and are used only to describe compatibility. **No warranty.** The display can fail or be wrong. Do not rely on it while riding; traffic signs and rules always take precedence. Use at your own risk.

## What it does
- **Free riding without navigation:** even without a destination the HUD shows the current speed limit and (in City mode) the street name, where OpenStreetMap has them. The original setup only shows this during active navigation. The **Free riding** switch on the overview screen disconnects only OSMAnd (GPS, speed limit and street name keep working, as do calls, WhatsApp and Spotify). A short description sits under every heading, small grey i buttons explain details of single switches, and the info page opens with the i at the top right.
- Shows speed, speed limit (from OpenStreetMap), turn arrow and distance, remaining distance / time / arrival, compass, incoming call / WhatsApp notices and, optionally, the current music track (artist, then title, 19 characters each, Explorer and City only) on the HUD.
- Six displays: Navigator, Minimalist, Explorer, City, plus **Guide** (arrow with distance and two selectable lines) and **Cruiser** (four selectable lines, for free riding). A gear on the card of Guide and Cruiser picks the value per line (speed, speed limit, remaining distance, remaining time, arrival, ride time, distance, height, next street, or empty). Both are still tests. The choice is saved. Street names only show in City mode; calls, messages and music only in Explorer and City.
- **Recording rides:** the Tracking tab is always available (also while navigating). Start/stop by button, auto-pause after 3 minutes standing (resumes by itself when you ride off), ended only by you. Saved as GPX and CSV in Download/GPX-Tracking (GPX with speed per point, optionally also barometer altitude, speed limit with overspeed count and estimated lean angle); rides appear in "Meine Fahrten". A ride opens its own page with a route map (OpenStreetMap tiles, fetched from the internet on first view), statistics and charts with a marker, plus view the GPX in a map app, share and delete. An interrupted recording is offered for saving the next time you open the app.
- Optional: a permanent straight-ahead arrow between turns, so you can see on long straights that navigation is running.
- On the Home tab a button next to the status opens OSMAnd directly.
- Optional: audible warning (triple beep, once per overspeed event) played by the phone, e.g. to a helmet intercom.
- **Speed camera warning** (off; a note appears when switching on; not allowed for drivers in Germany and some other countries): uses OpenStreetMap and an own GPX list (e.g. SCDB, not included). Separate switch for the sound; the HUD briefly shows the camera symbol and distance.
- **Hint tones** (each switchable, one single tone): level crossings, pedestrian crossings, traffic calming and very sharp bends.
- **Import road data:** load a map file (.osm.pbf, e.g. from Geofabrik) once. Speed limits, zones, town signs and street names are then stored on the phone and work without internet. An **Offline data** switch is available. Without an import the app loads data while riding (current tile and the next one) from an Overpass server and keeps it for 30 days.
- Runs as a foreground service, works with the screen off, reconnects automatically, shows nothing rather than something wrong.
- The service also runs without a connected HUD (GPS, speed limit, street name, sounds; storage limit adjustable). With the HUD the GPS is polled every second, otherwise less often.
- **Season rider:** outside the season the app rests and uses no energy. The season overview in "Meine Fahrten" shows rides, distance and ride time.
- The Home tab shows live speed limit and street name. The log shows the newest entry first, and a switch makes it more detailed.
- Optional: phone battery warning on the HUD. The Tools tab has an element test for HUD display codes.
- Navigation data comes from OSMAnd's AIDL API (arrow, distance, destination reached, intermediate point) and from OSMAnd's navigation notification (remaining distance/time, arrival, street, roundabout exit). If the API is not available (e.g. app not enabled in OSMAnd), arrow and distance fall back to the notification.
- Speed comes from the phone GPS, speed limits from OpenStreetMap (imported or online via Overpass, also without navigation; missing limits can optionally be estimated from town signs and neighbouring sections).

## Requirements
- Android 8.0+ (API 26), Bluetooth LE, a paired HUD
- [OSMAnd](https://osmand.net) (Google Play, F-Droid "OSMAnd~" or the official APK). In OSMAnd, enable this app under *Connected apps* (menu name may vary by version).
- Notification access for this app (for OSMAnd, WhatsApp, calls and music tracks)

Tested by the author with one HUD on a Xiaomi 10T (LineageOS). Other devices, firmware versions and Android versions are untested.

**Language:** The app's user interface is currently **German only**. The HUD itself shows no app text, only symbols, numbers and street names. An English translation is planned and contributions are welcome (see Contributing).

**Using it next to the original app:** the HUD accepts only one Bluetooth connection at a time. Switch **HUD verbinden** (connect HUD, Home tab) off before you use the vendor app. The app then no longer uses Bluetooth to the HUD, but speed limit, street names and sounds keep running. **App aktiv** (app active), on the other hand, switches everything off.

## Build
Open the folder in Android Studio (JDK 17, Gradle 8.13 / AGP 8.9.2), or:

```
./gradlew assembleDebug
```

The protocol and parser logic, speed-limit matching, GPS interval, season logic, overspeed alarm, ride recording and ride analysis have unit tests (`HudProtocolTest`, `OsmAndNotificationParserTest`, `SpeedLimitMatcherTest`, `SpeedLimitLogicTest`, `GpsPolicyTest`, `SeasonPlanTest`, `NavExtrapolationTest`, `OverspeedAlarmTest`, `BatteryWarnTest`, `CameraWarnTest`, `CameraImportTest`, `PbfImportTest`, `PointWarnTest`, `CurveWarnTest`, `SeasonSummaryTest`, `TripTrackerTest`, `TrackSessionTest`, `TrackExtrasTest`, `RideDetailTest`; they run without the Android SDK as plain Kotlin tests); the BLE, service and UI code is only covered by manual testing.

## Setup
1. Switch the HUD on and pair it in Android's Bluetooth settings.
2. Open the app, switch on *HUD verbinden* (connect HUD) and grant the permissions.
3. In the Tools tab, tap the switches *Benachrichtigungszugriff* (notification access) and *Akku-Optimierung aus* (battery optimization off) and allow them in Android settings (the switches then show "on").
4. Start navigation in OSMAnd.

## Troubleshooting (installing the APK)
- **"App blocked to protect your device" (Google Play Protect):** tap *More details*, then *Install anyway*. Play Protect does not know the signing key of this open-source app yet.
- **Notification access is greyed out ("Restricted setting"):** Android 13+ blocks this for apps installed from a file or browser. Open *Settings → Apps → OSMAnd HUD Bridge*, tap the **⋮ menu** (top right), choose **Allow restricted settings**, confirm, then enable notification access. If the menu is missing, tap the greyed-out switch once and look again. Menu names vary by manufacturer.
- **No data from OSMAnd:** enable this app in OSMAnd under *Connected apps* (again after every reinstall under a new signature).

## Privacy
- Notifications from OSMAnd, WhatsApp and phone calls, and the music track (artist, title), are read only to show them on the HUD. Nothing is stored or sent anywhere.
- Without imported data, the approximate position is sent to an Overpass server (OpenStreetMap) for street names and speed limits. With the *Offline data* switch (Speed limit tab) nothing is sent.
- The log stays on the device. It may contain caller or sender names; check it before sharing.

## Protocol
See [PROTOCOL.md](PROTOCOL.md) for the Bluetooth protocol as far as it is known.

## Licenses
MIT for this project's own code (see [LICENSE](LICENSE)). Third-party parts and notes: [NOTICE.md](NOTICE.md). Map data © OpenStreetMap contributors (ODbL).

## Contributing
Issues and pull requests are welcome, especially: tests with other HUD firmware, other navigation apps, and **translations (English UI first)**.
