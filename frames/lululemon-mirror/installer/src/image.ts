// The Mirror's images, made in the person's browser (#40, option A). `system` is the Glass Panes
// community image the person downloaded themselves. Kiln checks its SHA-256 and adds S1 Kiosk as a
// privileged system app, the Glassworks boot animation, and the screen's rotation. `userdata` is new
// and empty but for Kiln's own adb key for this browser, because the Mirror has no touchscreen to
// answer "Allow USB debugging?". Nothing here is downloaded from or uploaded to anyone: the person's
// file goes in, and the patched copy stays in their browser until fastboot writes it.
// The findings behind each step: frames/lululemon-mirror/research/image-patch.md.
import { PKG, apkInfo, zipEntry, zipNames } from '@glassworks/app-adb';
import { mkfs, type Attrs, type Disk, type Ext4 } from '@glassworks/ext4';

/** The Glass Panes release the person downloads `system.img` from, and what Kiln expects of it. */
export const GLASS_PANES = {
  /** The project and its developer: credited where the image is downloaded and on Done. */
  project: { name: 'Glass Panes', href: 'https://github.com/git-nimrod/glass-panes', developer: 'git-nimrod', developerHref: 'https://github.com/git-nimrod' },
  release: 'https://github.com/git-nimrod/glass-panes/releases/tag/mirror-android-1',
  system: {
    name: 'system.img',
    url: 'https://github.com/git-nimrod/glass-panes/releases/download/mirror-android-1/system.img',
    size: 850_905_808,
    sha256: 'd13dd330bce43b1c46edb65a5eb95a77968f8fd9c413f166c4393e0072f83ed5',
  },
  /** Its build.prop's fingerprint: read from the image on 2026-11-15. */
  fingerprint: 'Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11041605:userdebug/test-keys',
} as const;

export const LABELS = {
  system: 'u:object_r:system_file:s0',
  data: 'u:object_r:system_data_file:s0',
  adbKeys: 'u:object_r:adb_keys_file:s0',
} as const;

/** Android's ids (system/core/include/private/android_filesystem_config.h). */
const AID = { root: 0, system: 1000, shell: 2000, misc: 9998 } as const;

/** The Mirror's Android: 6.0.1. */
const MIRROR_API = 23;

/** S1 Kiosk in the system image: a privileged app, so Android 6 grants its privileged permissions. */
export const APP_DIR = '/priv-app/S1Kiosk';
/** APK ABI folder → the folder Android 6's package manager looks in for a bundled app's libraries. */
const LIB_DIRS: [string, string][] = [['arm64-v8a', 'arm64'], ['armeabi-v7a', 'arm']];

