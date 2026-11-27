# Mirror install from a computer

`flash-node.ts` runs Kiln's Mirror install (option A, #40) without a browser tab. It uses the same
packages as Kiln's page (`@glassworks/lululemon-mirror-installer`, `@glassworks/ext4`,
`@glassworks/fastboot`, `@glassworks/app-adb`). Only the transports differ: fastboot goes through
Node's WebUSB (the `usb` package), and adb goes through the computer's adb server (`adb -s <serial>`).

**It writes the Mirror's system and userdata partitions.** The Mirror's bootloader can't read
anything back, so lululemon's software can't be backed up or restored. The way back is the plain
Glass Panes image (`--way-back`).

## Steps

Each step is printed and waits for `y`. `--yes` answers every question.

1. Checks, read-only: the APK must be the legacy build of Glassworks for generic Android
   (`dev.glassworks.generic_android.legacy`, or the probe with `--probe`) for Android 6, ask for
   `INSTALL_PACKAGES`, `REBOOT` and `WRITE_SECURE_SETTINGS`, and have a home screen Android can open
   (an enabled activity or alias with MAIN, HOME and DEFAULT in one intent filter). Every
   `--adb-key` line must be an adb key, and none the published Glass Panes project key. Then over adb the
   board must be `msm8916`.
2. The warning above.
3. Load `system.img` (a local file, or `--refract`), and check its size and SHA-256.
4. `adb reboot bootloader`.
5. Fastboot checks, read-only: partition sizes and `max-download-size`.
6. Build the images: the app as a priv-app (`/priv-app/Glassworks`, the probe in `/priv-app/S1Kiosk`), with the
   image's own kiosk app (`/priv-app/MirrorKiosk`) left out. It adds the boot animation, the settings overlay and
   `ro.lockscreen.disable.default` (a first boot with nothing to tap), and `/framework/services.jar` letting in the first
   `--adb-key` (default `~/.android/adbkey.pub`) in place of the key the image names, and a fresh userdata with the
   keys in `adb_keys` and, with `--wifi-ssid`, the saved network. This image's adbd ignores `adb_keys`, but its
   framework lets in a key listed there, as it does the one services.jar names
   ([image-patch.md](../../frames/lululemon-mirror/research/image-patch.md#who-the-image-lets-in-over-adb)).
7. Flash system. 8. Flash userdata. 9. Reboot.
10. Wait for adb and `sys.boot_completed`. `unauthorized` for a while after each start is normal: the
    framework lets the key in once it runs. Still `unauthorized` after 6 minutes means adb's server
    isn't answering with the key the image lets in (below).
11. First boot: permissions, the screen locked upright in portrait (`user_rotation=3`, checked in
    `dumpsys window windows`; it stops if Android doesn't turn within 10 s), Home Assistant's address, Wi-Fi,
    the other launchers hidden.
12. HOME check (hides the launchers again if HOME didn't open the app). If the APK has no home
    screen of its own, nothing was hidden: the image's launcher stays, and the run exits 1.
13. Screenshot.

### After an interrupted install

If the run stops after step 9 (a power cut while it waits for adb, a lost cable), both images are
on the Mirror already. `--first-boot` runs only steps 10 to 13 against it, and writes no partition.
The first boot still checks the image's fingerprint and that the app is there. It needs no `--image`
or `--apk`. `--wifi-ssid` and `--wifi-pass-file` are optional, since the image may have the network
saved already. Pass the same `--probe` and `--ha-url` as the install.

## Use

From the repo root, after `npm install`. tsx isn't in the repo's lockfile (vite 8 lists it as an
optional peer, and that breaks `npm ci`'s lockfile check), so npx fetches the pinned version:

```sh
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --dry-run          # every step on a fake Mirror, no device touched
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk generic-android-legacy.apk \
  --ha-url http://homeassistant.local:8123
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --first-boot --ha-url http://homeassistant.local:8123  # steps 10 to 13 only
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --way-back --image system.img --userdata userdata.sparse
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --help
```

The adb serial is read from `/tmp/mirror-serial` (`--serial-file`) and is shown as `<serial>` in
logs. The tool never restarts or kills the adb server. Each USB transfer gets 120 s: the `usb`
package's default is 1 s, which stopped a real flash with "Cancelled" (2026-11-16). The images take about 2.5 GB in a temporary
folder, which is removed at the end unless `--keep` or `--work` is given.

## A throwaway key

The Mirror lets in every `--adb-key` you give: `services.jar` names the first, and image 3's framework
lets in each key the fresh userdata's `adb_keys` lists. So give only keys you mean to let in. adb's
server answers with the key in its `HOME`'s `.android/adbkey`. To flash with a key other than this computer's (a throwaway one for a
test), give the server that key for the Mirror only. This stops adb for every other device on this
computer until the last command: check no one is using them first.

```sh
T=$(mktemp -d); mkdir "$T/.android"
tools/mirror/probe-app/adbkey.py new "$T/.android"     # adbkey and adbkey.pub; prints only fingerprints
adb reboot bootloader                                   # with the normal server, while it still gets in
adb kill-server
HOME="$T" adb --one-device "$(cat /tmp/mirror-serial)" start-server   # --one-device before start-server: after it, adb ignores it
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk <apk> --adb-key "$T/.android/adbkey.pub"
# … and afterwards, the normal server again:
adb kill-server; adb start-server
```

The harness sees no adb before the flash and takes the Mirror to be in fastboot already. Keep `$T`
while the Mirror runs that image: its key is the only one the Mirror lets in without a screen tap.
Back with the normal server, the Mirror shows as `unauthorized`.

## Tests

The tests (`npm test -w @glassworks/mirror-root-run`) run the install and the way back on the fake
Mirror: `@glassworks/fastboot`'s `Fastboot` on `FakeLk`, the installer's adb fakes, and e2fsck on
what lands on the fake partitions.

## Vendor files for option C

Two read-only scripts for #334 ([option-c.md](../../frames/lululemon-mirror/research/option-c.md)):

- `vendor-files.py` reads an unpacked system image (and, if given, the unit's policy dump) and writes
  the list of vendor files an image needs, with each file's SHA-256, SELinux label and whether adb's
  shell may read it. It touches no device, and it deletes its scratch folder.
  The list in the repo is
  [`option-c-vendor-files.tsv`](../../frames/lululemon-mirror/research/option-c-vendor-files.tsv).
- `vendor-pull-check.sh -s <serial> <list.tsv> [part ...]` reads each file of the list off a running
  Mirror as adb's shell and compares its hash. Nothing is written on the Mirror or kept on the
  computer but the hash. Only the root runs it, through the adb server already bound to the Mirror.

## Camera spike (`camera-spike/`)

A throwaway app that opens the Mirror's camera through Camera1 and Camera2 and logs whether frames arrive and whether
they are blank. It found the camera pane's green stills; see [camera-spike/README.md](camera-spike/README.md) and
[camera-stock.md](../../frames/lululemon-mirror/research/camera-stock.md).

## Stock Mirror probe (`stock-probe.ts`)

`stock-probe.ts` is a guided 6-step tool for inspecting a Mirror that is still running lululemon's
stock firmware. It **never** writes `system`, `boot`, `recovery`, `modem`, or `persist`. After every
step, it updates `<out>/report.md` (a redacted Markdown progress report safe to paste into an issue
or chat) and prints a checkpoint so you can report progress at any stage.

1. **Preflight (read-only):** checks your `--adb-key` (refuses the published Glass Panes project key)
   and checks whether stock already answers on USB adb.
2. **Fastboot checks (read-only):** runs `fastboot oem device-info` and individual `getvar` queries
   (`product`, `variant`, `secure`, `version-bootloader`, `max-download-size`, partition sizes).
   Stops before any write unless `product` is `MSM8916` and `partition-size:userdata` is valid.
3. **Build sparse `userdata` (local file only):** uses `@glassworks/ext4` to build a fresh sparse
   `userdata-probe.sparse` containing `/data/misc/adb/adb_keys`, `/data/property/persist.sys.usb.config`,
   optional `/data/property/persist.adb.tcp.port` (default `5555`), and optional pre-saved Wi-Fi
   (PBKDF2 PSK only). Pass `--with-probe-apk` to also bundle the no-ADB fallback app below on the
   first flash.
4. **Flash `userdata` ONLY & reboot:** prompts with a warning (wipes stock account/onboarding data;
   leaves stock `system` untouched), then flashes `userdata` and reboots.
5. **Wait for stock ADB (with automatic `1.2x+` no-ADB fallback):** polls USB (and optional
   `--tcp-host <ip>`). If ADB authorizes (`device`), it proceeds straight to Step 6. If ADB stays
   `unauthorized` or `not-seen` (expected on `1.2x+` / `S22Q401` boot builds), it offers the
   **no-ADB HTTP probe app fallback**: uses the bundled prebuilt `tools/mirror/stock-probe-app/prebuilt/`
   APK + `libfiemap.so` (or rebuilds from source via `build.sh`; `dev.glassworks.stockprobe`,
   a plain read-only HTTP server + optional `libfiemap.so` JNI), plants `/data/app/dev.glassworks.stockprobe-1/base.apk`
   along with a verified 1-entry `/data/system/packages.xml` (so Android 6 boots it `stopped=false`
   and delivers `BOOT_COMPLETED` automatically without ADB or touch), flashes `userdata` only, and
   connects over HTTP (port `18765`). Without `--wifi-ssid`, stock's own `mirror-<serial>` setup
   SoftAP (`192.168.43.1`) stays untouched and is the easiest transport (first boot after flashing
   fresh `userdata` takes ~4–5 minutes for `dex2oat`; subsequent reboots take ~45 s).
6. **Extract stock info (read-only over ADB or HTTP):** saves raw files locally in `<out>/raw/`
   (outside the repo — never commit them) and writes redacted findings into `<out>/report.md`:
   build/hardware properties, `init.svc.*`, `/proc/config.gz` flags, `dmesg` highlights (ADB-only),
   display/LVDS device-tree nodes, key `/system` hashes, `FS_IOC_FIEMAP` physical block extents
   (via HTTP probe app), and an Option C comparison against `option-c-vendor-files.tsv`.
   Verified live on an untouched stock `1.24.0` Mirror ([stock-firmware-probe.md](../../frames/lululemon-mirror/research/stock-firmware-probe.md)).

```sh
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --dry-run        # all 6 steps on a fake stock Mirror
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --read-only      # Steps 1-2 only (no flash)
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts                  # full 6-step run (auto-falls back if no ADB)
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --with-probe-apk # single userdata flash for 1.2x+ stock
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --extract-only   # Step 1 preflight + Steps 5-6 over ADB (no flash)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-probe.ts --http-only --probe-host 192.168.43.1  # over stock SoftAP (no flash)
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --help
```

## Phase 2: Reversible surgical ADB & optional stock backup (`stock-surgical-adb.ts`)

`stock-surgical-adb.ts` builds on a Mirror that already has the Phase 1 HTTP probe app running on
stock's `mirror-<serial>` SoftAP (`192.168.43.1:18765`). Instead of overwriting `system` with a
third-party image, it backs up the original `/system/framework/services.jar`,
`/system/etc/init.qcom.post_boot.sh`, and (with `--backup`) `/system/vendor/lib64/libdpmframework.so`
plus their `FS_IOC_FIEMAP` block maps first, writes byte-exact
`restore-*.sparse` undo images under `<out>/backup/`, and patches **only** `services.jar`'s
physical 4 KiB blocks (`DONT_CARE` everywhere else) to authorize your own `--adb-key` in
`UsbDebuggingManager.startConfirmation()`.

- **Hard stop-gate:** aborts before any write if `/system/framework/oat/.../services.odex` exists.
- **Also replaces `userdata`:** Step 3 flashes a fresh `userdata` alongside the surgical `system`
  patch (keeping the Phase 1 probe APK as a SoftAP safety net, wiping `dalvik-cache`). `--undo`
  restores the `system` blocks above; it leaves `userdata` as Step 3 left it unless
  `--erase-userdata` is passed, which runs `fastboot erase userdata` for a factory reset — neither
  path brings back whatever was in `userdata` before Step 3 ran.
- **Exact byte size:** recompresses `classes.dex` with `@glassworks/bootpatch`'s `zlib-rs` level-9
  deflate and pads the ZIP EOCD comment so `patched.length === original.length` (zero ext4 metadata
  edits).
- **Optional Step 4 raw backup (`--backup`):** loads the pinned `selinux_permissive` kernel module
  and embeds it in an in-place, same-length patch of `/system/vendor/lib64/libdpmframework.so`.
  `dpmd` runs as root with `CAP_SYS_MODULE` and links that ELF64 library, so its constructor loads
  the module and makes `mmcblk0p22` and `mmcblk0p19` world-readable for one boot. The tool then
  streams raw `system` and `boot` over USB ADB, scrubs those blocks back to the original bytes inside
  the saved image, and flashes `restore-dpmd.sparse`. `init.qcom.post_boot.sh` is not the hook: on
  stock 1.24.0 that shell has no `sys_module` and cannot see `/data/local/tmp`. The 32-bit copy under
  `/system/vendor/lib/` is refused.

  The module is a GPL-2.0 kernel module, so it can't live in this repo (Hard Rule 4). It's published
  with its Corresponding Source in [`GlassworksHQ/glassworks-device-bins`](https://github.com/GlassworksHQ/glassworks-device-bins)
  and consumed here by pinned release URL + SHA-256 (`tools/mirror/device-bins.json`, held to the
  Refract allowlist by `tools/check-device-bins.py`). The published build only loads where its ABI
  matches stock 1.24.0 (`module_layout=0xa5b7f7dd`); for another Mirror revision, build that module
  yourself from the device-bins source and pass it with `--backup-module <path-to-ko>`.

```sh
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --preflight-only                  # Step 1 only (read-only over SoftAP)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --build-only                      # Steps 1-2 locally (no flash)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --keep-adb                        # Steps 1-3: reversible authorized USB ADB (Enforcing)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --backup --keep-adb               # Steps 1-4: pinned module, dpmd hook, raw system+boot, then restore the library
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --backup-only --backup-dir <out>/backup  # Step 4 only over USB (adb forward, no SoftAP swap)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --undo --backup-dir <out>/backup  # Restore untouched stock system (add --erase-userdata for factory reset)
npx --prefer-offline tsx@4.23.15 tools/mirror/stock-surgical-adb.ts --apply-adb --backup-dir <out>/backup  # Re-apply saved USB ADB hook without Wi-Fi AP
```
