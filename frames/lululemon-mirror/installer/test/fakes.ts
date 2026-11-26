// A simulated Mirror on the community Android 6 image, for the tests and Kiln's ?demo=…: its adb shell
// as read on the maintainer's unit on 2026-11-15 (frames/lululemon-mirror/research/home-app.md). It
// has no `cmd`, its shell can't disable apps, and it keeps the home app the way Android 6 does: an
// "Always" pick holds until the set of home apps changes, and the next HOME then shows the chooser.
import type { PanelShell } from '@glassworks/adb';
import { FakeKeySocket, fakeApk, filter, type FakeEl } from '@glassworks/app-adb/fakes';
import { GLASSWORKS, PRIVILEGED, type MirrorApp } from '../src/app';
import { IMAGE_OWNER_FINGERPRINT } from '../src/services';

/** A made-up adb public key line: 524 bytes, the size Android 6's adbd takes, so it has a fingerprint. Not a real key. */
export function fakeAdbKey(seed = 1, name = 'kiln@test'): string {
  let x = seed >>> 0;
  const b = Uint8Array.from({ length: 524 }, () => { x = (Math.imul(x, 1103515245) + 12345) >>> 0; return x >>> 24; });
  let s = '';
  for (const c of b) s += String.fromCharCode(c);
  return `${btoa(s)} ${name}`;
}

/**
 * A minimal valid dex 035 with only these strings (sorted, as ART wants them): what services.jar's
 * classes.dex looks like to Kiln's patch. `dexdump` accepts it.
 */