export interface SystemPatch {
  /** S1 Kiosk's legacy APK. */
  apk: Uint8Array;
  /** /system/media/bootanimation.zip; none: the image's own (it has none, so Android's text one). */
  bootAnimation?: Uint8Array;
  /** ro.sf.hwrotation: how the screen is turned. The Mirror stands portrait on a 1920×1080 panel: 270. */
  hwrotation?: 0 | 90 | 180 | 270;
  /** Seconds since 1970 for new files; default now. */
  time?: number;
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
 * Changes the Glass Panes system image on `fs` (already checked by SHA-256). Running it twice gives
 * the same result. Throws, with nothing written, unless it is the expected image and APK.
 */
export async function patchSystem(fs: Ext4, o: SystemPatch, log: (line: string) => void = () => {}): Promise<void> {
  const time = o.time ?? Math.floor(Date.now() / 1000);
  const prop = new TextDecoder().decode(await fs.readFile('/build.prop'));
  if (!prop.includes(`ro.build.fingerprint=${GLASS_PANES.fingerprint}\n`)) throw new Error('this isn\'t the Glass Panes Mirror image Kiln knows (its build fingerprint differs). Nothing was changed.');
  const info = await apkInfo(o.apk);
  if (info.package !== PKG) throw new Error(`this APK is ${info.package}, not S1 Kiosk (${PKG}). Nothing was changed.`);
  // the Mirror runs Android 6 (API 23) on arm64 with 32-bit arm too (frame.json abis): the legacy build fits
  const min = info.minSdk ?? 1;
  if (typeof min !== 'number' || min > MIRROR_API) throw new Error(`this APK needs Android API ${min}, and the Mirror runs Android 6 (API ${MIRROR_API}): pick S1 Kiosk's legacy build. Nothing was changed.`);
  if (info.abis.length && !info.abis.some((a) => LIB_DIRS.some(([x]) => x === a))) throw new Error(`this APK has native code only for ${info.abis.join(', ')}, not for the Mirror's arm64-v8a or armeabi-v7a: pick S1 Kiosk's legacy build. Nothing was changed.`);
  const names = zipNames(o.apk);
  const abi = LIB_DIRS.find(([a]) => names.some((n) => n.startsWith(`lib/${a}/`)));
  const libs = abi ? names.filter((n) => n.startsWith(`lib/${abi[0]}/`) && n.endsWith('.so') && !n.slice(`lib/${abi[0]}/`.length).includes('/')) : [];

  if (await fs.exists(APP_DIR)) { await fs.remove(APP_DIR, { recursive: true, time }); log(`replaced ${APP_DIR}`); }
  await fs.mkdir(APP_DIR, dirAttrs(time));
  await fs.writeFile(`${APP_DIR}/S1Kiosk.apk`, o.apk, fileAttrs(time));
  log(`${APP_DIR}/S1Kiosk.apk (${o.apk.length} bytes)`);
  // Android 6 doesn't unpack a bundled app's libraries from its APK: they sit next to it, by ABI
  if (abi && libs.length) {
    await fs.mkdirs(`${APP_DIR}/lib/${abi[1]}`, dirAttrs(time));
    for (const n of libs) {
      const data = await zipEntry(o.apk, n);
      await fs.writeFile(`${APP_DIR}/lib/${abi[1]}/${n.split('/').pop()}`, data!, fileAttrs(time));
    }
    log(`${libs.length} native librar${libs.length === 1 ? 'y' : 'ies'} for ${abi[0]} in ${APP_DIR}/lib/${abi[1]}`);
  }

  if (o.bootAnimation) {
    if (await fs.exists('/media/bootanimation.zip')) await fs.remove('/media/bootanimation.zip', { time });
    await fs.writeFile('/media/bootanimation.zip', o.bootAnimation, fileAttrs(time));
    log('/media/bootanimation.zip');
  }

  let next = prop;
  if (o.hwrotation !== undefined) next = setProp(next, 'ro.sf.hwrotation', String(o.hwrotation));
  if (next !== prop) { await fs.replaceFile('/build.prop', new TextEncoder().encode(next), time); log(`build.prop: ro.sf.hwrotation=${o.hwrotation}`); }
  await fs.flush();
}

/**
 * A new, empty userdata that lets this browser's adb key in: /data/misc/adb/adb_keys, owned and
 * labelled as Android 6's init and file_contexts make them. Init creates everything else on first
 * boot. `adbKey`: the line adb_keys takes, e.g. Kiln's AdbKey.publicKey.
 */
export async function buildUserdata(disk: Disk, o: { adbKey: string; time?: number; uuid?: Uint8Array }): Promise<Ext4> {
  if (!/^[A-Za-z0-9+/=]+( \S+)?$/.test(o.adbKey)) throw new Error('that isn\'t an adb public key line');
  const time = o.time ?? Math.floor(Date.now() / 1000);
  const fs = await mkfs(disk, {
    label: 'userdata', time, uuid: o.uuid,
    root: { mode: 0o771, uid: AID.system, gid: AID.system, label: LABELS.data },
    lostFound: { mode: 0o770, uid: AID.root, gid: AID.root, label: LABELS.data },
  });
  // init.rc (Android 6): mkdir /data/misc 01771 system misc; mkdir /data/misc/adb 02750 system shell
  await fs.mkdir('/misc', { mode: 0o1771, uid: AID.system, gid: AID.misc, label: LABELS.data, time });
  await fs.mkdir('/misc/adb', { mode: 0o2750, uid: AID.system, gid: AID.shell, label: LABELS.adbKeys, time });
  await fs.writeFile('/misc/adb/adb_keys', new TextEncoder().encode(`${o.adbKey}\n`), { mode: 0o640, uid: AID.system, gid: AID.shell, label: LABELS.adbKeys, time });
  await fs.flush();
  return fs;
}
