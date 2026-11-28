---
topic: perf
evidence: verified
---

# Window layers: where an app window's frames are on Android 17

**Status (2026-11-26): working.** On the Pixel Tablet's Android 17, a window's frames are on its
ViewRootImpl's BLAST layer, `VRI-<package>/<activity>#n`, not on the window's own layer. Clarity
reads that layer for Vitro since #949.

Device: Pixel Tablet, Android 17 (API 37). App: Glassworks for generic Android 0.2.0, a test build
at exp/vitro 2bdca2e31 with Vitro on. Tools: Clarity (`tools/perf`), `adb shell dumpsys SurfaceFlinger`.

## Question

Clarity times a view's open, live updates and restart from the frames SurfaceFlinger presents on
the layer the engine draws into. GeckoView draws into a SurfaceView, and Clarity already read its
`(BLAST)` layer on Android 17. Vitro draws into the app's own window. So which layer holds the
window's frames?

## How we got here

The first Vitro grade on the tablet came out E: open, live updates and restart were "not measured"
on every page, and only scrolling had frames. Vitro itself drew normally on screen, so the frames had
to be on a layer the runner wasn't reading.

`perflib.window_layers` took the layers whose name starts with `<package>/`, after an optional hash.
On Android 17 that matches only the window's container, `<hash> <package>/<activity>#n`. On Android 12
and later a BLAST buffer layer holds a surface's frames, so we looked for the window's own BLAST
child. WindowManager names it when it hands the app its surface.

## What we tried

1. **WindowManager's log** (2026-11-26, verified). Each time the app's window gets its surface:
   ```
   $ adb logcat -s WindowManager
   D WindowManager: setClientSurface Surface(name=VRI-dev.glassworks.generic_android/dev.glassworks.generic.Launcher#6447)/@0x… for … dev.glassworks.generic_android/dev.glassworks.generic.Launcher
   ```
   Every app's window follows the same pattern: `VRI-com.android.settings/…SubSettings#6355`,
   `VRI-NotificationShade#88`, and a splash screen's `VRI-Splash Screen com.android.settings#6310`.

2. **`--latency` on each of the app's layers, with a Bubble marquee animating** (2026-11-26,
   verified). The VRI layer gave 63 frames, and the container `<hash> <package>/<activity>#n`
   gave none.

3. **The grade again, with the runner patched to also take `VRI-<package>/` layers** (2026-11-26,
   verified). Presents read in run 1:

   | Runner | Presents in `refstart-1.json` | Grade |
   |---|---:|---|
   | Container only | 0 | E (open, updates, restart not measured) |
   | Container and VRI layer | 1630 | A |

The run didn't keep the raw `dumpsys SurfaceFlinger --list` output. The names above come from
WindowManager's log, and the test listing in `tools/perf/test/test_report.py`
(`test_vitro_window_on_android_15_and_later_is_its_vri_layer`) is modelled on them, wrapped in
`RequestedLayerState{…}` as SurfaceFlinger lists every layer on Android 15 and later.

## Result

| Layer (Android 17) | Frames |
|---|---|
| `<hash> <package>/<activity>#n` (the window's container) | none |
| `VRI-<package>/<activity>#n` (the ViewRootImpl's BLAST layer) | the window's: Vitro's |
| `<hash> SurfaceView[<package>/<activity>](BLAST)#n` | GeckoView's (the web engine, and Vitro's islands and fallback) |

`perflib.window_layers` takes both window layers, with the hash or `VRI-` in front, and `sf()` keeps
whichever has the newest frame. The S1 (Android 10) names the window `<package>/<activity>#0` and is
unchanged.

## How to reproduce

Read-only, on a device running the app:

```
adb shell dumpsys SurfaceFlinger --list | grep <package>
adb shell "dumpsys SurfaceFlinger --latency 'VRI-<package>/<activity>#<n>'"
```

Use the whole name, `#<n>` included.

## Open questions

- Android 15 and 16 are **untested**: we expect the same naming there, since they list layers the
  same way, but no device or emulator has shown it.
