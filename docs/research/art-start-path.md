---
topic: perf
evidence: verified
---

# ART and the start path: why the S1 interpreted and re-verified the app at every cold start

**Status (2026-11-28): working on an API 29 emulator, S1 run owed.** The app's classes now verify when Android
installs it (136 classes verified again at every start before, 0 on the start path now), and the release APK ships a
baseline profile of the start path that ProfileInstaller hands to Android. Android 10 compiles it at its next
background optimization, or at install when a `.dm` file comes with the APK. Neither has been measured on the S1 yet.

Device: API 29 `google_apis` arm64 emulator (Android 10, the S1's ART). Build: exp/vitro at 117d1026c, AGP 9.3.3,
Kotlin 2.4.20, androidx.profileinstaller 1.4.0. Tools: the emulator's own `dex2oat`, `oatdump`, `profman`,
`cmd package compile`, and `tools/perf/runtime-verify.sh`, `tools/perf/apk-dm.sh`.

## Question

A cold start of the S1 test build of exp/vitro (6e7f5b74e, Vitro on) took about 3.3 s from force-stop to the first full
dashboard frame, measured on 2026-11-28 with simpleperf and atrace over three starts. Almost all of it was serial
main-thread work:

- None of the main thread's samples were in ahead-of-time compiled app code. 16 % were in ART's interpreter, 12 % in
  class lookup, linking, verification and string interning. The JIT threads used 22 % of the app's CPU (2.2 s of CPU in
  the first 4 s). `dumpsys package` said `speed-profile`.
- ART verified 102 classes on every start: 274–297 ms of main thread, 200 ms of it inside `activityStart`. The largest
  were `MainActivity` (63–66 ms), `AppManage`, `VitroEngine$VitroPage` and `S1Panes`.
- `activityStart` took 646–730 ms (CPU-bound), `KioskService.onCreate` 465–523 ms, and each first frame 812–904 ms.

Why was nothing compiled, why did those classes verify at run time, and can the app fix both without root?

## How we got here

Two different things showed up in the trace, and they have different causes.

