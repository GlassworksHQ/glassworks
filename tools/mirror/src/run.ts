// The root's run of Kiln's Mirror install, headless: the same steps as the page (#357), from Node.
// WRITES the Mirror's system and userdata partitions. Every step is printed first and waits for a
// "yes" unless the run was started with --yes. Devices come in through RunDeps, so the dry run and the
// tests drive the same code against FakeLk and a fake adb.
import { closeSync, createReadStream, openAsBlob, openSync, readSync, writeSync } from 'node:fs';
import { Readable } from 'node:stream';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import type { Store } from '@glassworks/ext4';
import {
  GLASS_PANES, GLASS_PANES_USERDATA, buildImages, checkDownload, checkFastboot, firstBoot, loadSystem, makeOnlyHome, resumableBody,
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

export interface InstallOptions {
  /** system.img: a local file, or a URL (Refract) */
  image: { path: string } | { url: string };
  apk: Uint8Array;
  /** the adb_keys line(s) for the new userdata, e.g. the host's ~/.android/adbkey.pub */
  adbKey: string;
  bootAnimation?: Uint8Array;
  haUrl?: string;
  wifi?: { ssid: string; password: string };
  /** where the unpacked image and the built images go (about 2.5 GB for the real one) */
  work: string;
  /** zeros as RAW blocks, for an LK that refuses FILL */
  fill?: boolean;
  /** the dry run's small image, instead of the Glass Panes one */
  want?: Want;
  /** the dry run's small userdata, instead of the partition's size */
  userdataBytes?: number;
  /** where the screenshot goes (default: the work folder) */
  screenshot?: string;
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
async function steps(d: RunDeps, list: [string, () => Promise<void>][]): Promise<void> {
  for (const [i, [name, run]] of list.entries()) {
    d.log(`\n[${i + 1}/${list.length}] ${name}`);
    if (!(await d.confirm(`${name}: go on?`))) throw new Stopped(`stopped before: ${name}`);
    await run();
  }
}

export interface InstallResult { firstBoot?: FirstBoot; screenshot?: string }

export async function runInstall(o: InstallOptions, d: RunDeps): Promise<InstallResult> {
  const sleep = d.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const want = o.want ?? GLASS_PANES.system;
  const res: InstallResult = {};
  let fb: FastbootLike | undefined;
  let userdataBytes = 0;
  let loaded: Awaited<ReturnType<typeof loadSystem>> | undefined;
  let rawFd: number | undefined;
  const raw = join(o.work, 'system.raw'), sysOut = join(o.work, 'system.sparse'), udOut = join(o.work, 'userdata.sparse');
  try {
    await steps(d, [
      ['Checks (read-only)', async () => {
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
        if (await d.adb()) await d.rebootToBootloader();
        else d.log('    no adb: assuming it is in fastboot already');
      }],
      ['Fastboot checks (read-only)', async () => {
        fb = await d.fastboot();
        const r = await checkFastboot(fb);
        for (const c of r.checks) d.log(`    ${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}${c.ok ? '' : ` (needs ${c.need})`}`);
        r.info.forEach((i) => d.log(`    ${i}`));
        if (!r.ok) throw new Error('the fastboot checks failed. Nothing was written.');
        userdataBytes = o.userdataBytes ?? r.userdataBytes;
      }],
      ['Build the images (S1 Kiosk, boot animation, rotation; a fresh userdata with the adb key)', async () => {
        const sys = fileSink(sysOut), ud = fileSink(udOut);
        try {
          await buildImages(loaded!, { apk: o.apk, bootAnimation: o.bootAnimation, hwrotation: 270, adbKey: o.adbKey, userdataBytes, fill: o.fill },
            { system: sys.write, userdata: ud.write }, (step, x) => { if (x === 1) d.log(`    ${step} done`); });
        } finally { sys.close(); ud.close(); }
        d.log(`    ${sysOut}, ${udOut}`);
      }],
      ['Flash system (WRITES the Mirror)', async () => { await fb!.flashBlob('system', await openAsBlob(sysOut), pct(d.log, 'system')); }],
      ['Flash userdata (WRITES the Mirror)', async () => { await fb!.flashBlob('userdata', await openAsBlob(udOut), pct(d.log, 'userdata')); }],
      ['Reboot', async () => { await fb!.reboot(); }],
      ['Wait for adb (first start takes a few minutes)', async () => {
        const p = await d.waitForAdb();
        for (let i = 0; (await p.run('getprop sys.boot_completed')).out.trim() !== '1'; i++) {
          if (i > 180) throw new Error('the Mirror didn\'t finish starting in 6 minutes');
          await sleep(2000);
        }
      }],
      ['First boot: permissions, Home Assistant, Wi-Fi, the only home screen', async () => {
        const p = await d.waitForAdb();
        res.firstBoot = await firstBoot(p, { haUrl: o.haUrl, wifi: o.wifi, onStep: (s, x) => d.log(s ? `    ${s}` : `      ${x}`), sleep });
      }],
      ['HOME check', async () => {
        let home = res.firstBoot!.home;
        if (home.result !== 'set') {
          d.log(`    HOME opened ${home.focused ?? 'something else'}${home.left.length ? `; still a home app: ${home.left.join(', ')}` : ''}: hiding again`);
          home = (await makeOnlyHome(await d.waitForAdb(), (s, x) => d.log(s ? `    ${s}` : `      ${x}`), sleep)).home;
          res.firstBoot = { ...res.firstBoot!, home };
        }
        d.log(home.result === 'set' ? '    S1 Kiosk is the home screen' : `    NOT FIXED: HOME opens ${home.focused}. The Mirror has no touchscreen: plug in a USB mouse and choose S1 Kiosk, then Always.`);
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
