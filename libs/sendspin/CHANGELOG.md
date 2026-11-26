# Changelog: sendspin

What changed in each release of `dev.glassworks:sendspin` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/sendspin/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [0.2.0] - 2026-11-26

### Added
- **`SendspinPlayer.setOutputGain(gain)`.** A gain from 0 to 1 over the player's own volume, for an app with a
  master volume of its own on a device whose volume Android keeps fixed. The server isn't told: the player's
  volume stays where Music Assistant set it.

## [0.1.0] - 2026-11-22

### Added
- **First release on Maven Central.** `SendspinPlayer` is a native Sendspin player for Music Assistant:
  it finds the server, pairs with a pairing token, decodes FLAC or takes PCM, and plays in sync with
  the server's other speakers. Android 6 (API 23) and newer. It is the player the Glassworks S1 panel
  has run since S1 Kiosk 0.4, split out of its pane (#152).
- **The API.** `SendspinPlayer` with its `Identity`, `Settings` and `Status`; `SsStore`, `MemoryStore`,
  `SsIdentity` and `SsPairingRecord` for what it keeps across restarts; and `WsClient`, the WebSocket
  client it runs on. `WsClient.connect` can take an `openSocket` function that makes the TCP
  connection, so an app can try every address a name resolves to.
