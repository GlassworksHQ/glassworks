---
topic: android
evidence: verified
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
   (1080p is "high-end-gfx"). It kills a process at adj 0 when free memory and the file cache are each below
   73,728 kB, at adj 58 when each is below 92,160 kB, and a cached process when each is below 322,560 kB (read from
   the dmesg kill lines). Its 153 kill lines report a file cache between 69,396 and 312,704 kB, every one below
   the cached-process limit, and `dumpsys` reported 60 MB free. Every process of ours
   had `trm:15`, TRIM_MEMORY_RUNNING_CRITICAL.

4. **Found why the GPU process dies** (2026-11-18, read from GeckoView 144, verified on the emulator).
   `GeckoRuntime` watches the process lifecycle, and on its ON_PAUSE it tells Gecko the app went to the
   background. Gecko then calls `GeckoProcessManager.onBackground` (the native step between is inferred; the
   emulator log below shows the result), which rebinds every non-content process, the GPU process included, at
   `ServiceAllocator.PriorityLevel.IDLE`, which is `BIND_WAIVE_PRIORITY` (`0x20`). With no other binding Android 6
   treats it as a cached process. On the Mirror the lowmemorykiller may kill a cached process whenever free memory
   and the file cache are each under 322,560 kB, and both were below it in every kill line (3). The three deaths fit: 01:47:54 came 4 s after the app turned
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
   after a cold start. This emulator's kernel has no smaps, so PSS reads 0. The table gives, for each process,
   resident minus file-backed pages from `/proc/<pid>/statm` (in kB; mostly the process's anonymous memory, but
   not exactly its private memory), and the system's AnonPages from `/proc/meminfo`, which doesn't depend on that
   split. GL memory is on the host here, so it doesn't show.

   | Prefs (runs) | main | `:tab` | `:gpu` | `:crashhelper` | ours, total | AnonPages |
   |---|---|---|---|---|---|---|
   | none, today's build (4) | 137,400–155,032 | 157,344–160,052 | 34,040–34,972 | 12,360–12,400 | **351,685** (342,696–361,792) | **462,652** (448,972–475,208) |
   | `layers.gpu-process.enabled=false` (1) | 150,328 | 159,660 | gone | 12,452 | 322,440 | 447,632 |
   | bfcache 0, image cache 32 MB, memory cache 8 MB (1) | 141,680 | 162,444 | 34,692 | 12,416 | 351,232 | 469,460 |
   | JS GC heap growth 150/120/120 (1) | 140,176 | 158,924 | 34,664 | 12,356 | 346,120 | 464,060 |
   | GPU process off and no crash helper, the PR (2) | 145,128–166,100 | 153,888–158,516 | gone | gone | **311,816** (303,644–319,988) | **434,596** (426,100–443,092) |

   Bold is the mean. With the PR's build our processes hold about 40 MB less (resident minus file-backed), and the
   system's anonymous memory is about 28 MB lower, the firmer of the two numbers. Main moves by up to 18 MB between runs of the same build, so single runs can't
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
   ([image-patch.md](image-patch.md)).

