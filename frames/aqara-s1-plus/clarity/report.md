# Clarity report: Aqara S1 Plus

Allwinner QUAD-CORE R818 EVB2, Android 10 (API 29), armeabi-v7a, 4 cores, 1976 MB RAM, app pre-v1.

**Aqara S1 Plus: Clarity D, a good wall panel for light dashboards. Held back by typical dashboards: the main thread is blocked for 2.9 s after the view is drawn.**

Light dashboards feel good: taps, live updates and opening a view are quick, and scrolling is fluid. Bigger dashboards slow down: typical blocks for 2.9 s after it is drawn, and heavy takes 430 ms to answer a tap and takes 1.3 s to show a change. The likely limit is the browser's main thread: it stays busy (78% of a core on typical with a camera playing), and the page draws too few frames. Memory isn't the limit (904 of 988 MB).

The grade comes only from the reference dashboards this test ships (light, typical without and with a camera, heavy), so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.

Each page's tasks (median of runs; scroll and live-update levels are the median of each run's level). A page is good when scrolling averages at least 50 fps (p95 at most 50 ms), 95% of live changes show within 100 ms, taps within 200 ms, the view is drawn within 4 s and its main thread blocks for at most 600 ms after that; fluid at 30 fps (scroll p95 at most 67 ms) and changes within 200 ms; premium when scrolling and the live changes also hold 60 fps (A+ on heavy). Live-update pacing is detail, not graded.

| Page | Level | Tap (INP) | Scroll | Live updates | Open the view (drawn) | Blocking after it | Cameras | Holds it back |
|---|---|---|---|---|---|---|---|---|
| light (20 cards) | fluid | 64 ms (64 to 88, n=3), good | 46.1 fps (44.5 to 46.6, n=3), p95 50 ms (50 to 50, n=3), fluid | 99 ms (89 to 111, n=3) to show (p95), good; pacing p95 66.7 ms (50.0 to 83.3, n=3) | 1414 ms (1379 to 1790, n=3), good | 233 ms (150 to 266, n=3), needs-improvement (usable at 1768 ms (1754 to 2311, n=3)) | none | scroll |
| typical-nocam (50 cards) | neither | 120 ms (72 to 176, n=3), good | 39.3 fps (38.3 to 40.3, n=3), p95 66.7 ms (50.0 to 83.3, n=3), fluid | 101 ms (97 to 119, n=3) to show (p95), fluid; pacing p95 83.3 ms (66.7 to 83.3, n=3) | 1241 ms (1109 to 1826, n=3), good | 2862 ms (2629 to 3095, n=3), poor (usable at 5100 ms (4520 to 5446, n=3)) | none | blocking |
| typical (50 cards) | neither | 160 ms (152 to 216, n=3), good | 26.5 fps (25.8 to 26.6, n=3), p95 100 ms (100 to 117, n=3), slow | 114 ms (85 to 119, n=3) to show (p95), fluid; pacing p95 83 ms (67 to 100, n=3) | 1386 ms (1028 to 1405, n=3), good | 3982 ms (3478 to 4094, n=3), poor (usable at 6404 ms (5332 to 6493, n=3)) | first frame 4475 ms (4236 to 4502, n=3), 100 % (100 to 100, n=3) shown, stalls 0 ms (0 to 0, n=3), slow | scroll, camera, blocking |
| heavy (120 cards) | neither | 432 ms (352 to 528, n=3), needs-improvement | 4.0 fps (3.0 to 4.4, n=3), p95 433 ms (350 to 517, n=3), slow | 1347 ms (1019 to 1770, n=3) to show (p95), slow; pacing p95 283 ms (200 to 383, n=3) | 1431 ms (1096 to 1525, n=3), good | 34471 ms (32873 to 35736, n=3), poor (usable at 40433 ms (38694 to 41679, n=3)) | first frame 6059 ms (5513 to 6295, n=3), 100 % (100 to 100, n=3) shown, stalls n/a, slow | tap, scroll, updates, camera, blocking |

