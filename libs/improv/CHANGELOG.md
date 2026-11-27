# Changelog: improv

What changed in each release of `dev.glassworks:improv` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/improv/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [Unreleased]

### Fixed
- **`ImprovBleServer` keeps advertising after a failed refresh on Android 6 and 7.** Below Android 8, a refresh of
  the advertisement that failed left nothing advertised, and no later state change tried again, so a phone could no
  longer find the device. It now tries again up to three times, a second or more apart, and then reports the failure
  through `onAdvertising(false, …)` instead of staying quiet.
- **`WifiProvisioner` keeps a joined network on Android 6 and 7.** There a network `addNetwork` adds is forgotten at
  the next restart until the app calls `saveConfiguration`, so it now does, once the join succeeds. Android 8 and
  newer save it themselves, so nothing changes there.

## [0.2.1] - 2026-11-25

### Fixed
- **The client stays connected through a state change on Android 6 and 7.** Below Android 8, `ImprovBleServer`
  refreshed the advertisement by stopping and restarting it, and on Android 6 that dropped the connected client
  right after the PROVISIONING state, so the Wi-Fi result never reached it. While a client is connected the
  refresh now waits until the last one leaves. A refresh that fails no longer reports `onAdvertising(false)`, which
  told the app advertising couldn't start; only the first start does.

## [0.2.0] - 2026-11-22

### Added
- **`WifiProvisioner.saysWhatToDo(reason)`.** True when a failure reason already tells the user what to do
  (`RADIO_DIDNT_COME_BACK`, or `UNUSABLE_PASSWORD`, which is public now), so an app doesn't add "check the password"
  on top.

### Changed
- **No network name in the logs.** `WifiProvisioner` logs "the chosen network" and the name's length instead
  of the name, and `WifiProvisioner.Outcome` and the serial session's request print neither the network nor
  the one a failed join went back to. A join that times out fails with the reason `timed out`, without the
  name. The `ssid` and `restored` fields are unchanged.

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
