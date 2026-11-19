// Moving a panel from one signing key to another (in practice: a debug build to a release build)
// without it losing its Wi-Fi, and keeping Glassworks' data where the installer has root. Same steps
// as frames/aqara-s1-plus/tools/install.sh's switch; the panel side is frames/aqara-s1-plus/tools/install/glassworks-switch.sh.
//
// Why this is needed: Android won't update an app across signing keys, so the only way is uninstall
// + install. Uninstalling deletes the app's data, and on Android 10 also every Wi-Fi network the app
// saved (WifiConfiguration.creatorUid). Glassworks saves the panel's Wi-Fi itself (Improv), so a plain
// uninstall takes the panel off the network.
//
// Root: adb's shell has none on a normal install (the boot image's root helpers are init services
// that take no commands). Only a development boot image (patch-boot-magisk.sh DEV_SU_HOURS) or a
// hand-made Magisk policy gives it `su`. Then the app's data is carried across and the Wi-Fi password
// is read from Android's store into this page's memory and sent to the new app over Improv. Without
// root the app starts fresh and the Wi-Fi step asks for the password again.
//
// Sealed secrets (#584): the app seals its Home Assistant sign-in and ESPHome API key with a key in the Android
// Keystore, and Android deletes that key with the old app. The copied data still holds them, but nothing can open
// them. So, with root, the installer reads both from the running app (its adb-only hand-over socket, the one #574
// made for the Mirror's reflash) into this page's memory, and hands them to the new app once it runs.
//
// The Wi-Fi password, the sign-in and the key never go into a command line, a log line or an error message.
import type { PanelShell } from '@glassworks/adb';
import {
  ACT, ADMIN, PKG, type Carries, apiKeyFingerprint, dropAdmin, handOverApiKey, handOverSignIn, apiKeySocketUp, handoverCarries, readApiKey, readSignIn,
} from '@glassworks/app-adb';
import { IMPROV_SOCKET, ImprovSerialClient } from './improv';
import switchScript from '../../tools/install/glassworks-switch.sh?raw';

export const SWITCH_SCRIPT: string = switchScript;
export const SWITCH_SH = '/data/local/tmp/glassworks-switch.sh';
/** Where glassworks-switch.sh keeps the app's data between backup and restore. */
const SWITCH_DATA = '/data/s1kiosk/switch';
export const OLD_APK = '/data/local/tmp/glassworks-old.apk';
const NEW_APK = '/data/local/tmp/glassworks.apk';

/** Who saved the network the panel is on: Glassworks, someone else, can't tell, or no Wi-Fi. */
export type WifiOwner = 'kiosk' | 'other' | 'unknown' | 'none';
export interface PanelWifi { owner: WifiOwner; ssid?: string }

/** Android refused `pm install -r` because the APK's signing key differs from the installed app's. */
export function isKeyMismatch(pmOut: string): boolean {
  return /INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i.test(pmOut);
}

/**
 * Reads `dumpsys wifi` (Android 10): the current network id from `mWifiInfo … Net ID: n`, then that
 * network's entry in WifiConfigManager's list (`ID: n SSID: "x" …`, later ` cname=<package>`).
 * The dump never contains passwords (WifiConfiguration prints the PSK as `*`).
 */
export function parseWifiOwner(dump: string, pkg = PKG): PanelWifi {
  // a dump can print more than one mWifiInfo (an older, disconnected one first): the connected one counts
  const infos = dump.match(/mWifiInfo[^\n]*/g) ?? [];
  const info = infos.find((l) => /Supplicant state: COMPLETED\b/.test(l)) ?? infos[0];
  const id = info ? /Net ID: (-?\d+)/.exec(info)?.[1] : undefined;
  if (id === undefined) return { owner: 'unknown' };
  if (Number(id) < 0) return { owner: 'none' };
  const lines = dump.split(/\r?\n/);
  const head = /^\s*[*-]?\s*ID: (-?\d+) SSID: (?:"(.*)"|(\S+)) PROVIDER-NAME:/;
  for (let i = 0; i < lines.length; i++) {
    const m = head.exec(lines[i]!);
    if (!m || m[1] !== id) continue;
    const ssid = m[2] ?? m[3];
    for (let j = i + 1; j < lines.length && !head.test(lines[j]!); j++) {
      const c = /\bcname=(\S+)/.exec(lines[j]!);
      if (c) return { owner: c[1] === pkg ? 'kiosk' : 'other', ssid };
      if (/Configured networks End/.test(lines[j]!)) break;
    }
    return { owner: 'unknown', ssid };
  }
  return { owner: 'unknown' };
}

