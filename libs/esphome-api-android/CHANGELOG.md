# Changelog: esphome-api-android

What changed in each release of `dev.glassworks:esphome-api-android` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/esphome-api-android/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [0.1.0] - 2026-11-15

### Added
- **First release on Maven Central.** `EspHomeMdns` advertises an esphome-api device over mDNS
  with Android's `NsdManager`, so Home Assistant discovers it. Android 6 (API 23) and newer.
- **The API.** `EspHomeMdns` alone: `start` with an `ApiServer` or a port, and `stop`. It publishes
  the TXT records ESPHome does, including the encryption state.
