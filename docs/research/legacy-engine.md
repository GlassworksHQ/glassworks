# Legacy engine: does GeckoView 144 run on Android 6–7, locked to Home Assistant?

**Status (2026-11-15): working for the engine; the app itself is not yet.** GeckoView 144.0.2 (arm64-v8a) runs
on a real lululemon Mirror (Android 6.0.1) and on API 23 and API 25 emulators. It draws Home Assistant's real
frontend and runs the S1 app's helper extension (`window.s1kiosk`). The lockdown refuses everything outside the
dashboard's origin, with a note on the page, and nothing reached the other origin in any run. The emulators'
black tiles don't happen on the Mirror's Adreno 306. The S1 app's own screens still call API 24+ and crash on
Android 6.

Devices: a lululemon Studio Mirror (community image, Android 6.0.1, 929 MB RAM, Adreno 306, no root), run by the
maintainer's root thread; Android emulators `system-images;android-23;default;arm64-v8a` (Android 6.0) and
`system-images;android-25;default;arm64-v8a` (Android 7.1.1), 1 GB RAM, 1280×800, on an Apple Silicon Mac.
Engine: `org.mozilla.geckoview:geckoview-arm64-v8a:144.0.20251027123126` (reports itself as 144.0.2). Part of #198;
the engine choice is #39's (2026-11-15: 144, else 143).

## Question

Android 6–7 tablets and the Mirror (#29) can't run GeckoView 145+, which needs API 26. #39 settled on a frozen
engine for them, which gets no more security fixes, so it must load nothing but the panel's own Home Assistant.
Three things to find out:

1. Does GeckoView 144 run on API 23–25, with the core's engine code and the S1 helper extension unchanged?
2. Can GeckoView's delegates and a web extension hold a page to one origin, and say so on the page?
3. What does it cost: APK size per ABI, memory, and what breaks?

## What we tried

1. **The same engine code against GeckoView 144** (2026-11-15, verified). `engines/engine-gecko-legacy` compiles
   engine-gecko's `GeckoEngine.kt` and `PageGuard.kt` unchanged against GeckoView 144, with its own
   `GeckoVariant.kt` (the lockdown) in place of engine-gecko's (`build.gradle.kts`, task `sharedGeckoSources`).
   ```
   $ ./gradlew :engine-gecko-legacy:compileDebugKotlin :engine-gecko:compileDebugKotlin --rerun-tasks
   (no errors, no warnings in either)
   ```
   So every GeckoView API the engine uses exists in 144 and in 159, and none is deprecated in either:
   `GeckoRuntimeSettings.Builder` (`configFilePath`, `consoleOutput`, `aboutConfigEnabled`,
   `remoteDebuggingEnabled`), `WebExtensionController.ensureBuiltIn`, `WebExtension.MessageDelegate`, `Port`
   (`sender.isTopLevel`), `PortDelegate`, `GeckoSessionSettings.Builder`, the permission delegate,
   `ContentDelegate` (`onFirstContentfulPaint`, `onCrash`, `onKill`, `onExternalResponse(WebResponse)`),
   `NavigationDelegate` (`onCanGoBack`, `onNewSession`, `onLocationChange` with `hasUserGesture`, `onLoadRequest`,
   `onSubframeLoadRequest`), `ProgressDelegate`, `GeckoView.coverUntilFirstPaint`, `BuildConfig.MOZ_APP_VERSION`.
   The POMs differ in what they pull in: 144 wants kotlin-stdlib 2.2.10, androidx.core 1.17.0 and its own
   `geckoview-exoplayer2`; 159 wants kotlin-stdlib 2.4.20, androidx.core 1.19.1 and media3 1.11.1.

2. **APK size** (2026-11-15, verified). The AARs' sizes from maven.mozilla.org, then the test APK's parts:
   ```
   $ B=https://maven.mozilla.org/maven2/org/mozilla/geckoview
   $ for a in geckoview-arm64-v8a geckoview-armeabi-v7a; do for v in 143.0.20251003115653 144.0.20251027123126 159.0.20261102144711; do
       echo "$a $v: $(curl -sI $B/$a/$v/$a-$v.aar | grep -i '^content-length')"; done; done
   geckoview-arm64-v8a 143.0.20251003115653: content-length: 84425311
   geckoview-arm64-v8a 144.0.20251027123126: content-length: 84723838
   geckoview-arm64-v8a 159.0.20261102144711: content-length: 91498586
   geckoview-armeabi-v7a 143.0.20251003115653: content-length: 81859634
   geckoview-armeabi-v7a 144.0.20251027123126: content-length: 82222403
   geckoview-armeabi-v7a 159.0.20261102144711: content-length: 88751103
   $ unzip -lv engines/engine-gecko-legacy/test-app/build/outputs/apk/debug/kiosk-legacy-debug.apk \
       | awk 'NR>3 && $1 ~ /^[0-9]+$/ {n=$8; c=$3; if (n ~ /^lib\//) L+=c; else if (n ~ /omni.ja/) O+=c; else if (n ~ /\.dex$/) D+=c; else R+=c}
              END {printf "lib/ %.1f MB, omni.ja %.1f MB, dex %.1f MB, rest %.1f MB\n", L/1e6, O/1e6, D/1e6, R/1e6}'
   lib/ 77.3 MB, omni.ja 13.1 MB, dex 12.1 MB, rest 2.2 MB
   ```

   | AAR | 143 | 144 | 159 |
   |---|---|---|---|
   | `geckoview-arm64-v8a` | 84.4 MB | 84.7 MB | 91.5 MB |
   | `geckoview-armeabi-v7a` | 81.9 MB | 82.2 MB | 88.8 MB |

   GeckoView 144 is about 6.6–6.8 MB smaller than 159 per ABI. The legacy test APK (arm64-v8a, debug) is
   104.8 MB: `lib/` 77.3 MB compressed (GeckoView's `libxul.so` is 147 MB uncompressed; TensorFlow Lite is the next
   biggest), `omni.ja` 13.1 MB, dex 12.1 MB.

3. **Which ABI** (read): arm64-v8a. The Mirror runs a 64-bit zygote, `ro.product.cpu.abilist =
   arm64-v8a,armeabi-v7a,armeabi` (the root's probes on the Mirror, 2026-11-15, on #29; and
   [mirror-abis.md](../../frames/lululemon-mirror/research/mirror-abis.md)). armeabi-v7a would also run there, in a
   32-bit process, but loses the A53's 64-bit code for no gain. The test APK's ABI defaults to the Mirror's first
   (`frames/lululemon-mirror/design/frame.json`); `-PlegacyAbi=armeabi-v7a` builds a 32-bit one for older tablets.

4. **Run it on API 23** (2026-11-15, verified). The legacy test APK on the emulator, with
   `tools/legacy/lockdown-page.py` serving "Home Assistant" on 127.0.0.1:18125 and another origin on :18126
   (`adb -s emulator-5600 reverse` for both):
   ```
   $ adb -s emulator-5600 shell am start -n sh.edm.s1kiosk.legacy/sh.edm.s1kiosk.legacycheck.EngineCheckActivity \
       --es url http://127.0.0.1:18125/
   I LegacyCheck: Lockdown: pages may load http://127.0.0.1:18125
   I LegacyCheck: GeckoView 144.0.2 (legacy, Home Assistant only) · Android 6.0 · arm64-v8a · RAM 1000 MB (735 free)
   I LegacyCheck: Lockdown on: pages load only the dashboard's origin
   W GeckoConsole: [JavaScript Warning: "lockdown: blocked image from 127.0.0.1:18126"]
   W LegacyCheck: Lockdown: refused frame from 127.0.0.1:18126
   W GeckoConsole: [JavaScript Warning: "lockdown: blocked xmlhttprequest from 127.0.0.1:18126"]
   W GeckoConsole: [JavaScript Warning: "lockdown: blocked websocket from 127.0.0.1:18126"]
   (taps)
   W LegacyCheck: Lockdown: refused page from 127.0.0.1:18126
   W LegacyCheck: Lockdown: refused window from 127.0.0.1:18126
   W LegacyCheck: Lockdown: refused download from 127.0.0.1:18125
   ```
   The other origin's server logged no request at all. The page showed `window.s1kiosk` (the S1 helper's
   `content.js`, unchanged), the same-origin image and frame, and the note at the bottom for each refusal.
   `onSubframeLoadRequest` does fire for an iframe's `src` on 144, so the navigation delegate refuses
   cross-origin frames before any request; the frame stays empty and the note names the host.

