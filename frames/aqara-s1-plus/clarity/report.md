# Clarity report: Aqara S1 Plus

Allwinner QUAD-CORE R818 EVB2, Android 10 (API 29), armeabi-v7a, 4 cores, 1976 MB RAM, app pre-v1.

**Aqara S1 Plus: Clarity D, a good wall panel for light dashboards. Held back by typical dashboards without a camera: the main thread is blocked for 2.7 s after the view is drawn.**

Light dashboards feel good: taps, live updates, opening a view and more-info dialogs are quick, and scrolling is fluid. Bigger dashboards slow down: typical without a camera blocks for 2.7 s after it is drawn, and heavy takes 1.5 s to show a change and doesn't open a more-info dialog fully. The likely limit is the browser's main thread: it stays busy (82% of a core on typical with a camera playing), and the page draws too few frames. Memory isn't the limit (964 of 988 MB).

The grade comes only from the reference dashboards this test ships (light, typical without and with a camera, heavy), so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.

Each page's tasks (median of runs; scroll and live-update levels are the median of each run's level). A page is good when scrolling averages at least 50 fps (p95 at most 50 ms), 95% of live changes show within 100 ms, taps within 200 ms, the view is drawn within 4 s and its main thread blocks for at most 600 ms after that; fluid at 30 fps (scroll p95 at most 67 ms) and changes within 200 ms; premium when scrolling and the live changes also hold 60 fps (A+ on heavy). Live-update pacing is detail, not graded. Since Clarity v3, HA's more-info dialogs (a light's, a thermostat's and a sensor's with its history graph) settle within 1 s for good and 2 s for fluid (the slowest of them, from the tile's event), and premium asks their open animation to hold 60 fps.

| Page | Level | Tap (INP) | Scroll | Live updates | Open the view (drawn) | Blocking after it | Cameras | More-info dialog | Holds it back |
|---|---|---|---|---|---|---|---|---|---|
| light (20 cards) | fluid | 72 ms (56 to 72, n=3), good | 43.5 fps (43.2 to 45.2, n=3), p95 50 ms (50 to 50, n=3), fluid | 96.4 ms (96.2 to 97.0, n=3) to show (p95), good; pacing p95 66.7 ms (50.0 to 66.7, n=3) | 1068 ms (828 to 2185, n=3), good | 183 ms (100 to 216, n=3), good (usable at 1428 ms (1063 to 2484, n=3)) | none | settled in 950 ms (872 to 1126, n=3), good | scroll |
| typical-nocam (50 cards) | neither | 96 ms (64 to 160, n=3), good | 39.1 fps (38.5 to 39.7, n=3), p95 66.7 ms (50.0 to 66.7, n=3), fluid | 102 ms (97 to 105, n=3) to show (p95), fluid; pacing p95 83 ms (50 to 100, n=3) | 912 ms (796 to 1049, n=3), good | 2729 ms (2446 to 2862, n=3), poor (usable at 4425 ms (4088 to 4547, n=3)) | none | settled in 1224 ms (1145 to 1279, n=3), fluid | blocking |
| typical (50 cards) | neither | 176 ms (144 to 200, n=3), good | 27.3 fps (25.9 to 30.8, n=3), p95 83 ms (83 to 117, n=3), slow | 122 ms (118 to 125, n=3) to show (p95), fluid; pacing p95 100 ms (83 to 133, n=3) | 1320 ms (1296 to 1529, n=3), good | 3412 ms (3411 to 3445, n=3), poor (usable at 5692 ms (5633 to 5904, n=3)) | first frame 4376 ms (4320 to 4545, n=3), 100 % (100 to 100, n=3) shown, stalls 0 ms (0 to 0, n=3), slow | settled in 1431 ms (1303 to 1735, n=3), fluid | scroll, camera, blocking |
| heavy (120 cards) | neither | 416 ms (408 to 464, n=3), needs-improvement | 4.2 fps (4.0 to 4.3, n=3), p95 417 ms (350 to 417, n=3), slow | 1512 ms (708 to 1545, n=3) to show (p95), slow; pacing p95 300 ms (200 to 367, n=3) | 1232 ms (1171 to 1388, n=3), good | 31458 ms (30226 to 31525, n=3), poor (usable at 37187 ms (35814 to 37194, n=3)) | first frame 5413 ms (4818 to 5437, n=3), 99.8 % (99.3 to 99.9, n=3) shown, stalls n/a, slow | never settled, slow, 3 of 3 run(s) had one that didn't open fully | tap, scroll, updates, camera, blocking, dialog |

Restart, app process to a usable reference dashboard: 13963 ms (13511 to 14518, n=3) (poor; relaunched-by-system; first page about:blank). Engine ready 3737 ms (3565 to 3881, n=3), not graded. Android's TTID 1583 ms (1451 to 1619, n=3): the first frame of the kiosk's loading screen; not graded.
Viewport (CSS px, device pixel ratio): ((960, 480), 1.5); zoom check: ok.
CPU clock while each view was open (detail, every core, every 5 s): light 1512 MHz (1512 to 1512); typical-nocam 1512 MHz (1512 to 1512); typical 1512 MHz (1320 to 1512); heavy 1512 MHz (1512 to 1512); the CPU's top clock is 1608 MHz.
Camera video over the LAN (UDP), as in a real home: the panel reached this computer over UDP on the LAN, as a real setup's video goes.
typical cameras, run 1 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 2 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 0 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras, run 3 (detail): decoders 1 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 1 (detail): decoders 5 (OMX.allwinner.video.decoder.avc), 5 flushes, 0 drains; 1443 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 2 (detail): decoders 5 (OMX.allwinner.video.decoder.avc), 5 flushes, 0 drains; 1360 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
heavy cameras, run 3 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 1 flushes, 0 drains; 1134 frames presented inside the stalls (the callbacks', not the stream's); 0 segments, fetch p95 n/a (queued p95 n/a), longest gap between a stream's segments n/a.
typical cameras over WebRTC, run 1 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 399 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 2 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 404 for 450 frames, stalls by them up to 0.0 s.
typical cameras over WebRTC, run 3 (detail): decoder not reported, 450 frames decoded, 0 dropped, 0 freezes (0.0 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 397 for 450 frames, stalls by them up to 0.0 s.
typical cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 5149 ms, 100 % shown, stalls 0 ms, slow; run 2: first frame 4438 ms, 100 % shown, stalls 0 ms, slow; run 3: first frame 5102 ms, 100 % shown, stalls 0 ms, slow.
heavy cameras over WebRTC, run 1 (detail): decoder not reported, 375 frames decoded, 0 dropped, freezes n/a (the receiver's counts cover 6.2 s of the window's 30.1 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 145 for 375 frames, stalls by them up to 26.4 s.
heavy cameras over WebRTC, run 2 (detail): decoder not reported, 460 frames decoded, 0 dropped, freezes n/a (the receiver's counts cover 7.7 s of the window's 30.4 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 167 for 460 frames, stalls by them up to 25.8 s.
heavy cameras over WebRTC, run 3 (detail): decoder not reported, 892 frames decoded, 0 dropped, freezes n/a (the receiver's counts cover 7.1 s of the window's 30.1 s), candidate host/None; the page's frame callbacks (detail, how busy the page was): 208 for 892 frames, stalls by them up to 20.5 s.
heavy cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame 6162 ms, 40 % shown, stalls 22468 ms, slow; run 2: first frame 6427 ms, 39 % shown, stalls 25750 ms, slow; run 3: first frame 6580 ms, 38 % shown, stalls 23186 ms, slow.
light: more-info dialogs (detail): the first frame of the open 872 ms (414 to 950, n=3) after the tile's event, closed in 503 ms (458 to 511, n=3); their open animations missed 162 refresh(es) in all, the longest frame of an open 999 ms; shown as bottom-sheet.
typical-nocam: more-info dialogs (detail): the first frame of the open 567 ms (508 to 583, n=3) after the tile's event, closed in 680 ms (604 to 704, n=3); their open animations missed 218 refresh(es) in all, the longest frame of an open 966 ms; shown as bottom-sheet.
typical: more-info dialogs (detail): the first frame of the open 851 ms (647 to 952, n=3) after the tile's event, closed in 1054 ms (977 to 1149, n=3); their open animations missed 137 refresh(es) in all, the longest frame of an open 952 ms; shown as bottom-sheet.
heavy: more-info dialogs (detail): the first frame of the open 4244 ms (3918 to 5293, n=3) after the tile's event, closed in 661 ms (620 to 900, n=3); their open animations missed 0 refresh(es) in all, the longest frame of an open 5293 ms; shown as bottom-sheet.
heavy: more-info dialogs that didn't open fully or weren't measured: climate: it never opened within 20 s; sensor: it never opened within 20 s; sensor: its history graph wasn't drawn within 20 s.
heavy: HA still held climate.clarity_0, sensor.clarity_10 after the view's own wait in 2 of 3 run(s), so the page moved to the start view, untimed, where they came and were closed; the next view opened from wherever HA was then. What the dialogs did within their wait is this view's result.

Order of the pages (it turns each run): light, typical-nocam, typical, heavy; typical-nocam, typical, heavy, light; typical, heavy, light, typical-nocam.

| Page | 1st | 2nd | 3rd | 4th |
|---|---|---|---|---|
| light | scroll 45 fps (fluid), updates p95 97 ms (good), opens in 1068 ms |  | scroll 43 fps (fluid), updates p95 96 ms (good), opens in 2185 ms | scroll 44 fps (fluid), updates p95 96 ms (good), opens in 828 ms |
| typical-nocam | scroll 38 fps (fluid), updates p95 102 ms (fluid), opens in 1049 ms | scroll 40 fps (fluid), updates p95 105 ms (fluid), opens in 796 ms |  | scroll 39 fps (fluid), updates p95 97 ms (good), opens in 912 ms |
| typical | scroll 26 fps (slow), updates p95 125 ms (fluid), opens in 1529 ms | scroll 27 fps (slow), updates p95 122 ms (fluid), opens in 1296 ms | scroll 31 fps (slow), updates p95 118 ms (fluid), opens in 1320 ms |  |
| heavy |  | scroll 4 fps (slow), updates p95 708 ms (slow), opens in 1388 ms | scroll 4 fps (slow), updates p95 1512 ms (slow), opens in 1232 ms | scroll 4 fps (slow), updates p95 1545 ms (slow), opens in 1171 ms |

## RAM on the reference dashboards (not graded)

PSS per process, from `dumpsys meminfo`: at idle on the typical reference dashboard after each graded run, and between the graded pages. The peak is the largest of the between-page samples only (the idle samples aren't in it, so the idle median can be above it). They are taken between tasks, not inside them (a dumpsys would disturb what a task measures), so a short peak inside a task can be missed. The VmHWM line below is each process's peak RSS since it started, so it includes such a peak, but RSS counts shared pages in full: it bounds the PSS peak from above and doesn't measure it. It covers only the processes still running, with a readable VmHWM, when it is read: one that exited earlier isn't in it.

| Process | Idle (median of runs) | Peak, sampled between the graded pages |
|---|---|---|
| crashhelper | 7.1 MB (7.1 to 8.3, n=3) | 8 MB |
| gpu | 266 MB (264 to 273, n=3) | 286 MB |
| main | 207 MB (207 to 212, n=3) | 202 MB |
| media | 28.8 MB (24.6 to 32.0, n=3) | 35 MB |
| tab | 425 MB (424 to 427, n=3) | 441 MB |
| utility | 15.4 MB (15.4 to 15.5, n=3) | 15 MB |
| app, all processes | 954 MB (947 to 958, n=3) | 964 MB |

Peak RSS of the app's processes since they started (VmHWM, summed): 1407 MB, from 12 sample(s) between pages.

Budgets (frames/<id>/budgets.json):
- app_pss_peak (sampled between pages): 964 MB against 988 MB: ok
- device_available_min: 624 MB against 296 MB: ok

Not measured on their own: bluetooth-proxy (it scans only while Home Assistant listens, and Clarity's reference run has no Home Assistant listening, so on by itself it would measure an idle pane; measured by hand with HA listening (#121)); camera (the S1 has no camera, so the pane can't run here); notifications (it is off by default and, on, only waits for a notify call; Clarity's pane fixtures (tools/perf, #205) don't switch it yet); radio (its on/off is the radio mode, which Clarity leaves as it is so the panel's Zigbee or Thread network stays up); timers (it comes only with the voice assistant and has no switch of its own, so it is inside assist's share).
