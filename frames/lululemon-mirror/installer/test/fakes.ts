// A simulated Mirror on the community Android 6 image, for the tests and Kiln's ?demo=…: its adb shell
// as read on the maintainer's unit on 2026-11-15 (frames/lululemon-mirror/research/home-app.md). It
// has no `cmd`, its shell can't disable apps, and it keeps the home app the way Android 6 does: an
// "Always" pick holds until the set of home apps changes, and the next HOME then shows the chooser.
import type { PanelShell } from '@glassworks/adb';
import { fakeApk, filter, type FakeEl } from '@glassworks/app-adb/fakes';
import { GLASSWORKS, PRIVILEGED, type MirrorApp } from '../src/app';

export const MIRROR_KIOSK = 'com.nimrod.mirrorkiosk/.MainActivity';
export const LAUNCHER3 = 'com.android.launcher3/.Launcher';
/** The Glass Panes image's build fingerprint (image.ts GLASS_PANES). */
const FINGERPRINT = 'Android/msm8916_64/msm8916_64:6.0.1/MMB29M/11120023:userdebug/test-keys';

/**
 * A stand-in for the legacy build of Glassworks for generic Android (#408), as its manifest is: the
 * package, the privileged permissions, MainActivity (sh.edm.s1kiosk.MainActivity) behind
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
    { el: 'activity', attrs: { name: 'sh.edm.s1kiosk.MainActivity', permission: 'android.permission.WRITE_SECURE_SETTINGS', exported: true } },
    { el: 'activity-alias', attrs: { name: 'dev.glassworks.generic.Launcher', targetActivity: 'sh.edm.s1kiosk.MainActivity', exported: true }, children: aliasFilters },
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
  /** In the chooser, the person taps this after `pickAfter` reads of the preferred home; undefined: never */
  picks?: (choices: string[]) => string | undefined;
  pickAfter?: number;
  /** the app keeps its device admin when asked to drop it */
  keepsAdmin?: boolean;
  /** with an app's settings open, the person taps Clear defaults */
  clearsDefaults?: boolean;
  /** each command takes this long (the demo) */
  delayMs?: number;
}

export class FakeMirror implements PanelShell {
  cmds: string[] = [];
  files = new Map<string, string>();
  /** the "Always" home and the home apps it was picked from */
  preferred?: { comp: string; from: string[] };
  chooser?: string[];
  /** the package whose Settings page is open */
  settings?: string;
  installed?: number;
  admins = new Set<string>(['com.nimrod.mirrorkiosk/.AdminReceiver']);
  private reads = 0;

  /** our app: its package, the HOME component Android records (an alias), its admin */
  readonly app: MirrorApp;

  constructor(readonly o: FakeMirrorOptions = {}) {
    this.app = o.app ?? GLASSWORKS;
    this.installed = o.installed;
    this.preferred = { comp: o.installed ? this.app.home : MIRROR_KIOSK, from: this.homes() };
    if (o.installed && this.app.admin) this.admins.add(this.app.admin);
  }

  homes(): string[] {
    return [MIRROR_KIOSK, LAUNCHER3, ...(this.installed ? [this.app.home] : [])];
  }

  /** The person taps `comp`, then Always. */
  pick(comp: string) {
    this.preferred = { comp, from: this.homes() };
    this.chooser = undefined;
  }

  async push(path: string) { this.cmds.push(`push ${path}`); await this.wait(); }

  private async wait() { if (this.o.delayMs) await new Promise((r) => setTimeout(r, this.o.delayMs)); }

