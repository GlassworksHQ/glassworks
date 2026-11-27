// flash-node.ts's body (see its header): the arguments, the real devices (Node's WebUSB for fastboot,
// the host's adb), and the dry run. WRITES the Mirror's system and userdata unless --dry-run.
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { Fastboot } from '@glassworks/fastboot';
import { GLASSWORKS, IMAGE_PROXY, MIRROR_FASTBOOT, MIRROR_PROBE, type FastbootLike } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import { hostAdb, readSerial } from './adb';
import { DRY_USERDATA, dryDevices, dryImage, dryRoom } from './dry';
import { runInstall, runWayBack, type InstallResult, type RunDeps } from './run';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
export const HELP = `usage: flash-node.ts [--dry-run] [--yes] (install | --first-boot | --way-back)

install (WRITES system and userdata):
  --image <system.img>     the Glass Panes system.img (checked by size and SHA-256)
  --refract                download it through Refract instead (${IMAGE_PROXY.base})
  --apk <file>             the legacy build of Glassworks for generic Android
                           (dev.glassworks.generic_android.legacy: minSdk 23, arm64-v8a or armeabi-v7a)
  --probe                  the APK is the Mirror probe (tools/mirror/probe-app, dev.glassworks.mirror.probe) instead
  --adb-key <file>         the adb public key the Mirror lets in without asking (services.jar),
                           default ~/.android/adbkey.pub. adb's server must hold its private half:
                           for another key, see "A throwaway key" in tools/mirror/README.md. Repeat
                           for more lines in adb_keys (which this image's adbd ignores)
  --ha-url <url>           Home Assistant's address for the app
  --wifi-ssid <name> --wifi-pass-file <file>   Wi-Fi for the image (the password from a file)
  --boot-animation <zip>   default: branding/lululemon-mirror/bootanimation.zip; --no-boot-animation
  --no-fill                zeros as RAW blocks, for an LK that refuses FILL chunks
  --no-settings-overlay    the image's own first boot: leave out frames/lululemon-mirror/overlay/
                           GlassworksSettings.apk and ro.lockscreen.disable.default (Wi-Fi on, no screen
                           timeout, no lock screen, no auto-rotate)
  --no-framework-overlay   the image's own backlight floor: leave out frames/lululemon-mirror/overlay/
                           GlassworksFramework.apk (config_screenBrightnessSettingMinimum 0: the backlight goes fully off)
  --screenshot <png>       default: ./mirror-after.png
first boot only (writes no partition), for a Mirror already on the image whose install stopped after the flash:
  --first-boot             the install's last steps: wait for adb, the first boot (it checks the image's
                           fingerprint and that the app is there), the HOME check, the screenshot. Takes
                           --probe, --ha-url, --wifi-ssid with --wifi-pass-file (optional: the image may have
                           the network saved already) and --screenshot; needs no --image or --apk
way back (WRITES system and userdata):
  --way-back --image <system.img> --userdata <userdata.sparse>
both:
  --serial-file <file>     default: /tmp/mirror-serial
  --work <dir>             where the 2.5 GB of images go (default: a new folder in ${tmpdir()}, removed after)
  --keep                   keep the work folder`;

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** A wrong or missing argument: exit code 2. */
export class UsageError extends Error {}
const fail = (m: string): never => { throw new UsageError(m); };

function parse(argv: string[]) {
  return parseArgs({
    args: argv,
    options: {
      'dry-run': { type: 'boolean' }, yes: { type: 'boolean' }, 'way-back': { type: 'boolean' }, 'first-boot': { type: 'boolean' }, help: { type: 'boolean' },
      image: { type: 'string' }, refract: { type: 'boolean' }, apk: { type: 'string' }, probe: { type: 'boolean' }, 'adb-key': { type: 'string', multiple: true },
      'ha-url': { type: 'string' }, 'wifi-ssid': { type: 'string' }, 'wifi-pass-file': { type: 'string' },
      'boot-animation': { type: 'string' }, 'no-boot-animation': { type: 'boolean' }, 'no-settings-overlay': { type: 'boolean' }, 'no-framework-overlay': { type: 'boolean' }, 'no-fill': { type: 'boolean' },
      screenshot: { type: 'string' }, userdata: { type: 'string' }, 'serial-file': { type: 'string' }, work: { type: 'string' }, keep: { type: 'boolean' },
    },
  }).values;
}
type Args = ReturnType<typeof parse>;

async function confirmer(yes: boolean) {
  if (yes) return async () => true;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  process.on('exit', () => rl.close());
  return async (q: string) => /^y(es)?$/i.test((await rl.question(`    ${q} [y/N] `)).trim());
}

/** One USB transfer's limit: a piece's download, or LK writing it to eMMC before it answers. */
const TRANSFER_TIMEOUT_MS = 120_000;