/** XML text as Android's XmlSerializer writes it, decoded; undefined for anything malformed. */
export function xmlUnescape(s: string): string | undefined {
  let bad = false;
  const out = s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);?/gi, (all, e: string) => {
    if (!all.endsWith(';')) { bad = true; return all; }
    const named: Record<string, string> = { quot: '"', amp: '&', lt: '<', gt: '>', apos: "'" };
    if (e[0] === '#') {
      const n = e[1] === 'x' || e[1] === 'X' ? Number.parseInt(e.slice(2), 16) : Number.parseInt(e.slice(1), 10);
      if (!Number.isFinite(n) || n > 0x10ffff) { bad = true; return all; }
      return String.fromCodePoint(n);
    }
    if (!(e in named)) { bad = true; return all; }
    return named[e]!;
  });
  return bad ? undefined : out;
}

/** A stored SSID/passphrase is `"text"`; anything else (hex SSID, raw 64-hex PSK) is returned as is. */
export function unquote(v: string): string {
  return v.length >= 2 && v.startsWith('"') && v.endsWith('"') ? v.slice(1, -1) : v;
}

export interface SavedNetwork { n: number; kind: 'psk' | 'open' | 'wep' | 'other'; ssid?: string }

/** `glassworks-switch.sh wifi-list` output. */
export function parseWifiList(out: string): SavedNetwork[] {
  const nets: SavedNetwork[] = [];
  for (const line of out.split(/\r?\n/)) {
    const m = /^(\d+) (psk|open|wep|other) (.*)$/.exec(line.trim());
    if (!m) continue;
    const raw = xmlUnescape(m[3]!);
    nets.push({ n: Number(m[1]), kind: m[2] as SavedNetwork['kind'], ssid: raw === undefined ? undefined : unquote(raw) });
  }
  return nets;
}

