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
  <b>A 40-inch full-length mirror powered by Home Assistant</b><br>
  A two-way mirror with a 40-inch portrait screen behind the glass—repurposed into a local Home Assistant panel from your browser with Kiln.<br>
  Originally sold as Mirror, by Mirror.co.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://kiln.glassworks.dev/lululemon-mirror">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-beta-on-dark.svg">
    <img alt="Status: beta" src="https://www.glassworks.dev/brand/badge-status-beta-on-light.svg" height="30">
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

This folder defines the Glassworks **frame** for the lululemon Studio Mirror. Its hardware profile lives in [`design/frame.json`](design/frame.json) (see [frames/README.md](../README.md)) and is published at [glassworks.dev/frames/lululemon-mirror](https://www.glassworks.dev/frames/lululemon-mirror).

## Credits

This frame stands on the hard work of the mirror-repurposing community. Glassworks brings the panel app and browser installer; making the Mirror run open Android in the first place was their work. Thank you.

- **Android image: [Glass Panes](https://github.com/git-nimrod/glass-panes), by [git-nimrod](https://github.com/git-nimrod).** The Mirror runs their Android 6.0.1 image (`mirror-android-3`, a lighter build with their own kiosk app built in, which Kiln leaves out when it installs Glassworks). In their words: *"This is the third ready-made Android image for the Lululemon Studio mirror (board MIR63A0-00-P1, revision RevP21)."* Kiln fetches the image from their GitHub release through a pass-through proxy without storing it. The project code is MIT; the Android and kernel sources keep their own licenses, and vendor files belong to Qualcomm and Quectel. Glass Panes credits, in turn: *"This build stands on the work of the Lululemon-mirror community. Thank you to Onemoremichael, lrinQVQ, TimStewartJ, repairman29, porcuswine, vgab7 and everyone in the mirror-repurposing community."*
- **Hardware research: [lululemon-mirror-repurpose](https://github.com/repairman29/lululemon-mirror-repurpose), by [repairman29](https://github.com/repairman29)** (Apache-2.0) — identifying Qualcomm Android 6.0.1 boards, how later NXP revisions differ, and boot-chain limits ([revisions.md](research/revisions.md), [mirror-android.md](research/mirror-android.md)).
- **Hardware research: [lulu-mirror-software-only](https://github.com/Onemoremichael/lulu-mirror-software-only), by [Onemoremichael](https://github.com/Onemoremichael)** — stock kernel configuration, NXP board notes, and why screen size or purchase year do not identify the internal board.
- **Hardware research: [mirror](https://github.com/olm3ca/mirror), by [olm3ca](https://github.com/olm3ca)** — documenting the transition from 40-inch to 43-inch panels ([revisions.md](research/revisions.md)).
- **Teardown: [Smart Mirror Teardown](https://mdthats.me/projects/lululemon-smart-mirror-p1/), by [MDME](https://mdthats.me/)** — identifying the internal SoC, Wi-Fi/Bluetooth chip, audio hardware, and camera ([mirror-android.md](research/mirror-android.md)).

These same credits are stored in [`design/frame.json`](design/frame.json) and displayed on glassworks.dev and inside Kiln.

## Status

**Status: beta.** Glassworks for the Mirror is out as the legacy build of Glassworks for Generic Android, and Kiln installs its latest release on a supported Mirror straight from Chrome or Edge at [kiln.glassworks.dev/lululemon-mirror](https://kiln.glassworks.dev/lululemon-mirror). After that, new releases show up in Home Assistant under **Settings → Updates**, as on any Glassworks panel ([Updates](../../docs/updates.md)).

- **Supported hardware:** Original Qualcomm (MSM8916) Mirror units running stock software 1.x. Later revisions switched to an NXP i.MX8 board where only JTAG hardware access has been reported; those revisions are not supported. Note that screen size alone does not identify which board is inside ([see board revisions](research/revisions.md)).
- **Android 6 legacy build:** Because the original Mirror runs Android 6.0.1, Kiln installs the dedicated legacy build of [Glassworks for Generic Android](../generic-android/README.md#the-legacy-build-android-6-and-7). It runs a frozen browser engine locked strictly to your Home Assistant address, starts upright in portrait mode, and guides you through Wi-Fi and Home Assistant sign-in from your phone or computer since the Mirror has no touchscreen.
- **Keeping lululemon's system (experimental):** Kiln can also install Glassworks while keeping lululemon's own system in place, with a restore point that puts it back. It's new, and hasn't been through as many real Mirrors as the image install yet. See [Keeping lululemon's system](#keeping-lululemons-system).
- **Performance grade:** Clarity v1: **E**. See [Performance](#performance) below.

## Performance

**Clarity v1: E.** The Mirror works best as a panel for light dashboards. On them, views open in about 2 seconds and live updates stay fluid, though a tap takes about half a second to show. Typical dashboards keep the browser busy for several seconds after they appear, and on the heavy test dashboard the Mirror ran out of memory in every run, so Android closed the dashboard page and Glassworks had to load it again. Clarity counts a lost page as failed. See the full report in [clarity/report.md](clarity/report.md) and the summary in [clarity/clarity.json](clarity/clarity.json) ([how Clarity grades](../../docs/clarity.md)).

## Known issues

- **Best with light dashboards.** The Mirror has about 1 GB of memory. On a big dashboard Android may close the page to free memory, and the dashboard then reloads. Keep the Mirror's dashboard small, with few cards and cameras.
- **The camera's live stream costs memory.** Turning on the camera's video stream for Home Assistant (ONVIF/RTSP) uses memory the dashboard needs, and with it on the dashboard reloads more often. With the stream off, a light dashboard ran for an hour without a reload.

## Hardware

| Component | Details |
|---|---|
| Dimensions | About 1336 × 536 × 43 mm (40-inch model) |
| Processor | Qualcomm MSM8916 |
| Android | 6.0.1 (original stock software 1.x) |
| Display | 40-inch 1080p portrait screen behind two-way mirror glass (no touchscreen). When idle, Glassworks turns its backlight off rather than the display, which [comes back with lines](research/display-sleep-lines.md) after Android's own screen off |
| Camera & presence | 5 MP front camera (used for motion presence) |
| Audio | Built-in microphone and stereo speakers |
| Mounting | Floor stand leaning against the wall, or flat wall mount |

## Install

Open **[kiln.glassworks.dev/lululemon-mirror](https://kiln.glassworks.dev/lululemon-mirror)** in Chrome or Edge on a computer connected to the Mirror with a micro-USB data cable.

> [!IMPORTANT]
> **Installing the Glassworks image permanently replaces lululemon's stock software.** The Mirror's bootloader cannot read existing partitions back, so Kiln cannot back up or restore lululemon's original firmware. Kiln asks you to confirm before writing anything. If you ever want to switch away from Glassworks later, Kiln offers a one-click path to install the plain **Glass Panes** Android image instead. To keep lululemon's system, choose [Glassworks on the stock software](#keeping-lululemons-system): it changes one system file, keeps a restore point for it, and resets only the Mirror's app data.

### How the browser install works

1. **Download and verify:** Kiln downloads the [Glass Panes](https://github.com/git-nimrod/glass-panes) community system image and the latest Glassworks legacy APK, resuming automatically if your connection drops and verifying every SHA-256 checksum before proceeding.
2. **Prepare the image locally:** Inside your browser—without uploading anything—Kiln bundles Glassworks as a privileged system app, adds the Glassworks boot animation, leaves out the image's own kiosk app and the phone and carrier apps it still has that would otherwise waste RAM, and pairs USB debugging strictly with your browser's own key.
3. **Enter fastboot mode:**
   - **Already running Glassworks from this browser?** Kiln can back up your Home Assistant encryption key over USB and reboot the Mirror into fastboot automatically with no buttons to hold, so Home Assistant reconnects seamlessly after reflashing.
   - **First install from stock software (or reflashing from another browser):** Flashing from a different browser does not back up your existing Home Assistant encryption key, and step 4 writes a fresh `userdata` partition that erases it. To keep your existing Home Assistant pairing, reflash from the same browser you originally used; otherwise you can simply reconnect Home Assistant after first boot. Kiln plays an animated hardware guide ([`design/recovery.json`](design/recovery.json)): power off the Mirror with the bottom rocker switch, unplug power, lay it face down, remove the 4 stand screws and 14 back-panel screws (or open the rear service hatch if your unit has one), reconnect power with the switch still off, plug micro-USB into your computer, then hold **VOL− (SW3)** (the button nearer the top edge of the small green board) while switching the Mirror on for about 5 seconds. *(Do not hold both buttons—that enters Qualcomm EDL mode—and VOL+ opens lululemon's updater.)*
4. **Flash and finish setup:** Kiln checks that your partition layout matches, flashes the system and userdata partitions, reboots into portrait mode with Glassworks as the home screen, and walks you through Wi-Fi and Home Assistant setup right in your browser (or from your phone via QR code).

The initial boot logo before Android starts remains lululemon's logo, since it lives inside the locked signed bootloader.

### Keeping lululemon's system

Choose **Glassworks on the stock software** on Kiln's first screen to keep lululemon's system and change only the parts Glassworks needs. Nothing is downloaded for it, and it takes two trips into fastboot mode, so keep the back cover off until it's done.

| | Glassworks (image) | Glassworks on the stock software |
|---|---|---|
| lululemon's system | Replaced by the Glass Panes image | Kept, with one file changed |
| App data | Starts fresh | Starts fresh |
| Way back | Install the plain Glass Panes image | The restore point puts lululemon's system back as it was |
| Updates | Install by themselves | Install by themselves (Glassworks is the Device Owner) |
| Status | Beta | Experimental |

1. **Prepare:** Pick the Glassworks APK and, if you like, your Wi-Fi network. Glassworks joins it on its first start, so the Mirror comes up online and ready for Home Assistant. You can also add it on the Mirror later.
2. **First trip into fastboot:** Follow the same hardware guide as the image install. Kiln writes a small setup agent to the Mirror's app storage only, then restarts it. lululemon's system isn't touched.
3. **Copy:** The setup agent opens a Wi-Fi network of its own, `mirror-<serial>`, with no password. It can take 5–7 minutes to appear, because Android optimizes its apps on this start. Join it from this computer and click **Continue**, and Kiln copies the one system file Glassworks changes (`services.jar`) and where it sits in a few seconds. Then you can go back to your usual network. If you'd rather not switch networks, click **Use Bluetooth instead** and pick the Mirror; that takes about five minutes. In a browser without Bluetooth, click **Download it by hand instead**, save the file from the agent's own page, and pick it in Kiln. Kiln checks that the file came from this Mirror, during this run, and stops before anything is written if the Mirror's software is a kind it can't change in place.

   Reinstalling on a Mirror that already runs Glassworks this way? The install turned off lululemon's setup hotspot, which is the network the agent would use. On the fastboot screen, click **Already running Glassworks or Glass Panes? Restart over USB** instead of holding VOL−, so Kiln can check first. When the hotspot is gone, the agent joins the Wi-Fi network you gave in the first step instead. Click **Connect over Bluetooth** and pick the Mirror, and Kiln copies over your network. That needs your Wi-Fi in the first step and a browser that can use Bluetooth, such as Chrome or Edge on a computer.
4. **Second trip into fastboot:** Hold **VOL−** again while switching the Mirror on. Kiln builds the change in your browser and checks that it touches only that file's blocks.
5. **Restore point:** Before writing anything to the system, Kiln saves the exact blocks it will change, from your Mirror's own copy, in this browser, reads them back to check them, and offers a copy to download. Continue opens only once that's done. Keep the download somewhere safe.
6. **Install:** Kiln writes the changed blocks and fresh app storage, then restarts the Mirror. The Mirror's bootloader can't read a write back, so once the Mirror has started and lets your browser in over USB, Kiln reads `services.jar` back and checks it against what it wrote. If they differ, the Mirror didn't take the change: Kiln stops before installing anything and offers the restore point. If Kiln can't read the file at all, it says so in the log and carries on. Then it installs Glassworks over USB, hides lululemon's apps, and makes Glassworks the Device Owner so its updates install by themselves. The changed file also turns off lululemon's own setup hotspot (`mirror-<serial>`), which would otherwise come back whenever the Mirror is offline and keep Glassworks' setup hotspot from starting. Setup continues as usual from there.

To go back, choose **Restore the original system** on Done (or open `kiln.glassworks.dev/lululemon-mirror/undo`). Kiln uses the restore point this browser saved, or the copy you downloaded, and writes it over the blocks it changed.

Kiln can't read the restore back once it's written: the Mirror's bootloader can't read a partition, and after the restore the Mirror no longer lets your browser in over USB. So Kiln checks everything before it writes anything. It checks that each file in the restore point is exactly the one it saved, that the restore point was made on this Mirror, and that it puts back lululemon's original `services.jar` (and boot animation) in their own blocks and nothing else. While Glassworks is still running, Kiln also reads those files from the Mirror and checks that they're either what the install wrote or still lululemon's. If any check fails, Kiln writes nothing and says why; you can pick another restore point or choose another install. After the restore, lululemon's own start screen is the sign it worked. Then it resets the Mirror's app data, as a factory reset would, so Glassworks and its settings go and lululemon's apps come back, along with its setup hotspot. Kiln writes a restore point only to the Mirror it was made on. If a write fails partway through the install, the error screen offers the same restore. The Mirror's command-line tool in [`tools/mirror`](../../tools/mirror) can restore from that download too (add `--erase-userdata` to reset the app data as well).

### Returning to plain Glass Panes

At the end of setup—or anytime from Kiln's community image option—choose **Install Glass Panes instead** to flash Glass Panes' release, with all of the image's apps restored and their own kiosk app as the home screen. Kiln changes one thing in it: who the Mirror lets in over USB. Their system image has a computer's key built in that isn't published and that nobody can remove from the Mirror, so Kiln puts your browser's key in its place. And like Glass Panes' own installer, Kiln puts your browser's key in their `userdata.sparse` in place of the shared project key. The Mirror then lets only your computer in. Kiln can optionally pre-save your Wi-Fi network so the Mirror comes online without needing `scrcpy`.

A Mirror running Glassworks gets the same: your browser's key in place of the built-in one.

### Installing over adb on an existing Glass Panes Mirror

If your Mirror already runs the Glass Panes image and you only want to install the Glassworks app without reflashing, open `kiln.glassworks.dev/lululemon-mirror?mode=app`. Kiln installs the legacy APK, locks portrait rotation, sets Glassworks as the home screen, and launches network setup. First, the Mirror has to let your computer in: unless Kiln flashed it from this browser, choose **Allow** on the Mirror's prompt with a USB mouse, since it has no touchscreen. Without a mouse, use the full install above instead, which needs nothing on the Mirror's screen. On Glass Panes' newest image, their kiosk app is part of the system and keeps running next to Glassworks, so a full install from Kiln works better there. Using **Undo** removes Glassworks and restores your previous launcher.

## Panes

| Pane | Support | Notes |
|---|---|---|
| **Assist** | Alpha | Uses the built-in microphone for local wake word detection. |
| **Timers** | Alpha | On-screen voice timers paired with Assist. |
| **Sendspin** | Alpha | Synced Music Assistant playback over the Mirror's stereo speakers. |
| **Notifications** | Alpha | Because the Mirror has no touchscreen, high/critical alerts without a tag or timeout dismiss automatically after 2 minutes. |
| **Camera** | Alpha | Uses the 5 MP front camera for local motion presence (no images leave the Mirror in *Presence only* mode). Starts off until enabled in Home Assistant. The live video stream costs memory and can make the dashboard reload ([Known issues](#known-issues)). |
| **Bluetooth proxy** | Alpha | Uses the built-in Bluetooth LE radio once location permission is granted. |
| **Diagnostics** | Alpha | Reports uptime and memory (no CPU temperature sensor on this board). |
| **Zigbee / Thread radio** | Not supported | No 802.15.4 radio hardware. |
| **Time zone** | Not supported | Aqara S1 Plus only. |
| **Wall base** | Not supported | Aqara S1 Plus only. |

## Research notes

Deep engineering notes from bringing up the Mirror (see [all research notes](../../docs/research/README.md#the-research-notes)):

- [mirror-android.md](research/mirror-android.md) — Why the signed bootloader keeps the Mirror on Android 6.0.1
- [revisions.md](research/revisions.md) — Qualcomm MSM8916 vs. NXP i.MX8 board revisions
- [mirror-abis.md](research/mirror-abis.md) — 64-bit userspace ABI verification
- [home-app.md](research/home-app.md) — Setting the home screen on Android 6 without touch input
- [image-patch.md](research/image-patch.md) — System image patching and userdata creation in Kiln
- [first-boot.md](research/first-boot.md) — Zero-touch first boot (Wi-Fi, portrait rotation, no lock screen) and the backlight floor overlay
- [community-image-terms.md](research/community-image-terms.md) — Licensing and distribution notes for the community image
- [mirror-root.md](research/mirror-root.md) — Root exploration and system-uid analysis
- [privileged-app.md](research/privileged-app.md) — Privileged system-app permissions and silent updates on Android 6
- [adbd-keys.md](research/adbd-keys.md) — Why stock `adbd` rejects custom keys and how `services.jar` patching works
- [mirror-memory.md](research/mirror-memory.md) — Android 6 low-memory-killer tuning and GeckoView GPU memory budget
- [option-c.md](research/option-c.md) — Notes on building a clean AOSP 6 image from on-device vendor files
- [community-root-and-network-adb.md](research/community-root-and-network-adb.md) — Review of community root and network-adb methods
- [display-sleep-lines.md](research/display-sleep-lines.md) — Why Android's screen off leaves lines across the display, and the backlight-only sleep that avoids it
- [improv-ble-disconnect.md](research/improv-ble-disconnect.md) — Why Wi-Fi setup over Bluetooth never finished in Home Assistant, and the advertising fix
- [stock-firmware-probe.md](research/stock-firmware-probe.md) — Live probe of untouched stock 1.24.0 firmware via userdata-only HTTP app, `FS_IOC_FIEMAP`, and reversible surgical USB ADB
- [stock-softap.md](research/stock-softap.md) — Why stock software keeps opening its `mirror-<serial>` hotspot, and the `services.jar` edit that turns it off
- [camera-stock.md](research/camera-stock.md) — Why the stock camera sent green stills and a stream with no pictures, then upside-down stills and a sideways stream, and what the camera pane does about it
- [stock-inplace-parity.md](research/stock-inplace-parity.md) — USB debugging, the boot animation and one Home Assistant device on Stock-in-place, within its write-in-place limit
- [stock-wifi-boot.md](research/stock-wifi-boot.md) — Why stock Wi-Fi switches itself off after a fast boot, and how the app turns it back on

## Files

- [`design/frame.json`](design/frame.json) — Catalog profile, 3D model, and boot branding target
- [`design/recovery.json`](design/recovery.json) — Animated fastboot recovery instructions for Kiln
- [`installer/`](installer/) — Kiln WebUSB fastboot and app-mode recipes
- [`research/`](research/) — Engineering notes listed above
