// The root's run of Kiln's Mirror install, headless: the same steps as the page (#357), from Node.
// WRITES the Mirror's system and userdata partitions. Every step is printed first and waits for a
// "yes" unless the run was started with --yes. Devices come in through RunDeps, so the dry run and the
// tests drive the same code against FakeLk and a fake adb.
import { closeSync, createReadStream, openAsBlob, openSync, readSync, writeSync } from 'node:fs';
import { Readable } from 'node:stream';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import { apiKeyFingerprint, readApiKey } from '@glassworks/app-adb';
import type { Store } from '@glassworks/ext4';
import {
  GLASS_PANES, GLASS_PANES_KEY_FINGERPRINT, GLASS_PANES_USERDATA, GLASSWORKS, adbFingerprint, adbKeyLines, buildImages, checkDownload, checkMirrorApk, checkFastboot, firstBoot, loadSystem, makeOnlyHome, resumableBody,
  type MirrorApp,
  type FastbootLike, type FirstBoot,
} from '@glassworks/lululemon-mirror-installer';

export const STOCK_WARNING = [
  'This replaces the software on the Mirror for good.',
  'Its bootloader can\'t read anything back, so nothing can be backed up first:',
  'lululemon\'s software can\'t be restored, by Kiln or anyone. The way back is the plain Glass Panes image (--way-back).',
];

export interface RunDeps {
  log(line: string): void;
  /** Asks before a step; true to go on. --yes answers yes. */
  confirm(question: string): Promise<boolean>;
  /** The Mirror over adb while Android runs, if it does now. */
  adb(): Promise<PanelShell | undefined>;
  rebootToBootloader(): Promise<void>;
  /** Waits for the Mirror's fastboot and opens it. */
  fastboot(): Promise<FastbootLike>;
  /** After the restart: the Mirror over adb, once it answers. */
  waitForAdb(): Promise<PanelShell>;
  screenshot?(file: string): Promise<void>;
  sleep?: (ms: number) => Promise<void>;
}

export interface Want { name: string; size: number; sha256: string }

/** What the steps after the flash need: all of it, or the whole run for a Mirror already on the image (--first-boot). */
export interface FirstBootOptions {
  /** which app the APK is: Glassworks' legacy build by default (frame.json `app`), MIRROR_PROBE for the probe */
  app?: MirrorApp;
  haUrl?: string;
  /** given to the app at the first boot; the image may have the network saved already */
  wifi?: { ssid: string; password: string };
  /** where the unpacked image and the built images go (about 2.5 GB for the real one) */
  work: string;
  /** where the screenshot goes (default: the work folder) */
  screenshot?: string;
}

export interface InstallOptions extends FirstBootOptions {
  /** system.img: a local file, or a URL (Refract) */
  image: { path: string } | { url: string };
  apk: Uint8Array;
  /**
   * The adb key line(s), e.g. the host's ~/.android/adbkey.pub. The first is the one the patched
   * services.jar lets in without asking; all go in the new userdata's adb_keys, which this image's adbd
   * ignores. adb's server must answer with the first one's private half for the first boot.
   */
  adbKey: string;
  bootAnimation?: Uint8Array;
  /** GlassworksSettings.apk: Wi-Fi on, no screen timeout, no lock screen, no auto-rotate on the first boot */
  settingsOverlay?: Uint8Array;
  /** GlassworksFramework.apk: config_screenBrightnessSettingMinimum 0, so the app's backlight-off sleep turns the backlight off */
  frameworkOverlay?: Uint8Array;
  /** zeros as RAW blocks, for an LK that refuses FILL */
  fill?: boolean;
  /** the dry run's small image, instead of the Glass Panes one */
  want?: Want;
  /** the dry run's small userdata, instead of the partition's size */
  userdataBytes?: number;
}

class Stopped extends Error {}