8. **Read why the camera ran at 1080p and encoded in software** (2026-11-20, #679). The root's read-only pull while
   Home Assistant's ONVIF integration held the stream (2 RTSP clients): two `:tab` kills in 6 min, both at adj 0;
   free 79 MB, lost RAM 262 MB, kgsl `page_alloc` 122 MB; the camera at 1920×1080; mediaserver encoding with
   `OMX.google.h264.encoder` though `OMX.qcom.video.encoder.avc` is listed. The app asked for 1280×720
   (`camera 0 open (front, 1280x720, legacy)`).

   - **The 1080p is the LEGACY shim's preview** (read, AOSP `android-6.0.1_r81`,
     `core/java/android/hardware/camera2/legacy/RequestThreadManager.java`, `configureOutputs`). The shim sets the
     Camera1 preview to the smallest preview size with the largest JPEG size's aspect (the sensor's, within 0.01)
     whose area covers the largest output; with none, the largest preview size. It then scales that preview into
     each output on the GPU. A 4:3 5 MP sensor with a 16:9 1280×720 output and no 4:3 preview size between
     921,600 and the largest gives the largest, 1920×1080.
   - **Camera2 shows every 4:3 preview size** (read, same tag, `LegacyMetadataMapper.mapScalerStreamConfigs`). Its
     YUV list is the Camera1 preview list with its largest sizes dropped until the largest has the JPEG aspect, so
     every size of the sensor's shape stays in it, and the largest JPEG size gives that shape.
   - **So the app reads at a size of the sensor's shape on a LEGACY camera**, up to the frame's cap (`camera.maxPixels`
     in frame.json; the Mirror's is 1280×720). The preview is then that size: no 1080p buffers and no scaling.
     Which 4:3 sizes the Mirror lists is **untested**: the app logs the size it opens at.
   - **The encoder** (read, same tag, `frameworks/av` `ACodec.cpp` `UninitializedState::onAllocateComponent` and
     `OMXCodec::findMatchingCodecs`). `createEncoderByType` takes the AVC encoders in the codec list's order, with no
     preference for hardware, and when `allocateNode` fails it logs `Allocating component '…' failed, try next one.`
     and takes the next. So on the Mirror the qcom encoder either failed to allocate (that line is then in logcat) or
     is listed after Google's. Which is **untested**. The app now picks by name, hardware first, logs each encoder it
     tries and why one refuses, and falls back to Android's pick: the Mirror run answers it.
   - **The camera HAL's logging** is not ours: the app and Kiln's image set no camera debug property (read,
     `panes/camera`, `frames/lululemon-mirror/installer`).
   - **Encoding stops when the last viewer leaves** already (`ViewerEncoder`). Home Assistant's two clients kept it
     running the whole time.

9. **Read why Gecko stops giving memory back** (2026-11-21, read; #684). The root's pulls of 2026-11-21 (legacy
   0.2.0, one dashboard, stream later turned off): the lowmemorykiller took `:tabN` at 00:00, 02:09, 02:55, 03:03 and
   03:27 UTC after 7 hours with none. At 03:28 main was 158,557 kB PSS (EGL mtrack 61,756, Gfx dev 17,100, Unknown
   22,280, Dalvik 19,213) plus 114,200 swapped; the page was 121,775 PSS (Unknown 106,390) plus 86,384 swapped;
   MemFree 51 MB. #684 saw main grow from about 150 to 234 MB in 20 minutes on one view, and drop when the view changed.

   - **Android tells a process once.** AOSP `android-6.0.1_r81` `ActivityManagerService.updateOomAdjLocked` calls
     `scheduleTrimMemory` for a process in front only when its level rises (`app.trimMemoryLevel < fgTrimLevel`), and
     sets the level back to 0 only when memory is normal again. The Mirror sits at "critical" (3), so each of our
     processes hears RUNNING_CRITICAL once and never again.
   - **GeckoView passes on what it hears, no more.** GeckoView 144's `MemoryController` (jadx of the AAR) turns
     `onTrimMemory(15)` or `>= 80` into `memory-pressure` "low-memory", 40 to 79 into "low-memory" at most every
     10 s, and RUNNING_MODERATE, RUNNING_LOW and UI_HIDDEN into nothing. `GeckoRuntime` registers one in the app's
     process, and `GeckoThread` one in each child.
   - **What a "low-memory" does** (Gecko `FIREFOX_144_0_RELEASE`): `ContentParent::Observe` forwards it to every
     content process (`SendFlushMemory`); `nsJSEnvironmentObserver` runs a GC and a CC on "low-memory" (not on
     "low-memory-ongoing"); `gfxPlatform::OnMemoryPressure` in the parent sends `NotifyMemoryPressure` to the
     compositor, and `CompositorBridgeParent::NotifyMemoryPressure` calls `wr_api_notify_memory_pressure`, which clears
     WebRender's caches. With no GPU process the compositor and WebRender are in main, which is where EGL mtrack grows.
   - **So the legacy engine tells Gecko again** (`engines/engine-gecko-legacy` `MemoryPressure`): every 2 minutes it
     reads the process's trim level (`ActivityManager.getMyMemoryState`, `lastTrimLevel`) and, at RUNNING_LOW,
     RUNNING_CRITICAL or any background level, sends "low-memory" (`GeckoAppShell.notifyObservers`). Each one logs
     `memory-pressure again: Android's trim level is <n>`. Whether this holds main and the page flat on the Mirror is
     **untested**: a Mirror hour with `tools/mirror/memory-hour.sh` on each build answers it.
   - **The page process's oom adj is already the best an app gets.** `ServiceAllocator.PriorityLevel.FOREGROUND` is
     64, `BIND_IMPORTANT` (jadx), so the page gets its client's adj, 0, as the dmesg kill lines show. Only a
     persistent system app's processes go below 0 (`android:persistent`, -12 and, bound with `BIND_IMPORTANT`,
     `PERSISTENT_SERVICE_ADJ` -11, same AOSP file, `computeOomAdjLocked`), which the lowmemorykiller's table never
     reaches. Not tried: memory would still run out, the kernel would evict
     the file cache that libxul runs from, Android 6's `am force-stop` skips persistent processes (`forceStopPackageLocked` with
     `evenPersistent` false; Kiln's undo uses it), and the process restarts itself whenever it ends.
   - **The stream's cost** (read, `panes/camera`). One encoder per RTSP server, shared by every viewer
     (`ViewerEncoder`); each session queues at most 90 access units (`RtspServer.QUEUE_CAPACITY`), about 6 s at 15 fps
     and 1.4 Mbit/s, so about 1 MB. Android 6 runs OMX components, `OMX.google.h264.encoder` included, in
     mediaserver, so the encoder's buffers are in mediaserver's PSS, not ours. The camera2 LEGACY shim draws the
     preview into each output with GL in the app's process, so its textures are in main's EGL mtrack and Gfx dev.
     Measuring the stream on and off is in the plan; what to cut first is a lower size and rate on a software
     encoder.

