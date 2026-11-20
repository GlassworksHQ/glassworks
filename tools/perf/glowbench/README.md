# GlowBench (throwaway)

**On `test-build/glowbench-s1` only. Never merged.** Measures the #691 edge glow, Spark ("edge-lit glass", #694),
inside a Glassworks app in the voice overlay's layer over the live dashboard, against today's voice orb on the same
rig. The code is in the assist pane (`panes/assist/.../assist/glowbench/`), so the S1 app (`sh.edm.s1kiosk`), the
generic app (`dev.glassworks.generic_android`, e.g. the Pixel Tablet) and its legacy build
(`dev.glassworks.generic_android.legacy`, Android 6-7, e.g. the Mirror) all carry it; debug and test builds only.

## Round 4

- **Every app**: the S1 app, the generic app and the legacy build. Sizes, the mic (the middle of the top edge) and the
  caption follow the window, so a portrait screen works (the caption sits higher there, as in the mockups).
- **Android 6-7**: FrameMetrics has frame timestamps from Android 8 only, so below it the drawn frames come from
  Choreographer; the JSON's `frameSource` says which.
- **Measurement**: no interval is counted across a phase change. The ~300 ms "exit" frame in round 3's loops was the
  screen being still at the end of speaking (the scripted voice is flat for its last 300 ms), not a late frame.
- **Entrance in loops**: the late frames show up after the 700 ms idle gap between scripted runs, and not on real runs,
  where the wake word keeps the CPU busy. Likely the clocks ramping down in the gap (untested): compare `--gap 0`
  with `--gap 3000`.
- **The seam** where the halves meet (the middle of the top and bottom edges) is gone: each half's top and bottom
  strip runs from its corner's light to the a/b middle, so both halves meet in the same colour.
- **Real runs**: a timer set by voice flies out as the glow leaves, and a tap anywhere cancels the run, as with the orb.
- **run.sh** finds its own lines by a tag instead of `logcat -T`, matches `ResumedActivity` with or without
  `m`/`top`, and takes `--package` (or finds the one Glassworks app installed).

## Round 3: off the UI thread

Round 2 stamped about 2,500 sprites per frame in `onDraw` on the UI thread: 15–25 ms of drawing per frame, about
12 fps during real runs. Round 3 (`LitGlow.kt`) draws nothing per frame:

- **Option A, RenderThread property animation**, not a SurfaceView/TextureView with its own thread (B). A needs no
  new surface: the glow stays in the window it shares with the voice overlay, and SurfaceFlinger composes nothing
  extra. B would add a full-screen translucent surface over GeckoView that SurfaceFlinger blends every frame (on the
  A133, likely with the GPU), plus a GL path to write and keep.
- **About 30 views, each one bitmap baked once**: two half rings of three strips each (a 1 px colour profile
  stretched along an edge), a rose ring for errors, two comet heads, four thinking shuttles, a mic glow, six pooled
  ripples, three caustics, the bevel, the dim and the caption. A frame changes only alpha, scale and translation.
- **The moments run on RenderThread**: the entrance (strips grow from the mic by scale, heads run round the edge,
  the caustic where the halves meet), the exit, each ripple, and each thinking lap are ViewPropertyAnimator
  animations with no listeners or end actions, all scheduled up front, which Android runs on RenderThread. They
  keep their pace when the UI thread is busy (the wake word model).
- **Only the voice level comes from the UI thread**: the mic glow and the rings' breathing, set as properties every
  frame. (Round 3 also had a 30 fps cap; on the S1 it wasn't needed, so run.sh no longer offers it.)

Measured per phase: the window's drawn frames (FrameMetrics, RenderThread-only frames included: what the screen
shows), the time to render each, and the UI thread's frame interval (Choreographer). The orb runs through the same
rig: the same script, or real runs with look `orb`.

Known gap: the a/b seam at the middle of the top and bottom edges is a hard cut in this prototype.

## Running it

```sh
# the root: install the test build over the panel's app (adb install -r), with the app in front
tools/perf/glowbench/run.sh --package sh.edm.s1kiosk --loops 5 -- adb -s "$(cat /tmp/s1-serial)"
tools/perf/glowbench/run.sh --package dev.glassworks.generic_android --loops 5 -- adb -s <tablet>
tools/perf/glowbench/run.sh --package dev.glassworks.generic_android.legacy --loops 5 -- adb -s <mirror>
tools/perf/glowbench/run.sh --live 3 --say "<wake word>, what time is it" -- adb -s <device>
# then put the original APK back
```

`run.sh` installs nothing. It wakes the screen, resets `dumpsys gfxinfo` for the app, sends the broadcast (or waits
for N live runs per look, speaking the phrase with macOS `say` if given), copies the JSON, saves gfxinfo and prints
the table (`report.py`). Broadcast by hand: `am broadcast -a dev.glassworks.debug.GLOW --es look glow|orb --es tag T
--ei loops N --ei gap MS`; `--ei loops 0` only sets the look for the real runs that follow.

The bar per phase, on drawn frames at 60 Hz: p90 within one vsync (16.7 ms + 1 ms of jitter), p99 within two
(33.3 ms + 1 ms). Compare the glow with the orb's rows: the orb is what ships today.
