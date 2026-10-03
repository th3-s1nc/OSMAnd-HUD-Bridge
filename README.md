# OSMAnd HUD Bridge

[Deutsche Version](README.de.md)

An unofficial, private-use Android app that drives a Bluetooth LE head-up display (the *Tilsberk / DVision* HUD for motorcycle helmets) directly, fed by the navigation data of [OSMAnd](https://osmand.net). No vendor account, app or server needed.

> **Not affiliated** with Tilsberk, Digades or OSMAnd. All product names and trademarks belong to their owners and are used only to describe compatibility. **No warranty.** The display can fail or be wrong. Do not rely on it while riding; traffic signs and rules always take precedence. Use at your own risk.

## What it does
- **Free riding without navigation:** even without a destination the HUD shows the current speed limit and (in City mode) the street name, where OpenStreetMap has them. The original setup only shows this during active navigation.
- Shows speed, speed limit (from OpenStreetMap), turn arrow and distance, remaining distance / time / arrival, compass, incoming call / WhatsApp notices and, optionally, the current music track (artist, then title, 19 characters each, Explorer and City only) on the HUD.
- Four display modes (Navigator, Minimalist, Explorer, City), selectable in the app, remembered between runs. Street names (current and next street) are shown by the HUD in City mode only; calls, messages and music in Explorer and City only.
- Optional: a permanent straight-ahead arrow between turns, so you can see on long straights that navigation is running.
- On the Home tab a button next to the status opens OSMAnd directly.
- Runs as a foreground service, works with the screen off, reconnects automatically, shows nothing rather than something wrong.
- Without a connected HUD the app can still download road data. It then polls the GPS only rarely (power-saving mode); with the HUD it polls every second.
- **Season rider:** if you only ride from month to month, set your season. Outside the season the app rests; a few weeks before the season starts it loads the road data around you.
- The Home tab shows live what is being downloaded (surroundings and every open tour as "x of y"). The log shows the newest entry first, and a switch makes it more detailed.
- Navigation data comes from OSMAnd's AIDL API (arrow, distance, destination reached, intermediate point) and from OSMAnd's navigation notification (remaining distance/time, arrival, street, roundabout exit). If the API is not available (e.g. app not enabled in OSMAnd), arrow and distance fall back to the notification.
- Speed comes from the phone GPS, speed limits from OpenStreetMap via Overpass (online, also without navigation; map tiles are cached on the phone for 180 days and can be preloaded over Wi-Fi in a radius of up to 50 km, optionally in the background without the service, or along a GPX tour (Calimoto, Kurviger, Motobit); missing limits can optionally be estimated from town signs and neighbouring road sections).

## Requirements
- Android 8.0+ (API 26), Bluetooth LE, a paired HUD
- [OSMAnd](https://osmand.net) (Google Play, F-Droid "OSMAnd~" or the official APK). In OSMAnd, enable this app under *Connected apps* (menu name may vary by version).
- Notification access for this app (for OSMAnd, WhatsApp, calls and music tracks)

Tested by the author with one HUD on a Xiaomi 10T (LineageOS). Other devices, firmware versions and Android versions are untested.

**Language:** The app's user interface is currently **German only**. The HUD itself shows no app text, only symbols, numbers and street names. An English translation is planned and contributions are welcome (see Contributing).

**Using it next to the original app:** the HUD accepts only one Bluetooth connection at a time. Switch **HUD verbinden** (connect HUD, Home tab) off before you use the vendor app. The app then no longer uses Bluetooth to the HUD, but road data and preloading keep running (GPS only in power-saving mode). **App aktiv** (app active), on the other hand, switches almost everything off; only the optional background preload (Wi-Fi) keeps running if you switched it on.

## Build
Open the folder in Android Studio (JDK 17, Gradle 8.13 / AGP 8.9.2), or:

```
./gradlew assembleDebug
```

The protocol and parser logic, speed-limit matching, GPX import, GPS interval and season logic have unit tests (`HudProtocolTest`, `OsmAndNotificationParserTest`, `SpeedLimitMatcherTest`, `SpeedLimitLogicTest`, `GpxImportTest`, `GpsPolicyTest`, `SeasonPlanTest`); the BLE, service and UI code is only covered by manual testing.

## Setup
1. Switch the HUD on and pair it in Android's Bluetooth settings.
2. Open the app, switch on *HUD verbinden* (connect HUD) and grant the permissions.
3. In the Tools tab, tap the switches *Benachrichtigungszugriff* (notification access) and *Akku-Optimierung ausgeschaltet* (battery optimization off) and allow them in Android settings (the switches then show "on").
4. Start navigation in OSMAnd.

## Troubleshooting (installing the APK)
- **"App blocked to protect your device" (Google Play Protect):** tap *More details*, then *Install anyway*. Play Protect does not know the signing key of this open-source app yet.
- **Notification access is greyed out ("Restricted setting"):** Android 13+ blocks this for apps installed from a file or browser. Open *Settings → Apps → OSMAnd HUD Bridge*, tap the **⋮ menu** (top right), choose **Allow restricted settings**, confirm, then enable notification access. If the menu is missing, tap the greyed-out switch once and look again. Menu names vary by manufacturer.
- **No data from OSMAnd:** enable this app in OSMAnd under *Connected apps* (again after every reinstall under a new signature).

## Privacy
- Notifications from OSMAnd, WhatsApp and phone calls, and the music track (artist, title), are read only to show them on the HUD. Nothing is stored or sent anywhere.
- For street names and speed limits the approximate position is sent to an Overpass server (OpenStreetMap). This can be switched off (Speed limit tab → Data source).
- The log stays on the device. It may contain caller or sender names; check it before sharing.

## Protocol
See [PROTOCOL.md](PROTOCOL.md) for the Bluetooth protocol as far as it is known.

## Licenses
MIT for this project's own code (see [LICENSE](LICENSE)). Third-party parts and notes: [NOTICE.md](NOTICE.md). Map data © OpenStreetMap contributors (ODbL).

## Contributing
Issues and pull requests are welcome, especially: tests with other HUD firmware, other navigation apps, and **translations (English UI first)**.
