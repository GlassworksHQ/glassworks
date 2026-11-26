// The Mirror's in-place code (src/inplace.ts, src/stage.ts): the stage's files as the stock probe lays them out, the
// bundle's checks (signature, serial, odex stop-gate), the build Kiln checks with checkInPlace, and the app's install
// with Device Owner. Every serial, address and key is made up.
import { Disk, Ext4, MemoryStore, readSparse } from '@glassworks/ext4';
import { checkInPlace } from '@glassworks/kiln-core';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { bootInPlaceZip } from '../src/bootstill';
import design from '../../design/installer.json';
import {
  BundleRefused, DEFAULT_PROBE_APP_UID, STOCK_PROBE_ASSETS, STOCK_PROBE_LABELS, buildStageUserdata, installInPlace, mirrorInPlace,
} from '../src';
import { ADB_KEYS, macFromSerial } from '../src/image';
import { INSTALLER_MAC_SETTING, USB_ALLOW_PERMISSION_SETTING, factoryUserdata, keptBundleFits } from '../src/inplace';
import { USB_ALLOW_PERMISSION } from '../src/stock';
import { GLASSWORKS } from '../src/app';
import { simInPlace } from '../src/sim';
import { SYSTEM_BYTES } from '../src/flash';
import { FAKE_SERIAL, FakeMirror, buildFakeGwsbBundle, fakeAdbKey, fakeMirrorApk } from './fakes';
import { HAS_D8, buildSyntheticStockServicesJar } from './synthetic-services';

const SECRET = Uint8Array.from({ length: 32 }, (_, i) => i + 1);
const code = mirrorInPlace(design.device.words);
const bytes = async (b: Blob) => new Uint8Array(await b.arrayBuffer());

async function openSparse(b: Blob): Promise<Ext4> {
  const { disk } = await readSparse(b.stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }));
  return Ext4.open(disk);
}

describe('the stage', () => {
  const assets = {
    [STOCK_PROBE_ASSETS.apk]: new Uint8Array([0x50, 0x4b, 3, 4, 1, 2, 3]),
    [STOCK_PROBE_ASSETS.fiemap]: new Uint8Array([0x7f, 0x45, 0x4c, 0x46]),
    [STOCK_PROBE_ASSETS.cert]: new TextEncoder().encode(`${'ab'.repeat(40)}\n`),
  };

  it('plants the probe app, its packages.xml, the run\'s secret and the Wi-Fi switch, as the stock probe does', async () => {
    const blob = await buildStageUserdata({ adbKey: fakeAdbKey(3), userdataBytes: 64 * 2 ** 20, secret: SECRET, wifi: { ssid: 'Home Net', password: 'not-a-real-password' }, assets, time: 1_700_000_000 });
    const fs = await openSparse(blob);
    const pkg = '/app/dev.glassworks.stockprobe-1';
    expect(await fs.readFile(`${pkg}/base.apk`)).toEqual(assets[STOCK_PROBE_ASSETS.apk]);
    expect(await fs.readFile(`${pkg}/lib/arm64/libfiemap.so`)).toEqual(assets[STOCK_PROBE_ASSETS.fiemap]);
    expect(await fs.exists(`${pkg}/enable_wifi`)).toBe(true);
    const xml = new TextDecoder().decode(await fs.readFile('/system/packages.xml'));
    for (const perm of ['RECEIVE_BOOT_COMPLETED', 'INTERNET', 'ACCESS_WIFI_STATE', 'CHANGE_WIFI_STATE', 'BLUETOOTH', 'BLUETOOTH_ADMIN']) expect(xml).toContain(`android.permission.${perm}" granted="true"`);
    expect(xml).toContain(`version="1" userId="${DEFAULT_PROBE_APP_UID}"`);
    const secret = '/data/dev.glassworks.stockprobe/files/stage.secret';
    expect(await fs.readFile(secret)).toEqual(SECRET);
    const st = await fs.stat(secret);
    expect([st.mode & 0o7777, st.uid, st.gid]).toEqual([0o600, DEFAULT_PROBE_APP_UID, DEFAULT_PROBE_APP_UID]);
    expect(await fs.label(secret)).toBe(STOCK_PROBE_LABELS.appData);
    expect(new TextDecoder().decode(await fs.readFile('/misc/wifi/wpa_supplicant.conf'))).not.toContain('not-a-real-password');
  });

  it('refuses to build without the probe app\'s files, before anything is written', async () => {
    await expect(buildStageUserdata({ adbKey: fakeAdbKey(3), userdataBytes: 64 * 2 ** 20, secret: SECRET, assets: {} })).rejects.toThrow(/setup agent/);
  });
});

