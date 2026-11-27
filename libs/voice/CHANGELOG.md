# Changelog: voice

What changed in each release of `dev.glassworks:voice` on Maven Central. Versions follow
[semver](https://semver.org); while they start with 0, a minor version may change the API. Releases are
cut from `libs/voice/v<version>` tags ([docs/releasing.md](../../docs/releasing.md#libraries)), which
publish the matching section below as the release notes.

## [0.2.0] - 2026-11-26

### Changed
- **LiteRT 1.4.1 replaces TensorFlow Lite 2.16.1 for the wake word.** LiteRT is TensorFlow Lite under its new
  name, with the same `org.tensorflow.lite` API, and its native library is 16 KB page aligned, so the wake word
  loads on devices that use 16 KB memory pages (#962). LiteRT is on Google's Maven repository, not on Maven
  Central, so add `google()` to your repositories. Both libraries ship the same `org.tensorflow.lite` classes:
  if your app declares an `org.tensorflow:tensorflow-lite*` artifact, move it to the matching
  `com.google.ai.edge.litert` one, or the build fails on duplicate classes.

## [0.1.0] - 2026-11-22

### Added
- **First release on Maven Central.** An on-device wake word (microWakeWord, with openWakeWord models as
  a fallback) and a Home Assistant Assist client over the ESPHome native API, for Android 6 (API 23) and
  newer. It is the voice assistant the Glassworks S1 panel runs, split out of its pane (#152).
- **The API.** `VoiceAssistantController` (the Assist client), `VoiceEngine` and `VoiceInput` (the
  microphone and wake word), `VoiceConfig`, `VoiceEvent`, `UrlPlayer`, and the wake word catalogue
  (`WakeWords`, `WakeWord`, `MicroModel`, `WakeWordCatalog`).
- **`VoiceInput.readiness`.** Whether the wake word can listen, and if not why (`VoiceReadiness`: the
  model's download failed and why (`ModelFailure`), the engine didn't start, the microphone isn't allowed or
  doesn't work), with `onReadiness` for changes and `retryNow()`. `VoiceEngine` retries a failed load after
  30 s, then 1, 2, 5 and every 10 minutes (#458).
