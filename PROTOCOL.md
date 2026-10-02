# HUD Bluetooth protocol (as far as known)

Interoperability notes for the Bluetooth LE head-up display sold as *Tilsberk / DVision*. The values were determined by observing the radio traffic and are checked against reference captures in `HudProtocolTest`. No vendor code or assets are part of this repository. Everything here is **unofficial and may be incomplete or wrong**; "unverified" marks what has not been seen working on a device.

Reference implementation: `protocol/HudProtocol.kt`, `protocol/NavCommand.kt`, `protocol/DisplayMode.kt`, `ble/HudClient.kt`.

## GATT

| Item | UUID |
|---|---|
| Service | `93C3D190-B994-4E6C-A32E-C73D4A2ED762` |
| Write characteristic | `93C3D191-…` (write, no more than 20 bytes per write) |
| Notify characteristics | `93C3D192-…` and `93C3D194-…` (enable both CCCDs before talking) |

## Transport

Each BLE write is a frame of at most 20 bytes:

```
byte 0: (transactionId << 4) | frameIndex          transactionId 0..15, frameIndex 0..15
byte 1: (frameType << 5) | payloadLength
```

Frame types: `FIRST 0`, `FIRST_ACK 1`, `CONT 2`, `SINGLE_ACK 3`, `LAST 4`, `LAST_ACK 5`, `CTRL 6`, `SINGLE 7`.

- A body of up to 18 bytes is sent as one `SINGLE` / `SINGLE_ACK` frame.
- Longer bodies (up to 16 frames): first frame `[tid<<4][type<<5 | 16][len lo][len hi]` + 16 bytes, then 18 bytes per `CONT`, the rest (at most 18 bytes) as `LAST` / `LAST_ACK`. The `_ACK` variants ask the HUD to confirm.
- The HUD confirms with a `CTRL` frame `[tid<<4][0xC1][status]` on a notify characteristic; status 0 = OK.
- Used behaviour: one message at a time, 400 ms timeout, resend with the same transaction id, reconnect after 5 failures in a row.

The body is a protobuf message (proto3 rules: default value 0 is omitted). Several fields may be concatenated into one body while it stays at most 18 bytes.

## Update message fields

| # | Meaning | Content |
|---|---|---|
| 1 | Speed | `{1: value, 2: unit (1 = km/h)}` |
| 2 | Compass | `{1: (degrees / 2) % 180}` |
| 3 | Speed limit | `{1: limit (≥252 = unknown), 2: speed ok (0/1), 4: camera (0 none, 1 fixed, 2 mobile)}` |
| 4 | Distance to next maneuver | distance (below) |
| 5 | Pointer (arrow) | `{1: arrow id, 2: roundabout exit}` (exit only for roundabout arrows); empty = clear |
| 8 | Clock | `{1: hour, 2: minute}` |
| 10 | Route duration | `{1: hours, 2: minutes}`; empty = clear |
| 11 | Remaining route distance | distance |
| 12 | Arrival time | `{1: hour, 2: minute}` |
| 15 | Next street | `{1: ASCII text, at most 40 characters}`; empty = clear |
| 17 | Current street | like 15 |
| 18 | Lane info | repeated `{1: direction bitmask, 2: recommendation}` |
| 21 | Call event | `{1: state, 2: {1: name, at most 19 ASCII}, 3: auto-hide seconds}` |
| 22 | SMS event | like 21 (the HUD does not display it, unverified for other firmware) |
| 100 | Command | see below |

**Distance** `{1: value, 2: unit}` with unit 2 = metres, 1 = kilometres. Below 1 km: metres, rounded down to 100 m (from 300 m), 50 m (from 100 m), else 10 m. From 1 km: whole kilometres (truncated). An empty message clears the field.

**Strings** are plain ASCII; umlauts are transliterated (ä → ae, ß → ss), other characters dropped.

## Arrow ids (field 5)

| id | arrow | id | arrow |
|---|---|---|---|
| 0 | none | 11 | goal, left (unverified) |
| 1 | straight | 12 | goal, right (unverified) |
| 2 | merge | 13 | sharp left (unverified) |
| 3 | turn left | 14 | sharp right (unverified) |
| 4 | turn right | 15 | via point |
| 5 | keep left | 16 | roundabout |
| 6 | keep right | 17 | roundabout, right |
| 7 | ramp left | 18 | roundabout, left |
| 8 | ramp right | 19 | roundabout finish |
| 9 | U-turn | 20 | U-turn left |
| 10 | goal (flag) | 21 | U-turn right |

## Lanes (field 18)

Direction bits: straight 1, slightly right 2, right 4, sharp right 8, U-turn left 16, sharp left 32, left 64, slightly left 128, merge right 256, merge left 512, merge lanes 1024, U-turn right 2048, second right 4096, second left 8192. Recommendation: 0 n/a, 1 not recommended, 2 highly recommended, 3 recommended.

## Commands (field 100)

| Sub-message | Meaning |
|---|---|
| `20: {1: 1}` | navigation finished / reset |
| `11: {1: 1}` | read configuration (HUD answers with its own message, send without ack) |
| `2: {1: screen, 2: packed hide list, 3: packed show list}` | show/hide display elements of a screen |
| `5: {1: screen}` | activate screen |
| `10: {1: {2: 2}}` | automatic brightness on |

Connect sequence used: navigation finished → read configuration → clock + show/hide for the chosen screen → activate screen → automatic brightness. After that only changed fields are sent.

## Screens and elements

Screens: City 10, Explorer 11, Minimalist 12, Navigator 13. Elements shown per mode are set with the show/hide command; the lists used by this project are in `DisplayMode.kt`. Elements 4 (remaining distance) and 5 (remaining time) are hidden while there is no route, otherwise the HUD shows "0 min". In City the "next street" and "current street" elements share one position, so only one of them is filled at a time.

Call/message events (fields 21/22) are only visible in Explorer and City; the call event also works for message notices when sent for a short time.

## Not known / unverified
- Whether arrows 11–14 (goal left/right, sharp left/right) are drawn.
- Behaviour with other firmware versions and other HUD models.
- Lane display for navigation apps (OsmAnd provides no lane data).
