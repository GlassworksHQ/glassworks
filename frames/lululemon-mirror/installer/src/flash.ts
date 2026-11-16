// The Mirror in fastboot (its locked LK, reached with the button inside its back): what Kiln reads
// before writing, the images it builds for it, the writes, and the first boot over adb with Kiln's
// own key. The Mirror's LK has no read-back and no `fastboot boot`, so there is no backup of
// lululemon's software: the screens say so before anything is written (#40).
import type { PanelShell } from '@glassworks/adb';
import { PKG, Steps, grantPermissions, kioskVersion, sendWifi, startApp, type StepFn } from '@glassworks/app-adb';
import { Disk, Ext4, MemoryStore, Sha256, hex, readSparse, writeSparse, type Store } from '@glassworks/ext4';
import { ProxyError } from './download';
import { GLASS_PANES, buildUserdata, patchSystem, type SystemPatch } from './image';
import type { Check } from './recipe';

/** The Mirror's LK in fastboot, as the Glass Panes install guide reads it on a Mac (not yet seen by Kiln). */
export const MIRROR_FASTBOOT: USBDeviceFilter = { vendorId: 0x18d1, productId: 0xd00d };

/**
 * The Glass Panes `system.img`'s size: 314,554 blocks of 4 KiB. The partition must be at least this:
 * the real Mirror's is 314,573 blocks (1,288,491,008 bytes, read 2026-11-16).
 */
export const SYSTEM_BYTES = 1_288_413_184;
/** The Glass Panes `userdata.sparse` fills this; a unit with another size still gets a fresh one its size. */
export const USERDATA_BYTES = 5_583_458_304;

/** What the recipe needs of @glassworks/fastboot. */
export interface FastbootLike {
  getvar(name: string): Promise<string>;
  partitionSize(name: string): Promise<number>;
  flashBlob(partition: string, blob: Blob, onProgress?: (x: number) => void): Promise<void>;
  reboot(): Promise<void>;
}

export interface FastbootReport { checks: Check[]; ok: boolean; userdataBytes: number; info: string[] }

const optional = async (fb: FastbootLike, name: string) => { try { return await fb.getvar(name); } catch { return undefined; } };

/**
 * Read-only: is this the Mirror's LK, with the partitions the images are made for? The sizes are
 * the hard check (a wrong one stops the install), and so is the download size. Product and secure are
 * only shown: Kiln hasn't read this LK's answers yet.
 */
export async function checkFastboot(fb: FastbootLike): Promise<FastbootReport> {
  const size = async (p: string) => { try { return await fb.partitionSize(p); } catch { return undefined; } };
  const system = await size('system'), userdata = await size('userdata');
  const product = await optional(fb, 'product'), max = await optional(fb, 'max-download-size'), secure = await optional(fb, 'secure');
  const mb = (n?: number) => (n === undefined ? 'not reported' : `${(n / 1024 / 1024).toFixed(0)} MiB (${n} bytes)`);
  // fastboot.js falls back to 512 MiB without it: on a unit with less, a download would fail mid-write.
  // LK writes it as 0x%x and @glassworks/fastboot reads it as hex, so a bare number is refused: read as
  // hex, a decimal answer would look several times bigger than the real buffer.
  const maxBytes = max !== undefined && /^0x[0-9a-f]+$/i.test(max.trim()) ? Number.parseInt(max.trim(), 16) : undefined;
  const checks: Check[] = [
    { what: 'System partition', found: mb(system), ok: !!system && system >= SYSTEM_BYTES, need: `at least ${SYSTEM_BYTES} bytes, the Glass Panes image's size` },
    { what: 'Userdata partition', found: mb(userdata), ok: !!userdata && userdata >= 1024 ** 3 && userdata % 4096 === 0, need: 'at least 1 GiB, whole 4 KiB blocks' },
    { what: 'Download size', found: mb(maxBytes), ok: !!maxBytes && maxBytes >= 4096, need: 'max-download-size reported in hex (0x…), at least one 4 KiB block' },
  ];
  const info = [`product ${product ?? 'not reported'}`, `max-download-size ${max ?? 'not reported'}`, `secure ${secure ?? 'not reported'}`];
  return { checks, ok: checks.every((c) => c.ok), userdataBytes: userdata ?? 0, info };
}