/** A Store on a file (the system image is too big for memory). */
function fileStore(fd: number): Store {
  return {
    async read(o, n) { const b = new Uint8Array(n); readSync(fd, b, 0, n, o); return b; },
    async write(o, d) { writeSync(fd, d, 0, d.length, o); },
  };
}

function fileSink(path: string) {
  const fd = openSync(path, 'w');
  let pos = 0;
  return { write: (b: Uint8Array) => { writeSync(fd, b, 0, b.length, pos); pos += b.length; }, close: () => closeSync(fd) };
}

const pct = (log: (l: string) => void, what: string) => {
  let last = -1;
  return (x: number) => { const p = Math.floor(x * 10); if (p !== last) { last = p; log(`    ${what} ${p * 10}%`); } };
};

/** Runs the steps in order, each announced and confirmed. */
type Step = [string, () => Promise<void>];

async function steps(d: RunDeps, list: Step[]): Promise<void> {
  for (const [i, [name, run]] of list.entries()) {
    d.log(`\n[${i + 1}/${list.length}] ${name}`);
    if (!(await d.confirm(`${name}: go on?`))) throw new Stopped(`stopped before: ${name}`);
    await run();
  }
}

export interface InstallResult { firstBoot?: FirstBoot; screenshot?: string }

/**
 * The install. Given FirstBootOptions alone (no APK), only the steps after the flash, for a Mirror already on the
 * image whose run stopped before its first boot (--first-boot): firstBoot checks the fingerprint and the app first.
 */
