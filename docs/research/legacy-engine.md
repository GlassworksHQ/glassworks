# Legacy engine: does GeckoView 144 run on Android 6–7, locked to Home Assistant?

**Status (2026-11-17): the engine works; the app starts and shows the dashboard on the Mirror, and the rest is still to check.** GeckoView 144.0.2 (arm64-v8a) runs
on a real lululemon Mirror (Android 6.0.1) and on API 23 and API 25 emulators. It draws Home Assistant's real
frontend and runs the S1 app's helper extension (`window.s1kiosk`). The lockdown refuses everything outside the
dashboard's origin, with a note on the page, and nothing reached the other origin in any run. The emulators'
black tiles don't happen on the Mirror's Adreno 306. Until Phase B (#412) the app's own screens called API 24+ and
crashed on Android 6. Now the legacy build starts on the Mirror with no crashes (2026-11-17, #443) and loads the
dashboard after a sign-in from a phone (2026-11-17, #459).

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
   (Recorded 2026-11-15, before #22. The activity is `dev.glassworks.legacycheck.EngineCheckActivity` now; the package id is unchanged.)
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
15. **Home Assistant's own images blocked** (#455; 2026-11-17, API 23 emulator, verified). On the Mirror, with the
    dashboard at `http://<address>:8123`, HA's image entities stayed empty and the note said "Blocked content
    from <address>:8123". Logcat: `Mixed Content: Upgrading insecure display request
    'http://<address>:8123/api/image_proxy/image.<entity>?token=…'`, then `lockdown: blocked image from
    <address>:8123`. The cause (read, Gecko source at `FIREFOX_144_0_RELEASE`):
    - The app makes HA's host a secure context (`dom.securecontext.allowlist`, `GeckoEngine.writeConfig`), so the
      microphone works over http.
    - The `LoadInfo` constructor (`netwerk/base/LoadInfo.cpp`) marks every image, audio and video load from a
      secure-context document for upgrade when `security.mixed_content.upgrade_display_content` is on, its
      default (`StaticPrefList.yaml`). Only loopback documents are exempt. `ShouldSecureUpgradeNoHSTS`
      (`netwerk/base/nsNetUtil.cpp`) then rewrites the URL to https and logs the line above.
      `nsMixedContentBlocker::ShouldLoad` lets the http load through because the target is allowlisted, but the
      upgrade is decided elsewhere.
    - Gecko 158 also exempts allowlisted documents (`GetIsSecureContextAllowlistedHost`, in
      `FIREFOX_158_0_RELEASE` and `FIREFOX_159_0_RELEASE`, not in 144 to 155). So the S1 (GeckoView 159) isn't
      affected, lockdown or not.
    - Not the cause: a CSP `upgrade-insecure-requests` from HA logs "Content Security Policy: Upgrading…", and
      HTTPS-Only or HTTPS-First log "HTTPS-Only Mode"/"HTTPS-First Mode". The Mirror logged neither.

    Why no check caught it: `lockdown-page.py` serves "Home Assistant" on 127.0.0.1, which is loopback (never
    upgraded), and `EngineCheckActivity` started the engine with no secure hosts. The check now allowlists the
    dashboard's host, as the app does, and an emulator reaches the page at `http://10.0.2.2:18125/`, which isn't
    loopback to Gecko. The same page, before and after:
    ```
    before:  Mixed Content: Upgrading insecure display request 'http://10.0.2.2:18125/green.png' to use 'https'
             lockdown: blocked image from 10.0.2.2:18125        (the server got no GET /green.png; image broken)
    after:   security.mixed_content.upgrade_display_content: false
             [home] "GET /green.png HTTP/1.1" 200               (image shown; no upgrade line)
    ```
    The other origin stayed blocked in both runs (image, frame, XHR, WebSocket), with no `LEAK` line. The fix is
    the pref, set by the lockdown (`Lockdown.prefs`), not a lockdown rule that allows https on HA's host: that
    origin is still refused (`policy.test.mjs` pins it). The note now names a blocked origin with its scheme when
    it has HA's host and port (`https://<address>:8123`), so it no longer reads as HA's own address.

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
| A link to another origin | `onLoadRequest` → deny | Yes | "<host> isn't your Home Assistant's address, so it didn't open." |
| `window.open` to another origin | `onLoadRequest` (new window) → deny | Yes | Same |
| `window.open` to Home Assistant | Loads in place, as on the current engine | — | The page |
| A same-origin download | `onExternalResponse`: nothing saved. The GET reaches Home Assistant first | — | "Downloads are off on this panel." |
| An image, fetch or WebSocket from another origin | The extension's `webRequest` blocking | Yes | "Blocked content from <host>." |
| A cross-origin iframe | `onSubframeLoadRequest` → deny | Yes | Empty frame, same note |
| A cross-origin download (`<a download>`) | `onLoadRequest`: a page load, refused | Yes | "isn't your Home Assistant's address" |
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
changed, restart the app." (`Lockdown.ELSEWHERE`; `NO_DASHBOARD` when there was none at start). Since step 15
the message says "…isn't the Home Assistant address the panel started with…".

## What runs on the legacy engine

What #198's item 3 asks: the same core and panes as the current engine, where the engine allows. Two layers:
the engine (verified on the Mirror and the emulators, above), and the app's own code on Android 6–7. The app ran
into API 24 at start (step 9); since Phase B (#412) it runs. On the Mirror it starts with no crashes (2026-11-17,
#443) and loads the dashboard after a sign-in from a phone (2026-11-17, #459).

**The engine, in the page:**

| Feature | On the legacy engine | How sure |
|---|---|---|
| The dashboard (HA's frontend), sign-in through `/auth/authorize` | Works | Verified on the Mirror and API 23 (steps 5, 11) |
| The helper extension: `window.s1kiosk`, the native port | Works | Verified (steps 4, 11) |
| Hiding HA's header and sidebar, the external-auth bridge (`window.externalApp`) | Same `content.js`; needs the app's MainActivity to answer it | The bridge: inferred to work on the Mirror (2026-11-17, #459): after a sign-in from a phone the dashboard loaded signed in, which it can only do through the bridge. No log shows `getExternalAuth`. Hiding the header and sidebar: untested |
| Camera cards | HLS only: WebRTC is off | Read (HA's frontend falls back when `RTCPeerConnection` is missing); untested |
| Map cards, iframe and webpage cards, brand icons, anything from another origin | Blocked, with the note | Verified for the kinds of request (steps 4, 12); not with a real map card |
| Home Assistant's other pages: history, logbook, settings, the profile (`/profile/dashboard`) | Work | Verified on API 23, Vitro off: history, logbook and settings in #823's run, the profile (2026-11-24, [below](#a-blank-profilegeneral-is-home-assistants-not-the-engines-2026-11-24)). `/profile/general` is blank in every browser: HA 2026.11 has no such route |
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
| core: `MainActivity`, `manage/AppManage` | `Process.getStartElapsedRealtime` (24): setup's grace time, the Manage page's uptime | Fixed (Phase B): `Platform.processStartElapsedMs`, `/proc/self/stat`'s start time below 24, as `pane-diagnostics` reads it (`PlatformApi23Test`) |
| core: `manage/AppManage` | `DevicePolicyManager.reboot` (24) | Fixed (Phase B): `Platform.hasDeviceOwnerReboot`; a system app holding `REBOOT` (the Mirror) reboots through `PowerManager.reboot` instead, on any Android (`RebootRouteTest`) |
| core: `update/UpdateManager` | `registerDefaultNetworkCallback`, `getContentLengthLong` (24) | Fixed (Phase C): `Platform.registerDefaultNetworkCallback`; `contentLength`, an `Int`, is enough under the APK size limit |
| `pane-zigbee-thread`, `aqara-s1-plus-wall-base` | `Process.waitFor(timeout)`, autofill (26) | Open, but S1 hardware only (`SILABS_RADIO`, `S1_WALL_BASE`): never offered on a legacy device |
| The libraries, `pane-assist`, `-bluetooth-proxy`, `-notifications`, `-timers`, `-sendspin`, `engine-gecko-legacy` | None (`pane-sendspin`'s five are desugared) | Ready as far as API levels go; untested on a device |

Each fix has a test on Robolectric's Android 6 framework (`Api23FallbacksTest`, `PlatformApi23Test`,
`AndroidProbeApi23Test`, `RebootRouteTest`), where the old call throws `NoSuchMethodError` or `NoSuchFieldError`. The
core's conformance test starts `MainActivity` on an API 23 emulator (`core-emulators.yml`, Phase B).

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
| A privileged system app (`INSTALL_PACKAGES` granted: the Mirror, flashed into `/system/priv-app` by Kiln) | Commits with no prompt | Says "without a tap" (`SilentUpdates`, `SilentUpdatesTest`). On the Mirror, #398's probe app updated itself this way and kept its permissions (results in #395, `frames/lululemon-mirror/research/privileged-app.md`) |
| The device owner | Commits with no prompt | As before (#277) |
| An app someone installed | Android asks (`STATUS_PENDING_USER_ACTION`) | `UpdateInstallReceiver` shows the confirmation, as on Android 8–11 |

An update keeps only the privileged permissions the `/system` copy requested. So the legacy build declares
`INSTALL_PACKAGES`, `REBOOT` and `WRITE_SECURE_SETTINGS` from its first build (Phase D); a later build can't add one.

## The legacy build (#198 Phase D)

The legacy build is the `legacy` build type of Glassworks for generic Android (`frames/generic-android/android`):
`dev.glassworks.generic_android.legacy`, minSdk 23, `:engine-gecko-legacy` in place of `:engine-gecko`, core library
desugaring, and a manifest (`src/legacy`) that overrides the minSdk 26 of the core, the pane API and the panes. How
to build it, and why a build type and not a flavor: [its README](../../frames/generic-android/README.md#the-legacy-build-android-6-and-7).
**Run on the Mirror (2026-11-17).** Phase B fixed `MainActivity`'s API 24+ calls, and the core's conformance test starts
`MainActivity` on an API 23 emulator. On the Mirror (Android 6.0.1) the legacy APK first crashed six times on the
notification icon; with #443's fix it starts with no crashes, as the only home screen, on the portrait setup screen
(#443's run). After a sign-in from a phone it loads the dashboard (#459's run). No Android 6-7 tablet has run it yet.
Try a new build where a crash loop is cheap first: with `BootReceiver` and the home screen on, a crash at start would
repeat at every boot.
`:kiosk-legacy` (those entry points off) stays the safe way to try the engine alone.

What Phase B added for the Mirror, from the device and not from frame code (`GenericAndroid`, `GenericAndroidDeviceTest`):

| What | How | Tested |
|---|---|---|
| Portrait glass | Kiln's first boot locks the Mirror's Android in portrait (`user_rotation=3`, auto-rotate off; `ro.sf.hwrotation` does nothing there, 2026-11-17). The app followed that rotation (`ANY`, `SCREEN_ORIENTATION_FULL_USER`) and stood upright on the glass (verified 2026-11-17, the root's run of #439, the maintainer's look). But Android 6 turns auto-rotate on and the rotation to 0 on every update of the app (measured 2026-11-20, #686), so since #686 the Mirror's frame is `PORTRAIT_FIXED`: the app asks for `SCREEN_ORIENTATION_PORTRAIT`, which AOSP 6.0.1 gives the display's portrait rotation whatever auto-rotate says: 3 on a landscape panel, but 1 if the image sets `config_reverseDefaultRotation`, and a rotation lock on 1 would override it (read in `PhoneWindowManager.java`; which one the Mirror gets is **untested**, `dumpsys window policy` prints `mPortraitRotation=`). The S1's frame keeps landscape, as its manifest asks. Phase B first used the screen at rotation 0, which held a Pixel Tablet portrait on its dock (2026-11-16) | `OrientationTest` (the S1, the Pixel Tablet, the Mirror's model alone, an unknown tablet, a turn), `MirrorOrientationTest` (the Mirror, #686) |
| No touchscreen | `Frame.input` is `Input.NONE` when Android lists input devices and none is a touchscreen: setup then works from a computer (#34, #404) | `GenericAndroidDeviceTest` (the rule). On the Mirror: the no-touch portrait setup screen showed (2026-11-17, #443's run); setup from a computer over Wi-Fi isn't checked yet |
| Reboot | `REBOOT`: `PowerManager.reboot` | `RebootRouteTest` |

What checks it, besides the unit tests of the shared sources:

| Check | What | Where |
|---|---|---|
| `LegacyBuildTest` | Its id is the one the app picks the legacy line by; each build has one engine; every module above API 23 is in `overrideLibrary` | `./gradlew test` |
| `size-generic` | It builds for arm64-v8a, with its id, minSdk 23, the three system permissions and GeckoView's native code | `pr.yml` |
| `api23` | No new call above API 23 in the app's own sources | `pr.yml` |
| The conformance test on API 23 | The core boots on Android 6 with a fake frame and engine, built at minSdk 23 with desugaring (`tools/legacy/api23-emulator.gradle`) | `core-emulators.yml` |

**Desugaring and its licence.** Core library desugaring compiles the `desugar_jdk_libs` classes the app calls
(`java.time`, `Base64` and friends) into the legacy APK as `j$.*` (863 classes, #408). That code is GPL-2.0 with the
Classpath exception. The maintainer allowed it on 2026-11-16, in the generic legacy APK and the engine's debug test
app, as the one exception to the no-GPL rule (`AGENTS.md`, rule 4); it's in `THIRD-PARTY-NOTICES.md` and `LICENSES/`.
Where it goes:

| Build | Desugaring | `desugar_jdk_libs` code in the APK |
|---|---|---|
| Generic legacy (`assembleLegacy`) | On | Yes, the classes it calls |
| Generic current (`assembleRelease`, minSdk 26) | On, because AGP turns it on per module, not per variant | None. D8 adds a 3.8 kB dex of inert API stubs, so the APK isn't byte-identical to one without desugaring (#408) |
| S1 Kiosk (`:kiosk`) | Off | None |
| `:kiosk-legacy` (the engine's debug test app) | On | Yes. A test build artifact on branches named `legacy`, never released. Allowed too (maintainer, 2026-11-16) |

Test builds publish the legacy APK (`generic-test-build.yml`, arm64-v8a, 0.1.0 and 0.1.1). There's no release yet:
`release.yml` doesn't build the generic app or its legacy build; the release key exists since 2026-11-17. The app's legacy
release line ([above](#updates-on-the-legacy-build-198-phase-c)) is ready for one.

## Code that Android 6's verifier rejects (#435, verified 2026-11-18)

Newer Android runs dex that Android 6 rejects. On the Mirror, the legacy build of #435 crashed at every start with
`VerifyError: Rejecting class dev.glassworks.platform.UsbDebugging$Requests because it failed compile-time
verification`. On an API 23 arm64 emulator, dex2oat at install gave the reason:

```
Verification error in void dev.glassworks.platform.UsbDebugging$Requests.start$lambda$0(…)
… [0xC1] expected to be within a catch-all for an instruction where a monitor is held
Verifier rejected class dev.glassworks.platform.UsbDebugging$Requests due to bad method …
```

The method was a thread's body with `synchronized` blocks, a `useLines` loop and a `return@Thread` out of both. R8
left an instruction that can throw outside the monitor's catch-all, which Android 6 refuses and later Android accepts.
The fix keeps monitors out of such bodies: each locked step is its own small `@Synchronized` method. What rules
apply:

- **Android 6 verifies every class at install** (dex2oat), so the install's log shows a rejected class before any
  code runs. A class rejected there throws `VerifyError` when first used, which can be only on one device (the
  Mirror's privileged path) and never on an emulator start.
- **The core's conformance APK doesn't show it.** R8 shapes the release dex. `tools/legacy/verify-api23.sh` installs
  the real legacy APK on an API 23 emulator and fails on a rejected Glassworks class; CI runs it
  (core-emulators.yml, `legacy-verify`).
- **A part the panel can run without loads through `orWithout`** (the Manage page, the "Allow a new computer"
  card), so a class like this costs that part, not the app.

## A blank `/profile/general` is Home Assistant's, not the engine's (2026-11-24)

**Result: `/profile/general` no longer exists in Home Assistant, so it is blank in every browser. Nothing on the
legacy engine to fix.** The S1 app's README used it as the example for `--es open`; it now opens `/history`.

**How we got here.** The agent fixing the Vitro legacy fallback (#823) opened `/profile/general` with `--es open` on
an API 23 emulator (Vitro off) and got a blank page, while history, logbook and settings loaded. A blank page on the
frozen engine points first at the engine, so the hypotheses were, in order of cost to check:

1. The lockdown refuses something the profile page loads (a second origin, a `blob:` or `data:` worker).
2. GeckoView 144 lacks a JS feature the page uses (it pulls in push notifications, theme pickers, Assist).
3. The external bus: HA's frontend sends the app a message that awaits an answer `content.js` never gives.
4. The route itself.

Number 4 was the cheapest and turned out to be it. HA's profile panel is one small chunk that lists its routes, and
`general` isn't one of them; the other three were then ruled out by trying the same URL in a desktop browser.

**What we ran (verified 2026-11-24, HA 2026.11.3 in Docker, `ghcr.io/home-assistant/home-assistant:stable`):**

1. The profile panel's routes, read from HA's own frontend in the container (**read**: `hass_frontend/frontend_latest/56217.d21a1b26aa949498.js`,
   the chunk that defines `ha-panel-profile`):
   `grep -l profile-section-general frontend_latest/*.js` finds nothing; `ha-panel-profile`'s `routerOptions` are
   `defaultPage: "dashboard"` and routes `dashboard`, `preferences`, `localization`, `browser` and `security`.
   HA's router renders nothing for a path that matches no route, and the kiosk hides HA's header and sidebar, so
   on a panel the whole screen is the page background.
2. Desktop Chromium 141 (Playwright, headless, signed in as a test user): `/profile/general` shows HA's sidebar and
   an empty page with no console error; `/profile/dashboard` shows the profile (user card, theme, Appearance,
   Localization, This browser, Security). So hypotheses 1–3 are out: no lockdown, no GeckoView 144, no app bridge
   in that run. (Playwright's Firefox didn't start on this Mac; not needed.)
3. The legacy APK built from main (`./gradlew :generic-android:assembleLegacy -Ps1Abi=arm64-v8a -PlegacyAbi=arm64-v8a`,
   zipaligned and signed with the debug key) on an API 23 arm64 emulator (`system-images;android-23;default;arm64-v8a`,
   `wm size 1280x800`), the app set to `--es url http://10.0.2.2:8133` and signed in with
   `tools/legacy/emulator-signin.sh` (the panel's own QR sign-in server through `adb forward tcp:18099 tcp:8099`,
   then HA's `/auth/login_flow` API with the S256 `code_challenge` from the authorize URL: HA 2026.11 refuses the
   code exchange with `invalid_request` without it). Then, 45 s apart:
   `am start -n dev.glassworks.generic_android.legacy/dev.glassworks.MainActivity --es open http://10.0.2.2:8133/profile/<route>`.
   Each `open` loads a new page behind the app's connecting screen, which lifts on HA's `frontend/loaded` (logcat:
   `Connecting screen hidden: ready, frontend/loaded`, 4.8 s for `general`, 2.9 s for `dashboard`), so a capture
   taken sooner shows that screen, not HA.
   - `general`: one color over the whole page, `#fafafa` (HA's background; counted with PIL), as in Chromium. The
     kiosk hides HA's header and sidebar, so nothing else is left.
   - `dashboard`: the full profile (1,763 colors), including **This mobile app**, the section HA shows only to an
     external app, so the external-auth bridge answered `config/get` on GeckoView 144.
   - Logcat over both: no `lockdown: blocked`, no `Loading failed for the module`, no `Script terminated by timeout`.

**A dead end worth knowing.** The first emulator run, at the AVD's own 2560×1800 with SwiftShader, while the Mac ran
other builds (load average 600–850), looked like a real engine bug: 87 `Loading failed for the module` warnings for
HA's chunks, a `Script terminated by timeout` inside them, and the app's connecting screen that never left, for
`/profile/dashboard` too. HA served every one of those chunks (200 to `curl`).

The likely cause is the host, not GeckoView, but the runs don't prove it. The later runs changed two things at once,
the display (1280×800 with `wm size`) and the load. At 1280×800 the load alone made a large difference: the dashboard
took 59 s to load at load 440–700 (logcat `Connecting screen hidden … (58726 ms …)`), 7.8 s at about 300 after the
reboot, and 6.4 s at about 50, with no module failure or script timeout in the last two. No run was made at
2560×1800 with the host idle, so an engine problem at that size is not ruled out (**untested**). Before blaming the
legacy engine for chunks that don't load on an emulator, check `uptime` on the host and try the display at 1280×800.

Lesson for the next blank HA page on the legacy engine: open the same URL in a desktop browser before suspecting
GeckoView 144. HA renames and removes frontend routes between releases.

## `--es open /history` refused on the Mirror: a path has no origin (2026-11-28)

**Result: the app handed the `open` extra to the engine as it came, so a bare path (`/history`) reached Vitro's
fallback, and the legacy lockdown, without an origin. The app now puts a path on the dashboard's origin before any
engine gets it (`FrontendUrls.resolve`), and Vitro does the same for a path that reaches its `load`.**

**How we got here.** On the lululemon Mirror (Android 6, legacy GeckoView 144, a generic legacy test build of
`exp/vitro` at 6e7f5b74e, Vitro on, HA 2026.11.0), `am start … --es open /history` (also `/logbook`,
`/config/dashboard`) logged `Routing non-dashboard URL to fallback engine: /history`, then
`Lockdown: refused page from a URL without a network origin`, and the screen showed the lockdown's "This address
isn't the Home Assistant address the panel started with" note. On a Pixel Tablet (GeckoView 159), a fallback route
had logged a full URL (`http://…/<dashboard>/camera`) and opened. Two readings fit: Vitro dropped the origin on its
way to the fallback, or nothing ever added one. #983 had just changed how the app writes Home Assistant's address
(default ports dropped), so a mismatch of `:80`/`:443` between the URL and the lockdown's origin was a third.

The log settled it (**read**, `core/.../MainActivity.kt`, `applyIntent`): `open` went to `page.load(it)` as it
came, and `VitroEngine.load` routes a URL that isn't a dashboard straight to `openInFallback`. The tablet's full URL
was a dashboard view Vitro hands to the fallback, which `buildViewUrl` writes on the page's origin, so it never took
this path. The lockdown
was right to refuse: `Origin.of("/history")` is null, and a page with no origin never matches the dashboard's. #983
isn't the cause: the refused URL had no port at all. Every other source of a URL already had an origin: a card's
`navigate` and `url` actions and an island's links (`navigateInPlace`), More Info's "open in Home Assistant"
(`buildViewUrl`), the core's notification views (absolute links, `NotifyRequest`), and Back (the URLs loaded before).

**What changed.** A path (`/history`, `logbook?entity_id=…`, `#top`) goes on the origin the dashboard loads from:
the test hold's while there is one, else `KioskPrefs.url`'s, which is what the lockdown started with
(`engineStartWanted`). The origin is `OAuth.origin`'s (lower case, default port dropped), the query and fragment are
kept, and a path in the dashboard's address is no prefix: Home Assistant serves its frontend at the root and has no
setting for a base path. A URL with a scheme stays as it is, and a scheme-relative `//host` opens nothing.

**Tests (verified 2026-11-28, unit tests only):** `FrontendUrlsResolveTest` (core), `VitroFallbackReturnTest`
(`open /history` with Vitro on: the fallback gets `http://192.0.2.10:8123/history`, also on `:80`, `:443` and
`:8443` dashboards, and from a card's `navigate`), `GeckoBehaviourPinTest` (the real `MainActivity` on GeckoView,
Vitro off) and `LockdownPolicyTest` (the resolved URL loads under the lockdown for each port form, the bare path
doesn't). **Untested** on the Mirror itself: the re-check is to run the same three `open`s there with Vitro on and
off.

## How to reproduce

Off-device: `./gradlew :engine-gecko-legacy:testDebugUnitTest` (the policy in Kotlin and in `policy.js`, the gate).

On an emulator or device (writes only the test APK, `sh.edm.s1kiosk.legacy`; undo: `adb uninstall`):

1. `./gradlew :kiosk-legacy:assembleDebug`, then `adb -s <serial> install` the APK from
   `engines/engine-gecko-legacy/test-app/build/outputs/apk/debug/`.
2. `python3 tools/legacy/lockdown-page.py`, and `adb -s <serial> reverse tcp:18125 tcp:18125` and the same for 18126.
3. `adb -s <serial> shell am start -n sh.edm.s1kiosk.legacy/dev.glassworks.legacycheck.EngineCheckActivity --es url
   http://127.0.0.1:18125/`. The page lists what must load and what must not. On an emulator, also run it with
   `--es url http://10.0.2.2:18125/` (after `am force-stop`): a host that isn't loopback, as a real HA's isn't,
   so the page is an allowlisted secure context, as in the app (step 15).
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
- The app itself on an Android 6-7 device: it starts and loads the dashboard on the Mirror (2026-11-17, #443 and
  #459). Not checked there yet: hiding HA's header, Assist, Sendspin, camera presence, and a reboot.
- The Mirror's `Build.MODEL` reads "MSM8916 for arm64" (the Glass Panes image's reference board), so the app tells a
  Mirror by its boot image's fingerprint instead (#482). Portrait comes from Kiln's first boot
  (`user_rotation=3`), not from a `GenericAndroid.KNOWN` row.