**Nothing compiled.** `speed-profile` only compiles what a profile lists. The status line doesn't say whether there was
a profile, and a profile-guided filter with an empty profile compiles nothing. So the first questions were whether the
APK ships a profile, and whether anything on the S1 ever compiles one. The release build has no R8 (`isMinifyEnabled =
false`) and the app had no `baseline-prof.txt`. ProfileInstaller was already in the APK, though, pulled in by
androidx.lifecycle (GeckoView's dependency), with a 381-byte profile from AndroidX's own libraries. So the machinery
was there with nothing of ours in it.

**Verification at run time on every start** usually means the class *soft-failed* verification when it was installed:
the verifier met something it couldn't resolve against Android 10's framework, gave up on that class without rejecting
it, and left it for the runtime. The textbook cause is a newer API behind an SDK check, inlined into a big class. The
code has those (`MainActivity.hideSystemUi` calls Android 11's `WindowInsetsController` behind
`Platform.hasWindowInsetsController`), but one guarded call in `MainActivity` doesn't explain `S1Panes`, `AppManage`
or `VitroPage`, which call nothing new.

Rather than read bytecode, we asked Android 10's own verifier. The emulator's `dex2oat` takes a pushed APK, and
`-verbose:verifier` logs every soft failure with its reason, while `oatdump` prints each class's status in the result.
That gave the whole list, with reasons, in three seconds. It turned out to be mostly something else:

1. **Kotlin's interface-default bridges.** Kotlin 2.x's default `-jvm-default=enable` gives every interface with a
   default body a static `access$<name>$jd` method that invoke-supers the interface itself, for code compiled against
   the old `DefaultImpls`. Android 10's verifier soft-fails each one: `Cannot invoke-super on self as interface`.
   That puts the interface (`Frame`, `Glassworks`, `AppParts`, `Pane`, `PaneDefinition`, `Engine`, `Page`, `PageHost`,
   `OverlayHost`, `HaDashboardClient.Listener`, …) at `RetryVerificationAtRuntime`. ART also marks a class for run-time
   verification when its superclass or one of its default-method interfaces is marked, so it spreads: `MainActivity`
   implements `PageHost` and `OverlayHost` through its helpers, `VitroPage` implements `Page`, `S1Panes` implements
   `AppParts`, `AqaraS1Plus` implements `Frame`, and so on.
2. **Newer APIs inside big classes**, as expected, in seven places. The worst is `PlatformApi`: every class asks
   `Platform` something at start, and four of its methods called Android 11, 13 and 14 APIs directly.

Both fixes are mechanical. The app's modules can drop the bridges, since nothing outside this repo compiles against
them. The libraries published to Maven keep them, because apps built against their `DefaultImpls` would break. Each
newer call moves into a small nested `object ApiNN` that only its query's branch reaches, so the class that fails
verification is a holder that never loads on Android 10.

For compiling, the open question was what actually happens on the S1. Updates install through the root update service
(`pm install -r -S <size>` from stdin, `frames/aqara-s1-plus/tools/root/glassworks_update.sh`), and test builds through
`adb install -r`. Neither passes a profile. Android 10's install-time `speed-profile` then compiles nothing, and the
profile ProfileInstaller writes at first start is only compiled by the background optimizer, a job that waits for an
idle, charging device. A wall panel whose screen is always on may rarely or never count as idle. The emulator answers
the mechanism; the S1 has to answer whether its background job runs.

## What we tried

1. **Which classes Android 10 leaves to run time** (2026-11-28, verified). The release APK of exp/vitro 117d1026c,
   signed with the debug key, on the API 29 emulator:
   ```
   $ adb push base.apk /data/local/tmp/base.apk
   $ adb shell dex2oat --dex-file=/data/local/tmp/base.apk --dex-location=/data/app/sh.edm.s1kiosk-1/base.apk \
       --oat-file=/data/local/tmp/out.odex --instruction-set=arm64 --compiler-filter=verify \
       --runtime-arg -verbose:verifier
   $ adb logcat -d -s dex2oat | grep -c "Soft verification failures in"
   599
   $ adb shell oatdump --oat-file=/data/local/tmp/out.odex --no-disassemble --class-filter=dev.glassworks \
       | grep -E '^[0-9]+: L' | awk '{print $NF, $(NF-1)}' | sort | uniq -c
   6356 (OatClassNoneCompiled) (Verified)
    136 (OatClassNoneCompiled) (RetryVerificationAtRuntime)
   ```
   The 136 include `MainActivity`, `GlassworksRuntime`, `KioskPrefs`, `AppManage`, `VitroEngine`,
   `VitroEngine$VitroPage`, `S1Panes`, `AqaraS1Plus`, `Platform`, `PlatformApi` and every pane's definition and pane.
   The reasons, from the same log:
   ```
   dev.glassworks.frame.Frame.access$getAgent$jd(...): [0x0] Cannot invoke-super on self as interface
   void dev.glassworks.MainActivity.hideSystemUi(): [0xD] couldn't find method android.view.Window.setDecorFitsSystemWindows (Z)V
   android.os.Bundle dev.glassworks.platform.PlatformApi.backgroundStartOptions(): [0xB] couldn't find method android.app.ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode (I)...
   java.lang.Integer dev.glassworks.platform.PlatformApi.lastExitReason(...): [0x3C] (possibly) illegal class access: ... -> 'Unresolved Reference: android.app.ApplicationExitInfo'
   ```
   The other direct calls: `PlatformApi.intentExtra` and `processExit`, `AdjustableAccess.onInitialize`
   (`setStateDescription`), `WifiLocalOnlyAp.network` (`SoftApConfiguration`), `UpdateManager.sessionParams`
   (`setRequireUserAction`) and `ImprovBleServer.pump` (Android 13's `notifyCharacteristicChanged`). Every other soft
   failure in our code was a `$jd` bridge. 102 of the 136 being loaded in a start matches the S1's trace.

2. **No compatibility bridges in the app's modules** (2026-11-28, verified). `build-logic/glassworks.android` sets
   `jvmDefault = NO_COMPATIBILITY` on every Kotlin compile of a module that doesn't apply the Maven publishing plugin.
   The seven direct calls moved into nested `ApiNN` objects in the same files, so `api23-baseline.txt`'s keys don't
   change. Rebuilt and verified again the same way (with `tools/perf/runtime-verify.sh`, below):
   ```
   runtime-verify: API 29, 6464 of the app's classes, 8 left to run-time verification
     dev/glassworks/MainActivity$Api30
     dev/glassworks/platform/PlatformApi$Api34
     dev/glassworks/engine/vitro/ui/AdjustableAccess$Api30
     dev/glassworks/improv/ImprovBleServer$Api33
     dev/glassworks/platform/PlatformApi$Api30
     dev/glassworks/platform/PlatformApi$Api33
     dev/glassworks/platform/WifiLocalOnlyAp$Api30
     dev/glassworks/update/UpdateManager$Companion$Api31
   ```
   The eight left are the holders, each reached only on the Android that has its API. Dropping `DefaultImpls` also
   removed 34 classes from the dex.

3. **What installd's own compile does** (2026-11-28, verified). On an install, the emulator gave the state the S1
   showed:
   ```
   $ adb install -r app.apk
   $ adb shell dumpsys package dexopt | grep -A2 '\[sh.edm.s1kiosk\]'
         arm64: [status=speed-profile] [reason=install]
   $ adb shell ls -la /data/misc/profiles/cur/0/sh.edm.s1kiosk/
   -rw------- 1 u0_a144 u0_a144    0 ... primary.prof
   (oat/arm64: base.odex 510,616 bytes, base.art 86,016 bytes: verified, nothing compiled)
   ```
   installd's dex2oat line (`--compiler-filter=speed-profile --profile-file-fd=… --class-loader-context=PCL[]`, with
   `-Xbootclasspath` the public framework) makes hidden methods unresolvable that a plain `dex2oat` on the device
   resolves. `WifiProvisioner`'s Android 11 `setSecurityParams` is hidden on Android 10, so its holder
   `WifiProvisioner$Api30` is marked for run time there and nowhere else. That's why `StartPathVerifyTest` checks calls
   against the SDK's API levels rather than what a device happens to resolve.

4. **The baseline profile, merged** (2026-11-28, verified). Each module's `src/main/baseline-prof.txt` (below) ends up
   in `build/intermediates/merged_art_profile/release/…/baseline-prof.txt`, and the APK's
   `assets/dexopt/baseline.prof` grows from 381 to 8,684 bytes (the format stores method indices as compressed deltas).
   `profman` reads it against the APK:
   ```
   $ adb shell profman --dump-only --profile-file=baseline.prof --apk=base.apk --dex-location=base.apk
   classes.dex  hot methods 5585   classes 1100
   classes2.dex hot methods 25569  classes 4508
   classes3.dex hot methods 7003   classes 1437
   ```

5. **ProfileInstaller, then a compile** (2026-11-28, verified). The broadcast Macrobenchmark uses stands in for the
   first start (the startup initializer does the same 1–2 s after the first frame; read from ProfileInstaller 1.4.0):
   ```
   $ adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
       -n sh.edm.s1kiosk/androidx.profileinstaller.ProfileInstallReceiver
   D ProfileInstaller: Installing profile for sh.edm.s1kiosk
   D ProfileInstaller: RESULT_INSTALL_SUCCESS
   (cur/0/sh.edm.s1kiosk/primary.prof: 8,696 bytes)
   $ adb shell cmd package compile -m speed-profile sh.edm.s1kiosk
   I dex2oat : dex2oat took 2.722s (5.611s cpu) (threads: 4)
   (oat/arm64: base.odex 18,093,816 bytes, base.art 2,134,016 bytes)
   ```
   After it, `oatdump` shows the start path compiled and initialized in the app image: `S1Panes` and
   `VitroEngine$VitroPage` `(Initialized) (OatClassAllCompiled)`, `MainActivity`, `KioskService`, `AppManage`,
   `Platform`, `HaDashboardClient` and `EntitiesCardView` `(SuperclassValidated) (OatClassSomeCompiled)`. The packages
   left out (settings, Clarity's harness, ONVIF) are `OatClassNoneCompiled`. That's 2.7 s with four fast cores; the
   A133 will take far longer, which matters only for the background job or an install's wait.

6. **A `.dm` beside the APK** (2026-11-28, verified). A dex metadata file is a zip holding the profile as
   `primary.prof`; AGP's `baseline.prof` (version `010`, what Android 9 to 11 read) works as is, since installd's
   profman rekeys it to the installed APK by checksum. `tools/perf/apk-dm.sh` writes one from an APK:
   ```
   $ tools/perf/apk-dm.sh app.apk
   apk-dm: wrote app.dm (8684 byte profile)
   $ adb install-multiple app.apk app.dm
   $ adb shell dumpsys package dexopt | grep -A2 '\[sh.edm.s1kiosk\]'
         arm64: [status=speed-profile] [reason=install-dm]
   (oat/arm64: base.odex 18,093,816 bytes, base.art 2,134,016 bytes, at install)
   ```
   That compiles the start path during the install, with no first start and no background job.

## Result

| | Before | After (API 29 emulator) |
|---|---|---|
| Glassworks classes left to run-time verification | 136 (102 loaded in an S1 start) | 8 `ApiNN` holders, none on Android 10's path |
| Start path in the baseline profile | none (381-byte AndroidX profile) | 38,157 methods, 7,045 classes |
| Compiled after `adb install -r` / `pm install` | nothing | nothing (the same: no profile at install) |
| After the first start (ProfileInstaller) | AndroidX's few methods queued | the start path queued for the next background optimization |
| With a `.dm` at install | – | the start path compiled at install (`install-dm`) |

How a panel gets the compiled code:

| Install path | Profile at install | Compiled when | In this change |
|---|---|---|---|
| S1 in-app update (root `pm install`) | no | the background optimizer, once ProfileInstaller wrote the profile | yes, app only |
| `adb install -r` (test builds, the CLI) | no | the same | yes |
| `adb install-multiple -r app.apk app.dm` | yes | at install | `tools/perf/apk-dm.sh` |
| Root: `cmd package compile -m speed-profile sh.edm.s1kiosk` after the first start | – | right away | documented only: a root-side change needs a panel |
| Root update service with a `.dm` (`pm install-create`/`install-write`/`install-commit`) | yes | at install | documented only, same reason |
| PackageInstaller fallback (generic Android, an S1 without the update service) | possible on Android 9–11 | at install | not done: changes the update path |

What keeps it that way:

- `StartPathVerifyTest` (the S1 app's unit tests) reads every compiled class the APK ships and the SDK's
  `api-versions.xml`. It fails on a call, field or type above API 29 outside an `ApiNN` holder, and on any `$jd` bridge.
- `tools/perf/runtime-verify.sh` asks a real Android 10 the same question on a built APK.
- The baseline profile is per module, by package: each `baseline-prof.txt` lists the packages of that module that run
  at every start, and leaves out those that only run when someone opens them (settings, setup screens, Improv
  provisioning, the ONVIF server, the radio's views, Clarity's harness, GeckoView's media). A new package on the start
  path needs a line there; one that isn't listed is still JIT-compiled and picked up by the background optimizer's own
  profile, as before.

**What we expect on the S1** (untested): the 274–297 ms of run-time verification should go, 200 ms of it from
`activityStart`, as soon as the build is installed. Once the start path is compiled, the interpreter's 16 % and most of
the JIT threads' 2.2 s of CPU should go too, and the app image should cut class lookup and linking (12 %). Our guess is
a further 0.5–1 s off the 3.3 s, but the S1's main thread also waits on Gecko and on HA, so only the run says how much.

## How to reproduce

1. Build the release APK: `./gradlew --max-workers=2 :kiosk:assembleRelease` (writes build outputs only).
2. Start an API 29 `google_apis` emulator and run `tools/perf/runtime-verify.sh <apk> <serial>`. It pushes a copy to
   `/data/local/tmp` and deletes it after, installs nothing, refuses a real device, and uses its own adb server with
   USB and mDNS off.
3. For the compile: `adb install -r` the signed APK on the emulator, send the ProfileInstaller broadcast above, then
   `adb shell cmd package compile -m speed-profile sh.edm.s1kiosk`; or install with a `.dm` from
   `tools/perf/apk-dm.sh`. Undo: `adb uninstall sh.edm.s1kiosk` on the emulator.

### The S1 run this change needs

On the shared dev panel, following AGENTS.md's rule 7: note the installed version and radio mode first, and put them back.

1. Install the test build with `adb install -r` (keeps the app's data). Do three cold starts (force-stop, start) with
   simpleperf and atrace as on 2026-11-28. Expected: no `VerifyClass` for `dev.glassworks` classes on the main thread,
   and `activityStart` about 200 ms shorter. Nothing is compiled yet.
2. After the first start, check that ProfileInstaller wrote the profile: `ls -la /data/misc/profiles/cur/0/sh.edm.s1kiosk/`
   (about 8.7 KB) and `logcat -s ProfileInstaller`.
3. Compile, timing it: `adb shell cmd package compile -m speed-profile sh.edm.s1kiosk` (root), or install again with
   `adb install-multiple -r app.apk app.dm`. Record the compile or install time, `dumpsys package dexopt`, and the sizes
   of `oat/arm/base.odex` and `base.art`.
4. Three more cold starts, on Vitro and on GeckoView: the first full frame, `activityStart`, `KioskService.onCreate`, the
   first frames, the interpreter's share and the JIT threads' CPU. Also the app's PSS after start: the app image adds
   about 2 MB.
5. Whether the S1 ever compiles on its own: `dumpsys jobscheduler | grep -A20 BackgroundDexOptService` (are idle and
   charging ever satisfied?) and, after a night, `dumpsys package dexopt` (`reason=bg-dexopt`). If it never runs, the
   root update service should install a `.dm` or compile after an update. That's a root-side change, with its own
   panel run.

## Open questions

- Does the S1's background optimizer ever run (idle and charging on an always-on panel without a battery)? That decides
  whether ProfileInstaller alone is enough there, or the root side must compile.
- How long a `speed-profile` compile of 38,000 methods takes on the A133, and the 32-bit sizes (Thumb-2 code is smaller
  than the emulator's arm64).
- In the first compile after an install (reusing the install's vdex), `improv/RefreshRetry` came out
  `RetryVerificationAtRuntime`; a forced compile (`-f`) and every manual `dex2oat` verified it. It's off the start
  path. Watch for it in the S1's trace.
- Android 12 and newer read a newer profile format, so `apk-dm.sh`'s `.dm` is for Android 9 to 11. ProfileInstaller
  covers the rest.