export async function runInstall(o: InstallOptions | FirstBootOptions, d: RunDeps): Promise<InstallResult> {
  const ours = o.app ?? GLASSWORKS;
  const sleep = d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const want = ('want' in o && o.want) || GLASS_PANES.system;
  const res: InstallResult = {};
  let fb: FastbootLike | undefined;
  let userdataBytes = 0;
  let deviceMac: string | undefined;
  /** the ESPHome API key the running app holds, carried over the flash (#574). A secret: only its fingerprint is printed */
  let apiKey: string | undefined;
  let loaded: Awaited<ReturnType<typeof loadSystem>> | undefined;
  let rawFd: number | undefined;
  const raw = join(o.work, 'system.raw'), sysOut = join(o.work, 'system.sparse'), udOut = join(o.work, 'userdata.sparse');
  try {
    const flash: Step[] = 'apk' in o ? [
      ['Checks (read-only)', async () => {
        // the APK first: one the Mirror can't run as its home screen stops here, before anything is read or written
        const apk = await checkMirrorApk(o.apk, ours);
        d.log(`    APK: ${apk.package}, minSdk ${apk.minSdk ?? 1}, home screen on`);
        // every key line, before anything restarts: a bad second key would otherwise stop the build in fastboot
        const trusted = adbFingerprint(adbKeyLines(o.adbKey)[0]!);
        if (trusted === GLASS_PANES_KEY_FINGERPRINT) throw new Error('the adb key is the Glass Panes project key, published with its private half: the Mirror would let anyone in. Pass your own (--adb-key). Nothing was changed.');
        d.log(`    adb key the image will let in: ${trusted}`);
        const p = await d.adb();
        if (!p) { d.log('    no adb: the Mirror is in fastboot already, or off'); return; }
        const prop = async (k: string) => (await p.run(`getprop ${k}`)).out.trim();
        const board = await prop('ro.board.platform'), fp = await prop('ro.build.fingerprint');
        d.log(`    board ${board}, Android ${await prop('ro.build.version.release')}, ${fp === GLASS_PANES.fingerprint ? 'the Glass Panes image' : `not the Glass Panes image (${fp})`}`);
        if (board !== 'msm8916') throw new Error(`this isn't the original Mirror (board ${board || 'unknown'}, needs msm8916). Nothing was changed.`);
      }],
      ['The warning', async () => { for (const l of STOCK_WARNING) d.log(`    ${l}`); }],
      ['Load and check system.img', async () => {
        const fd = rawFd = openSync(raw, 'w+');
        const source = 'path' in o.image
          ? { size: (await openAsBlob(o.image.path)).size, stream: () => Readable.toWeb(createReadStream((o.image as { path: string }).path, { highWaterMark: 1 << 20 })) as ReadableStream<Uint8Array> }
          : resumableBody(o.image.url, want.size);
        loaded = await loadSystem(source, fileStore(fd), pct(d.log, 'read and checked'), want);
        d.log(`    SHA-256 ${loaded.sha256}: as published`);
      }],
      ['Reboot the Mirror into fastboot', async () => {
        const p = await d.adb();
        if (!p) {
          d.log('    no adb: assuming it is in fastboot already');
          d.log('    Home Assistant API key: none to carry (no adb before the flash): Home Assistant may ask to re-authenticate once');
          return;
        }
        // A reflash wipes the key Home Assistant provisioned: read it now, as late as the app still runs, so a key HA
        // changed meanwhile is the one carried. A key the app has but can't hand over stops the run: flashing would lose it.
        try {
          apiKey = await readApiKey(p);
        } catch (e) {
          throw new Error(`couldn't read the Home Assistant API key from the app: ${(e as Error).message}. Nothing was written to the Mirror. Run it again.`);
        }
        d.log(apiKey ? `    Home Assistant API key: carried over the flash (fingerprint ${await apiKeyFingerprint(apiKey)})`
          : '    Home Assistant API key: none to carry (no app of ours with a key, or one older than #574): Home Assistant may ask to re-authenticate once');
        await d.rebootToBootloader();
      }],
      ['Fastboot checks (read-only)', async () => {
        fb = await d.fastboot();
        const r = await checkFastboot(fb);
        for (const c of r.checks) d.log(`    ${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}${c.ok ? '' : ` (needs ${c.need})`}`);
        r.info.forEach((i) => d.log(`    ${i}`));
        if (!r.ok) throw new Error('the fastboot checks failed. Nothing was written.');
        userdataBytes = o.userdataBytes ?? r.userdataBytes;
        deviceMac = r.deviceMac;
      }],
      [`Build the images (${ours.name}, boot animation, services.jar trusting the adb key; a fresh userdata)`, async () => {
        // patched in place: a build that fails ends the run, and the next run loads and checks the file again
        const sys = fileSink(sysOut), ud = fileSink(udOut);
        try {
          // the Wi-Fi is saved in the new userdata too: Android joins it on the first boot with nothing tapped
          await buildImages(loaded!, { apk: o.apk, app: ours, bootAnimation: o.bootAnimation, settingsOverlay: o.settingsOverlay, frameworkOverlay: o.frameworkOverlay, adbKey: o.adbKey, userdataBytes, fill: o.fill, ...(deviceMac ? { deviceMac } : {}), ...(apiKey ? { apiKeyHandover: true } : {}), ...(o.wifi ? { wifi: o.wifi } : {}) },
            { system: sys.write, userdata: ud.write }, (step, x) => { if (x === 1) d.log(`    ${step} done`); },
            // image.ts's own lines: the APK's libraries, the unused apps left out, build.prop, services.jar
            (line) => d.log(`    ${line}`));
        } finally { sys.close(); ud.close(); }
        d.log(`    ${sysOut}, ${udOut}`);
      }],
      ['Flash system (WRITES the Mirror)', async () => { await fb!.flashBlob('system', await openAsBlob(sysOut), pct(d.log, 'system')); }],
      ['Flash userdata (WRITES the Mirror)', async () => { await fb!.flashBlob('userdata', await openAsBlob(udOut), pct(d.log, 'userdata')); }],
      ['Reboot', async () => { await fb!.reboot(); }],
    ] : [];
    await steps(d, [
      ...flash,
      ['Wait for adb (first start takes a few minutes)', async () => {
        const p = await d.waitForAdb();
        for (let i = 0; (await p.run('getprop sys.boot_completed')).out.trim() !== '1'; i++) {
          if (i > 180) throw new Error('the Mirror didn\'t finish starting in 6 minutes. Once it has, run only the first boot with --first-boot');
          await sleep(2000);
        }
      }],
      ['First boot: permissions, portrait, Home Assistant, Wi-Fi, the only home screen', async () => {
        const p = await d.waitForAdb();
        res.firstBoot = await firstBoot(p, { haUrl: o.haUrl, wifi: o.wifi, app: ours, ...(apiKey ? { apiKey } : {}), onStep: (s, x) => d.log(s ? `    ${s}` : `      ${x}`), sleep });
      }],
      ['HOME check', async () => {
        let home = res.firstBoot!.home;
        if (home.result === 'none') {
          // nothing was hidden, and hiding again wouldn't help: this APK has no home screen of its own
          d.log(`    NO HOME SCREEN IN THIS APK: ${ours.name} doesn't answer HOME (its launcher activity is off), so nothing was hidden: ${home.left.join(', ') || 'the image\'s launcher'} stays the home screen. Install a build made for Android 6.`);
          return;
        }
        if (home.result !== 'set') {
          d.log(`    HOME opened ${home.focused ?? 'something else'}${home.left.length ? `; still a home app: ${home.left.join(', ')}` : ''}: hiding again`);
          home = (await makeOnlyHome(await d.waitForAdb(), (s, x) => d.log(s ? `    ${s}` : `      ${x}`), sleep, ours)).home;
          res.firstBoot = { ...res.firstBoot!, home };
        }
        d.log(home.result === 'set' ? `    ${ours.name} is the home screen` : `    NOT FIXED: HOME opens ${home.focused}. The Mirror has no touchscreen: plug in a USB mouse and choose ${ours.name}, then Always.`);
      }],
      ['Screenshot', async () => {
        if (!d.screenshot) return;
        res.screenshot = o.screenshot ?? join(o.work, 'after.png');
        await d.screenshot(res.screenshot);
        d.log(`    ${res.screenshot}`);
      }],
    ]);
  } catch (e) {
    if (e instanceof Stopped) d.log(`\n${e.message}`);
    throw e;
  } finally {
    if (rawFd !== undefined) closeSync(rawFd);
  }
  return res;
}

