// A community image's Wi-Fi, set up from Kiln (#579 follow-up; maintainer, 2026-11-19). Glass Panes
// ships with Wi-Fi off and no saved network (its userdata has no /misc/wifi, read 2026-11-19), and
// its guide has you switch Wi-Fi on in Settings through scrcpy. Kiln instead:
// 1. saves the person's network in a copy of the checked userdata.sparse (saveWifi: one file,
//    /misc/wifi/wpa_supplicant.conf, from the template in the image's own system.img), and
// 2. after the first start, switches Wi-Fi on over adb with the image's published project key:
//    `svc wifi enable`, nothing else (wifiOn).
// Research: frames/lululemon-mirror/research/first-boot.md, "A community image's Wi-Fi".
import type { PanelShell } from '@glassworks/adb';
import { Disk, Ext4, Sha256, hex, readSparse, sparseStore, writeSparse, type RandomReadable, type Store } from '@glassworks/ext4';
import { WIFI_TEMPLATE, saveWifi } from './image';
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
 * Copies the checked userdata.sparse with the person's network saved in it: reads it into `store`
 * (unwritten bytes must read as zeros), hashing every byte against `want` on the way, saves the
 * network (saveWifi: one file), and writes the result as a sparse image to `sink`.
 */
export async function userdataWithWifi(source: { stream(): ReadableStream<Uint8Array> }, want: { name: string; size: number; sha256: string }, store: Store,
  template: string, wifi: { ssid: string; password: string }, sink: (b: Uint8Array) => Promise<void> | void,
  onProgress: (x: number) => void = () => {}): Promise<void> {
  const sha = new Sha256();
  let read = 0;
  const { disk } = await readSparse(source.stream(), (h) => new Disk(store, h.blockSize, h.blocks, { zeroed: true }),
    { onBytes: (b) => { sha.update(b); read += b.length; }, onProgress: (n) => onProgress(0.8 * (n / want.size)) });
  const got = hex(sha.digest());
  if (read !== want.size || got !== want.sha256) throw new WrongImage(`the stored ${want.name} no longer checks out (SHA-256 ${got}), so Kiln didn't save the network in it`);
  const fs = await Ext4.open(disk);
  await saveWifi(fs, template, wifi);
  await fs.flush();
  await writeSparse(disk, sink, { onProgress: (b) => onProgress(0.8 + 0.2 * (b / disk.blocks)) });
}

/**
 * After the first start, over adb with the image's own published key: waits for Android to finish
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
async function within(p: PanelShell, cmd: string, ms: number): Promise<{ out: string; code: number }> {
  const c = new AbortController();
  const stalled = new Promise<never>((_, reject) => c.signal.addEventListener('abort', () => reject(c.signal.reason), { once: true }));
  const t = setTimeout(() => c.abort(new Error(`the Mirror didn't answer \`${cmd}\` in ${Math.round(ms / 1000)} s`)), ms);
  try {
    return await Promise.race([p.run(cmd, c.signal), stalled]);
  } finally {
    clearTimeout(t);
  }
}
