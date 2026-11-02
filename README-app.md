# TahviaNav (Android)

Kotlin + Jetpack Compose phone app for the TahVia ESP32 display. Implements the README: destination entry
(coordinates or Nominatim), MapLibre map, OSRM routing with road-type filters, BLE protocol v1,
map matching, rerouting, route streaming, foreground service, GPX replay and a debug screen.

## Build
1. Open the `TahviaNav` folder in Android Studio (Ladybug or newer). It creates the Gradle wrapper and
   downloads the SDK pieces. JDK 17.
2. Run on a real phone (BLE + GPS). Android 8.0+ (minSdk 26).
3. Pair: Device tab -> Scan -> tap TahVia -> enter the passkey shown on the TFT.

## Before real use
- Settings -> OSRM URL: the default public demo server only supports the car profile and no road filters.
  Self-host OSRM and serve `capabilities.json` next to it:
  `{"classes":["motorway","toll","ferry","unpaved"],"exclude":[["motorway"],["toll"],["motorway","toll"]],"presets":[{"id":"main","label":"Primary and secondary only","url":"http://host:5001"}]}`
- Settings -> Map style URL: the default is MapLibre's demo style. Use a real tile provider.
- Settings -> User-Agent: put a real contact in it (Nominatim policy).
- Disable battery optimization for the app, or Android may drop BLE with the screen off.
- `usesCleartextTraffic` is on so a LAN OSRM over http works. Remove it if you serve https.

## Layout
| File | Role |
|---|---|
| `Proto.kt` | Message encode/decode (mirrors `protocol.h`) |
| `Geo.kt` | Haversine, `Projector` (dm frame, REBASE-safe), polyline6 decode, Douglas-Peucker |
| `Route.kt` | OSRM response -> simplified `Route`, steps, icon mapping, ASCII street names |
| `Net.kt` | OkHttp, Nominatim geocoder (1 req/s, cached), OSRM router, capabilities |
| `NavEngine.kt` | Map matching on the polyline |
| `RouteStreamer.kt` | 2 km rolling window, ACK flow control, gap resend, commit |
| `BleLink.kt` | Scan, bonding, MTU 247, notify, ordered write queue, reconnect backoff |
| `NavController.kt` | Location, nav state machine, POSE, off-route/reroute, GPX replay, state replay on reconnect |
| `NavService.kt` | Foreground service (location + connectedDevice) |
| `MapScreen.kt`, `Screens.kt`, `MainActivity.kt` | UI |

## Tests
`./gradlew test` runs golden-byte protocol tests, the coordinate parser and projector tests.
