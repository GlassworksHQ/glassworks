// flash-node.ts: Kiln's Mirror install (option A, #40), headless, for the first real flash.
//
// THIS WRITES THE MIRROR'S system AND userdata PARTITIONS. lululemon's software can't be read back
// first, so it can't be restored: the way back is the plain Glass Panes image (--way-back).
//
// Same packages as Kiln's page: @glassworks/lululemon-mirror-installer (load and check, patch,
// build, fastboot checks, first boot), @glassworks/ext4, @glassworks/fastboot and app-adb. Only the
// transports differ: fastboot through Node's WebUSB (the `usb` package), adb through the host's adb
// server (`adb -s <serial>`, the serial read from /tmp/mirror-serial and never printed). The new
// userdata carries the host's adb public key, so the host's adb can run the first boot.
//
// Each step is printed and waits for "y" (--yes skips the questions). --dry-run runs every step on
// a fake Mirror (FakeLk and the installer's adb fakes) and touches no device. The code is in src/cli.ts.
// tsx isn't a dependency of the repo (vite 8 lists it as an optional peer, which breaks npm ci's lockfile
// check): npx fetches the pinned version on the computer that runs this.
//
//   npx -y tsx@4.23.15 tools/mirror/flash-node.ts --dry-run
//   npx -y tsx@4.23.15 tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk s1-kiosk-legacy.apk --ha-url http://homeassistant.local:8123
//   npx -y tsx@4.23.15 tools/mirror/flash-node.ts --way-back --image system.img --userdata userdata.sparse
import { UsageError, cli } from './src/cli';

cli(process.argv.slice(2)).then((code) => process.exit(code), (e: unknown) => {
  console.error(`\nflash-node: ${(e as Error).message}`);
  process.exit(e instanceof UsageError ? 2 : 1);
});
