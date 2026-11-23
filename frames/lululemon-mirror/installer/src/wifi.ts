// A community image set up from Kiln (#579 follow-up; maintainer, 2026-11-19), each change in a copy of
// a checked download, one file each:
// 0. this browser's adb key in place of the one its services.jar hardcodes (systemWithKey:
//    /framework/services.jar, only that fingerprint): image 3's lets in a key Glass Panes never published;
// 1. this browser's adb key in place of the published one (userdataWithKey: /misc/adb/adb_keys), as the
//    image's own installer does it since image 3, whose framework lets in a key listed there;
// 2. the person's network (userdataWithWifi: /misc/wifi/wpa_supplicant.conf, from the template in the
//    image's own system.img): Glass Panes ships with no saved network, and its guide has you join one
//    through scrcpy;
// 3. after the first start, Wi-Fi switched on over adb with this browser's key: `svc wifi enable`,
//    nothing else (wifiOn).
// Research: frames/lululemon-mirror/research/first-boot.md, "A community image's Wi-Fi".
import type { PanelShell } from '@glassworks/adb';
import { Disk, Ext4, Sha256, hex, readSparse, sparseStore, writeSparse, type RandomReadable, type Store } from '@glassworks/ext4';
import { WIFI_TEMPLATE, saveWifi, setAdbKeys, setImageKey } from './image';
import { WrongImage } from './flash';

/**
 * The image's wpa_supplicant.conf template (/system/etc/wifi/wpa_supplicant.conf), read from the
 * checked system.img where it lies (sparseStore: a few block reads, not the whole image).
 */
export async function wifiTemplate(system: RandomReadable): Promise<string> {
  const { header, store } = await sparseStore(system);
  const fs = await Ext4.open(new Disk(store, header.blockSize, header.blocks));
  if (!(await fs.exists(WIFI_TEMPLATE))) throw new Error(`this system image has no ${WIFI_TEMPLATE}, so Kiln can't save a network for it. Nothing was changed.`);
  return new TextDecoder().decode(await fs.readFile(WIFI_TEMPLATE));
}

/**
 * A copy of a sparse ext4 image (a userdata.sparse or a system.img) with one change (`edit`): reads it into `store` (unwritten bytes must read
 * as zeros), hashing every byte against `want` on the way when it is the download itself (`want`
 * undefined: a copy this page made from one), applies `edit`, and writes the result as a sparse image
 * to `sink`.
 */
async function copyImage(source: { size?: number; stream(): ReadableStream<Uint8Array> }, want: { name: string; size: number; sha256: string } | undefined,
  store: Store, edit: (fs: Ext4) => Promise<void>, sink: (b: Uint8Array) => Promise<void> | void, onProgress: (x: number) => void, what: string): Promise<void> {
  const sha = new Sha256();
  let read = 0;
  const size = want?.size ?? source.size ?? 1;
  const { disk } = await readSparse(source.stream(), (h) => new Disk(store, h.blockSize, h.blocks, { zeroed: true }),
    { onBytes: (b) => { if (want) sha.update(b); read += b.length; }, onProgress: (n) => onProgress(0.8 * Math.min(1, n / size)) });
  if (want) {
    const got = hex(sha.digest());
    if (read !== want.size || got !== want.sha256) throw new WrongImage(`the stored ${want.name} no longer checks out (SHA-256 ${got}), so Kiln didn't ${what} it`);
  }
  const fs = await Ext4.open(disk);
  await edit(fs);
  await fs.flush();
  await writeSparse(disk, sink, { onProgress: (b) => onProgress(0.8 + 0.2 * (b / disk.blocks)) });
}

/**
 * Copies a userdata.sparse with the person's network saved in it (saveWifi: one file). `want`: the
 * checked download's pin, hashed as it is read; undefined for a copy this page made from it (its
 * `adb-key` step's).
 */
export async function userdataWithWifi(source: { size?: number; stream(): ReadableStream<Uint8Array> }, want: { name: string; size: number; sha256: string } | undefined, store: Store,
  template: string, wifi: { ssid: string; password: string }, sink: (b: Uint8Array) => Promise<void> | void,
  onProgress: (x: number) => void = () => {}): Promise<void> {
  await copyImage(source, want, store, (fs) => saveWifi(fs, template, wifi), sink, onProgress, 'save the network in');
}

/**
 * Copies the checked userdata.sparse with `adbKey` (this browser's) as its only adb key, in place of the
 * one the image publishes (setAdbKeys: one file, its owner, mode and label kept).
 */
export async function userdataWithKey(source: { stream(): ReadableStream<Uint8Array> }, want: { name: string; size: number; sha256: string }, store: Store,
  adbKey: string, sink: (b: Uint8Array) => Promise<void> | void, onProgress: (x: number) => void = () => {}): Promise<void> {
  await copyImage(source, want, store, (fs) => setAdbKeys(fs, adbKey), sink, onProgress, 'put this browser\'s key in');
}

/**
 * Copies the checked system.img with its framework letting in `adbKey` (this browser's) in place of the
 * key its services.jar hardcodes (setImageKey: one file, one fingerprint in it). `store` holds the
 * expanded image (about 1.3 GB on the Mirror).
 */
export async function systemWithKey(source: { stream(): ReadableStream<Uint8Array> }, want: { name: string; size: number; sha256: string }, store: Store,
  adbKey: string, sink: (b: Uint8Array) => Promise<void> | void, onProgress: (x: number) => void = () => {}): Promise<void> {
  await copyImage(source, want, store, (fs) => setImageKey(fs, adbKey), sink, onProgress, 'put this browser\'s key in');
}

/**
 * After the first start, over adb with this browser's key (or the image's published one): waits for Android to finish
 * starting, then `svc wifi enable`, then reads back the Wi-Fi state machine's own state from
 * `dumpsys wifi` ("Wi-Fi is enabled": the driver and supplicant are up). `wifi_on` would only say the
 * switch was asked for (AOSP 6.0.1 WifiServiceImpl.dump). That one command is all it runs that
 * changes anything. `true` once Android says Wi-Fi is enabled, `false` after `confirmTries` seconds.
 */
export async function wifiOn(p: PanelShell, sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)), tries = 180, confirmTries = 60,
  callMs = 30_000): Promise<boolean> {
  const run = (cmd: string) => within(p, cmd, callMs);
  for (let i = 0; (await run('getprop sys.boot_completed')).out.trim() !== '1'; i++) {
    if (i >= tries) throw new Error('the Mirror didn\'t finish starting in 6 minutes');
    await sleep(2000);
  }
  await run('svc wifi enable');
  for (let i = 0; i < confirmTries; i++) {
    if (/^Wi-Fi is enabled$/m.test((await run('dumpsys wifi')).out)) return true;
    await sleep(1000);
  }
  return false;
}

/**
 * One adb command with a stall watchdog: a Mirror that stops answering ends in an error after `ms`, not a
 * wait forever (Kiln's engine contract). The command's stream is closed through the signal.
 */
export async function within(p: PanelShell, cmd: string, ms: number): Promise<{ out: string; code: number }> {
  const c = new AbortController();
  const stalled = new Promise<never>((_, reject) => c.signal.addEventListener('abort', () => reject(c.signal.reason), { once: true }));
  const t = setTimeout(() => c.abort(new Error(`the Mirror didn't answer \`${cmd}\` in ${Math.round(ms / 1000)} s`)), ms);
  try {
    return await Promise.race([p.run(cmd, c.signal), stalled]);
  } finally {
    clearTimeout(t);
  }
}
