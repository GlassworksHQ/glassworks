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
   (an enabled activity or alias with MAIN, HOME and DEFAULT in one intent filter). The first
   `--adb-key` must be an adb key and not the published Glass Panes project key. Then over adb the
   board must be `msm8916`.
2. The warning above.
3. Load `system.img` (a local file, or `--refract`), and check its size and SHA-256.
4. `adb reboot bootloader`.
5. Fastboot checks, read-only: partition sizes and `max-download-size`.
6. Build the images: the app as a priv-app (`/priv-app/Glassworks`, the probe in `/priv-app/S1Kiosk`), the boot animation, the
   settings overlay and `ro.lockscreen.disable.default` (a first boot with nothing to tap),
   `/framework/services.jar` letting in the first `--adb-key` (default `~/.android/adbkey.pub`) in
   place of the Glass Panes project key, and a fresh userdata with the keys in `adb_keys` and, with
   `--wifi-ssid`, the saved network. This image's adbd ignores `adb_keys`: the key gets in through
   services.jar ([image-patch.md](../../frames/lululemon-mirror/research/image-patch.md#who-the-image-lets-in-over-adb)).
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

The Mirror lets in only the first `--adb-key`, and adb's server answers with the key in its
`HOME`'s `.android/adbkey`. To flash with a key other than this computer's (a throwaway one for a
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
   (PBKDF2 PSK only).
4. **Flash `userdata` ONLY & reboot:** prompts with a warning (wipes stock account/onboarding data;
   leaves stock `system` untouched), then flashes `userdata` and reboots.
5. **Wait for stock ADB:** polls USB (and optional `--tcp-host <ip>`) and records whether stock
   returns `device`, `unauthorized` (expected on `1.2x+` / `S22Q401` boot builds), or `not-seen`.
6. **Extract stock info (read-only over ADB):** saves raw files locally in `<out>/raw/` (outside the
   repo — never commit them) and writes redacted findings into `<out>/report.md`: build/hardware
   properties, `init.svc.*`, `/proc/config.gz` flags, `dmesg` highlights, display/LVDS device-tree
   nodes, key `/system` hashes, and an Option C comparison against `option-c-vendor-files.tsv`.

```sh
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --dry-run        # all 6 steps on a fake stock Mirror
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --read-only      # Steps 1-2 only (no flash)
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts                  # full 6-step guided run
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --extract-only   # Steps 5-6 only (already on stock adb)
npx -y tsx@4.23.15 tools/mirror/stock-probe.ts --help
```
