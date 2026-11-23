<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Kiln installs LineageOS, then Glassworks (#748)

**Status: design, 2026-11-23. Post-v1.** Nothing here has run on a device. The counts, the download API
and the CORS answers were measured on 2026-11-23. Each device claim is marked **verified** (measured that
day), **read** (from source, with the file named) or **untested**.

For a phone or tablet that LineageOS supports, Kiln installs LineageOS itself and then Glassworks. The
device ends up clean and the same every time: our boot animation, Glassworks as device owner, and OS
updates from LineageOS that you see and install from Home Assistant ([#745's
design](../../research/lineageos-frame-updates.md)). The design holds for every supported device, with
no Samsung or Pixel special case. Kiln reads the device's codename, looks up how LineageOS installs on it,
and runs one recipe per *install method*, never per device.

## 1. The data, measured

LineageOS keeps every device it supports as a YAML file in its wiki,
[`LineageOS/lineage_wiki`](https://github.com/LineageOS/lineage_wiki) `_data/devices/*.yml`. The
schema (`test/schema-06.yml`) names 30 install methods. The counts below come from
[`tools/kiln/lineage-devices.py`](../../../tools/kiln/lineage-devices.py), which reads only public data:

```sh
python3 tools/kiln/lineage-devices.py --fetch          # shallow clone into a temp dir, Markdown out
python3 tools/kiln/lineage-devices.py --wiki <checkout> --json
```

**Wiki at `67a8877` (2026-11-21), counted 2026-11-23:** 778 device files, 645 codenames. 90 codenames
have more than one file (`<codename>_variant<n>.yml`, for models that install differently). **455 files
(356 codenames) are maintained**, meaning `maintainers` isn't empty. The wiki marks a device with no
maintainer as discontinued (`_includes/templates/device_info.md`). The download server agrees: its
`/api/v2/oems` lists 357 codenames, which are the 356 plus `manaus`.

### By family

How Kiln reaches each family. *Unlock* is how the bootloader unlocks, read from each method's install
template (`_includes/templates/recovery_install_<method>.md`). A file with `is_unlockable: false`
counts as *closed* whatever its method: the wiki says no official unlock exists for it.

| Family | Unlock | Files | Maintained | Tablets | Phones¹ | Other² | Kiln over the browser |
|---|---|---|---|---|---|---|---|
| fastboot | a fastboot command after the OEM unlocking switch (`kiln`) | 186 | **117** | 7 | 97 | 13 | **Yes**: fastboot and adb over WebUSB today, plus `adb sideload` |
| fastboot | ships unlocked (`done`) | 15 | **10** | 0 | 2 | 8 | **Yes**, as above |
| fastboot | a token, code, file, app or account from the vendor first (`vendor`) | 332 | **216** | 3 | 213 | 0 | **Yes, after the person unlocks** through the vendor. Kiln checks `getvar unlocked` and links the vendor's page |
| fastboot | no official unlock (`closed`) | 56 | 28 | 0 | 25 | 3 | No. Out of scope |
| Samsung Download mode | OEM unlocking switch, then a key press on the device | 110 | **36** | 11 | 25 | 0 | **Yes, with a new library**: the Odin protocol over WebUSB or Web Serial ([§7](#7-libraries)) |
| Samsung Download mode | no official unlock (`closed`) | 10 | 10 | 1 | 9 | 0 | No. Out of scope |
| Other | each its own | 69 | 38 | 0 | 12 | 26 | No. See the next table |

¹ `phone`, `phone (slider)`, `foldable`. ² Set-top boxes, handheld consoles, devkits, TVs.

**Kiln can reach 163 maintained files with the person's hands alone, and 216 more once the person
has unlocked through the vendor.** That's 379 of 455 in all. Of the 22 maintained tablet files (18 codenames), 21
can be reached. The one that can't is the Galaxy Tab S6 Lite 2024, `gta4xlswifi`, which is closed.

### By method

| `install_method` | Files | Maintained | Tablets | Phones | Other | Maintained, `is_unlockable: false` |
|---|---|---|---|---|---|---|
| `fastboot_nexus` | 183 | 117 | 7 | 95 | 15 | 4 |
| `fastboot_xiaomi` | 115 | 77 | 0 | 77 | 0 | 0 |
| `fastboot_motorola` | 99 | 63 | 0 | 63 | 0 | 1 |
| `samloader_rs` | 120 | 46 | 12 | 34 | 0 | 10 |
| `fastboot_xiaomi_hyperos` | 23 | 23 | 0 | 23 | 0 | 0 |
| `fastboot_sony` | 45 | 22 | 0 | 22 | 0 | 0 |
| `dd` | 39 | 12 | 0 | 12 | 0 | 12 |
| `fastboot_custom` | 13 | 11 | 1 | 10 | 0 | 0 |
| `fastboot_unlocked` | 15 | 10 | 0 | 2 | 8 | 0 |
| `apx` | 10 | 10 | 0 | 0 | 10 | 0 |
| `fastboot_lg` | 12 | 9 | 0 | 9 | 0 | 9 |
| `amlogic_update` | 8 | 8 | 0 | 0 | 8 | 0 |
| `nintendo` | 8 | 8 | 0 | 0 | 8 | 8 |
| `fastboot_oneplus_tmo` | 7 | 7 | 0 | 7 | 0 | 0 |
| `fastboot_fairphone` | 5 | 5 | 0 | 5 | 0 | 0 |
| `fastboot_nokia` | 5 | 4 | 0 | 3 | 1 | 4 |
| `fastboot_realme` | 6 | 4 | 0 | 4 | 0 | 4 |
| `fastboot_oplus` | 4 | 4 | 2 | 2 | 0 | 0 |
| `fastboot_nubia` | 6 | 4 | 0 | 4 | 0 | 0 |
| `fastboot_zenfone` | 16 | 3 | 0 | 3 | 0 | 3 |
| `fastboot_realme_china` | 3 | 3 | 0 | 3 | 0 | 0 |
| `fastboot_generic` | 3 | 2 | 0 | 2 | 0 | 2 |
| `fastboot_lenovo` | 5 | 2 | 0 | 2 | 0 | 0 |
| `fastboot_zte` | 1 | 1 | 0 | 1 | 0 | 1 |
| `fastboot_huawei`, `fastboot_oppo`, `fastboot_htc`, `edl_custom`, `oor` | 27 | 0 | 0 | 0 | 0 | 0 |
| `odin_revision` (in the schema, no device uses it) | 0 | 0 | | | | |

The wiki has no `heimdall` method any more. Samsung devices install with
[samloader-rs](https://github.com/topjohnwu/samloader-rs) (`samloader_rs`), which matters for §7.

**The "other" methods, and what each would need:**

| Method | Maintained | What it is (read from its template) | Kiln |
|---|---|---|---|
| `dd` | 12 | Root the stock OS with an exploit, then `dd` the recovery from an adb shell. All 12 are `is_unlockable: false` | Out of scope: it needs an exploit, and Kiln doesn't ship exploits |
| `apx` | 10 | NVIDIA Tegra recovery mode (Shield TV) and NVIDIA's `tegraflash`, Linux only, with `sudo` | Out of scope: set-top boxes, a closed vendor protocol |
| `amlogic_update` | 8 | Amlogic burn mode and Amlogic's `update` tool | Out of scope: set-top boxes, a vendor protocol with no open spec |
| `nintendo` | 8 | Nintendo Switch through Hekate on an exploited or modded unit | Out of scope |
| `edl_custom`, `oor` | 0 | Qualcomm EDL and firehose loaders, a patched aboot | Out of scope, and nothing maintained uses them |

### Tablets, maintained (18 codenames)

| Device | Codename | Method | Branch |
|---|---|---|---|
| Google Pixel Tablet | `tangorpro` | `fastboot_nexus` | 24.0 |
| OnePlus | `caihong`, `erhai` | `fastboot_nexus` | 24.0 |
| OnePlus | `iceland` | `fastboot_oplus` (vendor unlock) | 24.0 |
| Razer | `nicole` | `fastboot_nexus` | 24.0 |
| Amazon | `sunstone` | `fastboot_custom` (vendor unlock) | 24.0 |
| Galaxy Tab A7 10.4 2020, Wi-Fi and LTE (SM-T500, SM-T505…) | `gta4lwifi`, `gta4l` | `samloader_rs` | 24.0 |
| Galaxy Tab S7 and S7+, Wi-Fi and LTE | `gts7lwifi`, `gts7l`, `gts7xlwifi`, `gts7xl` | `samloader_rs` | 24.0 |
| Galaxy Tab S6 Lite, Wi-Fi and LTE (SM-P610, SM-P615) | `gta4xlwifi`, `gta4xl` | `samloader_rs` | 23.2 |
| Galaxy Tab S6 Lite 2024 (SM-P620) | `gta4xlswifi` | `samloader_rs`, **closed** | 23.2 |
| Galaxy Tab A 8.0 2019 (SM-T290) | `gtowifi` | `samloader_rs` | 22.2 |
| Galaxy Tab S5e, Wi-Fi and LTE | `gts4lvwifi`, `gts4lv` | `samloader_rs` | 22.2 |

Branches among maintained files: 24.0 (253), 22.2 (141), 23.2 (61).

**Two more fields shape the flow:**

- `before_install: needs_specific_android_fw` is set on 356 of the 455 maintained files: the device's
  stock firmware has to be a given version before the install. Most give it as an Android version
  (`before_install.version`, Android 17 for `tangorpro`); 198 of them have `ships_fw: true`. 44
  instead list a firmware build per regional variant in `before_install_device_variants` (`beryllium`,
  the POCO F1: `V12.0.3.0.QEJMIXM` for Global, another for Russia), with no Android version at all. The
  snapshot keeps both forms (`firmware` in [§7](#lineageos-device-data-frameslineageosinstallersrcdevicests)).
  The Android-version form also asks for *the latest update* of that version
  (`device_specific/before_install_needs_specific_android_fw.md`). Kiln checks what it can establish
  on the stock OS over adb: `ro.build.version.release` against the Android version, and
  `ro.build.version.incremental` and `ro.build.display.id` against a variant's build. **When it can't
  establish the firmware, it stops before any write** and says which firmware the device needs. That
  covers a device on another custom ROM, whose Android version says nothing about its firmware, and a
  device that starts in fastboot or Download mode. *Latest update* isn't something Kiln can read: no
  vendor publishes it in a form the browser can fetch. So the person establishes it: a check step
  shows the device's own update (`ro.build.version.security_patch`, `ro.build.version.incremental`)
  and asks them to run the device's system update until it says it's up to date. The unlock screen
  doesn't appear until they confirm, and the log keeps what they confirmed against.
- `before_recovery_install` adds steps for 292 maintained files. In the two families Kiln reaches, the
  values are `boot_stack` (234: flash extra partitions such as `dtbo` and `vendor_kernel_boot`, listed
  in the data), `samsung_exynos9xxx` (23), `samsung_qcom` (20; it also flashes LineageOS's `vbmeta.img`
  through Download mode), and one device each for `g12` (4), `nubia` (4), `foster` (2), `samsung_a21s`,
  `samsung_universal7904`, `ellis` and `TP1803`. The snapshot keeps the value (`beforeRecovery`), and
  the recipe picks its pre-recovery steps from it. Method and family can't tell `samsung_qcom` from
  `samsung_exynos9xxx`.
- `before_lineage_install` runs in recovery before LineageOS is sideloaded: `ab_copy_partitions` (69),
  which sideloads LineageOS's `copy-partitions-<date>-signed.zip` so the inactive slot's firmware
  matches the active one (the wiki warns of a hard brick otherwise), and `m1s1` (2). The snapshot keeps it
  (`beforeLineage`).
- `recovery_reboot` says how to get from the bootloader to recovery after a flash: `fastboot_menu`
  (187: pick Recovery in the bootloader's menu), `fastboot_reboot` (61: `fastboot reboot recovery`),
  `fastboot_boot` (10: `fastboot boot <recovery>.img`, from RAM), `fastboot_misc` (1: write a
  `misc` image that boots recovery), or none (158: the `recovery_boot` keys). The snapshot keeps it
  (`recoveryReboot`), and step 7 dispatches on it.

## 2. How Kiln knows the device

### Reading the codename

| Where the device is | What Kiln reads | Notes |
|---|---|---|
| Android, USB debugging on (preferred start) | `getprop ro.lineage.device` (already on LineageOS), `ro.product.device`, `ro.product.vendor.device`, `ro.build.product`, `ro.product.model`, `ro.build.version.release` | The same adb read the generic Android flow already does (`frames/generic-android/installer`). The stock codename usually matches LineageOS's, but not always |
| Bootloader (fastboot) | `getvar product`, `getvar unlocked`, `getvar slot-count`, `getvar current-slot`, `getvar is-userspace` | `product` is the board name, which isn't always the codename |
| Samsung Download mode | `DVIF`, the pre-handshake device-info query: `MODEL` (`SM-P610`), sales code, bootloader version | Read from samloader-rs `odin/src/device_info.rs`. Untested. Its `UN` field is the serial: **never logged, stored or put in a device report** (hard rule 2) |
| Recovery (LineageOS Recovery) | adb banner `recovery::` or `sideload::`, `ro.lineage.device` when adb is up | Proves the recovery booted |

### Matching to the wiki entry

`match()` in the device data ([§7](#7-libraries)) goes in this order and stops at the first hit:

1. `ro.lineage.device` equals a codename.
2. `ro.product.model` or `DVIF` `MODEL` is in an entry's `models` (526 files list models). This is
   also how Kiln picks between the files of one codename: the Galaxy Tab A7's `models` tell SM-T500 from
   SM-T505.
3. `ro.product.device`, `ro.product.vendor.device`, `ro.build.product` or fastboot's `product` equals
   a codename or `custom_recovery_codename`.

More than one hit (two variants with no models) means the person picks from a short list, showing each
variant's name and models. No hit means "LineageOS doesn't support this device", with a link to the
wiki's device list, and the normal `/android` install stays one click away.

### Frames as data

This follows [one engine, frames as data](frames-as-data.md). Kiln's app and packages gain transports,
and nothing that names LineageOS or a device:

| What | Where | Kind |
|---|---|---|
| The frame: route, catalog facts, ratings, way back, words | `frames/lineageos/design/frame.json`, `installer.json` | Data, by hand |
| One flow per method family: `fastboot`, `samsung`. Their steps, transports, inputs, checks, taps | `installer.json` `flows` | Data, by hand |
| Method table: each `install_method` → family, unlock kind, the vendor's unlock page, Kiln's words | `frames/lineageos/installer/src/methods.json` | Data, by hand. Reviewed when the wiki adds a method |
| Each device: codename, variant, name, vendor, type, models, method, `is_ab_device`, `recovery_partition_name`, `recovery_reboot`, `before_lineage_install`, `before_recovery_install` and its boot-stack partitions, the firmware it needs (Android version or per-variant builds), key combinations (`download_boot`, `recovery_boot`) | `frames/lineageos/installer/data/devices.json` | **Generated** from the wiki, never edited by hand |
| What data can't say: building the fastboot write list from a build listing, Download mode's partition names from the PIT, the add-on zip | `frames/lineageos/installer/src/` behind `FrameSteps` (`check`, `build`, `setUp`) | Code, per method family |
| Builds, files, sizes, SHA-256 | `download.lineageos.org/api/v2/devices/<codename>/builds`, at install time | Live (CORS allows it, [§3](#3-downloads)) |

**The snapshot.** `tools/kiln/lineage-devices.py` gains `--emit`, which writes `devices.json` with only
the fields above. It records the wiki's commit and date, and the licence: the wiki's text is CC BY-SA
3.0 (its page footer), so the file carries that notice ([decision 4](#decisions-for-the-maintainer)). A
scheduled workflow regenerates it weekly and opens a PR when it changes. A method that isn't in
`methods.json` fails the generator, so a new method is never guessed at. HTML in the key-combination
strings (`<kbd>`) becomes structured key lists at generation time, so Kiln never renders the wiki's
HTML.

**Live at install time:** only the download API, which sends CORS headers. Kiln never scrapes the wiki
or the download pages from the browser. A device with no build in the API isn't offered, even when the
snapshot lists it as maintained, so only official builds get through (#748 piece 5).

**Where it shows in Kiln.** `/lineageos` is the frame's own route. When the device on `/android` matches
the snapshot and has a build, `/android` adds a quiet line under Glassworks: "This device can also run
LineageOS". This follows the community-images pattern ([community-images.md](community-images.md)), so
Glassworks on the device's own Android stays first and picked. After LineageOS's first boot, the frame
hands the device to the generic Android app install, then to the standard flow, the same as every
frame.

**New engine pieces** (frame-neutral, in `kiln/packages`): an `odin` transport, a `sideload` mode on
the adb transport, and a fastboot device filter by interface class (`0xff/0x42/0x03`, the standard
fastboot interface). `@glassworks/fastboot` only knows the S1's Allwinner ids today
(`FASTBOOT_FILTER`). Fastboot also needs a generic command for `flashing unlock` and `oem unlock`, and
`set_active`. Today only `oemRun` is public.

## 3. Downloads

### What the hosts answer (verified 2026-11-23)

Requests sent with `curl -sS -D - -H 'Origin: https://kiln.glassworks.dev' …`:

| URL | Status | `Access-Control-Allow-Origin` |
|---|---|---|
| `download.lineageos.org/api/v2/devices/tangorpro/builds` | 200, JSON | `https://kiln.glassworks.dev` (it echoes any origin: `https://example.org` got the same) |
| `download.lineageos.org/api/v2/devices/tangorpro`, `/api/v2/oems`, `/api/v1/tangorpro/nightly/<incr>` (the Updater's) | 200 | Echoed, as above |
| `mirrorbits.lineageos.org/full/tangorpro/20261118/boot.img` (every file URL in the API) | 302 to a mirror | **None**, and none on an `OPTIONS` preflight (also a 302) |
| The mirrors it lists (`?mirrorlist`): `mirrors.ocf.berkeley.edu`, `ftp.fau.de`, `mirrors.dotsrc.org`, `mirror.selfnet.de`, `lineageos.mirror.karneval.cz`, `lineageos.tetaneutral.net`, `mirror.accum.se` | 206 to a Range request | **None** on any of them. `mirror.math.princeton.edu` failed TLS from here |

**So the API is open to Kiln, and the files aren't.** A browser fetch of a build stops at the first
redirect. The API gives everything Kiln needs to check a file: for each build, every file's name, size,
`sha256` and `sha1`, the OS patch level and SDK level. The Pixel Tablet's 2026-11-18 build lists the
zip (1,474,014,620 bytes) and `boot`, `dtbo`, `init_boot`, `super_empty`, `vbmeta`, `vendor_boot`,
`vendor_kernel_boot` images. The Tab S6 Lite's lists `recovery.img`.

### So the person downloads, and Kiln checks

1. Kiln reads the build list from the API, then shows the files this device's method needs: the zip,
   the recovery image (`<recovery_partition_name>.img`), the boot-stack partitions, `vbmeta.img` for
   `samsung_qcom`. Each one gets its size and a download link to LineageOS's own URL.
2. The person downloads them and picks them in Kiln (`showOpenFilePicker`, several at once).
3. Kiln hashes each file as it reads it (`@glassworks/ext4`'s streaming `Sha256`, already used for
   the Mirror's 850 MB image) and compares it to the API's `sha256`. A file that doesn't match is
   refused before anything is written, and Kiln says which file it expected.
4. The file handles go to IndexedDB, so a reload resumes after one permission click. The engine rule
   is resume after a reload, and a picked `File` doesn't outlive the page.

This is a deliberate exception to #579's "no download-and-select". That rule is for files Kiln can
serve, and these Kiln can't ([decision 2](#decisions-for-the-maintainer)).

**We never host LineageOS** (hard rules 3 and 4), and Refract won't carry it either. Refract stores
nothing, but it serves the bytes from our domain, and that's distribution all the same. A build
contains a GPL-2.0 kernel and each device vendor's proprietary blobs, whose terms we can't check per
device. [lineageos.org/legal](https://lineageos.org/legal/) (read 2026-11-23) has a disclaimer and
privacy terms but no redistribution terms, and its mirror network runs on donated bandwidth. **Refract
only with LineageOS's written OK**, and the better ask is the one that needs no proxy: CORS headers on
`mirrorbits.lineageos.org` and its mirrors. Every mirror would need them, since mirrorbits picks one by
location.

## 4. The flow, step by step

Each step says what it writes and how to undo it. **Two things have no undo at all: the wipe, and
Samsung's Knox flag.** Kiln says both before the first write ([§5](#5-kiln-copy)). Kiln never sends
`flashing lock` or `oem lock`: relocking with LineageOS on the device can leave it unable to boot.

### Every family: before the first write

| # | Step | Writes | Undo |
|---|---|---|---|
| 0 | The person reads the page's ratings and the "Before you start" sheet | Nothing | — |
| 1 | Connect over USB debugging. Read the props, match the device, check the API has a build | Nothing | — |
| 2 | Check the firmware against `before_install` (Android version, or the variant's build). Stop if Kiln can't establish it. For the Android-version form, the person runs the device's system update and confirms it's up to date (a declared check step) | Nothing | — |
| 3 | Pick the downloaded files, then check sizes and hashes | Nothing on the device | — |

### fastboot families

| # | Step | Writes | Undo |
|---|---|---|---|
| 4 | `adb reboot bootloader` (engine rule 5: no buttons while adb works) | Nothing | — |
| 5 | **Unlock.** `kiln`: read `getvar unlocked`; if it's no, send the method's command (`fastboot flashing unlock`, or `custom_unlock_cmd` from the data) and the person confirms on the device with its keys (a declared tap). `vendor`: if `unlocked` is no, Kiln stops and links the vendor's unlock page, and nothing is written. `done`: skip | **Wipes userdata.** Unlocking erases the device | **No undo for the data.** The lock state comes back only with the vendor's firmware (for Pixels, Google's Android Flash Tool) |
| 6 | Flash the boot stack from the data (`boot_stack`: e.g. `boot`, `dtbo`, `vendor_kernel_boot`), then the recovery: **`fastboot boot` from RAM when the recovery is the `boot` image**, else `fastboot flash <recovery_partition_name>` as the wiki does (`vendor_boot` devices like `tangorpro`). On A/B devices, both slots where the wiki says so | Boot partitions | Vendor firmware. **No read-back**: production bootloaders don't read partitions. The proof is the hash before the write and recovery booting with `ro.lineage.device` set. **The RAM boot is untested** on a device whose recovery lives in `boot`; the spike decides |
| 7 | **After a RAM boot: skip.** The device is already starting recovery, and Kiln waits for it (engine rule 3). **After a flash**, by `recoveryReboot`: `fastboot_reboot` sends `fastboot reboot recovery`; `fastboot_menu` is a declared tap on the bootloader's menu; `fastboot_boot` boots the recovery image from RAM; none shows the `recovery_boot` keys. `fastboot_misc` writes `misc`, so that device is "not yet" | Nothing | — |
| 8 | In recovery: **Factory reset → Format data** (a declared tap, keys or touch) | **Wipes userdata again** | None needed: the device was already wiped at step 5 |
| 8b | `beforeLineage: ab_copy_partitions` only: **Apply update → Apply from ADB** (a tap), sideload `copy-partitions-<date>-signed.zip`, then **Advanced → Reboot to recovery** (a tap). The file's name and SHA-256 are pinned in `methods.json`, refreshed with the snapshot. The person downloads it, since its mirrors send no CORS either, and recovery checks LineageOS's signature on it. `m1s1` is "not yet" | The inactive slot's firmware partitions, copied from the active slot | Vendor firmware. Nothing of the device's own is lost: the active slot is the source |
| 9 | **Apply update → Apply from ADB** (a tap), then Kiln sideloads the zip (`sideload-host`). Recovery checks LineageOS's signature | `system`, `vendor`, `product`, boot (the inactive slot on A/B) | Vendor firmware, or sideload another LineageOS build |
| 10 | A/B only: recovery asks to reboot to recovery for add-ons; the person taps Yes. Then the Glassworks add-on ([below](#the-glassworks-part-every-family)) | `/product/media/bootanimation.zip`, `/system/addon.d/50-glassworks.sh` | The undo add-on (sideloaded the same way) puts LineageOS's animation back and removes the script |
| 11 | Reboot to system. First boot takes several minutes, and Kiln waits through it (engine rule 3) | — | — |

### Samsung Download mode

| # | Step | Writes | Undo |
|---|---|---|---|
| 4 | Into Download mode with the key combination from the data (`download_boot`), shown by the recovery player. Later, through the modem port as samloader-rs does (`reboot_download`, read, untested), once the library has it | Nothing | — |
| 5 | **Unlock**, on the device only: the OEM unlocking switch (step 1 checks it), then hold Volume Up in Download mode ("Device unlock mode"). The device wipes itself and reboots; the person goes through Android setup and turns on Developer options and USB debugging again. Kiln resumes at step 1 | **Wipes userdata. Trips Knox for good** | **No undo for either.** Knox is an e-fuse. Samsung's stock firmware (samloader-rs `download`, or Samsung Smart Switch) restores the software, not the flag |
| 6 | `samsung_qcom`: flash LineageOS's `vbmeta.img` (Odin `VBMETA`). The device wipes itself again, and the person goes through setup again | `vbmeta` | Samsung firmware |
| 7 | Flash the recovery (Odin `RECOVERY`, or `RECOVERY_A`/`RECOVERY_B` on A/B) with no reboot | `recovery` | Samsung firmware. **No read-back**: Odin can't read partitions. It logs the hash |
| 8 | The person forces a reboot into recovery straight away (`recovery_boot` keys), or the stock OS writes its own recovery back over ours | Nothing | — |
| 9–11 | As fastboot's 8–11 | As fastboot | As fastboot |

### The Glassworks part (every family)

| # | Step | Writes | Undo |
|---|---|---|---|
| 12 | The person finishes LineageOS's setup wizard **without adding an account**, which device owner needs. Then Developer options and USB debugging (taps, each with a heads-up) | Settings | Factory reset |
| 13 | The generic Android flow: install Glassworks, grant, **device owner** (`dpm set-device-owner`), home screen. Then the standard flow | `/data` | Kiln's Undo: the app clears its device owner and is removed |
| 14 | The **OS update** entity in Home Assistant ([#745](../../research/lineageos-frame-updates.md)): Glassworks polls the Updater's API and installs the signed zip with `installSystemUpdate` | Later, the OS | The entity's own switch turns updates off |

**The boot animation.** It's our add-on zip, made the LineageOS way, the way Google apps install. It's
an unsigned recovery package (MIT, ours, published as a Glassworks release file through Refract) that
recovery installs right after LineageOS, before the first boot. Recovery says it can't verify the
signature and asks "Install anyway?", which is a declared tap with a heads-up. The zip writes our
`bootanimation.zip` over `/product/media/bootanimation.zip`, which wins over `/oem` and `/system`
(#745, `BootAnimation.cpp`), and writes an `addon.d` script that backs it up and restores it on every
same-major LineageOS update. A major upgrade skips `addon.d` (`backuptool.sh`'s version guard), so the
HA entity offers only same-major updates. The add-on is the open question from #745 ("how does Kiln put
an `addon.d` script in place"), answered through recovery, untested.

**The bind-mount fallback**, for images recovery can't write (EROFS, or `product` read-only), needs an
early-boot hook to mount `/data/…/bootanimation.zip` over the file before `bootanimation` starts. On
plain LineageOS, the only hook that doesn't mean writing the image is a Magisk module, and on a non-A/B
device that module goes away at the first OS update. So the default on such an image is **LineageOS's
own animation**, which Kiln says on Done, and the Magisk route stays a maintainer's call ([decision
5](#decisions-for-the-maintainer)). Which official builds use EROFS isn't in the wiki's data. The spike
finds out on its devices.

## 5. Kiln copy

Calm, short and humane: plain headings with no periods, and no plumbing (no SHA-256, Refract or
partition names in the main copy; those go in the technical log). Every warning shows **before the
first write**, on the page's first screen and again on the confirm screen before step 5.

**The frame's ratings** (`frame.json` `ratings`, on the card and the page):

| Rating | Value | Detail |
|---|---|---|
| `wipes` | yes | "Erases everything on the device" |
| `reversible` | partly | "Back to stock with the maker's own software, which Kiln doesn't have" |
| `modifiesAndroid` | yes | "Replaces Android with LineageOS" |
| `root` | no | |
| `opensDevice` | no | |
| `vendorApps` | removed | "The maker's apps are gone" |
| `backup` | no | "Nothing to back up: the device starts fresh" |
| `updates` | yes | "LineageOS updates, installed from Home Assistant" |
| `difficulty` | medium | "About an hour, a few taps on the device" |

**First screen, "Before you start"** (a block under the heading, not a dialog):

> **Install LineageOS, then Glassworks**
> Your Pixel Tablet gets a fresh LineageOS with Glassworks on top. LineageOS keeps it up to date, and you
> install its updates from Home Assistant.
>
> - **This erases everything on the tablet.** Photos, apps and accounts go. Back up what you want to keep.
> - **Going back needs Google's own software.** Google's Android Flash Tool puts stock Android back, and erases the tablet again.
> - **LineageOS is made by the LineageOS project.** Questions about LineageOS go to its wiki.

On Samsung, the second line becomes **"Going back needs Samsung's own software"**, followed by one more:

> - **This trips Samsung's Knox flag, and that can't be undone.** Samsung Pay, Secure Folder and some
>   work apps stop working for good, even after going back. Samsung may treat the tablet as out of warranty.

For a `vendor` unlock:

> - **Unlock it with \<Xiaomi\> first.** \<Xiaomi\> makes you ask for an unlock. *How to unlock* (the vendor's page). Kiln
>   carries on once it's unlocked.

**The firmware check** (step 2, Android-version form):

> **Update the tablet first**
> LineageOS needs the newest Android 17 update on it. On the tablet, open Settings, then System update,
> and install updates until it says it's up to date.
> It's on the update from November 1, 2026. [It's up to date]

**Confirm before the first write** (step 5):

> **Erase and unlock the tablet**
> Last chance to back up. The tablet asks you to confirm on its own screen too.
> [Erase and unlock] · Not now

**Files:**

> **Get LineageOS for your Pixel Tablet**
> Download these from LineageOS, then choose them here. Kiln checks each one before it writes anything.
>
> Wrong file: **That's not the file LineageOS published for this tablet.** Nothing was written. Download
> it again, then choose it.

**Taps on the device** (a heads-up for each, the same as Android's dialogs today):

> **On the tablet: Factory reset, then Format data**
> Use the volume keys to move and the power key to select if touch doesn't work.

> **On the tablet: Apply update, then Apply from ADB**

> **The tablet can't check Glassworks' add-on. Choose Yes**
> LineageOS only vouches for its own packages, and this one comes from Glassworks. Kiln checked it before
> sending it.

**Done**, in "The way back" block:

> To go back to stock Android, use Google's Android Flash Tool. It erases the tablet again.

The device names, the vendor and its tool come from the frame's `words` and `methods.json`. Kiln's
sources name none of them.

## 6. The spike (piece 1 of #748), by risk

Each step needs a spare device that LineageOS supports. **The maintainer's Pixel Tablet is in daily
use, so installing LineageOS on it is the maintainer's call.** It isn't needed for any step below. Good
spare devices, cheap used and maintained on 24.0 or 23.2:

- **fastboot:** a Pixel 4a (`sunfish`), 5a (`barbet`) or 6a (`bluejay`), all `fastboot_nexus`. The 4a is
  the best pick: its recovery is the `boot` image, so S4 runs on it too (the 5a and 6a use `vendor_boot`).
- **Samsung:** a Galaxy Tab A7 10.4 Wi-Fi (SM-T500, `gta4lwifi`, `samsung_qcom`, 24.0) or a Tab S6
  Lite Wi-Fi (SM-P610, `gta4xlwifi`, `samsung_exynos9xxx`, no `vbmeta` step, 23.2).

| # | Risk | What | Device | Writes |
|---|---|---|---|---|
| S0 | — | Device data, the download API, CORS (this note) | None | Nothing. **Done 2026-11-23** |
| S1 | **Highest: can the browser reach Download mode at all?** macOS, Windows and Linux each bind their own CDC-ACM driver to it, so WebUSB may not be able to claim the interface. Web Serial goes through the OS driver | `@glassworks/odin` (#777) on Chrome on macOS, Windows and Linux: detect, `DVIF`, PIT dump over WebUSB. Where WebUSB can't claim the interface, samloader-rs's web build over Web Serial shows whether Web Serial is the way | Any Samsung phone or tablet, even one LineageOS doesn't support | Nothing. A PIT read reboots the device, which is harmless |
| S2 | High: `adb sideload` from the browser, 1.5 GB, odd endings (the wiki's "47%, `failed to read command: Success`" note) | The sideload library against LineageOS Recovery: sideload the device's current build again | The spare Pixel, already on LineageOS (S3 can go first) | Rewrites the OS with the same build |
| S3 | Medium | The whole fastboot family in Kiln, then Glassworks as device owner | The spare Pixel | Everything. Way back: Google's Android Flash Tool |
| S4 | Medium | `fastboot boot` of recovery from RAM, where the recovery is the `boot` image | The Pixel 4a (`recovery_partition_name: boot`) | Nothing (RAM) |
| S5 | Medium | The whole Samsung family: unlock, (vbmeta), recovery over Odin, sideload | The spare Galaxy tablet | Everything. **Trips Knox** |
| S6 | Medium | The add-on zip: our animation and `addon.d` script in recovery, then a monthly update through `installSystemUpdate` (#745's steps 1–3), and check the animation survives. Whether the build is EROFS | S3's device, then S5's | `/product`, `/system` |
| S7 | Low | Matching on many real devices: the props of every Android device we have, through `match()`, with no writes | Any Android devices at hand, S1's Samsung | Nothing |

S1 and S7 need no unlock and no wipe, so they can run on any device at hand. S2–S6 need the spare
devices.

## 7. Libraries

Two other sessions are building the first two now. The shapes below are what the frame's code calls.
Names can change; the seams shouldn't.

### adb sideload (`@glassworks/adb`)

As built in the sideload session (its PR is open in parallel):

```ts
type AdbMode = 'device' | 'recovery' | 'sideload' | 'rescue' | 'bootloader';

interface Panel {
  /** The banner's first field (`device::`, `recovery::`, `sideload::`…); undefined if unknown. */
  readonly mode: AdbMode | undefined;
  /**
   * `adb sideload`: serves `source` to recovery's `sideload-host:<size>:<block>` service, one 64 KiB
   * block per request from `blob.slice()`, never holding the file in memory. Refuses any mode but
   * `sideload` before it opens anything. `done` is DONEDONE. `closed` means the device closed the
   * stream cleanly after it had blocks (the wiki's "47%, failed to read command" case). It resolves,
   * and neither ending proves the install worked: recovery's screen and the next boot decide.
   * `sent` counts bytes sent, including repeats; a UI shows min(sent / size, 0.99).
   */
  sideload(source: Blob | { size: number; read(offset: number, length: number): Promise<Uint8Array> },
    o?: { onProgress?(p: { block: number; sent: number; size: number }): void; signal?: AbortSignal; stallMs?: number }):
    Promise<{ ended: 'done' | 'closed'; sent: number }>;
}
```

Failures throw `SideloadError` with a `code`: `not-in-sideload-mode`, `closed` (before any block, or
the connection dropped), `out-of-range`, `bad-request`, `stalled` (default 10 minutes; recovery's
"Install anyway?" pauses its requests, so the add-on step needs the long timeout), `failed` (rescue
only), `source`. Recovery's `minadbd` does no adb auth in sideload mode, so any key works. The
contract needs a fake for it: a recovery that asks for blocks out of order, drops at N%, and closes at
47%. Restarting into the bootloader is `PanelShell.rebootToBootloader` today; a `reboot:recovery` beside it
is a small addition.

### Odin, Samsung Download mode (`@glassworks/odin`, PR #777)

Built in parallel as **a TypeScript port of Heimdall (MIT)**, with its library banner in #773. It also
draws on [samloader-rs](https://github.com/topjohnwu/samloader-rs) (Apache-2.0, head `e47c733`), the
tool LineageOS's Samsung guides use, but for protocol facts only, with no code taken: `DVIF`, the
`0xFFFFFFFF` fail status with its signed code, skipping a stale FAIL before `LOKE`, and a best-effort
reboot answer. samloader-rs also builds its own Odin crate to WASM with WebUSB and Web Serial backends.
It's the second opinion when spike S1 misbehaves. The package README lists where samloader-rs and
Heimdall differ on the wire (zero-length packets only after commands on "Gadget Serial" devices, sequences
rounded to 128 KiB, protocol version 5 against 4), and those are the first things to check.

```ts
/** WebUSB today; Web Serial is the follow-up for systems whose CDC-ACM driver holds Download mode (spike S1). */
interface OdinTransport { send(data: Uint8Array): Promise<void>; /* empty = ZLP */ receive(): Promise<Uint8Array>; close(): Promise<void> }

class OdinDevice {
  /** Only the partitions named in `writable` can ever be flashed; VBMETA is opt-in (samsung_qcom). */
  static open(usb: USBDevice, o?: { writable?: readonly ('RECOVERY' | 'VBMETA')[] }): Promise<OdinDevice>;
  /** DVIF, before the handshake. The parser drops the serial (`UN`). */
  info(): Promise<{ model?: string; salesCode?: string; bootloaderVersion?: string; properties: Readonly<Record<string, string>> }>;
  beginSession(o?: { totalBytes?: number; resume?: boolean }): Promise<void>;
  deviceType(): Promise<number>;
  /** The partition table, which Kiln flashes by (RECOVERY, VBMETA, RECOVERY_A…). */
  pit(): Promise<Pit>;
  /**
   * Streams one image to one partition. It refuses before sending anything unless the partition is in
   * `writable`, `entry` is this session's own PIT entry and the only one with that name, the entry is AP
   * on eMMC or UFS with a block count, the image fits, and `totalBytes` covers it. Odin can't read back.
   */
  flash(o: { partition: 'RECOVERY' | 'VBMETA'; entry: PitEntry; image: Blob | Uint8Array;
    onProgress?(p: { sentBytes: number; totalBytes: number }): void; signal?: AbortSignal }): Promise<void>;
  /** `reboot: false` leaves the device in Download mode, as the wiki's `--no-reboot` does. */
  end(o?: { reboot?: boolean }): Promise<void>;
  close(): Promise<void>;
}
```

Not in it yet: asking a running device into Download mode through its modem port (samloader-rs's
`reboot_download`). Until it's added, the flow uses the key combination from the data. Also missing is
`RECOVERY_A`/`RECOVERY_B` for A/B Samsung devices. Only one maintained Samsung file is A/B today (`a55x`,
a phone), so it waits until that device is in scope.

### LineageOS device data (`frames/lineageos/installer/src/devices.ts`)

This is frame data and stays out of `kiln/packages`, since only this frame reads it.

```ts
interface LineageDevice {
  codename: string; variant?: number; name: string; vendor: string;
  type: 'tablet' | 'phone' | 'other'; models: string[];
  method: string; family: 'fastboot' | 'samsung'; unlock: 'kiln' | 'done' | 'vendor' | 'device';
  ab: boolean; recoveryPartition: 'boot' | 'recovery' | 'vendor_boot';
  /** `before_recovery_install`, from a closed set; the generator fails on a value it doesn't know. */
  beforeRecovery: 'none' | 'boot_stack' | 'samsung_qcom' | 'samsung_exynos9xxx' | 'samsung_a21s'
    | 'samsung_universal7904' | 'g12' | 'nubia' | 'foster' | 'ellis' | 'TP1803';
  bootStack: string[];
  /** `before_lineage_install`, a closed set like `beforeRecovery`. */
  beforeLineage: 'none' | 'ab_copy_partitions' | 'm1s1';
  /** `recovery_reboot`: how step 7 reaches recovery after a flash; 'keys' when the wiki gives none. */
  recoveryReboot: 'fastboot_menu' | 'fastboot_reboot' | 'fastboot_boot' | 'fastboot_misc' | 'keys';
  /** `before_install`: an Android version (its latest update), or one firmware build per regional variant. */
  firmware?: { android: string; latestUpdate: true; shipsFw: boolean } | { variants: { name: string; build: string }[] };
  keys: { download?: string[]; recovery?: string[] }; branch: string;
}
/** devices.json: { source: { repo, commit, date }, license: 'CC-BY-SA-3.0', devices: LineageDevice[] } */
function match(seen: { lineage?: string; device?: string; vendorDevice?: string; product?: string;
  model?: string; fastbootProduct?: string; odinModel?: string }): LineageDevice[];
/** The download API, live: the newest build and its files with size and sha256. */
function builds(codename: string, fetch?: typeof globalThis.fetch): Promise<Build[]>;
```

The recipe dispatches its pre-recovery steps on `beforeRecovery`, step 8b on `beforeLineage` and step 7 on
`recoveryReboot`. A value the recipe has no steps for yet
(the one-device ones at first) marks the device "not yet" in Kiln, before anything is written.
Only maintained devices in the `fastboot` (not `closed`) and `samsung` (not `closed`) families go in
`devices.json`. The script's counts keep the rest.

## Decisions for the maintainer

1. **Where it lives in Kiln:** its own frame and route, `/lineageos` (recommended), with the quiet
   line on `/android`. Or only a choice on `/android`.
2. **Download and pick:** accept it as the scoped exception to #579 for files Kiln can't serve
   (recommended), and ask LineageOS for CORS on mirrorbits and its mirrors.
3. **Odin library, after spike S1:** #777 is a TypeScript port of Heimdall (MIT) over WebUSB. If S1
   shows WebUSB can't claim Download mode on macOS or Windows, either add Web Serial to it, or use
   samloader-rs's WASM, which already has Web Serial (Apache-2.0, and its notice goes in
   `THIRD-PARTY-NOTICES.md`).
4. **The wiki snapshot's licence:** commit `devices.json` with its CC BY-SA 3.0 notice (and the
   licence text in `LICENSES/`), or fetch it at build time without committing it. The bundle carries
   it either way.
5. **Read-only images:** keep LineageOS's animation there (recommended), or offer a Magisk module for
   our animation, accepting that it's lost on non-A/B updates.
6. **Device classes:** tablets and phones both, or tablets first. The counts allow either: 21 tablet
   files can be reached, against 330+ phone files.
7. **The vendor-unlock families** (216 maintained files, mostly Xiaomi and Motorola): in from the
   start, with Kiln stopping at `getvar unlocked` until the person has unlocked, or later.
8. **The Pixel Tablet:** whether it's ever a test device for this. Nothing in the spike needs it.

## Sources

- LineageOS wiki: [`_data/devices`](https://github.com/LineageOS/lineage_wiki/tree/main/_data/devices),
  [`test/schema-06.yml`](https://github.com/LineageOS/lineage_wiki/blob/main/test/schema-06.yml),
  [`_includes/templates`](https://github.com/LineageOS/lineage_wiki/tree/main/_includes/templates)
  (`device_install.md`, `recovery_install_*.md`, `device_specific/before_recovery_install_*.md`), read at `67a8877`.
- Download API: `https://download.lineageos.org/api/v2/devices/<codename>/builds`, `/api/v2/oems`.
- samloader-rs: [`odin/src/device_info.rs`](https://github.com/topjohnwu/samloader-rs/blob/main/odin/src/device_info.rs),
  [`odin/src/modem.rs`](https://github.com/topjohnwu/samloader-rs/blob/main/odin/src/modem.rs),
  [`web/`](https://github.com/topjohnwu/samloader-rs/tree/main/web), read at `e47c733`.
- [#745's note](../../research/lineageos-frame-updates.md): the Updater's API, `installSystemUpdate`,
  the boot animation path and `addon.d`.
