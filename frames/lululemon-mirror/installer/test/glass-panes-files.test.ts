// Glass Panes' real mirror-android-2 files through Kiln's Wi-Fi code (#579 follow-up). Skipped
// unless GP_DIR names a folder with system.img and userdata.sparse downloaded from the release
// (the files can't be in the repo: hard rule 3). Reads those two, checks them against the pins first,
// and writes only GP_DIR/with-wifi.sparse. Touches no device. The network is made up.
//   GP_DIR=/tmp/gp npx vitest run test/glass-panes-files.test.ts
// With GP_RAW=1 it also expands both to GP_DIR/before.raw and GP_DIR/after.raw (holes kept, about
// 5.2 GB each as files, little on disk) for `e2fsck -fn`: both clean, two more inodes after
// (2026-11-19: 14 → 16). Delete them afterwards.
import { createHash } from 'node:crypto';
import { closeSync, ftruncateSync, openAsBlob, openSync, writeFileSync, writeSync } from 'node:fs';
import { Disk, Ext4, MemoryStore, readSparse } from '@glassworks/ext4';
import { describe, expect, it } from 'vitest';
import { GLASS_PANES, GLASS_PANES_USERDATA, WIFI_CONF, userdataWithWifi, wifiTemplate } from '../src';

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
  it('the template comes out of system.img in place, and the network goes into userdata as one file', async () => {
    const system = await openAsBlob(`${dir}/system.img`), userdata = await openAsBlob(`${dir}/userdata.sparse`);
    expect(await sha(system)).toBe(GLASS_PANES.system.sha256);
    expect(await sha(userdata)).toBe(GLASS_PANES_USERDATA.sha256);
    const template = await wifiTemplate(system);
    expect(template).toMatch(/^update_config=1$/m);
    const parts: Uint8Array[] = [];
    await userdataWithWifi(userdata, GLASS_PANES_USERDATA, new MemoryStore(), template, { ssid: 'Example Net', password: 'example-password' }, (b) => { parts.push(b.slice()); });
    const out = new Blob(parts as BlobPart[]);
    writeFileSync(`${dir}/with-wifi.sparse`, new Uint8Array(await out.arrayBuffer()));
    const { disk } = await readSparse(out.stream(), (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }));
    const fs = await Ext4.open(disk);
    expect((await fs.readdir('/misc')).map((e) => e.name).sort()).toEqual(['.', '..', 'adb', 'wifi']);
    expect((await fs.readdir('/misc/wifi')).map((e) => e.name).sort()).toEqual(['.', '..', 'wpa_supplicant.conf']);
    const st = await fs.stat(WIFI_CONF);
    expect([st.mode.toString(8), st.uid, st.gid, await fs.label(WIFI_CONF)]).toEqual(['660', 1000, 1010, 'u:object_r:wifi_data_file:s0']);
    const conf = new TextDecoder().decode(await fs.readFile(WIFI_CONF));
    expect(conf).toMatch(/psk=[0-9a-f]{64}/);
    expect(conf).not.toContain('example-password');
    if (process.env.GP_RAW) {
      await expand(userdata, `${dir}/before.raw`);
      await expand(out, `${dir}/after.raw`);
    }
  }, 600_000);
});
