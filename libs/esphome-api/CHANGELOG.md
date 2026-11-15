# Changelog: esphome-api

What changed in each release of `dev.glassworks:esphome-api` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/esphome-api/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [Unreleased]

Additions only, so the next release is 0.1.1: a store, server or app built against 0.1.0 works
unchanged.

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
