<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-esphome-api-on-dark.svg">
      <img alt="esphome-api, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-esphome-api-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">esphome-api</h1>

<p align="center">
  <b>The device side of the ESPHome native API, in Kotlin.</b><br>
  Home Assistant adds your JVM or Android app as an ESPHome device.
</p>

<p align="center">
  <a href="../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-beta-on-dark.svg">
    <img alt="Status: beta" src="https://www.glassworks.dev/brand/badge-status-beta-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

The **device side** of the [ESPHome native API](https://esphome.io/components/api.html) in plain
Kotlin. Run it in any JVM or Android app and Home Assistant's ESPHome integration adds that app as
an ESPHome device, with entities, encryption and the voice assistant. Most libraries implement the
*client* side (talking to ESPHome devices). This one implements the device.

It was extracted from [Glassworks for the Aqara S1 Plus](../../frames/aqara-s1-plus/android/README.md), where it runs the Aqara S1 Plus panel's
ESPHome device. Package `dev.glassworks.esphome`, MIT licensed.

## What it does

- **Server:** TCP on port 6053. Each connection has a reader thread, and a writer thread with its
  own queue, so a client that stops reading holds up only itself. Its states wait as the newest
  value per entity. Once 512 KiB of its other messages wait in the queue, the server drops it. The
  batch the writer is sending doesn't count, so a stuck client can hold about twice that. It handles
  Hello, DeviceInfo, DeviceCapabilities, ListEntities, SubscribeStates, commands, Ping and Disconnect,
  and ignores unknown messages, as ESPHome does. Keepalive follows ESPHome's `api_connection.cpp`.
- **Encryption:** Noise `NNpsk0_25519_ChaChaPoly_SHA256`, and Home Assistant can provision the key
  for you. With no key, the device accepts plaintext and zero-PSK clients and lets HA set a key
  (`NoiseEncryptionSetKeyRequest`). Once a key is active, it accepts only Noise with that key and
  drops older sessions. This matches an ESPHome build with `api: encryption:` and no YAML key. You
  can also pass a fixed key, or `null` for plaintext only.
- **Entities:** binary sensor, sensor, text sensor, switch, number, select, text, button, event,
  update, light (brightness) and media player. You can also subclass `ApiEntity`. Any entity can be
  marked `authOnly`, so commands are accepted only over Noise with the provisioned key.
- **Actions:** user-defined actions that Home Assistant calls as `esphome.<node>_<action>`, with
  bool, int, float and string arguments and their lists. An action can return JSON to the caller
  or fail with a message.
- **Events on Home Assistant's bus:** `sendHomeAssistantEvent(HomeAssistantEvent("esphome.<name>", data))`
  fires an event, as ESPHome's `homeassistant.event` does. HA allows only the `esphome.` domain and
  needs no option for it. Use it when an event entity can't: HA refuses event types an event entity
  didn't list up front.
- **Camera:** a camera entity that serves JPEG frames, one at a time or as the stream HA's
  more-info dialog shows. Images go out in chunks, so states and pings keep flowing meanwhile.
- **Voice assistant:** the messages are wired through `VoiceAssistantHandler`: start and stop, API
  audio, events, timers, announcements and wake word configuration. The audio, wake word and UI
  stay in your app.
- **Bluetooth proxy:** the feature flags in DeviceInfo and DeviceCapabilities, the
  advertisement subscription (one client at a time; a newer one replaces it, as in ESPHome) and
  batched raw advertisements, through `BluetoothProxyHandler` and `sendBluetoothAdvertisements`.
  Active connections (GATT) go through `BluetoothConnectionHandler`: requests arrive as `GattRequest`,
  and you answer with `sendBluetoothEvent(GattEvent…)`. Threads: the handler's calls come on the
  requesting client's reader thread, under the server's Bluetooth lock. Queue the work and return;
  never wait inside `onRequest` for a thread that answers, or it deadlocks. The Android side of both is
  [bluetooth-proxy](../bluetooth-proxy/README.md).
- **Sendspin pairing-token hand-off:** for HA → Music Assistant, through `SendspinTokenSource`.
  Leave it unset if you don't have a Sendspin player.
- **Noise cipher:** `NoiseCipher`, Noise's ChaChaPoly cipher state, is reusable on its own. S1
  Kiosk's Sendspin client uses it.

Checked against the real `aioesphomeapi` client, the library Home Assistant uses (see
[Tests](#tests)).

## Use

```kotlin
var lampOn = false
val identity = DeviceIdentity(
    name = "my-device", friendlyName = "My Device", macAddress = "02:00:00:00:00:01",
    esphomeVersion = "2026.10.2", compilationTime = "2026-11-10 12:00:00",
    manufacturer = "Me", model = "Kotlin device", voiceFeatures = 0,
)
val lamp = SwitchEntity("lamp", "Lamp", icon = "mdi:lamp", get = { lampOn }, set = { lampOn = it })
val uptime = SensorEntity("uptime", "Uptime", unit = "s", deviceClass = "duration",
    category = EntityCategory.DIAGNOSTIC, get = { 42f })
val keys = NoiseKeys(MemoryKeyStore()) // persist the key in real code: HA provisions it
val server = ApiServer(port = ApiServer.DEFAULT_PORT, identity = { identity },
    entities = listOf(lamp, uptime), keys = keys)
server.start()
// Later, when something changes outside a command: server.publishStates()
```

`ReadmeExampleTest` keeps this example compiling and working.

- **Persist the key.** Implement `NoiseKeyStore` over your own storage (Glassworks uses
  SharedPreferences). `save` must be synchronous and return false when the key wasn't stored, and the
  key must never be logged. If your storage can hold a key it can't read for a while (a key store
  that is down), implement `ReadableNoiseKeyStore` and return `StoredKey.Unreadable` from `read()`
  then: the server locks instead of falling back to plaintext.
- **Threads.** Entity getters and setters run on connection threads, so make them thread-safe.
- **Changing entities at runtime.** `server.setEntities(...)`.
- **Categories and defaults.** The entity types take `category` (`CONFIG`, `DIAGNOSTIC`) and
  `disabledByDefault`, all but the light and the media player (those two always send it as `false`). HA reads `disabledByDefault` only
  when it first sees an entity, so pick it when you add the entity. The category follows the
  device on every connection.
- **A web page for the device.** Set `DeviceIdentity(webserverPort = 8080)` and Home Assistant links
  the device page's "Visit" to `http://<device>:8080`. The default, 0, sends nothing.
- **Discovery.** HA finds devices over mDNS `_esphomelib._tcp`. On Android, use `EspHomeMdns` from
  [esphome-api-android](../esphome-api-android/README.md). On other platforms, advertise it
  yourself (Avahi, JmDNS…) with the TXT keys `friendly_name`, `version`, `mac` (`identity.macHex`)
  and `network`, plus `api_encryption` (once encrypted) or `api_encryption_supported` and
  `api_provisioning=zero-psk` (while provisionable). You can also add the device in HA by IP
  address.
- **Actions and cameras.** See [below](#actions-and-cameras).
- **Logging.** By default it goes to `android.util.Log` on Android (found by reflection) and to
  stdout elsewhere. Set `EspHomeLog.tag`, or replace `EspHomeLog.sink` to route it into your own
  logger.

## Actions and cameras

```kotlin
val notify = ApiAction(
    "notify",
    args = listOf(ActionArg("title", ActionArgType.STRING), ActionArg("seconds", ActionArgType.INT)),
    supportsResponse = ActionResponse.STATUS,
) { call ->
    showBanner(call.string("title"), call.int("seconds"))
    null // a JSON object instead, for ActionResponse.OPTIONAL or ONLY
}
val porch = CameraEntity("porch", "Porch", maxFps = 5f) { latestJpeg() }
server.setEntities(listOf(lamp, uptime, notify, porch))
```

`ReadmeExampleTest` keeps this one working too. Home Assistant then has an action
`esphome.my_device_notify` with the fields `title` and `seconds` (every field is required), and a
camera entity `camera.my_device_porch`.

- **Action handlers** run on the server's action thread, one call at a time and in order, so a slow
  one never holds up the connection. A handler returns JSON as text, or null. To fail the call,
  throw: HA shows the exception's message. With `ActionResponse.NONE` (the default) HA doesn't wait
  for the result at all.
- **The camera's frame function** runs only while a client wants images. A single request takes one
  frame. A stream takes frames at up to `maxFps` until 5 s pass without another stream request, as
  ESPHome does. HA's more-info dialog asks again after every frame. Each client has its own camera
  thread, and the frame function is called when that thread is ready to send, so a slow client gets
  fewer frames, not a queue. Return null when there is no image: HA then reports it at once instead
  of timing out. Images go only to clients that have subscribed to states, as in ESPHome.
- **Unasked, a client gets one empty image per camera** right after it subscribes to states. Home
  Assistant shows an ESPHome camera as unavailable after a reconnect until an image arrives for it,
  and an ESPHome camera gets there with its idle frames. The empty image has no bytes and doesn't
  call the frame function, so nothing from the camera leaves the device unless a client asks.
- **`authOnly`** works for both: the action runs, or the camera sends images, only over Noise with
  the provisioned key.
- **Not in yet:** the camera's idle image every few seconds (ESPHome's `idle_update_interval`), and
  argument descriptions (HA doesn't show them).

## Compared with ESPHome

What a real ESPHome device does over the native API, and what this library does. Checked on
2026-11-15 against ESPHome **2026.10.2** (released 2026-11-02), its
[`api.proto`](https://github.com/esphome/esphome/blob/2026.10.2/esphome/components/api/api.proto) and
`api_connection.cpp`, aioesphomeapi v46.30.0 and Home Assistant's `esphome` integration (`dev` on the
same day). Numbers are api.proto message ids: in the entity table, the list, state and command
messages. Rows marked "android" come from
[esphome-api-android](../esphome-api-android/README.md); the rest from this library. For an entity
type the library doesn't ship, you can subclass `ApiEntity` and write its messages yourself.

Status words:

- **Supported**: works today, since the version given.
- **Partial**: works, with the gaps named.
- **Planned**: we mean to do it; the issue is linked.
- **Not yet**: no decision either way.
- **Not planned**: we won't, for the reason given.

**Count for esphome-api** (against ESPHome 2026.10.2): 31 supported, 9 partial, 0 planned, 16 not yet, 15 not planned.

`tools/check-parity-tables.py` checks these words and the count. `ParityTableTest` checks that the
entity types marked Supported or Partial are exactly the ones the server lists.

### Connection and encryption

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| Plaintext connection | Without `api: encryption:` | Supported | 0.1.0 | Only while no key is set, or always with no `NoiseKeys` |
| Noise encryption | `Noise_NNpsk0_25519_ChaChaPoly_SHA256` | Supported | 0.1.0 | Once a key is active, only Noise with that key gets in |
| Key provisioning from HA | `NoiseEncryptionSetKeyRequest` (124, 125), zero-PSK handshake | Supported | 0.1.0 | Sends `api_encryption_provisionable`; persists the key through your `NoiseKeyStore` |
| Provisioning window | `provisioning:` timeout, then `DisconnectRequest` with `PROVISIONING_CLOSED` | Not yet | | The device stays provisionable until HA sets a key |
| Hello | `HelloRequest`, `HelloResponse` (1, 2) | Supported | 0.1.0 | Reports API 1.20, like ESPHome's `dev` branch; 2026.10.2 reports 1.19 |
| Password authentication | `AuthenticationRequest` (3, 4) | Not planned: removed from ESPHome in 2026.1 | | Ignored, as ESPHome does now |
| Ping and keepalive | `PingRequest`, `PingResponse` (7, 8), both ways | Supported | 0.1.0 | Keepalive timing follows `api_connection.cpp` |
| Disconnect | `DisconnectRequest`, `DisconnectResponse` (5, 6), both ways | Supported | 0.1.0 | |
| Outgoing connection (the device dials HA) | `HelloRequest.outgoing_connection_target`, `api_outgoing_connection_supported` | Not yet | | |

### Device info and discovery

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| Device identity | `DeviceInfoResponse` (10): name, MAC, version, compilation time, model, manufacturer, friendly name | Supported | 0.1.0 | From `DeviceIdentity` |
| Web page link | `webserver_port` | Supported | 0.1.0 | `DeviceIdentity(webserverPort)` |
| Project name and version | `project_name`, `project_version`, and the same mDNS TXT records | Not yet | | |
| Sub-devices and areas | `devices`, `areas`, `area`, `suggested_area`, and `device_id` on every entity | Not yet | | Every entity belongs to the one device |
| Deep sleep flag | `has_deep_sleep` | Not planned: a microcontroller power mode | | |
| Device capabilities | `DeviceCapabilitiesResponse` (149, 150) | Supported | Unreleased | The voice, Sendspin and Bluetooth proxy blocks. 0.1.1 and older leave out the proxy's flags, which HA still reads from DeviceInfo ([#337](https://github.com/GlassworksHQ/glassworks/issues/337)). The proxy's MAC address is its own row under [Bluetooth proxy](#bluetooth-proxy) |
| Time from Home Assistant | `GetTimeRequest`, `GetTimeResponse` (36, 37) | Partial: the time zone only | 0.1.0 | `onTimeZone` gets the `timezone` string. ESPHome deprecated it in 2026.9 for `parsed_timezone`. After 0.1.1, an answer with only `parsed_timezone` gives the same POSIX rule with numeric names (`<+01>-1<+02>-2,M3.5.0/2,M10.5.0/3`). The epoch is ignored: Android keeps its own clock |
| mDNS discovery | `_esphomelib._tcp`, TXT `friendly_name`, `version`, `mac`, `network`, `board`, `api_encryption`, `api_encryption_supported`, `api_provisioning` | Supported (android) | esphome-api-android 0.1.0 | `EspHomeMdns`. Without Android, advertise it yourself ([Use](#use)) |
| Firmware mDNS records | TXT `platform`, `config_hash`, `ota_signed`, `package_import_url` | Not planned: they describe an ESPHome firmware build (chip, YAML hash, signed OTA, dashboard import) | | |

### Entities

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| Binary sensor (`binary_sensor`) | 12, 21 | Supported | 0.1.0 | No `is_status_binary_sensor`, which only ESPHome's own `status` sensor sets |
| Sensor (`sensor`) | 16, 25 | Partial: no `force_update` | 0.1.0 | |
| Text sensor (`text_sensor`) | 18, 27 | Partial: no `device_class` (`timestamp`, `date`) | 0.1.0 | |
| Switch (`switch`) | 17, 26, 33 | Partial: no `assumed_state` | 0.1.0 | The state is always known, so `missing_state` is never sent |
| Button (`button`) | 61, 62 | Supported | 0.1.0 | |
| Number (`number`) | 49–51 | Supported | 0.1.0 | |
| Select (`select`) | 52–54 | Supported | 0.1.0 | |
| Text (`text`) | 97–99 | Supported | 0.1.0 | |
| Light (`light`) | 15, 24, 32 | Partial: on, off and brightness only | 0.1.0 | `BrightnessLightEntity`. No color modes, effects, transition or flash |
| Media player (`media_player`) | 63–65 | Partial: no category or disabled-by-default | 0.1.0 | Supported formats carry the format and purpose, not the sample rate, channels or sample size |
| Camera (`camera`) | 43–45 | Partial: no idle image | 0.1.0 | Single images and streams work. ESPHome also sends a frame every `idle_update_interval`; this sends one empty image on subscribe instead, which keeps the entity available |
| Event (`event`) | 107, 108 | Supported | 0.1.0 | |
| Update (`update`) | 116–118 | Supported | 0.1.0 | Install and check commands go to your callback |
| Date (`date`) | 100–102 | Not yet | | |
| Time (`time`) | 103–105 | Not yet | | |
| Date and time (`datetime`) | 112–114 | Not yet | | |
| Siren (`siren`) | 55–57 | Not yet | | A device with a speaker could be one |
| Alarm control panel (`alarm_control_panel`) | 94–96 | Not planned: HA's alarm panel card does the job ([#126](https://github.com/GlassworksHQ/glassworks/issues/126)) | | |
| Lock (`lock`) | 58–60 | Not planned: drives hardware a phone or panel doesn't have | | An app that bridges a real lock can subclass `ApiEntity` |
| Cover (`cover`) | 13, 22, 30 | Not planned: drives motor hardware | | As for locks |
| Fan (`fan`) | 14, 23, 31 | Not planned: drives fan hardware | | As for locks |
| Valve (`valve`) | 109–111 | Not planned: drives valve hardware | | As for locks |
| Climate (`climate`) | 46–48 | Not planned: drives HVAC hardware, and thermostat logic belongs in HA | | As for locks |
| Water heater (`water_heater`) | 132–134 | Not planned: drives heater hardware | | As for locks |
| Infrared and RF (`infrared`, `radio_frequency`) | 135–137, 148, 153 | Not planned: needs an IR or sub-GHz transceiver, which few Android devices have | | |

### Actions and Home Assistant

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| User-defined actions | `ListEntitiesServicesResponse`, `ExecuteServiceRequest` (41, 42) | Partial: no descriptions | 0.1.0 | All eight argument types. The action's and arguments' `description` and `example` aren't sent |
| Action responses | `ExecuteServiceResponse` (131) | Supported | 0.1.0 | Status, error message and JSON data |
| Events on HA's bus | `HomeassistantActionRequest` (35) with `is_event` | Supported | 0.1.0 | `sendHomeAssistantEvent` |
| Calling HA actions from the device | `HomeassistantActionRequest` (35), `homeassistant.action` | Not yet | | Events only. HA needs "Allow the device to perform Home Assistant actions" for these |
| HA action responses | `HomeassistantActionResponse` (130) | Not yet | | |
| HA state subscription | `SubscribeHomeAssistantStatesRequest` and its responses (38–40) | Not yet | | ESPHome's `homeassistant` sensors |
| Logs to HA | `SubscribeLogsRequest`, `SubscribeLogsResponse` (28, 29) | Not yet | | Logs go to `EspHomeLog.sink` |

### Voice assistant

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| Subscription | `SubscribeVoiceAssistantRequest` (89) | Supported | 0.1.0 | `VoiceAssistantHandler.onSubscribe` |
| Start and stop a pipeline | `VoiceAssistantRequest`, `VoiceAssistantResponse` (90, 91) | Supported | 0.1.0 | The UDP port reaches `onStartResponse`; a UDP stream is the app's to run |
| Pipeline events | `VoiceAssistantEventResponse` (92) | Supported | 0.1.0 | |
| Audio over the API | `VoiceAssistantAudio` (106), both ways | Partial: microphone to HA only | 0.1.0 | Audio HA streams to a `SPEAKER` device is dropped; play the TTS URL from the events instead |
| Timers | `VoiceAssistantTimerEventResponse` (115) | Supported | 0.1.0 | |
| Announcements | `VoiceAssistantAnnounceRequest`, `VoiceAssistantAnnounceFinished` (119, 120) | Supported | 0.1.0 | Including start-a-conversation |
| Wake word configuration | `VoiceAssistantConfigurationRequest`, its response, `VoiceAssistantSetConfiguration` (121–123) | Supported | 0.1.0 | |

### Bluetooth proxy

The messages are here; the Android scanner and its own comparison are in
[bluetooth-proxy](../bluetooth-proxy/README.md#compared-with-esphome).

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| Advertisements | `SubscribeBluetoothLEAdvertisementsRequest`, `UnsubscribeBluetoothLEAdvertisementsRequest`, `BluetoothLERawAdvertisementsResponse` (66, 87, 93) | Supported | 0.1.0 | One subscriber at a time, as in ESPHome; `authOnly` by default |
| Old-style advertisements | `BluetoothLEAdvertisementResponse` (67) | Not planned: replaced by raw advertisements, which HA asks for | | |
| Connect and disconnect | `BluetoothDeviceRequest` connect v3 and disconnect, `BluetoothDeviceConnectionResponse` (68, 69) | Supported | Unreleased | `BluetoothConnectionHandler`. Sends `ACTIVE_CONNECTIONS` and `REMOTE_CACHING`, without which aioesphomeapi won't connect. Only the advertisement subscriber may ask, and answers go to it, as in ESPHome. A v1 connect is answered "not connected" ([#246](https://github.com/GlassworksHQ/glassworks/issues/246)) |
| GATT services, reads, writes and notifications | `BluetoothGATT*` (70–79, 82–84) | Supported | Unreleased | `GattRequest` in, `GattEvent` out. Services go out with 128-bit UUIDs, never `short_uuid` |
| Free connection slots | `SubscribeBluetoothConnectionsFreeRequest`, `BluetoothConnectionsFreeResponse` (80, 81) | Supported | Unreleased | The handler keeps the table; the subscription follows `authOnly` |
| Pairing | `BluetoothDeviceRequest` pair and unpair, their responses (85, 86) | Not yet | | Answered "failed" at once. On Android a PIN would raise the system's dialog over a kiosk |
| Clearing the GATT cache | `BluetoothDeviceRequest` clear cache, `BluetoothDeviceClearCacheResponse` (88) | Not yet | | Answered "failed" at once |
| Connection parameters | `BluetoothSetConnectionParamsRequest`, its response (145, 146) | Not yet | | |
| Scanner state and mode | `BluetoothScannerStateResponse`, `BluetoothScannerSetModeRequest` (126, 127) | Not planned: Android apps can't switch to a passive scan | | |
| Bluetooth MAC address | `bluetooth_mac_address` in DeviceInfo (18) and the capabilities' `mac_address` | Supported | Unreleased | `ApiServer.bluetoothMacAddress`, sent with the proxy only. Unset, HA keys the scanner on the device MAC, as before |

### Other proxies and newer messages

| Feature | ESPHome | esphome-api | Since | Notes |
|---|---|---|---|---|
| Sendspin pairing token | `SendspinPairingTokenRequest`, its response (159, 160) | Supported | 0.1.0 | Only over Noise with the provisioned key |
| Onboarding wizard | `DeviceWizardRequest`, its response, `WizardInputSetRequest` (156–158) | Not yet | | |
| Z-Wave proxy | `ZWaveProxyFrame`, `ZWaveProxyRequest` (128, 129, 151) | Not planned: a Z-Wave radio on the microcontroller's UART | | |
| Serial proxy | `SerialProxy*` (138–147, 152, 154, 155) | Not planned: the microcontroller's UART pins; Android rarely gives apps a UART | | |

ESPHome's `dev` branch adds `GetYamlRequest` (161, 162) and `CrashReportRequest` (163, 164) after
2026.10.2. Both are about firmware (the YAML the device was built from, a crash dump), so they are
not planned. They aren't counted above.

## Gradle

On Maven Central as `dev.glassworks:esphome-api` ([changelog](CHANGELOG.md)).

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("dev.glassworks:esphome-api:0.2.0")
}
```

JVM 17 bytecode, so a Java build needs Java 17. A Kotlin build needs Kotlin 2.0 or newer (the library is
built for Kotlin 2.1 and its kotlin-stdlib). Dependency: BouncyCastle `bcprov-jdk18on` (X25519 and
ChaCha20-Poly1305 through the lightweight API, not the JCA provider).

On Android it runs from API 23 (Android 6). `./gradlew :esphome-api:test` runs Expediter first, which
checks the library's classes, and the BouncyCastle classes they reach, against API 23.

## Tests

```sh
./gradlew :esphome-api:test
```

`EspHomeKeepaliveTest`, `EspHomeActionsCameraTest`, `EspHomeHaEventTest`, `EspHomeCapsTimeTest` and
`EspHomeBluetoothConnectionsTest` run the real `aioesphomeapi` client when
`ESPHOME_PYTHON` points at a Python with it installed. They are skipped otherwise. PR CI installs it from
[`test-requirements.txt`](test-requirements.txt) (locked with hashes) and fails if one of them skipped:

```sh
python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
ESPHOME_PYTHON=/tmp/esphome-venv/bin/python ./gradlew :esphome-api:test --rerun
```

The app's `EspHomeApiTest` (`:kiosk:testDebugUnitTest`) covers the rest the same way: every entity
type, voice, announcements, timers, and Noise with the right, wrong and missing keys.
