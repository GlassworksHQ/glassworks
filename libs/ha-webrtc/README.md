<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-ha-webrtc-on-dark.svg">
      <img alt="ha-webrtc, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-ha-webrtc-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">ha-webrtc</h1>

<p align="center">
  <b>Home Assistant camera WebRTC for Android.</b><br>
  HA's camera signaling with trickle ICE, and a player on libwebrtc.
</p>

<p align="center">
  <a href="NOTICE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT, with one file's drawing steps under BSD-3-Clause" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-alpha-on-dark.svg">
    <img alt="Status: alpha" src="https://www.glassworks.dev/brand/badge-status-alpha-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

Plays a Home Assistant camera over WebRTC on Android, the way HA's own frontend does. It speaks HA's
`camera/webrtc/*` WebSocket API: the client configuration, the offer as a subscription, and ICE candidates
trickled both ways. Then it plays the camera's video on libwebrtc, with the device's hardware H.264 decoder
where it has one. Any camera HA can stream over WebRTC works, whether through go2rtc (HA's default for RTSP
cameras), Nest, Reolink or another integration, and nothing needs to be installed in Home Assistant.

It was written for Vitro, the native dashboard engine in [Glassworks](../../README.md), and works with any HA
WebSocket client. Package `dev.glassworks.hawebrtc`, MIT licensed, Android 6 (API 23) and newer.

## What's in it

| | |
|---|---|
| `HaCommandChannel`, `HaError` | The one thing you plug in: send a command and get its result, or subscribe and get its events. Any HA WebSocket client fits behind it. Pure Kotlin. |
| `HaWebRtcSignaling` | One negotiation, as HA's frontend runs it: `get_client_config`, the `camera/webrtc/offer` subscription (`session`, `answer`, `candidate` and `error` events), local candidates held until the session id comes, and the subscription closed at the end. Pure Kotlin, usable with any WebRTC stack. |
| `WebRtcClientConfig`, `IceCandidateInit` | HA's client configuration (ICE servers, transport policy, data channel) and `RTCIceCandidateInit`, read and written as HA does. |
| `HaWebRtcStream` | A whole stream on top of the signaling: the connection, a timeout, reconnecting, pausing. Pure Kotlin. The peer is a `WebRtcPeer` and the thread a `StreamScheduler`, so all of it runs in JVM tests. |
| `HaWebRtcPlayer` | The Android player: `HaWebRtcStream` on libwebrtc, drawn into a `TextureView`, with listeners on the main thread. |
| `VideoScaling` | How the picture fills the view: `FIT`, `FILL` or `STRETCH`, like a `<video>`'s `object-fit`. |

## Playing a camera

```kotlin
val player = HaWebRtcPlayer(context, channel, "camera.front_door")
container.addView(player.view) // transparent until the first frame: put the still under it
player.listener = object : HaWebRtcPlayer.Listener {
    override fun onFirstFrame(sinceStartMs: Long, width: Int, height: Int) {
        println("live after $sinceStartMs ms: ${width}x$height")
    }

    override fun onError(error: HaError) {
        player.close() // fall back to HLS or MJPEG, as HA's frontend does
    }
}
player.start()
```

Call `player.setActive(false)` when the picture is hidden and `true` when it shows again, and `player.close()`
when it goes. Everything is on the main thread. A paused player stops drawing at once and closes its connection
after `HaWebRtcStream.Options.idleTeardownMs`, which is 60 seconds by default (the wait HA's frontend has for a hidden
page). Set it to 0 to close the connection as soon as the picture is hidden.

## Plugging in your HA client

`channel` is your Home Assistant WebSocket connection behind `HaCommandChannel`. If your client already sends
commands with callbacks and runs subscriptions, it takes a few lines:

```kotlin
class ClientChannel(private val client: MyHaClient) : HaCommandChannel {
    override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) {
        // The client must answer every command once, also when the connection drops: a lost connection answers
        // with success false and no error, which HaError.fromJson reads as HaError.NOT_CONNECTED.
        client.send(command) { answer ->
            if (answer.optBoolean("success")) onResult(answer.opt("result"))
            else onError(HaError.fromJson(answer.optJSONObject("error")))
        }
    }

    override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit): HaCommandChannel.Subscription {
        val id = client.subscribe(command, onEvent) { error -> onError(HaError.fromJson(error)) }
        return HaCommandChannel.Subscription { client.unsubscribe(id) }
    }
}
```

The channel numbers the commands. Callbacks may come on any thread. Every command must be answered exactly once,
even when the connection drops before Home Assistant replies: otherwise the stream waits for an answer that never
comes, until its timeout.

## Signaling only

With another WebRTC stack, `HaWebRtcSignaling` does HA's side alone:

```kotlin
val signaling = HaWebRtcSignaling(channel, "camera.front_door", object : HaWebRtcSignaling.Listener {
    override fun onAnswer(sdp: String) = peer.setAnswer(sdp)
    override fun onRemoteCandidate(candidate: IceCandidateInit) = peer.addCandidate(candidate)
    override fun onError(error: HaError) = peer.close()
})
signaling.fetchClientConfig({ config ->
    peer.start(config) { offer -> signaling.offer(offer) }
}, { error -> println("no WebRTC: ${error.code}") })
// For each candidate your peer finds:
signaling.addLocalCandidate(IceCandidateInit("candidate:1 1 udp 2122260223 192.0.2.10 50000 typ host", "0", 0))
```

One `HaWebRtcSignaling` is one negotiation. HA makes a new session for every offer, so a new peer connection
gets a new one. `close()` ends the subscription, which closes the camera's session in HA.

## How it follows Home Assistant

It follows HA core 2026.11 (`homeassistant/components/camera/webrtc.py`) and the frontend's `ha-web-rtc-player`
of the same release:

