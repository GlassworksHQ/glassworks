// A simulated Android device for the tests and, later, Kiln's ?demo=…: its adb shell as Android answers
// at each API level. Android 6 (API 23) has no `cmd`, so its home app is Android's chooser and an "Always"
// pick in `dumpsys package preferred-activities`; Android 7+ has `cmd package set-home-activity` and
// `resolve-activity`. Options make it a vendor build that refuses a grant or set-home-activity.
// Its setup channel answers like the app's (core SetupChannel): line-delimited JSON on @s1kiosk_setup.
import type { PanelShell } from '@glassworks/adb';
import { fakeApk, filter, type FakeEl } from '@glassworks/app-adb/fakes';
import { GENERIC, LEGACY, type GenericApp } from '../src/app';

type ByteStream = Awaited<ReturnType<NonNullable<PanelShell['openStream']>>>;

const LAUNCHER = 'com.android.launcher3/.Launcher';
const RESOLVER = 'android/com.android.internal.app.ResolverActivity';

/** A stand-in APK of either build, as its manifest is (frames/generic-android/android/src/main). */
export function fakeGenericApk(o: { app?: GenericApp; package?: string; minSdk?: number; abis?: string[]; perms?: string[] } = {}): Uint8Array {
  const app = o.app ?? GENERIC;
  const perms = (o.perms ?? ['android.permission.RECORD_AUDIO', 'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.WRITE_SECURE_SETTINGS', 'android.permission.INTERNET',
    'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION'])
    .map((name): FakeEl => ({ el: 'uses-permission', attrs: { name } }));
  const tree: FakeEl[] = [...perms, { el: 'application', children: [
    { el: 'activity', attrs: { name: 'sh.edm.s1kiosk.MainActivity', permission: 'android.permission.WRITE_SECURE_SETTINGS', exported: true } },
    { el: 'activity-alias', attrs: { name: 'dev.glassworks.generic.Launcher', targetActivity: 'sh.edm.s1kiosk.MainActivity', exported: true },
      children: [filter(['MAIN'], ['LAUNCHER']), filter(['MAIN'], ['HOME', 'DEFAULT'])] },
  ] }];
  const libs = Object.fromEntries((o.abis ?? ['arm64-v8a']).map((a) => [a, { 'libxul.so': Uint8Array.of(0x7f, 0x45, 0x4c, 0x46) }]));
  return fakeApk(o.package ?? app.package, { minSdk: o.minSdk ?? app.apis.min, home: tree, libs });
}

export interface FakeAndroidOptions {
  sdk?: number;
  release?: string;
  abilist?: string;
  manufacturer?: string;
  model?: string;
  /** the app's versionCode when it is installed already */
  installed?: number;
  /** pm install's answer, when it isn't Success */
  installFails?: string;
  /** permissions whose `pm grant` the vendor build refuses (and doesn't grant) */
  refuseGrant?: string[];
  /** `pm grant` answers 0 but the build doesn't grant it (read back as off) */
  silentlyIgnore?: string[];
  refuseAppops?: boolean;
  /** `appops set … REQUEST_INSTALL_PACKAGES` answers 0 but the build keeps it off (a device policy, a vendor build) */
  keepInstallOpOff?: boolean;
  refuseAdmin?: boolean;
  /** the other build's device admin is active, left over from it (its package starts with this one's) */
  otherAdmin?: boolean;
  /** `cmd package set-home-activity` fails, as on a vendor build that locks its launcher */
  refuseSetHome?: boolean;
  /** in the chooser, the person taps Glassworks and Always after this many reads; undefined: never */
  picksAfter?: number;
  /** the app's Wi-Fi address on its setup channel; undefined: not on Wi-Fi */
  ip?: string;
  /** the app has no setup channel (an older build) or no pairing.arm */
  noChannel?: boolean;
  noPairing?: boolean;
}