5. **Home Assistant on API 23** (2026-11-15, verified). Clarity's fake HA (`tools/perf/fakeha.py --port 18123
   --no-harness`, HA's real frontend 20261028.5) through the same activity: the sign-in went through
   `/auth/authorize` and back (its sign-in accepts anyone), then `/clarity-ref/typical-nocam` drew. But parts of
   the page drew as black 1024×512 tiles that moved between captures, with the emulator's guest renderer
   (`hw.gpu.enabled=no`) and again with `-gpu host` (`eglCodecCommon: glUtilsParamSize: unknow param` in logcat).
   Untested whether that is the emulator's GLES or GeckoView 144 on Android 6; the Mirror's Adreno 306 decides.

6. **Memory on API 23** (2026-11-15, verified) with the dashboard open about a minute:
   ```
   $ adb -s emulator-5600 shell ps | grep s1kiosk        (columns: user pid ppid vsize rss …)
   u0_a55  3118 917 3445024 271080 … sh.edm.s1kiosk.legacy
   u0_a55  3135 917  948624  60928 … sh.edm.s1kiosk.legacy:crashhelper
   u0_a55  3168 917 3492172 276932 … sh.edm.s1kiosk.legacy:tab7
   u0_a55  3201 917 1179600 128744 … sh.edm.s1kiosk.legacy:gpu
   ```
   RSS: main process 271 MB, `:tab7` (the one content process) 277 MB, `:gpu` 129 MB, `:crashhelper` 61 MB. The legacy engine sets
   `dom.ipc.processCount=1`, `dom.ipc.processPrelaunch.enabled=false` and `fission.autostart=false`, so there is
   one content process and no spare one. RSS counts shared pages in each process, so the sum overstates the total.

7. **Content process names** (2026-11-15, verified). On Android 6, GeckoView 144 runs content in `:tab<n>`, not
   `:sandboxed_tab_<flags><n>` as on the S1's Android 10. `GeckoEngine.isContentProcess` now accepts both
   (`GeckoContentProcessTest`); the diagnostics pane counts content processes with it.

8. **Run it on API 25** (2026-11-15, verified). The first run loaded a cross-origin image: the page's first load
   started before the lockdown extension's `webRequest` listener existed (`[other] "GET /red.png" 200` in the
   page server's log). Fixed: `Page.load` now waits until the extension's background script connects, which it
   does after its listener is registered (`ReadyGate`, `ReadyGateTest`). If the extension can't install, nothing
   loads: the lockdown fails closed. The rerun matched API 23, and nothing reached the other origin.
   The Home Assistant run on API 25 didn't finish: the emulator stopped answering adb (`detected a hanging thread
   'QEMU2 CPU0 thread'`) while the Mac ran other builds at a load average of 45–107.

9. **The S1 app on API 23** (2026-11-15, verified): its MainActivity crashes at once.
   ```
   E AndroidRuntime: java.lang.NoSuchMethodError: No virtual method registerDefaultNetworkCallback(...)
   E AndroidRuntime:   at sh.edm.s1kiosk.setup.ConnectivityWifiStatus.observe(WifiSetupStatus.kt:72)
   E AndroidRuntime:   at sh.edm.s1kiosk.improv.ImprovWifiStatus.observe(ImprovWifiStatus.kt:40)
   E AndroidRuntime:   at sh.edm.s1kiosk.MainActivity.onCreate(MainActivity.kt:225)
   ```
   `registerDefaultNetworkCallback` is API 24, and the `catch (Exception)` around it doesn't catch an `Error`. That
   is the first API 24+ call, likely not the last: lint's NewApi check, which would list them all, crashes on this
   project (`StackOverflowError` in `ConfigurationHierarchy$ProjectPlaceholderConfiguration.isEnabled`, AGP 9.3.3).
   So the test APK turns the S1 app's MainActivity, BootReceiver and KioskService off, and its only entry is the
   engine check (`EngineCheckActivity`), which runs the engine through the core's `Engine` API. Running the app
   itself on API 23–25 is a later part of #198.

10. **Driving the emulators** (2026-11-15). agent-device couldn't launch an app on Android 6 (its launcher intent
    resolved to nothing; a relaunch failed with "process that ran before close did not exit") and timed out
    (`adb timed out after 10000ms`) on the loaded host, so the launches used `am start -n` and the API 25
    captures used `adb exec-out screencap`.

11. **The Mirror** (2026-11-15, verified by the root thread, 12:38–12:46 PST; results on #346). Test build
    `s1-kiosk-legacy-test-68f08af` installed with `adb install` as `shell`, removed with `adb uninstall` after.
    The lockdown page as in step 4: same origin loaded; the other origin's image, frame, fetch, XHR and WebSocket
    were blocked; the link, `window.open` and the download were refused; **0 requests** reached the other origin.
    Clarity's fake HA drew `/clarity-ref/typical-nocam` in full, with **no black tiles** on first paint, after two
    minutes of swiping, or on content drawn after scrolling. Memory after two minutes: main 68 MB RSS, content
    (`:tab36`) 114 MB, GPU 56 MB, crash helper 13 MB, about 250 MB in all, with 158 MB free plus cache. No
    `FATAL` in logcat.

12. **The lockdown, second round** (2026-11-15, API 23 emulator, verified). A review of #346 asked for the ways a
    page can reach another origin without a plain request: WebRTC, speculative connections, a cross-origin
    download, meta refresh, an auto-submitted POST, `target=_top` from a frame, and a 302. The legacy engine now
    turns off WebRTC (`media.peerconnection.enabled=false`) and Gecko's own early connections
    (`network.dns.disablePrefetch`, `network.predictor.enabled=false`, `network.http.speculative-parallel-limit=0`,
    `network.prefetch-next=false`), and `policy.js` holds top-level loads (`main_frame`) to Home Assistant too.
    `tools/legacy/lockdown-page.py` got a row for each, and its other origin now logs every **connection** as well
    as every request, so a preconnect that sends nothing would still show:
    ```
    [home] 127.0.0.1 "GET /t/meta HTTP/1.1" 200 -
    [home] 127.0.0.1 "GET /t/redirect?to=frame HTTP/1.1" 302 -
    [home] 127.0.0.1 "GET /t/post?target=_self HTTP/1.1" 200 -
    [home] 127.0.0.1 "GET /t/post?target=_top HTTP/1.1" 200 -
    [home] 127.0.0.1 "GET /t/link-top HTTP/1.1" 200 -
    W LegacyCheck: Lockdown: refused frame from 127.0.0.1:18126     (×4: the cross-origin frame, meta refresh, 302, POST)
    W LegacyCheck: Lockdown: refused page from 127.0.0.1:18126      (×2: POST to the top, link to the top)
    (taps)
    W LegacyCheck: Lockdown: refused page from 127.0.0.1:18126      (link)
    W LegacyCheck: Lockdown: refused window from 127.0.0.1:18126    (window.open, other origin)
    W LegacyCheck: Lockdown: refused download from 127.0.0.1:18125  (same-origin download: the GET reached home)
    W LegacyCheck: Lockdown: refused page from 127.0.0.1:18126      (cross-origin download: refused as a page, no GET)
    W LegacyCheck: Lockdown: refused page from 127.0.0.1:18126      (a page that 302s elsewhere)
    W LegacyCheck: Lockdown: refused page from 127.0.0.1:18126      (a page that meta-refreshes elsewhere)
    $ grep -c "LEAK:" lockpage.log
    0
    ```
    `window.open` to Home Assistant's own origin opened in place. The WebRTC row read `off (no RTCPeerConnection)`.
    `onLoadRequest` sees server redirects on 144: the 302 was refused there, before the extension's check.
    `onSubframeLoadRequest` sees a frame's meta refresh, 302 and POST.

13. **When the lockdown can't start** (2026-11-15, API 23 emulator, a scratch build with the extension's path
    broken, not committed). `Lockdown extension failed: nothing will load (the lockdown fails closed)` in logcat,
    and over the page: "Home Assistant can't load: the lockdown didn't start." No request reached either server.
    If the extension is only slow, the same screen says so after 20 s, and the page loads if it comes up later.

14. **Does the preconnect row prove anything?** (2026-11-15, API 23 emulator, verified). Round 2's row pointed
    `<link rel=preconnect>` at `127.0.0.1:18126`, and Gecko never connects speculatively to a local IP literal
    (`nsHttpConnectionMgr::SpeculativeConnect`, bug 853423, read in a review of #346). So "0 LEAK" there proved
    nothing. The row now names the other origin `localhost:18126`: a name, not an IP literal, which reaches the same
    server through `adb reverse`. A positive control, with a scratch build that drops the eight early-connection
    prefs (DNS prefetch, predictor, prefetch, `speculative-parallel-limit`, Early Hints), not committed:
    ```
    scratch build, no early-connection prefs:   [other] LEAK: connection from 127.0.0.1   (a connection, no request)
    the real build, same page, same server:     no new LEAK line; the page loaded again
    ```
    So without the prefs the page reaches the other origin with a bare connection, and with them it doesn't. The
    connection came with no request, so it was the preconnect: `<link rel=prefetch>` would have sent a GET, and the
    server logged none in either build (so prefetch is off in the scratch build too, or Gecko 144 skips it here; the
    control doesn't tell which). DNS prefetch is **untested**: it only resolves a name, which the server can't see,
    and `localhost` resolves on the device anyway. Early Hints are off too (`network.early-hints.enabled`,
    `network.early-hints.preconnect.enabled`): a 103 from Home Assistant could otherwise preconnect. Untested: a
    real 103, which the check page doesn't send.

## Result

| | GeckoView 144 on API 23–25 |
|---|---|
| Starts, draws a page | Yes: the Mirror, both emulators |
| HA's frontend, sign-in and a dashboard | Yes on the Mirror (no black tiles) and API 23 (black tiles: the emulator's); API 25 run unfinished |
| Memory with a dashboard open | About 250 MB RSS on the Mirror (929 MB RAM) |
| S1 helper extension (`content.js`, native port, `window.s1kiosk`) | Works unchanged |
| API gaps against 159 for what the engine uses | None (same source compiles against both) |
| Size per ABI | 6.6–6.8 MB smaller AAR than 159 |
| Crashes | None from GeckoView. The S1 app's MainActivity: API 24 call |

The lockdown. Every row ran on the API 23 emulator (steps 4 and 12); the first six also on API 25 (step 8) and
on the Mirror (step 11). "Nothing left" means the other origin's server logged no connection and no request.

| The page tries | Stopped by | Nothing left? | On the page |
|---|---|---|---|
| A link to another origin | `onLoadRequest` → deny | Yes | "<host> isn't your Home Assistant, so it didn't open." |
| `window.open` to another origin | `onLoadRequest` (new window) → deny | Yes | Same |
| `window.open` to Home Assistant | Loads in place, as on the current engine | — | The page |
| A same-origin download | `onExternalResponse`: nothing saved. The GET reaches Home Assistant first | — | "Downloads are off on this panel." |
| An image, fetch or WebSocket from another origin | The extension's `webRequest` blocking | Yes | "Blocked content from <host>." |
| A cross-origin iframe | `onSubframeLoadRequest` → deny | Yes | Empty frame, same note |
| A cross-origin download (`<a download>`) | `onLoadRequest`: a page load, refused | Yes | "isn't your Home Assistant" |
| A meta refresh, 302 or auto-submitted POST in a frame | `onSubframeLoadRequest` → deny | Yes | Same note |
| A POST or link with `target=_top` from a frame | `onLoadRequest` → deny | Yes | Same note |
| A top-level 302 or meta refresh to another origin | `onLoadRequest` → deny; `policy.js` also cancels a `main_frame` request off Home Assistant | Yes | Same note |
| WebRTC (`RTCPeerConnection`) | `media.peerconnection.enabled=false`: the constructor is gone | Yes (no UDP to check over `adb reverse`; the API is absent) | — |
| `<link rel=preconnect>` to `localhost` (a name: Gecko skips IP literals) | The prefs above | Yes, not even a connection; without the prefs, a connection (step 14) | — |
| `<link rel=prefetch>` to `localhost` | The prefs above | Yes, no request; but no request without the prefs either, so this row doesn't show the prefs are what stops it | — |
| `<link rel=dns-prefetch>` | `network.dns.disablePrefetch` | Untested: a DNS lookup never reaches the server | — |
| Anything, before the extension is up | `Page.load` waits; after 20 s, or if the extension fails, a message over the page | Yes | "Home Assistant can't load: the lockdown didn't start." |

Untested: a `<link rel=preconnect>` added after load by script, a service worker's own fetches, and a WebRTC
connection to a TURN server on Home Assistant (WebRTC is off, so HA's frontend uses HLS for cameras).

GeckoView's `ContentBlocking` can't do this part: it blocks by tracker lists and anti-tracking categories, not by
an origin we choose. So the per-request blocking is a built-in web extension (`assets/lockdown`), the same way the
S1 helper is built in.

## The allowed origin, once (#198 part 2)

Since this part, the lockdown takes its origin from the configured dashboard URL when the engine starts
(`EngineSettings.dashboard`, `LockdownPolicy.start`), once per process. Before, it took the origin of the app's
last `page.load`, so any load by the app could move it.

| | Before | Now |
|---|---|---|
| Where the origin comes from | The app's last `page.load(url)` | The configured dashboard (`KioskPrefs.url`), read at the engine's first open |
| An app load of another origin later | Moved the origin there | Refused like any page, with the note |
| No dashboard yet (auto, HA not found) | Nothing loaded until the first load | Nothing loads in this process; finding HA restarts it |
| A new dashboard address | Followed on the next load | Needs a new process: the settings menu's field restarts the app (`SettingsMenuTest`), the installer's `ha.set` restarts when `KioskPrefs.engineStart()` (secure hosts + dashboard origin) changes (`EngineStartTest`) |

Tested by `LockdownPolicyTest` (set once; a second `start`, an app load elsewhere and a window elsewhere change
nothing; no dashboard stays empty) and `EngineStartTest` (a new path on the same origin doesn't restart; a new
host, port or scheme does; finding HA on auto does; the engine starts with the configured URL or none).

On the API 23 emulator (2026-11-15, verified): the engine check started on `127.0.0.1:18125` loaded the check page
as before (4 frames and 2 pages refused). Then, in the same process (same pid, `am start --activity-clear-task`
with `--es url http://127.0.0.1:18126/`), the new dashboard address was refused: `Lockdown: refused page from
127.0.0.1:18126`, nothing reached the other origin's server, and with nothing else on screen the engine showed
"127.0.0.1:18126 isn't the Home Assistant the panel started with, so it didn't open. … If the dashboard's address
changed, restart the app." (`Lockdown.ELSEWHERE`; `NO_DASHBOARD` when there was none at start).

## What runs on the legacy engine

What #198's item 3 asks: the same core and panes as the current engine, where the engine allows. Two layers:
the engine (verified on the Mirror and the emulators, above), and the app's own code on Android 6–7, which
doesn't run yet (step 9: the S1 MainActivity calls API 24 at start).

**The engine, in the page:**

| Feature | On the legacy engine | How sure |
|---|---|---|
| The dashboard (HA's frontend), sign-in through `/auth/authorize` | Works | Verified on the Mirror and API 23 (steps 5, 11) |
| The helper extension: `window.s1kiosk`, the native port | Works | Verified (steps 4, 11) |
| Hiding HA's header and sidebar, the external-auth bridge (`window.externalApp`) | Same `content.js`; needs the app's MainActivity to answer it | Untested: the app doesn't run on API 23 yet |
| Camera cards | HLS only: WebRTC is off | Read (HA's frontend falls back when `RTCPeerConnection` is missing); untested |
| Map cards, iframe and webpage cards, brand icons, anything from another origin | Blocked, with the note | Verified for the kinds of request (steps 4, 12); not with a real map card |
| Downloads (backups, logs) | Refused | Verified (step 12) |
| `window.open` to HA (e.g. "open in a new tab") | Loads in place | Verified (step 12) |

**The app's code on API 23–25: the porting checklist.** `tools/legacy/api23-report.py` lists every call above
API 23 in every Android library module: it runs lint's `NewApi` check with minSdk lowered to 23 for that run only
(`tools/legacy/api23-lint.gradle`, a Gradle init script; no build file changes) and reads lint's reports. Run it
from the repo root: `python3 tools/legacy/api23-report.py > table.md`. `:engine-gecko` is left out (the current
engine, API 26; the legacy build lints its sources inside `:engine-gecko-legacy`). Of the apps, it lints the generic
app's legacy build (its `legacy` build type, minSdk 23 of its own: the app's own sources only).

CI runs it on every PR that touches Android code (`api23` in `pr.yml`) against `tools/legacy/api23-baseline.txt`,
and fails on a call above API 23 that the baseline doesn't list. Desugared calls never fail it: the legacy build
enables core library desugaring. Lint can't see a `Platform` check, so a call behind one stays in lint's findings
and in the baseline; remove a line only when lint stops reporting the call (the call is gone, or behind an
`SDK_INT` check lint understands). A lint detector that fails (`LintError`) fails the report too. Generated
sources are checked: `:engine-gecko-legacy` compiles the shared `GeckoEngine.kt` from `build/generated`.

A run of 2026-11-16 on #198 Phase C's head (#400): 109 calls, 52 of them desugared, so 57 to look at (main
before Phase A read 118 and 53, so 65). Phase C's fixes (`UpdateManager`) are in these numbers; calls added on main
since Phase A make up the rest:

| Module | Calls above API 23 | Of them desugared |
|---|---|---|
| `:esphome-api-android` | 0 | 0 |
| `:improv` | 0 | 0 |
| `:bluetooth-proxy` | 0 | 0 |
| `:sendspin` | 0 | 0 |
| `:voice` | 0 | 0 |
| `:glassworks-pane-api` | 10 | 0 |
| `:glassworks-core` | 84 | 44 |
| `:engine-gecko-legacy` | 0 | 0 |
| `:pane-diagnostics` | 1 | 0 |
| `:pane-bluetooth-proxy` | 0 | 0 |
| `:pane-camera` | 3 | 3 |
| `:pane-notifications` | 0 | 0 |
| `:pane-assist` | 0 | 0 |
| `:pane-timers` | 0 | 0 |
| `:pane-sendspin` | 5 | 5 |
| `:aqara-s1-plus-wall-base` | 3 | 0 |
| `:pane-zigbee-thread` | 3 | 0 |
| `:generic-android` (its `legacy` build, the app's own sources; added in Phase D, #408) | 0 | 0 |

Lint can't see a `Platform` check (`Platform.hasX` is a property, not an `SDK_INT` comparison), so many of these
are already guarded. Read at each call site (2026-11-16), they split like this (Phase A's fixes marked):

| Where | Calls (level) | State |
|---|---|---|
| core: `KioskService` | `NotificationChannel`, `Notification.Builder(context, channel)` (26); `startForegroundService` (26) | Guarded: `Platform.hasNotificationChannels`, `hasForegroundServiceStart` |
| core: `ui/Inter` | `Font.Builder`, `FontFamily.Builder`, `Typeface.CustomFallbackBuilder` (29) | Guarded: `Platform.hasFontBuilder`; below it the system font |
| core: `MainActivity` | window insets and cutout (28, 30) | Guarded: `Platform.hasWindowInsetsController`, `hasDisplayCutoutMode` |
| core: `update/UpdateManager` | `signingInfo`, `getLongVersionCode` (28); `setRequireUserAction` (31) | Guarded: `Platform.hasSigningInfo`, `hasLongVersionCode`, `canInstallWithoutUserAction` |
| core: `frame/GenericAndroid` | `canRequestPackageInstalls` (26) | Guarded: `Platform.canInstallWithoutUserAction` |
| pane-api: `platform/Platform` | 9 calls (26–34) | Guarded: `Platform`'s own queries |
| core: `setup/WifiSetupStatus`, `improv/ImprovController` | `registerDefaultNetworkCallback` (24) | Fixed: `Platform.registerDefaultNetworkCallback`, an internet request below 24. Was the first crash on API 23 (step 9) |
| core: `SystemLocale` | `Configuration.getLocales`/`setLocales`, `LocaleList` (24); `ActivityManager.getService` (26, hidden) | Fixed: `Platform.hasLocaleList` (`Configuration.locale` below), `hasActivityManagerGetService` (`ActivityManagerNative` below) |
| core: `ui/GlassSlider` | `AccessibilityAction.ACTION_SET_PROGRESS` (24) | Fixed: `Platform.hasSetProgressAction`; below it the slider steps |
| core: `web/Http`, `auth/OAuth` | `HashMap.putIfAbsent` (24) | Fixed: plain Kotlin |
| `pane-camera` | `ConcurrentHashMap.KeySetView.clear`, `.size`, `.remove` (24) | Fixed: the sets are typed `MutableSet` |
| `pane-diagnostics` | `Process.getStartElapsedRealtime` (24) | Fixed: `Platform.hasProcessStartTime`; below it `/proc/self/stat`'s start time |
| core: `MainActivity` | `Process.getStartElapsedRealtime` (24) | **Open**; waits for #364/#332, which own the file |
| core: `manage/AppManage` (#364) | `Process.getStartElapsedRealtime` (24): the Manage page's uptime; `DevicePolicyManager.reboot` (24) | **Open**, Phase B: the uptime would fail the Manage page's status on API 23; it needs `/proc/self/stat`'s start time as `pane-diagnostics` reads it, which `MainActivity` needs too. The reboot fails safe (`runCatching`, device owner only); the Mirror would reboot with `REBOOT` instead |
| core: `update/UpdateManager` | `registerDefaultNetworkCallback`, `getContentLengthLong` (24) | Fixed (Phase C): `Platform.registerDefaultNetworkCallback`; `contentLength`, an `Int`, is enough under the APK size limit |
| `pane-zigbee-thread`, `aqara-s1-plus-wall-base` | `Process.waitFor(timeout)`, autofill (26) | Open, but S1 hardware only (`SILABS_RADIO`, `S1_WALL_BASE`): never offered on a legacy device |
| The libraries, `pane-assist`, `-bluetooth-proxy`, `-notifications`, `-timers`, `-sendspin`, `engine-gecko-legacy` | None (`pane-sendspin`'s five are desugared) | Ready as far as API levels go; untested on a device |

Each fix has a test on Robolectric's Android 6 framework (`Api23FallbacksTest`, `PlatformApi23Test`,
`AndroidProbeApi23Test`), where the old call throws `NoSuchMethodError` or `NoSuchFieldError`. Left for the app's
own start on API 23: `MainActivity`'s calls (Phase B, after #332).

<details><summary>The full list (109 calls)</summary>

| Module | File | Line | Call | API | Desugared |
|---|---|---|---|---|---|
| `:aqara-s1-plus-wall-base` | `sh/edm/s1kiosk/base/LocalServiceLink.kt` | 126 | `java.lang.Process#waitFor` | 26 |  |
| `:aqara-s1-plus-wall-base` | `sh/edm/s1kiosk/base/UartBaseLink.kt` | 71 | `java.lang.Process#waitFor` | 26 |  |
| `:aqara-s1-plus-wall-base` | `sh/edm/s1kiosk/frame/AqaraCloud.kt` | 35 | `java.lang.Process#waitFor` | 26 |  |
| `:glassworks-core` | `dev/glassworks/frame/GenericAndroid.kt` | 73 | `android.content.pm.PackageManager#canRequestPackageInstalls` | 26 |  |
| `:glassworks-core` | `dev/glassworks/manage/HaSignIn.kt` | 147 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/HaSignIn.kt` | 147 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/HaSignIn.kt` | 147 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/HaSignIn.kt` | 158 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/HaSignIn.kt` | 158 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/HaSignIn.kt` | 158 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 71 | `java.util.Collection#removeIf` | 24 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 75 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 75 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 75 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 81 | `java.util.Collection#removeIf` | 24 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 476 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 476 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/ManageServer.kt` | 476 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/Sessions.kt` | 181 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/Sessions.kt` | 181 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/Sessions.kt` | 181 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/Sessions.kt` | 186 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/Sessions.kt` | 186 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/manage/Sessions.kt` | 186 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretPrefs.kt` | 40 | `java.util.concurrent.ConcurrentHashMap#newKeySet` | 24 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretStore.kt` | 62 | `java.util.Base64#getEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretStore.kt` | 62 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretStore.kt` | 70 | `java.util.Base64#getDecoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretStore.kt` | 70 | `java.util.Base64.Decoder#decode` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretStore.kt` | 101 | `java.util.Base64#getDecoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/secrets/SecretStore.kt` | 101 | `java.util.Base64.Decoder#decode` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/setup/LanSetup.kt` | 236 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/setup/LanSetup.kt` | 236 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/setup/LanSetup.kt` | 236 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/web/Http.kt` | 121 | `java.util.concurrent.ConcurrentHashMap#newKeySet` | 24 | yes |
| `:glassworks-core` | `dev/glassworks/web/WebSocket.kt` | 151 | `java.util.Base64#getEncoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/web/WebSocket.kt` | 151 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/web/WebSocket.kt` | 163 | `java.util.Base64#getDecoder` | 26 | yes |
| `:glassworks-core` | `dev/glassworks/web/WebSocket.kt` | 163 | `java.util.Base64.Decoder#decode` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/KioskService.kt` | 141 | `android.app.NotificationManager#createNotificationChannel` | 26 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/KioskService.kt` | 142 | `android.app.NotificationChannel()` | 26 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/KioskService.kt` | 143 | `android.app.NotificationChannel#setShowBadge` | 26 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/KioskService.kt` | 156 | `android.app.Notification.Builder()` | 26 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/KioskService.kt` | 183 | `android.content.Context#startForegroundService` | 26 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 176 | `android.view.WindowManager.LayoutParams#layoutInDisplayCutoutMode` | 28 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 176 | `android.view.WindowManager.LayoutParams#layoutInDisplayCutoutMode` | 28 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 331 | `android.os.Process#getStartElapsedRealtime` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 729 | `android.view.Window#setDecorFitsSystemWindows` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 730 | `android.view.Window#getInsetsController` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 731 | `android.view.WindowInsets.Type#navigationBars` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 731 | `android.view.WindowInsets.Type#statusBars` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 731 | `android.view.WindowInsetsController#hide` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 732 | `android.view.WindowInsetsController#setSystemBarsBehavior` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/MainActivity.kt` | 732 | `android.view.WindowInsetsController#setSystemBarsBehavior` | 30 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/SystemLocale.kt` | 35 | `android.content.res.Configuration#getLocales` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/SystemLocale.kt` | 35 | `android.os.LocaleList#get` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/SystemLocale.kt` | 35 | `android.os.LocaleList#size` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/SystemLocale.kt` | 39 | `android.content.res.Configuration#setLocales` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/SystemLocale.kt` | 39 | `android.os.LocaleList()` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 98 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 98 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 98 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 105 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 105 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 105 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 110 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 110 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/auth/OAuth.kt` | 111 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:glassworks-core` | `sh/edm/s1kiosk/manage/AppManage.kt` | 136 | `android.os.Process#getStartElapsedRealtime` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/manage/AppManage.kt` | 279 | `android.app.admin.DevicePolicyManager#reboot` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/GlassSlider.kt` | 128 | `android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction#ACTION_SET_PROGRESS` | 24 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 57 | `android.graphics.fonts.Font.Builder#setSlant` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 57 | `android.graphics.fonts.Font.Builder#setWeight` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 57 | `android.graphics.fonts.Font.Builder()` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 58 | `android.graphics.fonts.Font.Builder#build` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 58 | `android.graphics.fonts.Font.Builder#setFontVariationSettings` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 59 | `android.graphics.Typeface.CustomFallbackBuilder()` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 59 | `android.graphics.fonts.FontFamily.Builder#build` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 59 | `android.graphics.fonts.FontFamily.Builder()` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 60 | `android.graphics.Typeface.CustomFallbackBuilder#setSystemFallback` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 61 | `android.graphics.Typeface.CustomFallbackBuilder#setStyle` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 61 | `android.graphics.fonts.FontStyle()` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/ui/Inter.kt` | 62 | `android.graphics.Typeface.CustomFallbackBuilder#build` | 29 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/update/UpdateManager.kt` | 361 | `android.content.pm.PackageInfo#signingInfo` | 28 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/update/UpdateManager.kt` | 361 | `android.content.pm.SigningInfo#getApkContentsSigners` | 28 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/update/UpdateManager.kt` | 366 | `android.content.pm.PackageInfo#getLongVersionCode` | 28 |  |
| `:glassworks-core` | `sh/edm/s1kiosk/update/UpdateManager.kt` | 506 | `android.content.pm.PackageInstaller.SessionParams#setRequireUserAction` | 31 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 131 | `android.app.ActivityOptions#setPendingIntentCreatorBackgroundActivityStartMode` | 34 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 141 | `android.net.ConnectivityManager#registerDefaultNetworkCallback` | 24 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 152 | `android.content.Context#registerReceiver` | 26 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 162 | `android.app.Service#startForeground` | 29 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 173 | `android.app.ActivityManager#getHistoricalProcessExitReasons` | 30 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 174 | `android.app.ApplicationExitInfo#getProcessName` | 30 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 174 | `android.app.ApplicationExitInfo#getReason` | 30 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 184 | `TransportInfo` | 29 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 184 | `android.net.NetworkCapabilities#getTransportInfo` | 29 |  |
| `:glassworks-pane-api` | `dev/glassworks/platform/Platform.kt` | 189 | `android.content.Intent#getParcelableExtra` | 33 |  |
| `:pane-camera` | `dev/glassworks/camera/onvif/OnvifServer.kt` | 117 | `java.util.concurrent.ConcurrentHashMap#newKeySet` | 24 | yes |
| `:pane-camera` | `dev/glassworks/camera/onvif/OnvifServer.kt` | 360 | `java.util.Collection#removeIf` | 24 | yes |
| `:pane-camera` | `dev/glassworks/camera/onvif/RtspServer.kt` | 59 | `java.util.concurrent.ConcurrentHashMap#newKeySet` | 24 | yes |
| `:pane-diagnostics` | `dev/glassworks/diagnostics/AndroidProbe.kt` | 78 | `android.os.Process#getStartElapsedRealtime` | 24 |  |
| `:pane-sendspin` | `sh/edm/s1kiosk/sendspin/PrefsSsStore.kt` | 80 | `java.util.Base64#getUrlEncoder` | 26 | yes |
| `:pane-sendspin` | `sh/edm/s1kiosk/sendspin/PrefsSsStore.kt` | 80 | `java.util.Base64.Encoder#encodeToString` | 26 | yes |
| `:pane-sendspin` | `sh/edm/s1kiosk/sendspin/PrefsSsStore.kt` | 80 | `java.util.Base64.Encoder#withoutPadding` | 26 | yes |
| `:pane-sendspin` | `sh/edm/s1kiosk/sendspin/PrefsSsStore.kt` | 81 | `java.util.Base64#getUrlDecoder` | 26 | yes |
| `:pane-sendspin` | `sh/edm/s1kiosk/sendspin/PrefsSsStore.kt` | 81 | `java.util.Base64.Decoder#decode` | 26 | yes |
| `:pane-zigbee-thread` | `sh/edm/s1kiosk/radio/RadioController.kt` | 28 | `java.lang.Process#waitFor` | 26 |  |
| `:pane-zigbee-thread` | `sh/edm/s1kiosk/radio/ui/RadioSectionView.kt` | 223 | `android.view.View#setImportantForAutofill` | 26 |  |
| `:pane-zigbee-thread` | `sh/edm/s1kiosk/radio/ui/RadioSectionView.kt` | 223 | `android.view.View#setImportantForAutofill` | 26 |  |

