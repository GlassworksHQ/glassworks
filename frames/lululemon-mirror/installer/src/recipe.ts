// The lululemon Studio Mirror for Kiln: adb only. It runs the community Android 6 image with USB
// debugging on and no root, and its boot is signed, so there is no FEL, fastboot, boot patch or
// backup of partitions here: Kiln installs S1 Kiosk's legacy APK as an app, and nothing else on the
// Mirror changes. Undo removes the app and gives the home screen back.
//
// Android 6 has no `cmd`, so no `cmd package set-home-activity`, and its adb shell can't disable
// another app. The person picks S1 Kiosk and "Always" in Android's chooser on the Mirror, and Kiln
// waits until Android records that choice (frames/lululemon-mirror/research/home-app.md).
import type { PanelShell } from '@glassworks/adb';
import { ACT, PKG, Steps, dropAdmin, grantPermissions, kioskVersion, q, startApp, type StepFn } from '@glassworks/app-adb';
import { checkMirrorApk } from './image';

const HOME_INTENT = 'am start -a android.intent.action.MAIN -c android.intent.category.HOME';
/** The home app the Mirror had before S1 Kiosk, kept on the Mirror for undo. adb's shell owns /data/local/tmp. */
export const PREVIOUS_HOME = '/data/local/tmp/glassworks-previous-home';
/** S1 Kiosk's legacy APK, the space it needs to install and be optimised, and room to spare. */
const MIN_FREE_MB = 500;
const COMPONENT = /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/;

/** One read-only check, as the checks screen shows it. */
export interface Check { what: string; found: string; ok: boolean; need: string }

export interface AppVersion { name?: string; code: number }

/** What the Mirror is, read-only. `ok`: every check passed, so install may go ahead. */
export interface MirrorReport {
  checks: Check[];
  ok: boolean;
  /** S1 Kiosk, when it is installed */
  app?: AppVersion;
  /** the app Android opens as the home screen ("Always"), when there is one */
  home?: string;
  /** an undo removed S1 Kiosk but the old home app wasn't picked again yet: undo again to finish */
  pendingHome?: string;
}

/** The parts of install and undo the screen follows, in order. `home`: waiting for the person's tap. */
export type MirrorPhase =
  | { step: 'apk'; stage: 'copy' | 'install' }
  | { step: 'grant' }
  | { step: 'start' }
  /** `clear`: Android keeps another app's "Always" pick, so its defaults are cleared in Settings first */
  | { step: 'home'; pick: string; clear?: string }
  | { step: 'admin' }
  | { step: 'remove' };

export interface HomeWait {
  onStep?: StepFn;
  onPhase?: (phase: MirrorPhase) => void;
  sleep?: (ms: number) => Promise<void>;
  /** How long to wait for the person's tap in Android's chooser; default 3 minutes. */
  homeTimeoutMs?: number;
}

export interface MirrorOptions extends HomeWait {
  apk: Uint8Array;
  haUrl?: string;
  /** e.g. en-US; the app applies it. */
  locale?: string;
}

/** `home`: whether Android now opens the wanted app as the home screen; `unconfirmed` if the wait ran out. */
export interface Installed { app: AppVersion; home: 'set' | 'unconfirmed'; previousHome?: string }
export interface Removed { removed: boolean; home: 'set' | 'unconfirmed'; previousHome?: string }

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * The home app Android opens without asking: the HOME entry under "Preferred Activities User 0" in
 * `dumpsys package preferred-activities` that has `mAlways=true`. Android 6 drops that entry the
 * next time HOME is resolved after the set of home apps changed, e.g. when S1 Kiosk is installed.
 */
export function parsePreferredHome(dump: string): string | undefined {
  const start = dump.indexOf('Preferred Activities User 0:');
  if (start < 0) return undefined;
  const end = dump.indexOf('Preferred Activities User ', start + 1);
  let cur: { comp: string; always: boolean; home: boolean } | undefined;
  let found: string | undefined;
  const close = () => { if (cur?.always && cur.home) found = cur.comp; };
  for (const line of dump.slice(start, end < 0 ? undefined : end).split(/\r?\n/)) {
    const m = /^\s+[0-9a-f]+ (\S+\/\S+)\s*$/.exec(line);
    if (m) { close(); cur = { comp: m[1]!, always: false, home: false }; continue; }
    if (!cur) continue;
    if (/\bmAlways=true\b/.test(line)) cur.always = true;
    if (line.includes('Category: "android.intent.category.HOME"')) cur.home = true;
  }
  close();
  return found;
}

export async function preferredHome(p: PanelShell): Promise<string | undefined> {
  return parsePreferredHome((await p.run('dumpsys package preferred-activities')).out);
}

