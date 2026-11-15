# Changelog: improv

What changed in each release of `dev.glassworks:improv` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/improv/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [0.1.0] - 2026-11-15

### Added
- **First release on Maven Central.** The device side of Improv Wi-Fi for Android: the protocol,
  the state machines, the BLE GATT server, the serial transport and the adb transport. Android 6
  (API 23) and newer.
- **The API.** Over BLE, `ImprovSession` on `ImprovBleServer`; over serial, `ImprovSerialSession` with
  `SerialPacketReader`, or `ImprovAdbServer` over adb; and `WifiProvisioner`, which joins the network
  on Android 10 and later too. The packet encoding and the Wi-Fi security rules stay internal. Every
  public declaration has KDoc, in the KDoc jar.
