// Glassworks on any Android device, over adb (#403). No root, no image, no backup of partitions: Kiln
// installs the app as a normal app and changes nothing else but what the person can change back in
// Settings (permissions, the home app). Undo removes the app.
//
// 1. Read the device (read-only). Refuse below Android 6.
// 2. Check the APK: Glassworks for API 26+, its legacy build for 23-25, with code for this processor.
// 3. Install it, or update it in place when the same app is there.
// 4. Grant what adb's shell can, and read each one back. A grant a vendor build refuses is reported, not fatal.
//    On Android 12+, unless the person says no, allow "Install unknown apps" too, so updates install with nobody
//    at the device (#422). Below 12 only a device owner skips the tap, which Kiln doesn't offer yet (#403).
// 5. Start it, then make it the home screen: `cmd package set-home-activity` on Android 7+, else Android's
//    chooser and one "Always" tap. Checked by package, as the Mirror's recipe does (#411).
// 6. Hand over to setup over the LAN (#34): the app's setup channel gives its address, Kiln links its page.
import type { PanelShell } from '@glassworks/adb';
import { Steps, apkInfo, apkPermissions, dropAdmin, kioskVersion, startApp, type ApkInfo, type StepFn } from '@glassworks/app-adb';
import { parsePreferredHome } from '@glassworks/lululemon-mirror-installer';
import { SETUP_SOCKET, SetupChannel, type SetupState } from '@glassworks/setup-channel';
import { APPS, MIN_API, androidName, appFor, type GenericApp } from './app';
import { identifyAdb, type Identified } from './devices';

/** What adb reads off the device before anything changes. */
export interface DeviceFacts {
  sdk: number;
  release: string;
  /** ro.product.cpu.abilist, preferred first */
  abis: string[];
  manufacturer: string;
  model: string;
  fingerprint: string;
}

export interface AppVersion { name?: string; code: number }

/** Refused before anything changed on the device. */
export class Refused extends Error {
  constructor(why: string) { super(`${why} Nothing was changed.`); }
}

/** What install will do, worked out read-only. */
export interface Plan {
  device: DeviceFacts;
  identified: Identified;
  app: GenericApp;
  apk: ApkInfo;
  /** the device ABI the APK's code runs as; undefined for an APK without native code */
  abi?: string;
  /** the same app, already there: install updates it in place */
  installed?: AppVersion;
}

/** One permission or power the app asks for, and whether adb could give it. */
export interface Grant {
  what: 'microphone' | 'camera' | 'secure-settings' | 'overlay' | 'device-admin';
  /** `granted`: read back as on; `not-asked`: this build doesn't ask for it; `refused`: the device said no */
  result: 'granted' | 'not-asked' | 'refused';
  /** for `refused`: what the device answered, one line */
  detail?: string;
}

export interface HomeResult {
  /** `set-home-activity`: adb set it (Android 7+); `chooser`: Android's chooser and the person's "Always" tap */
  how: 'set-home-activity' | 'chooser';
  state: 'set' | 'unconfirmed';
  /** what Android opens as the home screen now, when it says */
  home?: string;
  /** why set-home-activity didn't do it, when the chooser took over */
  note?: string;
}

/** The app's setup page on the LAN (#34), or why there is none. */
export interface Handoff { setupPage?: string; ip?: string; why?: string }

/**
 * Whether updates install with nobody at the device (#422), as Kiln left it. `line` is what the app's Device page
 * and Home Assistant's *Silent updates* say (core SilentUpdates), for Kiln's Done screen.
 */
export interface SilentUpdatesResult {
  /**
   * `allowed`: "Install unknown apps" read back as allowed (Android 12+); `declined`: the person kept a tap per
   * update; `refused`: the device said no; `old-android`: below Android 12; `not-asked`: this build doesn't ask
   */
  state: 'allowed' | 'declined' | 'refused' | 'old-android' | 'not-asked';
  line: string;
  /** for `refused`: what the device answered, one line */
  detail?: string;
}

