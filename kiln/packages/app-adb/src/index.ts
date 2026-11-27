// Glassworks over adb, the steps that are the same on every frame: install the APK, grant what adb's
// shell can grant, start the app with the Home Assistant URL, read its version, and take back its
// device admin before it is removed. Each frame's recipe adds its own steps around these
// (frames/<id>/installer). The same "try" (never fails) vs "run" (must succeed) split as the
// reference CLI's install.sh.
import type { PanelShell } from '@glassworks/adb';
import { ImprovSerialClient } from '@glassworks/improv-serial';

/**
 * Which app the steps below act on: its package, the activity adb starts it with (and whose extras
 * it trusts from adb), and its device admin if it has one. Each frame names its own app, in its
 * installer (frames/<id>/installer).
 */
export interface AppId { package: string; activity: string; admin?: string }

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
 * Glassworks' version on the device, from `dumpsys package` (read-only); undefined when it isn't
 * installed. `signal` cancels the read.
 */
export async function kioskVersion(p: PanelShell, signal: AbortSignal | undefined, app: AppId): Promise<{ name?: string; code: number } | undefined> {
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
export async function grantPermissions(r: Steps, app: AppId): Promise<void> {
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
export async function startApp(r: Steps, opts: { haUrl?: string; locale?: string }, app: AppId): Promise<void> {
  const extras = [opts.haUrl ? `--es url ${q(opts.haUrl)}` : '', opts.locale ? `--es set_locale ${q(opts.locale)}` : ''].filter(Boolean);
  await r.run(`am start -n ${app.activity}${extras.length ? ` ${extras.join(' ')}` : ''}`);
}

/**
 * The Wi-Fi network the device is on, read-only, from `dumpsys wifi`'s `mWifiInfo` (Android 6 to 14 print it, quoted
 * from 10 on). Undefined when it isn't on one, or Android doesn't say: a frame with a reader of its own uses that.
 */
export async function wifiSsid(p: PanelShell, signal?: AbortSignal): Promise<{ ssid?: string }> {
  // up to the `, BSSID` that ends it, as printed: a network's name may hold spaces at its ends, commas and quotes
  const m = /mWifiInfo SSID: (?:"(.*?)"|(.*?)), BSSID/.exec((await p.run('dumpsys wifi', signal)).out);
  const ssid = m?.[1] ?? m?.[2];
  return ssid && ssid !== '<unknown ssid>' ? { ssid } : {};
}

/**
 * The person's language, time zone, 12- or 24-hour clock and the time itself, on a device whose app is already on (the standard flow's App step on a
 * frame whose install put the app on, issue 621). The same commands a frame's provisioning runs after an install, each a `try`: a vendor build
 * that refuses one still gets the rest. Then the app starts again with the language, which it applies itself.
 */
export async function applySettings(r: Steps, s: { locale?: string; timezone?: string; clock24?: boolean; now?: number }, app: AppId): Promise<void> {
  if (s.timezone && !/^[A-Za-z0-9_+\-/]+$/.test(s.timezone)) throw new Error(`bad time zone ${s.timezone}`);
  if (s.locale && !/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(s.locale)) throw new Error(`bad locale ${s.locale}`);
  if (s.now !== undefined && !(Number.isSafeInteger(s.now) && s.now > 0)) throw new Error(`bad time ${s.now}`);
  // the clock, from this computer's, only when the device's is more than a minute off: one that never reached a time server
  // (no network yet, or none it can reach) starts in 1970. Its own network time takes over when it gets one
  if (s.now !== undefined) {
    const dev = Number((await r.try('date +%s')).trim());
    if (!Number.isFinite(dev) || Math.abs(dev * 1000 - s.now) > 60_000) {
      r.step('Clock');
      await r.try(`service call alarm 2 i64 ${s.now}`);
      r.detail(`now ${(await r.try('date')).trim() || 'unknown'}`);
    }
  }
  if (s.timezone) {
    r.step(`Time zone ${s.timezone}`);
    await r.try('settings put global auto_time_zone 0');
    await r.try(`service call alarm 3 s16 ${q(s.timezone)}`);
    r.detail(`now ${(await r.try('getprop persist.sys.timezone')).trim() || 'unknown'}`);
  }
  if (s.locale) {
    r.step(`Language ${s.locale}`);
    await r.try(`settings put system system_locales ${q(s.locale)}`); // Android 7 and later read it at boot; the app applies it now
  }
  if (s.clock24 !== undefined) {
    r.step(`${s.clock24 ? '24' : '12'}-hour clock`);
    await r.try(`settings put system time_12_24 ${s.clock24 ? 24 : 12}`);
  }
  r.step('Start the app');
  await startApp(r, { locale: s.locale }, app);
}

/**
 * Android refuses to uninstall (or disable) an active device admin, and adb's shell can't remove
 * one. So the app is asked to drop it (`remove_admin`), and this waits until it has. False if it
 * still holds it after 15 s.
 */
export async function dropAdmin(p: PanelShell, sleep: (ms: number) => Promise<void>, app: AppId): Promise<boolean> {
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
  let stream: Awaited<ReturnType<NonNullable<PanelShell['openStream']>>>;
  try { stream = await p.openStream(IMPROV_SOCKET); } catch { return false; } // the socket refused: not joined, as below
  const c = new ImprovSerialClient(stream, { deviceLabel: 'device' });
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

/**
 * The app's secret hand-over (core SecretHandoverServer.SOCKET): adb only, never the LAN. It carried the ESPHome API key
 * over a reflash first (#574), and keeps that name; since #584 it carries the Home Assistant sign-in too.
 */
export const API_KEY_SOCKET = 'localabstract:glassworks_api_key';

/** One request line, one reply line, over a fresh connection to the hand-over socket. */
async function keyRequest(p: PanelShell, line: string, timeoutMs = 10_000): Promise<string> {
  const s = await p.openStream!(API_KEY_SOCKET);
  try {
    return await new Promise<string>((resolve, reject) => {
      let got = '';
      const t = setTimeout(() => reject(new Error('the app didn\'t answer on its hand-over socket')), timeoutMs);
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

/** Whether the app listens on its hand-over socket now (an app older than #574 never does). */
export async function apiKeySocketUp(p: PanelShell): Promise<boolean> {
  return Number.parseInt((await p.run(`grep -c ' @glassworks_api_key$' /proc/net/unix`)).out.trim(), 10) > 0;
}

/** What the running app can hand over. */
export interface Carries { apiKey: boolean; signIn: boolean }

/**
 * Asks the running app what it can hand over (`carries`, which answers with no secret). An app from #574, before
 * #584, refuses the request but carries the API key; an older one has no socket and carries nothing.
 */
export async function handoverCarries(p: PanelShell, replyMs = 10_000): Promise<Carries> {
  const none = { apiKey: false, signIn: false };
  if (!p.openStream || !(await apiKeySocketUp(p))) return none;
  try {
    const r = await keyRequest(p, 'carries', replyMs);
    if (r === 'refused unknown request') return { apiKey: true, signIn: false };
    const words = r.startsWith('carries ') ? r.split(' ').slice(1) : [];
    return { apiKey: words.includes('api_key'), signIn: words.includes('signin') };
  } catch {
    return none;
  }
}

/**
 * One secret from the running app, as base64 text: `request` asks, the reply is `<word> <base64>`. Undefined when
 * there is nothing to carry; throws when the app holds one it can't read now. The reply's first word only goes into
 * an error: never anything that could hold the secret.
 */
async function readSecret(p: PanelShell, request: string, word: string, pattern: RegExp, what: string, replyMs: number,
  o: { socket?: 'required' } = {}): Promise<string | undefined> {
  if (!p.openStream || !(await apiKeySocketUp(p))) {
    // a caller that saw the socket listen: one gone now is an app that restarted or stopped, not an app with no secret
    if (o.socket === 'required') throw new Error(`the app's hand-over socket closed before Kiln could read ${what === 'an API key' ? 'the key' : 'the sign-in'}`);
    return undefined;
  }
  const r = await keyRequest(p, request, replyMs);
  if (r === 'none') return undefined;
  const m = new RegExp(`^${word} (${pattern.source})$`).exec(r);
  if (m) return m[1];
  throw new Error(r === 'unreadable' ? `the app has ${what} but can't read it right now (its key store is down)`
    : `the app's hand-over socket gave an unexpected answer (${r.split(' ')[0] || 'empty'})`);
}

/**
 * The ESPHome API key Home Assistant provisioned, as base64 text, read from the running app before a reflash wipes
 * it. Undefined when there is nothing to carry: no app of ours listens (none, or one older than #574), or the app has
 * no key. Throws when the app has a key but it can't be read now (its key store is down, or the socket didn't answer):
 * a flash then would lose it. It is a secret: never put it in a log line, an error or a command line.
 */
export async function readApiKey(p: PanelShell, replyMs = 10_000, o: { socket?: 'required' } = {}): Promise<string | undefined> {
  return readSecret(p, 'get', 'key', /[A-Za-z0-9+/]{43}=/, 'an API key', replyMs, o);
}

/**
 * The Home Assistant sign-in (its refresh token, as base64 text), read from the running app before a signing-key
 * switch uninstalls it (#584). Only from an app whose `carries` lists `signin`: an older one answers `get-signin` with
 * a refusal, which throws here. Undefined when the app isn't signed in; throws when it can't read its sign-in now. It
 * is a secret: never put it in a log line, an error or a command line.
 */
export async function readSignIn(p: PanelShell, replyMs = 10_000): Promise<string | undefined> {
  return readSecret(p, 'get-signin', 'signin', /[A-Za-z0-9+/]+={0,2}/, 'a sign-in', replyMs);
}

/**
 * Refusals that may clear within the hand-back's wait: the key store was down, or the write failed. The others (a
 * different value is set, no sign-in to carry it into, not a key) are final.
 */
const PASSING = /^refused (the key store isn't available right now|the .+ can't be stored right now)$/;

/**
 * Sends `line` until the app answers `ok` (true), refuses for good (false), or `waitMs` has passed (false). A
 * refusal that may pass is tried again until then. Never throws.
 */
async function handOver(p: PanelShell, line: string, sleep: (ms: number) => Promise<void>, waitMs: number, replyMs: number): Promise<boolean> {
  if (!p.openStream) return false;
  const until = Date.now() + waitMs;
  for (;;) {
    try {
      if (await apiKeySocketUp(p)) {
        const r = await keyRequest(p, line, replyMs);
        if (r === 'ok') return true;
        if (r.startsWith('refused ') && !PASSING.test(r)) return false; // a different value, or no sign-in: final
      }
    } catch { /* the socket failed or didn't answer: try again */ }
    if (Date.now() > until) return false;
    await sleep(1500);
  }
}

/**
 * Hands `key` (from readApiKey) to the freshly flashed or switched app, once its socket listens (it starts with the
 * app). True when the app took it. A connection that fails or doesn't answer is tried again until `waitMs` has passed:
 * the app may have stored the key before its reply was lost, and then a retry gets `ok` for the same key. False when
 * it never took it. The key goes only into the socket, never a command line or a log.
 */
export async function handOverApiKey(p: PanelShell, key: string, sleep: (ms: number) => Promise<void>, waitMs = 90_000, replyMs = 10_000): Promise<boolean> {
  return handOver(p, `set ${key}`, sleep, waitMs, replyMs);
}

/**
 * Hands the sign-in (from readSignIn) to the switched app, as handOverApiKey does the key. The app takes it only into
 * the sign-in its copied data holds, and shows the dashboard. The token goes only into the socket.
 */
export async function handOverSignIn(p: PanelShell, signIn: string, sleep: (ms: number) => Promise<void>, waitMs = 90_000, replyMs = 10_000): Promise<boolean> {
  return handOver(p, `set-signin ${signIn}`, sleep, waitMs, replyMs);
}

/**
 * Tells the app the installer has handed over all it will (#608): it stops keeping the secrets its old Keystore key
 * sealed locked for the hand-over, and drops the ones nobody handed over. One try, never throws: without it the wait
 * ends by itself after 15 minutes. False when the app didn't say ok (an app from before this, or no answer).
 */
export async function handOverDone(p: PanelShell, replyMs = 10_000): Promise<boolean> {
  try {
    return !!p.openStream && (await apiKeySocketUp(p)) && (await keyRequest(p, 'done', replyMs)) === 'ok';
  } catch {
    return false;
  }
}

/**
 * A secret's fingerprint as the app logs it (NoiseKeys.fingerprint): the first 2 bytes of the SHA-256 of its bytes
 * (`b64` decoded), in hex. Reveals nothing. For the API key and the sign-in alike.
 */
export async function apiKeyFingerprint(b64: string): Promise<string> {
  const raw = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', raw)).slice(0, 2)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
