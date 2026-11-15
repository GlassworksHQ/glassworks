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