export class FakeAndroid implements PanelShell {
  cmds: string[] = [];
  installed?: number;
  granted = new Set<string>();
  overlay = false;
  /** "Install unknown apps" for the app: Android's default until set */
  installOp: 'allow' | 'default' = 'default';
  admins = new Set<string>();
  /** the home app Android opens; RESOLVER while it would ask */
  home = LAUNCHER;
  private reads = 0;
  private chooser = false;
  readonly app: GenericApp;

  constructor(readonly o: FakeAndroidOptions = {}) {
    this.app = (o.sdk ?? 34) >= 26 ? GENERIC : LEGACY;
    this.installed = o.installed;
    if (o.installed) { this.home = this.app.home; if (this.app.admin) this.admins.add(this.app.admin); }
    if (o.otherAdmin) this.admins.add((this.app === GENERIC ? LEGACY : GENERIC).admin!);
  }

  async push(path: string, data: Uint8Array, mode?: number) { this.cmds.push(`push ${path} ${data.length} ${(mode ?? 0o644).toString(8)}`); }

  async run(cmd: string): Promise<{ out: string; code: number }> {
    this.cmds.push(cmd);
    const o = this.o;
    const sdk = o.sdk ?? 34;
    const ok = (out = '') => ({ out, code: 0 });
    const PKG = this.app.package;
    const props: Record<string, string> = {
      'ro.build.version.sdk': String(sdk), 'ro.build.version.release': o.release ?? '14',
      'ro.product.cpu.abilist': o.abilist ?? 'arm64-v8a,armeabi-v7a,armeabi',
      'ro.product.manufacturer': o.manufacturer ?? 'Google', 'ro.product.model': o.model ?? 'Pixel Tablet',
      'ro.build.fingerprint': `example/tablet/tablet:${o.release ?? '14'}/TEST.000000/1:user/release-keys`,
    };
    let m: RegExpExecArray | null;
    if ((m = /^getprop (\S+)$/.exec(cmd))) return ok(`${props[m[1]!] ?? ''}\n`);
    if (cmd.startsWith('cmd ') && sdk < 24) return { out: '/system/bin/sh: cmd: not found\n', code: 127 };
    if (cmd === `dumpsys package ${PKG}`) {
      if (!this.installed) return ok('');
      const perms = [...this.granted].map((p) => `      ${p}: granted=true\n`).join('');
      return ok(`Packages:\n  Package [${PKG}] (1a2b3c):\n    versionCode=${this.installed} minSdk=${this.app.apis.min} targetSdk=35\n    versionName=0.6.0\n    runtime permissions:\n${perms}`);
    }
    if ((m = /^pm install -r -g (\S+)$/.exec(cmd))) {
      if (o.installFails) return ok(`${o.installFails}\n`);
      if (!this.installed) this.chooser = true; // a new home app: Android asks again
      this.installed = 700;
      return ok('Success\n');
    }
    if ((m = /^pm grant (\S+) (\S+)$/.exec(cmd)) && m[1] === PKG) {
      if (o.refuseGrant?.includes(m[2]!)) return { out: `Exception occurred while executing 'grant':\njava.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS.\n`, code: 255 };
      if (!o.silentlyIgnore?.includes(m[2]!)) this.granted.add(m[2]!);
      return ok();
    }
    if (cmd === `appops set ${PKG} SYSTEM_ALERT_WINDOW allow`) {
      if (o.refuseAppops) return { out: 'Error: java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES.\n', code: 255 };
      this.overlay = true; return ok();
    }
    if (cmd === `appops get ${PKG} SYSTEM_ALERT_WINDOW`) return ok(`SYSTEM_ALERT_WINDOW: ${this.overlay ? 'allow' : 'default'}\n`);
    if ((m = /^appops set (\S+) REQUEST_INSTALL_PACKAGES (allow|default)$/.exec(cmd)) && m[1] === PKG) {
      if (!o.keepInstallOpOff || m[2] === 'default') this.installOp = m[2] as 'allow' | 'default';
      return ok();
    }
    if (cmd === `appops get ${PKG} REQUEST_INSTALL_PACKAGES`) return ok(`REQUEST_INSTALL_PACKAGES: ${this.installOp}\n`);
    if (this.app.admin && cmd === `dpm set-active-admin ${this.app.admin}`) {
      if (o.refuseAdmin) return { out: 'java.lang.SecurityException: Calling identity is not authorized\n', code: 255 };
      if (this.admins.has(this.app.admin)) return { out: 'Exception occurred while executing:\njava.lang.IllegalArgumentException: Admin is already added\n', code: 255 };
      this.admins.add(this.app.admin);
      return ok(`Success: Active admin set to component {${this.app.admin}}\n`);
    }
    if (cmd === 'dumpsys device_policy') return ok(`Current Device Policy Manager state:\n  Enabled Device Admins (User 0, provisioningState: 0):\n${[...this.admins].map((a) => `    ${a}:\n      uid=10123\n`).join('')}`);
    if ((m = /^am start -n (\S+)/.exec(cmd)) && m[1] === this.app.activity) {
      if (cmd.endsWith('--ez remove_admin true') && this.app.admin) this.admins.delete(this.app.admin);
      return ok(`Starting: Intent { cmp=${this.app.activity} (has extras) }\n`);
    }
    if (cmd === `cmd package set-home-activity ${this.app.home}`) {
      if (o.refuseSetHome) return { out: 'Error: Failed to set default home.\n', code: 255 };
      this.home = this.app.home; this.chooser = false;
      return ok('Success\n');
    }
    if (cmd === 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') {
      this.maybePick();
      return ok(`priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n${this.chooser ? RESOLVER : this.home}\n`);
    }
    if (cmd === 'dumpsys package preferred-activities') { this.maybePick(); return ok(this.preferredDump()); }
    if (cmd.startsWith('dumpsys package ')) return ok(''); // another package: not installed
    if (cmd === 'am start -a android.intent.action.MAIN -c android.intent.category.HOME') { this.reads = 0; return ok('Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }\n'); }
    if (cmd === `pm uninstall ${PKG}`) {
      if (this.app.admin && this.admins.has(this.app.admin)) return ok('Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n');
      this.installed = undefined; this.home = LAUNCHER; this.installOp = 'default';
      return ok('Success\n');
    }
    return ok();
  }

