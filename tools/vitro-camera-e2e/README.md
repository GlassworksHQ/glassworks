# Vitro's camera end-to-end test

Robolectric can't decode video, so Vitro's unit tests can't tell whether a camera card really plays. This test
runs Vitro's test APK on an Android emulator, where Android's real media stack does the work (MediaCodec, the
surface the picture lands on, the network stack). It plays one live camera over each transport Home Assistant's
frontend uses, against Clarity's fake Home Assistant, and checks the result on the screen.

| Transport | Camera | What the fake serves |
|---|---|---|
| WebRTC | `camera.clarity_cam_1` (offers HLS and WebRTC) | An aiortc peer answers `camera/webrtc/offer` behind a TURN relay ([`perf/webrtc.py`](../perf/webrtc.py)), as go2rtc does in HA |
| HLS | `camera.clarity_hls_1` (offers HLS only) | `camera/stream`, then a live fMP4 playlist and segments, as HA's `stream` integration serves them |
| MJPEG | `camera.vitro_e2e_mjpeg` (no stream feature) | `/api/camera_proxy_stream/<entity>`, multipart JPEG at 15 fps |

All three carry the same 640×360 picture: ffmpeg's moving test pattern with a burnt-in counter
([`perf/camera.py`](../perf/camera.py)), so a frozen frame, the still poster and a blank surface are all easy to
tell from live video.

## Run it

On METIS (WSL2 Ubuntu 24.04, with the emulator on Windows):

```sh
./gradlew :vitro-test:assembleDebug
ADB=/mnt/c/Users/<you>/AppData/Local/Android/Sdk/platform-tools/adb.exe ANDROID_SERIAL=emulator-5580 \
  tools/vitro-camera-e2e/run.sh -- --transports webrtc,hls,mjpeg
```

On Linux or macOS with a local emulator, leave `ADB` unset. The script needs `ffmpeg` with libx264 and Python
3.11 or later with `venv`. On first use it builds the WebRTC peer's venv, the camera streams and HA's frontend
into `~/.cache/glassworks-perf`, which takes a few minutes. Results and screenshots go to
`build/vitro-camera-e2e/` (`--out` changes it). Use an emulator only: the test presses HOME and force-stops the
test app.

`e2e.py --seam optional` also accepts a build that plays cameras without Vitro's `CameraStream` seam. That build
writes no first-frame line, so only the screen checks run.

## Networking

The emulator reaches the fake only through `adb reverse`, which forwards TCP from the device to the computer
running adb:

```text
emulator 127.0.0.1:8089 ──adb reverse──▶ adb host 127.0.0.1:8089 ──▶ fake HA (HTTP, websocket, HLS, MJPEG)
emulator 127.0.0.1:8090 ──adb reverse──▶ adb host 127.0.0.1:8090 ──▶ TURN relay (TCP) ──UDP──▶ aiortc peer
```

- **WebRTC's media is UDP, and `adb reverse` carries only TCP.** So the fake gives the client one TURN server
  over TCP (`turn:localhost:8090?transport=tcp`) and relay candidates only. The relay's UDP side and the peer
  share one address of the computer running the fake, so the media never leaves that computer
  ([`perf/webrtc.py`](../perf/webrtc.py) explains why it's `localhost` and not `127.0.0.1`).
- **On METIS the fake runs in WSL while adb and the emulator run on Windows.** WSL2's NAT mode forwards Windows'
  `localhost` to ports that WSL programs bind on `127.0.0.1`. So `adb reverse`, which Windows' adb server
  carries out, reaches the fake in WSL on both ports, with no `.wslconfig` change and no port proxy. The
  relay's media address is WSL's own `eth0` address (`172.x`), which stays inside WSL. This needs WSL's default
  `localhostForwarding`, and mirrored networking works too.
- **CI runs everything on one Linux runner**, so both hops are plain loopback.

## What it checks

For each transport, the test opens a view of the test's dashboard that holds one live `picture-entity` card
([`home.py`](home.py)):

1. **Open.** The app starts on a view without a camera, and its threads become the baseline. Then it goes to the
   camera's view.
2. **First frame.** The stream's first frame must arrive within the budget (10 s by default) and come from the
   expected kind of stream, and the fake must be serving that transport. The time is the seam's own
   `Listener.onFirstFrame(sinceStartMs)`, which is HA's `hasVideo: true`. The test app's `CameraProbe` logs it
   (logcat tag `VitroCamE2E`) by wrapping each registered `CameraStreamFactories` provider. The screen's first
   motion is reported next to it as a cross-check.
3. **Rendered.** Four screen captures are taken 0.5 s apart. Every pair must differ over an area at least 2% of
   the screen, so a still or the poster image fails. That area must show the test pattern's colors, so a black
   or blank surface fails too.
4. **Paused when hidden.** The test presses HOME. The stream must go inactive (`setActive(false)`) or close, and
   draw nothing. On the server, MJPEG frames must stop and no HLS fetch may come for 3 s. When the app comes
   back, the stream must deliver a first frame again and keep moving.
5. **Closed when removed.** The card is taken out of the dashboard and the fake sends HA's `lovelace_updated`.
   The stream must close, and the probe must count no open stream left. The fake must have no WebRTC session or
   MJPEG connection left for the camera, and no HLS fetch for 3 s. Every thread the stream started in the app
   must be gone, apart from Vitro's shared image pool (`vitro-img`).

Each run writes `results-api<N>.json` with the timings and every measurement, plus a screenshot of each
camera playing and after its card went. When a run fails, it also saves the failing screen and logcat.
