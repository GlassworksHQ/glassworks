// The Mirror's images, made in the person's browser (#40, option A). `system` is the Glass Panes
// community image the person downloaded themselves. Kiln checks its SHA-256 and adds the legacy build of
// Glassworks as a privileged system app and the Glassworks boot animation (the screen's rotation is
// set on the first boot: flash.ts setPortrait), and leaves out the image's apps a Mirror never uses
// (unused.ts). The
// Mirror has no touchscreen to answer "Allow USB debugging?", and the image lets one key in without
// asking (services.ts): Kiln makes that this browser's key instead of the published Glass Panes one.
// `userdata` is new and empty. Nothing here is downloaded from or uploaded to anyone: the person's
// file goes in, and the patched copy stays in their browser until fastboot writes it.
// The findings behind each step: frames/lululemon-mirror/research/image-patch.md.
import { ManifestError, apkHome, apkInfo, apkPermissions, zipEntry, zipNames, type ApkInfo } from '@glassworks/app-adb';
import { APP_DIRS, GLASSWORKS, PRIVILEGED, type MirrorApp } from './app';
import { mkfs, type Attrs, type Disk, type Ext4 } from '@glassworks/ext4';
import { SERVICES_JAR, adbKeyLines, trustAdbKey } from './services';
import { UNUSED_APPS } from './unused';

/** The Glass Panes release the person downloads `system.img` from, and what Kiln expects of it. */
export const GLASS_PANES = {
  /** The project and its developer: credited where the image is downloaded and on Done. */
  project: { name: 'Glass Panes', href: 'https://github.com/git-nimrod/glass-panes', developer: 'git-nimrod', developerHref: 'https://github.com/git-nimrod' },
  release: 'https://github.com/git-nimrod/glass-panes/releases/tag/mirror-android-2',
  system: {
    name: 'system.img',
    url: 'https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-2/system.img',
    size: 850_905_808,
    sha256: 'bdb6b8445296666162256affa968d590075f56a8ad2229930f8502b9703e35c1',
  },
  /** Its build.prop's fingerprint: read from the image on 2026-11-16 (image 1's was …/11041605:…). */
  fingerprint: 'Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11120023:userdebug/test-keys',
} as const;

/**
 * The Mirror frame's version (#139): Kiln writes it into build.prop with the frame's id, and the app shows it and
 * compares it with the newest `frames/lululemon-mirror/v<version>` release (docs/releasing.md, "Frame releases").
 * Bump it with every change to what lands on a Mirror, and release that version. 1.0.0 comes out with Glassworks
 * 1.0.0; until then Kiln writes a pre-release of it, which counts as older than 1.0.0.
 */
export const MIRROR_FRAME = { id: 'lululemon-mirror', version: '1.0.0-dev' } as const;

export const LABELS = {
  system: 'u:object_r:system_file:s0',
  data: 'u:object_r:system_data_file:s0',
  adbKeys: 'u:object_r:adb_keys_file:s0',
  /** /data/misc/wifi(/.*)? (AOSP 6.0.1 file_contexts; the image's own is in its signed boot ramdisk) */
  wifi: 'u:object_r:wifi_data_file:s0',
} as const;

/** Android's ids (system/core/include/private/android_filesystem_config.h). */
const AID = { root: 0, system: 1000, wifi: 1010, shell: 2000, misc: 9998 } as const;

/** The Mirror's Android: 6.0.1. */
const MIRROR_API = 23;

/** APK ABI folder → the folder Android 6's package manager looks in for a bundled app's libraries. */
const LIB_DIRS: [string, string][] = [['arm64-v8a', 'arm64'], ['armeabi-v7a', 'arm']];

/**
 * The APK's own native libraries that patchSystem unpacks into the image: the zip names under
 * lib/<abi>/*.so for the first of the Mirror's two ABIs that has any (arm64-v8a, then armeabi-v7a),
 * and the folder Android 6 reads them from. Android 6 doesn't unpack a bundled app's libraries from
 * its APK: they sit next to it, uncompressed. Undefined when the APK has none.
 */
export function apkLibs(apk: Uint8Array): { abi: string; dir: string; names: string[] } | undefined {
  const all = zipNames(apk);
  for (const [abi, dir] of LIB_DIRS) {
    // an empty lib/<abi>/ folder doesn't count
    const names = all.filter((n) => n.startsWith(`lib/${abi}/`) && n.endsWith('.so') && !n.slice(`lib/${abi}/`.length).includes('/'));
    if (names.length) return { abi, dir, names };
  }
  return undefined;
}

