<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-voice-on-dark.svg">
      <img alt="voice, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-voice-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">voice</h1>

<p align="center">
  <b>Wake words and Home Assistant Assist on Android.</b><br>
  The device listens on its own and talks to Assist like a Voice PE.
</p>

<p align="center">
  <a href="NOTICE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT, with one ported file under Apache-2.0 and BSD-3-Clause" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-beta-on-dark.svg">
    <img alt="Status: beta" src="https://www.glassworks.dev/brand/badge-status-beta-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

The voice side of an [ESPHome voice assistant](https://esphome.io/components/voice_assistant/) on
Android, as Home Assistant's Voice PE does it. The microphone, an on-device wake word
([microWakeWord](https://github.com/kahrendt/microWakeWord), and openWakeWord models already on the
device as a fallback) on TensorFlow Lite, and the Assist client over
[`esphome-api`](../esphome-api/README.md)'s `VoiceAssistantHandler`: the run, the audio stream, the
response, announcements, continued conversations and voice timers.

It has no Pane API and no S1 classes (#152). The Glassworks `assist` pane wraps it with settings,
entities, the overlay and the speaker. Package `dev.glassworks.voice`, MIT licensed, except
`MicroFrontend.kt`, a port under Apache-2.0 and BSD-3-Clause ([NOTICE](NOTICE)). The AAR carries all
three texts (`META-INF/LICENSE`, and `LICENSE-APACHE-2.0`, `LICENSE-BSD-3-CLAUSE` and the NOTICE in
`META-INF/dev.glassworks/voice/`), and the POM lists all three. minSdk 23.

## Use it in your own app

```kotlin
val engine = VoiceEngine(context, VoiceConfig(), WakeWords.DEFAULT, onActiveWordChanged = { id -> /* store it, pass it next time */ })
val controller = VoiceAssistantController(
    send = { type, payload -> server.sendVoice(type, payload) },
    player = player, // plays the response and announcements: your media player
    resolveUrl = { url -> if (url.startsWith("/")) "http://homeassistant.local:8123$url" else url }, // HA may send paths
    wakeWords = engine, // Home Assistant's wake word select picks from these
    emit = { event -> println(event) }, // WakeDetected, Listening, Thinking, Speaking, Idle, ...
)
engine.controller = controller
server.voice = controller // Home Assistant runs Assist through the ESPHome API
engine.start(muted = false)
// and about once a second: controller.tick()
```

`server` is an esphome-api `ApiServer` that Home Assistant has added. `player` is your `UrlPlayer`:
it plays the response and announcements from their URLs, one after another. Ask for `RECORD_AUDIO`
at runtime before `start`. `stop` the engine and `close` the controller when voice is turned off.

`VoiceInput.onMicLevel` gives each streamed frame's level (0 to 1), for an orb or a meter.
`controller.cancel()` ends a run (a tap on the overlay). The wake word models are fetched on first use
from esphome/micro-wake-word-models at a pinned commit, each checked against its SHA-256
(`WakeWords.MICRO`), into `VoiceConfig.modelsDir`.

## What's in it

| | |
|---|---|
| `VoiceAssistantController` | The Assist client: Home Assistant's `VoiceAssistantRequest`, `Audio`, `Event`, `Announce` and `Timer` messages, as esphome's `voice_assistant.cpp` handles them for a device with a media player. Watchdogs end a run that stalls. Pure JVM. |
| `VoiceEngine`, `VoiceInput`, `VoiceConfig` | The microphone (16 kHz mono, 80 ms frames) and the wake word, feeding the controller. Loads a new word while the old one still listens, and retries a model that fails to load. |
| `WakeWords`, `WakeWord`, `MicroModel`, `WakeWordCatalog` | The official microWakeWord models with their default cutoffs, and how a stored word id reads. |
| `UrlPlayer`, `VoiceEvent` | What the app supplies and what it hears. |
| internal | The microWakeWord frontend (a port of TFLite Micro's) and detector, openWakeWord's feature pipeline, the TFLite glue and the model fetcher. |

The manifest adds `RECORD_AUDIO` and `INTERNET`. Logs use the tag `Glassworks.Voice`. How the S1 uses it, and
how the wake word was measured, is in [docs/esphome.md](../../docs/esphome.md) and
[docs/research/microwakeword.md](../../docs/research/microwakeword.md).

## Gradle

On Maven Central as `dev.glassworks:voice` ([changelog](CHANGELOG.md)). Its LiteRT dependency comes from
Google's Maven repository, so add `google()` too.

```kotlin
repositories {
    mavenCentral()
    google()
}
dependencies {
    implementation("dev.glassworks:voice:0.2.0")
}
```

An Android library (AAR), minSdk 23 (Android 6). It brings in `dev.glassworks:esphome-api`, whose
`VoiceAssistantHandler` it implements, and LiteRT 1.4.1 (TensorFlow Lite under its new name), with its
native library for every ABI, 16 KB page aligned. LiteRT and `org.tensorflow:tensorflow-lite` ship the same
`org.tensorflow.lite` classes, so an app that uses this library can't also declare a TensorFlow Lite
artifact: move it to the matching `com.google.ai.edge.litert` one. A Kotlin build needs Kotlin 2.0 or newer.

## Tests

`./gradlew :voice:testDebugUnitTest`. `MicroFrontendTest` compares the frontend with pymicro-features
on fixtures. `VoiceAssistantControllerTest` drives whole runs, announcements and timers.
`VoiceEngineSwitchTest` switches words under Robolectric. `MicroWakeWordClipTest` runs the four real
models (LiteRT) behind the Kotlin pipeline over a clip; it skips unless `MWW_PYTHON` points at a Python
with `mww-test-requirements.txt` installed, and CI's `wake-word` job runs it.
