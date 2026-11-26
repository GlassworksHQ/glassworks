---
topic: panes
evidence: verified
---

# The camera on stock Android 6: why Home Assistant got green stills and a stream with no pictures

**Status (2026-11-26): working on the Mirror.** The causes are verified on a Mirror with a spike app: the pane's fixed
15–15 fps request, which the camera lists but runs broken, and about a third of sessions starting with no frames or
blank ones. The camera pane now asks every camera for its top rate's steadiest range, treats a blank frame as no
frame and reopens the session, keeps the frame reader off most of every stream's frames (on every camera, not only
LEGACY ones), paces its retries while another app holds the camera, and turns stills and the stream upright. On the
Mirror (2026-11-26, verified; attempts 6 and 7) Home Assistant's still is a real, upright picture and the RTSP stream
is 720×1280, upright, in real color. On a full Camera2 camera (the Pixel Tablet's front camera, 2026-11-26, verified)
the GPU path at a turn of 0 first gave a stream on its side and squeezed into 1280×720, while the still was upright:
the camera puts its sensor's quarter turn in the texture's own transform, and the GPU step applied that on top of the
turn worked out for the raw buffer. It now keeps only that transform's crop. After the fix the tablet's stream is
1280×720, upright and matching the still, and the Mirror's is still 720×1280 and upright, matching its still. Still
owed: the long-run questions at the end.

Device: lululemon Studio Mirror, original Qualcomm MSM8916 board, stock Android 6.0.1 with a 5 MP front camera behind
Qualcomm's HAL1 (`mm-camera`). Camera2 sees it as LEGACY (hardware level 2), through Android's Camera1 shim.
Tools: a throwaway spike app, [`tools/mirror/camera-spike`](../../../tools/mirror/camera-spike/README.md) (Camera1
and Camera2 paths, an ImageReader, the hardware H.264 encoder, a GL consumer), and logcat, over adb.

## Question

With the camera pane on, Home Assistant's ESPHome camera image was solid green with a dark noisy strip across it,
and the RTSP stream opened but showed nothing (`missing picture in access unit` in the player). We wanted to know
whether Glassworks was converting frames wrongly, the camera was broken, or something in between, and what the pane
can do about it without root.

## How we got here

Solid green is what all-zero YUV looks like once converted to RGB: Y 0 with chroma 0 (instead of 128) pushes red and
blue below zero and green up. So the first suspect was Glassworks' own `yuvToNv21`: a wrong stride or plane order can
make green. But the stream was broken too, and it never goes through that code (the camera draws into the encoder's
surface directly). Two consumers failing together pointed at the camera, not at either consumer.

The pane already had a watchdog for a camera that starts and never sends (#599, #674): no first frame in 10 s means
close and open it again. Its log on the Mirror showed `reopen N (no first frame in 10 s)` lines, so sessions that
sent nothing were already being caught. The green stills meant some sessions sent frames that weren't pictures, and
nothing caught those.

To separate the camera from Glassworks, we wrote a spike app that opens the camera in each way we could think of
and measures what arrives: the mean of Y, the share of zero pixels, and the share of rows that are all zero. If the
spike saw the same blank frames with Camera1 alone, no Glassworks code was involved. It did.

The second thing the spike showed was the cost of the frame reader. On a LEGACY camera, Android's shim draws each
preview frame with GL into every output a request names, and for a YUV `ImageReader` it reads the pixels back with
`glReadPixels` and converts them on the CPU (AOSP 6.0.1, `SurfaceTextureRenderer.drawIntoSurfaces`, read). With
the reader in every request, the encoder only got as many frames as the reader. The same function skips outputs a
request doesn't name, and the shim's `RequestQueue` repeats a burst of requests (read), so a repeating burst where only
one request in a few names the reader lets the encoder run at full rate.

The third thing came out of the camera service crashing during the runs: while one app held the camera, a quick
series of connects from another crashed `mediaserver`.

## What we tried

1. **Twelve camera sessions, each a fresh open** (2026-11-25, verified). Each run opens the camera, runs for up to
   20 s, and logs frame statistics every 30 frames. Runs are named for what they did: `c1*` Camera1, `c2*` Camera2.
   The exact `run.sh` command of each is in the spike's README; the rows are in the order they ran.

   ```
   $ tools/mirror/camera-spike/run.sh c1-pic2 --es test c1 --es set 'picture-size=1920x1080' --es disp st
   set picture-size=1920x1080
   params now: picture-size=1920x1080 video-size=1920x1080 recording-hint=null zsl=off
   preview 1280x720 fmt=17 fps=7500,30000
   preview target: st
   preview started
   TIMEOUT frames=0
   ```

   | Run | Path | Settings | Result |
   |---|---|---|---|
   | c1 | Camera1, preview callback | SurfaceTexture target | no frame in 20 s |
   | c1-hint | Camera1, preview callback | `recording-hint=true` | no frame in 20 s |
   | c1-pic | Camera1, preview callback | `picture-size=1920x1080` | 28 fps, real picture |
   | c1-gl | Camera1, preview callback | GL consumer target | 2 frames in 20 s, both all zero |
   | c1-pic2 | Camera1, preview callback | as c1-pic | no frame in 20 s |
   | c1enc | Camera1, frames to the hardware encoder | as c1-pic | 25.5 fps, Y mean 5.2, no zero rows |
   | c2 | Camera2, 30 fps | ImageReader 1280x720 + encoder surface | reader 0 frames; encoder gave only its parameter sets |
   | c2b | Camera2, 30 fps | as c2 | reader 6.9 fps, encoder at the same rate, Y mean 4.4 |
   | c1b | Camera1, preview callback | as c1-gl | 28.5 fps, Y mean 5.3, no zero rows |
   | c2r | Camera2, 30 fps | ImageReader alone | 8.8 fps, Y mean 4.2 |
   | c1c | Camera1, preview callback | SurfaceTexture target | 28.8 fps, real picture |
   | c2e | Camera2, 30 fps | encoder surface alone | about 26 fps (146 output buffers in the run) |

   A bad frame logged `Ymean=0.0 zeroFrac=1.000 zeroRowFrac=0.999`: every row zero but one, which is the dark noisy
   strip Home Assistant showed. A good frame in the same dark room logged `Ymean=4.4 zeroFrac=0.000 zeroRowFrac=0.000`.

   What it showed: 5 of 12 sessions started with no frames or with blank ones, with either API and any consumer.
   The same settings went both ways: c1-pic was good and c1-pic2 empty, c1-gl blank and c1b good, c2 empty and c2b
   good.
   In those sessions `mm-camera` logged its stream on and the sensor counting frames, but only 0–2 buffers reached
   post-processing in 20 s. Nothing in the logs before a session told a bad one from a good one. It rules out
   Glassworks' conversion code, the encoder and the Camera2 shim as the cause.

2. **The reader's cost on the stream** (2026-11-25, verified). From the table: reader alone 8.8 fps, encoder alone
   about 26 fps, both in one repeating request 6.9 fps for both. The ImageReader gave YUV_420_888 at 1280x720 with
   row stride 1280 in all three planes and chroma pixel stride 2.

3. **Connecting while another app held the camera** (2026-11-25, verified). With the spike holding the camera, the
   camera pane retried `connectLegacy` about every 5 s, in bursts of 4–8. Twice in the session `mediaserver`
   aborted:

   ```
   F/libc: Fatal signal 6 (SIGABRT), code -6 in tid <tid> (Binder_3)
   F/DEBUG: pid: <pid>, tid: <tid>, name: Binder_3  >>> /system/bin/mediaserver <<<
   F/DEBUG: Abort message: 'finishConnectLocked: Invalid state for CameraService, clients not evicted properly'
   F/DEBUG:     #06 pc 0004cf53  /system/lib/libcameraservice.so (android::CameraService::finishConnectLocked(...)+366)
   F/DEBUG:     #08 pc 0004e87b  /system/lib/libcameraservice.so (android::CameraService::connectLegacy(...)+194)
   W/CameraBase: mediaserver's remote binder Camera object died
   ```

   `mediaserver` also hosts audio on Android 6, so each crash restarted audio too. The bursts came from the pane: a
   failed open retried after 5 s, but every other request for the camera in between (a still from Home Assistant,
   the end of a still's linger, presence starting again) tried at once.

4. **The pane's first fix on the Mirror** (2026-11-25, verified, test build `test-build/mirror-wifi-902-923` at
   `2ff391706`). With Home Assistant streaming over RTSP, the session that started after an app restart was bad, and
   stayed bad: Home Assistant's still was about two thirds solid green beside a streaky band, and frames pulled from
   the RTSP stream with `ffmpeg` (`select='not(mod(n\,30))'`) were the same, landscape: a streaky third on top, green
   below. So the stream's session was bad too, not only the reader's frames. The blank check didn't catch it: it
   asked for 90% of the sampled rows to be zero, and these frames had about two thirds. Since no picture has a row
   that is zero in Y and in both chroma planes, the threshold is now a quarter of the rows.

   The same run measured the stream's rate with the reader in one request of five: `ffprobe -count_packets
   -read_intervals %+5` read 103 packets in 5 s, against 6.9 fps with the reader in every request (attempt 2).

5. **Why the quarter threshold still missed** (2026-11-25, verified, test build `test-build/mirror-wifi-902-923` at
   `4d28332e0`, which carries attempt 4). The same bad session came back after the update: Home Assistant's still
   two thirds green, the stream the same, and the log had no `blank frame` line and no reopen. So the reader's frames
   passed the check. Reading the values rather than the colors explained it. Decoding Home Assistant's still to its
   own YCbCr (`ffmpeg -i still.jpg -f rawvideo -pix_fmt yuvj420p`) gave Y 79, Cb 83, Cr 71 across the green part,
   and the dark strip beside it Y 0 to 6 with chroma 126: the user's dark room. Decoding the stream
   (`-pix_fmt yuv420p`, no RGB step) gave a flat Y 84, U 89, V 79 below a third of picture.

   Both are RGB (0, 135, 0), the color all-zero YUV turns into, converted back to YUV: in full range for the
   reader (79, 83, 71) and in limited range for the encoder (84, 89, 79). Android 6's camera shim draws a LEGACY
   camera's preview through the GPU and converts it to YUV again for each output, so a zero region reaches the
   reader as that green, never as zero. The first green stills, from the old reader path, were zero; these weren't.
   The check now counts a row as dead when every sampled point is zero or within 4 of (79, 83, 71) in its plane. A
   flat grey wall (chroma 128) and a green scene with a real picture's spread still pass.

   The strip itself also says what goes wrong: in the stream it is a third of the height, 240 of 720 rows, and its
   picture repeats in vertical streaks. 640×480 is exactly a third of 1280×720, so a bad session most likely fills
   the buffer with a 640×480 preview laid out at the wrong size (**untested**: the preview size isn't logged).

6. **The cause: a fixed 15 fps range** (2026-11-25, verified, the same test build plus the green check above). With
   the check catching the green frames, every session the pane opened was bad: five in a row after an install, and
   five in a row again straight after a reboot, so a camera left stuck by a killed app was ruled out. `mm-camera`
   logged the preview stream at 1280x720 (`stream_type 1, stream format 2,stream dimension 1280x720`), which ruled
   out the 640×480 guess above. The camera spike, run with camera privacy on so the pane let go, got a clean picture
   with its reader alone. The difference was the rate: the spike always asked for 30–30, while the pane asked for
   15–15, for presence (the slowest range that covers 5 fps) and for the stream (the encoder's 15 fps). The spike got
   an `fps` option, and fresh sessions (force-stopped between runs: the spike then read `fps` from its first launch's
   Intent, since fixed) gave:

   ```
   $ tools/mirror/camera-spike/fps.sh a30:30,30 a15:15,15 a24:24,24 a730:7,30 b30:30,30 b15:15,15
   a30 want=30,30: session configured, fps [30, 30] reader frames=45 (9.2 fps)  green rows 0%
   a15 want=15,15: session configured, fps [15, 15] reader frames=44 (9.0 fps)  green rows 65%
   a24 want=24,24: session configured, fps [24, 24] reader frames=43 (8.7 fps)  green rows 0%
   a730 want=7,30: session configured, fps [7, 30] reader frames=45 (8.9 fps)  green rows 0%
   b30 want=30,30: session configured, fps [30, 30] reader frames=0
   b15 want=15,15: session configured, fps [15, 15] reader frames=45 (9.2 fps)  green rows 65%
   ```

   15–15 is in the camera's own list (`[15, 15], [24, 24], [7, 30], [30, 30]`) and broke every frame; the others
   didn't (`b30` is the separate first-frame stall the watchdog already handles). The reader got about 9 fps whatever
   was asked for: the shim's copy is the limit, so a lower range saved nothing. A LEGACY camera now gets its top
   rate, the steadiest range at it, for presence and the stream alike.

   Then the camera's own choice: with `--es fps template` the spike leaves the template's range alone and prints it.
   The Mirror's preview template comes with 30–30, and three fresh sessions with it were all clean (8.8–9.0 fps,
   green rows 0%). So the rule above picks what the camera recommends.

   With the cause found to be the rate, not the kind of camera, the LEGACY special cases went (maintainer's call):
   every camera now gets its top rate's steadiest range, and every stream gives the reader one frame in a few, the
   encoder the rest, with the encoder told the real rate. Only the size rule stays LEGACY's own (#679): it follows
   how Android's shim sizes its preview.

   On the Mirror (2026-11-26, verified, the test build with the single path): the camera opened at `[30, 30] fps`,
   Home Assistant's still was a real picture, and with Home Assistant playing the RTSP stream the log said
   `camera: streaming; the reader gets 1 frame in 10`. `top` gave the app 13% of the CPU while streaming, its page 2–4%
   and `mediaserver` 3%. The first session after the install was the first-frame stall again (one green frame, then
   nothing); the watchdog reopened it 15 s later and the second session was clean.

7. **Upside-down stills** (2026-11-26, verified). With a real picture at last, the still stood upside down. The camera
   reports `Orientation: 0` (`dumpsys media.camera`), and the screen runs at rotation 3 (`dumpsys display`:
   `rotation 3`, `persist.panel.orientation` 270) to stand the landscape panel up. Android's rule for a back camera,
   sensor minus screen, turned the still by (0 − 270) mod 360 = 90 degrees, which came out upside down, so the turn
   upright is 270 and the sensor really sits at 180. The Mirror's frame.json now says so (`camera.sensorDegrees: 180`),
   and the app goes by that over Android's value on this frame only.

   The RTSP stream isn't turned at all: the encoder takes the camera's frames as they come, so a frame grabbed with
   `ffmpeg -rtsp_transport tcp -i rtsp://…:8554/ -frames:v 1` is 1280×720 with the room on its side, needing a
   quarter turn counter-clockwise. Home Assistant's stream orientation option didn't help: set on the Mirror's camera
   as UniFi Protect gives it to Home Assistant, neither "Rotate left" nor "Rotate right" changed the live view, and
   Protect's own app reads the Mirror's stream directly anyway. So the app turns the stream itself: the camera draws
   into a `SurfaceTexture`, and one GL draw a frame puts it into the encoder's input surface turned by the still's own
   rule. Every stream takes that path, a turn of 0 included (maintainer's call: one encoder input on every camera).
   The encoder and the ONVIF profile run at the turned size, 720×1280 on the Mirror.

   The first build with the turn stood the stream upright but at 432×768. The Mirror's encoder,
   `OMX.qcom.video.encoder.avc`, lists its sizes as at most 1920×1088 in `/system/etc/media_codecs.xml`, and Android 6's
   `isSizeSupported(720, 1280)` reads that as landscape only, so 1280 rows looked too tall and the pane fell back to the
   next size that fit. Either way round, 720×1280 at 30 fps is 108,000 macroblocks a second, well inside the encoder's
   244,800, so the size check now accepts a size when the encoder lists it in either orientation. With that build (2026-11-26, verified) a
   frame grabbed from the RTSP stream is 720×1280, upright, with real color edge to edge. While Protect and the grab
   both streamed, `top` gave the app 15–18% of the CPU (11–16% at 432×768), its page 0–5% and `mediaserver` 2–3%.
   `dumpsys meminfo` gave the app 286 MB PSS, 80 MB of it `EGL mtrack`.

## Result

| Problem | Cause | What the camera pane does now |
|---|---|---|
| Green stills | The pane asked the camera for 15–15, which it lists but runs broken: a third of streaky picture over two thirds of green | Every camera is asked for the steadiest range at its top rate (30–30 on the Mirror, its own default). As a safety net, a frame with a quarter of its sampled rows dead (zero in Y and both chroma planes, or the green a LEGACY camera draws zero as) counts as no frame: it doesn't reach presence, doesn't answer a still, and the session is reopened by the silence watchdog and backoff. A dark room is never blank: its Y has noise and its chroma is near 128 |
| No pictures in the stream | The same blank or empty sessions | The reopen above. The reader stays in a few of the stream's requests, so the check still sees the stream's session |
| Stream at 7 fps | The shim reads back every frame the reader gets | While streaming, the repeating request is a burst on every camera: the encoder gets every frame, the reader one in ten at the Mirror's steady 30 fps (3 fps), enough for presence and stills. The interval comes from the slowest rate the camera may run at, so the reader keeps at least 3 fps; a camera that may run slower than 6 fps keeps the reader in every request |
| Upside-down stills, stream on its side | The camera reports its sensor at 0 degrees, but it sits at 180; the stream wasn't turned at all | The frame says 180 (`camera.sensorDegrees`). Stills and the stream turn by one rule from that and the screen's rotation; the stream turns on the GPU on its way to the encoder, from the camera's raw buffer as a still does (a full Camera2 camera's own turn in the texture's transform is left out) |
| `mediaserver` crashes | Bursts of connects while another app held the camera | A failed open waits 5 s, then 10, 20, 40, up to 60 s, and nothing else tries in the meantime. When Android reports that camera free again, it's tried at once, but never within 2 s of the last failure |

## How to reproduce

The spike is in [`tools/mirror/camera-spike`](../../../tools/mirror/camera-spike/README.md): `build.sh` builds it
without a device, and `run.sh <name> <extras>` runs one session on a Mirror and pulls its log. Turn camera privacy on
in Glassworks first, so the spike isn't fighting the pane for the camera. Run any row of the table several times:
about a third of the sessions come up empty or blank, whatever the settings. The spike writes only its own folder on
the Mirror; uninstall it afterwards (`adb uninstall dev.glassworks.camspike`).

To see the pane's handling on a Mirror running Glassworks with the camera pane:

1. Turn on the camera pane with *Presence and snapshots* and look at the camera entity in Home Assistant.
2. Toggle camera privacy on and off a few times, waiting about 20 s each time. Each toggle closes the camera and
   opens a new session. On a blank session the app's log shows `camera: blank frame (zero or drawn-green rows); not used`
   and a `reopen` line, and Home Assistant gets no green still.
3. To see the connect pacing, turn camera privacy on, start a spike run, and turn privacy off while the spike holds
   the camera, as in the recorded run. The pane's opens fail, and the log shows `try N failed; next in …` with growing
   waits, then `is free again; opening now` when the spike closes the camera. The other order, starting the spike
   while the pane holds the camera, is untested.

## Open questions

- What the GPU step costs on the Mirror over time (memory for the GL context, GPU time at 30 fps). Measured once
  under attempt 7; a long run with the page open is still owed, and a baseline without the GL step to compare
  `EGL mtrack` against.

- Why the HAL starts a third of its sessions this way. The `mm-camera` logs show nothing different before a bad
  session. Without the vendor's source this is a workaround, not a fix.
- Whether presence at 3 fps while streaming detects motion as well as at 5 fps. The detector compares frames 200 ms
  apart by default; at 333 ms apart a slow movement shows as a bit more change per frame. Needs a run on the Mirror.
- Whether a repeating burst behaves on this shim as AOSP's source says. That was read, not run on the Mirror.