export interface SystemPatch {
  /**
   * The adb key the Mirror lets in without asking, as an adb_keys line (e.g. Kiln's AdbKey.publicKey).
   * With several lines, the first. services.jar trusts it in place of the Glass Panes project key.
   */
  adbKey: string;
  /** The app's APK: Glassworks' legacy build, or the probe with `app: MIRROR_PROBE`. */
  apk: Uint8Array;
  /** Which app the APK must be (package, folder); default Glassworks (frame.json `app`). */
  app?: MirrorApp;
  /** /system/media/bootanimation.zip; none: the image's own (it has none, so Android's text one). */
  bootAnimation?: Uint8Array;
  /**
   * GlassworksSettings.apk (frames/lululemon-mirror/overlay): SettingsProvider's defaults for a first
   * boot with nothing to tap. It brings ro.lockscreen.disable.default=true with it; without it, the
   * first boot is the image's own.
   */
  settingsOverlay?: Uint8Array;
  /** The device's MAC, written as DEVICE_MAC_PROP (from checkFastboot's `deviceMac`); none: the app picks its own. */
  deviceMac?: string;
  /** Seconds since 1970 for new files; default now. */
  time?: number;
}

/**
 * The Mirror's SettingsProvider overlay (frames/lululemon-mirror/overlay/build.sh): Wi-Fi on, no screen
 * timeout, stay on while plugged in, no lock screen, no auto-rotate. Android 6 trusts a static overlay
 * by where it is: /vendor/overlay, which init links to /system/vendor (AOSP 6.0.1 init.rc).
 */
export const SETTINGS_OVERLAY = {
  path: '/vendor/overlay/GlassworksSettings.apk',
  sha256: '4a8796d33b41da6a7ad44c065d57a5bfcad860794006d52b9977ff19f5f1c342',
} as const;

/**
 * The build.prop key the app takes its Home Assistant identity from when its data is new (PanelDevice.IMAGE_MAC_PROP).
 * A reflash wipes the app's data; with this the Mirror stays the same device in Home Assistant (#523).
 */
export const DEVICE_MAC_PROP = 'ro.glassworks.device.mac';

const MAC = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/;

/**
 * The device's MAC for its chip serial, as the app derives it (PanelDevice.macFromSerial): SHA-256 of the salted
 * serial, its first 6 bytes, made unicast and locally administered. Undefined for a serial that isn't one.
 */
export async function macFromSerial(serial: string | undefined): Promise<string | undefined> {
  const s = serial?.trim() ?? '';
  if (s.length < 8 || s === 'unknown') return undefined;
  const b = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`s1kiosk-device-mac:${s}`))).slice(0, 6);
  b[0] = (b[0]! & 0xfc) | 0x02;
  return [...b].map((x) => x.toString(16).padStart(2, '0').toUpperCase()).join(':');
}

const dirAttrs = (time: number): Attrs => ({ mode: 0o755, uid: AID.root, gid: AID.root, label: LABELS.system, time });
const fileAttrs = (time: number): Attrs => ({ mode: 0o644, uid: AID.root, gid: AID.root, label: LABELS.system, time });

/** Sets `key=value` in a build.prop: the last assignment wins at boot, so any earlier one is replaced. */
export function setProp(text: string, key: string, value: string): string {
  const lines = text.split('\n').filter((l) => !l.startsWith(`${key}=`));
  while (lines.length && lines.at(-1) === '') lines.pop();
  return `${lines.join('\n')}\n${key}=${value}\n`;
}

/**
 * Refuses an APK the Mirror can't run as its only screen, before anything is written: not the app
 * the frame names, missing a privileged permission, above Android 6's API, no native code for its ABIs, or no home screen Android can open. The Mirror
 * has no touchscreen: with no home screen, it starts to nothing (its first real flash, 2026-11-16).
 */
export async function checkMirrorApk(apk: Uint8Array, app: MirrorApp = GLASSWORKS): Promise<ApkInfo> {
  try {
    return await checkApk(apk, app);
  } catch (e) {
    if (e instanceof ManifestError) throw new Error(`${e.message}. Nothing was changed.`);
    throw e;
  }
}

