// image-facts.ts: what Kiln's Mirror patch depends on in a Glass Panes release, read from its files.
// READ-ONLY: it reads a local sparse system.img (and, if given, userdata.sparse) into raw copies in a
// new temporary folder per run (removed after) and touches no device. For pinning a new image: compare
// its answers with frames/lululemon-mirror/installer/src (image.ts, services.ts, unused.ts) and
// frames/lululemon-mirror/research/image-patch.md.
//
//   npx -y tsx@4.23.15 tools/mirror/image-facts.ts ~/Downloads/system.img [~/Downloads/userdata.sparse]
import { closeSync, createReadStream, mkdtempSync, openSync, readSync, rmSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { zipEntry } from '@glassworks/app-adb';
import { Disk, Ext4, readSparse, type Store } from '@glassworks/ext4';
import { ADB_KEYS, GLASS_PANES, GLASS_PANES_KEY_FINGERPRINT, IMAGE_KIOSK, IMAGE_OWNER_FINGERPRINT, SERVICES_JAR, UNUSED_APPS, WIFI_TEMPLATE, adbFingerprint } from '@glassworks/lululemon-mirror-installer';

const [systemPath, userdataPath] = process.argv.slice(2);
if (!systemPath) { console.error('usage: image-facts.ts <system.img> [userdata.sparse]'); process.exit(2); }

/** The strings of a dex 035, by index (string_ids_size at 0x38, string_ids_off at 0x3c; MUTF-8 read as Latin-1). */
function dexStrings(dex: Uint8Array): string[] {
  const dv = new DataView(dex.buffer, dex.byteOffset, dex.byteLength);
  const n = dv.getUint32(0x38, true), off = dv.getUint32(0x3c, true);
  return Array.from({ length: n }, (_, i) => {
    let at = dv.getUint32(off + 4 * i, true);
    while (dex[at]! & 0x80) at++; // the uleb128 length
    at++;
    let end = at;
    while (dex[end] !== 0) end++;
    return Buffer.from(dex.subarray(at, end)).toString('latin1');
  });
}

const scratch = mkdtempSync(join(tmpdir(), 'image-facts-'));
async function open(path: string, name: string): Promise<{ fs: Ext4; fd: number }> {
  const fd = openSync(join(scratch, name), 'w+');
  const store: Store = {
    async read(o, n) { const b = new Uint8Array(n); readSync(fd, b, 0, n, o); return b; },
    async write(o, d) { writeSync(fd, d, 0, d.length, o); },
  };
  const { disk } = await readSparse(createReadStream(path, { highWaterMark: 1 << 20 }), (h) => new Disk(store, h.blockSize, h.blocks, { zeroed: true }));
  return { fs: await Ext4.open(disk), fd };
}

const FINGERPRINT = /^([0-9A-F]{2}:){15}[0-9A-F]{2}$/;
try {
  const sys = await open(systemPath, 'system.raw');
  const prop = new TextDecoder().decode(await sys.fs.readFile('/build.prop'));
  const fingerprint = /^ro\.build\.fingerprint=(.*)$/m.exec(prop)?.[1];
  console.log(`build fingerprint: ${fingerprint}${fingerprint === GLASS_PANES.fingerprint ? ' (the pinned image)' : ' (NOT the pinned image)'}`);
  const dex = await zipEntry(await sys.fs.readFile(SERVICES_JAR), 'classes.dex');
  const strings = dex ? dexStrings(dex) : [];
  const keys = strings.filter((s) => FINGERPRINT.test(s));
  console.log(`services.jar key fingerprints: ${keys.join(', ') || 'none'}`);
  for (const k of keys) console.log(`  ${k}: ${k === IMAGE_OWNER_FINGERPRINT ? 'the one Kiln retargets' : k === GLASS_PANES_KEY_FINGERPRINT ? 'the published project key' : 'unknown to Kiln'}`);
  for (const s of ['Authorizing the Mirror owner\'s ADB key', 'Authorizing an ADB key listed in adb_keys']) console.log(`services.jar logs "${s}": ${strings.includes(s)}`);
  console.log(`its own kiosk (${IMAGE_KIOSK.dir}): ${await sys.fs.exists(IMAGE_KIOSK.dir)}`);
  const unused = [];
  for (const u of UNUSED_APPS) if (await sys.fs.exists(u.dir)) unused.push(u.package);
  console.log(`unused apps still in it: ${unused.length} of ${UNUSED_APPS.length}`);
  console.log(`Wi-Fi template (${WIFI_TEMPLATE}): ${await sys.fs.exists(WIFI_TEMPLATE)}`);
  closeSync(sys.fd);
  if (userdataPath) {
    const ud = await open(userdataPath, 'userdata.raw');
    if (await ud.fs.exists(ADB_KEYS)) {
      const st = await ud.fs.stat(ADB_KEYS);
      const lines = new TextDecoder().decode(await ud.fs.readFile(ADB_KEYS)).split('\n').filter((l) => l.trim());
      console.log(`userdata ${ADB_KEYS}: ${st.mode.toString(8)} ${st.uid}:${st.gid} ${await ud.fs.label(ADB_KEYS)}`);
      for (const l of lines) { const f = adbFingerprint(l); console.log(`  ${f}${f === GLASS_PANES_KEY_FINGERPRINT ? ' (the published project key)' : ''}`); }
    } else console.log(`userdata ${ADB_KEYS}: none`);
    closeSync(ud.fd);
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