  private maybePick() {
    this.reads++;
    if (this.chooser && this.o.picksAfter !== undefined && this.reads >= this.o.picksAfter) { this.home = this.app.home; this.chooser = false; }
  }

  /** Android 6's dump around HOME: the "Always" home, or none while it would ask. */
  private preferredDump(): string {
    const home = this.chooser ? [] : [
      '      android.intent.action.MAIN:',
      `        97c67e3 ${this.home}`,
      '         mMatch=0x100000 mAlways=true',
      '          Action: "android.intent.action.MAIN"',
      '          Category: "android.intent.category.HOME"',
      '          Category: "android.intent.category.DEFAULT"',
    ];
    return ['Preferred Activities User 0:', '  Non-Data Actions:', ...home, '', 'App verification status:', ''].join('\n');
  }

  /** The app's setup channel: `state` and `pairing.arm`, recorded line by line. */
  async openStream(service: string): Promise<ByteStream> {
    this.cmds.push(`open ${service}`);
    if (this.o.noChannel || service !== 'localabstract:s1kiosk_setup' || !this.installed) throw new Error('connection refused');
    let cb: (b: Uint8Array) => void = () => {};
    const state = { ...(this.o.ip ? { ip: this.o.ip } : {}), ha: { signedIn: false }, radio: { mode: 'off', status: '', options: [], helper: false } };
    return {
      write: async (d) => {
        const line = new TextDecoder().decode(d);
        this.cmds.push(`setup ${line.trim()}`);
        const req = JSON.parse(line) as { id: number; op: string };
        const reply = req.op === 'state' || (req.op === 'pairing.arm' && !this.o.noPairing)
          ? { id: req.id, ok: true, state } : { id: req.id, ok: false, error: 'Unknown request' };
        queueMicrotask(() => cb(new TextEncoder().encode(`${JSON.stringify(reply)}\n`)));
      },
      onData: (f) => { cb = f; },
      close: async () => { this.cmds.push(`close ${service}`); },
    };
  }
}