async function checkApk(apk: Uint8Array, app: MirrorApp): Promise<ApkInfo> {
  const pick = app === GLASSWORKS ? 'pick the legacy build of Glassworks for generic Android' : `pick ${app.name}'s build`;
  const info = await apkInfo(apk);
  if (info.package !== app.package) throw new Error(`this APK is ${info.package}, not ${app.name} (${app.package}): ${pick}. Nothing was changed.`);
  // the Mirror runs Android 6 (API 23) on arm64 with 32-bit arm too (frame.json abis): the legacy build fits
  const min = info.minSdk ?? 1;
  if (typeof min !== 'number' || min > MIRROR_API) throw new Error(`this APK needs Android API ${min}, and the Mirror runs Android 6 (API ${MIRROR_API}): ${pick}. Nothing was changed.`);
  if (info.abis.length && !info.abis.some((a) => LIB_DIRS.some(([x]) => x === a))) throw new Error(`this APK has native code only for ${info.abis.join(', ')}, not for the Mirror's arm64-v8a or armeabi-v7a: ${pick}. Nothing was changed.`);
  // only the copy in /system can ask for a privileged permission: an update can never add one later
  const asks = new Set(await apkPermissions(apk, MIRROR_API));
  const missing = PRIVILEGED.filter((p) => !asks.has(p));
  if (missing.length) {
    throw new Error(`this APK doesn't ask for ${missing.map((m) => m.replace('android.permission.', '')).join(', ')}. Android 6 grants a privileged permission only to what the copy in /system asks for, and an update can never add one: ${pick}. Nothing was changed.`);
  }
  const home = await apkHome(apk);
  if (!home.on.length) {
    const why = home.off.length ? `its home screen (${home.off.join(', ')}) is turned off in its manifest`
      : home.unclear.length ? `whether its home screen (${home.unclear.join(', ')}) is on depends on a resource Kiln doesn't read`
        : 'it has no home screen (no activity with MAIN, HOME and DEFAULT in one intent filter)';
    throw new Error(`this APK can't be the Mirror's home screen: ${why}. The Mirror has no touchscreen, so it would start to nothing: ${pick}. Nothing was changed.`);
  }
  return info;
}

/**
 * Changes the Glass Panes system image on `fs` (already checked by SHA-256). Running it twice gives
 * the same result. Throws, with nothing written, unless it is the expected image and APK.
 */
