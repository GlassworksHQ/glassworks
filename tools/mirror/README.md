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

1. Checks over adb, read-only: the board must be `msm8916`.
2. The warning above.
3. Load `system.img` (a local file, or `--refract`), and check its size and SHA-256.
4. `adb reboot bootloader`.
5. Fastboot checks, read-only: partition sizes and `max-download-size`.
6. Build the images: S1 Kiosk as a priv-app, the boot animation, `ro.sf.hwrotation=270`, and a
   fresh userdata whose `adb_keys` holds this computer's `~/.android/adbkey.pub`.
7. Flash system. 8. Flash userdata. 9. Reboot.
10. Wait for adb and `sys.boot_completed`.
11. First boot: permissions, Home Assistant's address, Wi-Fi, the other launchers hidden.
12. HOME check (hides the launchers again if HOME didn't open S1 Kiosk).
13. Screenshot.

## Use

From the repo root, after `npm install`. tsx isn't in the repo's lockfile (vite 8 lists it as an
optional peer, and that breaks `npm ci`'s lockfile check), so npx fetches the pinned version:

```sh
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --dry-run          # every step on a fake Mirror, no device touched
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk s1-kiosk-legacy.apk \
  --ha-url http://homeassistant.local:8123
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --way-back --image system.img --userdata userdata.sparse
npx -y tsx@4.23.15 tools/mirror/flash-node.ts --help
```

The adb serial is read from `/tmp/mirror-serial` (`--serial-file`) and is shown as `<serial>` in
logs. The tool never restarts or kills the adb server. The images take about 2.5 GB in a temporary
folder, which is removed at the end unless `--keep` or `--work` is given.

The tests (`npm test -w @glassworks/mirror-root-run`) run the install and the way back on the fake
Mirror: `@glassworks/fastboot`'s `Fastboot` on `FakeLk`, the installer's adb fakes, and e2fsck on
what lands on the fake partitions.
