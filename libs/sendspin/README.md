<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-sendspin-on-dark.svg">
      <img alt="sendspin, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-sendspin-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">sendspin</h1>

<p align="center">
  <b>A Sendspin synced-audio player for Android.</b><br>
  Music Assistant plays to the device in sync with its other speakers.
</p>

<p align="center">
  <a href="../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
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

A native [Sendspin](https://github.com/Sendspin/spec) player for Android. Music Assistant sees the
device as one of its own players, plays to it in sync with its other Sendspin speakers, and can group
it with them. The library finds the server, pairs, runs the Noise handshake, decodes FLAC (or takes
PCM) and schedules the audio against the server's clock.

It has no Pane API and no S1 classes (#152). The Glassworks `sendspin` pane wraps it with settings,
entities and the settings page. Package `dev.glassworks.sendspin`, MIT licensed, minSdk 23.

## Use it in your own app

```kotlin
val player = SendspinPlayer(
    context,
    MemoryStore(), // your own SsStore keeps the pairing across restarts
    SendspinPlayer.Identity(
        name = "Kitchen panel", productName = "My Panel", manufacturer = "Example", softwareVersion = "My Panel 1.0.0",
    ),
    object : SendspinPlayer.Settings {
        override val server = SendspinPlayer.AUTO // mDNS, then Music Assistant on the Home Assistant host
        override val flac = true
        override val unpairedAccess = false
    },
    homeAssistantUrl = { "http://homeassistant.local:8123" },
)
player.start()
```

Then hand the pairing token to Music Assistant. On a Glassworks panel, Home Assistant reads it over
the ESPHome API (`esphome-api`'s `SendspinTokenSource`) and passes it on:

```kotlin
// Music Assistant pairs with this token (Home Assistant hands it over). A credential: never log it.
val token = player.pairingToken()
println(player.statusText()) // "ready, paired with Music Assistant", "playing from Music Assistant (FLAC): …"
```

Call `player.stop()` when you're done. It blocks for up to about 2.5 s, but does its network I/O on a
thread of its own, so the main thread may call it. `setVolume`, `setMuted`, `restartSession` and
`onFlacSettingChanged` block on the server's socket: don't call them from the main thread. `setDucked(true)` silences the output
while your app speaks, and keeps the player in sync with its group.

`MemoryStore` forgets the pairing at every restart. Keep it with your own `SsStore`: it stores an
X25519 identity, a pairing key, the pairing records (`SsStore.encodeRecords` and `decodeRecords`) and
the volume. Encrypt the keys if you can. The S1 app's `PrefsSsStore` seals them with the Android key
store.

## What's in it

| | |
|---|---|
| `SendspinPlayer` | The player: connection and reconnects, discovery (`_sendspin-server._tcp`, then Music Assistant's port 8927 on the Home Assistant host), the FLAC to PCM fallback, volume, mute and output delay. `statusText()` gives its state in one line. |
| `SsStore`, `MemoryStore`, `SsIdentity`, `SsPairingRecord` | What the player keeps across restarts. |
| `WsClient` | The WebSocket client the player runs on (`ws://` and `wss://`), usable on its own. |
| internal | The protocol messages, the Noise `KKpsk2` handshake, clock sync (`TimeFilter`), the FLAC framing and MediaCodec decoder, the jitter buffer and the playback scheduler on an `AudioTrack`. |

The manifest adds `INTERNET` and `ACCESS_NETWORK_STATE`. Logs use the tag `Glassworks.Sendspin` and never
contain a key or token. How the player behaves on the S1, and how its sync was measured, is in
[docs/sendspin.md](../../docs/sendspin.md).

## Gradle

On Maven Central as `dev.glassworks:sendspin` ([changelog](CHANGELOG.md)).

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("dev.glassworks:sendspin:0.1.0")
}
```

An Android library (AAR), minSdk 23 (Android 6). It uses `dev.glassworks:esphome-api` (for its Noise
cipher) and Bouncy Castle at runtime; neither is part of its API. A Kotlin build needs Kotlin 2.0 or newer.

## Tests

`./gradlew :sendspin:testDebugUnitTest`. `SendspinRunsTest` runs the player on Android 10 under
Robolectric through quick stop and start cycles. `SendspinServerTest` runs the client against a real
aiosendspin 10.2.1 server (the version Music Assistant 2.10.9 uses): pairing, audio, commands and a
FLAC stream. It skips unless `SENDSPIN_PYTHON` points at a Python 3.12 with `aiosendspin[server]==10.2.1`.