## Result

| Cut | Where | Expected on the Mirror | How sure |
|---|---|---|---|
| No GPU process | legacy engine pref | no GPU deaths at screen-off or under a dialog; some MB | Mechanism read and seen on the emulator; memory measured on the emulator; Mirror untested |
| No crash helper | legacy engine manifest | 6.6 MB and a restart a minute | Emulator verified; Mirror untested |
| 36 unused system apps out of the image | Kiln's system.img patch | the root's one sample: free RAM 60 → 205 MB | One sample; flash untested |
| Image and memory cache caps, bfcache off, JS GC heap growth | | nothing measured | Emulator: no change |
| `onTrimMemory` | legacy engine (`MemoryPressure`, #684) | GeckoView tells Gecko once per rise of the trim level; the legacy engine tells it again every 2 minutes while memory is short | Read (9); Mirror untested |
| `largeHeap` | | none: Java is 16 MB of main's 123 MB, Gecko's heap is native | Read (meminfo) |
| lowmemorykiller limits | image | not changed. Lowering adj 0's 72 MB would let the page live longer but evict the file cache that libxul runs from, so the panel thrashes instead | Untested, judgement |
| zram size | image | not changed: 512 MB with 167 MB used is not the limit; the limit is free plus file cache | Read |
| Camera | app | unknown; its buffers sit in Lost RAM. The HAL delivered 30 frames a second (frame 17,211 after about 9.5 min) though presence asks for 5 | Read (logcat); panel plan step E measures it |
| Camera at the sensor's shape, capped at 720p; hardware encoder first | app (#679) | no 1080p preview buffers and no GPU scaling; a hardware encoder if it takes the surface | Read (AOSP 6.0.1); Mirror untested |

A killed page reloads by itself: `MainActivity.onContentGone` restarts the session and loads the dashboard, so a
`:tab` kill shows as a reload, not a crash. Only a main-process kill restarts the app.

## How to reproduce

**The Mirror's counts** (read-only). From a pull of `logcat -b events -d` and `dmesg`:

```sh
P=dev.glassworks.generic_android.legacy
grep am_proc_died events.txt | grep -c "$P]"              # the main process
grep am_proc_died events.txt | grep -c -F "$P:tab"        # the page
grep am_proc_died events.txt | grep -c -F "$P:gpu"
grep am_proc_died events.txt | grep -c -F "$P:crashhelper"
grep -c am_low_memory events.txt
grep 'lowmemorykiller: Killing' dmesg.txt | grep -o 'adj [0-9]*' | sort | uniq -c
grep -o 'below limit [0-9]*kB for oom_score_adj [0-9]*' dmesg.txt | sort -u   # the limits
```

`tools/mirror/memory-hour.sh <out> [minutes]` records a run from now on: the same counts since its start, `dumpsys
meminfo` before and after, free RAM every 5 minutes, and every minute the PSS of the app's process and of the page's
(`pss.txt`: total, swapped, EGL mtrack, Gfx dev, Unknown, Dalvik, and the page's pid, which changes when it dies). It only reads. Set `MIRROR_ADB` to the adb command that
reaches the Mirror.

**The emulator numbers.** An API 23 arm64 AVD with `hw.ramSize=1024M`, 1080×1920 at 240 dpi, and the legacy APK.
Run `tools/perf/fakeha.py --port 8189 --no-harness` on the computer; the emulator reaches it at 10.0.2.2.
Start the app with
`am start -n dev.glassworks.generic_android.legacy/sh.edm.s1kiosk.MainActivity --es url http://10.0.2.2:8189/clarity-ref/typical-nocam`
(the runs above used `adb reverse` and 127.0.0.1). After 150 s read each process's `/proc/<pid>/statm` (resident
minus shared, in 4 kB pages) and `/proc/meminfo`. Give the computer's adb server `ADB_USB=0`, so it never claims a
real panel on USB. Nothing here writes to a device other than the emulator.

## Open questions

- The Mirror's own numbers with both PRs: the root's panel run (free RAM, page kills per hour, GPU deaths).
- Whether the page still dies every few minutes once the system apps are gone. If it does, the next lever is the
  page itself: what in the dashboard holds Gecko's 169 MB.
- The camera: why the HAL runs at 30 fps when presence asks for 5, and what its buffers cost.
- The camera HAL logs every frame, so logcat's main buffer holds only about 80 s on the Mirror. A bigger buffer
  (`logcat -G`) or a quieter HAL would keep the app's own logs.
- `com.qualcomm.svi` and `com.qualcomm.cabl` restart all the time too, but may change brightness or colour: kept
  until the maintainer looks at the glass with them off.