/** Writes both images, system first, then restarts the Mirror. `onProgress(part, 0..1)`. */
export async function flashImages(fb: FastbootLike, images: { system: Blob; userdata: Blob },
  onProgress: (part: 'system' | 'userdata', x: number) => void = () => {}): Promise<void> {
  await fb.flashBlob('system', images.system, (x) => onProgress('system', x));
  await fb.flashBlob('userdata', images.userdata, (x) => onProgress('userdata', x));
  await fb.reboot();
}

/** The person's file isn't the published `system.img`: wrong, or not fully downloaded. */
export class WrongImage extends Error {}

export type PrepareStep = 'check' | 'patch' | 'system' | 'userdata';

export interface Loaded { disk: Disk; sha256: string }

/**
 * Reads `system.img` into `store`, hashing every byte on the way, and refuses it unless it is the
 * Glass Panes one: the size first (instant), then the SHA-256 (as it is read). `file` is the person's
 * file, or the proxy's resumable stream (resumableBody), whose failures come through as ProxyError.
 * `store` should read unwritten bytes as zeros (a new OPFS file).
 */
export async function loadSystem(file: { size: number; stream(): ReadableStream<Uint8Array> }, store: Store, onProgress: (x: number) => void = () => {},
  want: { name: string; size: number; sha256: string } = GLASS_PANES.system): Promise<Loaded> {
  const again = `Download ${want.name} again from the Glass Panes release, then select the new file.`;
  if (file.size !== want.size) throw new WrongImage(`This file is ${file.size.toLocaleString('en')} bytes; the Glass Panes ${want.name} is ${want.size.toLocaleString('en')}. It's another file, or the download stopped early. ${again}`);
  const sha = new Sha256();
  let loaded: Disk;
  try {
    ({ disk: loaded } = await readSparse(file.stream(), (h) => new Disk(store, h.blockSize, h.blocks, { zeroed: true }),
      { onBytes: (b) => sha.update(b), onProgress: (n) => onProgress(n / file.size) }));
  } catch (e) {
    if (e instanceof ProxyError) throw e;
    throw new WrongImage(`This file isn't a readable ${want.name} (${(e as Error).message}). ${again}`);
  }
  const sha256 = hex(sha.digest());
  if (sha256 !== want.sha256) throw new WrongImage(`This file's SHA-256 is ${sha256}, not the published ${want.sha256}: the file is damaged or isn't the Glass Panes ${want.name}. ${again}`);
  return { disk: loaded, sha256 };
}

/**
 * The two images Kiln flashes, written as sparse images to the sinks: the loaded system image with
 * S1 Kiosk and the rest (patchSystem), and a fresh userdata the partition's size with this browser's
 * adb key (buildUserdata).
 */
/** `fill: false`: zeros go as RAW blocks, not FILL chunks, for a bootloader that refuses FILL. */
export async function buildImages(loaded: Loaded, o: SystemPatch & { adbKey: string; userdataBytes: number; fill?: boolean;
  /** saved in userdata's wpa_supplicant.conf, so Android joins it on the first boot with nothing tapped */
  wifi?: { ssid: string; password: string } },
  sinks: { system: (b: Uint8Array) => Promise<void> | void; userdata: (b: Uint8Array) => Promise<void> | void },
  onProgress: (step: PrepareStep, x: number) => void = () => {}): Promise<void> {
  onProgress('patch', 0);
  const sys = await Ext4.open(loaded.disk);
  await patchSystem(sys, o);
  // the image's own template: the saved network goes after its lines, as the Wi-Fi HAL would copy it
  const template = o.wifi ? new TextDecoder().decode(await sys.readFile('/etc/wifi/wpa_supplicant.conf')) : '';
  onProgress('patch', 1);
  await writeSparse(loaded.disk, sinks.system, { fill: o.fill, onProgress: (b) => onProgress('system', b / loaded.disk.blocks) });
  const ud = new Disk(new MemoryStore(), 4096, Math.floor(o.userdataBytes / 4096), { zeroed: true });
  await buildUserdata(ud, { adbKey: o.adbKey, time: o.time, ...(o.wifi ? { wifi: { ...o.wifi, template } } : {}) });
  await writeSparse(ud, sinks.userdata, { fill: o.fill, onProgress: (b) => onProgress('userdata', b / ud.blocks) });
}

