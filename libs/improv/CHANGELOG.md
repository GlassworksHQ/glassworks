# Changelog: improv

What changed in each release of `dev.glassworks:improv` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/improv/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [Unreleased]

### Added
- **`WifiProvisioner.saysWhatToDo(reason)`.** True when a failure reason already tells the user what to do
  (`RADIO_DIDNT_COME_BACK`, `UNUSABLE_PASSWORD`, both public now), so an app doesn't add "check the password" on top.

### Fixed
- **A join right after the device's own hotspot waits for the Wi-Fi client.** With the user's Wi-Fi switch on but
  the client down (a device without STA+AP, a few seconds after its hotspot stops), `WifiProvisioner.connect` waits
  up to 15 s for the client instead of asking Android to turn Wi-Fi on, which Android 10 refuses. If it never comes
  back, the join fails with `WifiProvisioner.RADIO_DIDNT_COME_BACK`.
- **A password Android refuses fails the join instead of crashing.** A WPA3 password that isn't ASCII made Android's
  suggestion builder throw on the main thread. The join now fails with a reason, and nothing is logged about the
  password.

## [0.1.0] - 2026-11-15

### Added
- **First release on Maven Central.** The device side of Improv Wi-Fi for Android: the protocol,
  the state machines, the BLE GATT server, the serial transport and the adb transport. Android 6
  (API 23) and newer.
- **The API.** Over BLE, `ImprovSession` on `ImprovBleServer`; over serial, `ImprovSerialSession` with
  `SerialPacketReader`, or `ImprovAdbServer` over adb; and `WifiProvisioner`, which joins the network
  on Android 10 and later too. The packet encoding and the Wi-Fi security rules stay internal. Every
  public declaration has KDoc, in the KDoc jar.
