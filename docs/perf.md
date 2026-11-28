# Clarity: the performance test and grade

<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
</p>

**Clarity** is the standardized performance benchmark for Glassworks wall panels. Running `tools/perf/run.sh` tests how smoothly a frame handles real Home Assistant dashboards and awards it a letter grade from **A+** to **E** (for example, *Clarity B*). Each letter represents the largest standardized dashboard where everyday wall-panel interactions—taps, scrolling, live state updates, more-info dialogs, and camera streams—still feel responsive:

| Grade | What it means |
|---|---|
| **A+** | Premium: meets grade A and holds a steady 60 fps across large dashboards |
| **A** | Great wall panel even for large, dense dashboards |
| **B** | Great wall panel for typical dashboards with a live camera |
| **C** | Great wall panel for typical dashboards without a camera |
| **D** | Great wall panel for light dashboards |
| **E** | Struggles to feel responsive even on light dashboards |

Because a live WebRTC camera stream is the heaviest load most dashboards carry—and many wall panels do not display cameras—Clarity measures the typical dashboard both without a camera (`typical-nocam`, grade C) and with a live 720p camera (`typical`, grade B). Each step up the scale adds one dashboard tier: **D** (light), **C** (typical without camera), **B** (typical with camera), and **A / A+** (heavy).

