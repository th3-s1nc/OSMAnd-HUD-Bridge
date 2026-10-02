# OsmAnd HUD Bridge

[Deutsche Version](README.de.md)

An unofficial, private-use Android app that drives a Bluetooth LE head-up display (the *Tilsberk / DVision* HUD for motorcycle helmets) directly, fed by the navigation data of [OsmAnd](https://osmand.net). No vendor account, app or server needed.

> **Not affiliated** with Tilsberk, Digades or OsmAnd. All product names and trademarks belong to their owners and are used only to describe compatibility. **No warranty.** The display can fail or be wrong. Do not rely on it while riding; traffic signs and rules always take precedence. Use at your own risk.

## What it does
- Shows speed, speed limit (from OpenStreetMap), turn arrow and distance, remaining distance / time / arrival, next and current street, compass, and incoming call / WhatsApp notices on the HUD.
- Four display modes (Navigator, Minimalist, Explorer, City), selectable in the app, remembered between runs.
- Runs as a foreground service, works with the screen off, reconnects automatically, shows nothing rather than something wrong.
- Navigation data comes from OsmAnd's AIDL API (arrow, distance, destination reached, intermediate point) and from OsmAnd's navigation notification (remaining distance/time, arrival, street, roundabout exit). If the API is not available (e.g. app not enabled in OsmAnd), arrow and distance fall back to the notification.
- Speed comes from the phone GPS, speed limits from OpenStreetMap via Overpass (online, also without navigation).

## Requirements
- Android 8.0+ (API 26), Bluetooth LE, a paired HUD
- [OsmAnd](https://osmand.net) (Google Play, F-Droid "OsmAnd~" or the official APK). In OsmAnd, enable this app under *Connected apps* (menu name may vary by version).
- Notification access for this app (for OsmAnd, WhatsApp, calls)

Tested by the author with one HUD on a Xiaomi 10T (LineageOS). Other devices, firmware versions and Android versions are untested.

**Language:** The app's user interface is currently **German only**. The HUD itself shows no app text, only symbols, numbers and street names. An English translation is planned and contributions are welcome (see Contributing).

## Build
Open the folder in Android Studio (JDK 17, Gradle 8.13 / AGP 8.9.2), or:

```
./gradlew assembleDebug
```

The protocol and parser logic have unit tests (`HudProtocolTest`, `OsmAndNotificationParserTest`, `SpeedLimitMatcherTest`); the BLE, service and UI code is only covered by manual testing.

## Setup
1. Switch the HUD on and pair it in Android's Bluetooth settings.
2. Open the app, tap *Connect HUD / start service* and grant the permissions.
3. Tap *Disable battery optimization* and grant notification access (Tools tab).
4. Start navigation in OsmAnd.

## Troubleshooting (installing the APK)
- **"App blocked to protect your device" (Google Play Protect):** tap *More details*, then *Install anyway*. Play Protect does not know the signing key of this open-source app yet.
- **Notification access is greyed out ("Restricted setting"):** Android 13+ blocks this for apps installed from a file or browser. Open *Settings → Apps → OsmAnd HUD Bridge*, tap the **⋮ menu** (top right), choose **Allow restricted settings**, confirm, then enable notification access. If the menu is missing, tap the greyed-out switch once and look again. Menu names vary by manufacturer.
- **No data from OsmAnd:** enable this app in OsmAnd under *Connected apps* (again after every reinstall under a new signature).

## Privacy
- Notifications from OsmAnd, WhatsApp and phone calls are read only to show them on the HUD. Nothing is stored or sent anywhere.
- For street names and speed limits the approximate position is sent to an Overpass server (OpenStreetMap). This can be switched off (Settings → Data sources).
- The log stays on the device. It may contain caller or sender names; check it before sharing.

## Protocol
See [PROTOCOL.md](PROTOCOL.md) for the Bluetooth protocol as far as it is known.

## Licenses
MIT for this project's own code (see [LICENSE](LICENSE)). Third-party parts and notes: [NOTICE.md](NOTICE.md). Map data © OpenStreetMap contributors (ODbL).

## Contributing
Issues and pull requests are welcome, especially: tests with other HUD firmware, other navigation apps, and **translations (English UI first)**.
