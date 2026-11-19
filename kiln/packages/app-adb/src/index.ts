// S1 Kiosk over adb, the steps that are the same on every frame: install the APK, grant what adb's
// shell can grant, start the app with the Home Assistant URL, read its version, and take back its
// device admin before it is removed. Each frame's recipe adds its own steps around these
// (frames/<id>/installer). The same "try" (never fails) vs "run" (must succeed) split as
// frames/aqara-s1-plus/tools/install.sh.
import type { PanelShell } from '@glassworks/adb';
import { ImprovSerialClient } from 'improv-serial';

/** The app's id. The boot helpers and every installed panel use it: never change it. */
export const PKG = 'sh.edm.s1kiosk';
export const ACT = `${PKG}/.MainActivity`;
export const ADMIN = `${PKG}/.AdminReceiver`;
/**
 * Which app the steps below act on: its package, the activity adb starts it with (and whose extras
 * it trusts from adb), and its device admin if it has one. S1 Kiosk by default, so the S1's commands
 * stay as pinned (frames/aqara-s1-plus/installer/test/adb-commands-pin.test.ts). Another frame names
 * its own app, e.g. the Mirror's legacy build of Glassworks for generic Android.
 */
export interface AppId { package: string; activity: string; admin?: string }
export const S1_KIOSK: AppId = { package: PKG, activity: ACT, admin: ADMIN };

