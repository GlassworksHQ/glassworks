// S1 Kiosk over adb, the steps that are the same on every frame: install the APK, grant what adb's
// shell can grant, start the app with the Home Assistant URL, read its version, and take back its
// device admin before it is removed. Each frame's recipe adds its own steps around these
// (frames/<id>/installer). The same "try" (never fails) vs "run" (must succeed) split as
// frames/aqara-s1-plus/tools/install.sh.
import type { PanelShell } from '@glassworks/adb';

/** The app's id. The boot helpers and every installed panel use it: never change it. */
export const PKG = 'sh.edm.s1kiosk';
export const ACT = `${PKG}/.MainActivity`;
export const ADMIN = `${PKG}/.AdminReceiver`;

/** A step heading, or with an empty step, a detail line under it. */
export type StepFn = (step: string, detail?: string) => void;

/** Shell-quotes one argument (single quotes). */
export function q(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

/** Commands on the device, with the step log. */
export class Steps {
  constructor(readonly p: PanelShell, private readonly onStep: StepFn = () => {}) {}
  step(s: string) { this.onStep(s); }
  detail(s: string) { this.onStep('', s); }
  /** Must succeed. */
  async run(cmd: string): Promise<string> {
    const r = await this.p.run(cmd);
    if (r.code !== 0) throw new Error(`panel command failed (${r.code}): ${cmd}\n${r.out.trim()}`);
    return r.out;
  }
  /** Never fails (idempotent steps such as re-granting). */
  async try(cmd: string): Promise<string> {
    return (await this.p.run(cmd)).out;
  }
  async install(name: string, apk: Uint8Array, grant: boolean, pushed?: () => void) {
    const out = await this.tryInstall(name, apk, grant, pushed);
    if (!/Success/.test(out)) throw new Error(`installing ${name} failed: ${out.trim()}`);
  }
  /** pm install -r; returns pm's output either way (the caller decides what a failure means). `pushed`: the APK is on the device. */
  async tryInstall(name: string, apk: Uint8Array, grant: boolean, pushed?: () => void): Promise<string> {
    const tmp = `/data/local/tmp/${name}.apk`;
    await this.p.push(tmp, apk, 0o644);
    pushed?.();
    try {
      return (await this.p.run(`pm install -r ${grant ? '-g ' : ''}${tmp}`)).out;
    } finally {
      await this.try(`rm -f ${tmp}`);
    }
  }
  /** debloat.sh op disable: skip packages the firmware already disabled itself (state 2). */
  async disable(pkg: string) {
    const listed = await this.try(`pm list packages -d ${pkg}`);
    if (listed.split(/\r?\n/).some((l) => l.trim() === `package:${pkg}`)) { this.detail(`already   ${pkg} (disabled)`); return; }
    const r = await this.p.run(`pm disable-user --user 0 ${pkg}`);
    this.detail(`${r.code === 0 ? 'disabled ' : 'skip/err '} ${pkg}`);
  }
  async enable(pkg: string) {
    const r = await this.p.run(`pm enable ${pkg}`);
    this.detail(`${r.code === 0 ? 'enabled  ' : 'skip/err '} ${pkg}`);
  }
}

/**
 * S1 Kiosk's version on the device, from `dumpsys package` (read-only); undefined when it isn't
 * installed. `signal` cancels the read.
 */
export async function kioskVersion(p: PanelShell, signal?: AbortSignal): Promise<{ name?: string; code: number } | undefined> {
  const dump = (await p.run(`dumpsys package ${PKG}`, signal)).out;
  const code = /versionCode=(\d+)/.exec(dump)?.[1];
  const name = /versionName=(\S+)/.exec(dump)?.[1];
  return code ? { name: name === 'null' ? undefined : name, code: Number(code) } : undefined;
}

export async function deviceInfo(p: PanelShell): Promise<{ model: string; sdk: string }> {
  const model = (await p.run('getprop ro.product.model')).out.trim();
  const sdk = (await p.run('getprop ro.build.version.sdk')).out.trim();
  return { model, sdk };
}

/**
 * What the app asks for that adb's shell can grant: microphone and camera (runtime permissions),
 * secure settings and the language (development permissions), the overlay, and the device admin
 * it locks the screen with. Each is a `try`: a device that refuses one still gets the rest.
 */
export async function grantPermissions(r: Steps): Promise<void> {
  r.step('Permissions');
  await r.try(`pm grant ${PKG} android.permission.RECORD_AUDIO`);
  await r.try(`pm grant ${PKG} android.permission.CAMERA`);
  await r.try(`pm grant ${PKG} android.permission.WRITE_SECURE_SETTINGS`);
  await r.try(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`);
  await r.try(`pm grant ${PKG} android.permission.CHANGE_CONFIGURATION`);   // language from the installer
  await r.try(`dpm set-active-admin ${ADMIN}`);
}

/** Starts the app, handing it the Home Assistant URL and the language when given. */
export async function startApp(r: Steps, opts: { haUrl?: string; locale?: string }): Promise<void> {
  const extras = [opts.haUrl ? `--es url ${q(opts.haUrl)}` : '', opts.locale ? `--es set_locale ${q(opts.locale)}` : ''].filter(Boolean);
  await r.run(`am start -n ${ACT}${extras.length ? ` ${extras.join(' ')}` : ''}`);
}

/**
 * Android refuses to uninstall (or disable) an active device admin, and adb's shell can't remove
 * one. So the app is asked to drop it (`remove_admin`), and this waits until it has. False if it
 * still holds it after 15 s.
 */
export async function dropAdmin(p: PanelShell, sleep: (ms: number) => Promise<void>): Promise<boolean> {
  await p.run(`am start -n ${ACT} --ez remove_admin true`);
  for (let i = 0; i < 30; i++) {
    if (!(await p.run('dumpsys device_policy')).out.includes(PKG)) return true;
    await sleep(500);
  }
  return false;
}

export { apkPackage, axmlAttribute, zipEntry } from './apk';
