// The S1's boot overlay (src/overlay.ts): unit tests, and the whole boot patch byte for byte against goldens made by
// the real CLI (../../tools/root/patch-boot-magisk.sh in Docker). The goldens contain Aqara's boot images and Magisk
// binaries, so they live outside the repo: S1_WEB_GOLDEN=~/src/aqara-panel-backups/web-golden npm test (that path is
// also the default). Without them the golden tests are skipped; the unit tests run.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { isMagiskPatched, magiskBusybox, patchBoot, type OverlayFile } from '@glassworks/bootpatch';
import { s1Overlay } from '../src/overlay';

const GOLDEN = process.env.S1_WEB_GOLDEN ?? join(homedir(), 'src/aqara-panel-backups/web-golden');
const HAVE_GOLDEN = existsSync(join(GOLDEN, 'out/SHA256SUMS'));
/** The overlay files s1Overlay takes at this revision: a set made before a rename (#641) lacks some of them. */
const OVERLAY = ['glassworks.rc', 'glassworks_loader.sh', 'glassworks_verify', 'glassworks_bundle.pub', 'glassworks_agent.sh',
  'otbr-agent', 'ot-ctl', 'glassworks_firstboot.sh', 'glassworks_netwatch.sh', 'glassworks_update.sh', 'glassworks_bootanimation.zip'];
const MISSING = HAVE_GOLDEN
  ? [...OVERLAY.map((f) => `fixtures/overlay/${f}`), 'fixtures/Magisk-v31.1.apk', 'fixtures/test_adbkey.pub']
    .filter((f) => !existsSync(join(GOLDEN, f)))
  : [];

const enc = new TextEncoder();
const g = (p: string) => new Uint8Array(readFileSync(join(GOLDEN, p)));
const sha256 = (d: Uint8Array) => createHash('sha256').update(d).digest('hex');

/** Equal bytes, reported by hash + first differing offset instead of a 32 MiB diff. */
function expectSameBytes(got: Uint8Array, want: Uint8Array, what: string) {
  if (got.length === want.length && Buffer.compare(got, want) === 0) return;
  let i = 0;
  while (i < got.length && i < want.length && got[i] === want[i]) i++;
  expect.fail(`${what}: differs (len ${got.length} vs ${want.length}, first diff @0x${i.toString(16)}; ` +
    `sha256 ${sha256(got)} vs ${sha256(want)})`);
}

describe('s1Overlay (unit)', () => {
  /** A zip whose one entry is STORED: the central directory and its end record, all zipEntries reads. */
  const storedZip = (): Uint8Array => {
    const name = enc.encode('desc.txt');
    const cd = new Uint8Array(46 + name.length);
    const c = new DataView(cd.buffer);
    c.setUint32(0, 0x02014b50, true);
    c.setUint16(28, name.length, true);
    cd.set(name, 46);
    const end = new Uint8Array(22);
    const e = new DataView(end.buffer);
    e.setUint32(0, 0x06054b50, true);
    e.setUint16(10, 1, true);
    e.setUint32(12, cd.length, true);
    e.setUint32(16, 0, true);
    const zip = new Uint8Array(cd.length + end.length);
    zip.set(cd); zip.set(end, cd.length);
    return zip;
  };
  const base = { rc: enc.encode('a'), loaderSh: enc.encode('b'), agentSh: enc.encode('c'), otbrAgent: enc.encode('d'),
    otCtl: enc.encode('e'), firstbootSh: enc.encode('f'), netwatchSh: enc.encode('g'), updateSh: enc.encode('h'),
    verify: enc.encode('m'), bundlePub: enc.encode(`${'ab'.repeat(32)}\n`), magiskBusybox: enc.encode('o') };
  it('refuses a key that is not an adb public key, accepts none', () => {
    expect(() => s1Overlay({ ...base, adbKeys: enc.encode('ssh-rsa AAAA') })).toThrow(/QAAAA/);
    expect(s1Overlay({ ...base, adbKeys: new Uint8Array(0) })).toHaveLength(12);
  });
  it('puts every file under its Glassworks name, in patch-boot-magisk.sh\'s order', () => {
    const files = s1Overlay({ ...base, adbKeys: new Uint8Array(0), bootAnimation: storedZip() });
    expect(files.map((f) => `${f.mode.toString(8)} ${f.path}`)).toEqual([
      '644 overlay.d/glassworks.rc', '700 overlay.d/sbin/glassworks_loader.sh', '700 overlay.d/sbin/glassworks_verify',
      '644 overlay.d/sbin/glassworks_bundle.pub', '600 overlay.d/sbin/glassworks_adb_keys', '700 overlay.d/sbin/glassworks_agent.sh',
      '700 overlay.d/sbin/otbr-agent', '700 overlay.d/sbin/ot-ctl', '700 overlay.d/sbin/glassworks_firstboot.sh',
      '700 overlay.d/sbin/glassworks_netwatch.sh', '700 overlay.d/sbin/glassworks_update.sh', '700 overlay.d/sbin/magisk-busybox',
      '644 overlay.d/sbin/glassworks_bootanimation.zip',
    ]);
    expect(files.some((f) => f.path.includes('s1kiosk'))).toBe(false);
  });
  it('refuses a radio bundle key that is not 32 hex bytes, and an empty verifier', () => {
    expect(() => s1Overlay({ ...base, adbKeys: new Uint8Array(0), bundlePub: enc.encode('abc') })).toThrow(/public key/);
    expect(() => s1Overlay({ ...base, adbKeys: new Uint8Array(0), verify: new Uint8Array(0) })).toThrow(/verifier/);
  });
  it('carries Magisk\'s busybox for its own setup (root only), and refuses an empty one', () => {
    const files = s1Overlay({ ...base, adbKeys: new Uint8Array(0) });
    expect(files.find((f) => f.path === 'overlay.d/sbin/magisk-busybox')).toMatchObject({ mode: 0o700, data: enc.encode('o') });
    expect(() => s1Overlay({ ...base, adbKeys: new Uint8Array(0), magiskBusybox: new Uint8Array(0) })).toThrow(/busybox/);
  });
});

