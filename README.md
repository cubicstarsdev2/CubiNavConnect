# TahviaNav

**TahviaNav** is the Android companion application for the [TahVia](https://github.com/cubicstarsdev2/TahVia) ESP32 navigation display. It handles destination search, road routing, live GPS navigation and route progress on the phone, then synchronizes navigation data with a TahVia display over Bluetooth Low Energy (BLE). Navigation can also run phone-only without a connected display.

Built with Kotlin and Jetpack Compose.

## Features

- **Live map:** MapLibre map showing current position, route, destination, snapped position and intermediate waypoints.
- **Destination search:** Nominatim-compatible place search or direct coordinate entry.
- **Multi-stop trips:** Ordered intermediate waypoints and multi-leg routing.
- **Road routing:** OSRM directions, maneuver instructions, route duration and distance, with road-class exclusions when supported by the server.
- **Live navigation:** Fused Location Provider, route map matching, heading and progress tracking, off-route detection and automatic rerouting.
- **TahVia BLE integration:** Device discovery, pairing, protocol negotiation, position and maneuver updates, and route transfer.
- **Rolling route streaming:** Transfers a moving route window using device acknowledgements and gap recovery.
- **Background navigation:** Foreground service keeps location and BLE operation active while the app is backgrounded.
- **Phone-only mode:** Navigation without an external display.
- **GPX replay:** Replay recorded tracks through the navigation pipeline for testing.
- **Firmware updater:** Check a firmware version source, download the binary and transfer it over BLE.
- **Saved destinations:** Recent destinations and favorites.
- **Diagnostics:** Device status, protocol activity and navigation debugging.

## Architecture

TahviaNav is the navigation brain; TahVia is the external display.

1. The user chooses a destination and optional waypoints.
2. The app geocodes text input and requests a route from the configured OSRM server.
3. GPS fixes are matched against route geometry to calculate progress and the next maneuver.
4. The app sends position, heading, speed, maneuver information and route geometry to TahVia over BLE.
5. The ESP32 renders the navigation information on its TFT display.
6. As the user advances, the phone streams more route points and requests a replacement route when the user goes off course.

The phone retains the full route and navigation state. The display receives a compact local-coordinate route representation and current navigation state.

## Requirements

- Android 8.0 (API 26) or newer.
- Android Studio (Ladybug or newer, per the project setup notes).
- JDK 17 and Android SDK 35.
- A phone with Bluetooth Low Energy and location services for full navigation.
- A TahVia ESP32 device for external-display functionality (optional for phone-only mode).
- Network access for geocoding, routing, map tiles and firmware downloads.

## Build and run

### Android Studio

1. Clone the repository.
2. Open the TahviaNav directory in Android Studio.
3. Allow Gradle to sync and download the required SDK components.
4. Connect an Android phone.
5. Run the app configuration.

For actual BLE and GPS testing, use a physical Android device.

### Command line

Linux/macOS:

    git clone https://github.com/cubicstarsdev2/TahviaNav.git
    cd TahviaNav
    ./gradlew assembleDebug

Windows:

    git clone https://github.com/cubicstarsdev2/TahviaNav.git
    cd TahviaNav
    .\gradlew.bat assembleDebug

Run unit tests:

    ./gradlew test

The debug APK is produced under app/build/outputs/apk/debug/.

## First launch and pairing

1. Enable Android location services and Bluetooth.
2. Launch TahviaNav and grant location and nearby-device permissions.
3. Power on the TahVia display.
4. Open the **Device** tab and tap **Scan**.
5. Select the TahVia device from the discovered-device list.
6. Complete Android pairing using the passkey shown on the TahVia TFT.
7. The app negotiates the BLE protocol and synchronizes navigation state.

The app can remember the selected device and reconnect to it. The Device tab also provides reconnect and forget controls.

**Background operation:** Android may suspend BLE activity when the app is backgrounded. For reliable screen-off navigation, allow background location and foreground-service operation. Consider disabling battery optimization for TahviaNav.

## Routing and map configuration

Open the **Settings** tab to configure the services used by the app.

### Geocoding

The app uses a Nominatim-compatible endpoint for destination lookup. Searches are on-demand, cached in memory and rate-limited to approximately one request per second. The configured User-Agent is sent with requests.

Set a descriptive User-Agent containing a real contact method when using the public Nominatim service.

### OSRM routing

The app uses the OSRM Route service. It requests full route geometry encoded as polyline6, turn-by-turn steps and disables route annotations. The public OSRM demo server is suitable only for basic testing: it uses the car profile and does not provide the road-exclusion capabilities expected by the app.

For real use, configure an OSRM instance with appropriate regional data and profiles. The app can retrieve a capabilities.json document next to the configured OSRM endpoint. This advertises supported road classes, exclusion combinations and routing presets.

Example capabilities document:

    {
      "classes": ["motorway", "toll", "ferry", "unpaved"],
      "exclude": [["motorway"], ["toll"], ["motorway", "toll"]],
      "presets": [
        {
          "id": "main",
          "label": "Primary and secondary only",
          "url": "http://host:5001"
        }
      ]
    }

This is an example server configuration, not a bundled routing service. The server must actually support the advertised exclusions.

### Map style and tiles

The map is rendered with MapLibre Native. Configure a valid style URL and use a tile provider whose terms and usage limits suit your deployment. The built-in fallback uses OpenStreetMap raster tiles and includes attribution. Public demo tiles are not intended as an unlimited production service.

### Network security

The Android manifest currently enables cleartext HTTP to support self-hosted OSRM instances on a local network. Prefer HTTPS for services exposed outside a trusted network. If all configured endpoints use HTTPS, remove the cleartext-traffic allowance.

## Navigation behavior

The navigation controller coordinates location, routing, route matching, BLE updates and trip state. Its main phases are:

- **IDLE:** no active trip.
- **ROUTING:** a route is being calculated.
- **NAVIGATING:** following the active route.
- **REROUTING:** calculating a replacement route after leaving the current route.
- **ARRIVED:** destination reached.

The app uses Android Fused Location Provider. Position fixes are projected into a local decimeter coordinate frame for the TahVia protocol. The frame can be rebased during long trips to keep transmitted coordinates within the device's signed 32-bit range.

Route matching projects the current position onto route geometry to update progress and maneuver selection. When the phone detects that the user has moved off route, it can request a replacement route. Rerouting uses backoff to avoid repeatedly querying the routing server during persistent failures.

### Waypoints and GPX

Trips can contain intermediate stops. OSRM receives the ordered coordinates as route waypoints, and the app retains leg boundaries so an intermediate stop is not treated as the final destination.

GPX replay feeds recorded track points through the navigation controller. It is useful for exercising route matching, maneuver progression, rerouting and BLE behavior without physically driving the route.

## TahVia BLE protocol

The app implements **TahVia BLE protocol v1**, mirroring the firmware definitions in TahVia/src/protocol.h.

- Custom GATT service with RX writes and TX notifications.
- Requested ATT MTU: 247 bytes.
- Little-endian binary message encoding.
- Ordered BLE write queue and reconnect backoff.
- Protocol handshake before navigation synchronization.
- Route acknowledgements drive transfer flow control and gap recovery.

### Phone-to-device messages

| Message | ID | Purpose |
|---|---:|---|
| HELLO | 0x01 | Start a session and negotiate protocol version |
| REBASE | 0x03 | Shift the local route coordinate origin |
| POSE | 0x10 | Send position, heading, speed and turn distance |
| ROUTE_BEGIN | 0x20 | Start a route transfer |
| ROUTE_CHUNK | 0x21 | Send delta-encoded route points |
| ROUTE_COMMIT | 0x22 | Commit the transferred route |
| ROUTE_CLEAR | 0x23 | Clear the device route |
| MANEUVER | 0x30 | Send maneuver icon and street name |
| CONFIG | 0x40 | Send display configuration |
| NAV_STATE | 0x50 | Send navigation state |
| OTA_BEGIN | 0x60 | Start firmware transfer |
| OTA_CHUNK | 0x61 | Send firmware bytes |
| OTA_END | 0x62 | Finish and verify firmware |
| OTA_ABORT | 0x63 | Abort firmware transfer |

### Device-to-phone messages

| Message | ID | Purpose |
|---|---:|---|
| HELLO_ACK | 0x81 | Protocol/firmware version, display dimensions and route capacity |
| ROUTE_ACK | 0x82 | Route transfer status, expected point index and free slots |
| OTA_ACK | 0x83 | Firmware transfer status, offset and flow-control information |

All multi-byte protocol values are little-endian. Route coordinates use signed 32-bit decimeter values (east/north); route chunks encode point-to-point deltas as signed 16-bit pairs. Street labels are transliterated to ASCII and limited to 40 bytes to match the device font and protocol.

### Rolling route transfer

The phone does not need to send the entire route at once. RouteStreamer maintains a rolling window of approximately **2 km ahead** of the current route position.

- The device reports available route-buffer slots in ROUTE_ACK.
- The phone sends only as many points as fit in the available capacity and BLE chunk limit.
- A gap acknowledgement causes the phone to resume from the device's expected point index.
- The initial route is committed after at least 500 m has been uploaded, or when the route is complete.
- More points are streamed as the navigation cursor advances.

This keeps BLE traffic and device memory use bounded while supporting long routes.

## Firmware updates

The Device tab includes an OTA section. The updater checks the configured firmware README for the available version, downloads the firmware binary and transfers it to TahVia using the OTA protocol.

The update flow uses device acknowledgements and transfer-window information to track progress. The device performs image validation and flash operations; the phone reports the resulting update state.

Configure the firmware binary URL and README/version URL in Settings. Keep the phone and display powered during an update; do not disconnect Bluetooth or close the app while firmware is being transferred.

## Project structure

    TahviaNav/
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/
    │       ├── main/
    │       │   ├── AndroidManifest.xml
    │       │   ├── java/com/tahvia/nav/
    │       │   │   ├── MainActivity.kt       # App entry, permissions and Compose navigation
    │       │   │   ├── Screens.kt            # Device and settings screens
    │       │   │   ├── MapScreen.kt          # MapLibre map and destination UI
    │       │   │   ├── NavController.kt      # App-scoped navigation state machine
    │       │   │   ├── NavService.kt         # Foreground location/BLE service and notification
    │       │   │   ├── NavEngine.kt          # Route map matching
    │       │   │   ├── Route.kt              # OSRM parsing and maneuver mapping
    │       │   │   ├── RouteStreamer.kt      # Rolling route transfer and ACK flow control
    │       │   │   ├── Geo.kt                # Geodesic helpers and local coordinate projection
    │       │   │   ├── CoordParser.kt        # Coordinate input parsing
    │       │   │   ├── BleLink.kt            # BLE scan, pairing, connection and writes
    │       │   │   ├── Proto.kt              # Protocol v1 encoding and decoding
    │       │   │   ├── Net.kt                # HTTP, geocoding, OSRM and capabilities
    │       │   │   ├── Ota.kt                # Firmware update client
    │       │   │   ├── Prefs.kt              # Persistent settings and destinations
    │       │   │   └── Design+.kt            # Compose theme and UI components
    │       │   └── res/                      # Icons, notification layouts and resources
    │       └── test/                         # JVM unit tests
    ├── gradle/wrapper/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradlew
    └── gradlew.bat

## Main dependencies

| Dependency | Purpose |
|---|---|
| Kotlin / Android Gradle Plugin | Android application and language toolchain |
| Jetpack Compose + Material 3 | Declarative UI |
| MapLibre Native Android | Interactive map rendering |
| Google Play Services Location | Fused location updates |
| OkHttp | HTTP networking |
| Kotlin Coroutines | Asynchronous work and state coordination |
| JUnit 4 | Unit tests |

Dependency versions are maintained in app/build.gradle.kts and the root Gradle build files.

## Tests

Run the JVM unit test suite:

    ./gradlew test

The existing tests cover protocol byte encoding, coordinate parsing and geographic projection behavior. Hardware-dependent BLE, GPS, map rendering and OTA flows should additionally be tested on a physical device with a TahVia display.

## Related project

- [TahVia firmware](https://github.com/cubicstarsdev2/TahVia) - ESP32 firmware for the external navigation display.

## License

This project is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE) for the complete license text.