/** The app's ImprovAdbServer socket (ImprovSerialServer.SOCKET). */
export const IMPROV_SOCKET = 'localabstract:s1kiosk_improv';

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
export async function kioskVersion(p: PanelShell, signal?: AbortSignal, app: AppId = S1_KIOSK): Promise<{ name?: string; code: number } | undefined> {
  const dump = (await p.run(`dumpsys package ${app.package}`, signal)).out;
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
export async function grantPermissions(r: Steps, app: AppId = S1_KIOSK): Promise<void> {
  const pkg = app.package;
  r.step('Permissions');
  await r.try(`pm grant ${pkg} android.permission.RECORD_AUDIO`);
  await r.try(`pm grant ${pkg} android.permission.CAMERA`);
  await r.try(`pm grant ${pkg} android.permission.WRITE_SECURE_SETTINGS`);
  await r.try(`appops set ${pkg} SYSTEM_ALERT_WINDOW allow`);
  await r.try(`pm grant ${pkg} android.permission.CHANGE_CONFIGURATION`);   // language from the installer
  if (app.admin) await r.try(`dpm set-active-admin ${app.admin}`);
}

/** Starts the app, handing it the Home Assistant URL and the language when given. */
export async function startApp(r: Steps, opts: { haUrl?: string; locale?: string }, app: AppId = S1_KIOSK): Promise<void> {
  const extras = [opts.haUrl ? `--es url ${q(opts.haUrl)}` : '', opts.locale ? `--es set_locale ${q(opts.locale)}` : ''].filter(Boolean);
  await r.run(`am start -n ${app.activity}${extras.length ? ` ${extras.join(' ')}` : ''}`);
}

/**
 * Android refuses to uninstall (or disable) an active device admin, and adb's shell can't remove
 * one. So the app is asked to drop it (`remove_admin`), and this waits until it has. False if it
 * still holds it after 15 s.
 */
export async function dropAdmin(p: PanelShell, sleep: (ms: number) => Promise<void>, app: AppId = S1_KIOSK): Promise<boolean> {
  await p.run(`am start -n ${app.activity} --ez remove_admin true`);
  // the admin's component (pkg/cls): a bare package would also match another package that starts with it
  for (let i = 0; i < 30; i++) {
    if (!(await p.run('dumpsys device_policy')).out.includes(`${app.package}/`)) return true;
    await sleep(500);
  }
  return false;
}

export { ManifestError, apkHome, apkInfo, apkPermissions, apkPackage, axmlAttribute, axmlTree, zipEntry, zipNames, type ApkInfo, type AxmlElement, type AxmlValue } from './apk';

/**
 * Gives the app a Wi-Fi network over its Improv socket, once it listens (it starts with the app).
 * True when the app says it joined. The password goes only into the Improv packet: never into a
 * command line, a log line or an error.
 */
export async function sendWifi(p: PanelShell, ssid: string, password: string, sleep: (ms: number) => Promise<void>, waitMs = 90_000): Promise<boolean> {
  if (!p.openStream) return false;
  const until = Date.now() + waitMs;
  while (Number.parseInt((await p.run(`grep -c ' @s1kiosk_improv$' /proc/net/unix`)).out.trim(), 10) <= 0) {
    if (Date.now() > until) return false;
    await sleep(1500);
  }
  const c = new ImprovSerialClient(await p.openStream(IMPROV_SOCKET), { deviceLabel: 'device' });
  try {
    await c.requestState(5000);
    await c.provision(ssid, password);
    return true;
  } catch {
    return false;
  } finally {
    await c.close().catch(() => {});
  }
}

/** The app's ESPHome API key hand-over (core ApiKeyHandoverServer.SOCKET): adb only, never the LAN (#574). */
export const API_KEY_SOCKET = 'localabstract:glassworks_api_key';

/** One request line, one reply line, over a fresh connection to the hand-over socket. */
async function keyRequest(p: PanelShell, line: string, timeoutMs = 10_000): Promise<string> {
  const s = await p.openStream!(API_KEY_SOCKET);
  try {
    return await new Promise<string>((resolve, reject) => {
      let got = '';
      const t = setTimeout(() => reject(new Error('the app didn\'t answer on its API key socket')), timeoutMs);
      s.onData((b) => {
        got += new TextDecoder().decode(b);
        const i = got.indexOf('\n');
        if (i >= 0) { clearTimeout(t); resolve(got.slice(0, i).trim()); }
      });
      s.write(new TextEncoder().encode(`${line}\n`)).catch((e) => { clearTimeout(t); reject(e); });
    });
  } finally {
    await s.close().catch(() => {});
  }
}

/** Whether the app listens on its API key socket now (an app older than #574 never does). */
async function keySocketUp(p: PanelShell): Promise<boolean> {
  return Number.parseInt((await p.run(`grep -c ' @glassworks_api_key$' /proc/net/unix`)).out.trim(), 10) > 0;
}

/**
 * The ESPHome API key Home Assistant provisioned, as base64 text, read from the running app before a reflash wipes
 * it. Undefined when the app has none, can't read it now, or is too old to hand it over. It is a secret: never put
 * it in a log line, an error or a command line.
 */
export async function readApiKey(p: PanelShell): Promise<string | undefined> {
  if (!p.openStream || !(await keySocketUp(p))) return undefined;
  const r = await keyRequest(p, 'get');
  const m = /^key ([A-Za-z0-9+/]{43}=)$/.exec(r);
  return m ? m[1] : undefined;
}

/**
 * Hands `key` (from readApiKey) to the freshly flashed app, once its socket listens (it starts with the app). True
 * when the app took it. It goes only into the socket, never a command line or a log.
 */
export async function handOverApiKey(p: PanelShell, key: string, sleep: (ms: number) => Promise<void>, waitMs = 90_000): Promise<boolean> {
  if (!p.openStream) return false;
  const until = Date.now() + waitMs;
  while (!(await keySocketUp(p))) {
    if (Date.now() > until) return false;
    await sleep(1500);
  }
  return (await keyRequest(p, `set ${key}`)) === 'ok';
}

/** A key's fingerprint as the app logs it (NoiseKeys.fingerprint): the first 2 bytes of its SHA-256, in hex. Reveals nothing. */
export async function apiKeyFingerprint(key: string): Promise<string> {
  const raw = Uint8Array.from(atob(key), (c) => c.charCodeAt(0));
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', raw)).slice(0, 2)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