/** The Mirror's LK over Node's WebUSB: waits for exactly one 18d1:d00d device. */
async function nodeFastboot(log: (l: string) => void): Promise<FastbootLike> {
  let usb: typeof import('usb');
  try { usb = await import('usb'); } catch { return fail('the `usb` package is missing: run `npm install` at the repo root (it is optional, and failed to build?)'); }
  const webusb = new usb.WebUSB({ allowAllDevices: true });
  for (let i = 0; i < 90; i++) {
    const found = (await webusb.getDevices()).filter((d) => d.vendorId === MIRROR_FASTBOOT.vendorId && d.productId === MIRROR_FASTBOOT.productId);
    if (found.length > 1) fail(`${found.length} devices answer as 18d1:d00d: unplug every other fastboot device`);
    if (found.length === 1) {
      // WebUSB transfers take no timeout, and the usb package gives each one 1 s by default: too short for a
      // 256 MiB download, or for LK writing a piece before it answers (a real run stopped with "Cancelled").
      const dev = found[0]! as USBDevice & { transferIn(ep: number, len: number, timeout?: number): Promise<USBInTransferResult>; transferOut(ep: number, data: BufferSource, timeout?: number): Promise<USBOutTransferResult> };
      const tin = dev.transferIn.bind(dev), tout = dev.transferOut.bind(dev);
      dev.transferIn = (ep: number, len: number) => tin(ep, len, TRANSFER_TIMEOUT_MS);
      dev.transferOut = (ep: number, data: BufferSource) => tout(ep, data, TRANSFER_TIMEOUT_MS);
      const fb = await Fastboot.fromDevice(dev);
      fb.log = (l) => log(`    LK: ${l}`);
      return fb;
    }
    if (i === 0) log('    waiting for the Mirror in fastboot (18d1:d00d)…');
    await sleep(2000);
  }
  return fail('no fastboot device 18d1:d00d in 3 minutes: is the Mirror in fastboot, on this USB cable?');
}

async function realDevices(a: Args, log: (l: string) => void, confirm: RunDeps['confirm']): Promise<RunDeps> {
  const serial = readSerial(a['serial-file']);
  const adb = hostAdb(serial);
  // `adb devices`, not get-state: get-state fails without saying "unauthorized"
  const state = async () => String(await adb.adb(['devices'], { timeoutMs: 10_000 }).catch(() => ''))
    .split('\n').map((l) => l.trim().split(/\s+/)).find(([s]) => s === serial)?.[1] ?? '';
  return {
    log, confirm, sleep,
    adb: async () => ((await state()) === 'device' ? adb : undefined),
    rebootToBootloader: async () => { await adb.adb(['reboot', 'bootloader']); },
    fastboot: () => nodeFastboot(log),
    async waitForAdb() {
      let s = '';
      for (let i = 0; i < 180; i++) {
        s = await state();
        if (s === 'device') return adb;
        if (i % 15 === 0) log(`    waiting for adb (${s || 'not seen yet'})…`);
        await sleep(2000);
      }
      // unauthorized is normal for a while after each start: the framework lets the key in once it runs
      throw new Error(s === 'unauthorized'
        ? 'the Mirror stayed unauthorized for 6 minutes: adb\'s server isn\'t answering with the key the image lets in (the first --adb-key). Restart the server with that key (tools/mirror/README.md, "A throwaway key"), then run only the first boot with --first-boot: no reflash needed'
        : 'the Mirror didn\'t come up on adb in 6 minutes. Once `adb devices` lists it (another serial goes in the serial file), run only the first boot with --first-boot. If it doesn\'t start at all, put the plain image back with --way-back');
    },
    async screenshot(file) { writeFileSync(file, await adb.adb(['exec-out', 'screencap', '-p'], { encoding: 'buffer' })); },
  };
}

function adbKeys(a: Args, log: (l: string) => void): string {
  const home = join(homedir(), '.android/adbkey.pub');
  const files = a['adb-key'] ?? [home];
  const lines = files.map((f) => {
    if (!existsSync(f)) fail(`no adb public key at ${f}`);
    const line = readFileSync(f, 'utf8').trim();
    if (!/^[A-Za-z0-9+/=]{100,}( .*)?$/.test(line)) fail(`${f} isn't an adb public key`);
    return line;
  });
  // the first key is the one the Mirror lets in: adb's server must answer with it
  const first = resolve(files[0]!);
  if (!existsSync(first.replace(/\.pub$/, ''))) log(`    note: no private key next to ${first}; adb's server needs it to get in after the flash`);
  if (first !== resolve(home)) log(`    note: the Mirror will let in ${first}, not this HOME's key. adb's server must run with it: see "A throwaway key" in tools/mirror/README.md`);
  return lines.join('\n');
}

/** The last line, and the exit code: 0 when HOME opens our app. */
function done(r: InstallResult, name: string, log: (l: string) => void): number {
  const fb = r.firstBoot;
  log(`\nDone: ${name} ${fb?.app.name ?? fb?.app.code}, home ${fb?.home.result}${fb?.wifi === undefined ? '' : `, Wi-Fi ${fb.wifi ? 'joined' : 'not confirmed'}`}, rotation ${fb?.rotation ?? 'unset'}${r.screenshot ? `, screenshot ${r.screenshot}` : ''}.`);
  return fb?.home.result === 'set' ? 0 : 1;
}

