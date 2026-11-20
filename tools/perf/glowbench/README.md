# GlowBench (throwaway)

**On `test-build/glowbench-s1` only. Never merged.** Measures the #691 edge glow, Spark ("edge-lit glass", #694),
drawn natively inside the S1 kiosk in the voice overlay's layer, over the live dashboard.

The app side is in `frames/aqara-s1-plus/android/src/main/java/dev/glassworks/s1/glowbench/`, active in debug and
test builds only:

- **Real Assist runs.** The glow replaces the voice overlay during a voice run, driven by the real voice state:
  listening with the real mic level (ripples on loud words), thinking, speaking with the real playback (calm), errors.
  Spark's entrance on wake, Spark's exit when the run ends. A small caption shows what was heard and the reply.
- **Scripted loops.** `am broadcast -a dev.glassworks.debug.GLOW --ei loops N` (senders need
  `android.permission.DUMP`, which adb's shell has) plays a 12 s scripted run N times, for repeatable numbers.

Both draw with sprites baked once and stamped with alpha (no blur per frame), and log each phase's frame intervals
(Choreographer) and draw times to logcat (`GlowBench`) and to a JSON file in the files dir and the external files
dir (which adb can read on a release-signed test build).

```sh
# the root: install the test build over the panel's app, with the kiosk in front
adb install -r s1-kiosk-test-<version>-<sha>.apk
tools/perf/glowbench/run.sh --loops 5 -- adb -s "$(cat /tmp/s1-serial)"
tools/perf/glowbench/run.sh --live 5 --say "Okay Nabu, turn off the kitchen lights" -- adb -s "$(cat /tmp/s1-serial)"
# then put the original APK back
```

`run.sh` installs nothing. It wakes the screen, resets `dumpsys gfxinfo` for the kiosk, sends the broadcast (or waits
for N live runs, speaking the phrase with macOS `say` if given), copies the JSON, saves gfxinfo and prints the table
(`report.py`).

The bar per phase: p90 within one vsync (16.7 ms + 1 ms of jitter at 60 Hz) and p99 within two (33.3 ms + 1 ms).
