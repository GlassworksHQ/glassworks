// list-apps.ts: every app folder in a Mirror system.img (/app and /priv-app), with the package its APK
// names, its sharedUserId and whether it is persistent. READ-ONLY: it reads a local sparse system.img
// into a raw copy in a new temporary folder per run (removed after) and touches no device. For the
// recipe's UNUSED_APPS list (frames/lululemon-mirror/installer/src/unused.ts), whose folders must match the image.
//
//   npx -y tsx@4.23.15 tools/mirror/list-apps.ts ~/Downloads/system.img
import { closeSync, createReadStream, mkdtempSync, openSync, readSync, rmSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { axmlTree, zipEntry } from '@glassworks/app-adb';
import { Disk, Ext4, readSparse, type Store } from '@glassworks/ext4';

const image = process.argv[2];
if (!image) { console.error('usage: list-apps.ts <system.img>'); process.exit(2); }
const scratch = mkdtempSync(join(tmpdir(), 'list-apps-'));
const fd = openSync(join(scratch, 'system.raw'), 'w+');
const store: Store = {
  async read(o, n) { const b = new Uint8Array(n); readSync(fd, b, 0, n, o); return b; },
  async write(o, d) { writeSync(fd, d, 0, d.length, o); },
};
try {
  const { disk } = await readSparse(createReadStream(image, { highWaterMark: 1 << 20 }), (h) => new Disk(store, h.blockSize, h.blocks));
  const fs = await Ext4.open(disk);
  for (const root of ['/app', '/priv-app']) {
    for (const e of (await fs.readdir(root)).filter((d) => d.name !== '.' && d.name !== '..')) {
      const dir = `${root}/${e.name}`;
      const apk = (await fs.readdir(dir)).find((f) => f.name.endsWith('.apk'));
      if (!apk) { console.log(`${dir}\t-\t(no APK)`); continue; }
      const manifest = await zipEntry(await fs.readFile(`${dir}/${apk.name}`), 'AndroidManifest.xml');
      const top = manifest ? axmlTree(manifest).find((el) => el.name === 'manifest') : undefined;
      const app = top?.children.find((el) => el.name === 'application');
      const shared = top?.attrs['android:sharedUserId'];
      const persistent = app?.attrs['android:persistent'];
      console.log(`${dir}\t${top?.attrs.package ?? '?'}${shared ? `\tshared=${shared}` : ''}${persistent === true ? '\tpersistent' : typeof persistent === 'object' ? '\tpersistent=?' : ''}`);
    }
  }
} finally {
  closeSync(fd);
  rmSync(scratch, { recursive: true, force: true });
}
