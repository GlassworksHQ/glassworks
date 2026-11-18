# Changelog: esphome-api

What changed in each release of `dev.glassworks:esphome-api` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/esphome-api/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [Unreleased]

### Added
- **Active Bluetooth connections (GATT).** Set `ApiServer.bluetoothConnections` to a
  `BluetoothConnectionHandler` next to `ApiServer.bluetooth`, and Home Assistant can connect to BLE
  devices through the proxy: connect and disconnect, services, reads, writes, descriptors,
  notifications and the free-slot count. Requests arrive as `GattRequest`; answer with
  `sendBluetoothEvent(GattEvent…)`; `removeBluetoothConnections` takes it out again. The flags then add `ACTIVE_CONNECTIONS` and `REMOTE_CACHING`
  (aioesphomeapi 46.25.0 connects only through a proxy with both). Only the client that holds the
  advertisement subscription may ask, so requests follow `BluetoothProxyHandler.authOnly`. Pairing
  and cache clearing are answered "failed" at once (#246).
- **The Bluetooth adapter's own MAC.** Set `ApiServer.bluetoothMacAddress` and the proxy sends it as
  `bluetooth_mac_address` (DeviceInfo field 18) and in the capabilities, as an ESPHome proxy does; Home
  Assistant then keys the scanner on it. Unset, nothing changes: HA keys it on the device MAC.
- **`ApiServer.isHomeAssistant(clientInfo)`** tells Home Assistant's `HelloRequest.client_info` from
  any other client's.

### Changed
- **`BluetoothProxyHandler.onRefused` now gets the refused client's `client_info`.** A device can then
  say "Home Assistant needs the key" only when it was Home Assistant, not a keyless script on the LAN.
  Implementations of `onRefused()` need the new parameter, so the next release is 0.2.0 (#246).

### Fixed
- **Home Assistant finds the Bluetooth proxy's flags where API 1.15 and later put them.** With
  `ApiServer.bluetooth` set, `DeviceCapabilitiesResponse` now carries the `bluetooth_proxy` block with
  the feature flags. HA still reads them from DeviceInfo too, so nothing changes today; this keeps the
  proxy working once HA reads only the capabilities. The proxy's MAC address isn't sent yet (#246).
- **The time zone still arrives when Home Assistant sends only `parsed_timezone`.** ESPHome 2026.9
  deprecated the `timezone` string in `GetTimeResponse`. While HA sends the string, `onTimeZone` gets it
  as before. An answer without it now gives the same POSIX rule rebuilt from the parsed zone, with
  numeric names (`<+01>-1<+02>-2,M3.5.0/2,M10.5.0/3`).

## [0.1.1] - 2026-11-15

Additions and fixes only: a store, server or app built against 0.1.0 works unchanged.

### Added
- **A stored key that can't be read now locks the server.** A store that implements the new
  `ReadableNoiseKeyStore` can say its key is stored but unreadable, in the same read that would give
  the key (`read()`: `StoredKey.Present`, `Absent` or `Unreadable`). The server then lets nobody in and
  isn't provisionable, and reads the key again on each connection and every minute. A device with a
  key never falls back to plaintext because its storage is down. `NoiseKeyStore` is unchanged; a plain
  one is read with `load()` as before.
- **`ApiServer.keyed`**: the device has a key, active or unreadable now. Advertise it as
  `api_encryption`, so a client tries Noise while the key can't be read.

### Fixed
- **A client that is slow to read still gets the DisconnectRequest.** When the entities or the key
  change, the server waits for its request to be written before the 2 s (or 500 ms) for the answer
  start, so the socket no longer closes with the request unsent. A client that doesn't take it within
  10 s is dropped anyway. `setEntities` and `replaceKey` still return at once.
- **Many entity changes in a row send one DisconnectRequest per client.** Before, each
  `setEntities` call sent another request and started another thread, so a burst of changes against
  a client that wasn't reading piled up threads.
- **An `Error` while writing to a client closes that connection**, even if the log sink fails too.
  Before, a caller waiting for a write (the camera, the answers to Disconnect and to a new key)
  waited until the keepalive dropped the client, up to 150 s.

## [0.1.0] - 2026-11-15

### Added
- **First release on Maven Central.** The device side of the ESPHome native API in Kotlin: the
  server, Noise encryption with key provisioning from Home Assistant, entities, actions, events on
  HA's bus, the camera, voice assistant messages and the passive Bluetooth proxy messages. Runs on
  any JVM and on Android 6 (API 23) and newer.
- **The API.** `ApiServer` and `DeviceIdentity`; the entities (binary sensor, sensor, text sensor,
  switch, number, select, text, button, event, update, light, media player, camera) and `ApiAction`;
  `NoiseKeys` with your own `NoiseKeyStore`; the voice, Bluetooth proxy and Sendspin hooks; and, to
  write an entity type of your own, `ApiEntity`, `ProtoWriter`, `ProtoMessage` and the `Msg` ids.
  Every public declaration has KDoc, in the KDoc jar.
