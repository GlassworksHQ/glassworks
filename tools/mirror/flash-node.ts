#!/usr/bin/env -S npx tsx
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
// a fake Mirror (FakeLk and the installer's adb fakes) and touches no device.
//
//   npx tsx tools/mirror/flash-node.ts --dry-run
//   npx tsx tools/mirror/flash-node.ts --image ~/Downloads/system.img --apk s1-kiosk-legacy.apk --ha-url http://homeassistant.local:8123
//   npx tsx tools/mirror/flash-node.ts --way-back --image system.img --userdata userdata.sparse
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { fakeApk } from '@glassworks/app-adb/fakes';
import { Fastboot } from '@glassworks/fastboot';
import { IMAGE_PROXY, MIRROR_FASTBOOT, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { hostAdb, readSerial } from './src/adb';
import { DRY_USERDATA, dryDevices, dryImage } from './src/dry';
import { runInstall, runWayBack, type RunDeps } from './src/run';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const HELP = `usage: flash-node.ts [--dry-run] [--yes] (install | --way-back)

install (WRITES system and userdata):
  --image <system.img>     the Glass Panes system.img (checked by size and SHA-256)
  --refract                download it through Refract instead (${IMAGE_PROXY.base})
  --apk <file>             S1 Kiosk's legacy APK (minSdk 23, arm64-v8a or armeabi-v7a)
  --adb-key <file>         a public key for the new userdata's adb_keys; repeat for more
                           (default: ~/.android/adbkey.pub, the host's)
  --ha-url <url>           Home Assistant's address for S1 Kiosk
  --wifi-ssid <name> --wifi-pass-file <file>   Wi-Fi for S1 Kiosk (the password from a file)
  --boot-animation <zip>   default: branding/lululemon-mirror/bootanimation.zip; --no-boot-animation
  --no-fill                zeros as RAW blocks, for an LK that refuses FILL chunks
  --screenshot <png>       default: ./mirror-after.png
way back (WRITES system and userdata):
  --way-back --image <system.img> --userdata <userdata.sparse>
both:
  --serial-file <file>     default: /tmp/mirror-serial
  --work <dir>             where the 2.5 GB of images go (default: a new folder in ${tmpdir()}, removed after)
  --keep                   keep the work folder`;

const { values: a } = parseArgs({
  options: {
    'dry-run': { type: 'boolean' }, yes: { type: 'boolean' }, 'way-back': { type: 'boolean' }, help: { type: 'boolean' },
    image: { type: 'string' }, refract: { type: 'boolean' }, apk: { type: 'string' }, 'adb-key': { type: 'string', multiple: true },
    'ha-url': { type: 'string' }, 'wifi-ssid': { type: 'string' }, 'wifi-pass-file': { type: 'string' },
    'boot-animation': { type: 'string' }, 'no-boot-animation': { type: 'boolean' }, 'no-fill': { type: 'boolean' },
    screenshot: { type: 'string' }, userdata: { type: 'string' }, 'serial-file': { type: 'string' }, work: { type: 'string' }, keep: { type: 'boolean' },
  },
});

const log = (l: string) => console.log(l);
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
const fail = (m: string): never => { console.error(`flash-node: ${m}`); process.exit(2); };

async function confirmer(yes: boolean) {
  if (yes) return async () => true;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  process.on('exit', () => rl.close());
  return async (q: string) => /^y(es)?$/i.test((await rl.question(`    ${q} [y/N] `)).trim());
}

/** The Mirror's LK over Node's WebUSB: waits for exactly one 18d1:d00d device. */
async function nodeFastboot(): Promise<FastbootLike> {
  let usb: typeof import('usb');
  try { usb = await import('usb'); } catch { return fail('the `usb` package is missing: run `npm install` at the repo root (it is optional, and failed to build?)'); }
  const webusb = new usb.WebUSB({ allowAllDevices: true });
  for (let i = 0; i < 90; i++) {
    const found = (await webusb.getDevices()).filter((d) => d.vendorId === MIRROR_FASTBOOT.vendorId && d.productId === MIRROR_FASTBOOT.productId);
    if (found.length > 1) fail(`${found.length} devices answer as 18d1:d00d: unplug every other fastboot device`);
    if (found.length === 1) {
      const fb = await Fastboot.fromDevice(found[0]!);
      fb.log = (l) => log(`    LK: ${l}`);
      return fb;
    }
    if (i === 0) log('    waiting for the Mirror in fastboot (18d1:d00d)…');
    await sleep(2000);
  }
  return fail('no fastboot device 18d1:d00d in 3 minutes: is the Mirror in fastboot, on this USB cable?');
}

async function realDevices(confirm: RunDeps['confirm']): Promise<RunDeps> {
  const serial = readSerial(a['serial-file']);
  const adb = hostAdb(serial);
  const state = async () => String(await adb.adb(['get-state'], { timeoutMs: 10_000 }).catch(() => '')).trim();
  return {
    log, confirm, sleep,
    adb: async () => ((await state()) === 'device' ? adb : undefined),
    rebootToBootloader: async () => { await adb.adb(['reboot', 'bootloader']); },
    fastboot: nodeFastboot,
    async waitForAdb() {
      for (let i = 0; i < 180; i++) {
        const s = await state();
        if (s === 'device') return adb;
        if (i % 15 === 0) log(`    waiting for adb (${s || 'not seen yet'})…`);
        await sleep(2000);
      }
      throw new Error('the Mirror didn\'t come up on adb in 6 minutes. If `adb devices` shows it as unauthorized, the adb key wasn\'t the one written to userdata; if it shows another serial, put that in the serial file and rerun from --way-back or the first boot');
    },
    async screenshot(file) { writeFileSync(file, await adb.adb(['exec-out', 'screencap', '-p'], { encoding: 'buffer' })); },
  };
}

function adbKeys(): string {
  const files = a['adb-key'] ?? [join(homedir(), '.android/adbkey.pub')];
  return files.map((f) => {
    if (!existsSync(f)) fail(`no adb public key at ${f}`);
    const line = readFileSync(f, 'utf8').trim();
    if (!/^[A-Za-z0-9+/=]{100,}( .*)?$/.test(line)) fail(`${f} isn't an adb public key`);
    return line;
  }).join('\n');
}

async function main() {
  if (a.help) { log(HELP); return; }
  const dry = !!a['dry-run'];
  const work = a.work ?? mkdtempSync(join(tmpdir(), 'gw-mirror-'));
  log(dry ? 'DRY RUN: a fake Mirror; no device is touched.' : 'This WRITES the Mirror\'s system and userdata partitions.');
  try {
    if (a['way-back']) {
      if (dry) {
        const img = await dryImage(work);
        const d = dryDevices(img.bytes, log);
        const ud = join(work, 'dry-userdata.sparse');
        writeFileSync(ud, readFileSync(img.path)); // any checked sparse image does for the dry run
        const want = { ...img.want, name: 'userdata.sparse' };
        await runWayBack({ system: img.path, userdata: ud, systemWant: img.want, userdataWant: want }, d.deps);
      } else {
        if (!a.image || !a.userdata) fail('--way-back needs --image <system.img> and --userdata <userdata.sparse>');
        await runWayBack({ system: a.image!, userdata: a.userdata! }, await realDevices(await confirmer(!!a.yes)));
      }
      log('\nDone: the plain Glass Panes image is on the Mirror.');
      return;
    }

    const apk = a.apk ? new Uint8Array(readFileSync(a.apk)) : dry ? fakeApk('sh.edm.s1kiosk', { minSdk: 23, libs: { 'arm64-v8a': { 'libdry.so': new Uint8Array(16) } } }) : fail('--apk <S1 Kiosk legacy APK> is needed');
    const animPath = a['boot-animation'] ?? join(REPO, 'branding/lululemon-mirror/bootanimation.zip');
    const bootAnimation = a['no-boot-animation'] ? undefined : new Uint8Array(readFileSync(animPath));
    const wifi = a['wifi-ssid'] ? { ssid: a['wifi-ssid'], password: a['wifi-pass-file'] ? readFileSync(a['wifi-pass-file'], 'utf8').replace(/\r?\n$/, '') : fail('--wifi-ssid needs --wifi-pass-file') } : undefined;
    const common = { apk, bootAnimation, haUrl: a['ha-url'], wifi, work, fill: !a['no-fill'] };
    let r;
    if (dry) {
      const img = await dryImage(work, apk.length + (bootAnimation?.length ?? 0));
      const d = dryDevices(img.bytes, log);
      r = await runInstall({ ...common, image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, adbKey: 'QAAAAdryrun= dry@run', screenshot: join(work, 'after.png') }, d.deps);
      log(`\nDry run: LK got ${d.lk.commands.filter((c) => c.startsWith('flash:')).join(', ')}, then reboot; adb ran ${d.after.cmds.length} commands on the first boot.`);
    } else {
      if (!a.image && !a.refract) fail('--image <system.img> or --refract is needed');
      const image = a.image ? { path: a.image } : { url: `${IMAGE_PROXY.base}/${IMAGE_PROXY.systemImg}` };
      r = await runInstall({ ...common, image, adbKey: adbKeys(), screenshot: a.screenshot ?? resolve('mirror-after.png') }, await realDevices(await confirmer(!!a.yes)));
    }
    const fb = r.firstBoot;
    log(`\nDone: S1 Kiosk ${fb?.app.name ?? fb?.app.code}, home ${fb?.home.result}${fb?.wifi === undefined ? '' : `, Wi-Fi ${fb.wifi ? 'joined' : 'not confirmed'}`}, rotation ${fb?.rotation ?? 'unset'}${r.screenshot ? `, screenshot ${r.screenshot}` : ''}.`);
    if (fb?.home.result !== 'set') process.exitCode = 1;
  } finally {
    if (a.keep || a.work) log(`work folder kept: ${work}`);
    else rmSync(work, { recursive: true, force: true });
  }
}

main().then(() => process.exit(process.exitCode ?? 0), (e: unknown) => { console.error(`\nflash-node: ${(e as Error).message}`); process.exit(1); });
