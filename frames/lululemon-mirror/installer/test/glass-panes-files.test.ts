// Glass Panes' real files (the pinned release, mirror-android-3) through Kiln's userdata code (#579 follow-up). Skipped
// unless GP_DIR names a folder with system.img and userdata.sparse downloaded from the release
// (the files can't be in the repo: hard rule 3). Reads those two, checks them against the pins first,
// and writes only GP_DIR/with-wifi.sparse, GP_DIR/system-key.sparse and its scratch GP_DIR/system-key.scratch
// (deleted after). Touches no device. The network and the key are made up.
//   GP_DIR=/tmp/gp npx vitest run test/glass-panes-files.test.ts
// With GP_RAW=1 it also expands both to GP_DIR/before.raw and GP_DIR/after.raw (holes kept, about
// 5.2 GB each as files, little on disk) for `e2fsck -fn`: both clean, two more inodes after
// (2026-11-19: 14 → 16), and system.img to GP_DIR/system-before.raw and GP_DIR/system-after.raw
// (about 1.3 GB each). Delete them afterwards.
import { createHash } from 'node:crypto';
import { closeSync, ftruncateSync, openAsBlob, openSync, readSync, rmSync, writeFileSync, writeSync } from 'node:fs';
import { zipEntry } from '@glassworks/app-adb';
import { Disk, Ext4, MemoryStore, readSparse } from '@glassworks/ext4';
import { describe, expect, it } from 'vitest';
import { ADB_KEYS, GLASS_PANES, GLASS_PANES_KEY_FINGERPRINT, GLASS_PANES_USERDATA, IMAGE_KIOSK, IMAGE_OWNER_FINGERPRINT, SERVICES_JAR, WIFI_CONF, adbFingerprint, systemWithKey, userdataWithKey, userdataWithWifi, wifiTemplate } from '../src';
import { fakeAdbKey } from './fakes';

const dir = process.env.GP_DIR;
/** A sparse file expanded to a raw image on disk, its holes kept (zeros aren't written). */
async function expand(src: Blob, out: string): Promise<void> {
  const fd = openSync(out, 'w');
  try {
    const store = { async read(_o: number, n: number) { return new Uint8Array(n); }, async write(o: number, d: Uint8Array) { writeSync(fd, d, 0, d.length, o); } };
    const { disk } = await readSparse(src.stream(), (h) => new Disk(store, h.blockSize, h.blocks, { zeroed: true }));
    ftruncateSync(fd, disk.blocks * disk.blockSize); // the full size: extends with zeros, never overwrites a byte written
  } finally {
    closeSync(fd);
  }
}

const sha = async (b: Blob) => { const h = createHash('sha256'); for await (const c of b.stream() as unknown as AsyncIterable<Uint8Array>) h.update(c); return h.digest('hex'); };