export interface WayBackOptions { system: string; userdata: string; systemWant?: Want; userdataWant?: Want }

/** The way back: the plain Glass Panes images, checked, written as downloaded. */
export async function runWayBack(o: WayBackOptions, d: RunDeps): Promise<void> {
  let fb: FastbootLike | undefined;
  await steps(d, [
    ['Check system.img and userdata.sparse', async () => {
      await checkDownload(await openAsBlob(o.system), o.systemWant ?? GLASS_PANES.system, pct(d.log, 'system.img'));
      await checkDownload(await openAsBlob(o.userdata), o.userdataWant ?? GLASS_PANES_USERDATA, pct(d.log, 'userdata.sparse'));
      d.log('    both as published');
    }],
    ['Reboot the Mirror into fastboot', async () => { if (await d.adb()) await d.rebootToBootloader(); }],
    ['Fastboot checks (read-only)', async () => {
      fb = await d.fastboot();
      const r = await checkFastboot(fb);
      for (const c of r.checks) d.log(`    ${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}`);
      if (!r.ok) throw new Error('the fastboot checks failed. Nothing was written.');
    }],
    ['Flash system (WRITES the Mirror)', async () => { await fb!.flashBlob('system', await openAsBlob(o.system), pct(d.log, 'system')); }],
    ['Flash userdata (WRITES the Mirror)', async () => { await fb!.flashBlob('userdata', await openAsBlob(o.userdata), pct(d.log, 'userdata')); }],
    ['Reboot', async () => { await fb!.reboot(); d.log('    the Mirror starts as a fresh Glass Panes unit (its own shared adb key)'); }],
  ]);
}
