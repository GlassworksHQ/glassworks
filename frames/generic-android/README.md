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

The app is the Glassworks core with a fixed base set of panes: Assist (with its timers) and Sendspin. A pane
the device has no hardware for (no microphone, no speaker) is hidden: it isn't in the settings, on the Manage
page, in setup or in Home Assistant. The screen follows Android's standard proximity sensor (near means under
5 cm, so a hand over it, not a person across the room) and the light sensor for brightness. Many tablets have
no proximity sensor; then the screen goes off after the idle time and wakes on a touch.

| | S1 Kiosk on a tablet | Glassworks for generic Android |
|---|---|---|
| applicationId | `sh.edm.s1kiosk` | `dev.glassworks.generic_android` |
| Home Assistant's model | "Pixel Tablet (S1 Kiosk)" | "Pixel Tablet (Glassworks)" |
| Panes | All of S1 Kiosk's the device can run | Assist, Timers, Sendspin |
| Presence and light | None | Android's proximity and light sensors |

Settings that S1 Kiosk takes as intent extras (`--es url …`, `--es api_key …`, the panes' switches) reach
this app only from adb: `adb shell am start -n dev.glassworks.generic_android/sh.edm.s1kiosk.MainActivity --es …`.
Starting that activity takes `WRITE_SECURE_SETTINGS`, which adb's shell has and other apps can't get. The
launcher and the home screen use an alias whose extras the app drops, so another app on the device can't
change a setting.

The two are separate apps with their own data and keys, so both can be installed on one device. Only one
can run at a time: both serve Home Assistant on port 6053 and the Manage page on 8099. Stop the other
first (`adb shell am force-stop <id>`).

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| Radio | None |
| Presence | Whatever sensors the device has |
| Audio | Whatever the device has |
| Root | Not needed |

## Install

It installs as a normal app (an APK). It doesn't install from Kiln yet, and there is no release yet. To try a
branch, a test build's artifact `glassworks-generic-test-<commit>` holds its APKs
([generic-test-build.yml](../../.github/workflows/generic-test-build.yml)).

## Updates

App updates come from Home Assistant's update entity, as on the S1
([docs/esphome.md](../../docs/esphome.md#app-updates)). Each app takes only its own releases from the
repo's GitHub releases (`ReleaseLine` in `core/.../update/Releases.kt`):

| App | Release tag | APK, per ABI, with its `.sha256` |
|---|---|---|
| Glassworks for generic Android | `generic-android-v1.2.3` | `glassworks-generic-android-arm64-v8a-1.2.3.apk`, `glassworks-generic-android-armeabi-v7a-1.2.3.apk` |
| S1 Kiosk | `aqara-s1-plus-v1.2.3` or `v1.2.3` | `s1-kiosk-1.2.3.apk`, `s1-kiosk-arm64-v8a-1.2.3.apk` |

So neither app ever offers itself the other's APK.

The check reads the repo's 30 newest releases (`Releases.LIST_URL`, `per_page=30`), all apps and libraries
together, and takes the newest of its own among them. So if more than 30 releases of other parts come
out after this app's newest one, the app sees no update until its next release: every app should release
at least once in any 30 of the repo's releases. One page keeps each check (after the app starts, then every 24 hours) to one request
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

Without either, Android asks for a tap on the screen before it installs. The *Update status* sensor
says which one happens: "Installing 1.2.3 without a tap", or "Installing 1.2.3: waiting for a tap on
the screen".

### Silent self-update (Android 12+)

The app asks Android to install its own update with no user action (#277). Android allows it when:

1. "Install unknown apps" is allowed for the app: *Settings → Apps → Glassworks → Install unknown
   apps*, or `adb shell appops set dev.glassworks.generic_android REQUEST_INSTALL_PACKAGES allow`.
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

## Signing

The app has its own release key, never S1 Kiosk's: Android updates an app only with an APK signed by the same
key, so this key is what lets every later version install in place over the one before. The build reads it
from the environment only, as S1 Kiosk's reads its `S1_*` variables:

| Variable | What |
|---|---|
| `GENERIC_KEYSTORE_FILE` | The keystore (`.jks`) |
| `GENERIC_KEYSTORE_PASSWORD`, `GENERIC_KEY_ALIAS`, `GENERIC_KEY_PASSWORD` | Its password, the key's alias and the key's password |
| `GENERIC_REQUIRE_RELEASE_SIGNING=true` | Fail the build when no key is set, instead of an unsigned APK |

None set: the release APK is unsigned. Some but not all set: the build fails. No release key exists yet; the
maintainer makes it before the first release, keeps it out of the repo, and from then on never changes it.
Test builds sign with a key made for each run ([generic-test-build.yml](../../.github/workflows/generic-test-build.yml)).

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

## Research notes

None yet. A note about this device goes in `research/` ([how](../../docs/research/README.md#the-research-notes)).

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model
- [`android/`](android): the app (`:generic-android`), Glassworks for generic Android
- [`budgets.json`](budgets.json): the app's size budget, per ABI and per pane ([docs/size.md](../../docs/size.md#budgets)); its RAM budgets are still to set