| Step | Home Assistant's frontend | ha-webrtc |
|---|---|---|
| Configuration | `camera/webrtc/get_client_config` | The same; `iceServers`, `iceTransportPolicy` and `dataChannel` |
| Peer | recvonly audio, then recvonly video; the data channel if HA names one | recvonly video; audio only with `Options(audio = true)`, since dashboard cameras are muted; the data channel the same |
| Offer | `camera/webrtc/offer` as a subscription | The same |
| Local candidates | Held until the `session` event, then `camera/webrtc/candidate` | The same |
| Remote candidates | `addIceCandidate`; neither `sdpMid` nor `sdpMLineIndex` means mid `"0"` | The same, held until the answer is set |
| Errors | `error` events and refusals show `start_failed`; a bad answer `connect_failed` | The same codes, plus `ice_failed` and `timeout` |
| ICE failure | `restartIce()` | A new connection (a new peer and a new HA session), up to 3 times in a row, backing off. Every offer to HA is a new session on the camera's side anyway. While the channel has no connection to HA (HA restarting, the device's Wi-Fi gone), a try doesn't count: the stream keeps waiting at the backed-off delay, as the frontend's offer waits for its socket, and `connectionRestored()` makes it try at once |
| Hidden | Closes the connection after 60 s hidden | `setActive(false)`, the same 60 s by default |
| No video | Keeps waiting | Fails after 20 s, so a caller can fall back |

## Threads

`HaWebRtcPlayer` is main-thread only, and its listener is called on the main thread. Each player runs its
connection on a thread of its own. libwebrtc's factory and its EGL context are shared by every player in the
process and freed 30 s after the last one closes, so a player opened again soon (a view rebuilt, a dialog opened) doesn't make them again. `HaWebRtcPlayer.prewarm(context)` makes them ahead of a player. `close()` frees the peer connection, HA's session, the decoder, the render
thread and the player's thread, and no listener call comes after it.

`HaWebRtcStream` runs on the thread of the `StreamScheduler` you give it, and `HaWebRtcSignaling` on any thread.

## libwebrtc

The player runs on [webrtc-sdk/android](https://github.com/webrtc-sdk/android) `150.7871.04`, a prebuilt of
Chromium's WebRTC M150. It's BSD-3-Clause, is maintained (it's the build LiveKit's Android SDK ships), supports
API 21 and newer, and bundles native libraries for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. The `arm64-v8a`
library is aligned for 16 KB pages. It decodes with `DefaultVideoDecoderFactory`: MediaCodec hardware decoders
first, then libwebrtc's own software VP8, VP9 and AV1, then the platform's software H.264.

This library uses its **prefixed** build, `io.github.webrtc-sdk:android-prefixed`, whose Java package is
`livekit.org.webrtc` and whose native library is `liblkjingle_peerconnection_so.so`. GeckoView carries WebRTC's own
`org.webrtc` classes for Firefox's WebRTC, so an app with GeckoView and any `org.webrtc` build would have about 300
classes twice and fail to build. The prefixed build shares no class or native symbol with it.

| Build | Licence | minSdk | Last release | Why not |
|---|---|---|---|---|
| **`io.github.webrtc-sdk:android-prefixed` 150.7871.04** | BSD-3-Clause | 21 | Oct 2026 (M150) | Chosen |
| `io.github.webrtc-sdk:android` 150.7871.04 | BSD-3-Clause | 21 | Oct 2026 (M150) | The same build as `org.webrtc`, which GeckoView also ships |
| `io.getstream:stream-webrtc-android` 1.3.10 | Apache-2.0 | 21 | Sep 2025 | No release in over a year, an older WebRTC, and `org.webrtc` |
| `org.jitsi:webrtc` 155.0.1 | Apache-2.0 / MIT | 24 | Oct 2026 | Needs Android 7, which rules out Android 6 devices |
| `org.webrtc:google-webrtc` | BSD-3-Clause | 21 | 2020 | Unmaintained: Google stopped publishing it |

What it adds to an APK, per ABI (the native library deflated, as apps that package native libraries the legacy
way carry it, plus its Java classes):

| ABI | Native library | In the APK |
|---|---|---|
| `armeabi-v7a` | 6.8 MB | about 4.2 MB, plus about 0.4 MB of classes |
| `arm64-v8a` | 12.3 MB | about 5.9 MB, plus about 0.4 MB of classes |

An app that keeps only the ABIs it needs (`ndk { abiFilters }`, or one APK per ABI) carries only one of them.

## Licences

This library is MIT, except the drawing steps in `TextureFrameDrawer.kt`, which reproduce libwebrtc's own and stay
under BSD-3-Clause ([NOTICE](NOTICE)). The AAR carries both texts (`META-INF/LICENSE`, `LICENSE-BSD-3-CLAUSE`) and
the NOTICE, and the POM lists both. libwebrtc is BSD-3-Clause and bundles third-party code under its own licences
(BoringSSL, libvpx, libyuv, dav1d, libaom, Opus, libsrtp, Abseil, libc++ and others, all permissive: BSD, MIT,
ISC, Apache-2.0 and similar). An app that ships this library must show those notices. Glassworks lists them in
[THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md).

## Testing

The signaling and the stream are tested on the JVM against a fake HA (`./gradlew :ha-webrtc:test`): offer,
answer and trickle both ways, refusals and `error` events, closing mid-negotiation, the timeout, reconnecting
and pausing. For real media, Glassworks' fake Home Assistant (`tools/perf/fakeha.py`) answers
`camera/webrtc/*` with an aiortc peer behind a TURN relay, as HA does with go2rtc. Point any `HaCommandChannel` at
it and play one of its cameras with `HaWebRtcPlayer`.
