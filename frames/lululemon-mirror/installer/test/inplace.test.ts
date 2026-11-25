// The Mirror's in-place code (src/inplace.ts, src/stage.ts): the stage's files as the stock probe lays them out, the
// bundle's checks (signature, serial, odex stop-gate), the build Kiln checks with checkInPlace, and the app's install
// with Device Owner. Every serial, address and key is made up.
import { Disk, Ext4, MemoryStore, readSparse } from '@glassworks/ext4';
import { checkInPlace } from '@glassworks/kiln-core';
import { describe, expect, it } from 'vitest';
import design from '../../design/installer.json';
import {
  BundleRefused, DEFAULT_PROBE_APP_UID, STOCK_PROBE_ASSETS, STOCK_PROBE_LABELS, buildStageUserdata, installInPlace, mirrorInPlace,
} from '../src';
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
    const m = built.manifest as { servicesJar: { restoreSparse: string; originalSha256: string; size: number }; deviceIdHash: string; systemBytes: number };
    expect(m.servicesJar).toMatchObject({ restoreSparse: 'restore-services.sparse', originalSha256: b.sha256, size: jar.length });
    expect(m.systemBytes).toBe(SYSTEM_BYTES);
    expect(JSON.stringify(m)).not.toContain(FAKE_SERIAL);
    expect(built.files?.['services.jar.orig']).toEqual(jar);
  }, 60_000);

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
    expect(m.cmds.filter((c) => c.startsWith('pm hide co.mirror') || c.startsWith('pm hide com.mirror'))).toHaveLength(8);
    expect(r.updates.state).toBe('on');
    expect(lines.join('\n')).toContain('Device Owner: set');
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