</details>

**`Pane.requires` stays honest.** Every pane's `requires` names hardware only, and no pane needs the page to reach
another origin, so the legacy engine needs no new capability: a pane is offered on a legacy device by the same
hardware checks as anywhere. What keeps a pane from running there is the app's API 24+ calls above, not the engine.

## Updates on the legacy build (#198 Phase C)

The legacy build gets its own release line, `ReleaseLine.GENERIC_ANDROID_LEGACY` (core, `update/Releases.kt`):
tags `generic-android-legacy-v1.2.3`, APKs `glassworks-generic-android-legacy-<abi>-1.2.3.apk`. The current
generic app's line never reads those tags, and the legacy line never reads its tags (`ReleasesTest`). So a legacy
app is never offered a build that needs Android 8, and a current app is never offered the frozen engine.

`UpdateManager` installs through a `PackageInstaller` session on every Android it runs on. How it ends:

| The app | Android 6–7 | What happens (read from `UpdateManager`, tested where marked) |
|---|---|---|
| A privileged system app (`INSTALL_PACKAGES` granted: the Mirror, flashed into `/system/priv-app` by Kiln) | Commits with no prompt | Says "without a tap" (`InstallRights`, `InstallRightsTest`). On the Mirror, #398's probe app updated itself this way and kept its permissions (results in #395, `frames/lululemon-mirror/research/privileged-app.md`) |
| The device owner | Commits with no prompt | As before (#277) |
| An app someone installed | Android asks (`STATUS_PENDING_USER_ACTION`) | `UpdateInstallReceiver` shows the confirmation, as on Android 8–11 |

An update keeps only the privileged permissions the `/system` copy requested. So the legacy build declares
`INSTALL_PACKAGES`, `REBOOT` and `WRITE_SECURE_SETTINGS` from its first build (Phase D); a later build can't add one.

## The legacy build (#198 Phase D)

The legacy build is the `legacy` build type of Glassworks for generic Android (`frames/generic-android/android`):
`dev.glassworks.generic_android.legacy`, minSdk 23, `:engine-gecko-legacy` in place of `:engine-gecko`, core library
desugaring, and a manifest (`src/legacy`) that overrides the minSdk 26 of the core, the pane API and the panes. How
to build it, and why a build type and not a flavor: [its README](../../frames/generic-android/README.md#the-legacy-build-android-6-and-7).
**Don't install it on an Android 6-7 device yet.** Its `MainActivity` still makes API 24+ calls (Phase B, after
#332), so it would crash at start, and with `BootReceiver` and the home screen on, at every boot. Until then this
note's test APK (`:kiosk-legacy`, with those entry points off) stays the way to try the engine on a device.

What checks it, besides the unit tests of the shared sources:

| Check | What | Where |
|---|---|---|
| `LegacyBuildTest` | Its id is the one the app picks the legacy line by; each build has one engine; every module above API 23 is in `overrideLibrary` | `./gradlew test` |
| `size-generic` | It builds for arm64-v8a, with its id, minSdk 23, the three system permissions and GeckoView's native code | `pr.yml` |
| `api23` | No new call above API 23 in the app's own sources | `pr.yml` |
| The conformance test on API 23 | The core boots on Android 6 with a fake frame and engine, built at minSdk 23 with desugaring (`tools/legacy/api23-emulator.gradle`) | `core-emulators.yml` |
| Generic test build | Signed legacy APKs per ABI, 0.1.0 and 0.1.1 | `generic-test-build.yml` (test-build branches) |

## How to reproduce

Off-device: `./gradlew :engine-gecko-legacy:testDebugUnitTest` (the policy in Kotlin and in `policy.js`, the gate).

On an emulator or device (writes only the test APK, `sh.edm.s1kiosk.legacy`; undo: `adb uninstall`):

1. `./gradlew :kiosk-legacy:assembleDebug`, then `adb -s <serial> install` the APK from
   `engines/engine-gecko-legacy/test-app/build/outputs/apk/debug/`.
2. `python3 tools/legacy/lockdown-page.py`, and `adb -s <serial> reverse tcp:18125 tcp:18125` and the same for 18126.
3. `adb -s <serial> shell am start -n sh.edm.s1kiosk.legacy/sh.edm.s1kiosk.legacycheck.EngineCheckActivity --es url
   http://127.0.0.1:18125/`. The page lists what must load and what must not.
4. For Home Assistant: `python3 tools/perf/fakeha.py --port 18123 --no-harness`, reverse 18123, and the same
   `am start` with `--es url http://127.0.0.1:18123/clarity-ref/typical-nocam`.

## Open questions

- Setting the system language on Android 6 (`SystemLocale.set`, the installer's `set_locale`): it reaches the
  activity manager through the hidden `ActivityManagerNative.getDefault()`, which Robolectric doesn't provide.
  `SystemLocaleApi23SetTest` only shows it fails safe (false, nothing thrown); whether it works is a device check
  (#198 Phase D).

- Memory over hours on the Mirror (2 minutes: about 250 MB), and whether the low-memory killer ever takes the
  content process.
- The extensions process: GeckoView runs extensions out of process; `extensions.webextensions.remote=false`
  would save one process. Not tried.
- What the lockdown blocks that HA itself uses: brand icons from `brands.home-assistant.io`, a dashboard URL that
  redirects to another origin (`http` → `https`, a reverse proxy), and HA's external sign-in providers. Each is
  refused today and says so on the page.
- GeckoView 143 wasn't needed: 144 didn't fail on the Mirror or either emulator.
- `MainActivity`'s `url` intent extra still restarts only when the secure-context hosts change. A same-host change
  of port or scheme over that extra reloads instead, and the legacy engine then refuses the new dashboard (with the
  note) until the app restarts. The fix is one line (`engineStart()` in place of `secureHosts()`), after #364.
- The app itself on API 23–25: `MainActivity`'s API 24+ calls (Phase B).