/** Free space in MB from `df /data`: Android 6's toolbox (Size Used Free, with K/M/G) or toybox (1K-blocks … Available). */
export function parseDfFreeMb(out: string): number | undefined {
  const lines = out.trim().split(/\r?\n/);
  const head = lines[0]?.trim().split(/\s+/) ?? [];
  const row = lines.find((l) => l.trim().startsWith('/') || /\s\/data\s*$/.test(l))?.trim().split(/\s+/);
  const col = head.findIndex((h) => /^(Free|Available|Avail)$/i.test(h));
  const v = col >= 0 ? row?.[col] : undefined;
  const m = v && /^(\d+(?:\.\d+)?)([KMGT]?)$/i.exec(v);
  if (!m) return undefined;
  const n = Number(m[1]);
  const unit = m[2]!.toUpperCase();
  if (!unit) return head.some((h) => /^1K-blocks$/i.test(h)) ? n / 1024 : n / (1024 * 1024);
  return n * { K: 1 / 1024, M: 1, G: 1024, T: 1024 * 1024 }[unit as 'K' | 'M' | 'G' | 'T'];
}

const prop = async (p: PanelShell, k: string) => (await p.run(`getprop ${k}`)).out.trim();

/**
 * Read-only: is this the original Mirror, on Android 6–7, with a userspace the APK runs on and room
 * for it? Writes nothing, so it is safe on any device.
 */
export async function checkMirror(p: PanelShell): Promise<MirrorReport> {
  const sdk = Number(await prop(p, 'ro.build.version.sdk'));
  const release = await prop(p, 'ro.build.version.release');
  const platform = await prop(p, 'ro.board.platform');
  const abis = (await prop(p, 'ro.product.cpu.abilist')).split(',').filter(Boolean);
  const free = parseDfFreeMb((await p.run('df /data')).out);
  const abi = abis.find((a) => a === 'arm64-v8a' || a === 'armeabi-v7a');
  const checks: Check[] = [
    { what: 'Android', found: `${release || '?'} (API ${sdk || '?'})`, ok: sdk >= 23 && sdk <= 25, need: 'Android 6 or 7 (API 23 to 25), for the legacy build' },
    { what: 'Board', found: platform || 'unknown', ok: platform === 'msm8916', need: 'Qualcomm MSM8916, the original Mirror' },
    { what: 'Processor', found: abis.join(', ') || 'unknown', ok: !!abi, need: 'arm64-v8a or armeabi-v7a' },
    { what: 'Free space', found: free === undefined ? 'unknown' : `${Math.round(free)} MB`, ok: (free ?? 0) >= MIN_FREE_MB, need: `${MIN_FREE_MB} MB` },
  ];
  const app = await kioskVersion(p);
  const home = await preferredHome(p);
  const recorded = app ? undefined : await recordedHome(new Steps(p));
  const pendingHome = recorded && recorded !== home ? recorded : undefined;
  return { checks, ok: checks.every((c) => c.ok), ...(app ? { app } : {}), ...(home ? { home } : {}), ...(pendingHome ? { pendingHome } : {}) };
}

/** pm's refusals that someone testing with the wrong file would hit, said plainly. */
function installError(out: string): Error {
  const why = /\[?(INSTALL_[A-Z_]+|Failure \[[^\]]+\])/.exec(out)?.[1] ?? out.trim();
  if (/OLDER_SDK/.test(out)) return new Error(`this APK needs a newer Android than the Mirror's: pick S1 Kiosk's legacy build. Nothing was changed. (${why})`);
  if (/NO_MATCHING_ABIS/.test(out)) return new Error(`this APK has no code for the Mirror's processor. Nothing was changed. (${why})`);
  if (/UPDATE_INCOMPATIBLE|INCONSISTENT_CERTIFICATES/.test(out)) {
    return new Error(`S1 Kiosk on the Mirror is signed with another key, so Android won't update it. Undo first, then install this one. Nothing was changed. (${why})`);
  }
  return new Error(`installing S1 Kiosk failed: ${why}`);
}

/**
 * Asks Android for the home screen, which shows its chooser when the home apps changed, and waits
 * until the person's pick is recorded: `want(home)` true. `pick` is what the screen asks them to tap.
 *
 * When the home apps didn't change (an update, with another app picked "Always" before), Android
 * keeps that pick and shows no chooser, and adb's shell can't clear another app's defaults. Then
 * that app's page in Settings is opened, the person taps Clear defaults, and the chooser follows.
 */
async function waitForHome(p: PanelShell, r: Steps, pick: string, want: (home: string | undefined) => boolean, o: HomeWait): Promise<'set' | 'unconfirmed'> {
  const sleep = o.sleep ?? defaultSleep;
  const phase = o.onPhase ?? (() => {});
  const before = await preferredHome(p);
  if (want(before)) { r.detail('home screen already set'); return 'set'; }
  phase({ step: 'home', pick });
  await r.run(HOME_INTENT);
  const until = Date.now() + (o.homeTimeoutMs ?? 180_000);
  let stuck: string | undefined;
  for (;;) {
    const home = await preferredHome(p);
    if (want(home)) { r.detail(`home screen: ${home}`); return 'set'; }
    if (home && home === before && !stuck) {
      stuck = home.split('/')[0]!;
      r.detail(`Android keeps ${home} as the home screen: opening its settings to clear its defaults`);
      phase({ step: 'home', pick, clear: stuck });
      await r.try(`am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${stuck}`);
    } else if (stuck && !home) {
      stuck = undefined;
      phase({ step: 'home', pick });
      await r.run(HOME_INTENT);
    }
    if (Date.now() >= until) { r.detail(`home screen not confirmed (Android has ${home ?? 'none'})`); return 'unconfirmed'; }
    await sleep(2000);
  }
}

