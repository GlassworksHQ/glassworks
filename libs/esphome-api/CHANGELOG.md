# Changelog: esphome-api

What changed in each release of `dev.glassworks:esphome-api` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/esphome-api/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [Unreleased]

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
