// A simulated Mirror on the community Android 6 image, for the tests and Kiln's ?demo=…: its adb shell
// as read on the maintainer's unit on 2026-11-15 (frames/lululemon-mirror/research/home-app.md). It
// has no `cmd`, its shell can't disable apps, and it keeps the home app the way Android 6 does: an
// "Always" pick holds until the set of home apps changes, and the next HOME then shows the chooser.
import type { PanelShell } from '@glassworks/adb';

export const MIRROR_KIOSK = 'com.nimrod.mirrorkiosk/.MainActivity';
export const LAUNCHER3 = 'com.android.launcher3/.Launcher';
const OURS = 'sh.edm.s1kiosk/.MainActivity';
const PKG = 'sh.edm.s1kiosk';

export interface FakeMirrorOptions {
  sdk?: number;
  release?: string;
  platform?: string;
  abilist?: string;
  /** /data free, as Android 6's df prints it */
  free?: string;
  /** S1 Kiosk's versionCode when it is installed already */
  installed?: number;
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

  constructor(readonly o: FakeMirrorOptions = {}) {
    this.installed = o.installed;
    this.preferred = { comp: o.installed ? OURS : MIRROR_KIOSK, from: this.homes() };
    if (o.installed) this.admins.add(`${PKG}/.AdminReceiver`);
  }

  homes(): string[] {
    return [MIRROR_KIOSK, LAUNCHER3, ...(this.installed ? [OURS] : [])];
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
    if (cmd === `dumpsys package ${PKG}`) {
      return ok(this.installed ? `Packages:\n  Package [${PKG}] (1a2b3c):\n    versionCode=${this.installed} minSdk=23 targetSdk=23\n    versionName=0.5.0-legacy\n` : '');
    }
    if (cmd === 'dumpsys package preferred-activities') { this.reads++; this.maybePick(); return ok(this.preferredDump()); }
    if ((m = /^pm install -r (-g )?(\S+)$/.exec(cmd))) {
      if (o.installFails) return ok(`\tpkg: ${m[2]}\n${o.installFails}\n`);
      this.installed = 600;
      return ok('\tpkg: /data/local/tmp/s1kiosk.apk\nSuccess\n');
    }
    if (cmd === 'dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver') { this.admins.add(`${PKG}/.AdminReceiver`); return ok('Success: Active admin set to component {sh.edm.s1kiosk/sh.edm.s1kiosk.AdminReceiver}\n'); }
    if (cmd === `am start -n ${OURS} --ez remove_admin true`) { if (!o.keepsAdmin) this.admins.delete(`${PKG}/.AdminReceiver`); return ok('Starting: Intent { cmp=sh.edm.s1kiosk/.MainActivity (has extras) }\n'); }
    if (cmd === 'dumpsys device_policy') return ok(`Current Device Policy Manager state:\n  Enabled Device Admins (User 0):\n${[...this.admins].map((a) => `  ${a}:\n    uid=10062\n`).join('')}`);
    if (cmd === `pm uninstall ${PKG}`) {
      if (!this.installed) return { out: 'Failure [DELETE_FAILED_INTERNAL_ERROR]\n', code: 0 };
      if (this.admins.has(`${PKG}/.AdminReceiver`)) return { out: 'Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n', code: 0 };
      this.installed = undefined;
      if (this.preferred?.comp === OURS) this.preferred = undefined; // Android drops a removed app's preferences
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