Restart, app process to a usable reference dashboard: 14312 ms (13780 to 14633, n=3) (poor; relaunched-by-system; first page about:blank). Engine ready 3857 ms (3772 to 3991, n=3), not graded. Android's TTID 1470 ms (1388 to 1514, n=3): the first frame of the kiosk's loading screen; not graded.
Viewport (CSS px, device pixel ratio): ((960, 480), 1.5); zoom check: ok.
CPU clock while each view was open (detail, every core, every 5 s): light 1512 MHz (1320 to 1512); typical-nocam 1512 MHz (1512 to 1512); typical 1512 MHz (1512 to 1512); heavy 1512 MHz (1512 to 1512); the CPU's top clock is 1608 MHz.
Camera video over the LAN (UDP), as in a real home: the panel reached this computer over UDP on the LAN, as a real setup's video goes.
typical cameras, run 1 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 2 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 3 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 1 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 1154 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 2 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 1270 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 3 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 1339 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras over WebRTC, run 1 (detail): decoder not reported, 451 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 387 for 451 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 2 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 409 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 3 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 381 for 450 frames, stalls by them up to 0.0 s.
typical cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 6012 ms, 100 % shown, stalls 0 ms, slow; run 2: first frame 4833 ms, 100 % shown, stalls 0 ms, slow; run 3: first frame 4957 ms, 100 % shown, stalls 0 ms, slow.
heavy cameras over WebRTC, run 1 (detail): decoder not reported, 401 frames decoded, 0 dropped, freezes n/a (the receiver's counts cover 6.7 of the window's 30.1 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 202 for 401 frames, stalls by them up to 22.2 s.
heavy cameras over WebRTC, run 2 (detail): decoder not reported, 392 frames decoded, 0 dropped, freezes n/a (the receiver's counts cover 6.5 of the window's 30.1 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 186 for 392 frames, stalls by them up to 24.8 s.
heavy cameras over WebRTC, run 3 (detail): decoder not reported, 369 frames decoded, 1 dropped, freezes n/a (the receiver's counts cover 6.1 of the window's 30.2 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 198 for 369 frames, stalls by them up to 23.7 s.
heavy cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 5409 ms, 40 % shown, stalls 21878 ms, slow; run 2: first frame 6504 ms, 39 % shown, stalls 20155 ms, slow; run 3: first frame 6241 ms, 48 % shown, stalls 20733 ms, slow.

Order of the pages (it turns each run): light, typical-nocam, typical, heavy; typical-nocam, typical, heavy, light; typical, heavy, light, typical-nocam.

| Page | 1st | 2nd | 3rd | 4th |
|---|---|---|---|---|
| light | scroll 45 fps (fluid), updates p95 99 ms (good), opens in 1414 ms |  | scroll 47 fps (fluid), updates p95 111 ms (fluid), opens in 1790 ms | scroll 46 fps (fluid), updates p95 89 ms (good), opens in 1379 ms |
| typical-nocam | scroll 39 fps (slow), updates p95 119 ms (fluid), opens in 1826 ms | scroll 38 fps (fluid), updates p95 101 ms (fluid), opens in 1241 ms |  | scroll 40 fps (fluid), updates p95 97 ms (good), opens in 1109 ms |
| typical | scroll 27 fps (slow), updates p95 119 ms (fluid), opens in 1405 ms | scroll 27 fps (slow), updates p95 114 ms (fluid), opens in 1386 ms | scroll 26 fps (slow), updates p95 85 ms (good), opens in 1028 ms |  |
| heavy |  | scroll 4 fps (slow), updates p95 1019 ms (slow), opens in 1431 ms | scroll 4 fps (slow), updates p95 1770 ms (slow), opens in 1096 ms | scroll 3 fps (slow), updates p95 1347 ms (slow), opens in 1525 ms |

## RAM on the reference dashboards (not graded)

PSS per process, from `dumpsys meminfo`: at idle on the typical reference dashboard after each graded run, and between the graded pages. The peak is the largest of those samples: they are taken between tasks, not inside them (a dumpsys would disturb what a task measures), so a short peak inside a task can be missed; the VmHWM line below catches it.

| Process | Idle (median of runs) | Peak, sampled between the graded pages |
|---|---|---|
| crashhelper | 7.6 MB (7.4 to 7.6, n=3) | 8 MB |
| gpu | 231 MB (231 to 240, n=3) | 243 MB |
| main | 202 MB (201 to 207, n=3) | 198 MB |
| media | 31.9 MB (25.1 to 32.6, n=3) | 35 MB |
| tab | 425 MB (424 to 432, n=3) | 422 MB |
| utility | 15.8 MB (15.5 to 16.0, n=3) | 16 MB |
| app, all processes | 914 MB (913 to 926, n=3) | 904 MB |

Peak RSS of the app's processes since they started (VmHWM, summed): 1394 MB, from 10 sample(s) between pages.

Budgets (frames/<id>/budgets.json):
- app_pss_peak (sampled between pages): 904 MB against 988 MB: ok
- device_available_min: 692 MB against 296 MB: ok

Not measured on their own: bluetooth-proxy (it scans only while Home Assistant listens, and Clarity's reference run has no Home Assistant listening, so on by itself it would measure an idle pane; measured by hand with HA listening (#121)); radio (its on/off is the radio mode, which Clarity leaves as it is so the panel's Zigbee or Thread network stays up); timers (it comes only with the voice assistant and has no switch of its own, so it is inside assist's share).