describe.skipIf(!dir)('the real Glass Panes files (GP_DIR)', () => {
  it('the template comes out of system.img in place; this browser\'s key, then the network, go into userdata as one file each', async () => {
    const system = await openAsBlob(`${dir}/system.img`), userdata = await openAsBlob(`${dir}/userdata.sparse`);
    expect(await sha(system)).toBe(GLASS_PANES.system.sha256);
    expect(await sha(userdata)).toBe(GLASS_PANES_USERDATA.sha256);
    const template = await wifiTemplate(system);
    expect(template).toMatch(/^update_config=1$/m);
    // as the recipe runs it: this browser's key in place of the project key the release ships, then the network on that copy
    const published = await Ext4.open((await readSparse(userdata.stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }))).disk);
    expect(adbFingerprint(new TextDecoder().decode(await published.readFile(ADB_KEYS)))).toBe(GLASS_PANES_KEY_FINGERPRINT); // read 2026-11-22
    const before = await published.stat(ADB_KEYS);
    const keyParts: Uint8Array[] = [];
    const KEY = fakeAdbKey(3, 'kiln@test');
    await userdataWithKey(userdata, GLASS_PANES_USERDATA, new MemoryStore(), KEY, (b) => { keyParts.push(b.slice()); });
    const keyed = new Blob(keyParts as BlobPart[]);
    const parts: Uint8Array[] = [];
    await userdataWithWifi(keyed, undefined, new MemoryStore(), template, { ssid: 'Example Net', password: 'example-password' }, (b) => { parts.push(b.slice()); });
    const out = new Blob(parts as BlobPart[]);
    writeFileSync(`${dir}/with-wifi.sparse`, new Uint8Array(await out.arrayBuffer()));
    const { disk } = await readSparse(out.stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }));
    const fs = await Ext4.open(disk);
    expect((await fs.readdir('/misc')).map((e) => e.name).sort()).toEqual(['.', '..', 'adb', 'wifi']);
    expect((await fs.readdir('/misc/wifi')).map((e) => e.name).sort()).toEqual(['.', '..', 'wpa_supplicant.conf']);
    const st = await fs.stat(WIFI_CONF);
    expect([st.mode.toString(8), st.uid, st.gid, await fs.label(WIFI_CONF)]).toEqual(['660', 1000, 1010, 'u:object_r:wifi_data_file:s0']);
    // the key: this one alone, with the file's owner, mode and label as the release has them
    expect(new TextDecoder().decode(await fs.readFile(ADB_KEYS))).toBe(`${KEY}\n`);
    const k = await fs.stat(ADB_KEYS);
    expect([k.mode, k.uid, k.gid, await fs.label(ADB_KEYS)]).toEqual([before.mode, before.uid, before.gid, await published.label(ADB_KEYS)]);
    const conf = new TextDecoder().decode(await fs.readFile(WIFI_CONF));
    expect(conf).toMatch(/psk=[0-9a-f]{64}/);
    expect(conf).not.toContain('example-password');
    if (process.env.GP_RAW) {
      await expand(userdata, `${dir}/before.raw`);
      await expand(out, `${dir}/after.raw`);
    }
  }, 600_000);

  it('this browser\'s key goes into system.img\'s services.jar in place of the one it hardcodes: one file changed', async () => {
    const system = await openAsBlob(`${dir}/system.img`);
    const KEY = fakeAdbKey(3, 'kiln@test');
    const scratchPath = `${dir}/system-key.scratch`;
    const fd = openSync(scratchPath, 'w+');
    const out = openSync(`${dir}/system-key.sparse`, 'w');
    try {
      const store = { async read(o: number, n: number) { const b = new Uint8Array(n); readSync(fd, b, 0, n, o); return b; }, async write(o: number, d: Uint8Array) { writeSync(fd, d, 0, d.length, o); } };
      let at = 0;
      await systemWithKey(system, GLASS_PANES.system, store, KEY, (b) => { writeSync(out, b, 0, b.length, at); at += b.length; });
    } finally {
      closeSync(fd); closeSync(out); rmSync(scratchPath, { force: true });
    }
    const read = async (b: Blob) => Ext4.open((await readSparse(b.stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }))).disk);
    const [before, after] = [await read(system), await read(await openAsBlob(`${dir}/system-key.sparse`))];
    const dexOf = async (fs: Ext4) => Buffer.from((await zipEntry(await fs.readFile(SERVICES_JAR), 'classes.dex'))!);
    expect((await dexOf(before)).includes(IMAGE_OWNER_FINGERPRINT)).toBe(true); // read 2026-11-22
    const dex = await dexOf(after);
    expect(dex.includes(IMAGE_OWNER_FINGERPRINT)).toBe(false);
    expect(dex.includes(adbFingerprint(KEY))).toBe(true);
    const [b, a] = [await before.stat(SERVICES_JAR), await after.stat(SERVICES_JAR)];
    expect([a.mode, a.uid, a.gid, await after.label(SERVICES_JAR)]).toEqual([b.mode, b.uid, b.gid, await before.label(SERVICES_JAR)]);
    // the image's own kiosk stays: this is Glass Panes as released, but for who it lets in
    expect(await after.exists(IMAGE_KIOSK.dir)).toBe(true);
    expect(new TextDecoder().decode(await after.readFile('/build.prop'))).toBe(new TextDecoder().decode(await before.readFile('/build.prop')));
    if (process.env.GP_RAW) {
      await expand(system, `${dir}/system-before.raw`);
      await expand(await openAsBlob(`${dir}/system-key.sparse`), `${dir}/system-after.raw`);
    }
  }, 900_000);
});
