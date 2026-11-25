# Clarity report: lululemon Studio Mirror

unknown MSM8916 for arm64, Android 6.0.1 (API 23), arm64-v8a, 4 cores, 930 MB RAM, app 0.3.0-1d900da.

**lululemon Studio Mirror: Clarity E, not a good wall panel, even for light dashboards. Held back by light dashboards: taps take 480 ms to show.**

Light dashboards come closest: opening a view is quick, and live updates and more-info dialogs are fluid. It slows down: light takes 480 ms to answer a tap, typical without a camera blocks for 4.5 s after it is drawn, and typical with a camera has a camera stream that shows no frame. On heavy, the page was lost in 3 of 3 runs: the panel ran out of memory and its browser page was closed (low-memory killer).

Scrolling: not applicable, this frame has no touch.

The grade comes only from the reference dashboards this test ships (light, typical without and with a camera, heavy), so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.

Each page's tasks (median of runs; scroll and live-update levels are the median of each run's level). A page is good when scrolling averages at least 50 fps (p95 at most 50 ms), 95% of live changes show within 150 ms, taps within 200 ms, the view is drawn within 4 s and its main thread blocks for at most 600 ms after that, and HA's more-info dialogs (a light's, a thermostat's and a sensor's with its history graph) settle within 1 s (the slowest of them, from the tile's event); fluid at 30 fps (scroll p95 at most 67 ms), changes within 250 ms and dialogs within 2 s; premium when scrolling, the live changes and the dialogs' open animation also hold 60 fps (A+ on heavy). Live-update pacing is detail, not graded.

| Page | Level | Tap (INP) | Scroll | Live updates | Open the view (drawn) | Blocking after it | Cameras | More-info dialog | Holds it back |
|---|---|---|---|---|---|---|---|---|---|
| light (20 cards) | neither | 480 ms (328 to 784, n=3), needs-improvement | n/a (no touch) | 174 ms (172 to 197, n=3) to show (p95), fluid; pacing p95 117 ms (117 to 133, n=3) | 2167 ms (2120 to 2189, n=3), good | 399 ms (313 to 700, n=3), needs-improvement (usable at 2628 ms (2577 to 3056, n=3)) | none | settled in 1846 ms (1379 to 1893, n=3), fluid | tap |
| typical-nocam (50 cards) | neither | 280 ms (208 to 800, n=3), needs-improvement | n/a (no touch) | 238 ms (225 to 250, n=3) to show (p95), fluid; pacing p95 167 ms (150 to 183, n=3) | 3897 ms (3455 to 4508, n=3), needs-improvement | 4517 ms (4483 to 4900, n=3), poor (usable at 9646 ms (9178 to 9878, n=3)) | none | settled in 2303 ms (2296 to 2313, n=3), slow | tap, blocking, dialog |
| typical (50 cards) | neither | 536 ms (368 to 600, n=3), poor | n/a (no touch) | 295 ms (283 to 440, n=3) to show (p95), slow; pacing p95 183 ms (167 to 217, n=3) | 2091 ms (1802 to 2134, n=3), good | 12400 ms (11667 to 12834, n=3), poor (usable at 20123 ms (20008 to 20263, n=3)) | a stream showed no frame, slow | settled in 2464 ms (2261 to 2629, n=3), slow | tap, updates, camera, blocking, dialog |
| heavy | neither | page lost in 3 of 3 run(s), poor | n/a (no touch) | page lost in 3 of 3 run(s), slow | page lost in 3 of 3 run(s), poor | page lost in 3 of 3 run(s), poor | the page was lost in 3 of 3 run(s), slow | the page was lost, slow | tap, updates, load, camera, blocking, dialog |

Restart, app process to a usable reference dashboard: 23548 ms (22158 to 23749, n=3) (poor; relaunched-by-system; first page about:blank). Engine ready 4624 ms (3820 to 4961, n=3), not graded. Android's TTID 3514 ms (2710 to 3645, n=3): the first frame of the kiosk's loading screen; not graded.
Viewport (CSS px, device pixel ratio): ((720, 1280), 1.5); zoom check: ok.
CPU clock while each view was open (detail, every core, every 5 s): light 1210 MHz (800 to 1210); typical-nocam 1210 MHz (998 to 1210); typical 1210 MHz (998 to 1210); heavy 1210 MHz (800 to 1210); the CPU's top clock is 1210 MHz.
Pages lost when the panel closed its browser page, measured as failing every task in that run (not "not measured"; the run's other pages count as usual): heavy in 3 of 3 run(s) (runs 1, 2 and 3): the panel ran out of memory and its browser page was closed (low-memory killer).
Camera video over the LAN (TCP, through the test's relay): UDP from the panel didn't arrive, a TCP connection did: the relay over the LAN.
The test computer's network (wired): to its gateway p95 1.9 ms, max 4.7 ms; to the panel p95 58.4 ms, max 170.7 ms. It carried the camera video.
typical cameras, run 1 (detail): 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 2 (detail): 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 3 (detail): 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 10514 ms, 28 % shown, stalls 28194 ms, slow; run 2: first frame 10111 ms, 28 % shown, stalls 28427 ms, slow; run 3: first frame 11263 ms, 23 % shown, stalls 27543 ms, slow.
heavy cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: no frame (the panel ran out of memory and its browser page was closed (low-memory killer)); run 2: no frame (the panel ran out of memory and its browser page was closed (low-memory killer)); run 3: no frame (the panel ran out of memory and its browser page was closed (low-memory killer)).
light: more-info dialogs (detail): the first frame of the open 504 ms (390 to 715, n=3) after the tile's event, closed in 1168 ms (1013 to 1301, n=3); their open animations missed 296 refresh(es) in all, the longest frame of an open 1234 ms; shown as bottom-sheet.
typical-nocam: more-info dialogs (detail): the first frame of the open 1045 ms (926 to 1122, n=3) after the tile's event, closed in 960 ms (806 to 972, n=3); their open animations missed 208 refresh(es) in all, the longest frame of an open 1317 ms; shown as bottom-sheet.
typical: more-info dialogs (detail): the first frame of the open 1094 ms (1083 to 1579, n=3) after the tile's event, closed in 1091 ms (1024 to 1092, n=3); their open animations missed 197 refresh(es) in all, the longest frame of an open 1579 ms; shown as bottom-sheet.

Order of the pages (it turns each run): light, typical-nocam, typical, heavy; typical-nocam, typical, heavy, light; typical, heavy, light, typical-nocam.

| Page | 1st | 2nd | 3rd | 4th |
|---|---|---|---|---|
| light | scroll n/a, updates p95 172 ms (fluid), opens in 2167 ms |  | scroll n/a, updates p95 197 ms (fluid), opens in 2189 ms | scroll n/a, updates p95 174 ms (fluid), opens in 2120 ms |
| typical-nocam | scroll n/a, updates p95 250 ms (fluid), opens in 3455 ms | scroll n/a, updates p95 238 ms (fluid), opens in 4508 ms |  | scroll n/a, updates p95 225 ms (fluid), opens in 3897 ms |
| typical | scroll n/a, updates p95 295 ms (slow), opens in 1802 ms | scroll n/a, updates p95 440 ms (slow), opens in 2134 ms | scroll n/a, updates p95 283 ms (slow), opens in 2091 ms |  |
| heavy |  | scroll n/a, updates p95 n/a (slow), opens in 300000 ms | scroll n/a, updates p95 n/a (slow), opens in 300000 ms | scroll n/a, updates p95 n/a (slow), opens in 300000 ms |

## RAM on the reference dashboards (not graded)

PSS per process, from `dumpsys meminfo`: at idle on the typical reference dashboard after each graded run, and between the graded pages. The peak is the largest of the between-page samples only (the idle samples aren't in it, so the idle median can be above it). They are taken between tasks, not inside them (a dumpsys would disturb what a task measures), so a short peak inside a task can be missed. The VmHWM line below is each process's peak RSS since it started, so it includes such a peak, but RSS counts shared pages in full: it bounds the PSS peak from above and doesn't measure it. It covers only the processes still running, with a readable VmHWM, when it is read: one that exited earlier isn't in it.

| Process | Idle (median of runs) | Peak, sampled between the graded pages |
|---|---|---|
| main | 192 MB (191 to 198, n=3) | 195 MB |
| tab | 194 MB (185 to 201, n=3) | 166 MB |
| utility | 3.6 MB (1.5 to 3.9, n=3) | 0 MB |
| app, all processes | 390 MB (387 to 394, n=3) | 346 MB |

Peak RSS of the app's processes since they started (VmHWM, summed): 490 MB, from 9 sample(s) between pages.

Budgets (frames/<id>/budgets.json):
- app_pss_peak (sampled between pages): 346 MB against 465 MB: ok
- device_available_min: n/a against 139 MB: n/a

## Detail: animations it can handle (test page, not graded)

Largest element count before the first step that missed the target (median of runs). A + means it held at the ladder's last step.

| Family | 60 fps | 30 fps |
|---|---|---|

Scroll p95 n/a. JS state burst n/a (band n/a, checksum MISMATCH). Test page LCP n/a.

## Real use on this dashboard (not graded)

Your own dashboard and panes. These numbers change with your Home Assistant, so they are not in the grade.

App process start to dashboard load 5782 ms (5068 to 6670, n=3) (relaunched-by-system). Dashboard load 11687 ms (11411 to 12333, n=3) (quiet-gap); its load event 11145 ms (11097 to 11738, n=3); force-stop to a settled dashboard 18579 ms (17071 to 18920, n=3).
Idle: app PSS 425 MB (401 to 426, n=3), device RAM in use n/a, app CPU 0 % of a core (0 to 0, n=3), device busy 74.3 % (73.0 to 77.0, n=3), 57.6 frames/s (56.5 to 57.7, n=3) presented.

| Process | PSS at idle |
|---|---|
| main | 230 MB (228 to 232, n=3) |
| tab | 193 MB (173 to 195, n=3) |

## Memory budget (real use, not graded)

- app_pss_peak: 426 MB against 465 MB: ok
- device_available_min: n/a against 139 MB: n/a

## Panes (real use, not graded)

| Pane | Method | PSS | CPU |
|---|---|---|---|
| radio | process | 0.0 MB | 0.0 % |
