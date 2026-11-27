// The Mirror's in-place code (src/inplace.ts, src/stage.ts): the stage's files as the stock probe lays them out, the
// bundle's checks (signature, serial, odex stop-gate), the build Kiln checks with checkInPlace, and the app's install
// with Device Owner. Every serial, address and key is made up.
import { Disk, Ext4, MemoryStore, readSparse } from '@glassworks/ext4';
import { checkInPlace, checkRestorePoint, recordRestoreFiles, sha256Hex } from '@glassworks/kiln-core';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { bootInPlaceZip } from '../src/bootstill';
import design from '../../design/installer.json';
import {
  BundleRefused, DEFAULT_PROBE_APP_UID, STOCK_PROBE_ASSETS, STOCK_PROBE_LABELS, buildStageUserdata, installInPlace, mirrorInPlace, restoredFiles,
} from '../src';
import { ADB_KEYS, AID, LABELS, WIFI_CONF, macFromSerial, wpaSupplicantConf } from '../src/image';
import { DEFAULT_WIFI_TEMPLATE } from '../src/stage';
import { INSTALLER_MAC_SETTING, USB_ALLOW_PERMISSION_SETTING, factoryUserdata, glassworksInPlace, keepWifiOn, keptBundleFits, wifiAddress } from '../src/inplace';
import { USB_ALLOW_PERMISSION } from '../src/stock';
import type { PanelShell } from '@glassworks/adb';
import { GLASSWORKS } from '../src/app';
import { simInPlace } from '../src/sim';
import { SYSTEM_BYTES } from '../src/flash';
import { FAKE_SERIAL, FakeMirror, buildFakeGwsbBundle, fakeAdbKey, fakeMirrorApk } from './fakes';
import { HAS_D8, buildSyntheticStockServicesJar } from './synthetic-services';