export async function fakeDex(strings: string[]): Promise<Uint8Array> {
  const list = [...new Set(strings)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const enc = new TextEncoder();
  const items = list.map((t) => { const b = enc.encode(t); if (b.length !== t.length || t.length > 127) throw new Error('fakeDex takes short ASCII strings'); return Uint8Array.from([t.length, ...b, 0]); });
  const ids = 0x70, data = ids + 4 * list.length;
  const map = (data + items.reduce((n, i) => n + i.length, 0) + 3) & ~3;
  const size = map + 4 + 4 * 12;
  const out = new Uint8Array(size);
  const dv = new DataView(out.buffer);
  out.set(enc.encode('dex\n035\0'));
  for (const [at, v] of [[32, size], [36, 0x70], [40, 0x12345678], [52, map], [56, list.length], [60, ids], [104, size - data], [108, data]] as const) dv.setUint32(at, v, true);
  let at = data;
  items.forEach((it, i) => { dv.setUint32(ids + 4 * i, at, true); out.set(it, at); at += it.length; });
  dv.setUint32(map, 4, true);
  [[0x0000, 1, 0], [0x0001, list.length, ids], [0x2002, list.length, data], [0x1000, 1, map]].forEach(([t, n, o], i) => {
    dv.setUint16(map + 4 + i * 12, t!, true); dv.setUint32(map + 8 + i * 12, n!, true); dv.setUint32(map + 12 + i * 12, o!, true);
  });
  out.set(new Uint8Array(await crypto.subtle.digest('SHA-1', out.subarray(32))), 12);
  let a = 1, c = 0;
  for (const x of out.subarray(12)) { a = (a + x) % 65521; c = (c + a) % 65521; }
  dv.setUint32(8, ((c << 16) | a) >>> 0, true);
  return out;
}

/** Strings around the fingerprint as in image 3 (its neighbours there, read 2026-11-22), and on both sides of every slot a key can move it to. */
export const SERVICES_STRINGS = ['0', '9 lives', 'A package name must be specified.', IMAGE_OWNER_FINGERPRINT, 'A2dpSuspended=false', 'Authorizing the Mirror owner\'s ADB key', 'D', 'D: ', 'DATA', 'Fingerprints do not match', 'G', 'Ljava/lang/String;', 'adb_keys'];

/** A services.jar like the image's: META-INF/MANIFEST.MF and classes.dex, deflated (the image's way) or stored, with real CRCs. */
export async function fakeServicesJar(o: { dex?: Uint8Array; stored?: boolean; extra?: [string, Uint8Array][] } = {}): Promise<Uint8Array> {
  const dex = o.dex ?? await fakeDex(SERVICES_STRINGS);
  const crc = (b: Uint8Array) => { let c = ~0; for (const x of b) { c ^= x; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; } return ~c >>> 0; };
  const deflate = async (b: Uint8Array) => new Uint8Array(await new Response(new Blob([b as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new CompressionStream('deflate-raw'))).arrayBuffer());
  const files: [string, Uint8Array][] = [['META-INF/MANIFEST.MF', new TextEncoder().encode('Manifest-Version: 1.0\r\nCreated-By: fake\r\n\r\n')], ['classes.dex', dex], ...(o.extra ?? [])];
  const locals: Uint8Array[] = [], centrals: Uint8Array[] = [];
  let off = 0;
  for (const [name, data] of files) {
    const n = new TextEncoder().encode(name), body = o.stored ? data : await deflate(data);
    const local = new Uint8Array(30 + n.length + body.length), central = new Uint8Array(46 + n.length);
    const lv = new DataView(local.buffer), cv = new DataView(central.buffer);
    lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(8, o.stored ? 0 : 8, true);
    lv.setUint32(14, crc(data), true); lv.setUint32(18, body.length, true); lv.setUint32(22, data.length, true); lv.setUint16(26, n.length, true);
    local.set(n, 30); local.set(body, 30 + n.length);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint16(10, o.stored ? 0 : 8, true);
    cv.setUint32(16, crc(data), true); cv.setUint32(20, body.length, true); cv.setUint32(24, data.length, true); cv.setUint16(28, n.length, true); cv.setUint32(42, off, true);
    central.set(n, 46);
    locals.push(local); centrals.push(central); off += local.length;
  }
  const cdSize = centrals.reduce((n, c) => n + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true); ev.setUint32(12, cdSize, true); ev.setUint32(16, off, true);
  const out = new Uint8Array(off + cdSize + 22);
  let at = 0;
  for (const b of [...locals, ...centrals, end]) { out.set(b, at); at += b.length; }
  return out;
}

export const MIRROR_KIOSK = 'com.nimrod.mirrorkiosk/.MainActivity';
export const LAUNCHER3 = 'com.android.launcher3/.Launcher';
/** The Glass Panes image's build fingerprint (image.ts GLASS_PANES). */
const FINGERPRINT = 'Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11212248:userdebug/test-keys';

/**
 * A stand-in for the legacy build of Glassworks for generic Android (#408), as its manifest is: the
 * package, the privileged permissions, MainActivity (dev.glassworks.MainActivity) behind
 * WRITE_SECURE_SETTINGS, and the Launcher alias with MAIN+HOME+DEFAULT. Each option breaks one thing.
 */
export function fakeMirrorApk(o: {
  package?: string; minSdk?: number; libs?: Record<string, Record<string, Uint8Array>>;
  /** permissions to leave out of the manifest */
  without?: string[];
  /** 'alias' (as #408), 'no-default' (HOME without DEFAULT), 'none' (no HOME filter) */
  home?: 'alias' | 'no-default' | 'none';
} = {}): Uint8Array {
  const home = o.home ?? 'alias';
  const perms = PRIVILEGED.filter((p) => !o.without?.includes(p)).map((name): FakeEl => ({ el: 'uses-permission', attrs: { name } }));
  const aliasFilters = home === 'none' ? [filter(['MAIN'], ['LAUNCHER'])]
    : [filter(['MAIN'], ['LAUNCHER']), filter(['MAIN'], home === 'no-default' ? ['HOME'] : ['HOME', 'DEFAULT'])];
  const tree: FakeEl[] = [...perms, { el: 'application', children: [
    { el: 'activity', attrs: { name: 'dev.glassworks.MainActivity', permission: 'android.permission.WRITE_SECURE_SETTINGS', exported: true } },
    { el: 'activity-alias', attrs: { name: 'dev.glassworks.generic.Launcher', targetActivity: 'dev.glassworks.MainActivity', exported: true }, children: aliasFilters },
  ] }];
  return fakeApk(o.package ?? GLASSWORKS.package, { minSdk: o.minSdk ?? 23, home: tree, ...(o.libs ? { libs: o.libs } : {}) });
}

export interface FakeMirrorOptions {
  sdk?: number;
  release?: string;
  platform?: string;
  abilist?: string;
  /** /data free, as Android 6's df prints it */
  free?: string;
  /** our app's versionCode when it is installed already */
  installed?: number;
  /** which app is ours: Glassworks by default */
  app?: MirrorApp;
  /** pm install's answer, when it isn't Success */
  installFails?: string;
  /** the app keeps its device admin when asked to drop it */
  keepsAdmin?: boolean;
  /** `pm hide` answers false: the other home app stays, so HOME opens Android's chooser */
  hideFails?: boolean;
  /** the community kiosk: `admin` (as Glass Panes' userdata has it, its device admin on, the default), `app` (admin off),
   * or `none` (a Mirror Kiln flashed: a fresh userdata has no kiosk) */
  kiosk?: 'admin' | 'app' | 'none';
  /** the display doesn't follow user_rotation */
  rotationStuck?: boolean;
  /** each command takes this long (the demo) */
  delayMs?: number;
  /** ro.serialno, as Android reads it from the bootloader (the same serial fastboot gives) */
  serialno?: string;
  /** our app is the image's privileged copy (Kiln's fastboot install), so an adb install updates it */
  privileged?: boolean;
  /** `Settings.Global.WIFI_ON` as `settings get` prints it: 1 (on) by default */
  wifiOn?: string;
}

/**
 * Android 6's `dumpsys package <pkg>` for our app, trimmed: a normal app in /data, or the image's copy in
 * /system/priv-app (PackageManagerService grants it INSTALL_PACKAGES, frames/lululemon-mirror/research/privileged-app.md).
 * `granted`: false for a privileged copy Android didn't grant it to.
 */
export function packageDump(pkg: string, code: number, o: { privileged?: boolean; granted?: boolean } = {}): string {
  const priv = !!o.privileged;
  return [
    'Packages:',
    `  Package [${pkg}] (1a2b3c):`,
    '    userId=10062',
    `    codePath=${priv ? '/system/priv-app/Glassworks' : `/data/app/${pkg}-1`}`,
    `    versionCode=${code} minSdk=23 targetSdk=23`,
    '    versionName=0.5.0-legacy',
    `    flags=[ ${priv ? 'SYSTEM ' : ''}HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]`,
    ...(priv ? ['    privateFlags=[ PRIVILEGED ]'] : []),
    `    pkgFlags=[ ${priv ? 'SYSTEM ' : ''}HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]`,
    '    install permissions:',
    ...(priv && o.granted !== false ? ['      android.permission.INSTALL_PACKAGES: granted=true', '      android.permission.REBOOT: granted=true'] : []),
    '      android.permission.INTERNET: granted=true',
    '    User 0: installed=true hidden=false stopped=false notLaunched=false enabled=0',
    '',
  ].join('\n');
}

/**
 * The Mirror on the community image over adb, for the app-only install and undo. It has no touchscreen: nothing here
 * taps Android's chooser. HOME opens the one home app left, the "Always" pick among the same apps, or the chooser.
 */
export class FakeMirror implements PanelShell {
  cmds: string[] = [];
  files = new Map<string, string>();
  /** the "Always" home and the home apps it was picked from */
  preferred?: { comp: string; from: string[] };
  /** packages hidden with pm hide */
  hidden = new Set<string>();
  /** the activity in front, as dumpsys activity prints it */
  focused?: string;
  userRotation = 0;
  accelerometer = 1;
  installed?: number;
  admins = new Set<string>();
  /** the Device Owner, once `dpm set-device-owner` made one (the in-place install): stock has none */
  owner?: string;
  /** `Settings.Global.WIFI_ON` */
  wifiOn: string;

  /** our app: its package, the HOME component Android records (an alias), its admin */
  readonly app: MirrorApp;

  /** our app's API key socket (#574), with `openStream` to it; none: no app of ours that has one */
  keys?: FakeKeySocket;
  openStream?: PanelShell['openStream'];
  /** Our app listens on its API key socket, holding `key`. */
  withKeySocket(keys = new FakeKeySocket()): this {
    this.keys = keys;
    this.openStream = (svc) => keys.open(svc);
    return this;
  }

  constructor(readonly o: FakeMirrorOptions = {}) {
    this.app = o.app ?? GLASSWORKS;
    this.installed = o.installed;
    this.wifiOn = o.wifiOn ?? '1';
    if ((o.kiosk ?? 'admin') === 'admin') this.admins.add('com.nimrod.mirrorkiosk/.AdminReceiver');
    this.preferred = { comp: o.installed ? this.app.home : o.kiosk === 'none' ? LAUNCHER3 : MIRROR_KIOSK, from: this.homes() };
    if (o.installed && this.app.admin) this.admins.add(this.app.admin);
  }

  /** The home apps on the Mirror: the kiosk (unless Kiln flashed it), the image's launcher, and ours. */
  present(): string[] {
    return [...(this.o.kiosk === 'none' ? [] : [MIRROR_KIOSK]), LAUNCHER3, ...(this.installed ? [this.app.home] : [])];
  }

  /** The home apps Android offers: those less the hidden ones. */
  homes(): string[] {
    return this.present().filter((c) => !this.hidden.has(c.split('/')[0]!));
  }

  /** `comp` picked with Always (as the Mirror had it before Kiln, with a mouse). */
  pick(comp: string) {
    this.preferred = { comp, from: this.homes() };
  }

  /** What HOME opens now. */
  private home(): string {
    const homes = this.homes();
    if (homes.length === 1) return homes[0]!;
    if (this.preferred && this.preferred.from.join() === homes.join()) return this.preferred.comp;
    this.preferred = undefined;
    return 'android/com.android.internal.app.ResolverActivity';
  }

  async push(path: string) { this.cmds.push(`push ${path}`); await this.wait(); }

  private async wait() { if (this.o.delayMs) await new Promise((r) => setTimeout(r, this.o.delayMs)); }

  async run(cmd: string): Promise<{ out: string; code: number }> {
    this.cmds.push(cmd);
    const key = this.keys?.shell(cmd);
    if (key) return key;
    await this.wait();
    const ok = (out = '') => ({ out, code: 0 });
    const o = this.o;
    const props: Record<string, string> = {
      'ro.build.version.sdk': String(o.sdk ?? 23), 'ro.build.version.release': o.release ?? '6.0.1',
      'ro.board.platform': o.platform ?? 'msm8916', 'ro.product.cpu.abilist': o.abilist ?? 'arm64-v8a,armeabi-v7a,armeabi',
      'ro.product.model': 'MSM8916 for arm64', 'sys.boot_completed': '1', ...(o.serialno ? { 'ro.serialno': o.serialno } : {}),
    };
    let m: RegExpExecArray | null;
    if ((m = /^getprop (\S+)$/.exec(cmd))) return ok(`${props[m[1]!] ?? ''}\n`);
    if (cmd === 'df /data') return ok(`Filesystem               Size     Used     Free   Blksize\n/data                    5.1G     1.1G     ${o.free ?? '4.0G'}   4096\n`);
    if (cmd.startsWith('cmd ')) return { out: '/system/bin/sh: cmd: not found\n', code: 127 };
    const PKG = this.app.package;
    if (cmd === `dumpsys package ${PKG}`) {
      return ok(this.installed ? packageDump(PKG, this.installed, { privileged: o.privileged }) : '');
    }
    if (cmd === 'dumpsys package preferred-activities') return ok(this.preferredDump());
    const set = /^settings put system (user_rotation|accelerometer_rotation) (\d)$/.exec(cmd);
    if (set) { if (set[1] === 'user_rotation') this.userRotation = Number(set[2]); else this.accelerometer = Number(set[2]); return ok(); }
    if (cmd === 'dumpsys window windows') return ok(`  mRotation=${o.rotationStuck || this.accelerometer !== 0 ? 0 : this.userRotation}\n`);
    if (cmd === 'settings get global wifi_on') return ok(`${this.wifiOn}\n`);
    if ((m = /^settings put global wifi_on (\d)$/.exec(cmd))) { this.wifiOn = m[1]!; return ok(); }
    if ((m = /^pm (hide|unhide) (\S+)$/.exec(cmd))) {
      // Android 6 won't hide a package with an active device admin
      if (m[1] === 'hide' && (o.hideFails || [...this.admins].some((a) => a.startsWith(`${m![2]}/`)))) return ok(`Package ${m[2]} new hidden state: false\n`);
      if (m[1] === 'hide') this.hidden.add(m[2]!); else this.hidden.delete(m[2]!);
      return ok(`Package ${m[2]} new hidden state: ${m[1] === 'hide'}\n`);
    }
    if ((m = /^pm list packages (\S+)$/.exec(cmd))) {
      const present = this.present().map((c) => c.split('/')[0]!);
      return ok(present.includes(m[1]!) && !this.hidden.has(m[1]!) ? `package:${m[1]}\n` : '');
    }
    if (cmd === `am start -a android.intent.action.MAIN -c android.intent.category.HOME ${PKG}`) {
      if (!this.installed) return ok('Error: Activity not started, unable to resolve Intent\n');
      this.focused = this.app.home;
      return ok('Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }\n');
    }
    if (cmd === 'dumpsys activity activities') return ok(this.focused ? `  mFocusedActivity: ActivityRecord{1a2b3c u0 ${this.focused} t7}\n` : '');
    if ((m = /^pm install -r (-g )?(\S+)$/.exec(cmd))) {
      if (o.installFails) return ok(`\tpkg: ${m[2]}\n${o.installFails}\n`);
      this.installed = 600;
      return ok(`\tpkg: ${m[2]}\nSuccess\n`);
    }
    if (this.app.admin && cmd === `dpm set-active-admin ${this.app.admin}`) { this.admins.add(this.app.admin); return ok(`Success: Active admin set to component {${this.app.admin}}\n`); }
    if (cmd === `am start -n ${this.app.activity} --ez remove_admin true`) { if (!o.keepsAdmin && this.app.admin) this.admins.delete(this.app.admin); return ok(`Starting: Intent { cmp=${this.app.activity} (has extras) }\n`); }
    if ((m = /^dpm set-device-owner (\S+)$/.exec(cmd))) {
      if (!this.installed || this.owner) return { out: 'java.lang.IllegalStateException: Trying to set device owner but device owner is already set.\n', code: 255 };
      this.owner = m[1]!;
      this.admins.add(m[1]!);
      return ok(`Success: Device owner set to package ${m[1]!.split('/')[0]}\nActive admin set to component {${m[1]}}\n`);
    }
    if (cmd === 'dumpsys device_policy') {
      const owner = this.owner ? `  Device Owner: \n    admin=ComponentInfo{${this.owner}}\n    name=\n    package=${this.owner.split('/')[0]}\n` : '';
      return ok(`Current Device Policy Manager state:\n${owner}  Enabled Device Admins (User 0):\n${[...this.admins].map((a) => `  ${a}:\n    uid=10062\n`).join('')}`);
    }
    if (cmd === `pm uninstall ${PKG}`) {
      if (!this.installed) return { out: 'Failure [DELETE_FAILED_INTERNAL_ERROR]\n', code: 0 };
      if (this.app.admin && this.admins.has(this.app.admin)) return { out: 'Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n', code: 0 };
      this.installed = undefined;
      if (this.preferred?.comp === this.app.home) this.preferred = undefined; // Android drops a removed app's preferences
      return ok('Success\n');
    }
    if (cmd === 'am start -a android.intent.action.MAIN -c android.intent.category.HOME') {
      this.focused = this.home();
      return ok('Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }\n');
    }
    if ((m = /^cat (\S+) 2>\/dev\/null$/.exec(cmd))) {
      const f = this.files.get(m[1]!);
      return f === undefined ? { out: '', code: 1 } : ok(f);
    }
    if ((m = /^echo '([^']*)' > (\S+)$/.exec(cmd))) { this.files.set(m[2]!, `${m[1]}\n`); return ok(); }
    if ((m = /^rm -f (\S+)$/.exec(cmd))) { this.files.delete(m[1]!); return ok(); }
    return ok();
  }

  /** Android 6's `dumpsys package preferred-activities`, trimmed to the shape it has around HOME. */
  private preferredDump(): string {
    const sms = [
      '      sms:',
      '        7252e47 com.android.mms/.ui.ComposeMessageActivity',
      '         mMatch=0x200000 mAlways=true',
      '          Selected from:',
      '            com.android.mms/.ui.ComposeMessageActivity',
      '          Action: "android.intent.action.SENDTO"',
      '          Category: "android.intent.category.DEFAULT"',
      '          Scheme: "sms"',
      '          AutoVerify=false',
    ];
    const home = this.preferred ? [
      '  Non-Data Actions:',
      '      android.intent.action.MAIN:',
      `        97c67e3 ${this.preferred.comp}`,
      '         mMatch=0x100000 mAlways=true',
      '          Selected from:',
      ...this.preferred.from.map((c) => `            ${c}`),
      '          Action: "android.intent.action.MAIN"',
      '          Category: "android.intent.category.HOME"',
      '          Category: "android.intent.category.DEFAULT"',
      '          AutoVerify=false',
    ] : [];
    return ['Database versions:', '  Internal:', '    sdkVersion=23 databaseVersion=3', '',
      'Preferred Activities User 0:', '  Schemes:', ...sms, '', ...home, '', 'App verification status:', ''].join('\n');
  }
}

/** The Mirror's first start on the patched image, as adb sees it. */
export class FirstBootMirror implements PanelShell {
  cmds: string[] = [];
  hidden = new Set<string>();
  /** `pm hide` answers false (Launcher3 survives): HOME shows Android's chooser */
  hideFails = false;
  /** our app's HOME is off (as the S1 legacy test build's was, 2026-11-16): HOME doesn't resolve in it */
  noHome = false;
  /** user_rotation and auto-rotate as a fresh userdata has them: 0, landscape (read on the unit 2026-11-17) */
  userRotation = 0;
  accelerometer = 1;
  /** the display doesn't follow user_rotation (the timeout case) */
  rotationStuck = false;
  /** Android didn't grant the image's copy INSTALL_PACKAGES */
  notGranted = false;
  /** `installed`: our app is there; `ours`: which app (Glassworks by default) */
  /** the app's API key socket (#574), with `openStream` to it; none: an app older than #574 */
  keys?: FakeKeySocket;
  openStream?: PanelShell['openStream'];
  constructor(private readonly fp: string = FINGERPRINT, private readonly installed = true, readonly ours: MirrorApp = GLASSWORKS) {}
  /** The fresh app's API key socket: listening, with no key yet. */
  withKeySocket(keys = new FakeKeySocket()): this {
    this.keys = keys;
    this.openStream = (svc) => keys.open(svc);
    return this;
  }
  async run(cmd: string) {
    this.cmds.push(cmd);
    const ok = (out = '') => ({ out, code: 0 });
    const key = this.keys?.shell(cmd);
    if (key) return key;
    if (cmd === 'getprop sys.boot_completed') return ok('1\n');
    if (cmd === 'getprop ro.build.fingerprint') return ok(`${this.fp}\n`);
    const set = /^settings put system (user_rotation|accelerometer_rotation) (\d)$/.exec(cmd);
    if (set) { if (set[1] === 'user_rotation') this.userRotation = Number(set[2]); else this.accelerometer = Number(set[2]); return ok(); }
    if (cmd === 'dumpsys window windows') {
      // Android 6.0.1's dumpWindowsLocked(pw, true, null): the windows, then the global state, mRotation among it
      const rot = this.rotationStuck || this.accelerometer !== 0 ? 0 : this.userRotation;
      const [w, h] = rot % 2 ? [1080, 1920] : [1920, 1080];
      return ok(['WINDOW MANAGER WINDOWS (dumpsys window windows)',
        '  Window #0 Window{5f1c2a8 u0 com.android.systemui.ImageWallpaper}:',
        '    mDisplayId=0 stack=0 mSession=Session{3c1d0b5 1043:u0a10023} mClient=android.os.BinderProxy@2b6a4e7',
        `    mConfiguration={1.0 ?mcc?mnc en_US ldltr sw1080dp w${w}dp h${h - 48}dp 240dpi xlrg ${rot % 2 ? 'port' : 'land'} finger -keyb/v/h -nav/h s.4}`,
        '    mHasSurface=true mShownFrame=[0.0,0.0][1080.0,1920.0] isReadyForDisplay()=true',
        '',
        '  mCurrentFocus=Window{9a3e21c u0 dev.glassworks.generic_android.legacy/dev.glassworks.MainActivity}',
        '  mInTouchMode=true mLayoutSeq=58',
        `  mScreenRect=[0,0][${w},${h}]`,
        '  mDisplayFrozen=false windows=0 client=false apps=0  waitingForConfig=false',
        `  mRotation=${rot} mAltOrientation=false`,
        '  mLastWindowForcedOrientation=-1 mForcedAppOrientation=-1',
        ''].join('\n'));
    }
    if (cmd === 'dumpsys window') return ok('WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n  <no ANR has occurred since boot>\n'); // Android 6 prints mRotation here only with dumpAll
    const pkg = this.ours.package;
    if (cmd === `dumpsys package ${pkg}`) return ok(this.installed ? packageDump(pkg, 600, { privileged: true, granted: !this.notGranted }) : '');
    if (cmd === 'pm list packages com.android.launcher3') return ok(this.hidden.has('com.android.launcher3') ? '' : 'package:com.android.launcher3\n');
    if (cmd.startsWith('pm list packages')) return ok('');
    if (cmd === `am start -a android.intent.action.MAIN -c android.intent.category.HOME ${pkg}`) {
      return ok(this.noHome
        ? `Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] pkg=${pkg} }\nError: Activity not started, unable to resolve Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000000 pkg=${pkg} }\n`
        : `Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] pkg=${pkg} }\n`);
    }
    const m = /^pm hide (\S+)$/.exec(cmd);
    if (m) {
      if (this.hideFails) return ok(`Package ${m[1]} new hidden state: false\n`);
      this.hidden.add(m[1]!);
      return ok(`Package ${m[1]} new hidden state: true\n`);
    }
    if (cmd === 'dumpsys activity activities') {
      // through the alias, Android records the alias's target activity
      const front = this.hidden.has('com.android.launcher3') ? this.ours.activity : 'android/com.android.internal.app.ResolverActivity';
      return ok(`  Stack #0:\n  mFocusedActivity: ActivityRecord{b2c93df u0 ${front} t243}\n`);
    }
    return ok();
  }
  async push() {}
}


// The stock Mirror for the in-place install (Stock-in-place): its bundle, its agent and its two fastboot sessions.
export * from './fakes-inplace';
