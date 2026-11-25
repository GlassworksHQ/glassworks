# Changelog: ha-webrtc

What changed in each release of `dev.glassworks:ha-webrtc` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/ha-webrtc/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [Unreleased]

### Added
- **Home Assistant cameras over WebRTC on Android.** `HaWebRtcPlayer` plays a camera the way HA's frontend
  does: HA's `camera/webrtc` signaling with candidates trickled both ways, then the video on libwebrtc with
  hardware H.264 decoding where the device has it, drawn into a `TextureView`. It times out, reconnects when the
  connection drops, and closes the connection while paused. Android 6 (API 23) and newer.
- **The signaling on its own.** `HaWebRtcSignaling` and `HaWebRtcStream` are plain Kotlin behind
  `HaCommandChannel`, so any HA WebSocket client and any WebRTC stack can use them.