it.runIf(MISSING.length > 0)('S1 goldens are for another revision: the golden tests are skipped', () => {
  process.stderr.write(`[s1 overlay] golden tests SKIPPED: ${GOLDEN} is a golden set for another revision (it lacks ` +
    `${MISSING.join(', ')}): make a set for this revision with gen.sh (copy one from an older set) and point S1_WEB_GOLDEN at it\n`);
});

describe.skipIf(!HAVE_GOLDEN || MISSING.length > 0)('golden: patchBoot with s1Overlay == patch-boot-magisk.sh', () => {
  const ov = (f: string) => g(`fixtures/overlay/${f}`);
  const overlay = (opts: { key?: boolean; anim?: boolean } = {}): OverlayFile[] => s1Overlay({
    rc: ov('glassworks.rc'), loaderSh: ov('glassworks_loader.sh'), verify: ov('glassworks_verify'), bundlePub: ov('glassworks_bundle.pub'),
    adbKeys: opts.key === false ? new Uint8Array(0) : g('fixtures/test_adbkey.pub'),
    agentSh: ov('glassworks_agent.sh'), otbrAgent: ov('otbr-agent'), otCtl: ov('ot-ctl'),
    firstbootSh: ov('glassworks_firstboot.sh'), netwatchSh: ov('glassworks_netwatch.sh'), updateSh: ov('glassworks_update.sh'),
    magiskBusybox: busybox,
    bootAnimation: opts.anim === false ? undefined : ov('glassworks_bootanimation.zip'),
  });
  /** The config the CLI printed ("--- Magisk config:" in out/<case>.log). */
  const cliConfig = (c: string) => {
    const log = readFileSync(join(GOLDEN, `out/${c}.log`), 'utf8');
    return log.split('--- Magisk config:\n')[1]!.split('--- overlay.d:')[0]!;
  };
  let apk: Uint8Array;
  let busybox: Uint8Array;
  beforeAll(async () => { apk = g('fixtures/Magisk-v31.1.apk'); busybox = await magiskBusybox(apk); });

  const cases: { name: string; input: string; wasMagisk: boolean; key?: boolean; anim?: boolean; preinit?: string }[] = [
    { name: 'stock-4.5.80', input: 'in/boot-4.5.80.img', wasMagisk: false },
    { name: 'stock-old', input: 'in/boot-old.img', wasMagisk: false },
    { name: 'repatch-bundled', input: 'in/s1-boot-magisk-v31.1-bundled.img', wasMagisk: true },
    { name: 'repatch-4.5.80', input: 'out/stock-4.5.80.img', wasMagisk: true },
    { name: 'noanim-4.5.80', input: 'in/boot-4.5.80.img', wasMagisk: false, anim: false },
    { name: 'nokey-4.5.80', input: 'in/boot-4.5.80.img', wasMagisk: false, key: false },
    { name: 'repatch-noanim-from-anim', input: 'out/stock-4.5.80.img', wasMagisk: true, anim: false },
    { name: 'preinit-data-4.5.80', input: 'in/boot-4.5.80.img', wasMagisk: false, preinit: 'metadata' },
  ];
  for (const c of cases) {
    it(c.name, async () => {
      const r = await patchBoot({ boot: g(c.input), magiskApk: apk, overlay: overlay(c), preinitDevice: c.preinit });
      expect(r.wasMagisk).toBe(c.wasMagisk);
      expect(r.magiskConfig).toBe(cliConfig(c.name));
      expectSameBytes(r.image, g(`out/${c.name}.img`), c.name);
    }, 120_000);
  }

  it('re-patching restores first: the result equals patching the stock image', () => {
    expect(sha256(g('out/repatch-bundled.img'))).toBe(sha256(g('out/stock-old.img')));
    expect(sha256(g('out/repatch-4.5.80.img'))).toBe(sha256(g('out/stock-4.5.80.img')));
  });

  it('isMagiskPatched', async () => {
    expect(await isMagiskPatched(g('in/boot-4.5.80.img'))).toBe(false);
    expect(await isMagiskPatched(g('in/s1-boot-magisk-v31.1-bundled.img'))).toBe(true);
    expect(await isMagiskPatched(g('out/nokey-4.5.80.img'))).toBe(true);
  });
});