/**
 * flash-node.ts's body: runs the install, the first boot alone, the way back or a dry run from `argv`. Returns the exit
 * code: 0 done, 1 done but HOME isn't our app. Throws UsageError for a wrong argument.
 */
export async function cli(argv: string[], log: (l: string) => void = (l) => console.log(l)): Promise<number> {
  const a = parse(argv);
  if (a.help) { log(HELP); return 0; }
  const dry = !!a['dry-run'];
  if (a['first-boot'] && a['way-back']) fail('--first-boot and --way-back: pick one');
  const work = a.work ?? mkdtempSync(join(tmpdir(), 'gw-mirror-'));
  log(dry ? 'DRY RUN: a fake Mirror; no device is touched.'
    : a['first-boot'] ? 'The first boot only, over adb: no partition is written.' : 'This WRITES the Mirror\'s system and userdata partitions.');
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
        await runWayBack({ system: a.image!, userdata: a.userdata! }, await realDevices(a, log, await confirmer(!!a.yes)));
      }
      log('\nDone: the plain Glass Panes image is on the Mirror.');
      return 0;
    }

    const app = a.probe ? MIRROR_PROBE : GLASSWORKS;
    const wifi = a['wifi-ssid'] ? { ssid: a['wifi-ssid'], password: a['wifi-pass-file'] ? readFileSync(a['wifi-pass-file'], 'utf8').replace(/\r?\n$/, '') : fail('--wifi-ssid needs --wifi-pass-file') } : undefined;
    if (a['first-boot']) {
      const o = { app, haUrl: a['ha-url'], wifi, work };
      if (dry) {
        // the fake Mirror runs the image already: adb from the start
        const d = dryDevices(0, log, { app });
        d.deps.waitForAdb = async () => d.after;
        const r = await runInstall({ ...o, screenshot: join(work, 'after.png') }, d.deps);
        log(`\nDry run: adb ran ${d.after.cmds.length} commands on the first boot; LK got ${d.lk.commands.length}.`);
        return done(r, app.name, log);
      }
      const devices = await realDevices(a, log, await confirmer(!!a.yes));
      return done(await runInstall({ ...o, screenshot: a.screenshot ?? resolve('mirror-after.png') }, devices), app.name, log);
    }
    const apk = a.apk ? new Uint8Array(readFileSync(a.apk)) : dry ? fakeMirrorApk({ package: app.package, libs: { 'arm64-v8a': { 'libdry.so': new Uint8Array(16) } } }) : fail(`--apk <${a.probe ? 'the probe\'s APK' : 'Glassworks legacy APK'}> is needed`);
    const animPath = a['boot-animation'] ?? join(REPO, 'branding/lululemon-mirror/bootanimation.zip');
    const bootAnimation = a['no-boot-animation'] ? undefined : new Uint8Array(readFileSync(animPath));
    const settingsOverlay = a['no-settings-overlay'] ? undefined : new Uint8Array(readFileSync(join(REPO, 'frames/lululemon-mirror/overlay/GlassworksSettings.apk')));
    const frameworkOverlay = a['no-framework-overlay'] ? undefined : new Uint8Array(readFileSync(join(REPO, 'frames/lululemon-mirror/overlay/GlassworksFramework.apk')));
    const common = { apk, app, bootAnimation, settingsOverlay, frameworkOverlay, haUrl: a['ha-url'], wifi, work, fill: !a['no-fill'] };
    let r;
    if (dry) {
      // the fake image needs room for what the patch writes: the APK, the libraries it unpacks, the
      // boot animation and the overlays (#557)
      const img = await dryImage(work, await dryRoom(apk, (bootAnimation?.length ?? 0) + (settingsOverlay?.length ?? 0) + (frameworkOverlay?.length ?? 0)));
      const d = dryDevices(img.bytes, log, { app });
      r = await runInstall({ ...common, image: { path: img.path }, want: img.want, userdataBytes: DRY_USERDATA, adbKey: fakeAdbKey(1, 'dry@run'), screenshot: join(work, 'after.png') }, d.deps);
      log(`\nDry run: LK got ${d.lk.commands.filter((c) => c.startsWith('flash:')).join(', ')}, then reboot; adb ran ${d.after.cmds.length} commands on the first boot.`);
    } else {
      if (!a.image && !a.refract) fail('--image <system.img> or --refract is needed');
      const image = a.image ? { path: a.image } : { url: `${IMAGE_PROXY.base}/${IMAGE_PROXY.systemImg}` };
      r = await runInstall({ ...common, image, adbKey: adbKeys(a, log), screenshot: a.screenshot ?? resolve('mirror-after.png') }, await realDevices(a, log, await confirmer(!!a.yes)));
    }
    return done(r, app.name, log);
  } finally {
    if (a.keep || a.work) log(`work folder kept: ${work}`);
    else rmSync(work, { recursive: true, force: true });
  }
}
