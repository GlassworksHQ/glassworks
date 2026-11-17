<p align="center">
  <a href="https://www.glassworks.dev/frames/lululemon-mirror">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-frame-lululemon-mirror-on-dark.svg">
      <img alt="lululemon Studio Mirror, a Glassworks frame" src="https://www.glassworks.dev/brand/banner-frame-lululemon-mirror-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">lululemon Studio Mirror</h1>

<p align="center">
  <b>A 40-inch mirror with Android behind it.</b><br>
  A full-length mirror with a 40-inch portrait screen behind it. The original version takes a new Android image over fastboot.<br>
  Originally sold as Mirror, by Mirror.co.
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
  <a href="https://www.glassworks.dev/frames/lululemon-mirror">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/frames/lululemon-mirror/clarity-on-dark.svg">
      <img alt="Clarity" src="https://www.glassworks.dev/frames/lululemon-mirror/clarity-on-light.svg" height="30">
    </picture>
  </a>
</p>

A Glassworks **frame**: the device definition for the lululemon Studio Mirror. Its data is in [`design/frame.json`](design/frame.json) (format: [frames/README.md](../README.md)), and [glassworks.dev/frames/lululemon-mirror](https://www.glassworks.dev/frames/lululemon-mirror) shows it.

## Credits

This frame stands on other people's work. Glassworks adds its app to the Mirror; making the Mirror
run Android in the first place was theirs. Thank you.

- **The Android image: [Glass Panes](https://github.com/git-nimrod/glass-panes), by
  [git-nimrod](https://github.com/git-nimrod).** The Mirror runs their Android 6.0.1 image, release
  [`mirror-android-2`](https://github.com/git-nimrod/glass-panes/releases/tag/mirror-android-2)
  (2026-11-16: image 1 with a fully dark panel in sleep). In
  their words: "Ready-made system.img and userdata.sparse for the Lululemon Studio mirror (board
  MIR63A0-00-P1, revision RevP21)."
  Kiln fetches it from their GitHub release through Refract, our download proxy, which passes it
  through without storing it. The project's code is MIT;
  the image's Android and kernel source keep their own licences, and its vendor files belong to
  Qualcomm and Quectel. Glass Panes credits, in turn: "This build stands on the work of the
  Lululemon-mirror community. Thank you to Onemoremichael, lrinQVQ, TimStewartJ, repairman29,
  porcuswine, vgab7 and everyone in the mirror-repurposing community."
- **Research: [lululemon-mirror-repurpose](https://github.com/repairman29/lululemon-mirror-repurpose),
  by [repairman29](https://github.com/repairman29)** (Apache-2.0). Which boards run Android 6.0.1,
  why the later NXP revisions differ, and the limits of the boot chain
  ([revisions.md](research/revisions.md), [mirror-android.md](research/mirror-android.md)).
- **Research: [lulu-mirror-software-only](https://github.com/Onemoremichael/lulu-mirror-software-only),
  by [Onemoremichael](https://github.com/Onemoremichael).** The stock kernel's config, the research
  on the NXP revisions, and why size and year don't identify a board (same notes).
- **Research: [mirror](https://github.com/olm3ca/mirror), by [olm3ca](https://github.com/olm3ca).**
  The move from 40-inch to 43-inch panels ([revisions.md](research/revisions.md)).
- **Teardown: [Smart Mirror Teardown](https://mdthats.me/projects/lululemon-smart-mirror-p1/), by
  [MDME](https://mdthats.me/).** The chips inside: the SoC, Wi-Fi and Bluetooth, audio and camera
  ([mirror-android.md](research/mirror-android.md)).

The same list is in [`design/frame.json`](design/frame.json) (`credits`), which glassworks.dev and
Kiln show ([how](../README.md#credits)).

## Status

**Status: planned.** Nothing installs on it yet. The target is the Qualcomm (MSM8916) board with stock software 1.x. Later revisions use an NXP i.MX8 board, where only hardware-assisted (JTAG) access has been reported; they are not a target. Screen size doesn't tell them apart ([revisions.md](research/revisions.md)).

The original version runs Android 6.0.1, and S1 Kiosk needs Android 8.0 or newer (`minSdk` 26), so today's app doesn't install on it. Kiln's Mirror recipe installs the legacy build of Glassworks for generic Android (`dev.glassworks.generic_android.legacy`, #198) instead, which asks for Android 6.0 (API 23); it doesn't run on a Mirror yet. Why a newer Android is hard on this board: [mirror-android.md](research/mirror-android.md).

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| Size | About 1336 × 536 × 43 mm (40-inch version, from reviews) |
| SoC | Qualcomm MSM8916 |
| Android | 6.0.1 (stock software 1.x) |
| Screen | 40-inch 1080p, portrait, behind a two-way mirror |
| Presence | 5 MP front camera |
| Audio | Microphone and stereo speakers |
| Mount | Leans on the wall on its stand, or mounts flat on the wall |

## Install

Not available yet. **Test only, today**, at `/lululemon-mirror?apk=local` in Kiln ([its recipe](installer)). Nothing of it has run on a real Mirror yet.

**Over fastboot, from lululemon's software or the community image (#40):**

1. Kiln downloads `system.img`, the [Glass Panes](https://github.com/git-nimrod/glass-panes) community image by [git-nimrod](https://github.com/git-nimrod), from its release through Refract, Kiln's download proxy (a Cloudflare Worker that stores nothing, #347), resuming if the connection drops, and checks its SHA-256 as it arrives. If the proxy can't serve it, you download it from the release yourself and select it.
2. Kiln adds the legacy build of Glassworks (as a privileged app in `/system/priv-app/Glassworks`), the Glassworks boot animation and the screen's rotation to the image, in your browser, and lets only your browser's adb key in without asking, in place of the Glass Panes project key, which is published with its private half. It also makes a fresh userdata. Nothing is uploaded. What changes, and why: [image-patch.md](research/image-patch.md).
3. You open the Mirror's back and hold its button as it powers on: fastboot. Kiln checks the partition sizes the image is made for, and stops if they differ.
4. Kiln writes system and userdata, restarts the Mirror, and finishes over adb: Home Assistant's address, the Wi-Fi, Glassworks as the only home screen.

**That erases lululemon's software for good.** The Mirror's bootloader can't read anything back, so there is no backup, and no way to restore lululemon's software. Kiln says so and waits for your confirmation before it writes. The way back from Glassworks is the plain Glass Panes image, which Kiln can write too (Way back).

The boot logo before Android starts stays lululemon's: it lives in the signed bootloader, which nothing here can change.

**Over adb, onto a Mirror that already runs the community image (`&mode=app`):** Kiln installs the legacy build of Glassworks from an APK file on your computer. It changes nothing else on the Mirror. Making Glassworks the home screen needs a choice on the Mirror, in Android's list of home screens: Glassworks, then Always ([home-app.md](research/home-app.md)). On an update where another app is still kept as the home screen, Kiln opens that app's settings first: choose Clear defaults. Undo removes the app and shows the same list again: choose the home app you had before, then Always. The Mirror has no touchscreen, so use a USB mouse (or scrcpy). None of this has run on a real Mirror yet, undo included.

## Panes

| Pane | Support | Note |
|---|---|---|
| Assist | Planned | It has a microphone. |
| Timers | Planned | Needs Assist. |
| Sendspin | Planned | It has stereo speakers. |
| Zigbee and Thread radio | No | No Zigbee or Thread radio. |
| Time zone | No | Worked out: only on the S1 (`aqara-s1-plus`). |
| Diagnostics | Planned | Worked out: needs no special hardware. |
| Bluetooth proxy | Planned | Worked out: has Bluetooth LE (its WCN3660B, read from a teardown). |
| Notifications | Planned | Worked out: needs no special hardware. |
| Camera | Planned | Worked out: has a camera (5 MP, front). |
| Wall base | No | S1 only. |

"Worked out" means `frame.json` doesn't rate the pane; its support comes from what the pane needs and what the frame has ([how](../README.md#the-panes-a-frame-runs)).

## Research notes

What we found on this device, and how ([all notes](../../docs/research/README.md#the-research-notes)):

- [mirror-android.md](research/mirror-android.md): Can the lululemon Mirror run Android 8+ for the current GeckoView? The signed boot image blocks it; the kexec idea, the paths, blobs and read-only probes (desk research, #39)
- [revisions.md](research/revisions.md): Which Mirror revisions are a target: the later NXP i.MX8 revisions, and why screen size doesn't identify it (read, no hardware)
- [mirror-abis.md](research/mirror-abis.md): Which ABIs the Mirror's userspace runs: 64-bit in the community image, stock untested (#98)
- [home-app.md](research/home-app.md): How adb without root makes S1 Kiosk the home screen on Android 6: no `cmd`, so Android's chooser and an Always tap (read-only, 2026-11-15)
- [image-patch.md](research/image-patch.md): What Kiln changes in the community system image and what a fresh userdata needs for Kiln's own adb key; flashed on a real Mirror once (2026-11-16): boots, takes the adb key; the boot animation rendered sideways (hwrotation doesn't turn it) and the test APK has no home screen (#40)
- [first-boot.md](research/first-boot.md): A first boot with nothing to tap: Wi-Fi on with the network saved, no screen timeout, no lock screen, no auto-rotate; read from AOSP 6.0.1 and the image, verified on the unit 2026-11-16 (#29)
- [community-image-terms.md](research/community-image-terms.md): May Kiln download and modify the community system image? No licence for its vendor binaries, no CORS; open (#40)
- [mirror-root.md](research/mirror-root.md): Can we root the Mirror dev unit cheaply and safely? The AOSP `su` route is a dead end; a platform test-key app gives uid system with no flash; three `system`-only leads stay open. Nothing flashed (#40)
- [privileged-app.md](research/privileged-app.md): Does an app in `/system/priv-app` get Android 6's privileged permissions (silent updates, reboot, secure settings) and keep them after it updates? Yes: read from AOSP 6.0.1 and verified on the unit with the probe app (2026-11-16). Whether it can approve a computer's adb key: read; the permission verified on the unit (read-only), the approval untested (#29)
- [adbd-keys.md](research/adbd-keys.md): Why doesn't adbd accept a key from `/data/misc/adb/adb_keys`? The stock lululemon boot's adbd most likely doesn't verify against that file. The AOSP key-loading timing is ruled out and SELinux is ruled out by the policy; a malformed key file is narrowed, not ruled out. So the `services.jar` rewrite (#426) is the only lever Kiln has without the boot partition, which it never writes (read/inferred, 2026-11-16, #29)

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model, and the boot branding's target
- [`research/`](research/): the notes above