export async function patchSystem(fs: Ext4, o: SystemPatch, log: (line: string) => void = () => {}): Promise<void> {
  const time = o.time ?? Math.floor(Date.now() / 1000);
  const prop = new TextDecoder().decode(await fs.readFile('/build.prop'));
  if (!prop.includes(`ro.build.fingerprint=${GLASS_PANES.fingerprint}\n`)) throw new Error('this isn\'t the Glass Panes Mirror image Kiln knows (its build fingerprint differs). Nothing was changed.');
  if (o.settingsOverlay) {
    const got = [...new Uint8Array(await crypto.subtle.digest('SHA-256', o.settingsOverlay as Uint8Array<ArrayBuffer>))].map((b) => b.toString(16).padStart(2, '0')).join('');
    if (got !== SETTINGS_OVERLAY.sha256) throw new Error(`the settings overlay isn't the one Kiln builds (SHA-256 ${got}). Nothing was changed.`);
  }
  if (o.deviceMac !== undefined && !MAC.test(o.deviceMac)) throw new Error('the device id isn\'t a MAC (AA:BB:CC:DD:EE:FF). Nothing was changed.');
  const app = o.app ?? GLASSWORKS;
  await checkMirrorApk(o.apk, app);
  // the ABI with real libraries (lib/<abi>/<name>.so), as apkInfo reads them
  const native = apkLibs(o.apk);
  // read every library before the first write: one Kiln can't unpack must leave the image as it was
  const payloads: [string, Uint8Array][] = [];
  const seen = new Set<string>();
  for (const n of native?.names ?? []) {
    const dest = n.split('/').pop()!;
    // two entries for the same lib/<abi>/<name>.so would write one then fail on the second: refuse before any write
    if (seen.has(dest)) throw new Error(`this APK lists ${native!.abi}/${dest} more than once. Nothing was changed.`);
    seen.add(dest);
    const data = await zipEntry(o.apk, n).catch((e: Error) => { throw new Error(`${e.message}. Nothing was changed.`); });
    if (!data) throw new Error(`this APK lists ${n} but Kiln can't read it. Nothing was changed.`);
    payloads.push([dest, data]);
  }
  if (!(await fs.exists(SERVICES_JAR))) throw new Error(`this image has no /system${SERVICES_JAR}. Nothing was changed.`);
  const services = await trustAdbKey(await fs.readFile(SERVICES_JAR), adbKeyLines(o.adbKey)[0]!);

  // one app of ours per image: any earlier one (Glassworks or the probe) goes first
  for (const dir of APP_DIRS) if (await fs.exists(dir)) { await fs.remove(dir, { recursive: true, time }); log(`replaced ${dir}`); }
  await fs.mkdir(app.dir, dirAttrs(time));
  await fs.writeFile(`${app.dir}/${app.file}`, o.apk, fileAttrs(time));
  log(`${app.dir}/${app.file} (${o.apk.length} bytes)`);
  // Android 6 doesn't unpack a bundled app's libraries from its APK: they sit next to it, by ABI
  if (native) {
    await fs.mkdirs(`${app.dir}/lib/${native.dir}`, dirAttrs(time));
    for (const [name, data] of payloads) await fs.writeFile(`${app.dir}/lib/${native.dir}/${name}`, data, fileAttrs(time));
    log(`${native.names.length} native librar${native.names.length === 1 ? 'y' : 'ies'} for ${native.abi} in ${app.dir}/lib/${native.dir}`);
  }

  // apps a Mirror never uses: they restart after every lowmemorykiller kill. A second run finds them gone.
  const left: string[] = [];
  for (const u of UNUSED_APPS) if (await fs.exists(u.dir)) { await fs.remove(u.dir, { recursive: true, time }); left.push(u.package); }
  if (left.length) log(`left out ${left.length} unused apps: ${left.join(', ')}`);

  if (o.bootAnimation) {
    if (await fs.exists('/media/bootanimation.zip')) await fs.remove('/media/bootanimation.zip', { time });
    await fs.writeFile('/media/bootanimation.zip', o.bootAnimation, fileAttrs(time));
    log('/media/bootanimation.zip');
  }

  if (o.settingsOverlay) {
    await fs.mkdirs('/vendor/overlay', dirAttrs(time));
    if (await fs.exists(SETTINGS_OVERLAY.path)) await fs.remove(SETTINGS_OVERLAY.path, { time });
    await fs.writeFile(SETTINGS_OVERLAY.path, o.settingsOverlay, fileAttrs(time));
    log(SETTINGS_OVERLAY.path);
  }

  let next = setProp(setProp(prop, 'ro.glassworks.frame.id', MIRROR_FRAME.id), 'ro.glassworks.frame.version', MIRROR_FRAME.version);
  const set = [`ro.glassworks.frame.version=${MIRROR_FRAME.version}`];
  // the value is device-unique: the log names the key only. Without one, an earlier build's goes: after a failed
  // flash Kiln patches the same image again, maybe for another Mirror.
  if (o.deviceMac) { next = setProp(next, DEVICE_MAC_PROP, o.deviceMac); set.push(DEVICE_MAC_PROP); }
  else next = next.split('\n').filter((l) => !l.startsWith(`${DEVICE_MAC_PROP}=`)).join('\n');

  // No swipe lock screen, with the overlay: the two make the first boot with nothing to tap, and
  // without the overlay the first boot is the image's own. SettingsProvider's DatabaseHelper and
  // LockSettingsService both read it when they make a fresh userdata's settings (AOSP 6.0.1; the
  // image's services and SettingsProvider dex have the string, read 2026-11-16).
  if (o.settingsOverlay) { next = setProp(next, 'ro.lockscreen.disable.default', 'true'); set.push('ro.lockscreen.disable.default=true'); }
  if (next !== prop) { await fs.replaceFile('/build.prop', new TextEncoder().encode(next), time); log(`build.prop: ${set.join(', ')}`); }
  if (services) { await fs.replaceFile(SERVICES_JAR, services, time); log(`${SERVICES_JAR}: lets in this adb key without asking, not the Glass Panes project key`); }
  await fs.flush();
}

/**
 * A new, empty userdata with /data/misc/adb/adb_keys, owned and labelled as Android 6's init and
 * file_contexts make them. Init creates everything else on first boot. `adbKey`: the line(s) adb_keys
 * takes, e.g. Kiln's AdbKey.publicKey; every line goes in. adbd on the Glass Panes image ignores this file (verified
 * 2026-11-16): the key gets in through services.jar (patchSystem). The file is kept because Android
 * writes keys there itself and a stock adbd would read it.
 */
