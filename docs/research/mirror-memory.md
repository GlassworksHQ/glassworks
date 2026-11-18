---
topic: android
evidence: read
---

# The Mirror's memory: why Glassworks "crashes" on the lululemon Mirror, and what was cut

**Status (2026-11-18): working off-device, panel run pending.** It isn't a crash: the Mirror runs out of RAM.
Android's lowmemorykiller kills the page's process every few minutes and once killed the whole app. The GPU
process deaths have a separate cause: GeckoView 144 gives that process away when the app leaves the front. Two
PRs cut what we could: the app's legacy engine drops its GPU and crash-helper processes, and Kiln's flash leaves
out 36 unused system apps. Neither has run on the Mirror yet.

Device: lululemon Studio Mirror (MSM8916, Adreno 306, Android 6.0.1 Glass Panes image 2, 952,252 kB RAM, 512 MB
zram), legacy Glassworks 0.1.1 (GeckoView 144), camera use "Presence + camera". Tools: the root's read-only adb
pull of 2026-11-18 (`dumpsys meminfo`, `logcat`, `logcat -b events`, `dmesg`, `dumpsys dropbox`), GeckoView 144's
AAR decompiled with jadx, and an Android 6 arm64 emulator with 1 GB of RAM at the Mirror's 1080×1920 and 240 dpi.

## Question

The maintainer sees the app on the Mirror "crash" and the GPU process restart. Is it a crash or memory? Where does
the memory go, and what can the app or the image give back?

## What we tried

1. **Looked for real crashes** (2026-11-18, verified on the pulled logs, 01:41–02:24, after the separate
   VerifyError loop of #435 and #486). No tombstone and no native crash in `dumpsys dropbox` (only
   `system_app_strictmode`), no "Fatal signal", no `am_crash` and no ANR for our package. So nothing crashed.

2. **Counted process deaths** (2026-11-18, verified, 43 minutes of `logcat -b events`):

   | Process | Deaths | How it died |
   |---|---|---|
   | `:tabN`, the page | 8 | lowmemorykiller at adj 0, for each of the 5 deaths that dmesg still covers |
   | main (`AndroidUI`) | 1 | lowmemorykiller at adj 0, 02:14:51: the whole app restarted |
   | `:gpu` | 3 | see 4 |
   | `:crashhelper` | 18 | lowmemorykiller at adj 58, then restarted by Android a minute later |
   | `com.qualcomm.display`, `qualcommsettings`, `diagservices` | 53, 55, 55 | lowmemorykiller, restarted each time |
   | Latin keyboard, contacts provider (`acore`), `smspush` | 31, 31, 19 | the same |

   `am_low_memory` fired 266 times. dmesg holds 153 lowmemorykiller kills, 6 of them at adj 0. dmesg's ring starts
   at 01:58:32, so it can't speak for the earlier deaths.

3. **Read the memory budget** (2026-11-18, verified, `dumpsys meminfo` at 02:23):

   | | kB |
   |---|---|
   | Total RAM | 952,252, status **critical** |
   | Free RAM | 60,228 |
   | Used: PSS + kernel | 545,757 + 105,516 |
   | Lost RAM (carve-outs, GPU and camera buffers outside any PSS) | 240,751 |
   | zram | 167,524 swapped, in 72,352 physical (of 512 MB) |
   | `:tab10`, the page | 230,081 PSS, of it 169,036 Gecko's own heap ("Unknown") |
   | main | 122,973 PSS, of it 45,968 graphics, plus 75,652 swapped out |
   | `:gpu` | 57,400 PSS, nearly all of it GL memory |
   | `:crashhelper` | 6,592 PSS |
   | surfaceflinger, system_server, systemui | 28,135, 31,457, 16,765 |

   Glassworks is 417 MB of the 546 MB of PSS. Android 6 picked the lowmemorykiller's limits from the screen size
   (1080p is "high-end-gfx"): it kills at adj 0 when free memory and file cache are both below 73,728 kB, at adj 58
   below 92,160 kB, and at cached processes below 322,560 kB (read from the dmesg kill lines). Every process of ours
   had `trm:15`, TRIM_MEMORY_RUNNING_CRITICAL.

