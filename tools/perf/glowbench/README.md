# GlowBench (throwaway)

**On `test-build/glowbench-s1` only. Never merged.** Measures the #691 edge glow ("edge-lit glass", #694) drawn
natively inside the S1 kiosk, in the voice overlay's layer, over the live dashboard.

The app side is in `frames/aqara-s1-plus/android/src/main/java/dev/glassworks/s1/glowbench/`. Debug and test builds
register a receiver for `dev.glassworks.debug.GLOW` (senders need `android.permission.DUMP`, which adb's shell has).
Each run plays one variant's entrance, listening (a fake voice), thinking, speaking (calm) and exit, about 12 s,
drawn with sprites baked once and stamped with alpha (no blur per frame). Frame intervals come from Choreographer;
draw times from `onDraw`.

```sh
# the root: install the test build over the panel's app, with the kiosk in front
adb install -r s1-kiosk-test-<sha>.apk
tools/perf/glowbench/run.sh --loops 3 -- adb -s "$(cat /tmp/s1-serial)"
# then put the original APK back
```

`run.sh` installs nothing. It wakes the screen, sends the broadcast per variant, waits for `GlowBench: done`, copies
the JSON (written to the app's files dir and its external files dir, which adb can read on a release-signed test
build), saves `dumpsys gfxinfo` for the kiosk, and prints the table (`report.py`).

The bar per phase: p90 within one vsync (16.7 ms + 1 ms jitter at 60 Hz) and p99 within two (33.3 ms + 1 ms).
