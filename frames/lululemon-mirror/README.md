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
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-under-development-on-dark.svg">
    <img alt="Status: under development" src="https://www.glassworks.dev/brand/badge-status-under-development-on-light.svg" height="30">
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

**Status: under development.** The legacy build has run on a real Mirror, but there is no release for it yet, and Kiln's install is test only. The target is the Qualcomm (MSM8916) board with stock software 1.x. Later revisions use an NXP i.MX8 board, where only hardware-assisted (JTAG) access has been reported; they are not a target. Screen size doesn't tell them apart ([revisions.md](research/revisions.md)).

The original version runs Android 6.0.1, and the S1 app needs Android 8.0 or newer (`minSdk` 26), so today's app doesn't install on it. Kiln's Mirror recipe installs the legacy build of Glassworks for generic Android (`dev.glassworks.generic_android.legacy`, #198) instead, which asks for Android 6.0 (API 23). It has run on a Mirror: it starts with no crashes and stands upright (2026-11-17, #443), and the dashboard loads after a sign-in from a phone (2026-11-17, #459). It runs a frozen engine and opens only your Home Assistant ([legacy-engine.md](../../docs/research/legacy-engine.md)). Why a newer Android is hard on this board: [mirror-android.md](research/mirror-android.md).

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

Not available yet. **Test only, today**, at `/lululemon-mirror?apk=local` in Kiln ([its recipe](installer)). The fastboot path has run end to end on a real Mirror from the command line, with `tools/mirror/flash-node.ts`, which runs the same steps without a browser ([image-patch.md](research/image-patch.md#the-first-real-flash-2026-11-16-verified-by-the-root)). In that run the home-screen step failed: its test APK had no home screen. The legacy build has been the home screen since (2026-11-17, #443). It hasn't run from Kiln's page yet.

**Over fastboot, from lululemon's software or the community image (#40):**

1. Kiln downloads `system.img`, the [Glass Panes](https://github.com/git-nimrod/glass-panes) community image by [git-nimrod](https://github.com/git-nimrod), from its release through Refract, Kiln's download proxy (a Cloudflare Worker that stores nothing, #347), resuming if the connection drops, and checks its SHA-256 as it arrives. If the proxy can't serve it, you download it from the release yourself and select it.
2. Kiln adds the legacy build of Glassworks (as a privileged app in `/system/priv-app/Glassworks`), and the Glassworks boot animation to the image, in your browser, and lets only your browser's adb key in without asking, in place of the Glass Panes project key, which is published with its private half. It leaves out 36 of the image's apps a Mirror never uses (phone and SMS front ends, Qualcomm diagnostics, demos and test tools), which otherwise restart over and over and take RAM the dashboard needs ([the list](installer/src/unused.ts)). It also makes a fresh userdata. Nothing is uploaded. What changes, and why: [image-patch.md](research/image-patch.md).
3. On a Mirror that already runs Glassworks, Kiln first keeps its Home Assistant device: you switch the Mirror on with the back off and USB in, click Connect, and Kiln reads the key Home Assistant set from the running app over adb, keeps it in the browser tab only, and restarts the Mirror into fastboot itself, with no buttons to hold. At the first start it gives the key back, so Home Assistant reconnects with no click ([esphome.md](../../docs/esphome.md#identity-across-reinstalls)). Only the browser that last installed Glassworks on the Mirror gets in. Kiln opens this step by itself in that browser; elsewhere it is a link under the fastboot steps. When Kiln can't read the key, the reflash still works and Home Assistant asks once. Not yet run on a real Mirror (#580).
4. Otherwise you put the Mirror in fastboot, and Kiln's card plays how ([`design/recovery.json`](design/recovery.json)). Switch it off with the rocker switch in its bottom edge, unplug its power cord and lay it face down. Take out the 4 screws in the bottom edge that hold the stand, take the stand off, then take out the 14 screws in the back (2 of them either side of the recess by the feet) and lift the back off. Plug the power cord back into its socket in the recess, with the switch still off, and plug the micro-USB port, in the top end's corner, into the computer. On the small green board at the top end, hold **VOL−** (SW3), the one of its two buttons nearer the top end's edge, and keep holding while you switch the Mirror on, for about 5 seconds more. Only VOL−: both buttons together start Qualcomm's download mode (EDL), which Kiln can't use, and VOL+ opens lululemon's update screen. Some units have a service hatch in the back that reaches the board instead. Kiln then checks the partition sizes the image is made for, and stops if they differ.
5. Kiln writes system and userdata, restarts the Mirror, and finishes over adb: the screen upright in portrait, Home Assistant's address, the Wi-Fi, Glassworks as the only home screen.

**That erases lululemon's software for good.** The Mirror's bootloader can't read anything back, so there is no backup, and no way to restore lululemon's software. Kiln says so and waits for your confirmation before it writes. The way back from Glassworks is the plain Glass Panes image, which Kiln can write too (Way back). It brings back the apps Kiln left out too.

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
| Diagnostics | Planned | Worked out: needs no special hardware. The legacy build has the pane, with no CPU temperature. Not yet run on a Mirror. |
| Bluetooth proxy | Planned | Worked out: has Bluetooth LE (its WCN3660B, read from a teardown). |
| Notifications | Planned | Worked out: needs no special hardware. |
| Camera | Planned | Worked out: has a camera (5 MP, front). The legacy build has the pane: presence wakes the screen when someone walks up, and no image leaves the Mirror in Presence only. Off until turned on (`camera_pane` in Home Assistant); Kiln grants the camera. Not yet run on a Mirror. |
| Wall base | No | S1 only. |

"Worked out" means `frame.json` doesn't rate the pane; its support comes from what the pane needs and what the frame has ([how](../README.md#the-panes-a-frame-runs)).

## Research notes

What we found on this device, and how ([all notes](../../docs/research/README.md#the-research-notes)):

- [mirror-android.md](research/mirror-android.md): Can the lululemon Mirror run Android 8+ for the current GeckoView? The signed boot image blocks it; the kexec idea, the paths, blobs and read-only probes (desk research, #39)
- [revisions.md](research/revisions.md): Which Mirror revisions are a target: the later NXP i.MX8 revisions, and why screen size doesn't identify it (read, no hardware)
- [mirror-abis.md](research/mirror-abis.md): Which ABIs the Mirror's userspace runs: 64-bit in the community image, stock untested (#98)
- [home-app.md](research/home-app.md): How adb without root makes the app the home screen on Android 6: no `cmd`, so Android's chooser and an Always tap (read-only, 2026-11-15)
- [image-patch.md](research/image-patch.md): What Kiln changes in the community system image and what a fresh userdata needs for Kiln's own adb key; flashed on a real Mirror once (2026-11-16): boots, takes the adb key; the boot animation rendered sideways (hwrotation doesn't turn it) and the test APK has no home screen (#40)
- [first-boot.md](research/first-boot.md): A first boot with nothing to tap: Wi-Fi on with the network saved, no screen timeout, no lock screen, no auto-rotate; read from AOSP 6.0.1 and the image, verified on the unit 2026-11-16 (#29)
- [community-image-terms.md](research/community-image-terms.md): May Kiln download and modify the community system image? No licence for its vendor binaries, no CORS; open (#40)
- [mirror-root.md](research/mirror-root.md): Can we root the Mirror dev unit cheaply and safely? The AOSP `su` route is a dead end; a platform test-key app gives uid system with no flash; three `system`-only leads stay open. Nothing flashed (#40)
- [privileged-app.md](research/privileged-app.md): Does an app in `/system/priv-app` get Android 6's privileged permissions (silent updates, reboot, secure settings) and keep them after it updates? Yes: read from AOSP 6.0.1 and verified on the unit with the probe app (2026-11-16). Whether it can approve a computer's adb key: yes, for one connection at a time, verified on the unit (2026-11-16), and again on each connection with a shell loop (2026-11-17, #435). The app's own approval is untested on the unit (#29, #435)
- [adbd-keys.md](research/adbd-keys.md): Why doesn't adbd accept a key from `/data/misc/adb/adb_keys`? The stock lululemon boot's adbd most likely doesn't verify against that file. The AOSP key-loading timing is ruled out and SELinux is ruled out by the policy; a malformed key file is narrowed, not ruled out. So the `services.jar` rewrite (#426) is the only lever Kiln has without the boot partition, which it never writes (read/inferred, 2026-11-16, #29)
- [mirror-memory.md](research/mirror-memory.md): Why Glassworks "crashes" on the Mirror: the lowmemorykiller at memory status critical, not a crash; the memory budget, why GeckoView 144's GPU process dies when the app leaves the front, and what the app and the image cut (2026-11-18)
- [option-c.md](research/option-c.md): Our own system image with vendor files taken off the person's Mirror (option C): which files (about 140 without the camera), which adb's shell can read (all but 13 daemons), the AOSP 6 build and its CI cost, how Kiln would assemble it, and the plan (read, off-device, 2026-11-18, #334)
- [community-root-and-network-adb.md](research/community-root-and-network-adb.md): Two community projects read for #573. `shihanqu`'s kernel-module root (the §4 `.ko`, built: full permissive root via the free `adsprpc.ko` slot — borrow the method, don't ship the GPL module) and `jstriblet`'s network adb on a stock Mirror (a key + TCP port in a fresh userdata — a stock-adb data point, skip adopting). Bearing on #334/#435/#486/#574/#577 and hard rules 1/4/5 (read, off-device, 2026-11-18, #573)

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model, and the boot branding's target
- [`design/recovery.json`](design/recovery.json): the way into fastboot, as data for Kiln's animation ([fields](../../docs/design/kiln/README.md#the-recovery-section)). The screw order and the button come from the maintainer (2026-11-18, #521), and the buttons' names and the modes from the [Glass Panes guide](https://github.com/git-nimrod/glass-panes/blob/main/docs/mirror-app/install-android-lululemon-studio.md). The maintainer's review of the drawing (2026-11-18, #561) added the rest: the side screws hold a stand, one bar along the bottom edge; the back is one piece; the two buttons sit side by side; the micro-USB port is in the top end's corner; and the order is USB, then VOL−, then the rocker power switch in the bottom edge. They also gave the switch's place (the middle of the bottom edge) and photos of the stand, the back by the feet and the board: the stand is the U of tube the 3D model in `frame.json` draws, closed by a top bar of the same tube along the bottom edge, which its 4 screws go through; the back has two speaker grilles and, by the feet, a recess for the power cord that belongs to the body and stays when the back lifts, with a screw either side of it; the power cord goes back in once the back is off; and the two buttons sit about 5 mm apart. The micro-USB in the corner is an extension of the board's own. Positions are drawn from these, not measured.
- [`research/`](research/): the notes above
