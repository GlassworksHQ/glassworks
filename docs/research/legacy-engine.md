# Legacy engine: does GeckoView 144 run on Android 6–7, locked to Home Assistant?

**Status (2026-11-15): partly working.** GeckoView 144.0.2 (arm64-v8a) starts on API 23 and API 25 emulators.
It draws Home Assistant's real frontend and runs the S1 app's helper extension (`window.s1kiosk`). The lockdown
refuses everything outside the dashboard's origin, with a note on the page. On the emulators, parts of the page
sometimes draw as black 1024×512 tiles. The S1 app's own screens still call API 24+ and crash on Android 6. Not
yet run on a lululemon Mirror: the plan is in "How to reproduce".

Devices: Android emulator, `system-images;android-23;default;arm64-v8a` (Android 6.0) and
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

2. **APK size** (2026-11-15, verified: `curl -sI` on maven.mozilla.org, then `unzip -lv` on the APK).

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

6. **Memory on API 23** (2026-11-15, verified, `ps`, RSS) with the dashboard open: main process 271 MB,
   `:tab7` (the one content process) 277 MB, `:gpu` 129 MB, `:crashhelper` 61 MB. The legacy engine sets
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

## Result

| | GeckoView 144 on API 23–25 |
|---|---|
| Starts, draws a page | Yes, on both emulators |
| HA's frontend, sign-in and a dashboard | Yes on API 23; black tiles on the emulator; API 25 run unfinished |
| S1 helper extension (`content.js`, native port, `window.s1kiosk`) | Works unchanged |
| API gaps against 159 for what the engine uses | None (same source compiles against both) |
| Size per ABI | 6.6–6.8 MB smaller AAR than 159 |
| Crashes | None from GeckoView. The S1 app's MainActivity: API 24 call |

The lockdown, verified on both emulators:

| Load | How it is stopped | What the page shows |
|---|---|---|
| Top-level page from another origin (link, redirect, `location`) | `onLoadRequest` → deny | Note: "<host> isn't your Home Assistant, so it didn't open." |
| `window.open`, `target=_blank` | `onLoadRequest` (new window) → deny; `onNewSession` refuses too | The same note |
| Download | `onExternalResponse` → nothing saved | "Downloads are off on this panel." |
| Cross-origin iframe, web card | `onSubframeLoadRequest` → deny; the extension would show `blocked.html` in its place | Empty frame, note "Blocked content from <host>." |
| Image, map tile, fetch, XHR, WebSocket, script from another origin | Lockdown extension, `webRequest` blocking | The same note |
| The first load, before the extension is up | Waits (`ReadyGate`) | Nothing until the lockdown is on |

GeckoView's `ContentBlocking` can't do this part: it blocks by tracker lists and anti-tracking categories, not by
an origin we choose. So the per-request blocking is a built-in web extension (`assets/lockdown`), the same way the
S1 helper is built in.

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

- Black tiles: the emulator's GLES, or GeckoView 144 on Android 6? The Mirror run answers it.
- Memory on the Mirror's ~930 MB, with the dashboard open for a while, and whether the low-memory killer takes
  the content process.
- The extensions process: GeckoView runs extensions out of process; `extensions.webextensions.remote=false`
  would save one process. Not tried.
- What the lockdown blocks that HA itself uses: brand icons from `brands.home-assistant.io`, a dashboard URL that
  redirects to another origin (`http` → `https`, a reverse proxy), and HA's external sign-in providers. Each is
  refused today and says so on the page.
- GeckoView 143 wasn't needed: 144 didn't fail on either emulator. If it fails on the Mirror, 143 is the fallback.
- The app itself on API 23–25: the API 24+ calls behind `registerDefaultNetworkCallback`.
