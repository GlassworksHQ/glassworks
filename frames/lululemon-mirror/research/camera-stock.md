---
topic: panes
evidence: verified
---

# The camera on stock Android 6: why Home Assistant got green stills and a stream with no pictures

**Status (2026-11-25): partly working.** The cause is verified on a Mirror with a spike app: about a third of camera
sessions start with no frames or with all-zero frames, whichever camera API or output is used. The camera pane now
treats a blank frame as no frame and reopens the session, never sends a blank still, keeps the frame reader off
most of the stream's frames on a LEGACY camera, and paces its retries while another app holds the camera. That
change is tested off the device only; a run on a Mirror is pending.

Device: lululemon Studio Mirror, original Qualcomm MSM8916 board, stock Android 6.0.1 with a 5 MP front camera behind
Qualcomm's HAL1 (`mm-camera`). Camera2 sees it as LEGACY (hardware level 2), through Android's Camera1 shim.
Tools: a throwaway spike app (Camera1 and Camera2 paths, an ImageReader, the hardware H.264 encoder's input surface,
a GL consumer) and logcat, over adb.

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

   | Run | Path | Outputs | Result |
   |---|---|---|---|
   | c1 | Camera1 | preview callback | no frame in 20 s |
   | c1-hint | Camera1 | preview callback, recording hint | no frame in 20 s |
   | c1-pic2 | Camera1 | preview callback, picture size set | no frame in 20 s |
   | c1-gl | Camera1 | GL consumer | 2 frames in 20 s, both all zero |
   | c1-pic | Camera1 | GL consumer | 28 fps, real picture |
   | c1b, c1c | Camera1 | GL consumer | 28–29 fps, Y mean 5.3, no zero rows |
   | c1enc | Camera1 | buffers to the hardware encoder | 25.5 fps, Y mean 5.2, no zero rows |
   | c2 | Camera2 | ImageReader 1280x720 + encoder surface | reader 0 frames; encoder gave only its parameter sets |
   | c2b | Camera2 | ImageReader + encoder surface | reader 6.9 fps, encoder at the same rate, Y mean 4.4 |
   | c2r | Camera2 | ImageReader alone | 8.8 fps, Y mean 4.2 |
   | c2e | Camera2 | encoder surface alone | about 26 fps (146 output buffers in the run) |

   A bad frame logged `Ymean=0.0 zeroFrac=1.000 zeroRowFrac=0.999`: every row zero but one, which is the dark noisy
   strip Home Assistant showed. A good frame in the same dark room logged `Ymean=4.4 zeroFrac=0.000 zeroRowFrac=0.000`.

   What it showed: 5 of 12 sessions started with no frames or with blank ones, with either API and any consumer.
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

## Result

| Problem | Cause | What the camera pane does now |
|---|---|---|
| Green stills | About a third of sessions deliver all-zero frames, and a frame was a frame | A frame whose sampled rows are (nearly) all zero in Y and both chroma planes counts as no frame: it doesn't reach presence, doesn't answer a still, and the session is reopened by the existing silence watchdog and backoff. A dark room is never blank: its Y has noise and its chroma is near 128 |
| No pictures in the stream | The same blank or empty sessions | The reopen above. The reader stays in a few of the stream's requests, so the check still sees the stream's session |
| Stream at 7 fps | The shim reads back every frame the reader gets | On a LEGACY camera the repeating request is a burst: the encoder gets every frame, the reader one in five at 15 fps (about 3 fps), enough for presence and stills. Other cameras are unchanged |
| `mediaserver` crashes | Bursts of connects while another app held the camera | A failed open waits 5 s, then 10, 20, 40, up to 60 s, and nothing else tries in the meantime. When Android reports that camera free again, it's tried at once, but never within 2 s of the last failure |

## How to reproduce

The spike was a throwaway, not kept in the repo. To see the problem again on a Mirror with the camera pane:

1. Turn on the camera pane with *Presence and snapshots* and look at the camera entity in Home Assistant.
2. Toggle camera privacy on and off a few times, waiting about 20 s each time. Each toggle closes the camera and
   opens a new session. With the fix, the app's log shows `camera: blank frame (every plane zero); not used` and a
   `reopen` line instead of a green still.
3. To see the connect pacing, open another camera app while the pane is on. The log shows `try N failed; next in
   …` with growing waits, and `is free again; opening now` when the other app closes.

All of this is read-only for the device: no storage is written outside the app's own data.

## Open questions

- Why the HAL starts a third of its sessions this way. The `mm-camera` logs show nothing different before a bad
  session. Without the vendor's source this is a workaround, not a fix.
- Whether presence at 3 fps while streaming detects motion as well as at 5 fps. The detector compares frames 200 ms
  apart by default; at 333 ms apart a slow movement shows as a bit more change per frame. Needs a run on the Mirror.
- Whether a repeating burst behaves on this shim as AOSP's source says. That was read, not run on the Mirror.