describe('the bundle', () => {
  it('takes the one this run signed, from this Mirror: its digest and extents', async () => {
    const b = await buildFakeGwsbBundle({ secret: SECRET });
    const r = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL });
    expect(r.digest).toBe(b.sha256);
    expect(r.extents).toEqual([{ physical: 4096 * 100, length: 16384 }]);
    expect(r.info.join('\n')).not.toContain(FAKE_SERIAL);
  });

  it('refuses a bad signature, another Mirror\'s bundle, an unsigned one, and an odexed build', async () => {
    await expect(code.readBundle((await buildFakeGwsbBundle({ secret: SECRET, corruptHmac: true })).bytes, { secret: SECRET, serial: FAKE_SERIAL })).rejects.toThrow(/signature/);
    await expect(code.readBundle((await buildFakeGwsbBundle({ secret: SECRET, serialno: '0000fake02' })).bytes, { secret: SECRET, serial: FAKE_SERIAL })).rejects.toThrow(/another device/);
    await expect(code.readBundle((await buildFakeGwsbBundle({})).bytes, { secret: SECRET, serial: FAKE_SERIAL })).rejects.toThrow(/not signed/);
    await expect(code.readBundle((await buildFakeGwsbBundle({ secret: SECRET, servicesOdexFound: true })).bytes, { secret: SECRET, serial: FAKE_SERIAL })).rejects.toThrow(BundleRefused);
    await expect(code.readBundle((await buildFakeGwsbBundle({ secret: SECRET, servicesHasClassesDex: false })).bytes, { secret: SECRET, serial: FAKE_SERIAL })).rejects.toThrow(/services.odex/);
  });
});

