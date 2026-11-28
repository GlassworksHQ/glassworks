// The Mirror in fastboot (its locked LK, reached with the button inside its back): what Kiln reads
// before writing, the images it builds for it, the writes, and the first boot over adb with Kiln's
// own key, which the patched services.jar lets in without asking. The Mirror's LK has no read-back and no `fastboot boot`, so there is no backup of
// lululemon's software: the screens say so before anything is written (#40).
import type { PanelShell } from '@glassworks/adb';
import { Steps, grantPermissions, handOverApiKey, kioskVersion, sendWifi, startApp, type StepFn } from '@glassworks/app-adb';
import { GLASSWORKS, type MirrorApp } from './app';
import { Disk, Ext4, MemoryStore, Sha256, hex, readSparse, writeSparse, type Store } from '@glassworks/ext4';
import { ProxyError } from './download';
import { GLASS_PANES, buildUserdata, macFromSerial, patchSystem, type SystemPatch } from './image';
import { adbKeyLines } from './services';
import type { Check } from './recipe';
import { describeMirrorUpdates, readMirrorUpdates, type MirrorUpdates } from './updates';

/** The Mirror's LK in fastboot, as the Glass Panes install guide reads it on a Mac (not yet seen by Kiln). */
export const MIRROR_FASTBOOT: USBDeviceFilter = { vendorId: 0x18d1, productId: 0xd00d };

/**
 * The Glass Panes `system.img`'s size: 314,554 blocks of 4 KiB (images 2 and 3). The partition must be at least this:
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
  /**
   * Lets go of the USB device (@glassworks/fastboot's close): on a connection Kiln stops using without a reboot, so
   * Connect or Try again opens it fresh. Optional: a fake or another transport may have nothing to let go of.
   */
  close?(): Promise<void>;
}

export interface FastbootReport { checks: Check[]; ok: boolean; userdataBytes: number; info: string[];
  /** The Mirror's id in Home Assistant, from its serial: the same on every reflash (#523). None when LK gives no serial. */
  deviceMac?: string }

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
  const deviceMac = await macFromSerial(await optional(fb, 'serialno'));
  const info = [`product ${product ?? 'not reported'}`, `max-download-size ${max ?? 'not reported'}`, `secure ${secure ?? 'not reported'}`,
    deviceMac ? 'Home Assistant id: from the serial, the same on every reflash' : 'Home Assistant id: no serial reported, so the app picks a new one'];
  return { checks, ok: checks.every((c) => c.ok), userdataBytes: userdata ?? 0, info, ...(deviceMac ? { deviceMac } : {}) };
}

/** Writes both images, system first, then restarts the Mirror. `onProgress(part, 0..1)`. */
export async function flashImages(fb: FastbootLike, images: { system: Blob; userdata: Blob },
  onProgress: (part: 'system' | 'userdata', x: number) => void = () => {}): Promise<void> {
  await fb.flashBlob('system', images.system, (x) => onProgress('system', x));
  await fb.flashBlob('userdata', images.userdata, (x) => onProgress('userdata', x));
  await fb.reboot();
}

/** The person's file isn't the published `system.img`: wrong, or not fully downloaded. */
import { WrongImage } from '@glassworks/kiln-core';

/** Bytes that aren't the release's (kiln-core's, so the engine knows it). */
export { WrongImage };

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
 * the app (Glassworks), services.jar trusting `adbKey`'s first line, and the rest (patchSystem), and a
 * fresh userdata the partition's size with `adbKey` in adb_keys (buildUserdata).
 * `log`: patchSystem's own lines (the app's libraries, the unused apps left out, build.prop, services.jar).
 * `fill: false`: zeros go as RAW blocks, not FILL chunks, for a bootloader that refuses FILL.
 */
export async function buildImages(loaded: Loaded, o: SystemPatch & { adbKey: string; userdataBytes: number; fill?: boolean;
  /** saved in userdata's wpa_supplicant.conf, so Android joins it on the first boot with nothing tapped */
  wifi?: { ssid: string; password: string } },
  sinks: { system: (b: Uint8Array) => Promise<void> | void; userdata: (b: Uint8Array) => Promise<void> | void },
  onProgress: (step: PrepareStep, x: number) => void = () => {},
  log: (line: string) => void = () => {}): Promise<void> {
  adbKeyLines(o.adbKey); // every line, before the system image is touched
  onProgress('patch', 0);
  const sys = await Ext4.open(loaded.disk);
  await patchSystem(sys, o, log);
  // the image's own template: the saved network goes after its lines, as the Wi-Fi HAL would copy it
  const template = o.wifi ? new TextDecoder().decode(await sys.readFile('/etc/wifi/wpa_supplicant.conf')) : '';
  onProgress('patch', 1);
  await writeSparse(loaded.disk, sinks.system, { fill: o.fill, onProgress: (b) => onProgress('system', b / loaded.disk.blocks) });
  const ud = new Disk(new MemoryStore(), 4096, Math.floor(o.userdataBytes / 4096), { zeroed: true });
  await buildUserdata(ud, { adbKey: o.adbKey, time: o.time, ...(o.wifi ? { wifi: { ...o.wifi, template } } : {}) });
  await writeSparse(ud, sinks.userdata, { fill: o.fill, onProgress: (b) => onProgress('userdata', b / ud.blocks) });
}