async function recordedHome(r: Steps): Promise<string | undefined> {
  const v = (await r.try(`cat ${PREVIOUS_HOME} 2>/dev/null`)).trim();
  return COMPONENT.test(v) ? v : undefined;
}

/**
 * Installs S1 Kiosk's legacy APK and makes it the home screen. Refuses (nothing changed) unless
 * every check passes. Keeps the home app the Mirror had, once, on the Mirror, so undo can give it
 * back even from another browser.
 */
export async function installMirror(p: PanelShell, o: MirrorOptions): Promise<Installed> {
  const r = new Steps(p, o.onStep);
  const phase = o.onPhase ?? (() => {});
  r.step('Checking the Mirror');
  const report = await checkMirror(p);
  for (const c of report.checks) r.detail(`${c.ok ? 'ok ' : 'NO '} ${c.what}: ${c.found}`);
  const bad = report.checks.filter((c) => !c.ok);
  if (bad.length) throw new Error(`this doesn't look like a Mirror Kiln can install on (${bad.map((c) => `${c.what}: ${c.found}, needs ${c.need}`).join('; ')}). Nothing was changed.`);

  // S1 Kiosk, on API 23, with a home screen Android can open: else the Mirror is left at the chooser
  const pkg = (await checkMirrorApk(o.apk)).package;
  r.detail(`APK: ${pkg}`);

  // the home app to give back on undo: kept on the Mirror once the install worked, never before
  const recorded = await recordedHome(r);
  const candidate = !recorded && report.home && report.home !== ACT && COMPONENT.test(report.home) ? report.home : undefined;

  r.step(report.app ? `Update S1 Kiosk (${report.app.name ?? report.app.code})` : 'Install S1 Kiosk');
  phase({ step: 'apk', stage: 'copy' });
  const out = await r.tryInstall('s1kiosk', o.apk, true, () => phase({ step: 'apk', stage: 'install' }));
  if (!/Success/.test(out)) throw installError(out);
  const app = await kioskVersion(p);
  if (!app) throw new Error(`Android says it installed the APK, but S1 Kiosk (${PKG}) isn't there.`);
  r.detail(`S1 Kiosk ${app.name ?? ''} (${app.code}) installed`);
  const previousHome = recorded ?? candidate;
  if (candidate) {
    await r.run(`echo ${q(candidate)} > ${PREVIOUS_HOME}`);
    r.detail(`kept the home app for undo: ${candidate}`);
  }

  phase({ step: 'grant' });
  await grantPermissions(r);

  phase({ step: 'start' });
  r.step('Start S1 Kiosk');
  await startApp(r, o);

  r.step('Make S1 Kiosk the home screen');
  const home = await waitForHome(p, r, 'S1 Kiosk', (h) => h === ACT, o);
  return { app, home, ...(previousHome ? { previousHome } : {}) };
}

/**
 * Undo: S1 Kiosk's device admin taken back, the app removed, and the home app the Mirror had before
 * picked again. Throws if the app stays installed: nothing about that is a success.
 */
export async function undoMirror(p: PanelShell, o: HomeWait = {}): Promise<Removed> {
  const r = new Steps(p, o.onStep);
  const phase = o.onPhase ?? (() => {});
  const sleep = o.sleep ?? defaultSleep;
  const previousHome = await recordedHome(r);
  const installed = await kioskVersion(p);
  if (installed) {
    phase({ step: 'admin' });
    r.step('Removing S1 Kiosk\'s device admin');
    r.detail(await dropAdmin(p, sleep) ? 'device admin removed' : 'device admin still active: removing the app may fail');
    phase({ step: 'remove' });
    r.step('Removing S1 Kiosk');
    await r.try(`am force-stop ${PKG}`);
    const out = await r.try(`pm uninstall ${PKG}`);
    if (!/Success/.test(out)) throw new Error(`S1 Kiosk could not be removed: ${out.trim() || 'no answer'}. Open the Mirror's Settings → Security → Device administrators, turn S1 Kiosk off, then try again.`);
  } else {
    r.step('S1 Kiosk is not on the Mirror');
  }

  r.step('Give the home screen back');
  const home = await waitForHome(p, r, previousHome ?? 'the app you had before',
    (h) => (previousHome ? h === previousHome : !!h && !h.startsWith(`${PKG}/`)), o);
  if (home === 'set') await r.try(`rm -f ${PREVIOUS_HOME}`);
  return { removed: !!installed, home, ...(previousHome ? { previousHome } : {}) };
}