/** Launchers the image ships, hidden on the first boot so S1 Kiosk is the only home screen: no chooser to tap. */
export const OTHER_HOMES = ['com.android.launcher3', 'com.cyanogenmod.trebuchet'];

/**
 * `home`: what Android opened for HOME after the other launchers were hidden: `set` (S1 Kiosk),
 * `chooser` (its list of home apps: the Mirror has no touchscreen to answer it), or `other`. `none`:
 * S1 Kiosk has no home screen of its own (a build whose launcher activity is off), so nothing was
 * hidden: the image's launcher stays the home screen.
 */
export interface FirstBoot { app: { name?: string; code: number }; hidden: string[]; home: HomeCheck; wifi?: boolean; rotation?: string }
export interface HomeCheck { result: 'set' | 'chooser' | 'other' | 'none'; focused?: string; left: string[] }

/** The activity in front, from `dumpsys activity activities` (Android 6's mFocusedActivity). */
export async function focusedActivity(p: PanelShell): Promise<string | undefined> {
  return /mFocusedActivity: ActivityRecord\{\S+ u\d+ (\S+\/\S+)/.exec((await p.run('dumpsys activity activities')).out)?.[1];
}

/**
 * Hides the image's other launchers (pm hide: adb's shell has MANAGE_USERS on Android 6), asks for
 * HOME, and checks what Android opened. Running it again is the fix when a launcher survived.
 */
export async function makeOnlyHome(p: PanelShell, onStep: StepFn = () => {}, sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<{ hidden: string[]; home: HomeCheck }> {
  const r = new Steps(p, onStep);
  const installed = async (pkg: string) => (await r.try(`pm list packages ${pkg}`)).split(/\r?\n/).some((l) => l.trim() === `package:${pkg}`);
  // S1 Kiosk must answer HOME itself before the image's launchers go: hidden with nothing in their
  // place, a Mirror with no touchscreen would have no home screen at all. A bare package as am's last
  // argument sets the intent's package (Android 6 Am.makeIntent).
  const own = await r.try(`am start -a android.intent.action.MAIN -c android.intent.category.HOME ${PKG}`);
  if (/unable to resolve/i.test(own)) {
    const left: string[] = [];
    for (const pkg of OTHER_HOMES) if (await installed(pkg)) left.push(pkg);
    r.detail(`${PKG} has no home screen: nothing hidden, ${left.join(', ') || 'the image\'s launcher'} stays`);
    return { hidden: [], home: { result: 'none', left } };
  }
  const hidden: string[] = [];
  for (const pkg of OTHER_HOMES) {
    if (!(await installed(pkg))) continue;
    const out = await r.try(`pm hide ${pkg}`);
    if (/true/.test(out)) hidden.push(pkg);
    r.detail(`${pkg}: ${out.trim() || 'no answer'}`);
  }
  const left: string[] = [];
  for (const pkg of OTHER_HOMES) if (await installed(pkg)) left.push(pkg); // a hidden app isn't listed
  await r.try('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
  let focused: string | undefined;
  for (let i = 0; i < 10; i++) {
    focused = await focusedActivity(p);
    if (focused?.startsWith(`${PKG}/`)) break;
    await sleep(1000);
  }
  const result: HomeCheck['result'] = focused?.startsWith(`${PKG}/`) ? 'set' : /ResolverActivity|ChooserActivity/.test(focused ?? '') ? 'chooser' : 'other';
  r.detail(`home: ${focused ?? 'unknown'}${left.length ? `; still a home app: ${left.join(', ')}` : ''}`);
  return { hidden, home: { result, ...(focused ? { focused } : {}), left } };
}

/**
 * The Mirror's first start on Glassworks, over adb with Kiln's own key: checks it is the patched
 * image, grants what adb can, hides the other launchers, starts S1 Kiosk with Home Assistant's
 * address, and gives it the Wi-Fi.
 */
export async function firstBoot(p: PanelShell, o: { haUrl?: string; locale?: string; wifi?: { ssid: string; password: string }; onStep?: StepFn;
  sleep?: (ms: number) => Promise<void> }): Promise<FirstBoot> {
  const r = new Steps(p, o.onStep);
  const sleep = o.sleep ?? ((ms: number) => new Promise<void>((res) => setTimeout(res, ms)));
  r.step('Checking the Mirror started Glassworks');
  const fp = (await r.run('getprop ro.build.fingerprint')).trim();
  if (fp !== GLASS_PANES.fingerprint) throw new Error(`the Mirror runs ${fp || 'something unknown'}, not the image Kiln wrote`);
  const app = await kioskVersion(p);
  if (!app) throw new Error(`S1 Kiosk (${PKG}) isn't on the Mirror after the install`);
  const rotation = (await r.try('getprop ro.sf.hwrotation')).trim() || undefined;
  r.detail(`S1 Kiosk ${app.name ?? app.code}, ro.sf.hwrotation=${rotation ?? 'unset'}`);
  await grantPermissions(r);

  r.step('Start S1 Kiosk');
  await startApp(r, o);

  r.step('Make S1 Kiosk the only home screen');
  const { hidden, home } = await makeOnlyHome(p, o.onStep, sleep);

  let wifi: boolean | undefined;
  if (o.wifi) {
    r.step('Wi-Fi: the network you entered'); // never its name: logs end up in failure reports (CLAUDE.md rule 2)
    wifi = await sendWifi(p, o.wifi.ssid, o.wifi.password, sleep);
    r.detail(wifi ? 'joined' : 'S1 Kiosk didn\'t confirm it joined: set the Wi-Fi in its settings');
  }
  return { app, hidden, home, ...(wifi !== undefined ? { wifi } : {}), ...(rotation ? { rotation } : {}) };
}

/** The way back: the plain Glass Panes images, as downloaded, checked against the release's hashes. */
export const GLASS_PANES_USERDATA = {
  name: 'userdata.sparse',
  url: 'https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/userdata.sparse',
  size: 154_944_572,
  sha256: '74b1f57ac20ce7c56065083a2bc3ddd283493be29d851fd04efc156ec96cf908',
} as const;

/** Checks a downloaded file against its published size and SHA-256, streaming. */
export async function checkDownload(file: Blob, want: { name: string; size: number; sha256: string }, onProgress: (x: number) => void = () => {}): Promise<void> {
  const again = `Download ${want.name} again from the Glass Panes release.`;
  if (file.size !== want.size) throw new WrongImage(`This file is ${file.size.toLocaleString('en')} bytes, not ${want.size.toLocaleString('en')}: it isn't ${want.name}, or the download stopped early. ${again}`);
  const sha = new Sha256();
  const r = file.stream().getReader();
  let n = 0;
  for (;;) { const { done, value } = await r.read(); if (done) break; sha.update(value); n += value.length; onProgress(n / file.size); }
  const got = hex(sha.digest());
  if (got !== want.sha256) throw new WrongImage(`This file's SHA-256 is ${got}, not the published ${want.sha256}. ${again}`);
}