export async function buildUserdata(disk: Disk, o: { adbKey: string; time?: number; uuid?: Uint8Array;
  /** the person's network, saved for Android's Wi-Fi; `template` is the image's /etc/wifi/wpa_supplicant.conf */
  wifi?: { ssid: string; password: string; template: string } }): Promise<Ext4> {
  const keys = adbKeyLines(o.adbKey); // the same check as the harness's preflight
  const time = o.time ?? Math.floor(Date.now() / 1000);
  const fs = await mkfs(disk, {
    label: 'userdata', time, uuid: o.uuid,
    root: { mode: 0o771, uid: AID.system, gid: AID.system, label: LABELS.data },
    lostFound: { mode: 0o770, uid: AID.root, gid: AID.root, label: LABELS.data },
  });
  // init.rc (Android 6): mkdir /data/misc 01771 system misc; mkdir /data/misc/adb 02750 system shell
  await fs.mkdir('/misc', { mode: 0o1771, uid: AID.system, gid: AID.misc, label: LABELS.data, time });
  await fs.mkdir('/misc/adb', { mode: 0o2750, uid: AID.system, gid: AID.shell, label: LABELS.adbKeys, time });
  await fs.writeFile('/misc/adb/adb_keys', new TextEncoder().encode(`${keys.join('\n')}\n`), { mode: 0o640, uid: AID.system, gid: AID.shell, label: LABELS.adbKeys, time });
  if (o.wifi) {
    // init.rc: mkdir /data/misc/wifi 0770 wifi wifi; the Wi-Fi HAL uses an existing wpa_supplicant.conf
    // as it is (copying the template only when there is none), system:wifi 0660 (AOSP 6.0.1 wifi.c)
    await fs.mkdir('/misc/wifi', { mode: 0o770, uid: AID.wifi, gid: AID.wifi, label: LABELS.wifi, time });
    await fs.writeFile('/misc/wifi/wpa_supplicant.conf', new TextEncoder().encode(await wpaSupplicantConf(o.wifi.template, o.wifi)),
      { mode: 0o660, uid: AID.system, gid: AID.wifi, label: LABELS.wifi, time });
  }
  await fs.flush();
  return fs;
}

/**
 * wpa_supplicant.conf for one saved network: the image's template, then the network. A WPA
 * passphrase (8 to 63 characters) is stored as its PSK, PBKDF2-HMAC-SHA1 over the SSID, 4096 rounds,
 * 32 bytes (IEEE 802.11i), never as the plain password; 64 hex digits are a PSK already; no password
 * is an open network. An SSID with a quote, a backslash or a character outside printable ASCII goes
 * as hex, as wpa_supplicant reads it.
 */
export async function wpaSupplicantConf(template: string, wifi: { ssid: string; password: string }): Promise<string> {
  const ssidBytes = new TextEncoder().encode(wifi.ssid);
  if (!ssidBytes.length || ssidBytes.length > 32) throw new Error('the Wi-Fi name must be 1 to 32 bytes');
  const plain = /^[\x20-\x7e]+$/.test(wifi.ssid) && !/["\\]/.test(wifi.ssid);
  const ssid = plain ? `"${wifi.ssid}"` : [...ssidBytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  let key: string;
  if (wifi.password === '') key = '\tkey_mgmt=NONE';
  else if (/^[0-9a-fA-F]{64}$/.test(wifi.password)) key = `\tpsk=${wifi.password.toLowerCase()}\n\tkey_mgmt=WPA-PSK`;
  else if (/^[\x20-\x7e]{8,63}$/.test(wifi.password)) key = `\tpsk=${await wpaPsk(wifi.password, ssidBytes)}\n\tkey_mgmt=WPA-PSK`;
  else throw new Error('the Wi-Fi password must be 8 to 63 printable characters (or 64 hex digits), or empty for an open network');
  const head = template.endsWith('\n') ? template : `${template}\n`;
  return `${head}\nnetwork={\n\tssid=${ssid}\n${key}\n\tpriority=1\n}\n`;
}

/** WPA's PSK for a passphrase: PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 256 bits), as hex. */
export async function wpaPsk(passphrase: string, ssid: Uint8Array): Promise<string> {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-1', salt: ssid as Uint8Array<ArrayBuffer>, iterations: 4096 }, k, 256);
  return [...new Uint8Array(bits)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
