<p align="center">
  <a href="https://www.glassworks.dev/frames/generic-android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-frame-generic-android-on-dark.svg">
      <img alt="Generic Android, a Glassworks frame" src="https://www.glassworks.dev/brand/banner-frame-generic-android-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Generic Android</h1>

<p align="center">
  <b>Any Android device, as an app. No root.</b><br>
  Core plus the panes that need no special hardware.<br>
  Tablets, old phones, other Android panels.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-planned-on-dark.svg">
    <img alt="Status: planned" src="https://www.glassworks.dev/brand/badge-status-planned-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://www.glassworks.dev/frames/generic-android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/frames/generic-android/clarity-on-dark.svg">
      <img alt="Clarity" src="https://www.glassworks.dev/frames/generic-android/clarity-on-light.svg" height="30">
    </picture>
  </a>
</p>

A Glassworks **frame**: the device definition for the Generic Android. Its data is in [`design/frame.json`](design/frame.json) (format: [frames/README.md](../README.md)), and [glassworks.dev/frames/generic-android](https://www.glassworks.dev/frames/generic-android) shows it.

## Status

**Status: planned.** The frame has its own app, **Glassworks** (`dev.glassworks.generic_android`, built from
[`android/`](android)), for Android 8.0 or newer (`minSdk` 26; Android 6 and 7 get the separate legacy build,
#198). It hasn't run on a device yet. S1 Kiosk also runs as this frame on any device that isn't an Aqara S1
(`Build.HARDWARE` other than `sun50iw10p1`). Either app names itself after the device and runs the panes the
device's features allow.

The app is the Glassworks core with a fixed set of panes: Assist (with its timers), Sendspin and the camera
(#117). A pane the device has no hardware for (no microphone, no speaker, no camera) is hidden: it isn't in the
settings, on the Manage page, in setup or in Home Assistant. The camera pane is off until someone turns it on, as
in S1 Kiosk; then it wakes the screen when someone walks up, on a device without a proximity sensor of its own.
On a screen without touch (the lululemon Mirror) its setup step never waits: the use stays Presence only, and
Kiln grants the camera. On a camera without 1280x720 (Android 6-7's) its stream runs at a size the camera lists, or
stays off with the reason on the Camera page; presence works either way. The screen follows Android's standard proximity sensor (near means under
5 cm, so a hand over it, not a person across the room) and the light sensor for brightness. Many tablets have
no proximity sensor; then the screen goes off after the idle time and wakes on a touch.

| | S1 Kiosk on a tablet | Glassworks for generic Android |
|---|---|---|
| applicationId | `sh.edm.s1kiosk` | `dev.glassworks.generic_android` |
| Home Assistant's model | "Pixel Tablet (S1 Kiosk)" | "Pixel Tablet (Glassworks)" |
| Panes | All of S1 Kiosk's the device can run | Assist, Timers, Sendspin, Camera |
| Presence and light | None | Android's proximity and light sensors |

Settings that S1 Kiosk takes as intent extras (`--es url …`, `--es api_key …`, the panes' switches) reach
this app only from adb: `adb shell am start -n dev.glassworks.generic_android/sh.edm.s1kiosk.MainActivity --es …`.
Starting that activity takes `WRITE_SECURE_SETTINGS`, which adb's shell has and other apps can't get. The
launcher and the home screen use an alias whose extras the app drops, so another app on the device can't
change a setting.

The two are separate apps with their own data and keys, so both can be installed on one device. Only one
can run at a time: both serve Home Assistant on port 6053 and the Manage page on 8099. Stop the other
first (`adb shell am force-stop <id>`).

### The legacy build (Android 6 and 7)

Android 6 and 7 (API 23-25) get a legacy build of this app (#198): GeckoView 144, locked to the panel's Home
Assistant ([legacy-engine.md](../../docs/research/legacy-engine.md)). It is a separate app,
`dev.glassworks.generic_android.legacy`, with its own release line, so neither build ever installs over or
updates the other. The same APK is meant for older tablets and the lululemon Mirror, where Kiln's Mirror recipe flashes it into
`/system/priv-app` under this id (#411).

It is the `legacy` build type of [`android/`](android): the same sources, manifest and key as the release build,
with its own id, GeckoView 144 (`:engine-gecko-legacy`), minSdk 23 and core library desugaring. A build type, not
a product flavor, so the release build keeps its tasks (a flavor would rename them and add `BuildConfig.FLAVOR`).
Desugaring is the module's, though (AGP has no per-variant switch), so it changes the release APK too: no desugared
library code goes in at minSdk 26, but D8 adds a small dex of inert API stubs, and the APK isn't byte-identical to
one built without it. Desugaring puts `desugar_jdk_libs` code (GPL-2.0 with the Classpath exception) in the legacy
APK only, which the maintainer allowed on 2026-11-16 (`AGENTS.md`, rule 4; `THIRD-PARTY-NOTICES.md`). Its GeckoView follows `-PlegacyAbi`, and the build fails unless that is the APK's `-Ps1Abi`:

```sh
./gradlew :generic-android:assembleLegacy -Ps1Abi=arm64-v8a -PlegacyAbi=arm64-v8a
# → frames/generic-android/android/build/outputs/apk/legacy/generic-android-legacy-unsigned.apk
```

Status: it builds (CI builds and checks it on every change, and test builds publish it), and CI's conformance test
starts the core's `MainActivity` on an API 23 emulator (core-emulators.yml). It hasn't run on a device yet, and there is no
release. Try it first where a crash loop is cheap: as the home app, a crash at start would repeat at every boot.
What waits: a first device run and a release job: `release.yml` doesn't build
the generic app or its legacy build yet. The release key exists ([Signing](#signing)).

The app reads two things from the device, so the Mirror needs no frame code of its own (#198):

- **Orientation:** the device's rotation: Android's auto-rotate, or the way the person locked the screen. The Mirror
  stands portrait because Kiln's first boot locks it there (`user_rotation=3`, auto-rotate off). A device with a frame of its own
  stands as its `frame.json`'s `display` says: the Pixel Tablet is landscape, though its screen is portrait at
  rotation 0. The app knows it by `Build.MODEL`, as Kiln's [`devices.ts`](installer/src/devices.ts) does
  (`GenericAndroid.KNOWN`; `GenericAndroidKnownTest` keeps the two the same).
- **Touch:** with input devices listed and no touchscreen among them (the Mirror), setup works from a computer
  instead of the screen (#34).
Neither build can go on Google Play as it is: each updates itself from the repo's releases, and Play updates
the apps it installs itself. A Play build would need a variant without the self-updater (#403). Play wouldn't grant
the system permissions both builds ask for (`INSTALL_PACKAGES`, `REBOOT`, `WRITE_SECURE_SETTINGS`, for the Mirror)
either.
Why one legacy app and not a Mirror app of its own: [The legacy build's id](#the-legacy-builds-id).

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| Radio | None |
| Presence | Whatever sensors the device has |
| Audio | Whatever the device has |
| Root | Not needed |

## Install

It installs as a normal app (an APK). There is no release yet, so Kiln installs it only as a test: open
`kiln.glassworks.dev/android?apk=local` (or `/android?apk=local` on `npm run dev -w @glassworks/kiln-app`) in Chrome
or Edge, pick a test build's APK, plug the tablet in with USB debugging still off, and follow the screens. Kiln names
the tablet or asks for its brand, shows that brand's steps to turn on USB debugging, waits for Allow on the tablet,
then installs. Undo is on the Done screen, and on the first screen for a later visit.

Kiln's recipe for it is in [`installer/`](installer) (`@glassworks/generic-android-installer`, #403). Given an adb connection and an APK, it reads the device, refuses a device with a frame of its own (the S1, by
`ro.hardware`: Kiln installs it from its own page), below Android 6 or the wrong build,
installs or updates in place, grants what adb can of what the APK asks for (the microphone, secure settings, the overlay, the device
admin, the camera) and reads each grant back, allows "Install unknown apps" on Android 12+
so updates install by themselves ([Silent self-update](#silent-self-update-android-12)), makes it the home screen (`cmd package set-home-activity` on Android 7+, else
Android's chooser), and hands over to setup over the LAN. Its table of brands maps a USB vendor ID, or adb's
maker and model, to the brand's steps to turn on USB debugging, each with its source. Tested against a fake
device only; how it works per Android version is in [research/kiln-install.md](research/kiln-install.md).

To try a branch, a test build's artifact `glassworks-generic-test-<commit>` holds its APKs
([generic-test-build.yml](../../.github/workflows/generic-test-build.yml)), the legacy build's
(`glassworks-generic-legacy-test-<version>-arm64-v8a-<commit>.apk`) among them.

## Updates

App updates come from Home Assistant's update entity, as on the S1
([docs/esphome.md](../../docs/esphome.md#app-updates)). Each app takes only its own releases from the
repo's GitHub releases (`ReleaseLine` in `core/.../update/Releases.kt`):

| App | Release tag | APK, per ABI, with its `.sha256` |
|---|---|---|
| Glassworks for generic Android | `generic-android-v1.2.3` | `glassworks-generic-android-arm64-v8a-1.2.3.apk`, `glassworks-generic-android-armeabi-v7a-1.2.3.apk` |
| Its legacy build (Android 6-7) | `generic-android-legacy-v1.2.3` | `glassworks-generic-android-legacy-arm64-v8a-1.2.3.apk`, `…-armeabi-v7a-…` |
| S1 Kiosk | `aqara-s1-plus-v1.2.3` or `v1.2.3` | `s1-kiosk-1.2.3.apk`, `s1-kiosk-arm64-v8a-1.2.3.apk` |

So no app ever offers itself another's APK.

The check reads the repo's 100 newest releases (`Releases.LIST_URL`, `per_page=100`, GitHub's largest page),
all apps and libraries together, and takes the newest of its own among them. So if more than 100 releases of
other parts come out after this app's newest one, the app sees no update until its next release: every app
should release at least once in any 100 of the repo's releases. A full page is about 1 MB (7 kB a release on
2026-11-16); the check reads up to 4 MB. One page keeps each check (after the app starts, then every 24 hours) to one request
against GitHub's limit of 60 an hour without a token. The release workflow doesn't build the generic app
yet, and there has been no release of it. Its first release needs its key ([Signing](#signing)) and
`release.yml` steps for the names above.

The commands below use the generic app's id, `dev.glassworks.generic_android`. For S1 Kiosk on a
tablet, use `sh.edm.s1kiosk`.

There is no root update service here, so the app installs the new APK through Android's
PackageInstaller. Two routes install it without anyone touching the screen:

| Route | Android | What you set up once |
|---|---|---|
| Silent self-update | 12 or newer | Allow "Install unknown apps" for the app |
| Device owner | 8.0 or newer | `dpm set-device-owner` over adb, on a device with no accounts |
| Privileged system app | 6.0 or newer | Nothing: Kiln flashes the app into `/system/priv-app` (the Mirror), where Android grants `INSTALL_PACKAGES` |

Without one of them, Android asks for a tap on the screen before it installs. The device's page in
the panel's settings and on the Manage page, and the *Silent updates* sensor in Home Assistant, say
whether updates install silently, and how to change it
([esphome.md](../../docs/esphome.md#silent-updates)). The *Update status* sensor says which one happens: "Installing 1.2.3 without a tap", or "Installing 1.2.3: waiting for a tap on
the screen".

### Silent self-update (Android 12+)

The app asks Android to install its own update with no user action (#277). Android allows it when:

1. "Install unknown apps" is allowed for the app: *Settings → Apps → Glassworks → Install unknown
   apps*, or `adb shell appops set dev.glassworks.generic_android REQUEST_INSTALL_PACKAGES allow`.
   Kiln's recipe does this by default on Android 12+ and reads it back; the person can say no
   (`silentUpdates: false`), and `undoSilentUpdates` sets it back to Android's default (#422).
2. The app that runs the install has this feature. An app from before it asks for a tap once, and
   the version it installs updates silently from then on.
3. The new APK targets a recent enough API for the device's Android
   ([`setRequireUserAction`](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)),
   read 2026-11-14):

| Device runs | The new APK must target at least |
|---|---|
| Android 12 (API 31) | 29 |
| Android 13 (33) | 30 |
| Android 14 (34) | 31 |
| Android 15 (35) | 33 |
| Android 16 (36) | 34 |
| Android 17 (37) | 35 |

The app targets 35, so every Android up to 17 qualifies. Android raises the bar by one level each
year, so the target goes up with each new Android
([releasing.md](../../docs/releasing.md#raising-targetsdk)).

Status: unit-tested only (2026-11-15). The first run on a real device (the Pixel Tablet, Android 17)
waits for a release that has the feature.

### Device owner

A device-owner app installs its updates silently on any Android version it runs on. It also lets the
app do more on the device (Wi-Fi, permissions), and Settings shows "managed by your organization".
Android allows it only on a device with no accounts and a single user: remove the Google account
first, or set it up right after a factory reset.

```sh
adb shell dpm set-device-owner dev.glassworks.generic_android/sh.edm.s1kiosk.AdminReceiver
adb shell dumpsys device_policy | grep -A3 "Device Owner"
# undo (`dpm remove-active-admin` doesn't work for a release build):
adb shell am start -n dev.glassworks.generic_android/sh.edm.s1kiosk.improv.ImprovActivity --ez release_device_owner true
```

While it is device owner, the app can't be uninstalled until you undo it. The details are in
[docs/onboarding.md](../../docs/onboarding.md#device-owner-optional). Silent installs as device owner
are Android's documented behaviour (Android 6.0 and later); they haven't been tried on a generic
device yet.

### The legacy build's id

The legacy build is `dev.glassworks.generic_android.legacy` on every Android 6-7 device, the Mirror included.
The choice is permanent for a Mirror: the app in `/system` keeps its id, and an update gets only the privileged
permissions that copy asked for. What was weighed (2026-11-15):

| | One legacy app (chosen) | A Mirror app of its own |
|---|---|---|
| Release lines | Two: current and legacy | Three: current, legacy, Mirror, with the same engine and floor |
| A fix for Android 6-7 | One release | Two releases of the same code, which can drift apart |
| Mirror-only behaviour (portrait, no touchscreen, camera presence) | To be chosen on the device, as for any device (not built yet): the screen's size, its input, its sensors, the permissions it holds | Built in |
| Kiln's Mirror recipe | Checks for the legacy id | Checks for the Mirror id, so a tablet build can't be flashed |
| What the Mirror build must keep | Every legacy build asks for `INSTALL_PACKAGES`, `REBOOT` and `WRITE_SECURE_SETTINGS` and keeps MAIN, HOME and DEFAULT in one filter (`GenericAppTest`) | The same, in its own build |

A tablet build on the Mirror would be the same APK, so the second app's guard buys nothing. Kiln's Mirror
recipe takes `sh.edm.s1kiosk` today; it moves to the legacy id with the build.

## Signing

The app has its own release key, never S1 Kiosk's: Android updates an app only with an APK signed by the same
key, so this key is what lets every later version install in place over the one before. The build reads it
from the environment only, as S1 Kiosk's reads its `S1_*` variables:

| Variable | What |
|---|---|
| `GENERIC_KEYSTORE_FILE` | The keystore (`.jks`) |
| `GENERIC_KEYSTORE_PASSWORD`, `GENERIC_KEY_ALIAS`, `GENERIC_KEY_PASSWORD` | Its password, the key's alias and the key's password |
| `GENERIC_REQUIRE_RELEASE_SIGNING=true` | Fail the build when no key is set, instead of an unsigned APK |

None set: the release APK is unsigned. Some but not all set: the build fails.

The key exists since 2026-11-17: certificate `CN=Glassworks, O=GlassworksHQ` (RSA 4096, valid to 2054). One key
signs both builds, `dev.glassworks.generic_android` and `.legacy`. It lives in the `release-signing` environment as
`GENERIC_KEYSTORE_B64` (the PKCS12 keystore, base64), `GENERIC_KEYSTORE_PASSWORD` and `GENERIC_KEY_PASSWORD`
(the alias, `glassworks`, isn't secret and sits in the workflow), and in the maintainer's password manager. It is never in the repo, and it never changes.
The keystore is PKCS12, which has one password: `GENERIC_KEYSTORE_PASSWORD` and `GENERIC_KEY_PASSWORD` must be the
same value, or signing fails with "keystore password was incorrect". The certificate's SHA-256 is
`564d1367c4eafeac81e80b5b51d4db090a5df9b615d5aa46a83c4c33d5c67caa`.

Test builds sign with it too ([generic-test-build.yml](../../.github/workflows/generic-test-build.yml)), so each one
updates the last in place, settings kept. CI checks every APK's certificate SHA-256 against the one above, so a new
key, even with the same name, fails the build. Builds from before 2026-11-17 were signed with a key made for their
own run: uninstall one before installing a newer build (once; that wipes the app's settings).

## Panes

| Pane | Support | Note |
|---|---|---|
| Assist | Depends on the device | Needs a microphone. |
| Timers | Depends on the device | Needs Assist. |
| Sendspin | Depends on the device | Needs a speaker. |
| Zigbee and Thread radio | No | No supported radio. |
| Time zone | No | Worked out: only on the S1 (`aqara-s1-plus`). |
| Diagnostics | Planned | Worked out: needs no special hardware. |
| Bluetooth proxy | Depends on the device | Worked out: needs Bluetooth LE. |
| Notifications | Planned | Worked out: needs no special hardware. |
| Camera | Depends on the device | Worked out: needs a camera. |
| Wall base | No | S1 only. |

"Worked out" means `frame.json` doesn't rate the pane; its support comes from what the pane needs and what the frame has ([how](../README.md#the-panes-a-frame-runs)).

## Tests

The app's unit tests are `GenericAppTest` (`:generic-android`). The core it builds on runs on emulators at
API 26, 29 and 34 in CI: `CoreConformanceTest` boots the core with a fake frame
([core-emulators.yml](../../.github/workflows/core-emulators.yml)).

## Research notes

- [kiln-install.md](research/kiln-install.md): which adb commands work on which Android, and how Kiln tells a
  device's brand before USB debugging is on (#403)

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model
- [`android/`](android): the app (`:generic-android`), Glassworks for generic Android
- [`installer/`](installer): Kiln's recipe, headless for now (#403)
- [`budgets.json`](budgets.json): the app's size budget, per ABI and per pane ([docs/size.md](../../docs/size.md#budgets)); its RAM budgets are still to set