  async run(cmd: string): Promise<{ out: string; code: number }> {
    this.cmds.push(cmd);
    await this.wait();
    const ok = (out = '') => ({ out, code: 0 });
    const o = this.o;
    const props: Record<string, string> = {
      'ro.build.version.sdk': String(o.sdk ?? 23), 'ro.build.version.release': o.release ?? '6.0.1',
      'ro.board.platform': o.platform ?? 'msm8916', 'ro.product.cpu.abilist': o.abilist ?? 'arm64-v8a,armeabi-v7a,armeabi',
      'ro.product.model': 'MSM8916 for arm64',
    };
    let m: RegExpExecArray | null;
    if ((m = /^getprop (\S+)$/.exec(cmd))) return ok(`${props[m[1]!] ?? ''}\n`);
    if (cmd === 'df /data') return ok(`Filesystem               Size     Used     Free   Blksize\n/data                    5.1G     1.1G     ${o.free ?? '4.0G'}   4096\n`);
    if (cmd.startsWith('cmd ')) return { out: '/system/bin/sh: cmd: not found\n', code: 127 };
    const PKG = this.app.package;
    if (cmd === `dumpsys package ${PKG}`) {
      return ok(this.installed ? `Packages:\n  Package [${PKG}] (1a2b3c):\n    versionCode=${this.installed} minSdk=23 targetSdk=23\n    versionName=0.5.0-legacy\n` : '');
    }
    if (cmd === 'dumpsys package preferred-activities') { this.reads++; this.maybePick(); return ok(this.preferredDump()); }
    if ((m = /^pm install -r (-g )?(\S+)$/.exec(cmd))) {
      if (o.installFails) return ok(`\tpkg: ${m[2]}\n${o.installFails}\n`);
      this.installed = 600;
      return ok(`\tpkg: ${m[2]}\nSuccess\n`);
    }
    if (this.app.admin && cmd === `dpm set-active-admin ${this.app.admin}`) { this.admins.add(this.app.admin); return ok(`Success: Active admin set to component {${this.app.admin}}\n`); }
    if (cmd === `am start -n ${this.app.activity} --ez remove_admin true`) { if (!o.keepsAdmin && this.app.admin) this.admins.delete(this.app.admin); return ok(`Starting: Intent { cmp=${this.app.activity} (has extras) }\n`); }
    if (cmd === 'dumpsys device_policy') return ok(`Current Device Policy Manager state:\n  Enabled Device Admins (User 0):\n${[...this.admins].map((a) => `  ${a}:\n    uid=10062\n`).join('')}`);
    if (cmd === `pm uninstall ${PKG}`) {
      if (!this.installed) return { out: 'Failure [DELETE_FAILED_INTERNAL_ERROR]\n', code: 0 };
      if (this.app.admin && this.admins.has(this.app.admin)) return { out: 'Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n', code: 0 };
      this.installed = undefined;
      if (this.preferred?.comp === this.app.home) this.preferred = undefined; // Android drops a removed app's preferences
      return ok('Success\n');
    }
    if (cmd === 'am start -a android.intent.action.MAIN -c android.intent.category.HOME') {
      const homes = this.homes();
      if (!this.preferred || this.preferred.from.join() !== homes.join()) { this.preferred = undefined; this.chooser = homes; this.reads = 0; }
      return ok('Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }\n');
    }
    if ((m = /^am start -a android\.settings\.APPLICATION_DETAILS_SETTINGS -d package:(\S+)$/.exec(cmd))) { this.settings = m[1]; return ok(); }
    if ((m = /^cat (\S+) 2>\/dev\/null$/.exec(cmd))) {
      const f = this.files.get(m[1]!);
      return f === undefined ? { out: '', code: 1 } : ok(f);
    }
    if ((m = /^echo '([^']*)' > (\S+)$/.exec(cmd))) { this.files.set(m[2]!, `${m[1]}\n`); return ok(); }
    if ((m = /^rm -f (\S+)$/.exec(cmd))) { this.files.delete(m[1]!); return ok(); }
    return ok();
  }

  private maybePick() {
    if (this.o.clearsDefaults && this.settings && this.preferred?.comp.startsWith(`${this.settings}/`)) { this.preferred = undefined; this.settings = undefined; return; }
    if (!this.chooser || !this.o.picks || this.reads < (this.o.pickAfter ?? 1)) return;
    const comp = this.o.picks(this.chooser);
    if (comp) this.pick(comp);
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
  /** `installed`: our app is there; `ours`: which app (Glassworks by default) */
  constructor(private readonly fp: string = FINGERPRINT, private readonly installed = true, readonly ours: MirrorApp = GLASSWORKS) {}
  async run(cmd: string) {
    this.cmds.push(cmd);
    const ok = (out = '') => ({ out, code: 0 });
    if (cmd === 'getprop sys.boot_completed') return ok('1\n');
    if (cmd === 'getprop ro.build.fingerprint') return ok(`${this.fp}\n`);
    if (cmd === 'getprop ro.sf.hwrotation') return ok('270\n');
    const pkg = this.ours.package;
    if (cmd === `dumpsys package ${pkg}`) return ok(this.installed ? '    versionCode=600 minSdk=23\n    versionName=0.5.0-legacy\n' : '');
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

