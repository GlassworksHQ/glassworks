# Clarity report: Google Pixel Tablet

Google Pixel Tablet, Android 17 (API 37), arm64-v8a, 8 cores, 7441 MB RAM, app 0.2.0-519bf2e, GeckoView 159.0.

**Google Pixel Tablet: Clarity B, a good wall panel for typical dashboards, with a camera. Held back by large dashboards: a camera takes 2.1 s to show its first frame and shows 100% of its frames; its stalls weren't measured in 3 of 3 run(s); the main thread is blocked for 0.9 s after the view is drawn. Provisional: heavy: 3 camera run(s) whose receiver counts left up to 1.0 s of the window out (getStats answered late): stalls n/a, not measured; not everything was measured.**

Typical with a camera dashboards feel good: taps, live updates, opening a view, more-info dialogs and the cameras are quick, and scrolling is smooth. Bigger dashboards slow down: heavy blocks for 0.9 s after it is drawn. Memory isn't the limit (1279 of 3721 MB).

The grade comes only from the reference dashboards this test ships (light, typical without and with a camera, heavy), so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.

Each page's tasks (median of runs; scroll and live-update levels are the median of each run's level). A page is good when scrolling averages at least 50 fps (p95 at most 50 ms), 95% of live changes show within 150 ms, taps within 200 ms, the view is drawn within 4 s and its main thread blocks for at most 600 ms after that, and HA's more-info dialogs (a light's, a thermostat's and a sensor's with its history graph) settle within 1 s (the slowest of them, from the tile's event); fluid at 30 fps (scroll p95 at most 67 ms), changes within 250 ms and dialogs within 2 s; premium when scrolling, the live changes and the dialogs' open animation also hold 60 fps (A+ on heavy). Live-update pacing is detail, not graded.

| Page | Level | Tap (INP) | Scroll | Live updates | Open the view (drawn) | Blocking after it | Cameras | More-info dialog | Holds it back |
|---|---|---|---|---|---|---|---|---|---|
| light (20 cards) | good | 32 ms (24 to 32, n=3), good | 59.7 fps (59.5 to 59.8, n=3), p95 16.7 ms (16.7 to 16.7, n=3), premium | 111 ms (86 to 118, n=3) to show (p95), premium; pacing p95 16.7 ms (16.7 to 16.7, n=3) | 249 ms (182 to 262, n=3), good | 0 ms (0 to 0, n=3), good (usable at 249 ms (182 to 262, n=3)) | none | settled in 300 ms (274 to 300, n=3), good | dialog |
| typical-nocam (50 cards) | good | 40 ms (32 to 40, n=3), good | 59.5 fps (59.5 to 59.8, n=3), p95 16.7 ms (16.7 to 16.7, n=3), premium | 146 ms (100 to 153, n=3) to show (p95), premium; pacing p95 16.7 ms (16.7 to 16.7, n=3) | 533 ms (190 to 581, n=3), good | 0 ms (0 to 149, n=3), good (usable at 533 ms (512 to 581, n=3)) | none | settled in 310 ms (307 to 316, n=3), good | dialog |
| typical (50 cards) | good | 32 ms (32 to 40, n=3), good | 59.2 fps (59.2 to 59.5, n=3), p95 16.7 ms (16.7 to 16.7, n=3), premium | 109 ms (91 to 130, n=3) to show (p95), premium; pacing p95 16.7 ms (16.7 to 16.7, n=3) | 535 ms (291 to 581, n=3), good | 13.1 ms (0.0 to 83.0, n=3), good (usable at 581 ms (535 to 921, n=3)) | first frame 840 ms (786 to 913, n=3), 100 % (100 to 100, n=3) shown, stalls 0 ms (0 to 0, n=3), good | settled in 289 ms (266 to 331, n=3), good | dialog |
| heavy (120 cards) | neither | 32 ms (32 to 40, n=3), good | 59.5 fps (59.2 to 59.7, n=3), p95 16.7 ms (16.7 to 16.7, n=3), premium | 118 ms (91 to 120, n=3) to show (p95), premium; pacing p95 16.7 ms (16.7 to 16.7, n=3) | 325 ms (271 to 354, n=3), good | 881 ms (730 to 1014, n=3), poor (usable at 2218 ms (2185 to 2567, n=3)) | first frame 2058 ms (1048 to 2123, n=3), 99.7 % (99.7 to 99.8, n=3) shown, stalls n/a, n/a | settled in 465 ms (450 to 476, n=3), good | camera, blocking |

Restart, app process to a usable reference dashboard: 2926 ms (2859 to 2934, n=3) (needs-improvement; relaunched-by-system; first page about:blank). Engine ready 905 ms (842 to 924, n=3), not graded. Android's TTID n/a: the first frame of the kiosk's loading screen; not graded.
Viewport (CSS px, device pixel ratio): ((1280, 800), 2); zoom check: ok.
CPU clock while each view was open (detail, every core, every 5 s): light cpu0 1098, cpu1 1098, cpu2 1328, cpu3 1262, cpu4 1410, cpu5 1262, cpu6 984, cpu7 1106 MHz (medians); typical-nocam cpu0 1328, cpu1 1197, cpu2 1197, cpu3 1262, cpu4 1328, cpu5 1197, cpu6 1352, cpu7 1582 MHz (medians); typical cpu0 1328, cpu1 1328, cpu2 1197, cpu3 1197, cpu4 1836, cpu5 1663, cpu6 1582, cpu7 1745 MHz (medians); heavy cpu0 1328, cpu1 1328, cpu2 1328, cpu3 1328, cpu4 1836, cpu5 1836, cpu6 1582, cpu7 1745 MHz (medians); the CPU's top clock is 2850 MHz.
Camera video over the LAN (UDP), as in a real home: the panel reached this computer over UDP on the LAN, as a real setup's video goes.
The test computer's network (wired): to its gateway p95 1.0 ms, max 1.4 ms; to the panel p95 8.3 ms, max 9.8 ms. It carried the live updates and the camera video.
typical cameras, run 1 (detail): decoders 2 (c2.exynos.h264.decoder), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 2 (detail): decoders 2 (c2.exynos.h264.decoder), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 3 (detail): decoders 2 (c2.exynos.h264.decoder), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 1 (detail): decoders 8 (c2.exynos.h264.decoder), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 2 (detail): decoders 8 (c2.exynos.h264.decoder), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 3 (detail): decoders 8 (c2.exynos.h264.decoder), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras over WebRTC, run 1 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 450 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 2 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 450 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 3 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 450 for 450 frames, stalls by them up to 0.0 s.
typical cameras over HLS, the fallback (detail, not graded; fluid by the camera bars): run 1: first frame 2388 ms, 100 % shown, stalls 0 ms, fluid; run 2: first frame 2424 ms, 100 % shown, stalls 0 ms, fluid; run 3: first frame 2506 ms, 100 % shown, stalls 0 ms, fluid.
heavy cameras over WebRTC, run 1 (detail): decoder not reported, 1767 frames decoded, 3 dropped, freezes n/a (the receiver's counts cover 29.0 s of the window's 30.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 1798 for 1767 frames, stalls by them up to 0.0 s.
heavy cameras over WebRTC, run 2 (detail): decoder not reported, 1754 frames decoded, 3 dropped, freezes n/a (the receiver's counts cover 29.0 s of the window's 30.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 1797 for 1754 frames, stalls by them up to 0.0 s.
heavy cameras over WebRTC, run 3 (detail): decoder not reported, 1768 frames decoded, 2 dropped, freezes n/a (the receiver's counts cover 29.0 s of the window's 30.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 1798 for 1768 frames, stalls by them up to 0.0 s.
heavy cameras over HLS, the fallback (detail, not graded; fluid by the camera bars): run 1: first frame 2536 ms, 100 % shown, stalls 0 ms, fluid; run 2: first frame 2484 ms, 100 % shown, stalls 0 ms, fluid; run 3: first frame 2524 ms, 100 % shown, stalls 0 ms, fluid.
light: more-info dialogs (detail): the first frame of the open 83.6 ms (71.4 to 84.4, n=3) after the tile's event, closed in 230 ms (229 to 230, n=3); their open animations missed 29 refresh(es) in all, the longest frame of an open 151 ms; shown as dialog.
typical-nocam: more-info dialogs (detail): the first frame of the open 100 ms (90 to 110, n=3) after the tile's event, closed in 229 ms (227 to 230, n=3); their open animations missed 25 refresh(es) in all, the longest frame of an open 110 ms; shown as dialog.
typical: more-info dialogs (detail): the first frame of the open 160 ms (131 to 200, n=3) after the tile's event, closed in 228 ms (227 to 228, n=3); their open animations missed 26 refresh(es) in all, the longest frame of an open 200 ms; shown as dialog.
heavy: more-info dialogs (detail): the first frame of the open 256 ms (249 to 261, n=3) after the tile's event, closed in 230 ms (229 to 245, n=3); their open animations missed 45 refresh(es) in all, the longest frame of an open 261 ms; shown as dialog.

Order of the pages (it turns each run): light, typical-nocam, typical, heavy; typical-nocam, typical, heavy, light; typical, heavy, light, typical-nocam.

| Page | 1st | 2nd | 3rd | 4th |
|---|---|---|---|---|
| light | scroll 60 fps (premium), updates p95 118 ms (premium), opens in 182 ms |  | scroll 60 fps (premium), updates p95 86 ms (premium), opens in 249 ms | scroll 60 fps (premium), updates p95 111 ms (premium), opens in 262 ms |
| typical-nocam | scroll 60 fps (premium), updates p95 153 ms (fluid), opens in 190 ms | scroll 60 fps (premium), updates p95 146 ms (premium), opens in 533 ms |  | scroll 60 fps (premium), updates p95 100 ms (premium), opens in 581 ms |
| typical | scroll 59 fps (premium), updates p95 91 ms (premium), opens in 581 ms | scroll 60 fps (premium), updates p95 109 ms (premium), opens in 291 ms | scroll 59 fps (premium), updates p95 130 ms (premium), opens in 535 ms |  |
| heavy |  | scroll 59 fps (premium), updates p95 91 ms (premium), opens in 354 ms | scroll 60 fps (premium), updates p95 118 ms (premium), opens in 325 ms | scroll 60 fps (premium), updates p95 120 ms (premium), opens in 271 ms |

## RAM on the reference dashboards (not graded)

PSS per process, from `dumpsys meminfo`: at idle on the typical reference dashboard after each graded run, and between the graded pages. The peak is the largest of the between-page samples only (the idle samples aren't in it, so the idle median can be above it). They are taken between tasks, not inside them (a dumpsys would disturb what a task measures), so a short peak inside a task can be missed. The VmHWM line below is each process's peak RSS since it started, so it includes such a peak, but RSS counts shared pages in full: it bounds the PSS peak from above and doesn't measure it. It covers only the processes still running, with a readable VmHWM, when it is read: one that exited earlier isn't in it.

| Process | Idle (median of runs) | Peak, sampled between the graded pages |
|---|---|---|
| crashhelper | 5.4 MB (5.4 to 5.5, n=3) | 6 MB |
| gpu | 425 MB (416 to 430, n=3) | 580 MB |
| main | 311 MB (305 to 318, n=3) | 294 MB |
| media | 14.9 MB (14.7 to 15.5, n=3) | 16 MB |
| tab | 506 MB (496 to 506, n=3) | 386 MB |
| utility | 16.6 MB (16.6 to 16.8, n=3) | 0 MB |
| app, all processes | 1272 MB (1260 to 1291, n=3) | 1279 MB |

Peak RSS of the app's processes since they started (VmHWM, summed): 2242 MB, from 12 sample(s) between pages.

Budgets (frames/<id>/budgets.json):
- app_pss_peak (sampled between pages): 1279 MB against 3721 MB: ok
- device_available_min: 2311 MB against 1116 MB: ok

## Detail: animations it can handle (test page, not graded)

Largest element count before the first step that missed the target (median of runs). A + means it held at the ladder's last step.

| Family | 60 fps | 30 fps |
|---|---|---|
| css-transform | 1024+ (1024 to 1024, n=3) | 1024+ (1024 to 1024, n=3) |
| css-opacity | 1024 (512 to 1024, n=3) | 1024+ (1024 to 1024, n=3) |
| svg | 32 (32 to 32, n=3) | 1024+ (1024 to 1024, n=3) |
| canvas2d | 512 (512 to 512, n=3) | 1024+ (1024 to 1024, n=3) |
| ha-cards | 1024+ (1024 to 1024, n=3) | 1024+ (1024 to 1024, n=3) |

Scroll p95 33.3 ms (33.3 to 33.3, n=3). JS state burst 5 ms (5 to 5, n=3) (band good, checksum ok). Test page LCP 67 ms (62 to 97, n=3).

## Real use on this dashboard (not graded)

Your own dashboard and panes. These numbers change with your Home Assistant, so they are not in the grade.

App process start to dashboard load 1051 ms (773 to 1256, n=3) (relaunched-by-system). Dashboard load 2686 ms (1385 to 3050, n=3) (quiet-gap); its load event 1201 ms (644 to 1249, n=3); force-stop to a settled dashboard 3881 ms (2338 to 4487, n=3).
Idle: app PSS 1107 MB (1103 to 1169, n=3), device RAM in use 66.1 % (65.7 to 66.6, n=3), app CPU 21.4 % of a core (18.2 to 22.9, n=3), device busy 29.8 % (29.7 to 30.8, n=3), 0.5 frames/s (0.3 to 1.3, n=3) presented.

| Process | PSS at idle |
|---|---|
| crashhelper | 5.7 MB (5.7 to 5.7, n=3) |
| gpu | 502 MB (488 to 525, n=3) |
| main | 295 MB (287 to 295, n=3) |
| tab | 314 MB (313 to 343, n=3) |

## Memory budget (real use, not graded)

- app_pss_peak: 1169 MB against 3721 MB: ok
- device_available_min: 2485 MB against 1116 MB: ok

## Panes (real use, not graded)

| Pane | Method | PSS | CPU |
|---|---|---|---|
| radio | process | 0.0 MB | 0.0 % |