4. **Found why the GPU process dies** (2026-11-18, read from GeckoView 144, verified on the emulator).
   `GeckoRuntime` watches the process lifecycle, and on its ON_PAUSE it tells Gecko the app went to the
   background. Gecko then calls `GeckoProcessManager.onBackground` (the native step between is inferred; the
   emulator log below shows the result), which rebinds every non-content process, the GPU process included, at
   `ServiceAllocator.PriorityLevel.IDLE`, which is `BIND_WAIVE_PRIORITY` (`0x20`). With no other binding Android 6
   treats it as a cached process, and on the Mirror the lowmemorykiller kills cached processes whenever free plus
   cache is under 322,560 kB, which is all the time. The three deaths fit: 01:47:54 came 4 s after the app turned
   the screen off (PowerManager: "Going to sleep due to device administration policy"), 01:58:30 1 s after the USB
   debugging dialog covered the app, 02:14:51 with the main process. On the emulator the log shows it at
   screen-off: `ServiceAllocator: …$gpu updateBindings: IDLE priority` (2026-11-17, 18:45:53). The emulator had
   enough memory for it to survive.

   With the PR's build a killed page comes back by itself: `kill -9` of the `:tab` process at 19:38:50.4, the
   dashboard drawn and ready again at 19:38:53.5 (emulator, 2026-11-17), with no GPU process and no crash helper.

5. **Read what GeckoView 144 already does** (2026-11-18, read). `MemoryController` turns `onTrimMemory` into
   Gecko's `memory-pressure` (low-memory at level 15), so the app needs no handler of its own. `startCrashHelper`
   binds the crash helper service at every start, but with no crash handler set Gecko runs with
   `MOZ_CRASHREPORTER_DISABLE=1`: the process does nothing for us.

6. **Measured Gecko prefs on the emulator** (2026-11-17, verified). Android 6 arm64 emulator, 1 GB RAM, the legacy
   APK on Clarity's fake Home Assistant (`tools/perf/fakeha.py`, the real frontend, the `typical-nocam` view), 150 s
   after a cold start. This emulator's kernel has no smaps, so PSS reads 0: the table gives each process's private
   RSS from `/proc/<pid>/statm`, and the system's AnonPages. GL memory is on the host here, so it doesn't show.

   | Prefs (runs) | main | `:tab` | `:gpu` | `:crashhelper` | ours, total | AnonPages |
   |---|---|---|---|---|---|---|
   | none, today's build (4) | 137,400–155,032 | 157,344–160,052 | 34,040–34,972 | 12,360–12,400 | **351,685** (342,696–361,792) | **462,652** (448,972–475,208) |
   | `layers.gpu-process.enabled=false` (1) | 150,328 | 159,660 | gone | 12,452 | 322,440 | 447,632 |
   | bfcache 0, image cache 32 MB, memory cache 8 MB (1) | 141,680 | 162,444 | 34,692 | 12,416 | 351,232 | 469,460 |
   | JS GC heap growth 150/120/120 (1) | 140,176 | 158,924 | 34,664 | 12,356 | 346,120 | 464,060 |
   | GPU process off and no crash helper, the PR (2) | 145,128–166,100 | 153,888–158,516 | gone | gone | **311,816** (303,644–319,988) | **434,596** (426,100–443,092) |

   Bold is the mean. The PR's build uses about 40 MB less private memory than today's, and the system's anonymous
   memory is about 28 MB lower. Main moves by up to 18 MB between runs of the same build, so single runs can't
   resolve less than that. On every run a setup step covered the dashboard (the fake Home Assistant can't adopt the
   ESPHome device), but the page was loaded under it: with the dashboard visible the page process was 139,624 kB.

   The cache caps and the GC prefs changed nothing on a dashboard that stays on one view, so they aren't in the PR.
   Without the GPU process its private memory goes (about 35 MB) and main may grow by up to about 15 MB, since the
   compositor now runs there (within main's run-to-run spread). On the Mirror the GPU process's 57 MB is mostly GL memory, which moves to main with the compositor,
   so the saving there is likely smaller (guess: 10 to 20 MB). Its reason is that nothing can be killed behind the
   app's back any more. The crash helper is 12 MB here, 6.6 MB on the Mirror, plus a restart a minute.

7. **The root hid 36 system apps on the unit** (2026-11-18 02:35Z, verified by the root): `pm hide` works from
   adb's shell, `pm disable-user` is refused. Free RAM went from about 60 MB to 205 MB (one sample, not a
   measurement). Kiln's flash now leaves the same apps out of `system`
   ([image-patch.md](../../frames/lululemon-mirror/research/image-patch.md)).