describe('the dex layout', () => {
  it('goes on only when the bundle says, as booleans, that there is no services.odex and services.jar has its classes.dex', async () => {
    const refused = async (metaPatch: Record<string, unknown>) => {
      const b = await buildFakeGwsbBundle({ secret: SECRET, metaPatch });
      const e = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL }).catch((x: unknown) => x);
      expect(e).toBeInstanceOf(BundleRefused);
      return (e as Error).message;
    };
    expect(await refused({ servicesOdexFound: undefined })).toMatch(/doesn't say whether/);
    expect(await refused({ servicesHasClassesDex: undefined })).toMatch(/doesn't say whether/);
    expect(await refused({ servicesOdexFound: 'false' })).toMatch(/doesn't say whether/);
    expect(await refused({ servicesHasClassesDex: 1 })).toMatch(/doesn't say whether/);
    // the refusal says what was and wasn't written: the stage's app storage, never lululemon's system
    const odexed = await refused({ servicesOdexFound: true });
    expect(odexed).toMatch(/precompiled copy/);
    expect(odexed).toMatch(/didn't change lululemon's system/);
    expect(odexed).not.toMatch(/Nothing was written/);
  });
});

describe('a bundle this browser kept', () => {
  const fiemapOf = (b: { meta: Record<string, unknown> }) => b.meta.fiemap as Record<string, unknown>;
  it('fits only while the agent reports services.jar in the same blocks', async () => {
    const b = await buildFakeGwsbBundle({ secret: SECRET });
    expect(keptBundleFits(b.bytes, { fiemap: fiemapOf(b) })).toBe(true);
    const moved = { ...fiemapOf(b), extents: [{ logical: 0, physical: 4096 * 900, length: 16384, flags: 1 }] };
    expect(keptBundleFits(b.bytes, { fiemap: moved })).toBe(false);
  });

  it('doesn\'t fit when the agent sends stock\'s boot animation and the kept copy came from an agent that didn\'t', async () => {
    const old = await buildFakeGwsbBundle({ secret: SECRET });
    expect(keptBundleFits(old.bytes, { fiemap: fiemapOf(old), bootAnimationSize: 22_901 })).toBe(false); // Bluetooth's short description
    expect(keptBundleFits(old.bytes, { fiemap: fiemapOf(old), bootAnimation: { size: 22_901 } })).toBe(false);
    expect(keptBundleFits(old.bytes, { fiemap: fiemapOf(old), bootAnimation: null })).toBe(true); // a Mirror with none
    const fresh = await buildFakeGwsbBundle({ secret: SECRET, metaPatch: { bootAnimation: { size: 22_901 } } });
    expect(keptBundleFits(fresh.bytes, { fiemap: fiemapOf(fresh), bootAnimationSize: 22_901 })).toBe(true);
  });

  it('doesn\'t fit when the agent\'s description carries no whole file map (it trims it for Bluetooth)', async () => {
    const b = await buildFakeGwsbBundle({ secret: SECRET });
    expect(keptBundleFits(b.bytes, { fiemap: { truncated: true, extentCount: 1 } })).toBe(false);
    expect(keptBundleFits(b.bytes, { compact: true })).toBe(false);
    expect(keptBundleFits(new Uint8Array([1, 2, 3]), { fiemap: fiemapOf(b) })).toBe(false);
  });
});

describe('the way back', () => {
  it('has nothing to undo over adb: it writes userdata fresh, so Device Owner, the hidden apps and the home screen go', async () => {
    expect(code.beforeRestore).toBeUndefined();
    expect(code.wayBack?.partitions).toEqual(['userdata']);
    const out = await code.wayBack!.build({ partitionBytes: { userdata: 64 * 2 ** 20 } });
    const fs = await openSparse(out.writes.userdata!);
    expect((await fs.readdir('/')).map((e) => e.name).filter((n) => n !== '.' && n !== '..').sort()).toEqual(['lost+found']);
    expect(await fs.exists(ADB_KEYS)).toBe(false); // stock asks for no key
    const root = await fs.stat('/');
    expect([root.mode & 0o7777, root.uid, root.gid]).toEqual([0o771, 1000, 1000]);
    await expect(code.wayBack!.build({ partitionBytes: {} })).rejects.toThrow(/userdata partition's size/);
  });

  it('an empty userdata is what factoryUserdata makes at any size', async () => {
    const fs = await openSparse(await factoryUserdata(32 * 2 ** 20, 1_700_000_000));
    expect(await fs.exists('/lost+found')).toBe(true);
    expect(await fs.exists('/misc')).toBe(false);
  });
});

describe('the build', () => {
  it.skipIf(!HAS_D8)('patches services.jar in its own blocks, with a restore of the same blocks Kiln\'s check accepts', async () => {
    const jar = buildSyntheticStockServicesJar();
    const padded = Math.ceil(jar.length / 4096) * 4096;
    const extents = [{ logical: 0, physical: 4096 * 5000, block: 5000, length: padded, blocks: padded / 4096, flags: 1 }];
    const b = await buildFakeGwsbBundle({ secret: SECRET, jarBytes: jar, extents });
    const read = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL });
    const built = await code.build(b.bytes, { adbKey: fakeAdbKey(4), userdataBytes: 64 * 2 ** 20, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL });
    const write = await bytes(built.writes['system-in-place']!);
    const restore = await bytes(built.restore);
    expect(() => checkInPlace({ write, restore, extents: read.extents, partitionBytes: SYSTEM_BYTES })).not.toThrow();
    const m = built.manifest as { servicesJar: { restoreSparse: string; originalSha256: string; patchedSha256: string; size: number }; deviceIdHash: string; systemBytes: number };
    expect(m.servicesJar).toMatchObject({ restoreSparse: 'restore-services.sparse', originalSha256: b.sha256, size: jar.length });
    // what Kiln reads back over adb after the restart (the bootloader can't): the patched jar, the same size as the stock one
    expect(built.readBack).toEqual([{ path: '/system/framework/services.jar', sha256: m.servicesJar.patchedSha256, size: jar.length }]);
    // a resume builds again before the app's install: the same bundle and key give the same jar, so its read back still matches
    const again = await code.build(b.bytes, { adbKey: fakeAdbKey(4), userdataBytes: 64 * 2 ** 20, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL });
    expect(again.readBack).toEqual(built.readBack);
    expect(m.systemBytes).toBe(SYSTEM_BYTES);
    expect(JSON.stringify(m)).not.toContain(FAKE_SERIAL);
    expect(built.files?.['services.jar.orig']).toEqual(jar);
    // USB debugging on from the start of boot, as the stage has it: stock's own switch comes too late for app-install
    const data = await openSparse(built.writes.userdata!);
    expect(new TextDecoder().decode(await data.readFile('/property/persist.sys.usb.config'))).toBe('adb');
    expect(new TextDecoder().decode(await data.readFile('/misc/adb/adb_keys'))).toBe(`${fakeAdbKey(4)}\n`);
  }, 60_000);

  describe('the boot animation', () => {
    const STOCK_BOOT_SIZE = 22_901; // stock 1.24.0's
    const stockBoot = Uint8Array.from({ length: STOCK_BOOT_SIZE }, (_, i) => (i * 7 + 3) & 0xff);
    const bootExtents = [{ logical: 0, physical: 4096 * 6000, block: 6000, length: 4096 * 6, blocks: 6, flags: 1 }];
    const compact = new Uint8Array(readFileSync(join(__dirname, '../../../../branding/lululemon-mirror/bootanimation-compact.zip')));
    /** The bundle with stock's boot animation as the agent sends it, signed with the rest. */
    async function withBoot(patch: Record<string, unknown> = {}) {
      const jar = buildSyntheticStockServicesJar();
      const padded = Math.ceil(jar.length / 4096) * 4096;
      const extents = [{ logical: 0, physical: 4096 * 5000, block: 5000, length: padded, blocks: padded / 4096, flags: 1 }];
      const bootAnimation = { path: '/system/media/bootanimation.zip', size: STOCK_BOOT_SIZE, sha256: createHash('sha256').update(stockBoot).digest('hex'),
        fiemap: { size: STOCK_BOOT_SIZE, extents: bootExtents }, base64: Buffer.from(stockBoot).toString('base64'), ...patch };
      return buildFakeGwsbBundle({ secret: SECRET, jarBytes: jar, extents, metaPatch: { bootAnimation } });
    }
    async function blocks(sparse: Blob, at: number, n: number): Promise<Uint8Array> {
      const { disk } = await readSparse(sparse.stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }));
      return disk.read(at, n);
    }

    it.skipIf(!HAS_D8)('goes in stock\'s blocks at stock\'s size, and the restore point puts lululemon\'s back', async () => {
      const b = await withBoot();
      const read = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL });
      expect(read.info.join('\n')).toContain(`bootanimation.zip ${STOCK_BOOT_SIZE} bytes`);
      expect(read.extents).toContainEqual({ physical: 4096 * 6000, length: 4096 * 6 });
      const built = await code.build(b.bytes, { adbKey: fakeAdbKey(4), userdataBytes: 64 * 2 ** 20, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL, assets: { 'mirror-bootanimation-compact': compact } });
      const write = await bytes(built.writes['system-in-place']!);
      const restore = await bytes(built.restore);
      expect(() => checkInPlace({ write, restore, extents: read.extents, partitionBytes: SYSTEM_BYTES })).not.toThrow();
      const ours = bootInPlaceZip(compact, STOCK_BOOT_SIZE);
      const written = await blocks(built.writes['system-in-place']!, 6000, 6);
      expect(Buffer.from(written.subarray(0, STOCK_BOOT_SIZE)).equals(Buffer.from(ours))).toBe(true);
      expect(written.subarray(STOCK_BOOT_SIZE).every((x) => x === 0)).toBe(true);
      const back = await blocks(built.restore, 6000, 6);
      expect(Buffer.from(back.subarray(0, STOCK_BOOT_SIZE)).equals(Buffer.from(stockBoot))).toBe(true);
      expect(built.files?.['bootanimation.zip.orig']).toEqual(stockBoot);
      expect((built.manifest as { bootAnimation: { size: number; extents: unknown[] } }).bootAnimation).toMatchObject({ size: STOCK_BOOT_SIZE, extents: bootExtents });
    }, 60_000);

    it.skipIf(!HAS_D8)('stays lululemon\'s when Kiln has no compact animation, and the write and restore leave its blocks alone', async () => {
      const b = await withBoot();
      const read = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL });
      const built = await code.build(b.bytes, { adbKey: fakeAdbKey(4), userdataBytes: 64 * 2 ** 20, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL });
      const write = await bytes(built.writes['system-in-place']!);
      const restore = await bytes(built.restore);
      expect(() => checkInPlace({ write, restore, extents: read.extents, partitionBytes: SYSTEM_BYTES })).not.toThrow();
      expect((await blocks(built.writes['system-in-place']!, 6000, 6)).every((x) => x === 0)).toBe(true); // untouched (DONT_CARE)
      expect((built.manifest as { bootAnimationLeft?: string }).bootAnimationLeft).toMatch(/compact boot animation/);
      expect(built.files?.['bootanimation.zip.orig']).toBeUndefined();
    }, 60_000);

    it.skipIf(!HAS_D8)('stays lululemon\'s when the bundle\'s copy doesn\'t check out, and says why', async () => {
      const b = await withBoot({ sha256: '0'.repeat(64) });
      const read = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL });
      expect(read.info.join('\n')).toContain('boot animation: stays lululemon\'s (its bytes don\'t match their SHA-256)');
      expect(read.extents).not.toContainEqual({ physical: 4096 * 6000, length: 4096 * 6 });
    }, 60_000);

    it('an agent from before it sent the boot animation builds all the same: the boot animation stays lululemon\'s', async () => {
      const b = await buildFakeGwsbBundle({ secret: SECRET });
      const read = await code.readBundle(b.bytes, { secret: SECRET, serial: FAKE_SERIAL });
      expect(read.info.join('\n')).toContain('boot animation: stays lululemon\'s (the setup agent didn\'t send it)');
    });
  });

  it('refuses an odexed build before it patches anything', async () => {
    const b = await buildFakeGwsbBundle({ secret: SECRET, servicesOdexFound: true });
    await expect(code.build(b.bytes, { adbKey: fakeAdbKey(4), userdataBytes: 64 * 2 ** 20, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL })).rejects.toThrow(BundleRefused);
  });
});