export interface GenericInstalled { plan: Plan; app: AppVersion; updated: boolean; grants: Grant[]; silentUpdates: SilentUpdatesResult; home: HomeResult; handoff: Handoff }

/** The parts of install the screen follows, in order. `home` with `pick`: waiting for the person's tap. */
export type GenericPhase =
  | { step: 'read' }
  | { step: 'apk'; stage: 'copy' | 'install' }
  | { step: 'grant' }
  | { step: 'updates' }
  | { step: 'start' }
  | { step: 'home'; how: 'set-home-activity' | 'chooser'; pick?: string }
  | { step: 'handoff' };

export interface GenericOptions {
  apk: Uint8Array;
  haUrl?: string;
  /** e.g. en-US; the app applies it */
  locale?: string;
  onStep?: StepFn;
  onPhase?: (phase: GenericPhase) => void;
  sleep?: (ms: number) => Promise<void>;
  /** how long to wait for the "Always" tap in Android's chooser; default 3 minutes */
  homeTimeoutMs?: number;
  /** how many times to try the app's setup channel after it starts; default 10, a second apart */
  setupTries?: number;
  /** Android 12+: allow "Install unknown apps", so updates install by themselves (#422). Default on */
  silentUpdates?: boolean;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
const prop = async (p: PanelShell, k: string) => (await p.run(`getprop ${k}`)).out.trim();
const HOME_INTENT = '-a android.intent.action.MAIN -c android.intent.category.HOME';
/** The app's web port: its Manage and setup pages (#34). */
const WEB_PORT = 8099;

/** Read-only: what the device is. Writes nothing, so it is safe on any device. */
export async function readDevice(p: PanelShell): Promise<DeviceFacts> {
  return {
    sdk: Number(await prop(p, 'ro.build.version.sdk')) || 0,
    release: await prop(p, 'ro.build.version.release'),
    abis: (await prop(p, 'ro.product.cpu.abilist')).split(',').map((a) => a.trim()).filter(Boolean),
    manufacturer: await prop(p, 'ro.product.manufacturer'),
    model: await prop(p, 'ro.product.model'),
    fingerprint: await prop(p, 'ro.build.fingerprint'),
  };
}

/**
 * Read-only: the build this device needs, and whether `apk` is it. Throws Refused (nothing changed) below
 * Android 6, for an APK that isn't Glassworks, for the other build, or for an APK without code for this
 * processor.
 */
export async function planInstall(p: PanelShell, apk: Uint8Array): Promise<Plan> {
  const device = await readDevice(p);
  const app = appFor(device.sdk);
  if (!app) {
    throw new Refused(`Glassworks needs Android 6 (API ${MIN_API}) or newer, and this device runs Android ${device.release || androidName(device.sdk)} (API ${device.sdk || '?'}).`);
  }
  let info: ApkInfo;
  try {
    info = await apkInfo(apk);
  } catch (e) {
    throw new Refused(`${(e as Error).message}.`);
  }
  const which = APPS.find((a) => a.package === info.package);
  if (!which) throw new Refused(`This APK is ${info.package}, not Glassworks: pick ${app.name} (${app.package}).`);
  if (which !== app) {
    throw new Refused(`This APK is ${which.name}, for Android ${androidName(which.apis.min)}${which.apis.max ? ` to ${androidName(which.apis.max)}` : ' and newer'}. This device runs Android ${androidName(device.sdk)}: pick ${app.name} (${app.package}).`);
  }
  const min = info.minSdk ?? 1;
  if (typeof min !== 'number' || min > device.sdk) throw new Refused(`This APK needs Android API ${min}, and this device runs API ${device.sdk}.`);
  const abi = info.abis.length ? device.abis.find((a) => info.abis.includes(a)) : undefined;
  if (info.abis.length && device.abis.length && !abi) {
    throw new Refused(`This APK has code for ${info.abis.join(', ')}, and this device runs ${device.abis.join(', ')}: pick the APK for ${device.abis[0]}.`);
  }
  const installed = await kioskVersion(p, undefined, app);
  return { device, identified: identifyAdb(device), app, apk: info, ...(abi ? { abi } : {}), ...(installed ? { installed } : {}) };
}

/** pm's refusals, said plainly. The APK was only copied, so nothing on the device changed. */
function installError(out: string, app: GenericApp): Error {
  const why = /\[?(INSTALL_[A-Z_]+|Failure \[[^\]]+\])/.exec(out)?.[1] ?? (out.trim() || 'no answer');
  if (/UPDATE_INCOMPATIBLE|INCONSISTENT_CERTIFICATES/.test(out)) {
    return new Refused(`${app.name} on this device is signed with another key, so Android won't update it. Uninstall it first (its settings go with it), then install this one. (${why})`);
  }
  if (/VERSION_DOWNGRADE/.test(out)) return new Refused(`This device has a newer ${app.name} than this APK. (${why})`);
  if (/USER_RESTRICTED/.test(out)) return new Refused(`The device refused an install over USB. Some vendor builds have an "Install via USB" switch in Developer options: turn it on and try again. (${why})`);
  if (/INSUFFICIENT_STORAGE/.test(out)) return new Refused(`The device is out of space for ${app.name}. (${why})`);
  if (/NO_MATCHING_ABIS/.test(out)) return new Refused(`This APK has no code for this device's processor. (${why})`);
  return new Error(`installing ${app.name} failed: ${why}`);
}

const firstLine = (s: string) => s.trim().split(/\r?\n/).find((l) => l.trim())?.trim() ?? '';

/**
 * Each permission adb's shell can grant, if this build asks for it: microphone and camera (runtime
 * permissions), secure settings (screen on and off), the overlay (an app-op), and the device admin it
 * locks the screen with. Each is read back from the device: a vendor build may answer 0 and still not
 * grant it.
 */
async function grant(r: Steps, app: GenericApp, apk: Uint8Array, sdk: number): Promise<Grant[]> {
  const asks = new Set(await apkPermissions(apk, sdk));
  const pkg = app.package;
  const PERMS = [
    ['microphone', 'android.permission.RECORD_AUDIO'],
    ['camera', 'android.permission.CAMERA'],
    ['secure-settings', 'android.permission.WRITE_SECURE_SETTINGS'],
  ] as const;
  const answers = new Map<Grant['what'], string>();
  r.step('Permissions');
  for (const [what, perm] of PERMS) {
    if (!asks.has(perm)) continue;
    const res = await r.p.run(`pm grant ${pkg} ${perm}`);
    if (res.code !== 0 || /Exception|not allowed|Error/i.test(res.out)) answers.set(what, firstLine(res.out) || `exit ${res.code}`);
  }
  const overlay = asks.has('android.permission.SYSTEM_ALERT_WINDOW');
  if (overlay) {
    const res = await r.p.run(`appops set ${pkg} SYSTEM_ALERT_WINDOW allow`);
    if (res.code !== 0 || /Exception|Error/i.test(res.out)) answers.set('overlay', firstLine(res.out) || `exit ${res.code}`);
  }
  if (app.admin) {
    const res = await r.p.run(`dpm set-active-admin ${app.admin}`);
    if (res.code !== 0 || !/Success/.test(res.out)) answers.set('device-admin', firstLine(res.out) || `exit ${res.code}`);
  }

  // read back: what Android says now, whatever the commands answered
  const dump = await r.try(`dumpsys package ${pkg}`);
  const ops = overlay ? await r.try(`appops get ${pkg} SYSTEM_ALERT_WINDOW`) : '';
  const admins = app.admin ? await r.try('dumpsys device_policy') : '';
  const out: Grant[] = [];
  const result = (what: Grant['what'], on: boolean): Grant =>
    on ? { what, result: 'granted' } : { what, result: 'refused', detail: answers.get(what) ?? 'Android says it is still off' };
  for (const [what, perm] of PERMS) {
    if (!asks.has(perm)) { out.push({ what, result: 'not-asked' }); continue; }
    out.push(result(what, new RegExp(`${perm.replace(/\./g, '\\.')}: granted=true`).test(dump)));
  }
  out.push(overlay ? result('overlay', /SYSTEM_ALERT_WINDOW: allow/.test(ops)) : { what: 'overlay', result: 'not-asked' });
  out.push(app.admin ? result('device-admin', admins.includes(`${app.package}/`)) : { what: 'device-admin', result: 'not-asked' });
  for (const g of out) r.detail(`${g.result.padEnd(9)} ${g.what}${g.detail ? `: ${g.detail}` : ''}`);
  return out;
}

/** Android 12 (API 31): the first where an app can update itself without a tap (core Platform.canInstallWithoutUserAction). */
export const SILENT_UPDATES_API = 31;
const INSTALL_OP = 'REQUEST_INSTALL_PACKAGES';

/** The words core SilentUpdates uses for each answer Kiln can leave. */
export const SILENT_LINES = {
  allowed: 'Yes: Android 12 or newer lets the app update itself',
  off: 'No: Android needs “Install unknown apps” allowed for the app',
  old: (release: string) => `No: Android ${release} asks for a tap on each update`,
} as const;

/**
 * Silent updates (#422): on Android 12+, `appops set <app> REQUEST_INSTALL_PACKAGES allow`, read back. The app asks
 * Android for no user action on its own updates (core UpdateManager), and Android allows it only with this on.
 * The app is the only thing it lets install, and it installs only its own release, checked by hash and key.
 */
async function silentUpdates(r: Steps, app: GenericApp, apk: Uint8Array, device: DeviceFacts, want: boolean): Promise<SilentUpdatesResult> {
  if (device.sdk < SILENT_UPDATES_API) return { state: 'old-android', line: SILENT_LINES.old(device.release || androidName(device.sdk)) };
  const asks = new Set(await apkPermissions(apk, device.sdk));
  if (!asks.has('android.permission.REQUEST_INSTALL_PACKAGES') || !asks.has('android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION')) {
    return { state: 'not-asked', line: SILENT_LINES.off };
  }
  if (!want) { r.detail('silent updates: kept a tap per update'); return { state: 'declined', line: SILENT_LINES.off }; }
  r.step('Silent updates');
  const res = await r.p.run(`appops set ${app.package} ${INSTALL_OP} allow`);
  const now = await r.try(`appops get ${app.package} ${INSTALL_OP}`);
  if (new RegExp(`${INSTALL_OP}: allow`).test(now)) { r.detail('Install unknown apps: allowed'); return { state: 'allowed', line: SILENT_LINES.allowed }; }
  const detail = (res.code !== 0 || /Exception|Error/i.test(res.out) ? firstLine(res.out) : '') || `exit ${res.code}, and Android says it is still off`;
  r.detail(`Install unknown apps: refused: ${detail}`);
  return { state: 'refused', line: SILENT_LINES.off, detail };
}

/**
 * Undo for silent updates alone (#422's Kiln screen E): "Install unknown apps" back to Android's default, read back.
 * Removing the app (undoGeneric) takes it away too. Throws if Android still allows it.
 */
export async function undoSilentUpdates(p: PanelShell, o: { onStep?: StepFn } = {}): Promise<{ app?: GenericApp }> {
  const r = new Steps(p, o.onStep);
  for (const app of APPS) {
    if (!(await kioskVersion(p, undefined, app))) continue;
    r.step('Updates ask for a tap again');
    await r.p.run(`appops set ${app.package} ${INSTALL_OP} default`);
    const now = await r.try(`appops get ${app.package} ${INSTALL_OP}`);
    if (new RegExp(`${INSTALL_OP}: allow`).test(now)) throw new Error(`Android still lets ${app.name} install apps. Turn it off in Settings → Apps → ${app.name} → Install unknown apps.`);
    return { app };
  }
  r.step('Glassworks is not on this device');
  return {};
}

/**
 * The home screen Android resolves HOME to now: `cmd package resolve-activity` (Android 7+), else the "Always"
 * pick. Read-only. Undefined when the lookup fails or names no component.
 */
export async function currentHome(p: PanelShell, sdk: number): Promise<string | undefined> {
  if (sdk >= 24) {
    const r = await p.run(`cmd package resolve-activity --brief ${HOME_INTENT}`);
    const last = r.out.trim().split(/\r?\n/).pop()?.trim() ?? '';
    if (r.code === 0 && /^[\w.]+\/[\w.$]+$/.test(last)) return last;
    return undefined;
  }
  return parsePreferredHome((await p.run('dumpsys package preferred-activities')).out);
}

/**
 * Makes the app the home screen. Android 7+: `cmd package set-home-activity`, read back by package (Android
 * resolves HOME to the Launcher alias). If that is refused or doesn't take (a vendor build), and on Android 6,
 * Android's chooser opens and the person taps Glassworks and Always.
 */
async function setHome(p: PanelShell, r: Steps, app: GenericApp, sdk: number, o: GenericOptions): Promise<HomeResult> {
  const ours = (h?: string) => !!h && h.startsWith(`${app.package}/`);
  const phase = o.onPhase ?? (() => {});
  const sleep = o.sleep ?? defaultSleep;
  r.step(`Make ${app.name} the home screen`);
  let note: string | undefined;
  if (sdk >= 24) {
    phase({ step: 'home', how: 'set-home-activity' });
    const res = await p.run(`cmd package set-home-activity ${app.home}`);
    const home = await currentHome(p, sdk);
    if (ours(home)) { r.detail(`home screen: ${home}`); return { how: 'set-home-activity', state: 'set', home }; }
    note = res.code !== 0 || !/Success/i.test(res.out) ? `set-home-activity: ${firstLine(res.out) || `exit ${res.code}`}` : `set-home-activity answered, but Android opens ${home ?? 'something else'}`;
    r.detail(note);
  } else {
    const home = await currentHome(p, sdk);
    if (ours(home)) { r.detail('home screen already set'); return { how: 'chooser', state: 'set', home }; }
  }
  phase({ step: 'home', how: 'chooser', pick: app.name });
  await r.run(`am start ${HOME_INTENT}`);
  const until = Date.now() + (o.homeTimeoutMs ?? 180_000);
  for (;;) {
    const home = await currentHome(p, sdk);
    if (ours(home)) { r.detail(`home screen: ${home}`); return { how: 'chooser', state: 'set', home, ...(note ? { note } : {}) }; }
    if (Date.now() >= until) {
      r.detail(`home screen not confirmed (Android opens ${home ?? 'the chooser'})`);
      return { how: 'chooser', state: 'unconfirmed', ...(home ? { home } : {}), ...(note ? { note } : {}) };
    }
    await sleep(2000);
  }
}

/**
 * The hand-off to setup over the LAN (#34), as Kiln's S1 wizard does it at Done: the app's setup channel
 * through adb gives its Wi-Fi address, and `pairing.arm` opens pairing for 30 minutes, so the page at
 * http://<address>:8099/setup pairs. No page when the device isn't on Wi-Fi, or the app has no channel.
 */
export async function handOff(p: PanelShell, o: { tries?: number; sleep?: (ms: number) => Promise<void> } = {}): Promise<Handoff> {
  if (!p.openStream) return { why: 'This connection has no stream to the app.' };
  const sleep = o.sleep ?? defaultSleep;
  let c: SetupChannel | undefined;
  let st: SetupState | undefined;
  for (let i = 0; i < (o.tries ?? 10) && !c; i++) {
    try {
      const ch = new SetupChannel(await p.openStream(SETUP_SOCKET));
      try { st = await ch.getState(); c = ch; } catch { await ch.close().catch(() => {}); }
    } catch { /* not listening yet */ }
    if (!c) await sleep(1000);
  }
  if (!c || !st) return { why: 'The app didn\'t answer on its setup channel.' };
  try {
    if (!st.ip) return { why: 'The device isn\'t on Wi-Fi yet.' };
    const armed = await c.armPairing().catch(() => undefined);
    if (!armed?.ip) return { ip: st.ip, why: 'This version of the app has no setup page.' };
    return { ip: armed.ip, setupPage: `http://${armed.ip}:${WEB_PORT}/setup` };
  } finally {
    await c.close().catch(() => {});
  }
}

/**
 * Installs Glassworks (or its legacy build) on any Android 6+ device, grants what adb can, makes it the home
 * screen and hands over to setup. Throws Refused, with nothing changed, when the device or the APK is wrong.
 */
export async function installGeneric(p: PanelShell, o: GenericOptions): Promise<GenericInstalled> {
  const r = new Steps(p, o.onStep);
  const phase = o.onPhase ?? (() => {});
  phase({ step: 'read' });
  r.step('Reading the device');
  const plan = await planInstall(p, o.apk);
  const { device, app } = plan;
  r.detail(`${device.manufacturer} ${device.model}, Android ${device.release} (API ${device.sdk}), ${device.abis.join(', ')}`);
  r.detail(`${plan.identified.model?.name ?? plan.identified.brand?.name ?? 'untested device'}; APK ${plan.apk.package}${plan.abi ? ` for ${plan.abi}` : ''}`);

  r.step(plan.installed ? `Update ${app.name} (${plan.installed.name ?? plan.installed.code})` : `Install ${app.name}`);
  phase({ step: 'apk', stage: 'copy' });
  const out = await r.tryInstall('glassworks', o.apk, true, () => phase({ step: 'apk', stage: 'install' }));
  if (!/Success/.test(out)) throw installError(out, app);
  const version = await kioskVersion(p, undefined, app);
  if (!version) throw new Error(`Android says it installed the APK, but ${app.name} (${app.package}) isn't there.`);
  r.detail(`${app.name} ${version.name ?? ''} (${version.code}) ${plan.installed ? 'updated' : 'installed'}`);

  phase({ step: 'grant' });
  const grants = await grant(r, app, o.apk, device.sdk);

  phase({ step: 'updates' });
  const silent = await silentUpdates(r, app, o.apk, device, o.silentUpdates ?? true);

  phase({ step: 'start' });
  r.step(`Start ${app.name}`);
  await startApp(r, o, app);

  const home = await setHome(p, r, app, device.sdk, o);

  phase({ step: 'handoff' });
  r.step('Hand over to setup');
  const handoff = await handOff(p, { tries: o.setupTries, sleep: o.sleep });
  r.detail(handoff.setupPage ? `setup page: ${handoff.setupPage}` : handoff.why ?? '');
  return { plan, app: version, updated: !!plan.installed, grants, silentUpdates: silent, home, handoff };
}

/**
 * Undo: the app's device admin taken back, the app removed. Android then opens the home app it had, or
 * asks. Throws if the app stays installed.
 */
export async function undoGeneric(p: PanelShell, o: { onStep?: StepFn; sleep?: (ms: number) => Promise<void> } = {}): Promise<{ removed: GenericApp[] }> {
  const r = new Steps(p, o.onStep);
  const removed: GenericApp[] = [];
  for (const app of APPS) {
    if (!(await kioskVersion(p, undefined, app))) continue;
    r.step(`Removing ${app.name}`);
    r.detail(await dropAdmin(p, o.sleep ?? defaultSleep, app) ? 'device admin removed' : 'device admin still active: removing the app may fail');
    await r.try(`am force-stop ${app.package}`);
    const out = await r.try(`pm uninstall ${app.package}`);
    if (!/Success/.test(out)) throw new Error(`${app.name} could not be removed: ${firstLine(out) || 'no answer'}. Open Settings → Security → Device admin apps, turn ${app.name} off, then try again.`);
    removed.push(app);
  }
  if (!removed.length) r.step('Glassworks is not on this device');
  return { removed };
}
