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
- **Waits for Home Assistant to come back.** While the connection to HA is down (HA restarting, the device's Wi-Fi
  gone), a reconnecting player keeps trying without using up its retries, and `connectionRestored()` makes it try at
  once when the connection is back.
- **Faster players after the first.** libwebrtc's shared parts stay 30 s after the last player closes, so a player
  opened again soon starts without making them again. `HaWebRtcPlayer.prewarm()` makes them ahead of the first
  player, and `setRendering(false)` keeps a player connected while its view is off screen without drawing.
- **The signaling on its own.** `HaWebRtcSignaling` and `HaWebRtcStream` are plain Kotlin behind
  `HaCommandChannel`, so any HA WebSocket client and any WebRTC stack can use them.
- **No per-frame reports nobody listens to.** A listener that also implements `HaWebRtcPlayer.FrameDemand` can say
  it doesn't want each frame now (`wantsFrames`): the player then posts no main-thread task for each steady frame. A
  new size, the first frame and errors are still reported, and a listener without it hears of every frame.
