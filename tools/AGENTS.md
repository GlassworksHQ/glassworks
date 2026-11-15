# Agent guide: the shared tools (`tools/`)

The repo-wide rules are in [`../AGENTS.md`](../AGENTS.md). The tools here serve every frame. The
S1's own tools (FEL, U-Boot, the root side, the radio, the CLI installer) are in
[`frames/aqara-s1-plus/tools/`](../frames/aqara-s1-plus/tools/AGENTS.md), with the rules for the part
of the repo that can brick a panel.

## Map

| Path | What | Notes |
|---|---|---|
| `perf/` | `run.sh`: Clarity, the performance test and grade for any frame; `summary.py` exports a frame's measurements, `grade.ts` computes the letter from them ([docs/perf.md](../docs/perf.md)); `threadcpu.py`: per-thread CPU of the app over a window, read-only ([idle-cpu.md](../frames/aqara-s1-plus/research/idle-cpu.md)); `transportwatch.py`: the panel's sockets and threads during a run, read-only (#202); `lanprobe.py`: whether the panel reaches this computer over the LAN for camera video (two connections from the panel, #202); `debug/screencam_moments.py`: which frames of a screen-camera recording to check against a run (debugging only: nothing in the grading path reads it) | Opens test pages and restarts the app; points the dashboard setting at its fake Home Assistant for the graded part (and turns panes off and on with `--pane-ram`), then puts them back |
| `perf-lab/` | `profile-view.sh`: one Clarity view open in desktop Firefox with the Gecko profiler, split by `split.py` (`mquit.py` quits that Firefox through Marionette so it writes its profile); `cpu-trace.sh` and `cpu-align.py`: who uses the panel's cores during each view open of a Clarity run, at which clock and temperature; `freq-around.sh`: the CPU clock at 20 Hz around one action; `pin-ceiling.sh`: holds the CPU ceiling at one frequency as root, with a temperature and time limit ([view-open.md](../frames/aqara-s1-plus/research/view-open.md)) | `profile-view.sh` touches no device; `cpu-trace.sh` only reads `/proc` and sysfs over adb, and adds a little load; `freq-around.sh` runs the action you give it; `pin-ceiling.sh` writes `scaling_max_freq` (RAM) and `/data/local/tmp`, and puts the ceiling back itself |
| `release/` | `frame-abis.sh`: the ABIs the release builds, from every frame's `abis` (#98); `check-update-compat.sh`; `changelog-section.sh`; `android-layout.sh`: the workflows' Gradle root, APKs found by name and the Android change filter, in both layouts of #21's move (test: `test/android-layout-test.sh`) | `frame-abis.sh` needs `jq` (GitHub's runners and macOS 15+ have it; else `brew install jq` or `apt install jq`) |
| `showcase/` | `render.mjs`: each frame's card render (`frames/<id>/design/render.webp`), HA's real frontend drawing `home.py`'s made-up home from Clarity's fake Home Assistant in headless Chrome ([frames/README.md](../frames/README.md#the-render)) | Writes only `frames/*/design/render.webp` (or `--out <dir>`); the fake HA listens on 127.0.0.1, Chrome runs with a throwaway profile. Needs Chrome and Node 22+ |
| `size/` | `apksize.py`: the APK's size per frame and per pane, the change and the frame's size budget, for CI ([docs/size.md](../docs/size.md)) | Reads APKs only |
| `esp32-uart-bridge/` | ESP32-S3 USB↔UART bridge for the debug console | |