/** Wi-Fi details held in memory between reading them (root) and sending them to the new app. */
export class WifiSecret {
  #psk: string;
  constructor(readonly ssid: string, psk: string) { this.#psk = psk; }
  get psk(): string { return this.#psk; }
  forget() { this.#psk = ''; }
  toString() { return `WifiSecret(${this.ssid})`; }
  toJSON() { return { ssid: this.ssid }; }
}

/**
 * The sign-in and the API key, read from the running app before the switch (#584) and held in memory until the new
 * app has them. Nothing prints them: toString and toJSON say only which there are.
 */
export class CarriedSecrets {
  #apiKey?: string;
  #signIn?: string;
  constructor(apiKey?: string, signIn?: string) { this.#apiKey = apiKey; this.#signIn = signIn; }
  /** Base64 of the API key, as the app's hand-over socket gives it. */
  get apiKey(): string | undefined { return this.#apiKey; }
  /** Base64 of the refresh token, as the app's hand-over socket gives it. */
  get signIn(): string | undefined { return this.#signIn; }
  forget() { this.#apiKey = undefined; this.#signIn = undefined; }
  toString() { return `CarriedSecrets(${[this.#signIn && 'sign-in', this.#apiKey && 'API key'].filter(Boolean).join(', ') || 'none'})`; }
  toJSON() { return { apiKey: this.#apiKey !== undefined, signIn: this.#signIn !== undefined }; }
}

/**
 * What happens to one sealed secret. carry: read from the old app, to hand to the new one. kept: the new app has it.
 * lost: it doesn't, so it is set again (signed in again, or confirmed in Home Assistant). none: there was none.
 */
export type Carried = 'carry' | 'kept' | 'lost' | 'none';

export interface SwitchPlan {
  /** versionName of the installed app, if dumpsys shows it. */
  installedVersion?: string;
  /** The installed app is debuggable (a debug build). */
  debugBuild: boolean;
  /**
   * adb's shell has root (`su`): the app's data is kept and the Wi-Fi password re-sent. Secrets sealed with the
   * Android Keystore are not copied: uninstalling deletes the app's keys (#584). See `carries`.
   */
  root: boolean;
  /**
   * With root: what the running app can hand over across the switch (its `carries`). Without root nothing is carried:
   * the data, which holds the rest of the sign-in, isn't kept.
   */
  carries: Carries;
  wifi: PanelWifi;
}

export interface SwitchOutcome {
  /** The app's settings came across. Its Keystore-sealed secrets don't by themselves (#584): see `secrets`. */
  dataKept: boolean;
  /** The HA sign-in and the API key: `carry` for one read from the old app and in `secrets`, for provision to hand over. */
  signIn: Carried;
  apiKey: Carried;
  /** What was read from the old app, only while the new app still needs it (dataKept). */
  secrets?: CarriedSecrets;
  /**
   * kept: the network was not Glassworks', so Android keeps it. resend: read as root, to be sent to
   * the new app (provision does). lost: Android forgot it; the Wi-Fi step must ask for it.
   */
  wifi: 'kept' | 'resend' | 'lost';
  ssid?: string;
  /** Present only for wifi = resend. */
  secret?: WifiSecret;
  notes: string[];
}

/** Wi-Fi the panel is on, and who saved it. Falls back to wlan0's address when dumpsys is unreadable. */
export async function panelWifi(p: PanelShell, signal?: AbortSignal): Promise<PanelWifi> {
  const w = parseWifiOwner((await p.run('dumpsys wifi', signal)).out);
  if (w.owner !== 'unknown') return w;
  const ip = /inet \d+\.\d+\.\d+\.\d+/.test((await p.run('ip -4 -o addr show wlan0 2>/dev/null', signal)).out);
  return ip ? w : { owner: 'none' };
}

/** adb's shell can become root (a development boot image or a Magisk policy for uid 2000). */
export async function hasRoot(p: PanelShell): Promise<boolean> {
  const r = await p.run(`timeout 15 su -c 'id -u' 2>/dev/null`);
  return r.code === 0 && r.out.trim().split(/\r?\n/).pop()?.trim() === '0';
}

/** Read-only look at the panel before asking the user. Changes nothing. */
export async function planSwitch(p: PanelShell): Promise<SwitchPlan> {
  const dump = (await p.run(`dumpsys package ${PKG}`)).out;
  const installedVersion = /versionName=(\S+)/.exec(dump)?.[1];
  const debugBuild = /pkgFlags=\[[^\]]*\bDEBUGGABLE\b/.test(dump);
  const root = await hasRoot(p);
  const carries = root ? await handoverCarries(p) : { apiKey: false, signIn: false };
  return { installedVersion, debugBuild, root, carries, wifi: await panelWifi(p) };
}

const noun = { signIn: 'Home Assistant sign-in', apiKey: 'encryption key' } as const;
const both = (xs: string[]) => xs.join(' and ');

/** What the confirm says about the sealed secrets on a panel with root. */
function describeSecrets(c: Carries): string {
  const kept = (['signIn', 'apiKey'] as const).filter((k) => c[k]);
  const lost = (['signIn', 'apiKey'] as const).filter((k) => !c[k]);
  if (!kept.length) {
    return 'This panel gives the installer root, so the app\'s settings are kept. Its Home Assistant sign-in and encryption key are not: Android deletes the app\'s Keystore keys when it removes the old app, and those secrets are sealed with them. Sign in again afterwards; Home Assistant may ask to confirm the panel\'s encryption key.';
  }
  const many = (xs: readonly unknown[], one: string, more: string) => (xs.length > 1 ? more : one);
  let t = `This panel gives the installer root, so the app's settings are kept, and so ${many(kept, 'is', 'are')} its ${both(kept.map((k) => noun[k]))}: the installer reads ${many(kept, 'it', 'them')} from the running app and hands ${many(kept, 'it', 'them')} to the new one.`;
  if (lost.length) {
    t += ` Its ${both(lost.map((k) => noun[k]))} ${many(lost, 'is', 'are')} not: this build of the app can't hand ${many(lost, 'it', 'them')} over, and Android deletes the app's Keystore keys, which seal ${many(lost, 'it', 'them')}, when it removes the old app.`;
    if (lost.includes('signIn')) t += ' Sign in again afterwards.';
    if (lost.includes('apiKey')) t += ' Home Assistant may ask to confirm the panel\'s encryption key.';
  }
  return `${t} The camera's stream password and the Music Assistant pairing are not carried${lost.length ? ' either' : ''}: set and pair them again if you use them.`;
}

/** Wi-Fi is at risk when the network is Glassworks', or when we can't tell whose it is. */
export const wifiAtRisk = (w: PanelWifi) => w.owner === 'kiosk' || w.owner === 'unknown';

/** Plain-language summary for the confirmation (no secrets in it: only the network's name). */
export function describeSwitch(plan: SwitchPlan): string {
  const what = plan.debugBuild
    ? `Glassworks on the panel is a development build${plan.installedVersion ? ` (${plan.installedVersion})` : ''}.`
    : 'Glassworks on the panel is signed with a different key than this version.';
  const lines = [what, 'Android can\'t update it in place: it has to be removed and installed again.', ''];
  const net = plan.wifi.ssid ? `"${plan.wifi.ssid}"` : 'its Wi-Fi network';
  if (plan.root) {
    lines.push(describeSecrets(plan.carries));
    if (wifiAtRisk(plan.wifi)) lines.push(`${plan.wifi.owner === 'kiosk' ? 'Android would forget' : 'Android may forget'} ${net} with the old app; the installer sends it to the new app, so the panel stays online.`);
  } else {
    lines.push('The installer has no root on this panel, so the app starts fresh: its settings, Home Assistant sign-in and pairing key are reset (Home Assistant may ask to set the panel up again).');
    if (wifiAtRisk(plan.wifi)) {
      lines.push(`${plan.wifi.owner === 'kiosk' ? `Android also forgets ${net}, which Glassworks saved.` : `The installer can't tell who saved ${net}, so the panel may lose its Wi-Fi.`} The next step asks for its password again; the cable keeps the panel reachable meanwhile.`);
    }
  }
  if (!wifiAtRisk(plan.wifi) && plan.wifi.owner === 'other') lines.push(`The panel's Wi-Fi ${net} wasn't saved by Glassworks and stays.`);
  lines.push('', 'If installing the new version fails, the old one is put back.');
  return lines.join('\n');
}

/**
 * One line for the end of a switch (the installer's Done row). With root the settings come across, and the sign-in
 * when the installer could carry it (#584); otherwise it says to sign in again.
 */
export function describeSwitched(sw: { dataKept: boolean; signIn: Carried; wifi: 'kept' | 'resent' | 'lost' }): string {
  const settings = !sw.dataKept ? 'Settings reset'
    : sw.signIn === 'kept' ? 'Settings and sign-in kept'
    : sw.signIn === 'none' ? 'Settings kept'
    : 'Settings kept, sign in again';
  return `${settings} · ${sw.wifi === 'lost' ? 'Wi-Fi needs its password again' : 'Wi-Fi kept'}`;
}

/**
 * Reads the sign-in and the API key from the running app into memory (only what `carries` says it can hand over).
 * A secret it holds but can't read now is lost to the switch: the run goes on and says so, as for the Wi-Fi. Only the
 * app's own `none` means there is none: a socket that is down (the app restarting) is waited for up to `waitMs`, then
 * counts as lost.
 */
export async function readSecrets(p: PanelShell, carries: Carries, log: SwitchLog,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)), waitMs = 15_000): Promise<{ signIn: Carried; apiKey: Carried; secrets: CarriedSecrets }> {
  const up = async () => {
    for (let tries = Math.max(1, Math.ceil(waitMs / 1500)); ; tries--) {
      if (await apiKeySocketUp(p)) return true;
      if (tries <= 1) return false;
      await sleep(1500);
    }
  };
  const read = async (k: 'signIn' | 'apiKey', get: () => Promise<string | undefined>, again: string): Promise<[Carried, string | undefined]> => {
    const name = noun[k][0]!.toUpperCase() + noun[k].slice(1);
    if (!carries[k]) { log(`${name}: this build of the app can't hand it over; ${again}`); return ['lost', undefined]; }
    if (!(await up())) { log(`${name}: the app isn't answering on its hand-over socket; ${again}`); return ['lost', undefined]; }
    try {
      const v = await get();
      log(v === undefined ? `${name}: none to carry` : `${name}: read from the running app (fingerprint ${await apiKeyFingerprint(v)}), kept in memory for the new app`);
      return v === undefined ? ['none', undefined] : ['carry', v];
    } catch (e) {
      log(`${name}: could not read it (${(e as Error).message}); ${again}`);
      return ['lost', undefined];
    }
  };
  const [signIn, token] = await read('signIn', () => readSignIn(p), 'sign in again afterwards');
  const [apiKey, key] = await read('apiKey', () => readApiKey(p), 'Home Assistant may ask to confirm it');
  return { signIn, apiKey, secrets: new CarriedSecrets(key, token) };
}

/**
 * Hands what readSecrets read to the app installed now, once it runs and listens, then forgets it. `carry` becomes
 * kept or lost; the rest stays as it was.
 */
export async function handBackSecrets(p: PanelShell, sw: { signIn: Carried; apiKey: Carried; secrets?: CarriedSecrets }, log: SwitchLog,
  sleep: (ms: number) => Promise<void>, waitMs = 90_000): Promise<{ signIn: Carried; apiKey: Carried }> {
  const s = sw.secrets;
  const out = { signIn: sw.signIn, apiKey: sw.apiKey };
  try {
    if (out.apiKey === 'carry') {
      out.apiKey = s?.apiKey !== undefined && await handOverApiKey(p, s.apiKey, sleep, waitMs) ? 'kept' : 'lost';
      log(out.apiKey === 'kept' ? 'Encryption key: handed to the app' : 'Encryption key: the app did not take it; Home Assistant may ask to confirm it');
    }
    if (out.signIn === 'carry') {
      out.signIn = s?.signIn !== undefined && await handOverSignIn(p, s.signIn, sleep, waitMs) ? 'kept' : 'lost';
      log(out.signIn === 'kept' ? 'Home Assistant sign-in: handed to the app' : 'Home Assistant sign-in: the app did not take it; sign in again');
    }
  } finally {
    s?.forget();
  }
  return out;
}

/** The sealed secrets a switch lost, for a message ("its Home Assistant sign-in and encryption key"), or undefined. */
export function lostSecrets(r: { signIn: Carried; apiKey: Carried }): string | undefined {
  const lost = (['signIn', 'apiKey'] as const).filter((k) => r[k] === 'lost' || r[k] === 'carry');
  return lost.length ? `its ${both(lost.map((k) => noun[k]))}` : undefined;
}

export type SwitchLog = (detail: string) => void;

const lastLine = (s: string) => s.trim().split(/\r?\n/).pop() || 'no detail';

/**
 * The switch itself: uninstall the installed app, install `apk`, with the data carried over when
 * there is root. Throws (with the old app put back where possible) if the new one won't install.
 * Permissions, HOME etc. are not done here: provision() continues with them as on any install.
 */
export async function switchSigningKey(p: PanelShell, apk: Uint8Array, plan: SwitchPlan, log: SwitchLog,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<SwitchOutcome> {
  const out: SwitchOutcome = { dataKept: false, signIn: 'lost', apiKey: 'lost', wifi: wifiAtRisk(plan.wifi) ? 'lost' : 'kept', ssid: plan.wifi.ssid, notes: [] };
  const su = (cmd: string) => p.run(`su -c 'sh ${SWITCH_SH} ${cmd}'`);
  const cleanup = async () => { await p.run(`rm -f ${OLD_APK} ${NEW_APK} ${SWITCH_SH}`); };

  // 1. The installed APK, for putting it back if the new one won't install.
  const path = /^package:(\S+)/m.exec((await p.run(`pm path ${PKG}`)).out)?.[1];
  if (!path || (await p.run(`cp ${path} ${OLD_APK}`)).code !== 0) {
    throw new Error('could not keep a copy of the installed Glassworks to fall back on. Nothing was changed.');
  }
  let root = plan.root;
  if (root) {
    await p.push(SWITCH_SH, new TextEncoder().encode(SWITCH_SCRIPT), 0o755);
    // 2. Wi-Fi, from Android's store into memory. Only this network's password, nothing logged.
    if (wifiAtRisk(plan.wifi)) {
      const nets = parseWifiList((await su('wifi-list')).out);
      const net = nets.find((x) => plan.wifi.ssid !== undefined && x.ssid === plan.wifi.ssid) ?? (nets.length === 1 ? nets[0] : undefined);
      if (net && (net.kind === 'psk' || net.kind === 'open') && net.ssid !== undefined) {
        let psk: string | undefined = '';
        if (net.kind === 'psk') {
          const r = await su(`wifi-psk ${net.n}`);
          psk = r.code === 0 ? xmlUnescape(r.out.replace(/\r?\n$/, '').replace(/\r$/, '')) : undefined;
          if (psk !== undefined) psk = unquote(psk);
        }
        if (psk !== undefined) { out.secret = new WifiSecret(net.ssid, psk); out.ssid = net.ssid; out.wifi = 'resend'; }
      }
      log(out.wifi === 'resend' ? `Wi-Fi ${out.ssid}: read as root; sent to the new app after installing` : 'Wi-Fi: could not read it as root; the Wi-Fi step will ask for it');
    }
  }
  const dropSecrets = () => { out.secrets?.forget(); out.secrets = undefined; };

  // 3. Device admin: Android won't uninstall an active admin. A device owner is released first.
  //    The app has to be running for these, so they come before it is stopped.
  const policy = (await p.run('dumpsys device_policy')).out;
  const owner = /Device Owner[\s\S]{0,200}?sh\.edm\.s1kiosk/.test(policy);
  if (owner) {
    await p.run(`am start -n ${PKG}/.improv.ImprovActivity --ez release_device_owner true`);
    await sleep(2000);
  }
  if (!(await dropAdmin(p, sleep))) log('device admin still active: removing the app may fail');
  const undo = async () => {
    await p.run(`pm enable ${PKG}`);
    await p.run(`dpm set-active-admin ${ADMIN}`);
    if (owner) await p.run(`dpm set-device-owner ${ADMIN}`);
  };

  // 3b. The sign-in and the API key, from the running app into memory (#584), as late as can be: right before it is
  //    stopped, so a sign-in finished meanwhile is the one read, and the one the backup copies.
  if (root) Object.assign(out, await readSecrets(p, plan.carries, log, sleep));

  // 4. Stop it for good: as the HOME app a force-stopped kiosk is started again at once, and its
  //    data must not change while it is copied. Disabling keeps its Wi-Fi networks (uninstalling
  //    is what removes them).
  await p.run(`pm disable-user --user 0 ${PKG}`);
  if (root) {
    const b = await su('backup');
    if (b.code !== 0) {
      dropSecrets();
      await su('discard');
      await undo();
      await cleanup();
      throw new Error(`could not keep Glassworks' data (${lastLine(b.out)}). Nothing was changed.`);
    }
    log(`app data kept: ${lastLine(b.out)}`);
  }

  // 5. Remove the old build.
  const un = (await p.run(`pm uninstall ${PKG}`)).out;
  if (!/Success/.test(un)) {
    dropSecrets();
    await undo();
    if (root) await su('discard');
    await cleanup();
    throw new Error(`Android would not remove the installed Glassworks (${un.trim()}). It is unchanged.`);
  }
  log('removed the old build');

  // 6. Install the new one; on failure put the old one back (with its data, given root).
  //    Its data goes in while it is disabled (it would start as HOME at once otherwise).
  const restoreInto = async (): Promise<{ out: string; code: number }> => {
    await p.run(`pm disable-user --user 0 ${PKG}`);
    try { return await su('restore'); } finally { await p.run(`pm enable ${PKG}`); }
  };
  await p.push(NEW_APK, apk, 0o644);
  const inst = (await p.run(`pm install -r -g ${NEW_APK}`)).out;
  if (!/Success/.test(inst)) {
    const back = /Success/.test((await p.run(`pm install -r -g ${OLD_APK}`)).out);
    const restored = back && root ? await restoreInto() : undefined;
    if (back) await undo();
    // The copy is the only one left until a restore worked: keep it otherwise.
    if (restored?.code === 0) await su('discard');
    await cleanup();
    out.secret?.forget();
    // The old build lost its Keystore key too: hand its sign-in and key back, as to a new one.
    let lost = '';
    if (restored?.code === 0) {
      await p.run(`am start -n ${ACT}`);
      const r = await handBackSecrets(p, out, log, sleep);
      const what = lostSecrets(r);
      if (what) lost = `, but not ${what}${r.signIn === 'lost' ? ': sign in again' : ''}`;
    }
    dropSecrets();
    const why = `the new Glassworks would not install (${inst.trim()})`;
    const kept = ` Its data is kept on the panel in ${SWITCH_DATA} (root only).`;
    if (!back) throw new Error(`${why}, and neither would the previous one. Run the installer again.${root ? kept : ''}`);
    if (!restored) throw new Error(`${why}. The previous build is back.`);
    if (restored.code === 0) throw new Error(`${why}. The previous build is back with its data${lost}.`);
    throw new Error(`${why}. The previous build is back, but its data is not (${lastLine(restored.out)}).${kept}`);
  }
  log('installed the new build');

  // 7. The data back in, owned by the new uid, labels restored.
  if (root) {
    const r = await restoreInto();
    if (r.code === 0) { out.dataKept = true; await su('discard'); log('app data restored'); }
    else {
      root = false;
      dropSecrets();
      out.signIn = 'lost';
      out.apiKey = 'lost';
      out.notes.push(`could not restore the app's data (${lastLine(r.out)}); it starts fresh. The copy stays on the panel in ${SWITCH_DATA} (root only).`);
    }
  }
  try {
    if (owner) {
      const d = await p.run(`dpm set-device-owner ${ADMIN}`);
      if (d.code !== 0) out.notes.push('Glassworks was device owner and could not be made device owner again; it works without (see ImprovActivity).');
    }
    await cleanup();
  } catch (e) {
    // The old app is gone and its copy discarded: the sign-in and key live only in `out` now, and provision never
    // sees it when this throws. Give them to the new app before the error goes up (#608 review).
    if (out.signIn === 'carry' || out.apiKey === 'carry') {
      await p.run(`am start -n ${ACT}`).catch(() => {});
      await handBackSecrets(p, out, log, sleep).catch(() => {});
    }
    dropSecrets();
    throw e;
  }
  return out;
}

/** Sends the remembered Wi-Fi to the (new) app over Improv through adb, then forgets it. */
export async function resendWifi(p: PanelShell, secret: WifiSecret, log: SwitchLog,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)), waitMs = 90_000): Promise<boolean> {
  try {
    if (!p.openStream) return false;
    const until = Date.now() + waitMs;
    while (Number.parseInt((await p.run(`grep -c ' @s1kiosk_improv$' /proc/net/unix`)).out.trim(), 10) <= 0) {
      if (Date.now() > until) { log('Glassworks did not start listening for Wi-Fi details'); return false; }
      await sleep(1500);
    }
    for (let i = 0; i < 3; i++) {
      let c: ImprovSerialClient | undefined;
      try {
        c = new ImprovSerialClient(await p.openStream(IMPROV_SOCKET));
        await c.requestState(5000);
        await c.provision(secret.ssid, secret.psk);
        log(`Wi-Fi ${secret.ssid}: sent to the new app, connected`);
        return true;
      } catch (e) {
        // The app can restart right after joining and cut the connection: ask again.
        try {
          const again = new ImprovSerialClient(await p.openStream(IMPROV_SOCKET));
          const s = await again.requestState(5000);
          await again.close();
          if (s === 0x04) { log(`Wi-Fi ${secret.ssid}: connected`); return true; }
        } catch { /* not up yet */ }
        log(`Wi-Fi ${secret.ssid}: try ${i + 1}: ${(e as Error).message}`);
        await sleep(3000);
      } finally {
        await c?.close().catch(() => {});
      }
    }
    return false;
  } finally {
    secret.forget();
  }
}