describe('the app\'s install', () => {
  it('installs Glassworks, makes it Device Owner, hides the stock apps, and reads updates as silent', async () => {
    const m = new FakeMirror({ kiosk: 'none' });
    const lines: string[] = [];
    const r = await installInPlace(m, { apk: fakeMirrorApk(), onStep: (s, d) => lines.push(s || `  ${d}`), sleep: async () => {} });
    expect(m.owner).toBe(GLASSWORKS.admin);
    expect(m.cmds).toContain(`dpm set-device-owner ${GLASSWORKS.admin}`);
    expect(m.cmds).toContain(`appops set ${GLASSWORKS.package} WRITE_SETTINGS allow`); // the setup hotspot on stock
    expect(m.cmds.filter((c) => c.startsWith('pm hide co.mirror') || c.startsWith('pm hide com.mirror'))).toHaveLength(8);
    expect(r.updates.state).toBe('on');
    expect(lines.join('\n')).toContain('Device Owner: set');
  });

  it('hands the app the image install\'s Home Assistant id before its first start, and lets it answer "Allow USB debugging?"', async () => {
    const m = new FakeMirror({ kiosk: 'none', serialno: FAKE_SERIAL });
    const lines: string[] = [];
    await installInPlace(m, { apk: fakeMirrorApk(), onStep: (s, d) => lines.push(s || `  ${d}`), sleep: async () => {} });
    const mac = await macFromSerial(FAKE_SERIAL);
    const put = m.cmds.indexOf(`settings put global ${INSTALLER_MAC_SETTING} ${mac}`);
    expect(put).toBeGreaterThan(-1);
    // before anything starts the app: its first start picks the id and keeps it
    expect(put).toBeLessThan(m.cmds.findIndex((c) => c.startsWith('am start') || c.startsWith('monkey')));
    expect(m.cmds).toContain(`pm grant ${GLASSWORKS.package} android.permission.READ_LOGS`);
    expect(m.cmds).toContain(`pm grant ${GLASSWORKS.package} ${USB_ALLOW_PERMISSION}`);
    expect(m.cmds).toContain(`settings put global ${USB_ALLOW_PERMISSION_SETTING} ${USB_ALLOW_PERMISSION}`);
    const log = lines.join('\n');
    expect(log).toContain('USB debugging: the Manage page can let in other computers');
    // the Glass Panes image's overlay defaults, over adb
    for (const c of ['settings put system screen_off_timeout 2147483647', 'settings put global stay_on_while_plugged_in 7', 'svc wifi enable']) expect(m.cmds).toContain(c);
    expect(log).not.toContain(mac!); // device-unique: the log names the setting only
  });

  it('without a serial, writes no id and says the app picks its own', async () => {
    const m = new FakeMirror({ kiosk: 'none' });
    const lines: string[] = [];
    await installInPlace(m, { apk: fakeMirrorApk(), onStep: (s, d) => lines.push(s || `  ${d}`), sleep: async () => {} });
    expect(m.cmds.some((c) => c.startsWith(`settings put global ${INSTALLER_MAC_SETTING}`))).toBe(false);
    expect(lines.join('\n')).toContain('Home Assistant id: no serial reported, so the app picks its own');
  });
});