const SECRET = Uint8Array.from({ length: 32 }, (_, i) => i + 1);
/** The Mirror's /system/etc/wifi/wpa_supplicant.conf, as `cat` printed it on stock 1.x (2026-11-26, research/stock-firmware-probe.md). */
const MIRROR_WIFI_TEMPLATE = 'update_config=1\neapol_version=1\nap_scan=1\nfast_reauth=1\npmf=1\np2p_no_group_iface=1\n';
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
    // the network as Android's Wi-Fi reads it on the agent's first start: the Mirror's own template, then the network
    // with its PSK, system:wifi 0660. The template is the Mirror's file as read, not the constant, so a change to the
    // constant fails here.
    expect(DEFAULT_WIFI_TEMPLATE).toBe(MIRROR_WIFI_TEMPLATE);
    expect(new TextDecoder().decode(await fs.readFile(WIFI_CONF))).toBe(await wpaSupplicantConf(MIRROR_WIFI_TEMPLATE, { ssid: 'Home Net', password: 'not-a-real-password' }));
    const conf = await fs.stat(WIFI_CONF);
    expect([conf.mode & 0o7777, conf.uid, conf.gid]).toEqual([0o660, AID.system, AID.wifi]);
    expect([await fs.label('/misc/wifi'), await fs.label(WIFI_CONF)]).toEqual([LABELS.wifi, LABELS.wifi]);
  });

  it('without a network, leaves Wi-Fi alone: no saved network and no switch, so stock\'s own Wi-Fi comes up as usual', async () => {
    const blob = await buildStageUserdata({ adbKey: fakeAdbKey(3), userdataBytes: 64 * 2 ** 20, secret: SECRET, assets, time: 1_700_000_000 });
    const fs = await openSparse(blob);
    expect(await fs.exists('/app/dev.glassworks.stockprobe-1/enable_wifi')).toBe(false);
    expect(await fs.exists(WIFI_CONF)).toBe(false);
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
  });

  it('fits with a boot animation only while the agent reports it, whole, in the same blocks', async () => {
    const anim = { size: 22_901, extents: [{ logical: 0, physical: 4096 * 6000, length: 4096 * 6, flags: 1 }] };
    const fresh = await buildFakeGwsbBundle({ secret: SECRET, metaPatch: { bootAnimation: { size: 22_901, fiemap: anim } } });
    expect(keptBundleFits(fresh.bytes, { fiemap: fiemapOf(fresh), bootAnimation: { size: 22_901, fiemap: anim } })).toBe(true);
    const moved = { ...anim, extents: [{ logical: 0, physical: 4096 * 7000, length: 4096 * 6, flags: 1 }] };
    expect(keptBundleFits(fresh.bytes, { fiemap: fiemapOf(fresh), bootAnimation: { size: 22_901, fiemap: moved } })).toBe(false);
    expect(keptBundleFits(fresh.bytes, { fiemap: fiemapOf(fresh), bootAnimation: { size: 22_901, fiemap: { truncated: true } } })).toBe(false);
    expect(keptBundleFits(fresh.bytes, { fiemap: fiemapOf(fresh), bootAnimationSize: 22_901 })).toBe(false); // Bluetooth's: no file map to compare
    expect(keptBundleFits(fresh.bytes, { fiemap: fiemapOf(fresh) })).toBe(false);
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
      // read back over adb after the restart, with services.jar
      expect(built.readBack).toContainEqual({ path: '/system/media/bootanimation.zip', sha256: createHash('sha256').update(ours).digest('hex'), size: STOCK_BOOT_SIZE });
      // the way back's check before it writes (kiln-core restore.ts) takes this build's restore point as Kiln keeps it:
      // the restore image puts back exactly the original jar and boot animation, and nothing else
      const files = [{ filename: 'restore-services.sparse', data: restore }, ...Object.entries(built.files ?? {}).map(([filename, data]) => ({ filename, data }))];
      const manifest = { ...built.manifest, kiln: { files: await recordRestoreFiles(files) } };
      const restored = restoredFiles(manifest);
      expect(restored.map((f) => [f.path, f.file])).toEqual([['/system/framework/services.jar', 'services.jar.orig'], ['/system/media/bootanimation.zip', 'bootanimation.zip.orig']]);
      expect(restored[1]!.written).toEqual({ sha256: createHash('sha256').update(ours).digest('hex') });
      const lines = await checkRestorePoint({ files, sparse: 'restore-services.sparse', manifest, restored, partitionBytes: SYSTEM_BYTES, partition: 'system', serial: FAKE_SERIAL });
      expect(lines.filter((x) => x.startsWith('restore image: puts back the original'))).toHaveLength(2);
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
      expect(built.readBack?.map((r) => r.path)).toEqual(['/system/framework/services.jar']);
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
    expect(lines).toContain('  Wi-Fi: on');
    expect(m.cmds).not.toContain('settings put global wifi_on 1'); // on already: left alone
    expect(m.cmds).not.toContain('svc wifi enable'); // stock's shell lacks CHANGE_WIFI_STATE, and is killed for it
  });

  it('puts Wi-Fi\'s switch back on when a fast start left it off, for the next start', async () => {
    const m = new FakeMirror({ kiosk: 'none', wifiOn: '0' });
    const lines: string[] = [];
    await installInPlace(m, { apk: fakeMirrorApk(), onStep: (s, d) => lines.push(s || `  ${d}`), sleep: async () => {} });
    expect(m.cmds).toContain('settings put global wifi_on 1');
    expect(m.wifiOn).toBe('1');
    expect(lines).toContain('  Wi-Fi: was off, on from the next start');
  });

  it('says what it found about Wi-Fi, and changes nothing else', async () => {
    const said = async (o: Partial<{ out: string; code: number }>, put?: { out: string; code: number }, after?: string) => {
      const cmds: string[] = [];
      let wrote = false;
      const p = { run: async (c: string) => {
        cmds.push(c);
        if (c.startsWith('settings put')) { wrote = true; return put ?? { out: '', code: 0 }; }
        return wrote ? { out: after ?? '1\n', code: 0 } : { out: '', code: 0, ...o };
      } } as unknown as PanelShell;
      return { line: await keepWifiOn(p), cmds };
    };
    expect(await said({ out: '3\n' })).toEqual({ line: 'Wi-Fi: off for airplane mode, left as it is', cmds: ['settings get global wifi_on'] });
    expect(await said({ out: '2\n' })).toMatchObject({ line: 'Wi-Fi: on' });
    expect(await said({ out: 'Killed\n', code: 137 })).toEqual({ line: 'Wi-Fi: switch not read (Killed), left as it is', cmds: ['settings get global wifi_on'] });
    expect(await said({ out: 'null\n' })).toMatchObject({ line: 'Wi-Fi: switch not read (null), left as it is' });
    expect((await said({ out: '0\n' }, { out: 'Killed\n', code: 137 })).line).toBe('Wi-Fi: off, and not set (Killed)');
    // a write that exits 0 but didn't take: the read-back says so
    expect((await said({ out: '0\n' }, { out: '', code: 0 }, '0\n')).line).toBe('Wi-Fi: off, and not set (it reads 0 after the write)');
    expect(await said({ out: '0\n' })).toEqual({ line: 'Wi-Fi: was off, on from the next start', cmds: ['settings get global wifi_on', 'settings put global wifi_on 1', 'settings get global wifi_on'] });
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
    for (const c of ['settings put system screen_off_timeout 2147483647', 'settings put global stay_on_while_plugged_in 7']) expect(m.cmds).toContain(c);
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

describe('a reinstall, and stock\'s hotspot', () => {
  // the install's services.jar empties MirrorApService.systemReady(): with Glassworks on, mirror-<serial> never opens
  it('counts the hotspot gone once Glassworks is installed in place, and only then', async () => {
    const code = mirrorInPlace(design.device.words);
    expect(code.channels.lan?.hotspot).toBeTruthy();
    expect(await code.ownWifiGone!(new FakeMirror({ kiosk: 'none', installed: 600 }))).toBe(true);
    expect(await code.ownWifiGone!(new FakeMirror({ kiosk: 'none' }))).toBe(false); // stock (the agent's stage, say)
  });

  it('counts what it can\'t read as no', async () => {
    const shell = (r: { out: string; code: number } | Error) => ({ run: async () => { if (r instanceof Error) throw r; return r; } }) as unknown as PanelShell;
    expect(await glassworksInPlace(shell(new Error('closed')))).toBe(false);
    expect(await glassworksInPlace(shell({ out: '', code: 1 }))).toBe(false);
    // `pm list packages` matches by substring: a package whose name only starts with ours isn't ours
    expect(await glassworksInPlace(shell({ out: `package:${GLASSWORKS.package}.other\n`, code: 0 }))).toBe(false);
    expect(await glassworksInPlace(shell({ out: `package:${GLASSWORKS.package}\n`, code: 0 }))).toBe(true);
  });

  it('reads the Mirror\'s Wi-Fi address for the reinstall\'s download by hand, and nothing that isn\'t one', async () => {
    const shell = (answers: Record<string, { out: string; code: number } | Error>) => ({
      run: async (c: string) => { const r = answers[c] ?? { out: '', code: 1 }; if (r instanceof Error) throw r; return r; },
    }) as unknown as PanelShell;
    expect(mirrorInPlace(design.device.words).lanAddress).toBeTypeOf('function');
    expect(await wifiAddress(shell({ 'getprop dhcp.wlan0.ipaddress': { out: '192.0.2.44\n', code: 0 } }))).toBe('192.0.2.44');
    expect(await wifiAddress(shell({ 'getprop dhcp.wlan0.ipaddress': { out: '\n', code: 0 },
      'ip -4 -o addr show wlan0': { out: '4: wlan0    inet 192.0.2.45/24 brd 192.0.2.255 scope global wlan0\n', code: 0 } }))).toBe('192.0.2.45');
    expect(await wifiAddress(shell({ 'getprop dhcp.wlan0.ipaddress': { out: '999.1.1.1', code: 0 } }))).toBeUndefined();
    expect(await wifiAddress(shell({ 'getprop dhcp.wlan0.ipaddress': new Error('closed'), 'ip -4 -o addr show wlan0': new Error('closed') }))).toBeUndefined();
    expect(await wifiAddress(shell({}))).toBeUndefined(); // on Ethernet only: no Wi-Fi address
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

describe('the restore point\'s files, for the way back\'s check', () => {
  const jar = { path: '/system/framework/services.jar', size: 10, originalSha256: 'AB'.repeat(32), patchedSha256: 'cd'.repeat(32), origFile: 'services.jar.orig',
    extents: [{ logical: 0, physical: 4096, block: 1, length: 4096, blocks: 1, flags: 1 }] };

  it('reads services.jar, and the boot animation when the install changed it, from the manifest', async () => {
    expect(restoredFiles({ servicesJar: jar })).toEqual([{ path: jar.path, file: 'services.jar.orig', size: 10, sha256: 'ab'.repeat(32),
      extents: [{ logical: 0, physical: 4096, length: 4096 }], written: { sha256: 'cd'.repeat(32) } }]);
    const boot = { ...jar, path: '/system/media/bootanimation.zip', origFile: 'bootanimation.zip.orig' };
    expect(restoredFiles({ servicesJar: jar, bootAnimation: boot }).map((f) => f.file)).toEqual(['services.jar.orig', 'bootanimation.zip.orig']);
    // a restore point that doesn't record what was written: only the original is checked against
    const { patchedSha256: _, ...noWritten } = jar;
    expect(restoredFiles({ servicesJar: noWritten })[0]!.written).toBeUndefined();
  });

  it('throws on a manifest that doesn\'t describe them', () => {
    expect(() => restoredFiles({})).toThrow('The restore point\'s manifest doesn\'t describe services.jar.');
    expect(() => restoredFiles({ servicesJar: { ...jar, origFile: undefined } })).toThrow(/describe services\.jar/);
    // the paths are the Mirror's own: a manifest can't point Kiln's adb at anything else, or slip shell syntax in
    expect(() => restoredFiles({ servicesJar: { ...jar, path: '/nope; reboot' } })).toThrow('The restore point\'s manifest puts services.jar somewhere other than /system/framework/services.jar.');
    expect(() => restoredFiles({ servicesJar: jar, bootAnimation: { ...jar, path: '/system/framework/services.jar', origFile: 'b' } }))
      .toThrow('The restore point\'s manifest puts the boot animation somewhere other than /system/media/bootanimation.zip.');
    expect(() => restoredFiles({ servicesJar: { ...jar, extents: [{ logical: 0 }] } })).toThrow(/describe services\.jar/);
    expect(() => restoredFiles({ servicesJar: jar, bootAnimation: { size: 1 } })).toThrow('The restore point\'s manifest doesn\'t describe the boot animation.');
  });

  it('the simulated Mirror\'s build keeps the same shape, so Kiln\'s tests check what a real restore point holds', async () => {
    const b = await buildFakeGwsbBundle({ secret: SECRET });
    const built = await simInPlace.code.build(b.bytes, { adbKey: fakeAdbKey(4), userdataBytes: 64 * 2 ** 20, partitionBytes: { system: SYSTEM_BYTES }, serial: FAKE_SERIAL });
    const [f] = restoredFiles(built.manifest!);
    expect(f!.sha256).toBe(await sha256Hex(built.files![f!.file]!));
  });
});
