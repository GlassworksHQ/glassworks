# GlowBench (throwaway)

**On `test-build/glowbench-s1` only. Never merged.** Measures the #691 edge glow, Spark ("edge-lit glass", #694),
inside the S1 kiosk in the voice overlay's layer over the live dashboard, against today's voice orb on the same rig.

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
- **Only the voice level comes from the UI thread**: the mic glow and the rings' breathing, set as properties, at a
  cap of 60 or 30 fps (`--fps`). At 30 the level parts step at 30 Hz; with the smoothing they move slowly, so it
  should be hard to see. The ripples and the entrance still run at the display's rate.

Measured per phase: the window's drawn frames (FrameMetrics, RenderThread-only frames included: what the screen
shows), the time to render each, and the UI thread's frame interval (Choreographer). The orb runs through the same
rig: the same script, or real runs with look `orb`.

Known gap: the a/b seam at the middle of the top and bottom edges is a hard cut in this prototype.

## Running it

```sh
# the root: install the test build over the panel's app, with the kiosk in front
adb install -r s1-kiosk-test-0.4.5-<sha>.apk
tools/perf/glowbench/run.sh --loops 5 -- adb -s "$(cat /tmp/s1-serial)"                       # orb, then glow
tools/perf/glowbench/run.sh --loops 5 --look glow --fps 30 -- adb -s "$(cat /tmp/s1-serial)"
tools/perf/glowbench/run.sh --live 3 --say "<wake word>, what time is it" -- adb -s "$(cat /tmp/s1-serial)"
# then put the original APK back
```

`run.sh` installs nothing. It wakes the screen, resets `dumpsys gfxinfo` for the kiosk, sends the broadcast (or
waits for N live runs per look, speaking the phrase with macOS `say` if given), copies the JSON, saves gfxinfo and
prints the table (`report.py`). Broadcast by hand: `am broadcast -a dev.glassworks.debug.GLOW --es look glow|orb
--ei loops N --ei fps 60|30`; `--ei loops 0` only sets the look and cap for the real runs that follow.

The bar per phase, on drawn frames at 60 Hz: p90 within one vsync (16.7 ms + 1 ms of jitter), p99 within two
(33.3 ms + 1 ms). Compare the glow with the orb's rows: the orb is what ships today.