/** Launchers the image ships, hidden on the first boot so our app is the only home screen: no chooser to tap. */
export const OTHER_HOMES = ['com.android.launcher3', 'com.cyanogenmod.trebuchet'];

/**
 * `home`: what Android opened for HOME after the other launchers were hidden: `set` (our app),
 * `chooser` (its list of home apps: the Mirror has no touchscreen to answer it), or `other`. `none`:
 * our app has no home screen of its own (a build whose launcher activity is off), so nothing was
 * hidden: the image's launcher stays the home screen.
 */
export interface FirstBoot { app: { name?: string; code: number }; hidden: string[]; home: HomeCheck; wifi?: boolean;
  /** whether its updates install by themselves: the image's copy in /system/priv-app, read back */
  updates: MirrorUpdates;
  /** with `apiKey`: whether the fresh app took the key it had before the flash (#574) */
  apiKey?: boolean;
  /** the display rotation Android reports after the rotation step: always MIRROR_ROTATION */
  rotation: number }

/**
 * The Mirror's 1920x1080 panel stands portrait, upright at user_rotation 3 (Surface.ROTATION_270:
 * graphics turned 90 degrees counter-clockwise, AOSP 6.0.1 Display.getRotation). Seen upright on the
 * glass with Kiln's first boot, 2026-11-17. The setting lives in userdata, which Kiln writes fresh, so
 * the first boot sets it again. (`ro.sf.hwrotation` does nothing on this image: nothing reads it.)
 */
export const MIRROR_ROTATION = 3;

/**
 * Android's display rotation: WindowManagerService's mRotation from `dumpsys window windows`, which
 * prints it every time (dumpWindowsLocked with dumpAll) and holds no saved ANR state. Plain
 * `dumpsys window` prints it only with dumpAll on Android 6, and its ANR snapshot has an old display.
 */
export async function displayRotation(p: PanelShell): Promise<number | undefined> {
  const m = /(?:^|\s)mRotation=(\d)\b/m.exec((await p.run('dumpsys window windows')).out);
  return m ? Number(m[1]) : undefined;
}

/**
 * Locks the screen upright in portrait (no auto-rotate, user_rotation 3) and waits until Android
 * reports rotation 3 exactly: 1 is portrait too, upside down. Throws after `tries` reads a second apart.
 */
export async function setPortrait(p: PanelShell, onStep: StepFn = () => {}, sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  tries = 10): Promise<number> {
  const r = new Steps(p, onStep);
  await r.run('settings put system accelerometer_rotation 0');
  await r.run(`settings put system user_rotation ${MIRROR_ROTATION}`);
  let seen: number | undefined;
  for (let i = 0; i < tries; i++) {
    seen = await displayRotation(p);
    if (seen === MIRROR_ROTATION) { r.detail(`rotation ${seen}`); return seen; }
    await sleep(1000);
  }
  throw new Error(`the Mirror's screen didn't turn to portrait: after user_rotation=${MIRROR_ROTATION}, Android still reports rotation ${seen ?? 'unknown'} (${tries} s). Look at the glass, then run the first boot again`);
}
export interface HomeCheck { result: 'set' | 'chooser' | 'other' | 'none'; focused?: string; left: string[] }