Every report opens with a plain-English headline and summary naming both the grade and the specific tasks holding the device back from the next letter up (for example: *"Aqara S1 Plus: Clarity C, a good wall panel for typical dashboards without a camera. Held back by large dashboards: scrolling averages 41.5 fps (p95 50 ms)."*). Grades are never hardcoded: each frame stores its measured [summary](#the-summary-glassworksperf-summary3), and `glassworks.dev` calculates the letter at render time against `tools/perf/page/rubric.json` (see [Showing the grade](#showing-the-grade)).

Clarity runs **Clarity v1** (protocol 1, rubric 2) on three standardized **reference dashboards**—*light*, *typical*, and *heavy*—using a pinned release of Home Assistant's real frontend served by a local fake Home Assistant (`tools/perf/fakeha.py`). Because every panel receives identical cards, layout, and update streams from the test machine rather than from your Home Assistant server or personal dashboard, grades reflect the hardware and Glassworks runtime. When a run uses wireless adb or LAN camera streaming instead of staying strictly over USB, graded traffic crosses your local network—`netcheck.py` checks gateway latency before the run and marks the grade provisional if the test machine's network adds latency (see [The test computer's network](#the-test-computers-network)). Background panes, the radio, and device thermals remain active just as they do in daily use (see [What the grade does and doesn't tell you](#what-the-grade-does-and-doesnt-tell-you)), and thresholds come from published web and mobile UX guidelines (Core Web Vitals, RAIL, Android vitals) rather than tuning to any specific panel.

Alongside the graded tasks, Clarity records per-process and per-pane RAM usage, idle CPU, real-dashboard reload times, and animation capacity for engineering visibility without affecting the letter grade. For pre-release protocol and renumbering notes, see [Version history](#version-history) at the bottom of this page.

## Run it

Before running Clarity, make sure you have `adb`, Python 3.11 or newer, `ffmpeg` with `libx264` (runs on your computer to generate camera streams), and a recent Glassworks build installed on the device (supporting the test hold and `test_dashboard` extras). The first run creates a local Python virtual environment in `~/.cache/glassworks-perf` for WebRTC cameras (`aiortc`, ~93 MB from `tools/perf/webrtc-requirements.txt`, verified by wheel hashes). Your computer needs an active non-loopback IPv4 interface (camera traffic stays strictly local).

Let the panel idle with its screen on for 10 minutes at room temperature with zoom left at default (`auto`). The benchmark never modifies your saved dashboard URL or Home Assistant sign-in (see [What it changes on the device](#what-it-changes-on-the-device)).

```sh
tools/perf/run.sh --frame aqara-s1-plus --name "Aqara S1 Plus" --runs 3 --out perf-s1
```

| Option | Default | What it does |
|---|---|---|
| `--frame` | *(required)* | Frame ID written into the report |
| `--name` | Frame ID | Display name used in the report headline |
| `--serial` | `$ANDROID_SERIAL` | Target adb device serial |
| `--package` | `sh.edm.s1kiosk` | Glassworks package to test (`dev.glassworks.generic_android` for Generic Android, `dev.glassworks.generic_android.legacy` for Android 6–7 / Mirror) |
| `--activity` | Auto-detected | Activity accepting Clarity extras (pass manually only if the app has not run since reboot; see [Other Glassworks apps, and Android 6](#other-glassworks-apps-and-android-6)) |
| `--runs` | `3` | Number of measurement runs (report uses medians and ranges) |
| `--pane-ram` | Off | Measure per-pane RAM impact by toggling switchable panes and restoring them afterward (the frame's switchable panes: on the S1 `assist`, `sendspin`, `wall-base`, `time-zone`, `diagnostics`, `mic`; on Generic Android and the frames based on it `assist`, `sendspin`, `camera`, `diagnostics`, `mic`); includes one unrecorded warm-up cycle (see [RAM](#ram)) |
| `--taps` | `10` | Number of toggle taps per reference view (`0` skips tap timing and yields a provisional E) |
| `--skip-synthetic`, `--skip-reference`, `--skip-real-use` | Off | Skip the animation ladder, graded reference views (yields no grade), or real-dashboard measurements |
| `--quick` | Off | Run a shortened animation ladder (1, 16, and 128 elements) to smoke-test setup |
| `--apk FILE` | None | Record APK size per ABI in the report (repeatable) |
| `--idle-only LABEL` | None | Capture a single idle sample under `LABEL` ([per-pane dashboard RAM](#ram-of-each-pane-on-your-own-dashboard)) |
| `--max-start-temp` | `50` | Wait up to 10 minutes for CPU temperature to cool below this threshold (°C) |
| `--sf-poll` | `auto` | SurfaceFlinger sampling strategy: `auto` runs the preflight check, `0` reads once per window (S1 default), or pass seconds to poll during windows |
| `--sf-check-only` | Off | Run preflight SurfaceFlinger (`sfcheck.json`) and network (`netcheck.json`) checks (~40 s) and exit |
| `--engine` | The engine the app runs | `vitro` or `web`: grade that engine. The run turns **Settings → Labs → Vitro** on or off for itself and puts it back afterward (see [Vitro](#vitro)) |
| `--ha-transport` | `adb` | How the panel reaches the fake Home Assistant: `adb` through `adb reverse`, `lan` over your network (for a device where `adb reverse` fails), or `auto`, which tries `adb reverse` and falls back to `lan` only on that failure. `--ha-over-lan` is short for `lan`. See [Home Assistant over the LAN](#home-assistant-over-the-lan) |
| `--webrtc-transport` | `auto` | The camera video's path: `auto` asks the panel, or force `lan-udp`, `lan-turn` or `adb` (see [WebRTC cameras](#webrtc-cameras-202-protocol-6)) |

On its first run, Clarity caches Home Assistant's frontend (~117 MB) in `~/.cache/glassworks-perf` and verifies its SHA-256 hash (`tools/perf/hafrontend.py`). A full 3-run benchmark takes roughly 40 minutes (`--pane-ram` adds ~10 minutes). Each graded run restarts to the start view and then navigates through all four reference views (*light*, *typical without camera*, *typical with camera*, and *heavy*) inside the loaded frontend with 10 taps, 6 swipes, and 3 more-info dialogs per view, followed by an idle sample.

Export the compact site summary from a completed report:

```sh
tools/perf/summary.py perf-s1/report.json perf-s1/summary.json --report-url report.json
```

Provisional or non-comparable runs are refused unless `--allow-provisional` is passed (which sets `complete: false` and records why). A run missing only what can't change the letter is exported without it, with `complete: false`. You can regenerate `report.json` and `report.md` offline anytime with `python3 tools/perf/report.py <dir>`.

### The test computer's network

Because `fakeha.py` runs on your computer, connect your test machine over wired Ethernet (or a quiet link) whenever graded traffic crosses the LAN—such as wireless adb (`adb reverse`) or LAN camera streaming (`lan-udp` / `lan-turn`). Before graded runs begin, `tools/perf/netcheck.py` pings both the default gateway and the panel (40 pings over 8 seconds, recording timings without addresses in `netcheck.json`). If gateway latency exceeds 20 ms while graded traffic uses that link, the report warns and marks the grade provisional. On macOS Wi-Fi, background AWDL traffic (AirDrop/Sidecar) frequently causes 60–90 ms spikes; use Ethernet or temporarily disable AWDL (`sudo ifconfig awdl0 down`, re-enabling with `up` afterward).

### Home Assistant over the LAN

Clarity normally serves its fake Home Assistant on your computer's `127.0.0.1` and lets the panel reach it with `adb reverse`, so the panel sees it at `http://127.0.0.1:8089`. Some devices can't make an `adb reverse`. The lululemon Mirror's Android 6, reached over network adb (`adb connect <address>:5555`) with no cable, answers every `adb reverse` with `adb: error: more than one device/emulator`, even with `-s` or `-t`. Its adbd from 2015 keeps its USB transport registered next to the network one, so its reverse service can't pick one. Changing the Mirror's USB setting isn't a way out, because on Android 6 that restarts adbd and drops the network link.

For such a device, run with `--ha-transport lan` (or `--ha-over-lan`):

```sh
adb connect <mirror-address>:5555
tools/perf/run.sh --frame lululemon-mirror --serial <mirror-address>:5555 --package dev.glassworks.generic_android.legacy \
  --ha-transport lan --runs 3 --out perf-mirror
```

`--ha-transport auto` does the same only when `adb reverse` fails with that error, and keeps `adb reverse` everywhere else. The default stays `adb`, so the S1 and Pixel Tablet runs work exactly as before and their grades stay comparable. With the default, a device whose `adb reverse` fails stops the run before anything is graded and suggests `--ha-transport lan`.

| | `adb` (default) | `lan` |
|---|---|---|
| Where the fake listens | `127.0.0.1` | Your computer's LAN address (the one the camera video uses, from its default route, or `GW_PERF_WEBRTC_ADDR`), on that address only and only for the run. Never every address |
| What the panel loads | `http://127.0.0.1:<port>/…` | `http://<LAN address>:<port>/…`: the test dashboard, the animation test page, the SurfaceFlinger check's page and the reference views |
| `adb reverse` | Two ports, removed on exit | None made, none removed |
| Camera video | `auto` picks `lan-udp`, `lan-turn` or `adb` | `auto` picks `lan-udp` when the panel's datagram arrives, and otherwise `lan-turn` (the relay over TCP). Android 6 has no `toybox nc` to ask with, so the Mirror gets `lan-turn`. `--webrtc-transport adb` is refused |
| What your network carries | The cameras, when their video goes over the LAN, and the live updates on wireless adb | The fake's pages and live updates, and the cameras |

**What the report says.** `ha_transport.json` records the choice (`transport`, `chosen_by` and `reason`, never the address). `report.json` gets `ha_transport`, `report.md` gets a **Home Assistant over the LAN** line, and the site summary gets `"ha_transport": "lan"`. The network check records `ha_over_lan` and counts the live updates as carried by your computer's network whatever adb runs over. A grade taken this way is therefore provisional when your computer's own link stalls, as with wireless adb ([The test computer's network](#the-test-computers-network)). Put the computer on a wired link for a grade you mean to publish.

**What the panel needs.** The test hold takes a test dashboard on a local network IP address as well as on loopback: a private, link-local or unique local address, never a name and never a public address, and only while a test holds the screen. An older app takes loopback only, and the run stops with that reason before the graded part. The legacy engine's lockdown holds pages to the test dashboard's origin, whatever its host, and Vitro follows that origin too. Over the LAN, the fake's address isn't one of Gecko's secure-context hosts (`127.0.0.1` is a secure context of its own). Nothing Clarity measures needs a secure context, and Home Assistant's frontend registers no service worker on either address.

### What it changes on the device

- **The test dashboard, for the graded part (and `--pane-ram`) only.** The kiosk shows the start
  view of the fake Home Assistant's reference dashboard instead of its own, with the test hold's
  `--es test_dashboard <url>`, so a restart comes back to a reference dashboard as it comes back to
  yours. The kiosk's dashboard setting and its sign-in to Home Assistant don't change. The kiosk
  takes only a page on this device (`127.0.0.1` or `localhost`) or, for [Home Assistant over the
  LAN](#home-assistant-over-the-lan), on a local network IP address, and only while a test hold is on; it
  keeps it through restarts with the hold (`no_backup/test_hold`), and says so in its log
  (`test dashboard on: http://127.0.0.1:8089`), which the runner checks. When the graded part ends, and
  on any exit the shell can catch, the runner ends it (`--es test_dashboard ''`, with `am start -W`)
  and reads the answer: `am` exits 0 even when the kiosk didn't take the intent. If it didn't, the
  runner tries once more, then exits with status 3 and logs the command to send yourself. The hold's
  end ends it too, so a run killed with `SIGKILL` leaves the fake on screen for at most the rest of
  the hold (30 minutes). The legacy engine (#198), which holds pages to one origin, restarts the app
  when the test dashboard starts and ends. The fake's sign-in is stored in the page for its own address
  (`127.0.0.1:8089`) only. Before this, the runner set the kiosk's saved dashboard (`--es url`) and put
  it back after: that moved the panel's Home Assistant address, and since #496 a panel whose Home
  Assistant moved asks to sign in again. On the Pixel Tablet (2026-11-18) the graded runs failed on
  the sign-in screen, and the panel still asked after the run.
- **The pages.** The frontend loads once per graded run, when the kiosk comes back after the
  restart. The four reference views then open **inside** it: the runner hands each one to the fake
  Home Assistant (`/clarity/open`), and the harness, already running in the frontend, picks it up
  (`/clarity/next`) and navigates there as HA's view tabs do. No page loads, and any page load while
  a view is measured fails the run. Since protocol 6, each graded run ends with the camera views'
  HLS copies (detail, not graded; see [WebRTC cameras](#webrtc-cameras-202-protocol-6)). Only the animation test page and the idle sample's page open
  with the kiosk's one-off `--es open <url>` extra. Everything is served from your computer through
  `adb reverse` (`http://127.0.0.1:8089` on the device), so the device's network plays no part.
  HA's frontend files are cached for a year, as HA serves them (their names carry a hash); the app
  shell, the API and the harness are never cached. The harness's URL carries a hash of its content,
  so a panel that cached an older harness (8c0027b served it at a fixed URL for a year) never runs
  it again. HA's frontend registers its service worker only on https or `localhost`
  (`register-service-worker.ts`), so on `http://127.0.0.1` there is none, as on an http HA.
- **The panes, with `--pane-ram` only.** The kiosk's own extras turn the switchable panes off and
  on in turn, in two intents: first the settings it applies live (`--ez sendspin_enabled`,
  `--ez wall_base_enabled`, `--ez time_zone_enabled`, `--ez diagnostics_enabled`, `--ez mic_muted`),
  then `--ez voice_enabled` with `--ez restart true`, so each sample starts from a fresh process
  (since #67 voice turns on and off live; a kiosk before it restarts for `voice_enabled` alone). The
  runner then reads the kiosk's log to check what it runs (see [RAM](#ram)). On exit they are put
  back to the settings you gave. While a pane is off, the panel doesn't play music, hear its wake
  word, drive the wall base, keep the time zone or sample its diagnostics.
- **The screen stays on** through the kiosk's test hold (#240): `--ez test_hold true --ei
  test_hold_s 1800`. While it lasts, the kiosk's idle timeout doesn't turn the screen off. It changes
  nothing else: brightness and night dimming stay what the panel shows without it, so a run at night
  is measured at the night's brightness, as before #240. It changes no setting, and Home
  Assistant sees no setting change. It outlives a restart of the app: the app keeps it in its memory
  and in a file outside its settings and backups (`no_backup/test_hold`), so the new process holds
  the screen from its start. In the S1's night run (2026-11-16) a hold that died with the process let
  the new process's own 20 s night timeout turn the screen off in every graded restart. It ends after
  its time, at `--ez test_hold false`, or at a reboot. The runner sets it first and checks the kiosk's log for its answer
  (`test hold on for 1800 s`). An app that doesn't answer within 15 s is refused, with no fallback.
  It sets it again before each part and before each view opens, so a long run never outlasts it,
  and ends it on exit. From the force-stop until the next view's hold (and through a recovery's
  restart), Android's own wake key (`input keyevent KEYCODE_WAKEUP`) goes at the stop, at the
  relaunch and every 8 s, for the time the app is down: the kiosk's intent would launch the stopped
  app and change the restart being measured. In s1rtc7 run 2 (2026-11-14, 01:12) the
  screen went off 20.4 s after the stop, before the kiosk's first frame, which then waited 280 s
  for the screen. If the screen goes off anyway during a graded run (Android's
  `PowerManagerService: Going to sleep` in logcat), that run is an error: with the screen off Gecko
  runs no animation frames and slows its timers, so nothing timed then is the panel's. In smoke10
  (9d986c9, 22:06, the kiosk's 20 s night timeout) the screen went off three times: light's view
  switch waited 22 s for it to wake, typical's main thread looked busy for 112 s, and heavy never
  ran. Before #240 the runner sent the kiosk's wake intent (`--es screen on`) every 8 s between
  views and to a live view after 10 s without a touch, and still lost the screen: s1rtc5 run 1 lost
  heavy's 16 to 20 s after its last tap.
- **Idle samples and the animation test page** set the test hold before they start. If the screen
  goes off anyway, the sample (`screen_off`) or the run (a `screen_off` line in
  `synthetic-<n>.jsonl`) records it, and the report lists it and leaves it out. In s1v7 (131f6da,
  2026-11-13) the screen went off at the end of the third `ref-idle` window, 0.14 s before the
  runner's wake, after the graded run had ended: no graded window overlapped it, and the RAM was
  read after the wake. Before this, that sample would have counted.
- **Taps and swipes**, only on the reference pages and only when the page asks: each page logs
  where its "Living room" toggle is (`input tap`) and where to swipe (`input swipe`), tagged with
  that page load's nonce. A request with another page's nonce is ignored. Before anything reaches
  `adb shell`, the runner checks the request: whole pixels inside the screen as it is turned, 1 to 50
  taps (and no more than `--taps`), a swipe of 50 to 2000 ms. A bad request isn't sent; that page
  gets an error and isn't graded. A gesture is sent only while its page is still the current one,
  and the next page opens only after the last page's gestures are done. Nothing else is touched.
  On a frame without touch ([No touchscreen](#other-glassworks-apps-and-android-6)) the graded views ask for no
  swipes: only the taps go.
- **Restarts** send the system `KEYCODE_WAKEUP` key (the kiosk's wake intent would start the app)
  and force-stop the app. A kiosk that is the device's home app, like the S1's, is started again by
  the system at once (0.2 s on the S1). Otherwise the runner starts it with `am start -W` after 3 s.
  If the run stops early, or the last restart doesn't reach the dashboard, the exit trap restarts
  the app. While the app restarts, Home Assistant sees the panel go offline for a few seconds.
- **Vitro, with `--engine`.** When the engine asked for isn't the one the app runs, the runner switches
  **Settings → Labs → Vitro** with the app's own `--ez vitro true|false` (the app restarts for it), checks the
  app's answer, and on exit switches it back the same way, after the test dashboard has ended and before the
  hold does. If that fails, it logs the command to send yourself. See [Vitro](#vitro).
- **On Android 6 only, five lines in the device's log** per runner step, ten in the steps that record the
  clock's drift (`ClarityClock: <nonce> <n>`):
  how the runner ties logcat's wall clock to the monotonic one (see
  [Other Glassworks apps, and Android 6](#other-glassworks-apps-and-android-6)).
- Everything else is read-only: `getprop`, `/proc`, `dumpsys meminfo`, `dumpsys SurfaceFlinger
  --latency`, `dumpsys activity recents` (the app's activity), logcat.

### Other Glassworks apps, and Android 6

Clarity runs on any Glassworks app: the S1's (`sh.edm.s1kiosk`), the generic app
(`dev.glassworks.generic_android`) and its legacy build (`dev.glassworks.generic_android.legacy`, the
lululemon Mirror's Android 6). Give the app with `--package`.

**The activity.** Clarity's extras go to the activity the kiosk runs in. The runner reads it from the app's
most recent task (`realActivity=` in `dumpsys activity recents`, the same on Android 6 to 17): `.MainActivity`
on the S1, `dev.glassworks.MainActivity` on the generic app. It doesn't take the app's launcher entry
(`cmd package resolve-activity`, or `pm dump` on Android 6, which has no `cmd`): on the generic app that is an
alias, `dev.glassworks.generic.Launcher`, and the app drops extras that come through it. A task stays listed
after the app stops, until the device reboots. If the app hasn't run since then, the runner says so and asks for
`--activity`.

**Android 6** (checked on an API 23 arm64 emulator, 2026-11-18; the Mirror runs 6.0.1) lacks some of what the
runner uses. What the runner does instead:

| What | Android 6 | The runner |
|---|---|---|
| `logcat -v monotonic` | "Invalid parameter monotonic to -v" | Asks once per runner step. Without it: `-v usec`, the wall clock to the µs, tied to the monotonic clock (below) |
| `date +%N`, `nproc` | No nanoseconds (`1795071980.N`); no `nproc` | Counts the `cpuN` lines of `/proc/stat`, the same online CPUs as `nproc` |
| `ps -A -o PID,NAME` | Ignores the options: its own columns (`USER PID PPID VSIZE RSS WCHAN PC S NAME`) | Reads the PID column by its header and the name last |
| `pidof <package>` | Lists every process | Not used: the runner reads `ps` |
| SurfaceFlinger's layer | Plain `SurfaceView`, without the app (Android 10: `SurfaceView - <pkg>/<activity>`, Android 12+: `SurfaceView[<pkg>/<activity>]`, Android 15+: below) | Takes plain `SurfaceView` while the app has a window listed: a kiosk is the only app on screen |
| `dumpsys meminfo` | `65128 kB: name` instead of `65,128K: name`; the legacy engine's content process is `:tab32` | Reads both; `:tab<n>` is a tab process |
| `toybox nc` (camera video over the LAN) | No `nc` in toybox | The camera video goes over adb (`lanprobe.py` already falls back); over the LAN (`--ha-transport lan`) it takes the relay over TCP |
| `adb reverse` over network adb (the Mirror, no cable) | `adb: error: more than one device/emulator`, also with `-s` and `-t` | `--ha-transport lan`: the fake over the LAN, no reverse ([Home Assistant over the LAN](#home-assistant-over-the-lan)) |
| `cmd` | Not there | Not used |

`screencap`, `wm size`, `input tap`/`swipe` with a duration, `am start -W`, `am force-stop`, `stat -c`,
`logcat -T`, `adb reverse` over USB and `dumpsys SurfaceFlinger --latency` work as on Android 10.

**Android 15 and later name layers differently** (checked on API 35 and API 37 arm64 emulators,
2026-11-19). The Pixel Tablet's first graded run on Android 17 had no frames from SurfaceFlinger, so
open, restart and live updates were n/a on every view, and scrolling rested on rAF alone.
`dumpsys SurfaceFlinger --list` wraps each line as `RequestedLayerState{<name> parentId=… z=…}`. On
Android 17 a SurfaceView's name also starts with the view's hash:

| Android | The app's frames in `--list` |
|---|---|
| 10 (the S1) | `SurfaceView - <pkg>/<activity>#0` |
| 12 to 14 | `SurfaceView[<pkg>/<activity>](BLAST)#163` |
| 15, 16 | `RequestedLayerState{SurfaceView[<pkg>/<activity>](BLAST)#169 parentId=168}` |
| 17 | `RequestedLayerState{f5fea55 SurfaceView[<pkg>/<activity>](BLAST)#163 parentId=162}` |

`--latency` takes only the whole name, hash and `#<id>` included. Any other form gets the refresh
period alone, as an unknown name does. The runner reads the name out of the wrapper and passes it on
whole. The `(BLAST)` layer has the frames; its parent has none. When the runner finds a window of
the app but no SurfaceView it can name, it prints a warning in the run's log.

**SurfaceFlinger's history, and the check before a run** (#643). `--latency` keeps only a layer's
last frames, and one read must reach back to a window's start to cover it:

| | S1 (Android 10) | Pixel Tablet (Android 17), run 3, 2026-11-19 |
|---|---|---|
| Frames one read holds while the page draws every frame | 127 (2.1 s) | 59 (0.98 s) |
| A swipe's window | | 1.15 to 1.18 s |
| The step line, behind the window's end | | 0.38 to 0.43 s |
| One adb shell call (wireless on the tablet) | | 80 ms, p95 171 ms |

On the tablet a swipe is longer than the whole history, so one read after it can't cover it, and
neither can `--latency-clear` before it. Instead, `run.sh` first runs `perfrun.py sfcheck`. It opens
`page/sfcheck.html`, a page that draws every frame for 20 s, and reads the app's layer until the history
stops growing. Then it decides (`perflib.sf_plan`), and writes `sfcheck.json`:

1. **One read after each window** when the history's frames, a refresh apart, reach back at least 1.8 s
   (`SF_ONE_READ_MS`: a swipe, its step line's lag, and room). This is the S1's way: nothing changes
   there, and its records are the same, byte for byte.
2. **A read every N seconds during each window** too, when it reaches less. N is half the reach, at most
   0.3 s (the tablet: 0.3 s). The reads keep that rhythm, so a slow read is followed at once. The
   frame log behind the restart and the open, and the idle sample, read at the same rhythm: the known
   layer alone, with a listing read (`--list`, then the newest layer: two calls) every third read. The
   check times both kinds of read, and each must take at most half the reach (the API 37 emulator:
   294 and 374 ms p95 against 525). The
   runner joins the reads by present time (`perflib.stitch_reads`). A window counts as covered back to
   where the reads overlap without a gap, as one read covered it before. Each window's record then has
   `reads`, how many it took.
3. **Stop**, exit 2, before anything is graded, when the app's layer can't be named, the page draws no
   frames, or reads would have to come closer than 0.05 s, or one read or listing read takes longer than
   half the reach.

The reads during a window are dumpsys calls on the device while it is measured: one every 0.3 s on
the tablet. On the S1 there are none.

**The clock on Android 6.** SurfaceFlinger's frame times are on the monotonic clock (`CLOCK_MONOTONIC`), and the
runner cuts them to the windows the page logs. Android 6's logcat stamps lines only with the wall clock. So the
runner measures the offset once per runner step: five times, it reads the monotonic clock from
`/proc/timer_list` (`now at <ns> nsecs`, read by the shell itself, no process started), writes a log line
(`log -t ClarityClock`), and reads the clock again. The line's stamp lies between the two reads, so the tightest
of the five pairs gives the offset to within half its width. The run's files on Android 6 carry it in
`logcat_clock` (refstart, coldstart and idle samples): `offset_err_ms`, and `drift_ms`, how far the offset
moved by the end of that step. A wall-clock change during a step (a network-time step, daylight saving) moves
every later line by that much, and shows in `drift_ms`. A restart run (graded, or real use) whose offset
moved by more than both calibrations' errors plus 5 ms fails, as one with the screen off does: its windows no
longer line up with SurfaceFlinger's frames. The check compares the offset at the step's start and end only,
so a step that goes back before the end isn't caught. Measured on the API 23 emulator (2026-11-18), in a `--quick` run: the offset's error was 0.76 to 1.9 ms
in every step, and it moved by 0.35 ms at most within one. Where logcat has `-v monotonic` (Android 7 and later, the S1, the tablet) nothing changes: the
runner reads it as before.

**The legacy engine's lockdown** (#198) loads pages only from the dashboard's origin, which it takes when its
process starts. The animation test page opens on the fake Home Assistant's origin with `--es open`, so the
legacy engine refuses it ("Lockdown: refused page from 127.0.0.1"). The runner sees that line, skips the
animation test page (detail, not graded) and goes on.

**No touchscreen.** `input tap` and `input swipe` inject touch events whether or not the device has a
touchscreen. The API 23 emulator was set up without one (`hw.screen=no-touch`, which Android reports as
`-touch`), with the Mirror's legacy build of the app (0.2.0). There (2026-11-18) the taps reached the page:
the first two views counted all their taps by the page's own `pointerdown`, and Event Timing measured them. But
no swipe scrolled the page, on any view, while the same `input swipe` scrolled Android's own Settings list. So
on a frame without a touchscreen, the legacy engine takes injected taps but doesn't scroll for injected swipes.

**A frame without touch isn't graded on scrolling** (rubric 2, the maintainer, 2026-11-23). Nobody can scroll a
Mirror, so the scroll task doesn't apply to it. A frame says it has no touch in its frame data:
`frames/<id>/design/installer.json` `"touch": false` (the Mirror's), the same field Kiln reads, or its base frame's
(`design/frame.json` `base`); a frame that doesn't say, or that the repo doesn't know, has touch
(`perflib.frame_touch`). Then:

- The runner records it (`device.json` `touch`) and sends the graded views no swipes (`swipes=0`, as the HLS copies
  already get). The taps go as before: they reach the page there.
- The scroll is `n/a` on every page (`rubric.json` `grade.no_touch`), which every level accepts. It never holds a
  page back and is never missing, so it makes nothing provisional, and nothing about swipes (none during the live
  updates, rAF only, lost scroll steps) is a reason either. The letter comes from the other tasks: taps, live
  updates, opening the view, the blocking after it, cameras and more-info dialogs.
- Every other task is graded as on any frame. Missing evidence for one of them still makes the grade provisional.
- report.json says so: `frame.device.touch` is `false`, each page's `grade.bands.<page>.scroll` is `n/a`, and
  `grade.not_applicable` is `{"scroll": "this frame has no touch"}`. report.md says it under the notes, "Scrolling:
  not applicable, this frame has no touch.", and its table's scroll cells read `n/a (no touch)`. The summary carries
  `touch: false` (only on such a frame), so `grade.ts` grades it the same way.
- The headline says nothing about scrolling; an A+ there says every *graded* task holds 60 fps on large dashboards.

A frame with touch is graded exactly as before, and a missing scroll there is still "not measured" and provisional.
This isn't a new protocol: on every frame with touch the runner does what it did, record for record, and on the Mirror
the swipes it no longer sends never scrolled. A protocol marks results whose numbers mean something else, and no stored
result's do. #643 changed what the device does too (a SurfaceFlinger read every 0.3 s during each window on the
tablet), within its protocol, for the same reason. A bump here would send the S1's and the Pixel Tablet's results to
"re-measure" for a change that doesn't touch them. No frame without touch has a stored result.

**Portrait.** The pages place their taps and swipes from the page's own size (`innerWidth`, `innerHeight`,
`devicePixelRatio`): the swipe runs down the middle from 85 % to 15 % of the height. Nothing assumes landscape.
The runner checks each request against the screen's current size, turned as it is shown (`cur=` in
`dumpsys window displays`), which is what `input tap` takes. `wm size` gives the panel's own size, unturned: the
Pixel Tablet's `1600x2560` while it shows 2560 × 1600, against which a tap right of x 1600 would be refused.

### Vitro

Vitro, the experimental native renderer (**Settings → Labs → Vitro**), draws Home Assistant's dashboards with
Android views and runs no web page, so the harness can't run inside it. Vitro carries its own copy of the
harness instead: `VitroClarity` (`engines/engine-vitro`, `clarity/`). It speaks the same protocol with the
fake Home Assistant (`/clarity/next`, `/clarity/live`, `/clarity/time`, `/clarity/still`), reads the same
page config (`/clarity/config.json`, the one the web harness gets in its script), runs the same phases and
writes the same `GWPERF` lines, with every timing rule ported from `stats.js` (`ClarityStats`, held to the
same test cases). `perfrun.py`, `report.py` and the grade read a Vitro run as they read a web one. The rubric,
the bands and the letters are the same.

It runs only while the app's test hold shows the reference dashboard (`/clarity-ref/…`) on the test
dashboard's loopback address. It checks the hold twice a second, and stops when the hold ends, whatever it was doing. At any other time it doesn't exist, and each hook in Vitro is a single check
for it.

**Running it.** `tools/perf/run.sh --frame aqara-s1-plus --engine vitro …`. Without `--engine`, the runner
grades the engine the app says it runs. The app says that in its answer to the test hold (`test hold engine:
<name>; vitro setting on|off`), and an app that doesn't say is graded as the web engine, as before. On Vitro the
animation test page and your own dashboard's restarts are skipped: both time GeckoView's page loads, and both are
detail, not graded.

**What each task measures on Vitro.** Each task starts and ends where it does on the web engine:

| Task | Web engine (the harness in HA's frontend) | Vitro (`VitroClarity`) | Same? |
|---|---|---|---|
| Open the view | HA's `navigate()`, to the first screen drawn: two animation frames with the head's 10 cards and every card on screen laid out, the layout still | The view tab's own switch, to the same first screen by the same rule (`usableStep`), on `Choreographer` frames | Yes |
| Blocking after it | Each animation frame's time past 50 ms, from drawn until tasks posted back to back run within 50 ms for 500 ms | The same, with tasks posted to the main thread's looper | Yes |
| Restart | Process start to the start view usable | The same | Yes |
| Live updates | From the fake's send (its schedule, tied to the fake's clock) through the main thread reading the change and the card drawing it, to the first SurfaceFlinger present that can show it | The same: the store hands each change to the main thread, the card renders it, and the next frame draws it | Yes |
| Tap | Event Timing: from the event's timestamp to the next paint after its handlers, worst of 10, rounded to 8 ms, at least 16 ms | From the touch's own timestamp to the end of the first frame drawn after its handlers (the click included), worst of 10, rounded the same way (`method: vitro-frame`). A tap counts with both its down and its up timed; one whose up or frame hasn't come 2 s after the taps is given no time, and the run's tap is then not measured (`incomplete`) | Yes, by its definition |
| Scroll | Six real swipes during the live updates, SurfaceFlinger and rAF | The same swipes, SurfaceFlinger and `Choreographer` frames, with the same room to flick (the view's content at least 4 screens high) | Yes |
| More-info dialog | HA's dialog, from the tile's event to its show animation finished and the sensor's graph drawn | Vitro's own sheet, opened as a tile's tap opens it, to its enter animation finished and the sensor's graph drawn with its data (the fake answers Vitro's `history/history_during_period` with the same seeded points as HA's `history/stream`). Like HA's dialog, the sheet draws a sensor's statistics only when HA's config lists `recorder`; the fake's doesn't, so both draw its state history | Yes; the sheet is Vitro's (its animation is 240 ms, HA's is 300 ms) |
| Cameras | From the navigation to each stream's first frame; over a fixed 30 s window from the view going live, beside the updates and the taps, the frames shown and the stalls (the WebRTC receiver's counts) | The same window, from each stream's own reports: its first picture on screen, and every picture it drew | Yes, by its definition: see below |

**Cameras on Vitro.** Vitro plays a camera the way HA's frontend does: WebRTC first, then HLS, then MJPEG, each
behind one interface (`CameraStream`). Every stream comes from a factory the engine wraps with
`ClarityCameras.measured`, so each one tells Clarity what it does, whatever its kind and whatever the stream
choice does with it. Each camera task starts and ends where the web harness's does (`harness.js` `trackCameras`,
`camerasStart`, `camerasEnd`), and the `camera` line has the same fields:

| | Web engine | Vitro (`ClarityCameras`) |
|---|---|---|
| Which cameras | The video elements in the view's cards | The streams that played in the view's cards (a card that holds the stream's picture), opened or still open since the navigation. A stream belongs to its view, not to its card, so a view that builds its cards again mid-window (a late state) keeps its camera's history. Only what happened by the window's end counts: a stream, a picture or an error after it is not the window's, however late the line is written |
| Graded path | WebRTC (`CAMERA.path`); HLS on a fallback copy | The same. A camera that never played on the view's path is a stream not found, which fails the task, as on the web engine |
| First frame | From the navigation to the first presented frame (`requestVideoFrameCallback`) | From the navigation to the stream's first picture on screen (`onFirstFrame`, HA's `hasVideo: true`) |
| The window | 30 s (`CAMERA.windowMs`) from when every camera showed a frame, or 20 s after the navigation; the swipes come after it | The same |
| Frames shown | The receiver's `framesDecoded` against 15 fps × the window | The pictures the stream drew (`onFirstFrame` and each `onFrameRendered`) against 15 fps × the window |
| Stalls and longest gap, WebRTC | The receiver's freezes and pauses, and its intervals with nothing decoded, from `getStats` about once a second | libwebrtc's freeze rule applied to each gap between two pictures (`ClarityStats.freezeGaps`): every gap joins the mean of the last 30 (whole ms, rounded down), and from the 5th on one of at least max(3 × that mean, the mean + 150 ms) is a freeze, counted for its part inside the window. The time from the last picture to the window's end counts when it already meets the rule, as a sample with nothing decoded does; the time from the window's start to the first picture, when none came before, counts past 200 ms. The longest gap is the longest stretch without a picture, which the pictures give outright where the receiver's samples give the rule's bound |
| Stalls, HLS fallback | Gaps over 500 ms between frame callbacks | Gaps over 500 ms between pictures (`ClarityStats.videoGaps`) |
| Left out of the window | What the receiver's counts didn't cover (`uncovered_ms`) | Nothing: every picture is counted as it comes (`span_ms` is the window) |

The rubric's bars and levels are the same, and so is the rule for a missing measurement.

What each camera played is recorded: each stream's `transport` (`web_rtc`, `hls` or `mjpeg`, the kind it
showed at the window's end), its other kinds in `others` (with their first frame), and the errors its streams
reported (`errors`). The run's file keeps each run's `transports` and `fallbacks`, one per camera, and the kinds
fallen back to in `fell_back`. A camera fell back when another kind was what it showed while its view's path
wasn't: the kind showing at the window's end, or, for a camera that never played on its path, another kind that
showed a picture. The hidden player of HA's side-by-side start (HLS shows until WebRTC has video) isn't a
fallback once WebRTC played. report.md says it plainly, page by page and camera by camera: "heavy cameras on
Vitro: over WebRTC, 3 of 4 cameras in each of 3 runs; **fell back to HLS, 1 of 4 cameras in each of 3 runs**".
What a fallback does to the grade depends on whether the camera played on its view's path first:

| The camera | Its camera task, graded on the view's path (WebRTC) |
|---|---|
| Never showed a WebRTC frame, and showed another kind instead | Fails, as a camera that doesn't play over WebRTC does on the web engine. The page's limit says "a camera fell back to … on Vitro (it never played over WebRTC)" |
| Showed WebRTC frames, then fell back to another kind | Graded on its WebRTC frames, under the same bars: the first frame stands, and the time after WebRTC stopped has no WebRTC frames, so it counts against frames shown, and as a stall when that gap meets the freeze rule (above) |

Two differences, and what they mean:
- **The pictures are timed on the main thread.** A stream reports each picture through its listener on the main
  thread, so a picture is timed when the main thread gets to it, not when it reached the screen. A busy main
  thread would read as gaps the video didn't have, as `requestVideoFrameCallback` does on the web engine. Vitro's
  main thread is free after a view is drawn (its blocking was 0 ms on every S1 page), so this costs little in
  practice. The frames shown aren't affected: a late call still counts.
- **The freeze rule comes from libwebrtc, not from its counts.** On the web engine the receiver counts its own
  freezes; Vitro applies the same rule to the pictures it drew. That the new gap joins the mean before the check
  is how `video_quality_observer2.cc` reads to us (**untested** against libwebrtc itself; the bars aren't close
  to the cases where it matters).

A Vitro build whose cameras don't report (from before `ClarityCameras`, or one whose engine doesn't wrap its
factories: `ClarityCameras.wired`) logs no `camera` line. Then it played its cameras over MJPEG only, and the
task is missing, under the same rules as any missing measurement: it fails whatever needs it, and the grade is
provisional when the camera could change the letter. The report says why (`typical, heavy: cameras not
measured on Vitro (this build's cameras don't report to Clarity …)`).

**Frames on screen.** Vitro draws into the app's own window, not into a SurfaceView, so on Vitro the runner reads
that window's layer (`perflib.window_layers`: `<package>/<activity>#0` on Android 10). A layer's history length
is SurfaceFlinger's own, the same for both layers on the same Android.

The SurfaceFlinger check before the run (`--sf-poll auto`, the default) doesn't work on Vitro yet. It is meant to
open its page in Vitro's GeckoView fallback and read the SurfaceView, but Vitro takes the page's address for a
dashboard (`LovelaceModel.isDashboardUrl` sends only Home Assistant's own panels to the fallback) and tries to draw
it natively, so the check finds no frames and stops the run. Until Vitro sends that page to its fallback, give
Vitro runs the reading the check would pick: `--sf-poll 0` on the S1, and `--sf-poll 0.3` on the Pixel Tablet.

**The clock.** Vitro's lines are timed on the device's monotonic clock (`clock: "monotonic"`, ms after
`origin_mono_ms`). That's the same clock logcat and SurfaceFlinger use, so `perflib.page_to_mono_ms` ties them
exactly, where a web page's lines are tied through `Date.now()` to within a few ms.

**Records.** `device.json` and the report's `frame.device` carry `engine` (`web` or `vitro`), and report.md says
"measured on Vitro". A Vitro report's summary carries `engine: "vitro"`, so a Vitro result is never shown as the
frame's web result. A web summary has no `engine` and stays as it was. `summary.py` and `regrade.py` refuse to write a summary of
another engine as a frame's published `clarity.json`, which the site shows as the frame's grade; written under any
other name, it keeps its `engine`. The site shows no grade for a frame whose summary is another engine's
(`frameGradeSummary`): publishing a Vitro grade is the maintainer's call. Vitro's restart reports engine ready at
Vitro's start (`Starting Vitro` in logcat; `engine_uri` `vitro`), as detail.

## The reference dashboards

Since protocol 4 they are **Home Assistant's real frontend**: the `home-assistant-frontend` release
**20261028.5** (Apache-2.0), the build Home Assistant core serves. `tools/perf/hafrontend.py` fetches
its wheel from PyPI on first use and checks it against a pinned SHA-256, like Magisk; it isn't in
the repo (117 MB). Its files are only served to the panel, never run on your computer. The licence
is in `THIRD-PARTY-NOTICES.md`.

**The fake Home Assistant** (`tools/perf/fakeha.py`, standard-library Python) serves the frontend over
HTTP and answers its websocket API: HA's sign-in flow (without the login page), the 27 commands this
release sends while it loads and runs a dashboard (recorded from it, `test/fakeha-traffic.json`),
`subscribe_entities` with fixed states, and the live updates. The dashboard, states, history and
pictures come from `tools/perf/dashboards.py`, seeded, from the counts in `tools/perf/page/tiers.json`
(pinned by `test/page.test.mjs` and `test/test_pages.py`). The counts are **project choices**, fixed
before any frame was graded under protocol 4.

**HA's icons.** Home Assistant core sends the frontend the icons of each integration it has loaded
(`frontend/get_icons`), and the frontend draws an entity's default icon from them on every card that
names none: a bulb for a light, a bulb with a slash when it's off, a thermometer for a temperature
sensor. The fake answers it the same way, from `tools/perf/haicons.json`: the `icons.json` of each
integration it loads, taken from Home Assistant core's source at the release it reports (2026.11.0,
pinned by commit, Apache-2.0) by `tools/perf/haicons.py`, which also checks the kept files against
that source. Its answers match what HA core 2026.11.0 itself builds for the same integrations, for every
category the command takes. The frontend draws each of these icons through `ha-icon`, which fetches
the Material Design Icons it needs from the frontend's own `/static/mdi/` files and caches them in the
browser, as it does on a real HA.

**One dashboard, four views.** `clarity-ref` is one Lovelace dashboard, as a real panel's is. Its
views are the **start** view (`/clarity-ref/start`), where the kiosk comes back to after a restart,
and the three **reference views** (`/clarity-ref/light`, `typical`, `heavy`), which the grade is
measured on. A view opens inside the loaded frontend, as a view tab or a navigate action opens it
(see [Open the view](#open-the-view-a-navigation-in-the-loaded-frontend)).

| View | Cards | What |
|---|---|---|
| **Start** | 23 | The light view's cards, plus a history graph, a picture and a 360p live camera (`START` in `tiers.json`) |
| **Light** | 20 | 12 tiles, 4 entities cards of 5 rows, a glance, 2 sensor cards with a graph, a clock |
| **Typical** | 50 | 26 tiles, 11 entities cards of 6 rows, 2 glances, 6 sensor cards, 2 history graphs, a picture, **a 720p live camera**, a clock |
| **Heavy** | 120 | 50 tiles, 22 entities cards of 8 rows, 4 long lists of 20 rows, 4 glances, 16 sensor cards, 8 history graphs, 13 pictures, **a 720p live camera and a grid of three 360p**, a clock |

Every card is one of HA's own types (`tile`, `entities`, `glance`, `sensor` with its graph,
`history-graph`, `picture`, `clock`), in HA's masonry view, with HA's default theme. The pictures are
generated PNGs the fake serves, and the graphs' history and statistics follow the window each card
asks for, so they look the same on any date. In headless Chromium at 960 CSS px (2026-11-13) heavy
builds about 8000 elements, counted through every shadow root; protocol 3's imitation of it had 4463.

**Why the start view has a history graph and a picture.** HA loads the code of some card types only
when a card of that type first appears (`create-card-element.ts` at the pinned tag: `history-graph`
L86, `clock` L94, `picture` L100; tile, entities, glance and sensor are always loaded). On a panel,
that happens once, after the frontend loads. With the start view holding every type the reference
views use, the cold load pays for that code, inside the restart, and opening a view measures the
view, not the first download of a card's code. In smoke9 (8c0027b, 2026-11-13), each page loaded
the whole frontend, and typical, the first page in the order with a history graph and a picture,
opened in 8.2 s, against heavy's 5.0 s. Its first screen was drawn at 5.2 s (light 4.5 s, heavy
5.0 s), but its main thread stayed busy until 8.2 s.

**The first screen is the same on every view** (`HEAD` in `tiers.json`): the clock (with seconds),
the "Living room" toggle (a tile whose tap toggles its light), 4 sensor tiles, 3 on/off tiles (two
lights and a switch), and an "Energy" entities card of 5 sensor rows: the **head**, the first 10
cards of every view's config. The rest of the view comes after it, shuffled. Only the head changes
while the view is live, so every view gets the same update stream; the views differ in how much is
around and below it.

**Room to flick (protocol 2 and later).** Every page leaves at least 3 screens of scrolling below its
top, so no swipe reaches the end. On HA's frontend the harness does it with one style in
`hui-root`'s shadow root (`#view { min-height: … }`), after the page has loaded: light's 20 cards
would otherwise end within about one screen. Before protocol 2, light could scroll less than one
swipe travels (about 560 px on the S1), so every light swipe hit the end of the page and read worse
than heavy.

No header: the kiosk hides HA's header and sidebar, as it does on your dashboard. The page scrolls as
a document, so a touch scroll goes through GeckoView's compositor.

**Live updates, as Home Assistant sends them.** The fake sends each change the way HA core does: a
`subscribe_entities` event with the entity's new state, a new context id, and new `last_changed` and
`last_updated`. Read from HA's frontend at
[`77170b4`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67):

| What HA does | Source | So |
|---|---|---|
| Every state change reaches the dashboard as it arrives: no throttling in the frontend | [`connection-mixin.ts` L269](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/state/connection-mixin.ts#L269) | One event per change |
| Only the card whose entity changed re-renders | [`has-changed.ts` L67–L86](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/common/has-changed.ts#L67-L86) | The harness watches only the first screen's cards |
| A tile's colour comes from whether its entity is active. A sensor with a value is always active, so a value change doesn't change its colour; an on/off change fades the icon over 180 ms | [`hui-tile-card.ts` L198–L233](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/cards/hui-tile-card.ts#L198-L233), [`ha-tile-icon.ts` L125–L143](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/tile/ha-tile-icon.ts#L125-L143) | A sensor change has no transition; an on/off change has 180 ms (`tileTransitionMs`) |

The stream: **5 sensor value changes a second** over the first screen's 4 sensor tiles and 5 sensor
rows, and **one on/off change every 2 s** over its 3 on/off tiles. HA's clock card ticks by itself.
The rates are **project choices**. 5 a second is a busy home: dozens of power and energy sensors that
report every 5 to 10 s add up to a few changes a second on one view, and those are value changes.
Lights and switches change on someone's action or an automation, far less often. One every 2 s is
still more than most homes see; it is there so every window has an HA tile fade in it. The changes go
out at fixed times, so a late one doesn't push the next ones back, and every value step is non-zero
(a zero step is no change for HA, and nothing would be drawn). The view starts the stream when it
goes live and stops it when it is done (`/clarity/live`, `/clarity/still`), so the next view never
opens over one that is still updating. Each start is a new **generation** of the stream, and each
change's context id carries its generation and its number (`01JC1ARITY` `S`/`F`, 5 digits, 10
digits: 26 characters, as HA's ULIDs; the frontend treats it as opaque). So the harness knows when
the fake sent each change, and never mistakes a late one from the view before for this view's. When
a stream stops, and before the next view opens, the fake puts every entity the stream or a tap
changed back as it started (`Hub.reset`), told to the frontend as any change is. So every view starts
from the same states, and its stream sends the same values. The fake logs how late it sent each
change (`fakeha.jsonl`) and puts that in the change's context id too, so each change is timed from
when it actually went out, and a late send (smoke10: one, 92 ms, on the Mac that ran it) doesn't
count against the panel. The report is provisional only if a send was more than one update
interval (200 ms) late, when the stream's own rhythm wasn't the protocol's.

**The order.** Each graded run starts with a restart: the kiosk comes back to the start view, which
is the restart's measurement and also takes the cold caches, the first JIT and every card type's
code. So protocol 4 has no separate warm-up page (protocols 1 to 3 opened one; `--warmup-tier` can
still add a view, but not one that is also graded: hui-root keeps a view it has shown, so its graded
open would skip building it, and the runner refuses that). Then the four views open in an order that turns by one each run: run 1 light,
typical-nocam, typical, heavy; run 2 typical-nocam, typical, heavy, light; run 3 typical, heavy, light,
typical-nocam. So no view always comes
first, and the report gives each view's results at each place in the order. Each view opens from
the view before it (the start view for the first), as you would tap from one tab to the next. The
runner waits for the screen to be still for 2 s first, at most 10 s. On HA's dashboards that wait
never ends early: the head's clock card shows seconds, so a frame is drawn every second. So each view
opens 10 s after the view before it ended, and every run's `between` reads `moving` (s1v7, all
three runs). The 10 s stays, as s1v7 was measured with it: by then the view before has stopped its
updates and its last work.

Each view runs in phases, one log line per event:
1. **Open the view.** The harness navigates to it, reports when its first screen was drawn (a
   `drawn` line, at once), then when it was usable (`refdash`: the main thread free, and the
   blocking in between).
2. It stays still for 2.5 s after it was usable (`stillAfterUsableMs`), as you look before you touch.
3. **Live updates.** It goes live: a warm-up of 1 s, then a 4 s window in which each change is timed to
   the screen (about 22: 20 sensor values and 2 on/off changes). The first 100 display refreshes (1.67 s at 60 Hz) of it also give
   the frame pacing (detail).
4. **Tap.** 10 taps on the toggle while the updates run. The fake toggles the light and sends the
   change back, as HA does.
5. **Scroll.** 6 swipes while the updates still run. Before each, the view jumps back to its top and
   waits 0.5 s still; each swipe is then the same flick down the view, from 85% to 15% of the
   screen's height in 300 ms. The fling is included.
6. **More-info dialogs** (#211). Back at the view's top, while the updates still run, HA's
   more-info dialog of a light, a thermostat and a sensor with its history graph, one after the
   other: each opened as a tile's tap opens it, timed until it has settled, read for 1 s, closed again
   and followed until HA says it closed. See [The more-info dialog](#the-more-info-dialog-211).
   On the S1 they add about 10 to 15 s to each view (an estimate from the waits, not yet timed on a
   panel).

### How Clarity measures HA's frontend

The harness (`tools/perf/page/harness.js`) is the first script in HA's `index.html`: the fake puts it
right after `<head>`, with the frame stats (`stats.js`) and the config from `tiers.json`, at a URL
that carries a hash of all of it. It changes nothing HA does except the room to flick. It runs as
long as the frontend does: it reports when the view it landed on was usable (the restart's end, with
no nonce), then waits for the runner's commands and measures each view it is asked to open. It asks
for animation frames only while a view is measured, so between views and at idle it adds none.

#### Open the view: a navigation in the loaded frontend

On a wall panel HA's frontend loads once (after a restart), and opening a view is a navigation inside
it: a view tab, or a navigate action on a card. Both end in HA's `navigate()`
(`src/common/navigate.ts`, `performNavigation`, at the pinned tag): `history.pushState`, then a
`location-changed` event on the window. `hui-root` reads the route and swaps in the view
(`hui-root.ts` `_selectView` L1289–L1347: the old view's element comes out of `#view`, the new
view's `hui-view` goes in, cached if it was shown before in this load; its cards are built when it is
first shown). The harness calls that same pair, so the frontend can't tell it from a tab. Each view
is shown once per run, so none comes from the cache. Open the view runs from the navigation to the first screen drawn, and the wait from there to usable is
graded as blocking ([Usable](#usable)); the first v1's rubric 1 (2026-11-14) ran the open to usable. The frontend's cold load is the restart's, and
is graded there.

Protocol 4 as first written (8c0027b, smoke9) loaded the whole frontend for each reference
dashboard and called that "open the view". That counted the cold load twice (it is the restart too),
judged it against a page-load bar, and let first-use code downloads land on whichever page came
first (above).

#### Usable

Usable is when HA has drawn the view and would answer a touch at once. Two parts, both rules in
`stats.js` with a node test for each clause (`page.test.mjs`):
1. **Drawn** (`usableStep`): the first of two animation frames in a row in which HA's launch screen
   is gone, `hui-root` shows the asked view (`hui-view.index`), every head card (the view's first 10
   configured cards, from the layout's own list, so one HA hasn't put on the page yet counts as not
   drawn) and every card on the screen has its `ha-card` laid out (`offsetHeight > 0`), and the
   layout hasn't moved. The report also checks the first screen held at least the 10 head cards.
2. **Free** (`freeStep`): from then, the main thread runs every task posted back to back within
   50 ms (RAIL's input budget) for 500 ms (`freeWindowMs`, a project choice); usable is when that
   stretch began. A single quick task isn't enough: HA builds the cards below the first screen in
   many tasks, and one probe can land in a gap between two long ones. In smoke9 heavy's single probe
   passed 47 ms after its first screen was drawn while typical's main thread stayed busy for 3 s.
   500 ms is far shorter than Lighthouse's TTI window (5 s quiet): it asks that a touch now, and the
   next, would be answered, not that HA has finished every card below the fold.

On the screen, usable is the first frame SurfaceFlinger presented that can carry the drawn frame (one
refresh after it, less 3 ms for the clock mapping), and never before the main thread was free.

**What the grade takes from this.** *Open the view* is the navigation to part 1, the
first screen drawn, on screen: the paint LCP itself times. Part 2 is graded as its own task,
*blocking*: from the draw to usable, each animation frame's time past 50 ms, added up
(`stats.js` `frameBlocking`, recorded as `usable.blocking_ms`). That is Lighthouse's Total Blocking
Time ("adding the blocking portion of all long tasks between First Contentful Paint and Time to
Interactive", the part of each task past 50 ms), measured on frames: Gecko has no Long Tasks API
([bug 1348405](https://bugzil.la/1348405)) and no Long Animation Frames, and it runs the refresh
driver's frames ahead of ordinary tasks (s1rtc4's heavy: its timers waited 140 s while frames came
5 to 8 a second), so a frame comes late by the length of the task that held it, not by a queue of
short ones. Usable itself stays in the report as `usable_ms`, not graded.

It is TBT's shape on frames, not TBT itself, and it differs in three ways (#264's verifier,
2026-11-14, in Chrome, where both can be read):
- **It counts rendering.** A frame is late whatever held the main thread: script, style, layout or
  paint. Chrome's TBT counts tasks, and leaves out paint: a page that only repaints a blur measured
  683 ms of frame blocking against a TBT of 0. Kept on purpose (root decision, 2026-11-14): rendering
  on the main thread delays a tap as much as script does. So on a rendering-bound page this is the
  stricter of the two.
- **It is as fine as a frame.** For script tasks it tracks Chrome's TBT within about one vsync per
  long frame (a little under). A task of 50 to 67 ms can fall inside one frame interval and be
  missed.
- **A frame that isn't drawn counts.** The browser stops animation frames for a page that can't be
  seen, so a system dialog over the page would count as blocking. The runner already refuses runs
  with the screen off, and the reference views have nothing that opens a dialog.

Drawn and usable are logged as they happen, each on its own line (protocol 2: a `drawn` line, then
`refdash` once free). So a view that is drawn but never free keeps its open time (see "A view the
panel can't finish", below).

#### Live camera streams (#174, protocol 5)

Camera streams are a normal part of a wall panel's dashboard (the maintainer, 2026-11-13), so the
typical and heavy views have live camera cards, right after the head, on the first screen:

| View | Streams |
|---|---|
| Light | none |
| Typical | one `picture-entity` card with `camera_view: live`, 1280×720 at 15 fps |
| Heavy | one 720p card, and a grid of three 640×360 cards (a main stream and sub-streams) |

They take the place of picture cards, so the card counts stay 20, 50 and 120. The start view has one
360p camera, so the cold load brings in the camera player's code, as it does every lazy card type.

**What the fake serves.** HA's frontend asks `camera/capabilities` for a camera with the STREAM
feature and plays what it offers: WebRTC first, then HLS, else MJPEG (`ha-camera-stream.ts` at the
pinned tag). The fake offers HLS, as HA's `stream` integration does, and lists `stream` among HA's
components (`ha-hls-player.ts` refuses to play without it). `camera/stream` starts a fresh stream
(`/api/hls/<token>/master_playlist.m3u8`), whose live playlist is a window of the last four whole
1 s segments. Since protocol 7 the stream starts when the frontend asks, as HA's does by default (no
"preload stream": `camera/prefs.py`), and the master playlist answers once two segments are made,
as HA's `HlsMasterPlaylistView` waits (`stream/hls.py`, HA 2026.11.0). Protocol 5 started it as if
the camera had been streaming for 4 s, which on the WebRTC views let HA's HLS players start before
WebRTC and take the panel's hardware decoders (#202, smoke 7). The segments are H.264 Main, fMP4,
made once by `tools/perf/camera.py` with ffmpeg from its moving test pattern (`testsrc`, with its
burnt-in counter): no video is in the repo, and the same ffmpeg makes the same bytes every time
(bitexact, one encoder thread; `manifest.json` records the version and every file's SHA-256). The loop
is 120 s; past it the playlist wraps with `EXT-X-DISCONTINUITY`. Since protocol 6 the graded cameras
play over WebRTC, and HLS is the fallback (next section).

**Which player.** `ha-hls-player.ts` uses hls.js on MSE when the engine supports H.264 there
(`Hls.isSupported()`), else the browser's own HLS, and ExoPlayer only in HA's companion app (not
the kiosk). On a panel that is hls.js on MSE in GeckoView, ending in the device's hardware decoder.
Checked in Chrome with H.264 (2026-11-13): the frontend took hls.js on MSE (`blob:` sources) and
played every stream. GeckoView on the S1 takes the same path (smoke-cam1, 2026-11-13, verified):
`video_caps` read `mse_h264: true`, `native_hls: "maybe"`, `rvfc: true`, so HA's player picks
hls.js on MSE, and each stream gets the hardware H.264 decoder (below).

**What is measured.** For each stream, by the harness, on the views with streams:
- **First frame**: from the navigation to the stream's first presented frame
  (`requestVideoFrameCallback`, or `loadeddata` without it).
- **Frames shown**: over the camera window, a fixed 30 s from the view going live (`CAMERA.windowMs`; the updates window and the taps run in it, with the cameras on screen, and the
  swipes come after it), the frames the decoder presented against 15 fps × the time:
  over WebRTC the receiver's `framesDecoded` (`getStats`: the frames the decoder handed to the
  renderer; its `framesDropped` are frames that never came out of the decoder, so they aren't
  subtracted, below), over HLS
  `getVideoPlaybackQuality` (total less dropped). Gecko fills `getVideoPlaybackQuality` only for an
  element with a media decoder, so for a WebRTC video it reads 0 (#202's verifier; seen in Firefox
  through the harness, 2026-11-13: 0 there, 75 from the receiver). A 0 while frames arrived counts as
  not reported, and `requestVideoFrameCallback`'s callbacks count instead (`stats.js` `shownFrames`;
  each stream's `source` says which). The swipes scroll the cameras away, and an engine
  may rightly stop decoding a video no one can see, so they aren't in this window; scrolling is still
  measured with the streams playing. (`requestVideoFrameCallback` can fire once for two frames that
  land in one rendering update: in Chrome, 60 callbacks for 77 decoded frames. So it is used for the
  first frame and the gaps only.)
- **Gaps and stalls over WebRTC (graded)**: the receiver's own, from `getStats` samples
  about once a second (`stats.js` `webrtcWindow`). Per interval between two samples: if no receiver
  decoded a frame, the whole interval is a stall; otherwise each receiver's new freezes and pauses
  are stalls at their full length, less the time with nothing decoded just before, which they cover
  too. A freeze is libwebrtc's (`VideoQualityObserver`, read in Firefox's tree,
  `third_party/libwebrtc/video/video_quality_observer2.cc`, 2026-11-13): a gap between frames
  handed to the renderer of at least 3 × the mean of the last 30, or the mean + 150 ms, counted at
  its full length. A pause is the stream going inactive. The longest gap is the longest freeze,
  pause or run with nothing decoded (two in one interval count as their sum, an upper bound).
  Without any, every gap was under the freeze rule, and the longest gap is that rule's bound for
  the window's mean interval (217 ms at 15 fps; `gaps_source` `webrtc (bound)`). Each stream's
  `stalls_at` says where they were. One outage a receiver reports as both a freeze and a pause in
  the same interval counts once, the longer of the two; different receivers add up. The intervals
  run on the inbound reports' own `timestamp`s (Gecko reads the counts on its call thread right
  after the ask, `RTCRtpReceiver.cpp`; a busy main thread delays only the answer, by up to
  `statsWaitMs`, 10 s, at the window's end), so a late answer counts nothing past the time its
  counts were taken, and frames shown are over that same span (`clock`: `stats`, or `asked` when a
  sample has no timestamp). A receiver that reports no `freezeCount` has stalls and gaps `n/a`
  (`stalls_reported` false), never 0. So does a stream whose counts leave more than 500 ms of the
  window out (`uncovered_ms`; the longest gap "good" allows): a busy page answers `getStats` late,
  and the counts end at the last answer back, so a stall after it would go unseen (#255; on
  s1rtc9's heavy the counts covered 5.8 to 24.7 s of 30). Such a run's camera level isn't measured,
  and the report says so, unless its first frame or frames shown already fail every level: then
  it is `slow`, as no stall could lift it. When the counts leave out 500 ms or less their stalls
  count, but they can be under-read by up to that much, so "fluid" holds its 1 s bar against the stalls
  plus what was left out: 600 ms of stalls with 400 ms left out is not measured, with 399 ms it is
  fluid. "Good" likewise holds its 500 ms gap bar against the longest gap plus what was left out, as a
  gap open at the counts' end could run on into it: a 450 ms gap with 100 ms left out is not
  measured, 400 with 100 is good. That uses the longest gap anywhere, not only one open at the end
  (the counts don't say which), so it can leave a level open that was good, never grade one wrongly. report.md's camera cell says in how many runs the stalls were n/a or the level open.
- **Gaps and stalls over HLS (the fallback, not graded)**: gaps over 500 ms between frames
  presented by `requestVideoFrameCallback`, the longest and their sum, and the `waiting` events.

**Why the receiver's and not the page's frame callbacks** (#202, s1rtc4). On the S1's
heavy view the page's frame callbacks ran 127 times in a 147 s window: 132 s of "stalls", with no
gap over 4.8 s. In the same window the receiver decoded 2198 frames (99.3% of 15 fps) with 2
freezes, 0.49 s in all, and the compositor presented 1866 frames inside the callbacks' "stalls"
(`presentedFrames`). The maintainer, watching the panel, saw all four test patterns' counters
ticking smoothly. A busy main thread delays the callbacks, not the video, which the compositor
shows on its own thread. The callbacks' gaps stay as detail (`callback_gaps`: how busy the page
was). Checked in Chrome (`webrtc.e2e.test.mjs`): with the page blocked 900 ms of every second through
typical's camera window, the callbacks read 25 s of stalls and the receiver none, 450 of 450
frames.
- Scroll, taps and live updates run with the streams playing, as on a panel showing a camera; RAM and
  CPU per process too.

Each run's camera numbers are its **worst** stream's. A stream that never showed a frame (or wasn't
found) fails the task: it was measured, and the panel didn't play it.

**Detail, to tell a slow camera's cause apart** (not graded; added after smoke-cam1):
- **The decoders each view got** (`media` in each run's camera detail), from the logcat the runner
  already reads: the codec name when Android's `MediaCodec` puts one on a surface
  (`[<name>] setting surface generation`) or `CCodec` allocates one (`allocate(<name>)`: Codec2,
  where Android 10's software `c2.android.*` decoders run). On the S1 also the Allwinner decoder's
  flushes (`omx_vdec_aw`: `decoder flush`) and end-of-stream drains (`omx_vdec`: `found eos flag`):
  Gecko drains and flushes a decoder whose stream ran out of data. Names and counts only.
- **The segments' fetch times** (`fetches` in the camera line, `stats.js` `hlsFetches`), from
  Resource Timing during the window, through a `PerformanceObserver` (HA's frontend has already
  filled the page's resource buffer by then): for segments and playlists, `queued_ms` (fetch start
  to request start: waiting for a connection), `wait_ms` (request to first byte: the fake and the
  link), `total_ms`, the kilobytes, and the longest gap between two segments of one stream arriving.
  This assumes hls.js fetches from the page's main thread (its default loader); untested on the panel.

To read the decoders yourself (read-only):
`adb logcat -d -v threadtime -s MediaCodec CCodec OMXNodeInstance GeckoMediaManager omx_vdec_aw omx_vdec`.
`OMXNodeInstance` names each hardware instance (`[0x…:allwinner.decoder.avc]`), and
`GeckoMediaManager` logs when GeckoView's media process stops (`Media service has been unbound`),
which frees its decoders.

**First panel run: smoke-cam1** (#182 at 59ffd21, the S1 app 0.4.3, `thread_router`, 2026-11-13,
03:44 to 03:49 by the panel's clock; **1 run, not calibrated**). The run exited 0, the restore
worked, and the fake knew every camera command (7 `camera/capabilities` and 7 `camera/stream` calls, none unknown or failed; its
live changes at most 23 ms late).

| View | Streams | First frame | Shown | Stalls | The view's other tasks, with the streams |
|---|---|---|---|---|---|
| typical | 1 of 1 (720p) | 4.4 s | 100% (362 decoded, 0 dropped) | none; longest gap 283 ms | scroll 23.7 fps (s1v7 without streams: 35.1); opens in 6.6 s (4.8) |
| heavy | 4 of 4 (720p, 3 × 360p) | 3.8 to 4.1 s | 28 to 42% | 25.6 to 29.8 s of gaps over 500 ms; longest 4.6 to 7.1 s; 13 to 18 `waiting` | scroll 5.6 fps (23.3), p95 283 ms; a change takes 1433 ms (156); opens in 33 s (14.5); tap 272 ms (88) |

The grade stays D: light has no streams, and typical and heavy were already below fluid on other
tasks. The maintainer predicted streams would hurt (#174); on heavy they do.

What the panel's logcat says about heavy (read 2026-11-13, read-only):
1. **Every stream had the hardware decoder.** Heavy's four streams got four instances of
   `OMX.allwinner.video.decoder.avc` (four node ids) within 0.4 s. There was no Codec2 software
   decoder. The S1's codec limits allow 4 concurrent instances (the root read
   `max-concurrent-instances` 4), so heavy sat at the limit, not over it. Typical's decoder was gone
   before heavy opened: GeckoView's media process stopped ("Media service has been unbound") 2.5 s
   before heavy's decoders started, in a new media process.
2. **The decoders ran out of data; they didn't fall behind.** Each heavy stream dropped 0 or 1 frames
   and decoded what it got, but fired `waiting` 13 to 18 times. In heavy's first minute the decoders
   logged 117 flushes and 43 end-of-stream drains (typical: 1 flush, no drain), and 3630 decoder log lines
   against typical's 171. That is Gecko draining and flushing decoders whose buffers emptied. So the
   segments reached the media buffer late.
3. **Why they were late wasn't known then.** HA's player turns low-latency HLS off with more than 2
   streams unless the page came over HTTP/2 (`_isLLHLSSupported`, `ha-hls-player.ts`), and the fake
   serves HTTP/1.1, as HA itself does without a proxy. So heavy's four streams poll plain HLS. The
   candidates were the engine's few connections per host, `adb reverse`, and heavy's blocked main
   thread, on which hls.js runs. s1cam3 answers it (below): the main thread.

**Three runs: s1cam3** (#182 at cbc2be6, the S1 app 0.4.3, `thread_router`, 2026-11-13, about 05:45 to
06:40; **3 of 3 runs, verified, not calibrated**). Clarity D, not provisional. The run exited 0 and
the restore worked. The fake knew every command (none unknown or failed), and sent each live change
at most 192 ms late, under the 200 ms limit.

| View | First frame (from the navigation) | Shown | Stalls (from rVFC) | The view's other tasks, with the streams |
|---|---|---|---|---|
| typical, 1 × 720p | 4.2 s (3.8 to 4.7) | 100% (99.8 to 100) | none; longest gap 0.2 to 0.27 s | scroll 25.0 fps (s1v7 without streams: 35.1); opens in 6.6 s (4.8) |
| heavy, 720p + 3 × 360p | 3.7 s (3.6 to 4.4), the worst stream's | 35% (32 to 42), the worst stream's | 26.6 s (24.7 to 27.2) | scroll 5.7 fps (23.3), p95 350 ms; a change takes 739 ms (156); opens in 35.7 s (14.5); tap 288 ms (88) |

The decoders, from the runner's `media` detail (logcat, read-only):
- **Every stream got the hardware decoder** (`OMX.allwinner.video.decoder.avc`): 1 on typical, 4 on
  heavy, in every run. No software decoder.
- **Typical's decoder was flushed 1 to 3 times per run** and drained at most once. **Heavy's four were
  flushed 241 to 263 times and drained 97 to 107 times per run.** Gecko drains a decoder whose stream
  ran out of data.

Each heavy stream decoded 169 to 269 of the 474 to 526 frames its window was due, dropped none, and
fired `waiting` 9 to 15 times.

**Why heavy's streams starve: the page's main thread.** From the `fetches` detail (Resource Timing):
- **Each fetch is quick.** Heavy's segment fetch p95 is 114 to 162 ms (queued for a connection: p95
  93 to 137 ms). Typical's is 45 to 58 ms, with the longest gap between a stream's segments about
  1.2 s.
- **But the requests come seconds apart.** On heavy, the longest gap between two segments of one
  stream arriving is 2.8 to 4.3 s, against 1 s segments.
- A harness bug, found after this run, counted the fetches twice in the views after a view without
  cameras (s1cam3's runs 1 and 3 for typical, run 1 for heavy: 48 segments against 24, 185 against
  88): an observer from light stayed connected. The timings above stand, since each fetch was counted
  twice with the same times. The fix ends the observer there; nothing graded used it.

So hls.js, which runs its loading loop on the page's main thread, doesn't get to run on time. The
same thread takes 739 ms to show a live change on heavy. The data rules out the decoders (they
had nothing to decode), the fake (about 3 Mbit/s over loopback, no lock on the HLS path; #182's
verifier) and the link (each fetch is fast once asked). The maintainer predicted this (#174): a
JavaScript player on a slow CPU, beside a heavy dashboard.

**The stalls overstate on a blocked page.** rVFC's callbacks run with the page's rendering, so on
heavy they fired only 33 to 62 times per stream while the decoder presented 169 to 269 frames.
65 to 146 frames per stream (397 to 463 per run, `stall_presented`) were presented inside the
gaps counted as stalls. "Shown" comes from the decoder's own count (`getVideoPlaybackQuality`), so
it stands. Heavy fails the camera task on "shown" alone.

**Where first frame's time goes.** The fake's log puts the view's `camera/stream` request 0.7 to
1.3 s after the view was opened. The rest is the stream starting up, while the view is still being
built: 3.1 to 3.4 s on typical, 1.5 to 3.0 s on heavy (the Mac's clock for both events; about ±0.1 s).

**RAM with the cameras** (not graded). The app at idle on the typical view, now with its 720p
stream, is **850 MB** (848 to 851) against s1v7's 736 MB without it: **+114 MB**. The peak during the
graded tasks is **889 MB**, against 743 MB. Per process at idle:

| Process | s1v7 (no streams) | s1cam3 |
|---|---|---|
| tab | 320 MB | 361 MB |
| gpu | 213 MB | 232 MB |
| main | 195 MB | 211 MB |
| media (GeckoView's media process) | none | 28 MB (28 to 43 between pages) |
| utility | none | 15 MB |

The app is the same 0.4.3 in both runs. Only the test code differs (131f6da and cbc2be6), and its
harness adds a few kB to the page. The frame's budget for the app's peak is 988 MB (half the S1's 1976 MB), so the
peak is within it, with 99 MB to spare.

**The bars: a proposal, not a change** (the maintainer decides, in its own PR; updated after s1cam3):
- **Keep frames shown (95 / 85%).** It is the robust measure. It comes from the decoder's own count,
  and it separates the views by far: typical 99.8 to 100%, heavy 32 to 42%.
- **Measure stalls from frames presented, not from rVFC callbacks.** Keep the numbers (no gap over
  500 ms; under 1 s of stalls in all), but count a gap only when `presentedFrames` didn't move in it.
  On a blocked page the callback gaps overstate (above). This is a change to what is measured, so it
  is a new protocol. It wouldn't change heavy's result (shown fails it), but it would stop a page
  whose video plays from failing on its callbacks.
- **Keep first frame timed from the navigation, at 2 / 4 s.** That is what a person sees, as open the
  view is. After smoke-cam1 I suggested timing it from the stream request; s1cam3 shows the view's
  part is only 0.7 to 1.3 s and most of the wait is the stream's start. Timing from the request would
  move typical's 4.2 s (over fluid's 4 s) to about 3.2 s (under it): a change of definition, not of
  the panel, so I withdraw it. #174's bars have no external source for camera start-up, so they stay
  a project choice.
- **Don't calibrate on the S1's letter.** The camera bars don't move the S1's D: typical and heavy
  were below fluid on scroll and open the view already.

#### WebRTC cameras (#202, protocol 6)

**Why.** Real setups mostly play cameras over WebRTC: go2rtc has been built into HA since 2024.11.
Then the browser's own media stack carries the video, and no JavaScript runs per segment. Over HLS,
hls.js fetches on the page's main thread, and on the S1's heavy view it fetched late (s1cam3, above).
So protocol 6 grades the camera task on WebRTC, and keeps HLS as a measured fallback.

**What the fake serves.** The cameras on the graded views are HA's cameras with go2rtc. Their
`camera/capabilities` offers `web_rtc` and `hls`, as HA core does for a camera with a WebRTC provider
(`camera_capabilities`, HA 2026.11.0). With both, HA's frontend starts both players: HLS shows until
WebRTC has video, then only WebRTC stays (`ha-camera-stream.ts` `_streams`, frontend 20261028.5). The
fake answers the commands `ha-web-rtc-player.ts` sends, in HA's own shapes (HA core's `homeassistant/components/camera/webrtc.py`; the fake's peer and relay
are `tools/perf/webrtc.py`):

| Command | The fake's answer |
|---|---|
| `camera/webrtc/get_client_config` | `{configuration: {iceServers: [TURN over TCP], iceTransportPolicy: "relay"}}` |
| `camera/webrtc/offer` | A subscription: `result` first, then events `session` (`session_id`), `answer` (the SDP), or `error` (`code`, `message`) |
| `camera/webrtc/candidate` | Passes the browser's candidate to the peer; `result` |
| `unsubscribe_events` (the offer's id) | Closes the session, as HA does |

A camera without `web_rtc` gets HA's errors (`webrtc_offer_failed` and the others). The peer is
aiortc on your computer (`tools/perf/webrtc.py`). It sends `camera.py`'s H.264 as it is: the same
bitstream as the HLS segments, remuxed to Annex B, never re-encoded, its access units at 15 fps from
an IDR. The answer offers H.264 only.

**How the panel reaches it: over the LAN when it can (protocol 7, the maintainer's option B, 2026-11-13).**
Real setups carry WebRTC video over the LAN, UDP between the browser and go2rtc. Through `adb reverse`
the S1's heavy view stalled: four relayed streams share one USB link with the frontend's websocket
on a busy panel (#202's smokes 4 and 5; [the research note](../frames/aqara-s1-plus/research/webrtc-camera.md)).
So before the graded part, run.sh asks the panel whether it can reach the computer
(`tools/perf/lanprobe.py`, run under the fake's Python, before the fake starts):

| Order | Check | If it works | Transport |
|---|---|---|---|
| 1 | the panel sends one UDP datagram with a random token to the computer's media address, port `--port + 1` | the computer received the token | **`lan-udp`**: no relay; the browser and the peer pair over UDP on the LAN, as a real setup does |
| 2 | the panel opens one TCP connection there and sends a token | the computer received it | `lan-turn`: the same relay, over TCP to the computer's address |
| 3 | (neither, or the computer has no LAN address) | | `adb`: TURN over TCP through adb reverse, as before |

**What it does on the panel:** two `toybox nc` commands over `adb shell`, as adb's shell user: one UDP
datagram and one TCP connection, both from the panel to this computer only. They are connections
from the panel; nothing on the panel is written or changed. It records no addresses.

UDP comes first because it is the real path: no TCP head-of-line blocking, no relay in the media's
way. TCP to the relay is only for a network that drops UDP from the panel but passes TCP (the
computer's firewall decides per program, and the check runs under the fake's own Python, so it
answers for the fake). Over `lan-udp` the browser has no ICE servers; Gecko hides its own addresses
behind mDNS names, and the peer learns them from Gecko's checks as peer-reflexive candidates
(verified in Firefox, `test/webrtc.gecko.test.mjs`, 2026-11-13).

`--webrtc-transport adb|lan-turn|lan-udp` sets it instead (`auto` is the default). report.json and the
summary record it (`webrtc_transport`: `transport`, `chosen_by`, `reason`), and report.md says it
beside the camera results. Over `adb`, heavy's camera result is marked as possibly the test's link's,
not the panel's. **Results over different transports aren't comparable**: the summary carries it,
and `gradeSummary` (grade.ts and grade.py) returns it with the letter (`transport`, and over adb
`transport_caveat`), so a site can keep them apart. It never changes a letter or a threshold.

The adb path, as designed first (still the fallback). `adb reverse` forwards TCP, and WebRTC's media
is UDP. The choice then:

| Option | Through adb reverse? | Needs the LAN? | Verdict |
|---|---|---|---|
| ICE-TCP (the browser connects to a TCP candidate on the computer) | yes | no | aiortc has no ICE-TCP; it would need another WebRTC stack |
| **TURN over TCP**: the browser's relay is on the computer, reached through adb reverse | yes | no | **Chosen**. Every hop but the TCP one stays inside the computer |
| The panel reaches the computer's LAN address | no | yes | Depends on the tester's network (an IoT VLAN, a firewall): not repeatable |

So `get_client_config` names one TURN server, `turn:localhost:<port + 1>?transport=tcp`, and run.sh
adds that port to `adb reverse`. The relay (`webrtc.py`, RFC 5766 with long-term credentials) relays
UDP on the computer's **media address**, where the peer's only candidate is: the computer's IPv4
address towards its default route (else its first one that isn't loopback or link-local;
`GW_PERF_WEBRTC_ADDR` sets it). Packets between the relay and the peer are delivered inside the
computer, and the panel never sends to that address itself: it only talks TURN over the TCP
connection. The relay relays to the peer only.

**Why `localhost` and not loopback for the media** (found on the S1's first WebRTC smoke, 2026-11-13,
and reproduced in Firefox 152 on a Mac the same day, verified). The first version used `127.0.0.1`
for all of it. Chrome played it. GeckoView, the kiosk's engine, never connected: 14 offers, no ICE
candidate from the panel, ICE checking then closed. Gecko (nICEr) has three rules Chrome doesn't:

| TURN server | Relayed address | Firefox connects to the relay? | Relay candidate? |
|---|---|---|---|
| `127.0.0.1` | `127.0.0.1` | no (`icecandidateerror` 701) | no |
| `127.0.0.1`, with Gecko's `media.peerconnection.ice.loopback` on | `127.0.0.1` | yes | yes |
| `localhost` | `127.0.0.1` | yes, allocates | no (701) |
| `localhost` | the computer's own address | yes | yes; ICE connects and the video plays (a browser on the computer) |
| `localhost`, through adb reverse (the relay sees the browser as `127.0.0.1`) | the computer's own address | yes, allocates | no (the S1's second smoke, and Firefox through a stand-in) |
| the same, the allocation's mapped address reported as the computer's own address | the computer's own address | yes | **yes; ICE connects and the video plays** |

So Gecko won't use a TURN server given as a loopback IP, drops a relayed candidate on loopback, and
drops one whose allocation says the browser itself is on loopback (XOR-MAPPED-ADDRESS). Through adb
reverse the relay only ever sees `127.0.0.1` (adb's own connection), and the browser's real address is
unknown to it, so it reports the media address there: with relay-only ICE that only labels the
candidate. A relayed address the browser has no route to is fine (Firefox, `192.0.2.55`). The kiosk's
Gecko prefs aren't the test's to change, so the fake avoids all three. The tries and the
commands are in [research/gecko-turn.md](research/gecko-turn.md). `test/webrtc.gecko.test.mjs`
holds this: it runs the fake's WebRTC in Firefox. HA sends only `iceServers`; the
fake adds `iceTransportPolicy: "relay"`, which the frontend passes to `RTCPeerConnection` unchanged,
so a browser on the computer's network can't pair its own address and skip the relay. What differs
from a real setup is the transport (TCP through adb, not UDP on the LAN); the video and the
browser's media stack are the same. So the receiver's loss and jitter numbers (detail) aren't
comparable with a camera on a LAN; the graded bars (first frame, shown, stalls) are what count.

While a run lasts, the relay's UDP side and the peer listen on the media address, which other hosts
on that network can reach. The relay passes on only datagrams from the peer's address, so injecting
into a session would take a spoofed source address on that network: a low risk for a test that runs
minutes. On macOS the first run may show the firewall's "accept incoming connections" prompt for
Python; either answer works, since nothing comes from outside the computer.

**The profile in the answer isn't the stream's, as with go2rtc.** The stream is x264's Main
profile, and the fake's answer lists the two Baseline `profile-level-id`s aiortc has, `42001f`
first, then `42e01f`. go2rtc 1.9.15, which HA 2026.11.0 pins, does the same: its `Codec.Match`
(`pkg/core/codec.go:126`) compares only the codec name, clock rate and channels, its own H.264 list
starts with `42001f` (`pkg/webrtc/api.go:277`), and it passes a camera's Main or High stream
through unchanged. Gecko, the panel's engine, offers only `42e01f` and `42001f`, so a Main fmtp
wouldn't negotiate with it at all. Re-encoding to Baseline would make the stream less like a real
camera's (read 2026-11-14, CodeRabbit on #205).

**The video has no B-frames** (protocol 6, both paths). Protocol 5's segments had them (x264's
default, I B B B P). Browsers' WebRTC receivers don't reorder B-frames, and IP cameras send I and P
frames only, so `camera.py` encodes with `-bf 0`. So the HLS numbers aren't comparable with protocol 5's
(s1cam3) either: the same streams, re-encoded without B-frames.

**What is graded.** The camera task, with the same bars and the same rules: first frame, frames
shown, stalls ("a stall is a stall"; the maintainer, #174). On a graded view each camera's stream is
its WebRTC player's video. A camera whose WebRTC never showed a frame fails the task, even if HLS
showed one meanwhile.

**Detail, not graded:**
- **The receiver's own counts** (`webrtc` on each stream, `stats.js` `webrtcWindow`): frames received, decoded and dropped, freezes and their time, pauses, packets lost,
  NACKs and PLIs, the jitter buffer, the codec and its fmtp line, and the candidate pair (`relay`
  over `tcp` on a panel). Also the decoder the browser names (`decoderImplementation`,
  `powerEfficientDecoder`); an engine may leave them out. The harness lists the page's
  `RTCPeerConnection`s to read them, and changes nothing about them. The page samples every
  connection that carries the camera's video once a second, because HA's player can renegotiate or
  replace it mid-window (on the S1's heavy view it did, after ICE failed: research note below). Each
  receiver (connection, stats id, SSRC) counts only its own increments: one that appears mid-window
  counts from 0, a counter that goes back counts on from 0, two receivers are never subtracted, and
  no count is negative. `sessions` and `receivers` say how many there were; `no_video_ms` is the time
  between samples in which none decoded a frame. `connections` lists each one: when HA's player made
  it, its offers (a `restartIce` is a second one) and its ICE states. The graded stalls stay those of
  the video element, which count the time between sessions too. The report says when a camera
  needed another session.
- **The relay's view** (`fakeha.jsonl`): each relay connection's datagrams both ways when it
  closes, and the round trip of each of the peer's ICE checks through the browser (`checks`:
  answered, unanswered, median and longest, how many over 500 ms). The peer's liveness transitions
  are `webrtc_liveness` (disconnected, connected, failed), and an ICE loss is `lost` with `reason`
  (`liveness` or `remote`).

**A view the panel can't finish** (#202: smoke 7, s1rtc3, s1rtc4). Each task has its own budget
so a slow view still gets every task tried:

| Task | Budget |
|---|---|
| Open the view (usable) | 300 s from opening (`GW_PERF_VIEW_DEADLINE_S`): the only per-view deadline |
| Live updates | 1 s warm-up and a 4 s window |
| Taps | 5 s a tap (`PHASES.tapEachMs`; at least 30 s): `adb shell input` took 2.4 to 4.7 s a tap on s1rtc4's heavy |
| Cameras | a fixed 30 s from the view going live, beside the updates and the taps |
| Scroll | each swipe up to 4 s, after the cameras' window |

The harness checks each budget on waits paced by animation frames: on s1rtc4's heavy a 30 s tap
wait, checked by a timer, ended after 140 s, because the page's timers wait behind the frontend's
queue while the refresh driver's frames come first. It doesn't wait for answers a busy page holds:
its camera line came 93 s after the window, waiting on a getStats a second for each of 4 receivers
(now one at a time, and at most 10 s at the end), and the stream's stop at the end (at most 5 s;
the runner stops it at the fake too, and the fake ignores a stop for a view that isn't the current
one). INP is the page's own Event Timing, from when each tap reached the page, never from when the
runner sent it.

After the view is usable, the runner times it out only when it says nothing for 180 s
(`GW_PERF_STAGE_IDLE_S`): stuck in one task. A view that times out (`timed_out` in the run's file
and the report, with `timed_out_after`: the last thing it said, or `usable`) fails its own tasks and
nothing else:

1. What it measured stands.
2. What it didn't is measured as failing, not as missing: "didn't finish" is itself the
   measurement, as a camera that never shows a frame fails rather than goes unmeasured. Tap counts
   as the time it ran (`timed_out_s`, at least the deadline when it was never usable). So does
   blocking when the main thread was never free, and so does open when the view was never
   drawn; a view drawn but never free keeps its real open (its `drawn` line), and its usable isn't
   measured. Scroll, live updates and the cameras get a `{"timed_out": true}` record, which every grader
   (grade.py, grade.ts) puts below the lowest bar. So a timeout doesn't make the grade provisional;
   report.md names it in the detail, and the report's and summary's `timed_out_runs` count it per
   page.
3. The runner then restarts the kiosk as at the run's start (`am force-stop`; `am start` if nothing
   relaunched it within 10 s), waits for its reference dashboard to be usable again (at most
   180 s, or the run fails), and opens the run's remaining views (`recoveries` in the run's file).
   The run's restart time stays the first restart's, and RAM isn't sampled right after this one.

Before, the whole run waited out its 900 s and was dropped (smoke 7); then the run's later views
weren't opened (s1rtc3); then heavy, usable after 43 s and still working, ran out of a 300 s
per-view deadline before its swipes (s1rtc4). A page stuck on a busy panel can outlast the kiosk's idle
timeout (20 s at night on the S1), and with the screen off nothing measured is the panel's: the
test hold keeps it on (`holds`, one before each view), and Android's wake key through each
restart (`restart_wakes`): see "The screen stays on". A graded run may take up to 1800 s (`--timeout`).
None of this moves a threshold.

**A page the panel closes** (the lululemon Mirror, 2026-11-23). On a panel short of memory, Android's
low-memory killer can end the browser page's process (Gecko's content process) while a view is
measured. The app logs `Content process killed; restarting session` (or `crashed`, when the process
died by itself: `MainActivity.onContentGone`), restarts the session and loads its page again: the
test dashboard's start view. Without that line, any page load while views are measured fails the
run (a sign-in, a navigation; see the restart below). With it, the open view is lost in that run, not
the run:

1. The view (`content_gone` in the run's file: `cause`, `low_memory`, how long after its open and
   after which line) fails every task in that run, measured, not missing. Nothing it measured before
   stands: the panel couldn't hold the page, which is the result. Tap, open and blocking take the
   300 s view deadline as their value, as a view that never answered; scroll, live updates, the
   cameras and the dialogs get a `{"lost": true}` record, which every grader (grade.py, grade.ts)
   puts below the lowest bar. The page can't reach a level in that run, the median and run rules are
   the usual ones, and the grade isn't provisional for it.
2. `low_memory` is true only when the killer said so: lmkd's line in logcat (Android 9 and later), or
   the kernel's `lowmemorykiller: Killing '<name>' (<pid>)` in `dmesg` (Android 8 and earlier), naming
   one of the app's own processes, at most 120 s before the app's line. The kernel keeps only the last
   15 characters of a longer process name, so a long one may keep no colon: the runner matches by the
   pid and full name ActivityManager started each of the app's processes under during the run, and
   otherwise by `<package>:<name>` or an end of it that keeps the colon. It reads `dmesg` once at the
   kill, after the lost view's gestures and command have stopped (read-only; a build whose shell
   can't read it gives nothing). The report says "the panel
   ran out of memory and its browser page was closed (low-memory killer)" then, "Android closed the
   panel's browser page" when nothing named the killer, and "the panel's browser page crashed" for a
   crash.
3. The runner waits for the start view to say it is usable again (at most 180 s, or the run fails),
   as after a timed-out view but with no force-stop, and opens the run's remaining views once it says
   done, or 10 s after its usable line when the done line was lost (`recoveries`, `launch:
   session-restart`, and `done_missing` for a lost done line). A lost view that was the run's last ends the run,
   graded. A lost HLS copy fails only itself, and a kill between two views loses nothing.
4. report.md lists each lost page, in how many runs and why, and its table and headline say "page
   lost" instead of the deadline. The report's `lost` ([run, view, why]) and each page's `lost_runs`
   and `lost_why`, which the summary carries, are there only when a page was lost, so every other
   report and summary stays as it was.

**Drawn long before usable** (detail). A view is usable when its first screen is drawn and its main
thread is free: every probe (a `setTimeout(0)`) runs within 50 ms, for 500 ms. On s1rtc4's heavy
the first screen was drawn 1.1 to 1.3 s after the navigation, and the main thread was free after 43
to 46 s; its live updates then drew 5 to 8 frames a second. So the wait is the frontend's own work,
not a starved callback of the harness's: a tap in that time waits as the probes do. That wait is
graded, as blocking ([Usable](#usable)); `drawn_ms`, `usable_ms` and `busy_ms` (the
longest a probe waited, each second of the wait) are detail, and report.md says when drawn and usable
are more than 5 s apart.

**Checking Clarity against the screen** (debugging only). `tools/perf/debug/screencam_moments.py`
lists, for a view in a run, when it was opened, drawn, usable, each camera's first frame and each
stall, in this computer's epoch ms, and the frames of a screen-camera recording to look at, with how
far the test pattern's counter should move if the video played. It reads a run's files and nothing
reads it: no part of the grading path (run.sh, perfrun.py, report.py, summary.py, the graders)
uses it or anything a screen camera records (`test_screencam_moments.py` checks this). Recordings
show the room: keep them local.

**Stream sizes, against a real setup.** go2rtc passes a camera's own stream through without
transcoding, so a `picture-entity` card with `camera_view: live` plays what the camera entity
streams: for most integrations the main stream (often 1080p or more, several Mbit/s), unless the
user picks a sub-stream entity (UniFi Protect's lower channels, Reolink's "fluent" stream). This is
general knowledge, not measured here. Clarity's heavy view plays 720p and three 640×360 at 15 fps,
about 0.45 and 0.35 Mbit/s of `testsrc` (camera.py): lighter than a typical real four-camera grid,
never heavier.

**Where the video waits** (diagnostics, #202's sixth round). On the S1's heavy view the panel
stopped receiving video for tens of seconds ([the research note](../frames/aqara-s1-plus/research/webrtc-camera.md)).
To find where it waits:
- `fakeha.jsonl`: each relay connection's `seconds` (each second: seconds since it opened, kB down
  and up, datagrams down and up so far, and kB queued on this computer toward the panel), and
  `ws_second` (the frontend websockets' kB and messages each way, and the longest blocked send).
- `tools/perf/transportwatch.py --out DIR [--logcat]`, beside run.sh, read-only: the panel's TCP
  sockets on the fake's ports each second (`rx_queue` on the kiosk's socket is data not yet read),
  each thread's CPU in the kiosk and adbd every few seconds, and logcat if asked. Its sampling costs
  the panel a few percent of a core (adbd 3.6% at idle, with it).
- refstart's `gestures_sent`: when each tap and swipe went over adb, and how long `input` took.
- `run.sh --webrtc-transport adb|lan-turn|lan-udp`: one path, forced, to compare with what `auto`
  picked (since protocol 7 the graded path is `auto`'s choice; see "How the panel reaches it").

**When the peer gives a connection up: as go2rtc does** (since #202's fifth round, 2026-11-13).
Real setups answer the frontend's offer with go2rtc. HA 2026.11.0 pins go2rtc 1.9.15
(`homeassistant/components/go2rtc/const.py`, and its Dockerfile), which uses pion/webrtc v4.2.25 and
pion/ice v4.4.8 (go2rtc's `go.mod`) and sets no ICE timeouts (its `pkg/webrtc/api.go`). So pion/ice's
defaults apply (read from its source at v4.4.8):

| pion/ice v4.4.8 | Value | Where |
|---|---|---|
| Keepalive: a Binding request on the selected pair | every 2 s | `agent_config.go:21`, `agent.go:951` |
| "disconnected" | 5 s without receiving anything from the browser | `agent_config.go:24`, `agent.go:906-946` |
| "failed" (the connection is given up) | 25 s after that, 30 s in all | `agent_config.go:31` |
| What counts as receiving | any packet: media, or any STUN message, however late an answer is | `candidate_base.go:337`, `agent.go:1845` |

aiortc's own check (aioice 0.13.0, `query_consent`) is stricter: each check's answer must come within
0.5 s, and 6 late ones in a row close ICE. On the S1's heavy view that dropped every session while the
panel's answers were all arriving, only late (smoke 4, 2026-11-13: 0 unanswered; median round trip
1.4 to 2.6 s, longest 9.6 s, about 3 in 4 over 500 ms; typical's median 16 to 22 ms). The answers
travel through the panel's TCP connection behind the video, on a panel busy with heavy. The fake's
peer now uses pion's rules (`webrtc.py` `PION_ICE`), so it gives a connection up when a real setup's
would. This is fidelity to the path real setups use, not tuning toward a letter: the grading rules
are unchanged, and a panel that stops answering still loses its stream (after 30 s, as with go2rtc).

**Frames shown over WebRTC: `framesDecoded`** (read 2026-11-13 from GeckoView 159's source,
mozilla-release `7b34a03a910d0385299f4a9f27aa585a786862d8`). Gecko fills `framesDecoded` and
`framesDropped` from libwebrtc's receive statistics (`dom/media/webrtc/jsapi/RTCRtpReceiver.cpp`,
lines 542 and 607). `frames_decoded` counts each frame the decoder hands to the renderer
(`third_party/libwebrtc/video/video_stream_decoder2.cc:53`, `receive_statistics_proxy.cc:664`).
`frames_dropped` counts frames the frame buffer dropped before decoding
(`video/video_stream_buffer_controller.cc:148` and `:325-329`) and frames given to the decoder that
never came out of it (`modules/video_coding/generic_decoder.cc:137, 275, 287`). So no dropped frame is
a decoded one, and subtracting them undercounted: smoke 4's heavy had 1090 decoded and 690 dropped,
which read as 400 shown. Frames dropped after the decoder, in the rendering, are in no count ("Not
including frames dropped in the rendering pipe", `RTCRtpReceiver.cpp:605`); the video element's
frame callbacks, which give the stalls, see those. Chrome's counters are libwebrtc's too (Gecko
vendors libwebrtc).
- **The other player** (`others` on each stream): HLS's first frame while WebRTC started, and
  whether the frontend had removed it by the window's end.
- **The decoders from logcat**, as for HLS (`media`). GeckoView's WebRTC uses Android's MediaCodec
  when an instance is free: over the LAN, typical's one camera got the S1's hardware decoder and
  played 100%. The S1 has 4 hardware H.264 instances; on heavy, HA's HLS players (started with
  WebRTC, as HA's frontend does with go2rtc) took them first while the fake's HLS started instantly,
  and the rest fell to Android's software decoder (#202, smoke 7;
  [frames/aqara-s1-plus/research/webrtc-camera.md](../frames/aqara-s1-plus/research/webrtc-camera.md)).
  The log doesn't say which player got which decoder.

**The HLS fallback** (detail, never in the grade). After each run's graded views, the runner opens
`typical-hls` and `heavy-hls`: the same views, card for card, whose cameras offer HLS only (HA's
`stream` integration without go2rtc). They run as the graded views do, taps included, without the
swipes. The report gives each run's worst HLS stream and the level the camera bars would give it
(`camera_fallback`); the summary gives the numbers (`camera.fallback`). A copy that fails fails only
itself. RAM between them isn't sampled (they aren't graded tasks).

**Checked in Firefox** (2026-11-13, verified; `test/webrtc.gecko.test.mjs`): the fake's client config,
offer, answer and candidates through its websocket API as HA's frontend sends them, in headless
Firefox 152 on a Mac: a relay candidate on the computer's address, ICE connected, the video decoded at
15 fps (180 frames in 12 s, none lost).

**Checked in Chrome** (2026-11-13, verified; `test/webrtc.e2e.test.mjs`): HA's frontend on the fake,
in headless Chrome on a Mac, played typical's camera over WebRTC through the relay (candidate pair
relay over TCP), first frame 326 ms after the navigation, 99.2% shown, no stall, 0 frames dropped.
Its HLS copy: 318 ms, 99.4%. One fix came from this check: aiortc asks a public STUN server by
default and held each answer 5 s waiting for it, so the peer has no ICE servers. Not yet run on a
panel: the S1's first smoke (above) failed on the loopback relay; see the panel plan in #202's PR.

#### The more-info dialog (#211)

Tapping a tile opens HA's more-info dialog: its controls, its history graph and its attributes. On a
slow panel that is one of the delays people feel most, so every view opens three of
them after its swipes (`tiers.json` `DIALOG`): a light's (`light.clarity_2`, its brightness controls),
a thermostat's (`climate.clarity_0`, its temperature control) and a temperature sensor's
(`sensor.clarity_10`, its history graph over the last 24 hours). None of them is an entity the live
stream or the taps change, so every view, and every run, opens the same three dialogs. The thermostat
is on no card: the fixed home has it for its dialog only.

**What HA does** (read from the frontend at the pinned tag, 20261028.5, and checked in Chrome):

| Step | HA | Source |
|---|---|---|
| The tap | A tile's tap ends in `handleAction`, which for more-info fires `hass-more-info` with the entity | `src/panels/lovelace/common/handle-action.ts` |
| The dialog | The more-info mixin shows `ha-more-info-dialog` (the module is loaded at startup; the element is made on its first open in a load, and each domain's controls on their first use) | `src/state/more-info-mixin.ts`, `src/dialogs/make-dialog-manager.ts` |
| Dialog or sheet | `ha-more-info-dialog` is an `ha-adaptive-dialog`: a bottom sheet (`ha-bottom-sheet`) when the viewport matches `(max-width: 870px), (max-height: 500px)`, else a centred dialog (`ha-dialog`). The mode is picked when HA first puts it on the page. The S1's CSS viewport, 960 x 480 at the default zoom, is under 500 px high: **a bottom sheet** | `src/components/ha-adaptive-dialog.ts` |
| The animation | The sheet slides in 300 ms (`BOTTOM_SHEET_ANIMATION_DURATION_MS`); the dialog's `--ha-dialog-show-duration` and `--ha-dialog-hide-duration` default to 200 ms. With `prefers-reduced-motion: reduce` the sheet takes 1 ms and the dialog 0 ms | `src/components/ha-bottom-sheet.ts`, `src/components/ha-dialog.ts` |
| Its events | Either fires `opened` when it starts to show, `after-show` when its show animation has finished and `closed` when its hide animation has | the same files |
| The graph | The sensor's history (`history/stream`, the last 24 h) drawn by `ha-chart-base` with ECharts, which grows the line over 500 ms (off with reduced motion) | `src/dialogs/more-info/ha-more-info-history.ts`, `src/components/chart/ha-chart-base.ts` |
| The close | The dialog's X button calls `closeDialog()` | `src/dialogs/more-info/ha-more-info-dialog.ts` |

**How it is timed.** The harness fires the same `hass-more-info` event from the view, so the
frontend can't tell it from a tile (no touch: the tap's own cost is the tap task's, as opening a view
is HA's `navigate()` without a touch). From that moment (`t0`) it follows every animation frame
(`stats.js` `dialogStep`, `dialogTimes`, with a node test for each timing point against a fake
dialog). HA's `opened`, `after-show` and `closed` count only when the dialog shows the entity
measured (`ha-more-info-dialog`'s `_entityId`), and the show and the close only from the element whose
`opened` counted (`stats.js` `dialogEvent`, #410): an earlier dialog HA shows late, while this one is
measured, isn't this one's.

| Point | What |
|---|---|
| `open_ms` | The first frame after HA's `opened`: the open's response, the first frame of its animation (detail) |
| `shown_ms` | The first frame after `after-show`: the show animation finished |
| `graph_ms` | The sensor's: the first frame in which its chart is laid out with its data. ECharts then grows the line for 500 ms on its own clock: that isn't waited for, or the graph's own flourish would be most of what a fast device takes (Chrome on a Mac, 2026-11-16: the data on the page at 35 ms, the animation finished at 880 ms, the sheet shown at 315 ms) |
| **`settled_ms`** | The first frame that is both shown and, for the sensor, has its graph drawn. **Graded**: the slowest of the view's three |
| `anim` | The frames of the open animation, from the frame it started in to the frame it finished in: their stats (`frameStats`), `dropped` (refreshes missed) and the longest. The premium level asks them to hold 60 fps |
| `close_ms` | From `closeDialog()` to the first frame after `closed` (detail) |

A dialog that never opened, or never settled, within `DIALOG.waitMs` (20 s) fails its run: it was
measured, and the panel didn't show it. The harness closes it anyway, so no dialog outlives its view.

The run's record is the slowest to settle, how many failed, and the three animations' frames pooled
(`report.py` `dialog_run`); a run with a dialog line lost is not measured. The frames are the page's
own (rAF): they show the main thread. The sheet's slide is a CSS transform, which Gecko can run on its
compositor while the main thread is busy, so rAF may count a hitch the screen didn't show. That is why
they decide only premium, as live updates' pacing does.

**No dialog of one view lands on the next (#410).** HA can still show a dialog after its 20 s. Its
dialog manager holds a show while its history entry for a dialog is the current one and no dialog is
open (`history.state.dialog` with an empty `OPEN_DIALOG_STACK`: the close's `history.back()` hasn't
landed), and retries on a timer (`make-dialog-manager.ts` `showDialog`). On the S1's heavy (s1v3a,
2026-11-16, PR head 0c9cc43c) that `back()` didn't land while heavy was on screen. In all three runs
its thermostat's and sensor's dialogs, which "never opened within 20 s", reached HA 0.3 to 0.9 s and
about 5 s after the next view's navigation (the fake's `config/entity_registry/get` for
`climate.clarity_0`, then `sensor.clarity_10` with its `history/stream`). They opened on that view,
and the sensor's sheet stayed open over it until that view's own first dialog replaced it (its history
subscription was ended only then). So light after heavy opened in 9.1 to 11.6 s (1.0 s when first),
didn't scroll, and its own light dialog "never opened", in runs 2 and 3. Run 1's view after heavy was
the HLS copy of typical, not graded. Desktop Firefox showed the same when HA's `history.back()` was
held back (a scratch test, 2026-11-16).

So, untimed, after a view's dialogs and after the restart's warm-up, the harness waits until HA is
done with them:

1. Each one it asked for was shown: it opened, or HA asked for its entity's registry entry after the
   event (HA's dialog does that each time it shows one). One that threw before its event was
   dispatched was never asked for, so it isn't waited for.
2. None is open: one that opens is closed, and so is one still open at the limit.
3. No dialog's history entry is current.

It waits at most `DIALOG.settleMs` (30 s). If HA still holds one, the harness moves to the start view
(after the warm-up, which is on the start view, a new entry for the same view), where the held dialogs
land and are closed, and waits as long again. The next view then opens from wherever HA is: the start
view, or the view again when HA's held `history.back()` lands after the move and takes it back (Chrome,
2026-11-16). Before each view's navigation the harness checks the same, for at most `DIALOG.beforeMs`
(10 s). A view that would start on a dialog HA still holds isn't measured: it logs an `error` line
with its nonce, and none of its tasks is graded in that run. The dialogs HA still hadn't shown then
are carried to the next view's check, each for one dialog's wait and one settle (`waitMs` +
`settleMs`, 50 s) from when it was first carried (`stats.js` `carriedShows`). One HA shows late is
still caught; one it never shows refuses only the views in that time, not every view after it (the
dialog e2e test gives HA a held show after the next view's check would otherwise have passed). The
wait itself is `stats.js` `settleLoop`: a dialog still open at the limit is closed before it stops,
and `closed` counts dialogs, not looks.

None of these waits is in any time. What a dialog did within its 20 s stays its own view's result:
heavy's dialogs that never opened stay heavy's failures. The `done` line's `settle` and the refdash's
`dialogs_before` say what happened, and report.md says when a view was moved from. The harness also
opens a view as HA's `navigate()` does, with the history state `{ from }` alone (`from` is the path
left), so no key of the entry before, such as a `dialog`, rides into the new one; the dialog e2e test
checks the state after a navigation.

After each restart, before the first view opens, the harness opens and closes each dialog once on
the start view, untimed (its lines say `stage: "warmup"`): HA makes the dialog's element on its first
open in a load, and each domain's controls on their first use. So no view pays for that by coming
first in the run's order, as the start view's cards bring in every card type's code.

**Checked in Chrome** (2026-11-16, verified; `test/dialog.e2e.test.mjs`): HA's frontend on the fake,
at the S1's viewport (960 x 480 CSS px, ratio 1.5), shows each as a bottom sheet with its 300 ms
animation and settles in about 0.3 to 0.4 s; the sensor's graph is drawn 20 to 35 ms after the event.
With `prefers-reduced-motion` the sheet takes 1 ms and they settle within 40 ms. At 1280 x 800 HA
shows its centred dialog (200 ms), settled in about 0.22 to 0.26 s (two runs). The fake knows every command they send: the
entity's registry entry (`config/entity_registry/get`, answered as HA answers an entity with none,
`not_found`) and the sensor's history.

A panel with `prefers-reduced-motion` on (Android's "Remove animations") opens them with no
animation: the report says so and the grade is provisional, since the S1's default is off and the
times aren't comparable.

#### Change to screen

- **When a change was sent.** Each change's context id carries its slot in the stream and how late
  the fake sent it, and the fake sends change k at a fixed time after the stream started (plus that
  lateness, a few ms). One that never reached the page counts from its fixed time. The fake answers `/clarity/live` with that
  start on its own clock, and the harness ties its clock to the fake's while the view is still: 12
  round trips to `/clarity/time`, the shortest kept, uncertain by half its length (`clock_rtt_ms`;
  the report is provisional over 40 ms). So it knows when the fake sent each change, and change to
  screen runs from then. The moment the harness asks for the stream won't do: a busy main thread can
  hold the request back before it leaves (heavy, in headless Chromium, 2026-11-13: about 625 ms,
  which would have added that much to every change).
- **When it reached the page.** The harness wraps `WebSocket` so its own message listener is
  registered before HA's. On HA's frontend that listener runs on the main thread, so a busy main
  thread holds a change back before HA has seen it. That wait counts: the user waits for it too. The
  report gives how many changes reached the page in the window and how long they waited
  (`arrivals`). The frontend can hold two connections for a moment (a reconnect), so a change is
  counted once per entity and context id.
- **When HA drew it.** A `MutationObserver` on each watched card's shadow roots sees HA change the
  card. The first animation frame after that is the frame the change is drawn in (its tick). The
  report then takes the first SurfaceFlinger present at least one refresh later, as before.
- **One that never arrived** in the window (smoke9's heavy: frames stalled for up to 649 ms and the
  changes came in bursts) counts as not shown by the window's end, from when it was sent, so at least
  that long. Before this, such a window read "only 17 of 20 state updates ran in the window" and
  heavy's updates were "not measured", which hid the panel failing the task.

**What differs from your dashboard.** The kiosk loads the reference pages as it loads yours, in the
same GeckoView, with its loading cover, zoom and hidden header. The differences:

| | Your dashboard | The reference dashboards |
|---|---|---|
| Server | Your Home Assistant, over Wi-Fi | The fake, on your computer over `adb reverse` (USB) |
| Sign-in | HA's login page once, or the kiosk's phone sign-in (`external_auth=1`) | HA's own sign-in flow; the fake answers `/auth/authorize` at once with a code, and the frontend keeps its tokens as it does with HA |
| Scripts | HA's | HA's, after the harness (one script) and one `min-height` style |
| Translations | HA sends them | None: words HA would translate show HA's fallback |
| Icons | HA sends them | HA core's own, for the integrations the fake loads ([HA's icons](#the-reference-dashboards)) |
| Registries, areas, services | Yours | Empty or minimal: enough for the cards on the page |
| History, pictures | Yours | Seeded history and statistics over the window each card asks for, and generated pictures |
| Opening a view | Your tap on a view tab or a navigate action | The same `navigate()`, called by the harness: no touch, so the tap's own cost isn't in it (tap is graded on its own) |

The fake answers every command the frontend sent while it was recorded. A command it doesn't know
gets an error, which the frontend handles as it would from HA, and the fake logs it in
`fakeha.jsonl` (`"unknown"`), which makes the grade provisional.

## What it measures

### The graded tasks

| Task | How | Where it comes from |
|---|---|---|
| Tap | INP: the worst of 10 taps on the toggle, taken while the live updates run. Event Timing gives the delay, the handler time and the time to the next paint. Taps under 16 ms aren't reported by Event Timing; they count as 16 ms | Each page |
| Scroll | Real swipes (`input swipe`), while the live updates run. A swipe's window runs from its first scroll event until 400 ms with no scroll event, the fling included, capped at 100 refreshes. A swipe that reached the end of the page anyway (`hit_edge`) is left out and counted: it stops early, so its window is mostly the flick's slow start. All of a run's other swipes are pooled: rAF over every swipe, SurfaceFlinger over the swipes whose history it kept (decided swipe by swipe). The frames are the worse of the two, judged on the run's scroll level (average fps over the swipes and p95; see [The grade](#the-grade)), and the page takes the median level. A run in which SurfaceFlinger kept no swipe rests on rAF only, which can't see frames the compositor dropped: the report says so and the grade is provisional. `scrolled_px` proves the page moved: a page that didn't move is "not measured". Each swipe logs how many state changes (sensor values and on/off changes, not the clock) ran during it (`changes_during`); a page where none did is provisional. A frame without touch isn't graded on it: `n/a`, no swipes sent ([No touchscreen](#other-glassworks-apps-and-android-6), rubric 2) | Each page; SurfaceFlinger |
| Live updates | **Change to screen**: per change, from when the fake sent it (from the stream's schedule; see [Change to screen](#change-to-screen)) to the first frame on screen that can show it. The harness notes when each change reached the page (before HA's own listener), when HA changed the card, and the next animation frame (the frame the change is drawn in); that frame can reach the screen one refresh later at the earliest, so the first SurfaceFlinger present after that is taken (frames presented sooner were already on their way). A change with no frame by the window's end counts as the time from it to the window's end, which is conservative: it may show later still (one less than 250 ms from the end isn't counted, and one that is counts as at least 250.1 ms, so it never passes fluid's 250 ms). A change that never reached the page in the window counts the same way. One SurfaceFlinger's history doesn't reach isn't counted, and if fewer than half are counted the run isn't. The two clocks are tied by the page's log line to within a few ms, so a value can be a refresh off; the bars are 150 and 250 ms. Each run's level is from the p95 over the window's changes; the page takes the median level. **Frame pacing** over the frames that had a change to draw (a rAF interval counts when it overlaps a change, from when it was due to when it ran plus its transition) decides only the premium level (A+) and is reported. A **live check** at the window's start logs whether the toggle and everything that changes are on screen, and the change log proves the updates ran. If something was off screen or there were no targets, the updates aren't graded (and, if off screen, the taps neither). The change log is the fake's schedule (since pre-release protocol 4), so updates that didn't arrive are never a reason to skip the window: they count | Each view; SurfaceFlinger |
| Open the view | From the navigation to the view (HA's `navigate()`, as a view tab calls it, in the frontend that is already loaded) to the view's head and every card on its first screen **drawn**, on screen (the first v1's rubric 1 ran to usable: drawn and the main thread free; see [Open the view](#open-the-view-a-navigation-in-the-loaded-frontend) and [Usable](#usable)). The times come from the page's own clock, tied to logcat's by its log line. If the view never says it was usable, or no frame reached the screen after it, it is "not measured", never 0. (Pre-release protocols 1 to 3, and protocol 4 as first written, loaded the whole page for each dashboard) | Each view; SurfaceFlinger |
| Blocking after the open | From the first screen drawn to usable (the main thread free for 500 ms), each animation frame's time past 50 ms, added up: Total Blocking Time measured on frames ([Usable](#usable)). A view that never became usable within the runner's deadline blocks for all of it | Each view's page |
| More-info dialog | From the event a tile's tap fires to the dialog settled (its show animation finished and, for the sensor, its history graph drawn), the slowest of a light's, a thermostat's and a sensor's; per run, then the median level. A dialog that never opened or settled within 20 s fails the run, and so does a view that didn't get to them. The open animation's frames decide only premium ([The more-info dialog](#the-more-info-dialog-211)) | Each view's page |
| Restart | From the app's process start (`Start proc` in logcat) to the reference dashboard's start view being **usable**, as for open the view: the kiosk comes back to it by itself, because the graded part shows it as the kiosk's test dashboard, which outlives the restart. This is HA's cold load: the app, the websocket, the registries, every card type's code and the first view. Once per run, for the whole frame. The engine being ready (GeckoView's own first page, `about:blank`) is reported, not graded (`engine_ready_ms`). See [Restart: back to a usable dashboard](#restart-back-to-a-usable-dashboard) | logcat; the restart page |

Each graded run is one restart back to the start view, then the four reference views in the run's
order (`tiers.json` `ORDER`: light, typical-nocam, typical, heavy; run k starts k − 1 views in).
`typical-nocam` is typical card for card, with a still picture (`picture` card) where its camera
card was (`tiers.json` `PLAIN`), as the cameras took picture cards' places in protocol 5: the same
cards and layout, without the live video. The first view opens once the restart page has said it was usable and the screen has been
still for 2 s, never on HA's load event, which comes seconds earlier. If its `done` line is lost, the runner goes on 10 s after its usable line. If the
kiosk doesn't come back to a reference dashboard (another page loads, or none says it was usable),
the restart is "not measured" ("the kiosk didn't come back to a reference dashboard") and that
run's views still open after the old wait for the kiosk's own page: up to 30 s after engine ready,
then provisional. While the views are measured, and for 1.5 s after the last one, any page the
kiosk loads makes the run an error, and that run isn't graded: a view switch loads no page. The one
exception is a page the app loads again after it said its browser page was killed or crashed: that
loses the open view only ("A page the panel closes"). So does
a view that never says done by the run's time limit (smoke10's heavy), and the screen going off.

**SurfaceFlinger's data.** It keeps the last 127 frames and is read just after each window. A window
is at most 100 refreshes, so the other 27 slots cover that read, and the page's own logged window
length marks the window's end. The data is complete when the history reaches back before the
window. Then every gap inside the window counts, and so do gaps at its edges longer than 1.5
refreshes. If the history doesn't reach back, that swipe counts with rAF only; the others in the
run keep SurfaceFlinger's frames. `sf_valid` lists, per run, whether every swipe had it.

**Not available:** checkerboarding (blank areas while scrolling). GeckoView doesn't expose it to a
page, and the runner can't read it without changing the app. The time from touch to the first
scroll event is reported per page (`first_scroll_ms`), not graded: it is measured on the main thread
and lags the compositor's first scrolled frame.

### Reported, not graded

| What | How |
|---|---|
| More-info detail | Per view: each dialog's response (the first frame of its open), when its animation finished, when its graph was drawn, the refreshes its open animation missed and its longest frame, the close, and whether HA showed a bottom sheet or a dialog (Clarity v3) |
| Animations it can handle | The animation test page's ladder: five families of animated elements, each doubling from 1 to 1024 (CSS transform, CSS opacity, SVG, canvas 2D, HA-like tiles whose numbers change every frame). The report gives the largest count held at 60 fps and at 30 fps. The same page has a scripted scroll stage and a JS workload (an HA-like state update for 400 entities, checked by a checksum) |
| Android's TTID | The `Displayed …: +Nms` line of each restart |
| CPU clock per view | Every core's `scaling_cur_freq` every 5 s (`GW_PERF_CPU_CLOCK_S`) from a view's open to its done, and each core's `cpuinfo_max_freq` once, when the sampler starts (read-only `cat`s of `/sys/devices/system/cpu/cpu*/cpufreq/` over adb). The sampler runs while the SurfaceFlinger poller is paused too: it is the one adb call inside timed windows, on purpose, and costs a fraction of a percent of a core. report.md gives each view's median and range in MHz beside the CPU's top clock, or each core's median when they differ (a big.LITTLE CPU's clusters), so a capped or boosted CPU shows (#244: the S1's power HAL holds 1.2 of its 1.608 GHz). The run's file has `cpu_khz` (`[view, time, {cpu number: kHz}]`, the numbers from the sysfs paths, so cpu10 is never taken for cpu2; a reading that crossed into another view, a recovery or the run's end is dropped), `cpu_max_khz` (`{cpu number: kHz}`) and `cpu_clock_s`; report.json has each tier's `cpu_mhz` (cores pooled) and `cpu_mhz_by_core` (by cpu number), and `graded.reference.cpu_max_mhz` and `cpu_clock_s` |
| Your dashboard | A restart back to your own dashboard: process start to its `PageStart`, its load to settled, and the whole wait from the force-stop. A run that doesn't finish within 120 s is recorded as an error |
| RAM on the reference dashboards | PSS per process at idle on the typical reference dashboard and the peak of the samples taken between the graded pages, and each switchable pane's share. See [RAM](#ram) |
| Memory on your dashboard | PSS of every app process (the app, Gecko's GPU, content and crash helper processes), the main process's App Summary, each process's peak RSS (`VmHWM`), the radio's processes, and `MemTotal`/`MemAvailable`, at idle on your dashboard and at the run's peak |
| CPU at idle | Jiffies of every app and radio process over 15 s, and the whole device's busy share |
| Idle frames | Frames your dashboard presents while nobody touches it |
| Panes on your dashboard | What turning each pane off saves; see [RAM of each pane on your own dashboard](#ram-of-each-pane-on-your-own-dashboard) |
| Device | Model, Android version and API, ABI, SoC, cores, max clock, RAM, display, app version, APK size, GeckoView version (from the running engine's user agent), temperatures |

Your dashboard isn't swiped. A drag on it can move a slider or flip a switch; the reference
dashboards stand in for it.

## The bands

Each measurement gets a band, as in Core Web Vitals: **good**, **needs improvement** or **poor**.
The thresholds live in one file, `tools/perf/page/rubric.json`. `grade.py` grades from it, and the
animation test page reads it to stop its ladder. The sources were checked on 2026-11-12, and the
Total Blocking Time row's on 2026-11-14.

| Measurement | Good | Poor | Source |
|---|---|---|---|
| Frame time, p95 | ≤ 17.17 ms (one frame at 60 Hz, 16.67 ms, plus 0.5 ms slack) | > 33.83 ms (two frames plus slack) | [RAIL](https://web.dev/articles/rail): "the maximum budget for each frame is 16 ms (1000 ms / 60 frames per second ≈ 16 ms)". [Android vitals](https://developer.android.com/topic/performance/vitals/render): slow frames take longer than 16 ms |
| Jank rate (frames over 16.7 ms) | ≤ 5% | > 50% | Poor: [Play Console](https://support.google.com/googleplay/android-developer/answer/9844486) "excessive slow frames", more than 50% of frames over 16 ms. Good: the same as a p95 within budget |
| Frozen frames (over 700 ms) | 0% (**a project choice**) | > 0.1% | [Android vitals](https://developer.android.com/topic/performance/vitals/render): frozen frames take longer than 700 ms; Play Console "excessive frozen frames" is more than 0.1% |
| INP: worst tap to next paint | ≤ 200 ms | > 500 ms | [Core Web Vitals INP](https://web.dev/articles/inp): good at or below 200 ms, poor above 500 ms |
| Open the view, navigation to the first screen drawn | ≤ 2.5 s | > 4 s | [Core Web Vitals LCP](https://web.dev/articles/lcp): "Good LCP values are 2.5 seconds or less, poor values are greater than 4.0 seconds". Since rubric 2 the open ends at the paint, as LCP's does; rubric 1 ran it to usable (drawn and the main thread free). See the note below the table |
| Blocking after the open, from the first screen drawn to usable | ≤ 200 ms | > 600 ms | [Lighthouse Total Blocking Time](https://developer.chrome.com/docs/lighthouse/performance/lighthouse-total-blocking-time), mobile: "0–200" green (fast), "200–600" orange (moderate), "Over 600" red (slow) (page last updated 2019-10-09, read 2026-11-14). Measured on animation frames, not tasks (Gecko has no Long Tasks API; [Usable](#usable)) |
| Restart, process start to a usable dashboard | ≤ 2.5 s (**a project choice**: LCP's bound) | ≥ 5 s | [Android vitals](https://developer.android.com/topic/performance/vitals/launch-time) counts a cold start that "takes 5 seconds or longer" as excessive (vitals apply it to TTID) |

**LCP's bands are lenient for an in-app navigation.** They are for a page load, network included.
Opening a view loads no page: it is a response to a tap. [RAIL](https://web.dev/articles/rail) asks
to "complete a transition initiated by user input within 100 ms", says that from 100 to 1000 ms
"things feel part of a natural and continuous progression of tasks", and that "beyond 1000
milliseconds (1 second), users lose focus on the task they are performing". Its load goal is for
pages: "be interactive in 5 seconds or less" on a first load, "under 2 seconds" for later ones
(checked 2026-11-13). So 2.5 s is generous for a view switch. The bands are unchanged here (rubric v5): changing them
is a rubric decision for its own PR, not a measurement fix, and it must not be made to move a
letter. A proposal, not adopted: grade a view switch against RAIL's 1 s.

A frames band is the worst of its three measurements. A window holds about 100 frames, so a single
frozen frame in it is 1%, which is poor. Frame times are snapped to whole display refreshes before
the percentile is taken, so timer jitter doesn't count a frame that made its refresh as late. The
0.5 ms slack covers displays that run slightly under 60 Hz. The budget stays 16.7 ms on a faster
display.

## The grade

**What it grades: feel, not video.** A wall panel is good when it feels quick and steady: when a
light or a sensor changes, the new state shows up at once, and when you flick through the dashboard
it doesn't stutter. People notice how fast a change appears, not the frame rate of its repaint, and
a panel scrolling at 50 frames a second feels fine even though it isn't 60. So (rubric v5, a user
decision on 2026-11-12):
- **Live updates** are graded on how fast a change reaches the screen. Their frame pacing is
  reported, not graded.
- **Scroll** is graded on its average frame rate over the swipes, with a cap on hitches. The 60 fps
  rule is the premium level, which earns **A+**, not A.
- Tap and opening the view keep their bars.

**Each run gets a level per task**, then the page takes the median level of the runs (the worse of
the two middles for an even count):

| Task | Premium | Good | Fluid |
|---|---|---|---|
| Scroll | the 60 fps rule: frames band good (p95 ≤ 17.17 ms, ≤ 5% late, nothing frozen) | average ≥ 50 fps over the swipes, and p95 ≤ 50 ms | average ≥ 30 fps, and p95 ≤ 67 ms |
| Live updates | good, and the frames that had a change to draw hold the 60 fps bar | 95% of changes on screen within 150 ms | within 250 ms |
| Tap | INP good (≤ 200 ms) | INP good | INP good |
| Open the view (the first screen drawn) | ≤ 2.5 s, or up to 4 s | the same | the same |
| Blocking after it | ≤ 200 ms, or up to 600 ms | the same | the same |
| Cameras (typical and heavy only; over WebRTC since protocol 6) | as good | first frame within 2 s, at least 95% of frames shown, no gap over 500 ms | first frame within 4 s, at least 85% shown, stalls under 1 s in all |
| More-info dialog | good, and the open animation's frames hold the 60 fps bar | the slowest dialog settled within 1 s | within 2 s |

**Why live updates aren't held to 100 ms** (Clarity v1, the maintainer, 2026-11-19). 100 ms is the limit
for a response to your own action: you touch, and something must answer within it to feel immediate
([RAIL](https://web.dev/articles/rail): "complete a transition initiated by user input within 100 ms").
That is what the tap measures, and its bar stays (the Pixel Tablet: 32 ms). A live update isn't a
response to you. It is a light someone else switched, or an automation. Its physical device usually
takes longer than 100 ms to change, so a 100 ms bar at p95 judges a gap nobody can see. So:

- **Good:** 95% of live changes on screen within 150 ms of Home Assistant sending them.
- **Fluid:** within 250 ms.
- **Premium:** unchanged: good, and the frames that had a change to draw hold 60 fps.

Every other bar is unchanged: tap, scroll, opening the view, the blocking after it (heavy's 0.6 s
too), cameras and more-info dialogs.

The evidence: the Pixel Tablet's quick run on 2026-11-19, with the test computer wired, showed
change to screen at a median of 62 to 73 ms and a p95 of 110 to 142 ms per view. Of that, 60 to 91 ms
is the change waiting for Home Assistant's frontend on the tablet's main thread before it is read
(the run's arrival waits): the browser's work, not the screen's. The bar applies to every frame.

**The more-info dialog's bars** (#211, project choices with their sources, set before any
frame had a dialog result). Good is 1 s: [RAIL](https://web.dev/articles/rail), "beyond 1000
milliseconds (1 second), users lose focus on the task they are performing". Fluid is 2 s, RAIL's goal
for a later load ("under 2 seconds"): the dialog fetches its history and builds its content, as a
small load does. HA's own animation is the floor: 300 ms for the S1's bottom sheet, 200 ms for the
centred dialog (both settled within 0.4 s in Chrome on a Mac). Premium asks the open animation's frames
for the 60 fps bar (frames band good), as live updates' premium asks it of theirs; the frames grade
nothing else, because they are the main thread's ([The more-info
dialog](#the-more-info-dialog-211)). The close is reported, not graded: it fetches and
builds nothing, and its 300 ms is HA's hide animation.

Below fluid, a measured scroll, live-update, camera or more-info level is `slow`. Over WebRTC (graded) a
camera's gaps and stalls are the receiver's own counts ("What is measured"). Over HLS
they come from `requestVideoFrameCallback`, which a busy main thread delays while the compositor
goes on showing frames, so there a stall can be the page's, not the stream's; each stream's
`stall_presented` (detail) is the cross-check: the frames rVFC's `presentedFrames` says were shown
inside the stalls. The camera bars are #174's
proposal, to be calibrated on the S1 before they're final (a decision for the maintainer, in its own
PR, never to move a letter); there is no separate premium bar for cameras. A page without streams
(light) has camera `none`, which every level accepts. The p95 caps are whole frames at
60 Hz plus the rubric's 0.5 ms slack: 50.5 ms is three frames, 67.17 ms four, as the user's 50 and
67 ms. Scroll is measured during the live updates (they keep running while the page scrolls), so
updates that make scrolling stutter cost the page its scroll level.

**A page is at a level when every task is**, and the letter is the first one whose pages are:

| Grade | Needs |
|---|---|
| A+ | Heavy premium: every task there holds 60 fps (the headline reads "A+, premium") |
| A | Heavy good |
| B | Typical (with its camera) good |
| C | Typical without a camera (`typical-nocam`) fluid |
| D | Light fluid |
| E | None of the above |

The camera-free typical and these steps came in pre-release rubrics 10 and 11 (the maintainer,
2026-11-13, #205), before any result under them. A report without `typical-nocam` has that page
"not measured", and is pre-release anyway (see "Versions").

The scale is A+, A, B, C, D, E (a user decision on 2026-11-12; an earlier draft of v5 had premium as
a label on an A). A+ needs only heavy, like A; when light or typical weren't measured, the grade is
provisional and the headline doesn't claim that every task holds 60 fps.

**No reference dashboard measured** (`--skip-reference`, or no graded run left a file) gives no
letter: the grade is refused ("the reference dashboards weren't measured"), not an E, and the
summary says it isn't comparable.

**A restart of 5 s or more caps the grade at C.** A panel that takes that long to come back to a usable
dashboard after a restart (an update, a crash, a power cut) is not an A+, A or B panel. A cap never raises a
letter. Letters rank by their place in the table, not by spelling.

**A frame without touch isn't graded on scrolling** (rubric 2, 2026-11-23). Its scroll is `n/a` on every page
(`grade.no_touch` in rubric.json), which every level accepts, so the letter comes from the other tasks and the
scroll is never missing. The runner sends it no swipes. See [No touchscreen](#other-glassworks-apps-and-android-6).

A measurement that is missing fails whatever needs it, and a missing restart caps nothing. Those give the
letter. The grade is marked **provisional** for what is missing only when that could change the letter
(Clarity v1, 2026-11-23). The grader grades twice: once for the best case, with each missing band (and a
missing restart) at the best it can be, and once for the worst, with each missing band failing and a
missing restart slow enough to cap. The start cap applies in both. When the two letters differ, the grade
is provisional, with what is missing listed. When they agree, the missing measurement can't change the
letter: `missing` still lists it, `missing_settled_by` says what decides the letter instead (the measured
tasks that keep it from the next letter up, or the restart's cap), and the headline says so plainly, for
example "Cameras on heavy weren't measured fully, but that can't change the letter: heavy already falls
short on blocking." So on the Pixel Tablet, heavy's camera stalls (getStats answered late) can't hold back
a B: heavy only counts toward an A, and its blocking is already poor.

Each band counts over the runs **attempted** (`plan.json`, written by `run.sh`), not only those that
finished: a run that failed or left no file, a task measured in fewer runs than attempted, or a scroll that
rests on rAF only also make it provisional, whatever the letter (`provisional_reasons`, the summary's `gaps`).
Why a camera is missing when every one of its runs was left open (stalls n/a, or a gap or a total the
counts' edge leaves open) is the one reason that goes with the missing measurement: when the letter can't
change, it goes to `grade.unmeasured` (the summary's `unmeasured`), and report.md lists it under "Not
measured, and it can't change the letter".

The headline names the tasks that keep the frame from the next letter up, with their numbers, and
says if the restart capped it. An A says what keeps it from A+ ("Short of A+ on large dashboards:
…"); an A+ says every task holds 60 fps on large dashboards. (On smoke5 the old headline read "E, … Large dashboards scroll at about 53 fps", which read against the letter.)
The report's table gives every task's value and band per page, and the task that holds each page
back. Another table gives each page's results at each place in the run order.

### The notes: what's good, what holds it back

Under the headline, every report has a short paragraph in plain words (`notes`, #201). It is built
by fixed rules in `tools/perf/notes.py` from the report alone, so the same report always gives the
same text. It never gives the letter or its meaning. It is a summary: every number it leaves out
is in the report body. At most four sentences and about 85 words (the maintainer's model, 2026-11-13,
is 83; the test caps it at 85), plus one when the panel lost a page and one when the grade is provisional:

1. **What's good**, in one sentence: the heaviest page that reaches a level, and what is quick,
   smooth or fluid there ("Light dashboards feel good: …"). Without a level, the page with the most
   good or fluid tasks ("… come closest"). When it is typical without a camera and typical with one
   doesn't reach a level, the sentence says what the camera costs, with its worst limit: "Typical
   dashboards feel good without a camera: …; with one, it shows 40% of its camera's frames." That
   limit isn't quoted again in the next sentence. Pages read as "typical" (without a camera) and
   "typical with a camera"; a pre-release report keeps "typical" for its one typical.
2. **What holds it back**, in one sentence, at most three limits. Each failing task is quoted
   once, with one rounded number, on the lightest page it fails on, and ranked by how far that
   number is past its fluid bar (rubric.json: scroll 30 fps and 67 ms, a change 200 ms, a tap
   200 ms, open the view 4 s, a camera's first frame 4 s, 85% of its frames shown and 1 s of
   stalls, a more-info dialog 2 s: from the runs whose level fails, the late ones' median time, or
   "doesn't open a more-info dialog fully" when at least as many of them never settled). So the number shown is why it was picked. The lightest page is on purpose: the text
   names the first dashboard that fails, not the worst, so heavy's bigger misses stay in the body. Only a number that fails is quoted: a scroll
   with enough frames on average but uneven ones gives its slowest frames, and a camera its first
   frame, its frames shown or its stalls, whichever is furthest past, from the runs whose camera
   level fails (the grade takes the median of per-run levels). The restart is named only
   when it caps the letter.
3. **The likely cause**, in one sentence, only when the report holds evidence for it. The first
   that applies:

   | Likely limit | Said when | Its evidence, in words |
   |---|---|---|
   | The browser's main thread | A slow scroll is `limited_by: main-thread` (the page's frames, rAF, worse than SurfaceFlinger's), or a camera page gets video late (below) | "it stays busy" when the app keeps 25% of a core or more on typical untouched (`reference_idle.cpu_app_pct_of_core`); "cameras get video late even though the (hardware) decoder keeps up" when, on a failing camera page, every run that has all three kinds of evidence shows segments arriving 2 s or more apart (1 s segments), a fetch's p95 under 500 ms and its decoder dropping at most 1% (never combined across runs); else "the page draws too few frames" |
   | The GPU | A slow scroll is `limited_by: compositor` (SurfaceFlinger's frames worse than rAF's) | "the screen drops frames the page drew" |
   | The link | A failing camera page's segment fetch p95 is 1 s or more | "camera video takes a second or more to fetch" |
   | The test itself | The fake Home Assistant sent a graded view's change more than 200 ms late | "its fake Home Assistant sent changes late" |

   Before it, when the panel closed a page's browser page in some run ("A page the panel closes"), one
   sentence says where, in how many runs and why: "On typical with a camera, the page was lost in 3 of 3
   runs: the panel ran out of memory and its browser page was closed (low-memory killer)." A page lost in
   at least half its runs, which decide its median, isn't quoted in the limits sentence: its numbers
   would be the deadline that stands for those runs.
4. **Memory**, when RAM was measured: "Memory isn't the limit (X of Y MB)", the app's peak against
   its budget, when both the peak and the device's available memory are within the frame's budgets
   and no page was lost to the low-memory killer;
   "Memory is a limit" with the budget it misses; or only the peak ("The app's memory peaks at X
   of Y MB") when available memory wasn't read.
5. **Provisional**, only when the grade is: one plain clause from the run's protocols and what is
   missing, not the reasons' wording. What is missing counts only when it could change the letter; the
   headline says what can't. A run from before Clarity v1 reads "measured with a
   pre-release version of Clarity" (", without cameras" when its camera tasks are missing, since
   cameras came with pre-release protocol 5); every other reason and missing task is counted ("2 things weren't measured
   fully"). The report keeps the reasons in full (`grade.provisional_reasons`). report.md leaves
   this clause out under its headline, which already gives the reasons.

Not said: heat, unless something shows throttling (the run records temperatures, in `thermal`, but
no clock speeds), and nothing on long tasks, which the run doesn't record. The notes judge tasks by
the report's rubric, so `notes.rubric` names its version. On the S1:

| Run | Notes |
|---|---|
| s1cam3 (protocol 5, 3 runs) | Light dashboards feel good: taps, live updates and opening a view are quick, and scrolling is fluid. Bigger dashboards slow down: typical scrolls at 25 fps and takes 6.6 s to open, and heavy takes 0.7 s to show a change. The likely limit is the browser's main thread: it stays busy (97% of a core on typical with a camera playing), so cameras get video late even though the hardware decoder keeps up. Memory isn't the limit (889 of 988 MB). |
| s1v7 (protocol 4, provisional under the protocol 5 rubric) | Light dashboards feel good: taps, live updates and opening a view are quick, and scrolling is fluid. Bigger dashboards slow down: typical scrolls unevenly (slowest frames 80 ms) and takes 4.8 s to open. The likely limit is the browser's main thread: it stays busy (46% of a core on typical with nothing touched), and the page draws too few frames. Memory isn't the limit (743 of 988 MB). Provisional: measured with a pre-release version of Clarity, without cameras. |
| smoke11 (protocol 4, 1 run, provisional) | Light dashboards feel good: taps and opening a view are quick, and scrolling and live updates are fluid. Bigger dashboards slow down: typical scrolls unevenly (slowest frames 80 ms) and takes 5.2 s to open, and heavy takes 230 ms to answer a tap. The likely limit is the browser's main thread: it stays busy (46% of a core on typical with nothing touched), and the page draws too few frames. Memory isn't the limit (748 of 988 MB). Provisional: measured with a pre-release version of Clarity, without cameras. |

The task levels (`scroll_levels`, `update_levels`), the page levels, the letters (A+ first) and the
restart cap live in `tools/perf/page/rubric.json` (`grade`), next to the thresholds.
`tools/perf/grade.py` and `tools/perf/grade.ts` both read them,
and neither holds a rule of its own. `tools/perf/test/grade-cases.json` lists each task's pass rule
and each letter's boundary with its answer: a frame that just meets a level or a letter gets it, and
moving any one requirement past its edge drops it. `test_grade.py` checks Python against it and
`grade.test.mjs` checks TypeScript, so a rule changed in one language only fails.

### Not tuned to any frame

The bands come from the cited guidelines. The pages, the tasks, the levels and the letter table are
project choices. They changed twice, both times for product reasons, and each time before any frame
had results under the new definition:
1. The first letter measured how much **animation** a frame runs (light, typical and busy animated
   pages). The S1 ran under it in smoke runs and graded D.
2. The maintainer then put **scrolling a big dashboard** ahead of animation.
3. Then came the final definition: **every wall-panel task** (tap, scroll, live updates, opening a
   view, restart) on dashboards of growing size. No frame had scroll or wall-panel results when it
   was set. The S1's D under the animation pages was not the reason for either change.

Rubric 2's rule for a frame without touch (its scroll is `n/a`) is the same kind of choice: it holds for every frame
that has no touchscreen, it changes nothing for a frame that has one, and it came before any such frame had a result
(the Mirror had none published then).

The S1 is just the first frame graded under it. Its result goes in this document whatever letter it
gets. The thresholds, the pages and the tables must not be moved to change a particular frame's
letter. Change them only for a reason that holds for every frame, in their own PR, with the docs and
tests.

The smoke5 fixes (warm-up, turning order, the shared first screen, the change frames, the first-paint
settle rule) are measurement fixes: each made a page's numbers depend less on where it ran or on a
clock artefact. They became protocol 1. None moved a threshold or a letter rule. Rubric version 4
only moved the existing letter rules into `rubric.json`, unchanged, so the site can read them.

**Rubric v5 is different: it came after the S1 had results.** Under v4 the S1's protocol 1 runs
graded E, held back by scroll and live-update frame pacing. The user then decided, for a reason that
holds for every frame, that a wall panel is judged on feel, not video: how fast a change shows, and
whether scrolling stutters (see [The grade](#the-grade)). The new bars are project choices:
- **Live updates:** 100 ms is RAIL's bound for a response to feel immediate (["complete a transition
  initiated by user input within 100 ms"](https://web.dev/articles/rail)), applied to a state
  change; 200 ms is INP's "good" bound.
- **Scroll:** 50 and 30 fps, with their p95 caps, are the user's numbers.

They weren't picked to give the S1 a letter. Re-graded offline under v5, its s1v5 runs give C:
typical scrolls at 49.6 to 50.8 fps, just under the good bar in two of three runs. A letter that
lands where the user guessed it would isn't evidence either way. The record of what changed and when
is here so anyone can check.

**Protocol 4 (2026-11-13) changed what is measured, not how it is judged.** The S1's first full run
under protocol 3 graded A (see [the example](#example-aqara-s1-plus)). The maintainer questioned it:
the panel feels slower than that, and the reference pages were an imitation of HA's cards, lighter
than HA's frontend (heavy had 4463 elements against about 8000) and without its work (loading the
app, the websocket, Lit's rendering, HA's state handling). So the reference dashboards became HA's
real frontend, and restart now ends at a usable dashboard. No threshold, level or letter rule moved:
rubric v5 stands. The protocol-3 A is superseded. The S1's protocol 4 result is a D (s1v7,
2026-11-13; [Example](#example-aqara-s1-plus)).

### Restart: back to a usable dashboard

Android vitals time a cold start to the launched activity's first frame (TTID). On the S1 that is
MainActivity's first window: the kiosk's own native loading screen ("Starting up", "Connecting to
Home Assistant…"), not a splash activity (the `:boot` direct-boot home activity ships disabled). Any
kiosk can draw a loading screen within 100 ms, so TTID doesn't separate frames; it is reported, not
graded. The S1's timeline after a force-stop (smoke run, 2026-11-12, your own dashboard):

| Event | After the force-stop |
|---|---|
| App process starts | 0.22 s |
| `Displayed` (MainActivity: the loading screen) | 0.40 s |
| First GeckoView frame | 3.2 s |
| Your dashboard's `PageStart` | 4.5 s |
| Your dashboard settled | 11.5 s |

What you wait for after a restart is a usable dashboard. Timing your own would make the grade depend
on your Home Assistant, and opening a reference page during the restart doesn't work: until
GeckoView's session is up, the kiosk ignores `open` (#103's pins; seen on the S1 too), and once it is
up, the request races the kiosk's own dashboard load. So protocols 1 to 3 ended the restart at
**engine ready**, GeckoView's own first page (`about:blank`).

Protocol 4 changes the dashboard the kiosk shows instead, for the graded part only (a user decision,
2026-11-13: "I'm okay with it changing for the test and then it changing back"). The kiosk shows
the start view of the fake Home Assistant's reference dashboard, so after the force-stop it comes
back to it by itself, by the same path that brings it back to yours (its start loads the dashboard).
Until 2026-11-19 that was the kiosk's dashboard setting; since then it is the test hold's own test
dashboard, which leaves the setting and the sign-in alone (see
[What it changes on the device](#what-it-changes-on-the-device)). The page loads at the same point of
the start either way, so the measurement didn't change. The restart ends when that dashboard is usable, as
defined for open the view. Its start, the process start, is stamped on the device in logcat, and its
end is on the page's clock, tied to logcat's by the page's log line, so the harness's adb round trips
aren't in it. Engine ready is still reported (`engine_ready_ms`).

**How the 5 s bound compares with Android vitals.** Vitals apply 5 s to TTID, the first frame of any
kind. Here the same 5 s covers the whole way to a dashboard you can use, so it is much stricter than
vitals, and stricter than the engine-ready bound of protocols 1 to 3.

### Zoom and viewport

The kiosk's zoom is a Home Assistant entity (0 to 3; 0 is "auto"). It sets Gecko's device pixel
ratio, which changes how much of a dashboard fits on a screen: at a large zoom, the same page holds
fewer cards per screen and needs more scrolling. So the grade compares frames only at their
**default** zoom. The report records each page's CSS viewport and device pixel ratio. The default
ratio is the display's density / 160 (1.5 on the S1: density 240). If a page ran at another ratio,
the grade is **refused** ("not graded"), with the reason. It isn't marked provisional, because a
letter would then look comparable when it isn't. If the density wasn't recorded, the check can't
run, and the grade is provisional.

## What the grade does and doesn't tell you

- It describes **this frame running this app version** on the reference dashboards, at its default
  zoom. It doesn't change with your Home Assistant, your dashboard or your network. It doesn't say
  how fast **your** dashboard is: that is in the report's real-use section, which isn't graded.
- **Heat matters.** A warm device throttles its CPU. The runner waits for the CPU to drop below
  50 °C and logs every thermal zone at each stage (`thermal.tsv`). Compare runs only at similar
  temperatures.
- **Background load matters.** The radio, voice wake word and Sendspin run during every part, as
  they do in daily use. That is the one way the panel's own setup can reach the grade: a pane that
  uses the CPU all the time (on the S1, the wake word: [idle-cpu.md](../frames/aqara-s1-plus/research/idle-cpu.md), #105)
  slows the reference dashboards too. The device's idle busy share is in the report.
- **Screen brightness** doesn't change the work the app does. Over a long run it can add heat.
- The reference dashboards use HA's default card types. Custom cards, cameras and maps are heavier
  than the heavy page.
- The tap test taps one toggle. The more-info dialog is graded too, from the event
  the tap fires: three dialogs of HA's own, not a custom card's.
- The device's busy share at idle includes the runner's own `dumpsys` calls. While a page goes
  live, is tapped or scrolls, the runner doesn't call `dumpsys`.
- The memory budget's `ok` is `false` if any check fails. It is `null` when none fails but a
  budgeted value wasn't measured, and `true` only when every check was measured and passed.

## Not in the grade

These have no published standard, or depend on your own Home Assistant:

| What | Band or budget | Why this choice |
|---|---|---|
| JS state update (median) | good ≤ 16.7 ms, poor > 50 ms | Good: the update fits in one frame. Poor: past RAIL's 50 ms input-processing budget ("process user input events within 50 ms"), which is also the [Long Tasks](https://www.w3.org/TR/longtasks-1/) cut-off (that draft is now discontinued). The 400-entity size stands for a larger home |
| Animations it can handle | reported only | A wall panel mostly shows still dashboards; animation capacity is useful to compare frames, not to judge a panel |
| App memory, peak PSS of all app processes (on the reference dashboards, and on yours) | ≤ 50% of `MemTotal` | An always-on panel shares its RAM with the system, vendor daemons and the radio. Half for the app leaves room for them |
| Device memory, lowest `MemAvailable` during the run | ≥ 15% of `MemTotal` | Headroom for the system and the radio. A first choice, not a measured limit; change it in the same PR as the evidence |
| A pane's RAM, its PSS on by itself against every pane off | the frame's `ram_pss_max_kb` for that pane (48 MB for `assist` and `sendspin` on the S1, 16 MB for `wall-base`, `time-zone` and `diagnostics`; 48 MB for `assist`, `sendspin`, `camera` and `diagnostics` on Generic Android) | The S1's were set on 2026-11-13, before the first per-pane measurement; revisit with the first one. Generic Android's follow one rule (below). A pane that needs more should say why in its PR |
| CPU at idle | reported only | No standard. Lower means less heat and power on a panel that never sleeps |
| Your dashboard's start and load | reported only | Your dashboard, your network and your Home Assistant, so not comparable between panels |
| Idle frames | reported only | A still dashboard should present almost nothing. A clock or an animated card adds frames |

The budgets are the frame's own, in `frames/<id>/budgets.json` (#68): `ram` for the frame and
`panes.<id>` for each pane. A frame without one that names a `base` in its `design/frame.json` has
its base's: the Pixel Tablet runs Glassworks for generic Android, so `--frame google-pixel-tablet`
reads `frames/generic-android/budgets.json`, for `--pane-ram`'s switches and for the report (#23).
`tools/perf/budgets.json` is the default for a frame with neither; a frame's `ram` names only the
keys it changes, and its panes are its own. Clarity reports a budget as `ok`, `over` or `n/a`; it
doesn't fail the run, because the device run isn't CI.

**Generic Android's pane budgets** (#23) follow one rule: the smallest 16 MB step that is at least
the pane's share plus its tolerance, and at least four times the tolerance. The second part is there
because the report gives no verdict on a tolerance over a quarter of the budget (below). No frame
running that app has a per-pane run yet. The Pixel Tablet's graded run (2026-11-19) has
`ram.panes: []`, and `--pane-ram` couldn't run there then, because it didn't read the base frame's
switches. So `assist` and `sendspin` take the S1's best per-pane numbers, from the same code (s1p3
without its cold sample: Assist +2.3 MB within ±10.2, Sendspin −4.4 MB within ±10.4, so four times
10.4 MB is 41.6 MB, and the step is 48 MB). The camera and diagnostics have never been measured, and
take the same 48 MB until they are. Notifications and the Bluetooth proxy aren't switched, for the
same reasons as on the S1 (below). The tablet's three idle samples on the typical dashboard spread
±36.9 MB over the whole app (`perflib.noise_band`; `main` ±15.5 MB, `gpu` ±17.1 MB, `tab` ±12.9 MB).
If its `--pane-ram` samples are as noisy, every share there reads not measurable against 48 MB, and
the run sets the budgets again by the same rule. `ram` stays the default: on that run the app's peak
PSS was 1279 MB, 17% of the tablet's 7.27 GB, and the lowest `MemAvailable` 2311 MB, 31%.

### How Home Assistant animates (reference for the animation detail)

An earlier version graded animated reference pages. They were replaced before they ran on a panel,
but what they copied from HA's source (commit
[`77170b4`](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67)) still describes
how HA animates, for any future animation detail page:

| Element | How HA does it | Source |
|---|---|---|
| Pulsing state icon (alarm triggered or arming, lock jammed) | `@keyframes pulse` on the state icon: opacity 1 → 0 → 1, `1s infinite` | [`icon_color_css.ts` L8–L21](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/common/style/icon_color_css.ts#L8-L21) |
| Spinner (`ha-spinner`), the only spinning element on a dashboard | Web Awesome's spinner (HA's fork 3.14.0-ha.2): the SVG rotates with `transform` (3.5 s in HA) and its arc animates `stroke-dasharray`/`stroke-dashoffset` (1.5 s), repainted every frame | [`ha-spinner.ts` L45](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/ha-spinner.ts#L45), [spinner styles](https://cdn.jsdelivr.net/npm/@home-assistant/webawesome@3.14.0-ha.2/dist/components/spinner/spinner.styles.js) |
| Alert badge pulse | opacity 0 → 0.3 on a `::before`, 1 s, alternating | [`hui-alert-badge.ts` L179–L223](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/panels/lovelace/badges/hui-alert-badge.ts#L179-L223) |
| Charts | ECharts on a canvas, a 500 ms animation on each update | [`ha-chart-base.ts` L1224](https://github.com/home-assistant/frontend/blob/77170b447f88b74da78a5288c421d39339253c67/src/components/chart/ha-chart-base.ts#L1224) |

HA's own cards don't spin state icons; custom cards such as Mushroom do, with a CSS `transform`.

## RAM

Not graded (#68). RAM is read with `dumpsys meminfo` (PSS per process) and `/proc/<pid>/status`
(`VmHWM`, the peak RSS since the process started), per process: `main` (the app), `boot` (`:boot`,
the direct-boot home activity, when it runs), `gpu` (Gecko's GPU process), `tab` (Gecko's content
processes, summed) and `crashhelper`. The radio's processes run outside the app and are reported on
their own.

**On the reference dashboards** (the report's `ram`), so the numbers don't depend on your Home
Assistant:

- **At idle**: after each graded run, the runner opens the typical reference dashboard (with no
  nonce, so nothing on it is timed and the live updates stay off), waits 30 s, samples CPU and
  frames for 15 s, then RAM (label `ref-idle`). The report gives the median of the runs per process.
- **Peak, sampled between the graded pages**: after each graded page, while the screen is still and nothing is
  timed, the runner samples RAM. The largest of those samples is the peak. It is taken between tasks,
  not inside them (a `dumpsys` during a task would disturb what it measures), so a short peak inside
  a task can be missed. `VmHWM` (each process's peak RSS since it started) includes such a peak, and the
  report gives it beside the PSS peak; RSS counts shared pages in full, so it bounds the PSS peak from above
  and doesn't measure it. It covers only the processes still running, with a readable `VmHWM`, when it
  is read.
- **Per pane**, with `--pane-ram assist=on,sendspin=on,wall-base=on,time-zone=on,diagnostics=on,mic=on`
  on the S1, or `--pane-ram assist=on,sendspin=on,camera=on,diagnostics=on,mic=on` on Generic Android
  and the frames based on it, such as the Pixel Tablet (the settings now, put back afterwards). The kiosk is pointed at the typical reference dashboard. In each run the runner turns every switchable
  pane off and takes an idle sample (`ref-panes-off`), then turns each pane on by itself and takes one
  (`ref-<pane>-on`). Before the first run it does one **warm-up cycle**, every state once, whose
  files (its idle and pane files, and its `thermal.tsv` rows) go to `warmup/` and aren't in the
  report (#186: the first sample ran low, below). A pane's share is the median of its "on" samples
  less the median of the "off" samples, both counted **without `:boot`** (below). With `--runs 3`,
  each median is of three samples.
- **The tolerance** is the larger noise band of the two sets (`perflib.noise_band`): twice an
  estimate of one sample's standard deviation σ. Up to seven samples the estimate is the range
  divided by d2(n) (1.128, 1.693, 2.059, 2.326, 2.534, 2.704 for two to seven: the expected range
  of n normal samples, the control-chart constant). From eight it is 1.4826 × the median absolute
  deviation, so one odd sample doesn't set it; there it averages about 1.8 σ, a little under the
  range's 2 σ, and reads a zero difference as measurable in under 1% of runs (simulated). The MAD
  at five averaged only 1.64 σ (5% false "measurable"; the verifier on #196), so the range runs to
  seven. Both estimate σ, so the band doesn't grow with more runs, unlike max − min. Not the median absolute deviation at three: it is the smaller of the two gaps, so two
  samples that agree gave 0 whatever the third (the verifier on #196: 11% of normal draws under
  0.2 σ). Under two samples on either side there is no tolerance (`null`) and the share isn't
  measurable. CPU's tolerance is the same band.
- **What the tolerance is, and isn't.** It is about 2 σ of one sample, not a confidence interval.
  The difference of two three-sample medians has a standard deviation of about 0.95 σ, so the band
  is about two of those. A share is called measurable when it is outside the band. With no real
  difference and normal noise that happens in about 3.5% of runs at three samples (simulated:
  `test_report.NoiseBand`, which also checks that the band averages 2 σ and is under 0.2 σ in
  under 2% of draws). A share "within tolerance" may still be a real cost, too small for this
  many samples to show. The switches are the kiosk's own extras, named in the frame's `budgets.json`
  (`switch`): `voice_enabled` for `assist`, `sendspin_enabled` for `sendspin`, and `wall_base_enabled`,
  `time_zone_enabled` and `diagnostics_enabled` for the others (#67). `mute` names
  Assist's microphone mute (`mic_muted`) and `state` what the kiosk's log shows while the pane runs
  (below). Turning Assist off
  this way stops the whole pane, unlike the mute switch (below), which leaves its model loaded. It
  also leaves out the timers pane, which only comes from the voice assistant, so `assist`'s share
  includes the timers'.
- **Each sample is checked** against the kiosk's log (`perflib.pane_state`): the ESPHome line the
  app logs at start (`voice=true|false`), then `Sendspin player starting`, `Microphone open`,
  `Microphone muted (released)` or `Microphone unmuted`, and `Pane <id> on|off` for each pane with a
  switch (`state` `pane:<id>`, #67). A sample that has no record for a pane (a run from before its
  switch) isn't counted against that pane. The runner tries a state up to three times
  (`panes-<label>-<n>.json` has what it asked and what ran), and the idle sample records what ran
  (`panes_state`). The report counts only samples that ran what their label says, gives how many it
  left out (`excluded`), and has no number for a pane with none left (`error`). Samples from before
  this check say `verified: false`.
- **Assist off by its mute.** the S1 app 0.4.3 and earlier save `voice_enabled` asynchronously and
  then exit at once to restart, so the write is lost and voice stays on. In s1v7 (131f6da,
  2026-11-13) every switch was lost: the "panes off" samples ran Assist and Sendspin, and Assist read
  −0.4% of a core against
  [#105](https://github.com/GlassworksHQ/glassworks/issues/105)'s 37%. So the runner also
  mutes the microphone (`mic_muted`) for "off" states, which the kiosk applies live, and stops after
  two tries at `voice_enabled`. When voice stayed on, Assist's share is its running cost only (the
  wake word, recorder and inference; [idle-cpu.md](../frames/aqara-s1-plus/research/idle-cpu.md) step 7), not the code and
  model it loads. The report says so (`off_by: "mute"`). s1v7's pane numbers are not valid; the S1's
  are s1p1 and s1p3 ([Example](#example-aqara-s1-plus)).
- **The budget check needs enough evidence.** A pane's budget is checked (`pane_budget`, and the
  summary's `budget_ok`) only with at least three samples on each side
  (`grade.PANE_BUDGET_MIN_SAMPLES`) and a tolerance at most a quarter of the budget
  (`grade.PANE_TOLERANCE_MAX_OF_BUDGET`). Otherwise it says **not measurable** (`measurable: false`,
  `ok: null`), not "ok". Even then "ok" only means the share is under the budget; with a tolerance
  of about 2 σ, a pane a little over could read "ok" now and then. Read a null `budget_ok` as
  unknown, not as over. On the S1 (s1p3, 2026-11-13) the tolerance was ±31.6 MB (max − min), two
  thirds of the 48 MB budget, and the report still said "ok"; s1p1, one sample a side, read
  ±0.0 MB and "ok". Two things made it wide, neither of them a pane (#186):
  1. **The first sample was low.** In both s1p1 and s1p3 the first sample (`ref-panes-off-1`) had
     the GPU process at 141 to 142 MB, against 155 to 157 MB in every later one. In s1p3 the app was
     17.4 to 33.3 MB under the other samples (with `:boot` left out). It set the panes-off spread
     (564.7 to 596.4 MB). The warm-up cycle is for this. Why the first sample is low is untested.
  2. **`:boot` comes and goes.** The direct-boot home activity's process (29.2 MB) was in 2 of 12
     samples (s1p1's `ref-sendspin-on-1` and s1p3's `ref-sendspin-on-3`), not in the other two
     Sendspin samples. That is what s1p1's Sendspin +50 MB was. Why it runs then is untested; it is a
     HOME activity, so a restart may start it. It isn't a pane, so a pane's share leaves it out, and
     the report says how often it ran (`boot_samples_on`, `boot_samples_off`) and its size
     (`boot_pss_kb`). The app's RAM at idle (`reference_idle`) and its peak (`graded_peak`) still
     count `:boot`: they are what the app used, so they and the pane shares add up differently.

  On s1p3's own samples (computed with this report, 2026-11-13, not a new run):

  | s1p3, as | Assist PSS (tolerance) | Sendspin PSS (tolerance) | Budget check |
  |---|---|---|---|
  | reported then (max − min, `:boot` in) | +5.2 MB (±31.6) | +0.3 MB (±31.6) | "ok" (wrongly) |
  | recorded, read now (noise band, `:boot` apart) | +5.2 MB (±37.4) | −1.5 MB (±37.4) | not measurable |
  | without the cold first sample (as the warm-up should give) | +2.3 MB (±10.2) | −4.4 MB (±10.4) | not measurable (2 "off" samples) |

  Read now, the recorded tolerance is wider than before: with three samples the cold one counts in
  full, as it should. The last row leaves `ref-panes-off-1` out, so its "off" set has two samples
  and gets no budget verdict; both panes' tolerances (Assist ±10.2 MB, Sendspin ±10.4 MB) are
  within a quarter of the 48 MB budget. Each pane's tolerance is the larger of its own "on" set's
  band and the shared "off" set's, so the panes differ only when an "on" set is the noisier. A
  run with the warm-up has three warm ones. Whether it brings the S1 near ±10 MB is for the next
  run on the panel (after #181).
- **Not switched**: since [#67](https://github.com/GlassworksHQ/glassworks/issues/67) every
  pane has an on/off, but four aren't measured on their own (`not_switched` in `budgets.json`). The
  radio's on/off is its mode, which Clarity leaves as it is, so the panel's Zigbee or Thread network
  stays up; its processes are measured where they run. Timers come only with voice, so they are inside
  `assist`'s share. The Bluetooth proxy scans only while Home Assistant listens, which the reference
  run doesn't, so on by itself it would measure an idle pane (#121). The S1 has no camera, so the
  camera pane can't run there; its code still ships, so it keeps a size budget (#117). The report
  lists all four, with why. The wall base, time zone and diagnostics shares
  have not been measured on a panel yet. Those four are the S1's. Generic Android and the frames
  based on it switch the camera and diagnostics too, and leave out three: timers, notifications and
  the Bluetooth proxy, for the same reasons. Its camera, diagnostics, Assist and Sendspin shares have
  not been measured on a device yet.

**Why not an emulator in CI** (assessed 2026-11-13, from the build and the panes' needs; not tried).
#68 asked whether an x86_64 Android emulator in CI could measure RAM per pane. Not now:
1. The kiosk ships `armeabi-v7a` and `arm64-v8a` APKs only. An x86_64 run needs a third ABI build of
   the app and of GeckoView; an ARM APK on an x86_64 image runs through translation, whose memory
   isn't the panel's.
2. Gecko's GPU process holds a large part of the app's PSS (about 210 MB of 700 MB on the S1, s1v6),
   and that depends on the graphics driver: the emulator's is not the S1's PowerVR Rogue GE8300
   (read on the panel 2026-11-12, [boot-animation.md](../frames/aqara-s1-plus/research/boot-animation.md)).
3. The panes need what an emulator fakes or lacks: Assist a microphone and a voice pipeline,
   Sendspin a Music Assistant server and audio output, the radio its serial port and the root side.
   Their memory without those isn't their memory on a panel.

So **the device run is the source of RAM numbers**, and CI checks only the APK size, which needs no
device.

### RAM of each pane on your own dashboard

The older method, on your dashboard and through Home Assistant, still works and is reported under
`real_use.panes`: turn a pane off through its Home Assistant entity and compare with everything on.
You need an admin account in Home Assistant, and a panel you are allowed to disturb. The runner
doesn't talk to Home Assistant. You toggle the entity, and take an idle sample after each change.
Alternate on and off, three times each. Use a separate output directory: the full run's `all-on`
samples come 30 s after a restart, and a panel that has been up for a while may use more memory, so
mixing them could bias the difference.

```sh
tools/perf/run.sh --frame aqara-s1-plus --out perf-s1-panes --idle-only all-on
# turn the pane off in Home Assistant, wait 10 s
tools/perf/run.sh --frame aqara-s1-plus --out perf-s1-panes --idle-only sendspin-off
# turn it back on, wait 10 s, and repeat both steps twice more
# finish with the pane back on, as you found it
```

For `assist`, "off" means the mute switch is **on**: `--idle-only assist-off` is taken with
`switch.<panel>_mute_microphone` on, and you finish by turning it off again.

Each `<pane>-off` label is compared with the `all-on` samples. If a difference is smaller than the
spread of the `all-on` samples, the report says "within noise". On the S1:

| Pane | Entity (turn off → on) | What turning it off stops |
|---|---|---|
| `sendspin` | `switch.<panel>_music_assistant_player_sendspin`: off, then on | The Sendspin player and its connection |
| `assist` | `switch.<panel>_mute_microphone`: **on**, then off | The microphone and wake word detection. The wake word model stays loaded, so this measures the running cost, not all of the pane's memory |
| `radio` | (not switched) | The radio's own processes (`otbr-agent`, `cpcd`, `zigbeed`) are measured where they run. Changing the radio mode would change the network |
| `wall-base` | none | Only if a base is fitted (`sensor.<panel>_base`) |

## The report (`glassworks.perf/4`)

`report.json` has one frame per file. Repeated measurements are given as
`{"median", "min", "max", "n"}`.

| Key | What |
|---|---|
| `schema` | `"glassworks.perf/4"`. A field that changes meaning gets a new version (`/4`, 2026-11-12: `grade.bands` carries scroll and live-update levels, rubric v5) |
| `clarity` | The Clarity version the pages ran, as they recorded it: `3` now, `2` or `1` for an earlier run (rebuilt by v3's code it keeps its own, and is re-measured); `null` for a report from before v1 (pre-release) or of mixed versions, which the site shows as "re-measure". See [Versions](#versions) |
| `protocol_version` | The measurement protocol the pages ran (`null` if the runs disagree). See [Protocol and rubric versions](#protocol-and-rubric-versions) |
| `measured_on` | The run's date (UTC, from the computer that ran it) |
| `runs_attempted` | Graded runs attempted (`plan.json`), the count each band is over |
| `frame` | `id`, `name`, `device` (model, Android, API, ABI, SoC, CPU, RAM, display with density and refresh, temperatures, `touch`: whether the frame has a touchscreen, from its frame data; a report from before it has none and is graded as having touch), `app` (package, version, APK bytes on the device, GeckoView version) |
| `apk` | One entry per APK given with `--apk`: `abis`, `bytes`, `parts_bytes` (dex, native, assets, resources, other) |
| `notes` | What's good and what holds it back, in words ([The notes](#the-notes-whats-good-what-holds-it-back)): `good` and `limits` (a sentence each, or `null`), `causes` (the likely cause and memory, each a sentence, either may be missing), `text` (the paragraph), `rubric` (the rubric version it judged by) |
| `thermal` | Each stage's thermal zones from `thermal.tsv`, in order: `phase`, `zones` (°C) |
| `ha_transport` | Only with `--ha-transport lan` or `auto`: `transport` (`lan` or `adb`), `chosen_by` (`flag`, `auto`), `reason`. With `lan` the fake went over the LAN, not `adb reverse` ([Home Assistant over the LAN](#home-assistant-over-the-lan)). Absent: through `adb reverse`, as before |
| `webrtc_transport` | Protocol 7: `transport` (`lan-udp`, `lan-turn`, `adb`), `chosen_by` (`auto`, `flag`), `reason` (lanprobe.py's), `limited` (over adb with heavy's cameras measured: why heavy's camera result may be the link's; else `null`). `null` before protocol 7 |
| `fake_ha` | What the fake Home Assistant's log (`fakeha.jsonl`) says: `commands` (the frontend sent), `unknown` and `failed` (per command; any makes the grade provisional), `stream_sent`, `stream_late_max_ms` (the graded views' streams only; over 200 ms, one update interval, is provisional), `stream_late_detail_max_ms` (the views that aren't graded: the HLS copies and a warm-up; detail, a line in report.md, never provisional: #205, s1rtc8's one 2 s burst on typical-hls). Each change belongs to the view open when it was sent. `null` without the log, which is provisional |
| `ram` | Not graded. RAM on the reference dashboards (`null` if none was sampled): `reference_idle` (`samples`, `app_pss_kb`, `by_role_pss_kb`, `main_kb`, `device_used_pct`, `cpu_app_pct_of_core`), `graded_peak` (`samples`, `app_pss_kb`, `by_role_pss_kb`, `app_rss_hwm_kb`, `mem_available_min_kb`), `panes` (each: `id`, `method` `on-alone`, `samples_on`, `samples_off`, `pss_kb`, `pss_tolerance_kb` (`null` under two samples a side), `pss_measurable`, `by_role_pss_kb`, `cpu_pct_of_core`, `cpu_tolerance_pct`, `cpu_measurable`, `verified`, `excluded`, `boot_samples_on` and `boot_samples_off` (samples `:boot` ran in, left out of the share), `boot_pss_kb` (its median PSS, or `null`), and `off_by: "mute"` when Assist was off by its mute alone; or `id`, `method` and `error` when no sample ran as asked), `screen_off_samples` (idle samples the screen went off in, left out), `panes_waiting` (`id`, `waiting_on`, `note`; empty since #67, kept because this version only adds fields), `panes_not_switched` (`id`, `why`, `note`: a pane not measured on its own, and why), `budget` (the frame's `ram` checks) and `pane_budget` (per pane: `value_kb`, `budget_kb`, `tolerance_kb`, `measurable` (three samples a side and a tolerance at most a quarter of the budget), `ok` (`null` when not measurable: read it as unknown, not as over)). See [RAM](#ram) |
| `grade` | `grade` (`null` when refused), `meaning`, `headline`, `levels` (per page: `premium`, `good`, `fluid` or `null`), `bands` (per page: `tap`, `load` and `blocking` bands, `scroll`, `updates`, `camera` and, since Clarity v3, `dialog` levels), `start`, `capped_by_start`, `viewport` (`state`, `reason`), `refused`, `provisional`, `provisional_reasons`, `missing`, `rubric_version`; when something is missing that can't change the letter, `missing_settled_by` (`capped_by_start`, and `limits`: the measured tasks that keep it from the next letter up, as `[{"page", "needs", "tasks"}]`) and, when a page's camera is the missing one, `unmeasured` (why, in the words `provisional_reasons` would use); and on a frame without touch (rubric 2) `not_applicable` (`{"scroll": "this frame has no touch"}`; its `bands` say `n/a` for the scroll). Computed from `graded` only. `provisional_reasons` are measurement facts: the kiosk's own page never finished loading in a graded run; graded runs failed or are missing; the restart or a task was measured in fewer runs than attempted; a page's live changes rest on a loose clock tie; no swipe ran during the live updates; scrolling rests on rAF only; the fake Home Assistant's log is missing, or it didn't know or failed a command, or sent a graded view's change late; page lines didn't parse or never arrived whole (`bad_page_lines`); swipes were answered with no scroll step back (`lost_scroll_steps`); "measured with a pre-release version of Clarity (protocol N): re-measure"; the pages ran another protocol; "<page>: N camera run(s) whose stalls couldn't be measured (no freeze counts from the receiver, or no frames to measure gaps over): n/a, not measured" (in `unmeasured` instead when the letter can't change); the viewport couldn't be checked |
| `graded` | `reference`: `start_ms`, `restart_to` (`usable` since protocol 4, else `engine-ready`), `engine_ready_ms` (detail), `start_errors`, `engine_uris`, `android_ttid_ms` (not graded), `launch`, `own_page_states`, `orders` (each run's page order), `warmups`, `protocols`, `bad_page_lines` (page lines that didn't parse or never arrived whole), `lost_scroll_steps` (swipes answered with no scroll step back), `lost` (graded views whose browser page the panel closed, as `[run, view, why]`; only when there was one), `errors`, `runs`, `graded_runs`, `per_run` (each with its `order` and `between`, whether the screen was still before each page opened), and `tiers` with, per page: `inp_ms`, `load_ms` (rubric 2: to the first screen drawn), `blocking_ms` (rubric 2: from the draw to usable, graded), `usable_ms` (detail), `load_errors`, `paint_to_screen_ms`, `scroll` (p95, jank, frozen, fps, n), `scroll_levels` (per run) and `scroll_level` (graded), `scroll_bands` and `scroll_band` (the 60 fps rule, detail), `scroll_runs` (each run's frame stats), `scroll_sources`, `scroll_limited_by`, `scroll_raf_only` (per run), `sf_swipes`, `swipes_moved`, `swipes_at_edge` (left out), `swipes_during_updates` (per run), `scroll_errors`, `scrolled_px`, `first_scroll_ms`, `sf_valid` (per run), `latency_p95_ms` and `latency_median_ms` (change to screen), `updates_levels` (per run) and `updates_level` (graded), `updates_runs` (each run's latency and change frames), `updates` and `updates_bands` (pacing of the frames that had a change to draw, detail), `updates_all_p95_ms` (every frame of the pacing stretch, detail), `updates_errors`, `camera_runs` (each run's worst stream and its `path`, with each stream as `detail`, and as detail `fetches` (the HLS fetch times) and `media` (the decoders the view got, from logcat)), `camera_levels` (per run) and `camera_level` (graded), `camera_fallback` (protocol 6, detail: `path` `hls`, `runs` (each run's worst HLS stream on the view's HLS copy, with its `view`, `media` and `error`), `levels` and `level` as the camera bars would give them; `null` on a page without streams), Clarity v3's more-info dialogs: `dialog_runs` (each run's `n`, `failed`, `settled_ms` (the slowest; `null` when one failed), `frames` (the open animations' frame stats, pooled), and as detail `open_ms`, `close_ms`, `longest_frame_ms`, `dropped`, `modes`, `reduced_motion`, `errors` and each dialog; `{"timed_out": true}` when the view didn't get to them, `{"lost": true}` when its page was lost), `dialog_levels` (per run) and `dialog_level` (graded), `dialog_settled_ms`, `dialog_open_ms` and `dialog_close_ms` (spreads), `dialog_errors`, `dialog_settles_away` (#410, detail: each run's `settle` in which the view was left for the start view); `state_ticks`, `positions` and `by_position` (the page's results at each place in the order), `fcp_ms`, `nodes`, `cards`, `viewport`; `lost_runs` and `lost_why` (in how many runs the panel closed the page's browser page, and why in words; only when it did) |
| `detail` | `synthetic`, the animation test page: `ladder` (per family: `held_60fps`, `held_30fps`, `…_at_top`), `steps_without_sf`, `screen_off_runs` (left out of the rest), `scroll`, `scroll_moved`, `js_burst_ms`, `js_band`, `js_checksum_ok`, `page_lcp_ms`, `page_lcp_from`, `per_run` |
| `real_use` | Not graded. Your dashboard: `app_start_to_dashboard_ms`, `android_ttid_ms`, `launch`, `stop_to_dashboard_ms`, `dashboard_load_ms`, `dashboard_page_stop_ms`, `settle_how`, `errors`, `screen_off_samples`, `per_run`. Then `idle`, `peak`, `memory` (`idle`, `peak`, `budget`) and `panes` |

`own_page_states` says, per run, how the kiosk's own page ended before the reference dashboards
opened: `reference` (protocol 4: it came back to the reference dashboard and said it was usable),
`finished-still` (stopped, then quiet), `finished-moving` (stopped, still painting, such as
a clock card; normal) or `never-finished` (still loading 30 s after engine ready; the grade is provisional).

## The summary (`glassworks.perf-summary/3`)

`tools/perf/summary.py` writes a small, stable, frame-level summary of a finished report, for places
that show a frame's grade (glassworks.dev, later a frame's `design/frame.json`). It holds
**measurements only, never the letter or its meaning**: the letter is derived, so it stays derived
(see [Showing the grade](#showing-the-grade)). Only measurements from the reference dashboards and
the APKs go in: the graded ones, and two optional parts that aren't graded (`ram`, `apk`). Nothing
from real use, and nothing device-unique.

| Key | What |
|---|---|
| `schema` | `"glassworks.perf-summary/3"` |
| `test` | The test's name, `"Clarity"` (`rubric.json` `name`), so a summary says what measured it |
| `clarity` | Clarity's version, `3` (`2` and `1` for v2 and v1, which v3's rules re-measure). Without it the summary is pre-release: `gradeSummary` returns `re-measure` ([Versions](#versions)) |
| `protocol_version` | How it was measured. A site grades it only with a rubric for the same protocol |
| `report_schema` | The report it came from, `"glassworks.perf/4"` |
| `measured_on` | The run's date, `YYYY-MM-DD` (UTC) |
| `frame` | `id`, `name` |
| `touch` | Rubric 2: `false` for a frame without a touchscreen, whose scroll isn't graded. Only there: a frame with touch has no `touch`, and its summary is as before |
| `app_version` | The kiosk's version name |
| `viewport` | `css` (CSS px), `dpr` (device pixel ratio), `default_dpr` (density / 160) |
| `runs` | `attempted`, `graded` |
| `pages` | Per page (`light`, `typical-nocam`, `typical`, `heavy`): `inp_ms` (median), `open_ms` (median; to the first screen drawn), `blocking_ms` (median, rubric 2), `usable_ms` (median, detail; rounded), the graded medians (`inp_ms`, `open_ms`, `blocking_ms`) unrounded since `/3`, `scroll` (`fps`, `p95_ms`, `scrolled_px`: medians; `sf_valid` per run; `runs`: each run's `p95_ms`, `jank_pct`, `frozen_pct`, `fps`, `n`), `updates` (`latency_p95_ms`, `latency_median_ms`: medians of change to screen; `pacing_p95_ms`, detail; `runs`: each run's `latency_n`, `latency_median_ms`, `latency_p95_ms` and `frames`, the stats of the frames that had a change to draw), `scroll.during_updates` (per run, how many swipes ran during the updates), `camera` (`runs`: each run's worst stream's `first_frame_ms`, `shown_pct`, `max_gap_ms`, `stall_total_ms`, and over WebRTC `uncovered_ms` (the most of the window its receiver counts left out), with `stalls_reported` false when the stalls are n/a; empty on a page without streams; `path`: what the graded streams played over, `web_rtc` since protocol 6, `null` without streams; `fallback`: the page's cameras over HLS, detail, `path` and `runs` as above, `null` before protocol 6 and without streams; a run's record is `{"timed_out": true}` with the numbers `null` when the page didn't finish before its streams were measured), `timed_out_runs` (protocol 7: in how many runs the page didn't finish within the runner's deadline; their unmeasured tasks are in the runs as failing: `inp_ms` at the deadline, `blocking_ms` at it when the main thread was never free, `open_ms` at it only when the view was never drawn (drawn but never free keeps its real open), `{"timed_out": true}` records for `scroll`, `updates` and `camera`), `lost_runs` and `lost_why` (only when the panel closed the page's browser page in a run: in how many, and why in words; every task failed in those runs, as `{"lost": true}` records and `inp_ms`, `open_ms` and `blocking_ms` at the 300 s view deadline), `runs` (how many runs measured `tap`, `scroll`, `updates`, `open`, `blocking`, `camera`, and `dialog` since Clarity v3), `dialog` (Clarity v3, added within `/3`, and only in a summary of a report that has it: `settled_ms`, the median of each run's slowest; `runs`: each run's `n`, `failed`, `settled_ms` and `frames` (`p95_ms`, `jank_pct`, `frozen_pct`, `fps`, `n`), or `{"timed_out": true}`) |
| `restart_ms` | Process start to a usable reference dashboard (median, unrounded since `/3`; to engine ready before protocol 4) |
| `gaps` | Measurement facts that leave it short: "1 of 3 graded runs failed or are missing", "heavy: scrolling rests on rAF only in 1 run(s)…". Empty when complete |
| `unmeasured` | Only when a page's camera is missing and it can't change the letter: why it is missing ("heavy: 3 camera run(s) whose receiver counts left up to 1.0 s of the window out (getStats answered late): stalls n/a, not measured"). Not a gap, so not provisional |
| `not_comparable` | Why the numbers can't be compared with other frames' (e.g. not at the default zoom), or `null` |
| `report` | A relative path or URL of the full report (`--report-url`) |
| `complete` | `false` when it has gaps, missing measurements or isn't comparable. A summary whose letter is provisional, or that has no letter, needs `--allow-provisional`; one missing only what can't change the letter is exported without it, with `complete` false |
| `ram` | Not graded; `null` if not measured. In kB: `idle_app_pss_kb` and `idle_by_process_pss_kb` (medians at idle on the typical reference dashboard), `peak_app_pss_kb` and `peak_by_process_pss_kb` (the largest of the samples taken between the graded pages), `panes` (each: `id`, `pss_kb`, `tolerance_kb` (`null` under two samples a side), `measurable`, `runs`, `budget_kb` and `budget_ok` (the frame's pane budget and whether the share is within it; `null` without a budget, or when the tolerance is too wide to tell), `budget_measurable` (`false` under three samples a side or with a tolerance over a quarter of the budget; `null` without one; read a null `budget_ok` as unknown, not as over), and `off_by` when the report has it. Only panes whose every sample was checked against the kiosk's log (`verified`) are listed: one with no number, or from a run before that check (such as s1v7), isn't), `panes_waiting` (pane ids; empty since #67), `panes_not_switched` (pane ids), `budget_ok` (`true`, `false` or `null`) |
| `apk` | Not graded; `null` if no APK was given. Per APK: `file`, `abis`, `bytes` |
| `notes` | The report's `notes` (`good`, `limits`, `causes`, `text`, `rubric`). Show them only when `rubric` is the site's rubric version: they judge tasks by that rubric |
| `webrtc_transport` | Protocol 7: the camera video's path, `transport` (`lan-udp`, `lan-turn` or `adb`), `chosen_by` (`auto` or `flag`), `reason`, and `possibly_limited` (over adb, heavy's camera result may be the test's link's). Letters over different transports aren't comparable; `gradeSummary` returns `transport` and `transport_caveat` with the letter. `null` before protocol 7 |
| `ha_transport` | `"lan"` when the fake Home Assistant reached the panel over the LAN, not `adb reverse` ([Home Assistant over the LAN](#home-assistant-over-the-lan)); absent otherwise |

The levels need each run's measurements (each run gets a level, then the median level is taken), which
is why the summary carries `runs` and not only medians. Medians are kept for display. Fields are only
added within a version; a change of meaning is a new one (`ram` and `apk` were added within `/2`,
2026-11-13, and `notes` with #201; `grade.ts` ignores them). `/2` (2026-11-12, rubric v5): `updates`
carries change to screen and its runs are latency records, where `/1` had frame pacing. `/3`
(2026-11-14, rubric 2, Clarity v2): `open_ms` is to the first screen drawn, where `/2` had it to
usable; `blocking_ms` and `usable_ms` are new; and the graded medians (`inp_ms`, `open_ms`,
`blocking_ms`, `restart_ms`) go out unrounded, as the report graded them, so rounding can't carry a
value across a band's edge (a 600.4 ms blocking median is poor; 600 isn't). A site reads `/3`.

### Protocol and rubric versions

Two numbers say whether a stored summary can still be graded. A change that doesn't change the work
the device does, such as a colour value, doesn't bump either (2026-11-12: the pages moved to the
Glassworks palette, below, within protocol 3):

| Version | Where | Bump it when | A stored summary |
|---|---|---|---|
| `protocol_version` | `tiers.json` `PROTOCOL`, and `rubric.json` `protocol_version` (the protocol its rules are for; `page.test.mjs` pins them equal) | **How** something is measured changes: the pages, the first screen, the swipes, the update rate, the windows, the warm-up and order, or how the runner and report read them | From another protocol: **re-measure**. Its numbers mean something else |
| `version` (rubric) | `rubric.json` | **How it is judged** changes: a threshold, a level, a letter rule, the restart cap | Graded again with the new rubric at render time. No re-measure |

So a change to the letter table changes every frame's letter on the site without a new run, while a
change to the pages makes the site ask for a new run. Protocol 1 (2026-11-12) added the warm-up, the
turning order, the shared first screen, change frames and the first-paint settle rule. Protocol 2
(2026-11-12) added room to flick on every page, a flick from the top for every swipe with edge swipes
left out, the HA-faithful update stream, change-to-screen timing over a 4 s window, and the changes
counted during each swipe. Protocol 3 (2026-11-12) fades only what HA fades on a tile (the icon's
shape and colour, not a background behind it) and counts only state changes, not the clock, during a
swipe. Protocol 4 (2026-11-13) runs HA's real frontend (release 20261028.5) behind a fake Home
Assistant, times the restart to a usable dashboard, points the kiosk's dashboard setting at the
reference dashboard for the restart, and drops the warm-up page. Within protocol 4, before any
frame had a protocol 4 result (2026-11-13, after smoke9): the reference dashboards became views of
one dashboard, opened by navigation in the loaded frontend; usable waits for the head and a 500 ms
free stretch; change to screen runs from when the fake sent a change; and the fake resets the home
between views. smoke9's numbers came before that and aren't protocol 4's. Every summary from
protocol 3, the S1's A included, is now "re-measure". Protocol 5 (#174, 2026-11-13) adds live camera
streams to typical and heavy (and the start view), and the camera task (rubric v6). Protocol 6
(#202, 2026-11-13) plays those cameras over WebRTC, as HA with go2rtc does, and grades that; encodes
the video without B-frames; and opens the camera views' HLS copies after each run's graded views, as
detail (rubric v7, the same rules). Protocol 7 (#202 option B, 2026-11-13) sends that video over the
LAN when the panel can reach the computer (UDP first, then TCP to the relay) and through adb reverse
otherwise, and records which (rubric v8, the same rules). Protocol 8 (#202, s1rtc4, 2026-11-13)
gives each task its own budget, so a slow view still gets every task tried, and grades a WebRTC
camera's gaps and stalls on the receiver's counts (rubric v9, the same rules): the camera window is a
fixed 30 s from the view going live; the harness's own waits are paced by animation frames, which a
busy main thread delays less than its timers; the taps wait 5 s each; and the runner's per-view
deadline covers only a view that never became usable ("A view the panel can't finish"). Rubric
v10 and v11 (2026-11-13) split typical into typical without and with its camera and made each letter
add one page. Since Clarity v1 the numbers start again (below): protocol 2 and rubric 2 are Clarity
v2's (the blocking time), protocol 3 and rubric 3 Clarity v3's (the more-info dialog), and since
2026-11-19 they start again once more: Clarity v1, protocol 1 and rubric 1 (below). Within the earlier
v3's protocol 3, which is v1's protocol 1 (#643, 2026-11-19): where SurfaceFlinger's history is shorter than a window, the runner reads it during
the window too. That isn't a new protocol: what is measured, the frames on screen in the window, is the
same. Where one read covered a window (the S1), nothing changes, record for record. Where it didn't,
the old result rested on rAF and said so (provisional), so no stored grade changes meaning.
Rubric 2 of Clarity v1 (2026-11-23, the maintainer: "a frame without touch just doesn't scroll") changes how a
result is judged, so it raises the rubric, not the protocol: on a frame without touch the scroll is `n/a`, accepted
at every level ([No touchscreen](#other-glassworks-apps-and-android-6)). Nothing else moved, and a frame with touch is
judged as under rubric 1, so the published results were graded again with `regrade.py` and kept their letters (the
S1 D, the Pixel Tablet B, provisional as before): only their `rubric_version` and notes' `rubric` read 2. The runner
sends a frame without touch no swipes; that isn't a new protocol either, for #643's reason: on a frame with touch it
does what it did, and on the Mirror those swipes never scrolled. (The earlier v2's "rubric 2", in the history below, is
another one: the numbers started again.)
When a missing measurement makes a grade provisional changed on 2026-11-23 too (the maintainer: only when it could change
the letter, and keep it Clarity v1; [The grade](#the-grade)). That moves the provisional flag and its words, never a
letter, so it raises neither the rubric nor the protocol. The published results were graded again with `regrade.py`:
the S1's D is byte for byte as it was, and the Pixel Tablet's B is no longer provisional. Its heavy camera stalls are
still missing, and its report says so, but heavy's blocking is already poor, so they can't change the letter.

### Versions

**Clarity v1, the version Glassworks 1.0.0 ships with (the maintainer, 2026-11-19): protocol 1, rubric 1; rubric 2
since 2026-11-23** (a frame without touch isn't graded on scrolling, [above](#protocol-and-rubric-versions)).
It measures what the earlier v3 measured (below), and moves one bar: live updates are good within
150 ms and fluid within 250 ms ([Why live updates aren't held to 100 ms](#the-grade)). The numbers
start again at 1, as they did on 2026-11-14, so everything below this entry is history:

- Each page line, report and summary says `clarity: 1` and protocol 1; reports are graded by rubric 2 (rubric 1
  until 2026-11-23, with the same rules for a frame with touch).
- **The earlier v3's runs are v1's.** Their pages (`clarity: 3`, protocol 3) measured exactly what
  protocol 1 measures, so report.py reads them as v1 (`SAME_AS_V1`). A stored v3 report or summary
  says "re-measure" until it is graded again, with `tools/perf/regrade.py <run-dir>`: from the raw files
  when the folder has them, else from its `report.json`. The S1's published result was graded again so
  (it stays D), and the Pixel Tablet's first one (run 4, 2026-11-19, measured with v3's pages) was
  published under v1 from its raw files: B, provisional (heavy's camera stalls unmeasured); a final B since
  2026-11-23, since those stalls can't change the letter.
- **The first v1 (2026-11-14) also said `clarity: 1`, protocol 1.** A v1 report or summary measured
  before `clarity_measured_since` (rubric.json, 2026-11-16 UTC) is that one's: report.py makes it
  provisional and `gradeSummary` returns "re-measure" ("measured with the first Clarity v1
  (2026-11-14), before the version started again at 1"). Every run records its UTC day (`measured_on`);
  the first v1 ran on 2026-11-14 only, the earlier v3 from 2026-11-17.
- The earlier v2's results (`clarity: 2`) and pre-release ones (no `clarity`) are re-measure, as before.

**Clarity v3 (#211): protocol 3, rubric 3** (history: now v1's protocol 1). Opening HA's more-info dialog is a graded task (the
maintainer's decision, 2026-11-13: add it). Protocol 3 opens a light's, a thermostat's and a sensor's
dialog on each view after its swipes and times each to settled, with the frames of its open animation
([The more-info dialog](#the-more-info-dialog-211)); rubric 3 grades the slowest of them
(good within 1 s, fluid within 2 s, premium with the open animation at 60 fps) in every level, as
every other task. Nothing else moved: no threshold, no level or letter rule of the other tasks, and the
views, the swipes, the updates and the taps run as in protocol 2, the dialogs coming after them. The
fake Home Assistant's home gains the thermostat (on no card) and HA's answer to
`config/entity_registry/get`. A v2 or v1 result didn't measure the dialog, so it can't be graded by
rubric 3: `gradeSummary` returns `re-measure` ("measured with Clarity v2; these rules are Clarity v3's"),
and report.py makes it provisional. The bars were set from their sources before any frame had a
dialog result; a v3 run of the S1 is still to come.

**Clarity v2 (2026-11-14): protocol 2, rubric 2** (history). Rubric 1 graded "open the view" (the navigation
to *usable*: the first screen drawn **and** the main thread free) against LCP's bands. LCP is a
paint metric, so applying its 2.5 s and 4 s to drawn-and-free was stricter than the standard it
cites: on s1rtc9's typical-nocam the first screen was drawn after a median of 953 ms, but long
tasks after the draw held usable to 4852 ms. Rubric 2 grades the paint against LCP's bands and the
main thread's blocking after it against TBT's (above, [Usable](#usable)). The maintainer decided it
on 2026-11-14 to put each standard on the quantity it measures, before rubric 2's letter for any
frame was known; no threshold moved, and the ladder keeps its shape. Protocol 2 records the frames
from the draw to usable (`usable.blocking_ms`), which protocol 1 didn't, so a v1 result (clarity 1)
can't be graded by rubric 2: `gradeSummary` returns `re-measure` ("measured with Clarity v1; these
rules are Clarity v2's"), and report.py makes it provisional.

**The first Clarity v1 (2026-11-14): protocol 1, rubric 1** (history; the numbers started again on 2026-11-19). Nobody outside the project
had used Clarity, so the maintainer reset the numbers. Every protocol and rubric number in this page's
history (protocols 1 to 8, rubrics 1 to 11, before 2026-11-14) is **pre-release**, and its results
aren't comparable with v1's.

- Each page line, report and summary of v1 says `clarity: 1` (the harness's `refdash` line,
  report.json and the summary). One without it was measured with a pre-release version: report.py
  makes it provisional ("measured with a pre-release version of Clarity (protocol N): re-measure"),
  the notes say so, and `gradeSummary` returns `re-measure`, never a v1 letter. The marker decides,
  not the number, because v1's protocol 1 repeats a pre-release number.
- Pre-release protocol 8 was v1's release candidate: the same measurement, run once on the S1
  (s1rtc5, #205, 2026-11-13) before its lines carried the marker. report.py reads its lines as v1
  (the one bypass of the marker, and only for lines that carry the round's `usable.busy_ms`, which
  no earlier protocol 8 line has),
  so its report.json is rebuilt as v1 without re-measuring: `tools/perf/report.py <run-dir>`, then
  `tools/perf/summary.py <run-dir>/report.json <out>`.
- A later version raises `clarity` with a change that makes earlier results incomparable, and
  `protocol_version` and the rubric's `version` with each change to the measurement or the rules.

### Published results

A frame's published result lives in its folder, `frames/<id>/clarity/`, generated from the run's
files and never edited by hand:

| File | What |
|---|---|
| `report.md` | The report, as report.md is for any run (the site links to it on GitHub) |
| `report.json` | The full report (`glassworks.perf/4`) |
| `clarity.json` | The summary (`glassworks.perf-summary/3`), which the site grades with `grade.ts`; its `report` is report.md's GitHub URL |

**Grading a run again** under the rules now in `rubric.json` (a new rubric, or the version starting
again): `tools/perf/regrade.py <run-dir>` rewrites its report.json, report.md and summary (clarity.json in a
published folder, else summary.json). From the raw files when the folder has them, else from the
report.json alone; touches no device. `--dry-run` only says what the grade would be.

```sh
python3 tools/perf/report.py <run-dir> --keep-apk --out frames/<id>/clarity --app-version <label>
python3 tools/perf/summary.py frames/<id>/clarity/report.json frames/<id>/clarity/clarity.json \
    --report-url https://github.com/GlassworksHQ/glassworks/blob/main/frames/<id>/clarity/report.md
```

`--out` leaves the run directory as it was. `--app-version` shows a label in place of the version
the device reported (report.md, report.json, the summary's `app_version` and the APK sizes' names),
for an app before its first release; nothing in the grade reads it. Before committing, check the
files for anything device-unique (serial, MACs, addresses other than RFC 5737, HA URLs, tokens):
the report and summary are built to carry none, but a run directory's other files do. The S1's is
[frames/aqara-s1-plus/clarity/](../frames/aqara-s1-plus/clarity/report.md).

`test_summary.py` checks every `frames/*/clarity/clarity.json` against the command above: it runs
`summary.py` on the `report.json` beside it, with the committed `report` URL, and fails if the output
differs by a byte. A hand edit, or a `report.json` changed without regenerating the summary, fails
CI (#115).

**A published result of an earlier Clarity version stays as it was published** until the frame's
next run replaces it, and the maintainer decides when that is. The site grades it with the current
rules, so it shows "Re-measure", not a letter. The S1's v2 result (s1rtc10) did, until its Clarity v3 result
(s1v3c, 2026-11-17 UTC, D) replaced it.
`summary.py` now refuses that report as complete, so `test_summary.py` exports it marked and checks
every byte but `complete`, which stays as it was decided under its own version's rules.

### Showing the grade

The site computes the letter **at render time** from the frame's summary and `rubric.json`, with
`tools/perf/grade.ts` (plain TypeScript; the shared frame package can import it later, #114):

```ts
import { gradeSummary } from "./grade.ts";
const g = gradeSummary(summary, rubric);   // rubric = tools/perf/page/rubric.json
// g.status: "graded" | "re-measure" | "not-comparable"
// g.grade (A+ to E), g.meaning, g.levels (per page), g.limits (what keeps it from the next
// letter), g.provisional
```

| `status` | Show |
|---|---|
| `graded` | The letter (A+ to E) and its meaning. When `provisional` is set (something wasn't measured that could change the letter, or `gaps` isn't empty), mark it provisional. When `missing_settled_by` is set, say what wasn't measured and that it can't change the letter |
| `not-comparable` | No letter, and `why` (from `not_comparable`): not at the default zoom, or nothing measured. Checked first |
| `re-measure` | "Re-measure", not a letter: the summary's protocol isn't the rubric's (a missing protocol reads "unknown") |

`report.md` still gives a letter for pages from another protocol, for the record, and its headline
ends "Re-measure to publish: the site shows no letter for these pages."

`grade.py`'s `grade_summary` gives the same answer. `test_summary.py` builds fixture reports
(letters A, B, C by the restart cap, E, a refused zoom, rAF-only scrolling, an even run count, a
missing run), exports each summary, and checks that Python's grade from the summary equals the
report's grade, and that `grade.ts` (run under node) equals Python's.

### How #68 uses it

[#68](https://github.com/GlassworksHQ/glassworks/issues/68) is the report of APK size and RAM
per frame and pane, with budgets. Its two halves come from different places:

- **RAM comes from this test, on the device** (see [RAM](#ram) for why not an emulator). The
  report's `ram` holds the reference dashboard's numbers per process and per pane, and checks them
  against the frame's budgets in `frames/<id>/budgets.json`. The summary carries them (`ram`),
  with each pane's budget and whether it is within it (`budget_kb`, `budget_ok`).
- **APK size comes from CI** on every pull request, which needs no device: the size per ABI split
  into the engine, the core, each pane, assets and native libraries, the change from the base branch,
  and the frame's size budget in the same `frames/<id>/budgets.json`. See [APK size](size.md).
  `--apk` still adds sizes to a device report.
- **Panes** use the ids from the code (`assist`, `sendspin`, `radio`, `wall-base`, `timers`,
  `time-zone`, `diagnostics`). Since [#67](https://github.com/GlassworksHQ/glassworks/issues/67),
  `assist`, `sendspin`, `wall-base`, `time-zone` and `diagnostics` can be switched; `radio` and
  `timers` are `not_switched`, and [RAM](#ram) says why.

## Page log lines

The pages write one console line per event, `GWPERF {json}`. It reaches logcat from the content
process (tag `Isolated Web Content` on the S1), and **only its first 1023 bytes arrive** (read from
the S1's logcat, 2026-11-12: smoke7's four 4 s updates steps, about 1.5 KB each, were cut there and
didn't parse; logcat itself allows 4068 bytes). So a page line longer than 900 bytes of UTF-8 is split, never inside a character, into
numbered parts, `GWPERF+ <nonce>:<n> <k>/<parts> <chunk>` (`stats.js`, `consoleLines`), and the
runner joins them (`perflib.PageLines`). A line that still doesn't parse is counted
(`bad_page_lines`) and makes the grade provisional, and so is a line whose parts haven't all arrived
when its page says done. A swipe the runner answered with no scroll step back is counted too
(`lost_scroll_steps`), and makes the grade provisional. A page that says done without its updates step
doesn't fail the run: its other tasks stand, and its live updates read "not measured". Every line has `run`, `nonce` (the runner's id for that page
load) and `t`:

| `t` | Fields |
|---|---|
| `drawn` | Protocol 2: a reference view's first screen drawn, logged at once: `tier`, `protocol`, `clarity`, `nav_ms`, `usable` (`rendered_tick_ms`, `first_screen_cards`, `head_cards`, as in `refdash`), `origin_epoch_ms`, `epoch_ms`. The runner keeps it as the view's `drawn`; `refdash` follows once the main thread is free |
| `refdash` | A reference view, usable: `tier` (the view: `start` for the restart page, or `light`, `typical`, `heavy`), `protocol`, `clarity` (`3`; v2: `2`, v1: `1`), `frontend` (HA's frontend release), `nodes`, `cards` (the view's cards), `cards_rendered` (how many HA had built when it was usable), `css` (viewport), `dpr`, `scroll_height`, `fcp_ms`, `lcp_ms`, `load_ms` (the frontend's own load: the restart page only, else `null`), `nav_ms` (the navigation to the view; 0 for the frontend's own load), `video_caps` (`mse_h264`, `native_hls`, `rvfc`, `webrtc`: which camera player paths the engine offers), `usable` (`rendered_tick_ms`, the frame in which it was drawn; `first_screen_cards`; `head_cards`, the 10 head cards it waited for; `interactive_ms`, when the main thread's free stretch began; `busy_ms`, protocol 8, the longest a probe waited each second until then; protocol 2: `blocking_ms`, the frames' time past 50 ms from the draw to usable, `long_frames`, how many frames were over 50 ms, and `longest_frame_ms`) (all after `performance.timeOrigin`), `origin_epoch_ms` and `epoch_ms`, which tie the page's clock to logcat's |
| `warmup` | `stage`, `n`. The page goes live; the runner stops polling SurfaceFlinger |
| `measure` | `stage`, `n`. A window starts. For `updates`, the live check: `moving`, `offscreen`, `targets`, `update_every_ms` |
| `step` | `stage` (`updates`, `scroll`, or a ladder family), `n`, `window_ms` (the window's length on the page's clock), `raf` (frame stats). For `updates`: `t0` (the window's start on the page's clock), `pacing_ms` (the stretch `raf_times` covers), `from` (`sent`: the changes start when the fake sent them), `stream_at_ms` (when the harness asked for the stream), `raf_times` and `changes` (`[sent, arrived, transition ms, kind, tick]`: every change the fake sent up to the window's end, from its schedule; `arrived` and `tick` are `null` for one that hadn't reached the page when the window was logged; kind 0 a sensor value, 2 an on/off change (1, the clock, was protocol 3's); tick, the animation frame it was drawn in), all ms after `t0`. For `scroll`: `scrolled_px`, `hit_edge`, `changes_during`, `first_scroll_ms`, `raf_intervals`. The runner reads SurfaceFlinger |
| `input-ready` | `x`, `y` (device pixels), `taps`. The runner taps |
| `swipe-ready` | `n`, `x`, `y1`, `y2` (device pixels), `ms`. The runner swipes once |
| `input` | `stage`, `method`, `taps`, `values`, `inp_ms` |
| `camera` | Views with streams (#174), logged before the swipes: `stage`, `path` (`web_rtc` or `hls`, protocol 6), `window_ms`, `t0_ms` and `nav_ms` (the window's start and the navigation, page clock; protocol 8), `fps`, `expected`, `found`, and per stream `w`, `h`, `first_frame_ms` (after the navigation), `shown_pct`, `max_gap_ms`, `stall_total_ms`, `frames` (presented, from the decoder), `callbacks` (`requestVideoFrameCallback`), `decoded`, `dropped`, `waiting`, `source`, `stall_presented` (frames presented inside the stalls, from rVFC's `presentedFrames`; `null` without rVFC), `gaps_source` (`webrtc`, `webrtc (bound)`, `webrtc (no freeze counts: n/a)`, `rvfc` or `timeupdate`: where `max_gap_ms` and `stall_total_ms` come from), `stalls_at` (where the stalls were, page clock), `callback_gaps` (WebRTC, protocol 8: the frame callbacks' `max_gap_ms`, `stall_total_ms`, `presented`, `callbacks`, `stalls_at`; detail), `span_ms` (WebRTC: the receiver samples' span, which shown is over), `path`, `others` (the camera's other players: `path`, `first_frame_ms`, `connected`), `webrtc` (WebRTC only: `stats.js` `webrtcStats`); then `fetches` (`segment` and `playlist`: `n`, `queued_ms`, `wait_ms`, `total_ms`, each `median`, `p95`, `max`; `segment.kb`, `segment.arrival_gap_max_ms`; `streams`) |
| `dialog` | Clarity v3 (#211), one per more-info dialog, after the swipes (and after a restart with `stage` `warmup`, not graded): `stage` (the view), `n`, `of`, `entity`, `kind` (`light`, `climate`, `sensor`), `graph` (whether it has a history graph), `mode` (`bottom-sheet` or `dialog`, as HA showed it), `show_duration_ms` (its show animation, as the page computed it), `reduced_motion`, `opened`, `open_ms`, `shown_ms`, `graph_ms`, `settled_ms` (all after the tile's event; `null` when it didn't happen; `settled_ms`, graded, unrounded), `anim` (the open animation's frame stats), `anim_intervals`, `dropped` (refreshes missed), `longest_frame_ms`, `close_ms`, `close_longest_ms`, `error` and `close_error` (`null`, or why it didn't open, settle or close in time) |
| `hello`, `js` | Animation test page: `ua`, `dpr`, `css`, `cores`, `load`; the JS workload's `runs`, `median_ms`, `p95_ms`, `checksum`, `expected` |
| `settle` | #410: a view whose dialogs HA still held after `DIALOG.settleMs` on it, logged before the move to the start view: `stage`, `ms`, `clean` (`false`), `closed` (dialogs found open and closed), `pending` (the entities HA hadn't shown), `open`, `history` (a dialog's history entry still current). After a restart's warm-up (`stage` `warmup`, no nonce) it is logged again if HA still held one after the move too. The runner keeps those in `refstart-<k>.json` under `warmup_settle`: a `settle` line with no nonce is always the restart's, even when it comes after the runner has opened the first view, as a held warm-up dialog's does (`perfrun.py` `handle`; `test_runner` `FAKE_SETTLE_VIA=warmup`) |
| `done`, `error` | `message` on error. At `done` the page has stopped its updates. A view's `done` has `settle` (#410, detail, untimed): how HA got done with its dialogs (`ms`, `clean`, `closed`, `pending`, `open`, `history`; with `via: "start"`, `first` (the view's own wait) and `at` (the view HA was on at the end), when it was left for the start view); `null` on a view asked for none |

Vitro's lines (`VitroClarity`, see [Vitro](#vitro)) have the same types and fields, plus `engine: "vitro"`,
`clock: "monotonic"` and `origin_mono_ms` on `drawn` and `refdash` in place of `origin_epoch_ms` and `epoch_ms`.
Their `frontend` and `video_caps` are `null`, and their `input` line adds `incomplete` (taps
counted that got no frame in time; then `inp_ms` is `null`). Their `camera` line's streams add `entity`,
`transport` (the kind the camera showed at the window's end: `web_rtc`, `hls` or `mjpeg`), `errors`,
`first_frame_since_start_ms` (the stream's own time from its start), `opened_ms` and `started_ms` (when that
stream was made and started, from the view's navigation), `sessions` (its streams on the path) and, on
WebRTC, `freezes`; `gaps_source` is `vitro-frames (freeze rule)` or `vitro-frames (gaps over 500 ms)`, and
`others` lists `mjpeg` too. A build whose cameras don't report logs no `camera` line. They go to logcat with the tag
`Glassworks.Clarity`.

The restart page logs with an empty nonce (its parts are numbered `restart:<n>`): only `refdash` and
`done`, kept in `refstart-<k>.json` under `restart_page`. A warm-up view (`--warmup-tier`, none by
default since protocol 4) is a view opened with `taps=0&swipes=0&warmup=1`; its lines are kept
under `warmup`, not graded. Its lines that don't parse still count in `bad_page_lines` (one count
for the run), but its swipes don't count in `lost_scroll_steps`, which adds up the graded views only.

Each view in `refstart-<k>.json` `tiers` also has `swipes_asked` (the swipes the runner sent),
`lost_scroll_steps` (those with no scroll step back: `swipes_asked` less the steps that came), and
`lost_lines` (page lines whose parts hadn't all arrived when the view said done).

The runner and the harness talk through the fake Home Assistant, never through adb:

| Request | From | What |
|---|---|---|
| `/clarity/open?view=&nonce=&run=&vsync=&taps=[&swipes=&dialogs=&warmup=]` | the runner | Open this view next. Checked: a known view, a hex nonce, numbers only; else 400. `dialogs`: how many of the more-info dialogs to open, all when absent; the runner sends 0 for the HLS copies and a warm-up view |
| `/clarity/next?after=<nonce>` | the harness | Waits up to 20 s for a command other than `after` (the last one this page ran); 200 with it, or 204. The command stays until its view's stream stops, so a long poll left behind by a page that died (a force-stopped frontend's) can't use it up |
| `/clarity/live`, `/clarity/still` | the harness | Start the stream (200 `{"gen": n}`, its generation), or stop it, end the view's command and reset the home |
| `/clarity/cancel` | the runner | Before each restart: no command, no stream and no changed state is left from a view that was cut short |

Frame statistics are defined once, in `tools/perf/page/stats.js` and `tools/perf/perflib.py`.
`tools/perf/test/stats-cases.json` pins both to the same answers.

## Example: Aqara S1 Plus

**Clarity v1: D** (s1v3c, measured 2026-11-17 UTC with the earlier v3's protocol 3, 3 of 3 runs, not
provisional; graded again under v1 on 2026-11-19 with `tools/perf/regrade.py frames/aqara-s1-plus/clarity`):
"a good wall panel for light dashboards. Held back by typical dashboards without a camera: the main
thread is blocked for 2.7 s after the view is drawn." Under v1's live-update bar typical with and
without its camera went from fluid to good on live updates; heavy stays slow (1.5 s to show a change).
The letter didn't move: blocking holds it. The published report is
[frames/aqara-s1-plus/clarity/report.md](../frames/aqara-s1-plus/clarity/report.md), and its summary
[clarity.json](../frames/aqara-s1-plus/clarity/clarity.json).

History, under the earlier versions:

**Clarity v2: D** (s1rtc10, 2de8ac20, 3 of 3 runs, protocol 2, rubric 2, the S1 app build before
its first release (`pre-v1`), camera video over the LAN (`lan-udp`), measured 2026-11-15 UTC; not
provisional): "a good wall panel for light dashboards. Held back by typical dashboards: the main
thread is blocked for 2.9 s after the view is drawn." Typical without its camera (`typical-nocam`) opens in 1.2 s
(drawn, good) and is usable at 5.1 s; the 2.9 s of blocking between them (poor, over 600 ms) is what stops it at fluid.
Light blocks for 233 ms (needs improvement, which fluid accepts). A restart takes 14.3 s, which
would cap it at C. The published report is
[frames/aqara-s1-plus/clarity/report.md](../frames/aqara-s1-plus/clarity/report.md), and its
summary [clarity.json](../frames/aqara-s1-plus/clarity/clarity.json). The S1's Clarity v1 run
(s1rtc9, also a D) was held when rubric 2 was decided, and never published. The results below
are earlier versions' (see [Versions](#versions)) and not comparable with it.

**Protocol 5: D** (s1cam3, cbc2be6, 3 of 3 runs, rubric v6, the S1 app 0.4.3, `thread_router`,
2026-11-13; not provisional): protocol 4's views with live camera streams. Typical's 720p stream
plays in full (first frame 4.2 s), and heavy's four starve (35% shown) on a saturated main thread.
The details are in [Live camera streams](#live-camera-streams-174-protocol-5).

**Protocol 4: D** (s1v7, 131f6da, 3 of 3 runs, rubric v5, the S1 app 0.4.3, the radio in
`thread_router`, 2026-11-13; not provisional). "A good wall panel for light dashboards. Held back by
typical dashboards: scrolling averages 35.1 fps (p95 83 ms); the view takes 4.8 s to open." The
verifier recomputed the D by hand, with `grade.py` and with `grade.ts`.

| View | Level | Tap | Scroll | Change to screen (p95) | Open the view |
|---|---|---|---|---|---|
| light (20 cards) | fluid | 96 ms | 40.9 fps, p95 66.7 ms | 84 ms | 1.45 s |
| typical (50 cards) | neither | 96 ms | 35.1 fps, p95 83.3 ms | 96 ms | 4.82 s |
| heavy (120 cards) | neither | 88 ms | 23.3 fps, p95 133 ms | 156 ms | 14.5 s |

Restart, process start to the start view usable: **11.7 s** (11.6 to 13.0 s), poor; engine ready
2.9 s (detail). Medians of 3 runs. Scrolling holds back every view and open the view holds back
typical and heavy. Taps and the live changes are good everywhere except heavy's changes (fluid).
The panel ran with the wake word using about 37% of a core
([#105](https://github.com/GlassworksHQ/glassworks/issues/105)); the move to microWakeWord
(#181) needs a re-measure. s1v7's pane numbers are not valid (see [RAM](#ram)); s1p1 and s1p3 below
are.

**RAM per pane** (s1p1 and s1p3, 248ce09, 2026-11-13, the S1 app 0.4.3, the radio in
`thread_router`; verified: every sample's kiosk log showed what its label says, none left out, and
the panel was put back each time). In both runs the kiosk kept `voice_enabled` (the 0.4.3 bug
above), so Assist is off by its microphone mute: its running cost, not the model it loads.

| | Assist CPU | Assist PSS | Sendspin CPU | Sendspin PSS |
|---|---|---|---|---|
| s1p3, 3 runs (9 samples) | **+36.3% of a core** (±1.2) | +5.2 MB, within ±31.6 | +1.9%, within ±2.9 | +0.3 MB, within ±31.6 |
| s1p1, 1 run (smoke) | +36.5% | +21.3 MB | +3.5% | +50.1 MB (`:boot`, see [RAM](#ram)) |

Assist's CPU matches [#105](https://github.com/GlassworksHQ/glassworks/issues/105)'s 37%
(the wake word's model). Neither pane's PSS is measurable at this tolerance, so these runs can't say
whether either fits its 48 MB budget. Read with the noise band and `:boot` apart, the same samples
give ±37.4 MB for both, and ±10.2 MB (Assist) and ±10.4 MB (Sendspin) without the cold first sample ([RAM](#ram)). The app at idle on the typical
reference dashboard, all panes off: 565 to 596 MB.

**smoke9** (8c0027b, one run, 2026-11-13, protocol 4 as first written: each reference dashboard a
full page load) read **E, provisional**: open the view 4.9, 8.2 and 5.0 s (light, typical, heavy);
restart 10.4 s; scroll 42.9, 36.9 and 19.1 fps (heavy's p95 167 ms); change to screen 112 and 115 ms
on light and typical, heavy not measured ("only 17 of 20 state updates ran in the window"); taps 168,
88 and 232 ms. Its RAM part ran (Assist +2.8 MB, Sendspin +15.2 MB, n=1) and the restore worked. It
is not a protocol 4 result: open the view then counted HA's cold load each time, heavy's updates
were dropped instead of counted (its changes reached the page in bursts, with frames stalled for up
to 649 ms), and its fake HA log was never written (`run.sh` didn't pass `--log`), so its "no
unknown commands" couldn't have seen one. All three are fixed above.

**Protocol 3, superseded: A** (s1v6, 3 of 3 runs, rubric v5, the S1 app 0.4.3, measured 2026-11-13
UTC, the page order turning each run, nothing missing, no gaps). Protocol 3's pages were an imitation
of HA's cards and its restart ended at engine ready, so this letter doesn't carry over (see
[Not tuned to any frame](#not-tuned-to-any-frame)):

| Page | Scroll, median of runs (each run) | Change to screen, p95 | Tap (INP) | Opens in |
|---|---|---|---|---|
| light | 56.0 fps (54.2 to 56.3), good in all 3 | 76.5 ms (67.7 to 77.6), good | 88 ms | 1181 ms |
| typical | 53.8 fps (53.1 to 53.8), good in all 3 | 64.0 ms (61.6 to 68.8), good | 64 ms | 1382 ms |
| heavy | 54.4 fps (54.3 to 54.7), good in all 3 | 70.4 ms (69.2 to 87.9), good | 120 ms | 1985 ms |

Restart (process start to engine ready) took 2929 ms: needs improvement, under the 5 s cap. Every
swipe moved freely (none reached an edge) and all 18 ran while the live updates did. Android's
framework didn't crash during the run: its dropbox count was the same before and after (checked by
the root on the panel). The run's `summary.json` (`summary.py`) grades A in both `grade.py` and
`grade.ts`. It is an A, not
an A+: on heavy, scrolling averages 54 fps with p95 33 ms, under the 60 fps rule, and the frames of the
live changes don't hold 60 fps either. s1v6 ran with the pages' old colours; the palette change after
it changes no work (see [Protocol and rubric versions](#protocol-and-rubric-versions)).

The earlier runs, under earlier definitions, are below.

What the earlier runs on the S1 (2026-11-12, kiosk 0.4.3, GeckoView 159, Android 10) established:

- `dumpsys SurfaceFlinger --latency 'SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0'`
  returns the 16.67 ms refresh period and the last 127 presented frames, about 2.1 s at 60 fps. That
  is why a window is at most 100 refreshes and the runner reads SurfaceFlinger just after it.
- The kiosk runs as six processes: the app, Gecko's GPU process, three content processes and a
  crash helper. The app process is under a third of the total PSS, so the report adds them all up.
- The kiosk is the S1's home app. After `am force-stop` the system starts it again within 0.2 s.
  GeckoView's `about:blank` follows about 3 s later, and the dashboard's `PageStart` about 1.5 s
  after that. An `open` sent at `Displayed` was dropped: the session wasn't up yet.
- The S1's CSS viewport is 960 × 480 at a device pixel ratio of 1.5.
- Gecko doesn't run CSS animations that are off screen. An animated page whose animated cards sat
  below the fold showed only its clock ticking, and read as frozen frames (p95 1017 ms). The live
  check exists because of that.
- At 128 spinning CSS boxes on the animation test page, rAF counted 118 frames in 2 s while
  SurfaceFlinger presented 64, which is why both views are kept. One quick ladder run can differ
  from a full run's at a band's edge (47 against 78 frames at 16 boxes), so read ladder counts from a
  full run's median.
- Its idle samples found the app's main process using about 40% of one core with nothing changing on
  screen ([#105](https://github.com/GlassworksHQ/glassworks/issues/105)).
- **smoke5** (80df5ab, one run, before protocol 1) showed the swipes work (`scrolled_px` 2646, 3248
  and 3324; SurfaceFlinger valid on every page; restart 2487 ms) and an order bias that decided the
  letter (E). Light, always first and right after the kiosk's dashboard, scrolled at 42.7 fps (poor)
  against heavy's 53.1 fps. Its updates read p95 50 ms against heavy's 16.7 ms, but light's first
  screen updated fading tiles and heavy's updated rows that don't fade; rAF wasn't idling (light's
  median interval was 16.64 ms). Light's first paint (907 ms on its own clock) reached the screen at
  1255 ms, 348 ms later (typical 146 ms, heavy 92 ms), so its load wasn't measured. Rebuilt with the
  first-paint settle rule, light opens in 1754 ms, and heavy in 2137 ms instead of 1357 ms: the old
  rule had let heavy settle at its load event, before its first paint was on screen. Protocol 1's
  warm-up, turning order and shared first screen answer the rest.
- **s1v5** (d00fb81, protocol 1, 3 runs, the S1 app 0.4.3, orders rotated). Under rubric v4 it graded
  E. Re-graded offline under v5 (the raw data has what change to screen needs: when each change
  applied, the rAF frames and SurfaceFlinger's presents), it gives **C, provisional** (protocol 1
  pages):

  | Page | Scroll (per run) | Live updates, change to screen p95 (per run) | Tap | Opens in |
  |---|---|---|---|---|
  | light | 42.6 fps, fluid, in all three runs | 72 to 88 ms, good | 96 ms | 1182 ms |
  | typical | 49.6, 50.0 and 50.7 fps: fluid, fluid, good, so fluid | 66 to 87 ms, good | 144 ms | 1377 ms |
  | heavy | 47.7 fps, fluid, in all three runs | 66 to 78 ms, good | 120 ms | 2004 ms |

  Typical misses the good scroll bar by under 0.5 fps in two of three runs, and that decides the C.
  Light's scroll is the protocol 1 artefact above: every light swipe hit the end of the page, at
  41 to 46 fps in every position. These were 9 changes per run, so the p95 is the slowest of them.
- **smoke7** (d60ec95, protocol 2, one run, 2026-11-12) failed with "did not finish within 600 s"
  although all three pages finished. Every updates step was cut at 1023 bytes in logcat and didn't
  parse, and the runner then counted the run as failed. Fixed: long lines are split, and a lost step
  no longer fails a run. Its other measurements, re-read: light's page is 1936 px tall; every swipe
  moved 559 to 563 px without reaching an edge, with 8 or 9 live changes during each. Scroll is good
  on every page (light 56.0 fps, typical 53.8, heavy 53.5, p95 33 ms), so protocol 2 removed light's
  scroll artefact. Live updates weren't measured in that run.

## Run the tests

```sh
python3 tools/perf/test/parallel.py      # every Python test here, several at once (or: python3 -m unittest discover -s tools/perf/test)
node --test tools/perf/test/page.test.mjs tools/perf/test/grade.test.mjs tools/perf/test/browserguard.test.mjs
# The WebRTC camera (#202), under the WebRTC venv; then HA's frontend in Chrome, and Firefox (Gecko).
PY=$(python3 tools/perf/webrtcenv.py)
"$PY" -m unittest discover -s tools/perf/test -p test_webrtc.py
GW_PERF_FAKE_PYTHON="$PY" node --test tools/perf/test/webrtc.e2e.test.mjs
GW_PERF_FAKE_PYTHON="$PY" node --test tools/perf/test/dialog.e2e.test.mjs     # the more-info dialogs (Clarity v3)
GW_PERF_FAKE_PYTHON="$PY" node --test tools/perf/test/webrtc.gecko.test.mjs
```

`test_runner.py` runs `run.sh` and `perfrun.py` end to end against a fake panel (`test/fake_adb.py`)
and the fake Home Assistant. A panel's waits are real seconds (a wake key every 8 s in a restart, a 180 s stage
deadline), so these runs go on a compressed clock: every wait and timestamp of the runner,
`lanprobe.py` and the fake panel goes through `clock.py`, and the tests set `GW_PERF_TIME_SCALE=0.2`.
A wait then takes a fifth of its seconds, and the clock runs five times as fast, so recorded times
keep their units and the tests their numbers. `GW_PERF_TIME_SCALE=1` runs them in real time. Two
things stay on real seconds: `run.sh`'s own sleeps and polls (the tests set its settle and gap times
to 0, and it never waits to cool on the fake), and the gesture times in a run's file
(`gestures_sent`), which share `fakeha.jsonl`'s clock. A real run never sets the scale, and then the
clock asks the device nothing. With it set, `run.sh`, `perfrun.py` and `lanprobe.py` refuse it on
any device that isn't the fake, before they touch anything else (`test_runner.RealTime`).

The tests come in two sets, which `tools/perf/test/parallel.py` runs:

| Set | What | When | Took (2026-11-14, a 10-core Mac) |
|---|---|---|---|
| `--set fast` | `test_grade`, `test_report`, `test_summary`, `test_notes`: grading, report, export, notes | After a grading, report or notes change | 2 s |
| `--set slow` | Everything else: `test_runner` and the fakes | Too when you change `perfrun.py`, `run.sh`, `lanprobe.py`, `clock.py` or a fake | 88 s at 8 at a time, 170 s at 4 (about 10 minutes one at a time) |

CI runs both on every PR, the fast set first, in the `scripts` job. `parallel.py` runs each
`test_runner` class whose tests share one run as one process, each other `test_runner` test as one,
and each other module as one, `-j` at a time (twice the cores, at most 8; `GW_PERF_TEST_JOBS`). Every
process gets its own temp directory and its own block of ports (`GW_PERF_TEST_PORT_BASE`), so none
share a port, a path or a fake panel, and a result doesn't depend on what runs beside it. A failed
process prints its whole output. One test or class alone: `cd tools/perf/test && python3 -m unittest
test_runner.Restart`.

`dialog.e2e.test.mjs` runs HA's real frontend in headless Chrome at the S1's viewport and has the
harness open the more-info dialogs, as [The more-info dialog](#the-more-info-dialog-211)
says: each must open, settle and close, as a bottom sheet there, with and without reduced motion, and
as a centred dialog at a desktop's viewport; and the fake must know every command they send.

`test_webrtc.py` checks the relay (its credentials, one allocation over TCP) and a whole session
through the fake's websocket API, with aiortc as a relay-only browser. `webrtc.e2e.test.mjs` runs
HA's real frontend (the pinned build, fetched once) in headless Chrome: Chrome's own build has
H.264, Playwright's Chromium doesn't (`CHROME=` picks another). `webrtc.gecko.test.mjs` runs the
fake's WebRTC in Firefox, whose ICE code is GeckoView's: Gecko is stricter about TURN than Chrome
(above). It checks the relay candidate always, and the frames when that Firefox offers H.264 (on
Linux, once it has fetched OpenH264; `FIREFOX=` picks another). All skip without what they need,
except with `GW_PERF_E2E=1`, as in CI.

`test_fakeha.py` checks the fake Home Assistant against the traffic HA's frontend sent it
(`test/fakeha-traffic.json`), on a stub frontend, so the tests never download the real one. After a
frontend upgrade, record the traffic again and add any new command to the fake.

The node tests need node 24 or later, which runs `grade.ts` without a build step.

CI runs both in the `scripts` job.

## Version history

Clarity shipped with Glassworks 1.0.0 as **Clarity v1** (`clarity: 1`, protocol 1, rubric 1). Rubric 2 stops grading scrolling on a frame without touch (the lululemon Mirror); every other frame is judged as before. Still within Clarity v1, rubric 2 and protocol 1, a view whose browser page the panel closes (killed or crashed) fails that view in that run instead of the whole run ("A page the panel closes"): no record measured before had one, so none grades differently. During pre-release development, the protocol and rubric went through two earlier numbering sequences before resetting to v1 for release:

- **Pre-release protocols 1–8 and rubrics 1–11:** Iterated from synthetic pages to Home Assistant's real frontend (protocol 4), live HLS and WebRTC cameras (protocols 5–8), and feel-based grading for live updates and scrolling (rubric v5–v11). Pre-release summaries carry no `clarity` field and are flagged as `re-measure`.
- **Early v1–v3 iterations:** Split view-open paint time from post-draw Total Blocking Time (`blocking_ms`, early v2), added Home Assistant more-info dialog grading (early v3), and widened the live-update threshold to 150 ms good / 250 ms fluid. When the numbers reset to **Clarity v1** (`clarity: 1`, protocol 1, rubric 1), early v3 measurements remained byte-compatible and can be regraded with `tools/perf/regrade.py <run-dir>`, while runs dated before `clarity_measured_since` (`2026-11-16 UTC`) or from early v2 are marked `re-measure`.