describe('the simulated stock Mirror', () => {
  it('runs a stage, serves its bundle signed with the secret it read, and takes the surgical write', async () => {
    const dev = simInPlace.device({});
    const fb = dev.fastboot();
    expect(await fb.getvar('serialno')).toBe(FAKE_SERIAL);
    await fb.flashBlob('userdata', await simInPlace.code.stage({ adbKey: '', userdataBytes: 0, secret: SECRET, assets: {} }));
    await fb.reboot();
    expect(dev.staged()).toBe(true);
    const res = await dev.fetch('http://192.0.2.42:18765/stock-bundle.bin');
    const bundle = new Uint8Array(await res.arrayBuffer());
    const read = await simInPlace.code.readBundle(bundle, { secret: SECRET, serial: FAKE_SERIAL });
    const built = await simInPlace.code.build(bundle, { adbKey: '', userdataBytes: 0, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL });
    const write = await bytes(built.writes['system-in-place']!);
    const restore = await bytes(built.restore);
    expect(() => checkInPlace({ write, restore, extents: read.extents, partitionBytes: SYSTEM_BYTES })).not.toThrow();
    const fb2 = dev.fastboot();
    await fb2.flashBlob('system', built.writes['system-in-place']!);
    await fb2.reboot();
    expect(dev.staged()).toBe(false);
  });
});