/** The activity in front, from `dumpsys activity activities` (Android 6's mFocusedActivity). */
export async function focusedActivity(p: PanelShell): Promise<string | undefined> {
  return /mFocusedActivity: ActivityRecord\{\S+ u\d+ (\S+\/\S+)/.exec((await p.run('dumpsys activity activities')).out)?.[1];
}

/**
 * Hides the image's other launchers (pm hide: adb's shell has MANAGE_USERS on Android 6), asks for
 * HOME, and checks what Android opened. Running it again is the fix when a launcher survived. `others`:
 * the home apps to hide, the image's launchers by default (the app-only install adds the one it replaces).
 * `beforeHide`: called with each package before its `pm hide`, so a record of it survives an interrupted run.
 */
export async function makeOnlyHome(p: PanelShell, onStep: StepFn = () => {}, sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  app: MirrorApp = GLASSWORKS, others: readonly string[] = OTHER_HOMES, beforeHide: (pkg: string) => Promise<void> = async () => {}): Promise<{ hidden: string[]; home: HomeCheck }> {
  const PKG = app.package;
  const r = new Steps(p, onStep);
  const installed = async (pkg: string) => (await r.try(`pm list packages ${pkg}`)).split(/\r?\n/).some((l) => l.trim() === `package:${pkg}`);
  // our app must answer HOME itself before the image's launchers go: hidden with nothing in their
  // place, a Mirror with no touchscreen would have no home screen at all. A bare package as am's last
  // argument sets the intent's package (Android 6 Am.makeIntent).
  const own = await r.try(`am start -a android.intent.action.MAIN -c android.intent.category.HOME ${PKG}`);
  if (/unable to resolve/i.test(own)) {
    const left: string[] = [];
    for (const pkg of others) if (await installed(pkg)) left.push(pkg);
    r.detail(`${PKG} has no home screen: nothing hidden, ${left.join(', ') || 'the image\'s launcher'} stays`);
    return { hidden: [], home: { result: 'none', left } };
  }
  const hidden: string[] = [];
  for (const pkg of others) {
    if (!(await installed(pkg))) continue;
    await beforeHide(pkg);
    const out = await r.try(`pm hide ${pkg}`);
    if (/true/.test(out)) hidden.push(pkg);
    r.detail(`${pkg}: ${out.trim() || 'no answer'}`);
  }
  const left: string[] = [];
  for (const pkg of others) if (await installed(pkg)) left.push(pkg); // a hidden app isn't listed
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
 * The Mirror's first start on Glassworks, over adb with Kiln's own key (the one services.jar trusts): checks it is the patched
 * image, grants what adb can, turns the screen upright in portrait, starts the app (Glassworks) with Home
 * Assistant's address, hides the other launchers, and gives it the Wi-Fi.
 */
export async function firstBoot(p: PanelShell, o: { haUrl?: string; locale?: string; wifi?: { ssid: string; password: string }; onStep?: StepFn;
  sleep?: (ms: number) => Promise<void>; app?: MirrorApp;
  /** the ESPHome API key read before the flash (readApiKey), for an image built with `apiKeyHandover` (#574). A secret: never logged */
  apiKey?: string }): Promise<FirstBoot> {
  const ours = o.app ?? GLASSWORKS;
  const r = new Steps(p, o.onStep);
  const sleep = o.sleep ?? ((ms: number) => new Promise<void>((res) => setTimeout(res, ms)));
  r.step(`Checking the Mirror started ${ours.name}`);
  const fp = (await r.run('getprop ro.build.fingerprint')).trim();
  if (fp !== GLASS_PANES.fingerprint) throw new Error(`the Mirror runs ${fp || 'something unknown'}, not the image Kiln wrote`);
  const app = await kioskVersion(p, undefined, ours);
  if (!app) throw new Error(`${ours.name} (${ours.package}) isn't on the Mirror after the install`);
  r.detail(`${ours.name} ${app.name ?? app.code}`);
  await grantPermissions(r, ours);
  // The image carries the APK, so nothing installed it with `pm install -g` as the app alone is: its runtime location
  // grant is missing, and Android 6 gives an app without it no Wi-Fi scan results (the Mirror, 2026-11-20). These two
  // are the only ones the app asks for on API 23 that grantPermissions doesn't give.
  for (const perm of ['ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION']) await r.try(`pm grant ${ours.package} android.permission.${perm}`);
  const updates = await readMirrorUpdates(p, ours);
  r.detail(describeMirrorUpdates(updates));

  // before the app starts, so it opens in portrait (it follows the system's rotation, #434)
  r.step('Turn the screen upright (portrait)');
  const rotation = await setPortrait(p, o.onStep, sleep);

  // through its main activity, whose extras it trusts from adb (#364): the HOME alias drops them
  r.step(`Start ${ours.name}`);
  await startApp(r, o, ours);

  // right after the start: the app keeps its API server shut until the key comes, and Home Assistant waits for it
  let apiKey: boolean | undefined;
  if (o.apiKey) {
    r.step('Home Assistant API key: the one from before the flash');
    apiKey = await handOverApiKey(p, o.apiKey, sleep);
    r.detail(apiKey ? 'handed over: Home Assistant reconnects with no re-authentication'
      : `${ours.name} didn't take it, so Home Assistant may ask to re-authenticate once. If ${ours.name} has no key, it starts without one after 15 minutes`);
  }

  r.step(`Make ${ours.name} the only home screen`);
  const { hidden, home } = await makeOnlyHome(p, o.onStep, sleep, ours);

  let wifi: boolean | undefined;
  if (o.wifi) {
    r.step('Wi-Fi: the network you entered'); // never its name: logs end up in failure reports (CLAUDE.md rule 2)
    wifi = await sendWifi(p, o.wifi.ssid, o.wifi.password, sleep);
    r.detail(wifi ? 'joined' : `${ours.name} didn't confirm it joined (the image's saved network still applies)`);
  }
  return { app, hidden, home, rotation, updates, ...(wifi !== undefined ? { wifi } : {}), ...(apiKey !== undefined ? { apiKey } : {}) };
}

/** Glass Panes' userdata, as its release publishes it: Kiln's Glass Panes install (choices.ts) and the CLI's way back. */
export const GLASS_PANES_USERDATA = {
  name: 'userdata.sparse',
  url: 'https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-3/userdata.sparse',
  size: 154_952_764,
  sha256: '81eb5648ec0b5ed13d520e90c597c9ff50f3ea57524e9d9554c2a71dc94c521d',
} as const;

/** Checks a downloaded file against its published size and SHA-256, streaming (the CLI, tools/mirror). */
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