## Result

| Cut | Where | Expected on the Mirror | How sure |
|---|---|---|---|
| No GPU process | legacy engine pref | no GPU deaths at screen-off or under a dialog; some MB | Mechanism read and seen on the emulator; memory measured on the emulator; Mirror untested |
| No crash helper | legacy engine manifest | 6.6 MB and a restart a minute | Emulator verified; Mirror untested |
| 36 unused system apps out of the image | Kiln's system.img patch | the root's one sample: free RAM 60 → 205 MB | One sample; flash untested |
| Image and memory cache caps, bfcache off, JS GC heap growth | | nothing measured | Emulator: no change |
| `onTrimMemory` | | already handled by GeckoView | Read |
| `largeHeap` | | none: Java is 16 MB of main's 123 MB, Gecko's heap is native | Read (meminfo) |
| lowmemorykiller limits | image | not changed. Lowering adj 0's 72 MB would let the page live longer but evict the file cache that libxul runs from, so the panel thrashes instead | Untested, judgement |
| zram size | image | not changed: 512 MB with 167 MB used is not the limit; the limit is free plus file cache | Read |
| Camera | app | unknown; its buffers sit in Lost RAM. The HAL delivered 30 frames a second (frame 17,211 after about 9.5 min) though presence asks for 5 | Read (logcat); panel plan step E measures it |

A killed page reloads by itself: `MainActivity.onContentGone` restarts the session and loads the dashboard, so a
`:tab` kill shows as a reload, not a crash. Only a main-process kill restarts the app.

## How to reproduce

Read-only on a Mirror, through the root: `~/.t3/glassworks-m1/mirror-ram/run-hour.sh` (not in the repo; it holds
the root's adb setup) records one hour: `am_proc_died` per process, lowmemorykiller kills, and `dumpsys meminfo`.
The emulator numbers: an API 23 arm64 AVD with `hw.ramSize=1024M`, 1080×1920 at 240 dpi; the legacy APK; and
`tools/perf/fakeha.py --port 8189 --no-harness` with `adb reverse tcp:8189 tcp:8189`, started with
`am start -n dev.glassworks.generic_android.legacy/sh.edm.s1kiosk.MainActivity --es url http://127.0.0.1:8189/clarity-ref/typical-nocam`.
Read each process's `/proc/<pid>/statm` (resident minus shared, in pages) after 150 s. Nothing here writes a device
beyond the emulator.

## Open questions

- The Mirror's own numbers with both PRs: the root's panel run (free RAM, page kills per hour, GPU deaths).
- Whether the page still dies every few minutes once the system apps are gone. If it does, the next lever is the
  page itself: what in the dashboard holds Gecko's 169 MB.
- The camera: why the HAL runs at 30 fps when presence asks for 5, and what its buffers cost.
- The camera HAL logs every frame, so logcat's main buffer holds only about 80 s on the Mirror. A bigger buffer
  (`logcat -G`) or a quieter HAL would keep the app's own logs.
- `com.qualcomm.svi` and `com.qualcomm.cabl` restart all the time too, but may change brightness or colour: kept
  until the maintainer looks at the glass with them off.
