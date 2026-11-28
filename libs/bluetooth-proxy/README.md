<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-bluetooth-proxy-on-dark.svg">
      <img alt="bluetooth-proxy, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-bluetooth-proxy-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">bluetooth-proxy</h1>

<p align="center">
  <b>An ESPHome Bluetooth proxy on Android.</b><br>
  Home Assistant hears the BLE sensors near the device, as through an ESP32 proxy.
</p>

<p align="center">
  <a href="../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-alpha-on-dark.svg">
    <img alt="Status: alpha" src="https://www.glassworks.dev/brand/badge-status-alpha-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

The Android side of an [ESPHome Bluetooth proxy](https://esphome.io/components/bluetooth_proxy/):
BLE scanning, batching, the scan schedule, and GATT connections. The protocol messages are in
[`esphome-api`](../esphome-api/README.md); this library plugs into its `ApiServer`. Home
Assistant's `esphome` and `bluetooth` integrations then take the device as a Bluetooth scanner,
with no setup in HA.

It has no Pane API and no S1 classes (#152). The Glassworks `bluetooth-proxy` pane wraps it with
settings and entities. Package `dev.glassworks.esphome.bluetoothproxy`, MIT licensed, minSdk 23.

It forwards advertisements, which covers most sensors (thermometers, plant sensors, presence tags).
With `BluetoothConnectionEngine` it also makes GATT connections for Home Assistant, 3 at a time, for
devices that need one (locks, SwitchBots, sensors with a key). On the S1 (#246), the pane ran on
2026-11-16 and a GATT connection to one real device worked on 2026-11-17: reads, a write, and the
link closed cleanly. More than one link at once, and a link kept open between polls, are untested.

## Use it in your own app

```kotlin
val server = ApiServer(ApiServer.DEFAULT_PORT, { identity }, entities, keys)
val proxy = BluetoothProxyEngine(server, AndroidBleScanner(context))
server.bluetooth = proxy // DeviceInfo now carries proxy flags, so HA adds a Bluetooth scanner

// optional: GATT connections, to the devices the scanner heard
val recent = RecentDevices() // pass it to AndroidBleScanner(context, recent) above
val links = BluetoothConnectionEngine(server, AndroidGattRadio(context, recent))
server.bluetoothConnections = links // HA now sees the scanner as connectable
server.start()

// later, e.g. on a settings page or for a diagnostic entity:
val s = proxy.status // subscribed, scanning, failure, advertisementsPerMinute

// to turn it off: close() takes it out of the server too. Clients see it gone once they reconnect.
links.close()
proxy.close()
```

Before you start it, check `AndroidBleScanner(context).unavailable()`: it says why a scan can't run
(no BLE, Bluetooth off, a missing permission, location off), or null. The app asks for the runtime
permissions in `bluetoothProxyPermissions` (scanning, and `BLUETOOTH_CONNECT` from Android 12); the
manifest entries come with the library.

## What's in it

| | |
|---|---|
| `BluetoothProxyEngine` | The proxy. Scans only while a client is subscribed to advertisements, batches up to 16 per message or for 200 ms (as ESPHome does on Wi-Fi), and reports a `ProxyStatus`: advertisements and different devices in the last full minute, the last hour's counts, and when the next try is after a failure. Runs on one thread of its own. |
| `ScanSchedule` (internal) | When to start, stop and restart the one scan. Pure Kotlin, time passed in. |
| `BleScanner`, `ScanPower`, `ScanFailure` | The scanner seam. Tests use a fake. |
| `AndroidBleScanner` | `BluetoothLeScanner`, with one empty `ScanFilter` (see below). `turnBluetoothOn()` for a "Turn on Bluetooth" button (apps may up to Android 12). With `RecentDevices`, it remembers the devices it heard. |
| `BluetoothConnectionEngine` | Home Assistant's GATT connections. A slot table of 3 (ESPHome's default). Each link asks for an MTU of 517 and finds its services before HA hears it is connected. One operation at a time per link, from a queue. Timeouts: 20 s to connect, 10 s per operation (then the link is dropped), 5 s to disconnect. A new subscriber closes every link. |
| `GattRadio`, `GattLink` | The connection seam. Tests use a fake. |
| `AndroidGattRadio`, `RecentDevices` | `connectGatt(…, TRANSPORT_LE)` on the device from the last scan result, which carries the address type Android 10 hides. Never pairs. Handles are Android's instance ids (see [Android's GATT rules](#androids-gatt-rules)). |

## Android's scan rules

Read from Android 10's Bluetooth service (`packages/apps/Bluetooth`, `android10-release`):

| Rule | Where | What the library does |
|---|---|---|
| A 6th scan start within 30 s fails (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`) | `GattService.registerScanner`, `AppScanStats` | One long-running scan. At most 4 starts per 30 s; more wait. Home Assistant leaving and coming back within 15 s keeps the same scan. |
| A scan without filters is suspended while the screen is off, and while location is off | `ScanManager.handleStartScan` | Passes one empty `ScanFilter`: it matches everything, but counts as filtered. |
| Results need location on and the location permission (FINE from Android 10) | `GattService.hasScanResultPermission` | `unavailable()` reports `LOCATION_OFF` or `NO_PERMISSION`. |
| A scan becomes opportunistic after 30 min unless its filters fit in the controller | `ScanManager.regularScanTimeout` | Restarts the scan every 25 min. |
| A background app's scan drops to `BLE_SCAN_BACKGROUND_MODE` (low power) | `ScanManager.handleImportanceChange` | Nothing: a kiosk app is in the foreground. |

`AndroidBleScanner` also watches the adapter while the engine uses it. Bluetooth turning off ends
the scan (`BLUETOOTH_OFF`), and turning back on retries at once instead of after the backoff.
Android can deliver a scan's failure or results late. A failure for a scan already stopped or
replaced is ignored, and so is a late result from a scan that was replaced or has failed: only the
running scan's advertisements are forwarded.

Android can't switch to a passive scan (no scan requests), so the proxy doesn't offer ESPHome's
scanner mode switch (`FEATURE_STATE_AND_MODE`).

## Android's GATT rules

Read from Android 10 (`android10-release`); the details and sources are in the S1's
[Bluetooth research](../../frames/aqara-s1-plus/research/bluetooth.md#gatt-connections-246).

| Rule | What the library does |
|---|---|
| Apps can't see a device's address type | Connects to the `BluetoothDevice` from a scan result. A device not heard recently is taken as a public address |
| One GATT operation at a time per link | A queue per link. An operation with no callback in 10 s drops the link: Android starts no other until it answers |
| A service's and a characteristic's instance id is its ATT handle | Sends them as handles, as ESP-IDF does |
| Below Android 7 an instance id isn't a handle: it only tells attributes with the same UUID apart, so it is 0 for nearly all of them (seen on the Mirror, Android 6.0.1: Home Assistant got one service with one characteristic) | Numbers the attributes itself, in the ATT table's order: a service, then each characteristic's declaration, value and descriptors. Unique and stable for the link, not the device's real handles |
| A descriptor's instance id is hidden | The ATT layout: a characteristic's descriptors are the attributes right after its value handle, in order. No private API |
| `BluetoothGatt.refresh()` is hidden | Not used, so no `CACHE_CLEARING` |
| A device that asks for encryption can make Android pair on its own | Nothing to stop it; the proxy never asks to pair |

## Privacy

Nothing here logs an address. `RecentDevices` keeps the last 256 devices heard, in memory only, for
connections. Addresses go only to the subscribed client, and only an authenticated
connection (Noise with the device's provisioned key) may subscribe: `BluetoothProxyHandler.authOnly`,
on by default. On a device without a key, nobody on the LAN can listen in plaintext.

## Compared with ESPHome

What ESPHome's [`bluetooth_proxy`](https://esphome.io/components/bluetooth_proxy/) does on an ESP32,
and what this library does on Android. Checked on 2026-11-15 against ESPHome **2026.10.2** (its
`bluetooth_proxy` component and `api.proto`). Numbers are api.proto message ids. The
library isn't on Maven Central yet, so "Since" says Unreleased. The messages themselves are in
esphome-api ([its table](../esphome-api/README.md#bluetooth-proxy)).

Status words:

- **Supported**: works today, since the version given.
- **Partial**: works, with the gaps named.
- **Planned**: we mean to do it; the issue is linked.
- **Not yet**: no decision either way.
- **Not planned**: we won't, for the reason given.

**Count for bluetooth-proxy** (against ESPHome 2026.10.2): 7 supported, 1 partial, 0 planned, 3 not yet, 3 not planned.

| Feature | ESPHome | bluetooth-proxy | Since | Notes |
|---|---|---|---|---|
| Forward advertisements to HA | `PASSIVE_SCAN` flag, subscription (66, 87) | Supported | Unreleased | Scans only while a client is subscribed; ESPHome's tracker scans all the time |
| Raw, batched advertisements | `RAW_ADVERTISEMENTS`, `BluetoothLERawAdvertisementsResponse` (93) | Supported | Unreleased | 16 per message or every 200 ms, as ESPHome on Wi-Fi. Data up to 62 bytes, as in ESPHome |
| Scan responses | `esp32_ble_tracker` `active: true` | Supported | Unreleased | Android always scans actively and hands over the scan response with the advertisement |
| Scan timing | `esp32_ble_tracker` `scan_parameters` (interval, window, duration) | Partial: three Android scan modes | Unreleased | `ScanPower`; Android takes no exact interval or window. The engine also restarts the scan every 25 min (Android's 30-minute rule) |
| Passive scan and mode switching | `STATE_AND_MODE`, `BluetoothScannerStateResponse`, `BluetoothScannerSetModeRequest` (126, 127) | Not planned: Android apps can't stop scan requests | | One ESPHome flag covers both the state report and the switch |
| Old-style advertisements | `BluetoothLEAdvertisementResponse` (67) | Not planned: raw advertisements replaced them, and HA asks for raw | | |
| Active connections | `ACTIVE_CONNECTIONS`, `BluetoothDeviceRequest` connect and disconnect (68, 69) | Supported | Unreleased | `BluetoothConnectionEngine`. One real device and one link verified on the S1 (2026-11-17, [#246](https://github.com/GlassworksHQ/glassworks/issues/246)); more links at once untested |
| Connection slots | `SubscribeBluetoothConnectionsFreeRequest`, `BluetoothConnectionsFreeResponse` (80, 81) | Supported | Unreleased | 3, like ESPHome's default. The RTL8821CS's own limit isn't known yet |
| GATT services, read, write, descriptors, notify | 70–79, 82–84 | Supported | Unreleased | One operation at a time per link. 128-bit UUIDs only |
| Remote caching | `REMOTE_CACHING`, connect with or without cache | Supported | Unreleased | HA keeps the services. A connect "with cache" still finds them, from Android's own cache, because Android needs its objects for every operation |
| Pairing and bonding | `PAIRING`, pair and unpair (85, 86) | Not yet | | [#246](https://github.com/GlassworksHQ/glassworks/issues/246) leaves it out at first: a PIN raises Android's dialog over the app |
| Clearing the GATT cache | `CACHE_CLEARING` (88) | Not yet | | [#246](https://github.com/GlassworksHQ/glassworks/issues/246): only if the hidden `BluetoothGatt.refresh()` works |
| Connection parameters | `CONNECTION_PARAMS_SETTING` (145, 146) | Not yet | | Android offers three connection priorities, not exact parameters |
| The adapter's own MAC address | `bluetooth_mac_address` in DeviceInfo | Not planned: Android hides it from apps | | An app that can read it sets `ApiServer.bluetoothMacAddress` (the S1, through its root helper, #246). Without it, HA keys the scanner on the device MAC |

`tools/check-parity-tables.py` checks the status words and the count.

## Tests

`./gradlew :bluetooth-proxy:testDebugUnitTest`. `AndroidBleScannerTest` runs the Android glue on
Android 10 under Robolectric: the empty filter, the failure codes, a late failure, Bluetooth off and
on. `AndroidGattRadioTest` checks the handles and the device a connection uses.
`BluetoothConnectionEngineTest` drives the slot table, the queue and every timeout on a manual clock.
`BluetoothProxyHomeAssistantTest` runs Home Assistant's client (aioesphomeapi, the version HA
pins) against `ApiServer` and the engines with fake radios: the advertisements, and a whole GATT
connection (esphome-api's `esphome_bluetooth_connections.py`). It skips unless `ESPHOME_PYTHON` points
at a Python with it installed (`../esphome-api/test-requirements.txt`).
