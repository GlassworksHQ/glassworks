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

**Status: planned.** It needs Android 8.0 or newer: S1 Kiosk's `minSdk` is 26. S1 Kiosk runs as this frame on any device that isn't an Aqara S1 (`Build.HARDWARE` other than `sun50iw10p1`). It names itself after the device, and runs the panes the device's features allow. Not yet run on a device.

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| Radio | None |
| Presence | Whatever sensors the device has |
| Audio | Whatever the device has |
| Root | Not needed |

## Install

It will install as a normal app. It doesn't install from Kiln yet.

## Updates

App updates come from Home Assistant's update entity, as on the S1
([docs/esphome.md](../../docs/esphome.md#app-updates)). There is no root update service here, so the
app installs the new APK through Android's PackageInstaller. Two routes install it without anyone
touching the screen:

| Route | Android | What you set up once |
|---|---|---|
| Silent self-update | 12 or newer | Allow "Install unknown apps" for S1 Kiosk |
| Device owner | 8.0 or newer | `dpm set-device-owner` over adb, on a device with no accounts |

Without either, Android asks for a tap on the screen before it installs. The *Update status* sensor
says which one happens: "Installing 1.2.3 without a tap", or "Installing 1.2.3: waiting for a tap on
the screen".

### Silent self-update (Android 12+)

The app asks Android to install its own update with no user action (#277). Android allows it when:

1. "Install unknown apps" is allowed for S1 Kiosk: *Settings → Apps → S1 Kiosk → Install unknown
   apps*, or `adb shell appops set sh.edm.s1kiosk REQUEST_INSTALL_PACKAGES allow`.
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
adb shell dpm set-device-owner sh.edm.s1kiosk/.AdminReceiver
adb shell dumpsys device_policy | grep -A3 "Device Owner"
# undo (`dpm remove-active-admin` doesn't work for a release build):
adb shell am start -n sh.edm.s1kiosk/.improv.ImprovActivity --ez release_device_owner true
```

While it is device owner, the app can't be uninstalled until you undo it. The details are in
[docs/onboarding.md](../../docs/onboarding.md#device-owner-optional). Silent installs as device owner
are Android's documented behaviour (Android 6.0 and later); they haven't been tried on a generic
device yet.

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
