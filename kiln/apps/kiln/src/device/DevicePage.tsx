'use client';
// A device frame's route (DeviceFrame): its install as an image over fastboot, or the app alone over adb, then the
// standard flow every frame runs after its install (standard/: App, Wi-Fi, Home Assistant, Panes, Radio, Done, on the
// wizard's own screens and routes). Locked to the public (Off) until its frame.json makes it installable (deviceOpen);
// until then ?apk=release (its newest release) and ?apk=local (a file from this computer) are test links, or a demo.
// The screens follow Kiln's (docs/design/kiln): one idea each, one ember button, the log one click away.
import { Panel, adbKeyFromPkcs8, loadOrCreateAdbKey, type AdbKey, type PanelShell } from '@glassworks/adb';
import { Assets, cap, fmtSize, pickInList, webUsbDevices, type DeviceEntry, type DeviceFrame, type DevicePhase, type StepCredits } from '@glassworks/kiln-core';
import * as Collapsible from '@radix-ui/react-collapsible';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useCallback, useEffect, useReducer, useRef, useState, useSyncExternalStore } from 'react';
import { BuiltOn, stepCredits } from '../components/Credit';
import { Shell, useBrowserChip } from '../components/Shell';
import { standardOfDevice } from '../standard/frame';
import { StandardSession } from '../standard/session';
import { StandardRoutes } from '../standard/StandardWizard';
import { ROUTES, status, stepOf, type Step } from '../wizard/routes';
import { Browser } from '../wizard/screens';
import type { RailMark, RailStep } from '../wizard/state';
import { Fastboot } from '@glassworks/fastboot';
import { APP_DEMOS, appDemo, type AppDemo } from './demo';
import type { AdbAsk } from './adbAsk';
import { ImageSession, sessionProgress } from './imageSession';
import { browserStageSecret, idbInPlaceStore } from './inPlace';
import { IMAGE_DEMOS, imageDemo, type ImageDemo } from './imageDemo';
import { KEEP_DEMOS, keepDemo, type KeepDemo } from './keepDemo';
import { ImageOutcome, imageActions, ImageScreens, ImageStartOver, imageLevel, imageLock, imageNav, imageSteps } from './ImageScreens';
import { Foot } from '../components/Nav';
import { registry, type Entry } from '../wizard/registry';
import { keptDevice } from './activity';
import { ReleaseApk, TestOnly } from './ReleaseApk';
import { AppSession, deviceMode, deviceOpen, type ApkFrom, type AppState } from './appSession';
import { INSTALLERS } from '@glassworks/frames/catalog';
import type { ImageState } from './imageSession';
import { rich } from './rich';

/** Demos run in `next dev` and in a build made with NEXT_PUBLIC_KILN_DEMO=1, as the wizard's do. */
const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

/**
 * The adb key loads as the page opens, so Connect can ask for the device inside its click with no
 * await in front (WebUSB shows its list only during the click), as the wizard's session does.
 */
export function adbKeyHolder(loadKey: () => Promise<AdbKey>) {
  let key: AdbKey | undefined;
  const ready = loadKey().then((k) => { key = k; });
  ready.catch(() => {}); // a click reports it (connectWith)
  return {
    key: () => key,
    connectWith: <T,>(connect: (key: AdbKey) => Promise<T>) => (): Promise<T> =>
      (key ? connect(key) : ready.then(() => { throw new Error('Kiln is finishing its one-time browser setup. Click Connect again in a moment.'); })),
  };
}

export function keyedConnect(loadKey: () => Promise<AdbKey>, connect: (key: AdbKey) => Promise<PanelShell>): () => Promise<PanelShell> {
  return adbKeyHolder(loadKey).connectWith(connect);
}

/** The release list and Refract, from the page (a bare `fetch` held by an object is an illegal invocation). */
const releaseRoute = (...a: Parameters<typeof fetch>) => fetch(...a);
/** The device's setup agent on the local network, from the page (an in-place install's receive step). */
const agentFetch = ((...a: Parameters<typeof fetch>) => fetch(...a)) as typeof fetch;

/**
 * Whether this browser has flashed this frame's device (issue 580): a flag in its storage (the frame's
 * `imageFlow.storage.flashedFlag`), never a key. Storage may be off (a private window): then the keep step is the link
 * under the fastboot steps only.
 */
const flashedHere = (flag: string) => ({
  get: () => { try { return localStorage.getItem(flag) === '1'; } catch { return false; } },
  set: () => { try { localStorage.setItem(flag, '1'); } catch { /* storage off: the link stays the way in */ } },
});

/**
 * Home Assistant's key lives in this tab only until the first start (issue 580): closing or reloading the tab loses it,
 * so the browser asks first. On for as long as the session holds the key, whichever screen shows (the session outlives
 * its screen in Kiln's registry).
 */
export function leaveWarning(target: Pick<Window, 'addEventListener' | 'removeEventListener'> = window): (on: boolean) => void {
  const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
  return (on) => (on ? target.addEventListener('beforeunload', warn) : target.removeEventListener('beforeunload', warn));
}

/**
 * The device over WebUSB adb with this browser's key (adbAsk.ts): the browser's list inside the click, then the device
 * it picked again with no list while it refuses the key. One per session, so each asks again for its own pick.
 */
export function webUsbAdb(keys: ReturnType<typeof adbKeyHolder>, authMs: number): Required<AdbAsk> {
  let picked: USBDevice | undefined;
  return {
    request: (o) => {
      picked = undefined; // a click that fails before the list (the key still loading) picks nothing
      return keys.connectWith(async (k) => {
        picked = await Panel.pick();
        o?.onPicked?.();
        return Panel.connect(k, picked, { authMs: o?.authMs });
      })();
    },
    reopen: async (o) => {
      const k = keys.key();
      if (!k || !picked) throw new Error('no panel selected');
      return Panel.connect(k, picked, { authMs: o?.authMs ?? authMs });
    },
    picked: () => !!picked,
  };
}

/** The install's end: the device, handed to the standard flow (its session, when there is one to take it). */
/**
 * `o.credits`: the credits the install stood on, by step (an in-place install wrote no one's image: none); without it,
 * the frame's. Other fields (an app install's result) are ignored.
 */
export type HandOver = (panel: PanelShell, o?: { readonly credits?: StepCredits; readonly [k: string]: unknown }) => StandardSession | undefined;

/** The page's two paths on one adb key: the app over adb (AppSession), or the image over fastboot (ImageSession). */
function realSessions(frame: DeviceFrame, on: boolean, from: ApkFrom = 'local', handOver?: HandOver, base = ''): { app: AppSession; image: ImageSession } {
  const flow = frame.imageFlow;
  if (!on) {
    const app = new AppSession({ frame, connect: () => Promise.reject(new Error('off')) }, false);
    return { app, image: new ImageSession({ frame, images: flow.images(), requestFastboot: () => Promise.reject(new Error('off')), requestAdb: () => Promise.reject(new Error('off')), adbKey: () => undefined, apk: () => undefined }) };
  }
  const keys = adbKeyHolder(loadOrCreateAdbKey);
  const appAdb = webUsbAdb(keys, frame.timings.authMs);
  const app = new AppSession({
    frame,
    connect: (o) => appAdb.request(o),
    reopen: appAdb.reopen,
    picked: appAdb.picked,
    ...(from === 'release' ? { release: releaseRoute } : {}),
    handOver: (p) => { handOver?.(p); },
    progress: sessionProgress(`kiln:device:${base}:app`),
  }, true);
  const assets = new Assets<string>('/assets/');
  /** a staged asset the frame names, checked by SHA-256, when Kiln has it */
  const staged = (name?: string) => async () => (name && (await assets.has(name)) ? assets.get(name) : undefined);
  // the first start's adb: asked again with no list while the device is still starting
  const imageAdb = webUsbAdb(keys, frame.timings.authMs);
  const image = new ImageSession({
    frame,
    // the frame's boot animation, settings APK and framework APK, staged with Kiln's other assets
    bootAnimation: staged(flow.assets?.bootAnimation),
    settings: staged(flow.assets?.settings),
    framework: staged(flow.assets?.framework),
    images: flow.images(),
    // Kiln's download route (PR 347); NEXT_PUBLIC_KILN_PROXY at build time points it elsewhere, e.g. a local Worker
    refract: process.env.NEXT_PUBLIC_KILN_PROXY ?? flow.proxy,
    // ?image=<id>: a community image's install, with no choice first (issue 579)
    search: location.search,
    requestFastboot: () => Fastboot.request([flow.fastbootFilter]),
    requestAdb: imageAdb.request,
    adbPicked: imageAdb.picked,
    reopenAdb: imageAdb.reopen,
    // a community image's own published key (its wifi-on step): in memory, for that one connection
    adbWithKey: (pkcs8) => adbKeyHolder(async () => adbKeyFromPkcs8(pkcs8, 'kiln@published-key')).connectWith((k) => Panel.connect(k)),
    adbKey: () => keys.key()?.publicKey,
    flashedHere: flashedHere(flow.storage.flashedFlag),
    holdingKey: leaveWarning(),
    apk: () => app.apkBytes(),
    apkBlocked: () => app.releaseBlocked(),
    // an in-place install wrote no one's image: its Done credits none
    handOver: (p) => { handOver?.(p, image.inPlace ? { credits: {} } : undefined); },
    progress: sessionProgress(`kiln:device:${base}:image`),
    // one stage secret per browser: a reload or another tab still checks the agent's signature
    secret: () => browserStageSecret(),
    // an in-place recipe (the frame's `imageFlow.inPlace`): its bundles and restore points in this browser, its stage's
    // staged files checked by SHA-256, and its agent on the local network
    ...(flow.storage.inPlaceDb ? { inPlaceStore: idbInPlaceStore(flow.storage.inPlaceDb) } : {}),
    stagedAssets: async (names) => Object.fromEntries(await Promise.all(names.map(async (n) => [n, await assets.get(n)] as const))),
    agentFetch,
  });
  return { app, image };
}

/**
 * The frame's standard flow, as Kiln's registry entry for its base: a StandardSession over WebUSB, or for a demo, over
 * its simulated `device`. `wayBack`: Done's way back to the frame's install.
 */
function standardEntry(frame: DeviceFrame, base: string, o: { demo?: string; device?: PanelShell; wayBack?: () => void } = {}): Entry {
  const device = o.device;
  const used = usedCredits(base);
  const session = new StandardSession({
    standard: { ...standardOfDevice(frame), ...(used ? { credits: used } : {}) },
    devices: device ? { knownAdb: async () => device, requestAdb: async () => device } : webUsbDevices,
    loadKey: device ? async () => ({ publicKey: 'QAAAAdemo kiln@demo' }) as unknown as AdbKey : loadOrCreateAdbKey,
    ...(device ? { simulated: true, sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, Math.min(ms, 800))) } : {}),
    wayBack: o.wayBack,
  });
  return { base, name: frame.name, session, demo: o.demo, standard: true };
}

type FlowData = { steps: { id: string; route?: string; transport?: string }[]; engine?: string; wayBack?: { how: string } };

/**
 * Which engine runs a flow: its installer.json `engine`, or for a frame that doesn't say, `image` when it has a fastboot
 * step and `app` when it hasn't.
 */
export const engineOf = (f: FlowData): string => f.engine ?? (f.steps.some((x) => x.transport === 'fastboot') ? 'image' : 'app');

/**
 * The install's step routes, from the frame's installer.json, by engine: its image flow, its in-place flow (when it
 * has one) and its app flow, each step's `route` by id. The address follows the install's step; going to one starts
 * nothing.
 */
export function installRoutes(id: string): { image: Record<string, string>; app: Record<string, string>; inPlace: Record<string, string> } {
  const flows = Object.values(INSTALLERS[id]?.flows ?? {}) as FlowData[];
  const byId = (f?: FlowData) => Object.fromEntries((f?.steps ?? []).filter((x) => x.route !== undefined).map((x) => [x.id, x.route!]));
  const of = (e: string) => flows.find((f) => engineOf(f) === e);
  return { image: byId(of('image')), app: byId(of('app')), inPlace: byId(of('in-place')) };
}

/** Undo's routes of a frame whose in-place flow's way back is its restore point (installer.json `wayBack.how` restore-blocks). */
export const restoresBlocks = (id: string) => Object.values(INSTALLERS[id]?.flows ?? {}).some((f) => (f as FlowData).wayBack?.how === 'restore-blocks');

/**
 * An in-place flow's step for each of its screens, in the order kiln-core's step library runs them: the first screen,
 * the first fastboot session (and its warning), the stage's writes, the receive, the second session (with the restore
 * point), and the app's install. The ids are the step ids its installer.json declares for each part, in order.
 */
export function inPlaceStep(s: ImageState, ids: readonly string[]): string {
  const [first, fastboot, stage, receive, install, appInstall] = ids;
  const step = s.work?.step;
  // the app's install while it runs or stopped; a session that left it (back on the choice, say) is on that screen's step
  if (s.screen === 'adb' || (step === 'app-install' && (s.screen === 'working' || s.screen === 'error'))) return appInstall ?? '';
  if (s.screen === 'receive') return receive ?? '';
  if (s.round === 2 && (s.screen === 'fastboot' || s.screen === 'restore-point' || s.screen === 'working' || s.screen === 'error')) return install ?? '';
  if (s.screen === 'working' || (s.screen === 'error' && s.work)) return stage ?? '';
  if (s.screen === 'fastboot' || s.screen === 'confirm') return fastboot ?? '';
  return first ?? '';
}

/**
 * A session still on the choice, opened on an in-place route: the in-place recipe, picked. Its route is the first one
 * that has nothing to resume (the flow's first screen, where its inputs are); a session past the choice stays as it is.
 */
function pickInPlace(image: ImageSession) {
  const r = image.recipes.find((x) => x.mode === 'in-place');
  if (r && image.state.screen === 'choose') image.pick(r.id);
}

/** The ids of a frame's in-place flow's steps, in order. */
const inPlaceIds = (id: string) => ((Object.values(INSTALLERS[id]?.flows ?? {}) as FlowData[]).find((f) => engineOf(f) === 'in-place')?.steps ?? []).map((x) => x.id);

/** The undo route of each way-back screen. */
const undoRoute = (s: ImageState) => ({ start: 'undo', fastboot: 'undo/connect', working: 'undo/restore', error: 'undo/restore', done: 'undo/done' } as const)[s.undo?.at ?? 'start'];

/** The image flow's step for each of its screens (installer.json step ids). */
function imageStep(s: ImageState): string {
  const firstStart = s.screen === 'adb' || s.work?.step === 'first-boot' || s.work?.step === 'hand-back';
  if (firstStart && (s.screen === 'adb' || s.screen === 'working' || s.screen === 'error')) return 'first-boot';
  if (s.screen === 'working' || s.screen === 'done' || (s.screen === 'error' && s.work)) return 'install';
  if (s.screen === 'keep' || s.screen === 'fastboot' || s.screen === 'confirm') return 'fastboot';
  return 'image';
}

/** The app flow's step: `install` while it writes, `connect` (with its checks) otherwise. */
const appStep = (s: AppState) => (s.screen === 'working' ? 'install' : 'connect');

/**
 * Keeps the address on the install's step (its declared route under `base`), without a history entry. `route`
 * undefined: the frame declares none for it, so the address stays.
 */
function useInstallRoute(base: string, route: string | undefined) {
  useEffect(() => {
    if (route === undefined || typeof window === 'undefined') return;
    const want = `${base}${route ? `/${route}` : ''}`;
    if (window.location.pathname.replace(/\/+$/, '') !== want) window.history.replaceState(null, '', `${want}${window.location.search}`);
  }, [base, route]);
}

/**
 * The page that hands a frame's device over now, by its base: sessions kept in the registry from an earlier visit were
 * made by another mount, and must reach this one's mode and way back.
 */
const HANDS = new Map<string, HandOver>();

/**
 * Installs that ended while no page of their frame was up (the person went to the picker or another frame): the device,
 * kept with its frame until its page opens again. Handing it over then would replace another frame's session.
 */
const PENDING = new Map<string, PanelShell>();

/**
 * The hand-over a frame's sessions are made with: whichever page of the frame is up when their install ends, or, with
 * none up, the frame's pending hand-over, which its page takes when it opens.
 */
export const pageHandOver = (base: string): HandOver => (p, o) => {
  setUsedCredits(base, o?.credits);
  const page = HANDS.get(base);
  if (page) return page(p, o);
  PENDING.set(base, p);
  setUpPending(base, true); // a reload before the page opens still knows set-up is next (the device itself stays here)
  return undefined;
};

/** The tab's mark that a frame's install finished with set-up still to do: no device data, only that. */
const pendingKey = (base: string) => `kiln:standard:${base}:pending`;
function setUpPending(base: string, on: boolean): void {
  try { if (on) sessionStorage.setItem(pendingKey(base), '1'); else sessionStorage.removeItem(pendingKey(base)); } catch { /* storage off */ }
}
function isSetUpPending(base: string): boolean {
  try { return sessionStorage.getItem(pendingKey(base)) === '1'; } catch { return false; }
}

/**
 * The credits the last install stood on, when they aren't the frame's: kept in memory for this page's set-up, and in the
 * tab's storage only to find them again after a reload (so set-up's Done is right with storage off too).
 */
const USED = new Map<string, StepCredits | undefined>();
const usedKey = (base: string) => `kiln:standard:${base}:credits`;
function setUsedCredits(base: string, used?: StepCredits): void {
  USED.set(base, used);
  try { if (used) sessionStorage.setItem(usedKey(base), JSON.stringify(used)); else sessionStorage.removeItem(usedKey(base)); } catch { /* storage off */ }
}
function usedCredits(base: string): StepCredits | undefined {
  if (USED.has(base)) return USED.get(base);
  try { const v = sessionStorage.getItem(usedKey(base)); return v ? JSON.parse(v) as StepCredits : undefined; } catch { return undefined; }
}

/** The standard flow's routes, after any frame's install. */
const STANDARD_ROUTES: readonly Step[] = ['set-up', 'resume', 'wifi', 'home-assistant', 'panes', 'radio', 'done'];

/** The app alone's install steps on the rail, before the standard flow's Set up and Done. */
export const appSteps = (f: Pick<DeviceFrame, 'short'>): string[] => [f.short, 'Connect', 'Check', 'Install'];

/** Install, then the standard flow's Set up and Done; or undo, then Done. The rail's steps and where the person is on them. */
export function appRail(s: AppState, f: DeviceFrame): RailStep[] {
  const names = [...appSteps(f).slice(0, 3), ...(s.flow === 'undo' ? ['Undo'] : ['Install', 'Set up']), 'Done'];
  const at = { off: 0, pick: 1, checks: 2, working: 3, done: names.length, error: 3 }[s.screen];
  return names.map((name, i): RailStep => {
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : 'now';
    return { name, mark };
  });
}

/** The app alone's rail with its way back: from its first screen to the picker, from Checks to Connect. */
function appNavOf(session: AppSession) {
  return (s: AppState): RailStep[] => appRail(s, session.frame).map((x, i) => {
    if (x.mark !== 'done' || s.connecting) return x;
    if (i === 0 && (s.screen === 'pick' || s.screen === 'off')) return { ...x, go: { href: '/' } };
    return x;
  });
}

function level(s: AppState, f: DeviceFrame): number {
  if (s.screen === 'done') return 4;
  if (s.screen === 'working') return s.phases.some((p) => f.appRows.level3.includes(p.step)) ? 3 : 2;
  return s.screen === 'checks' ? 1 : 0;
}

function Log({ s }: { s: AppState }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
      <Collapsible.Trigger className="link">{open ? 'Hide technical log' : 'Technical log'}</Collapsible.Trigger>
      <Collapsible.Content className="tech">
        <pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre>
      </Collapsible.Content>
    </Collapsible.Root>
  );
}

function Off({ f }: { f: DeviceFrame }) {
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{`${cap(f.the)} doesn't install from Kiln yet`}</h2>
      <p className="lede">Its installer is still being tested.</p>
      <Foot back={null}>
        <a className="btn ghost" href={`https://github.com/GlassworksHQ/glassworks/tree/main/frames/${f.id}`}>{`${cap(f.the)} on GitHub`}</a>
        <Link className="btn ghost" href="/">Pick a panel</Link>
      </Foot>
    </section>
  );
}

/** The APK: on the first screen, and again on Checks to correct it. Home Assistant is set-up's, after the install. */
function Inputs({ s, session }: { s: AppState; session: AppSession }) {
  return (
    <>
      {s.release && <ReleaseApk m={session} ms={s} />}
      {!s.release && (
        <div className="fields">
          <label className="field"><span>APK file</span>
            <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void session.pickApk(f.name, f.arrayBuffer().then((b) => new Uint8Array(b))); }} />
          </label>
        </div>
      )}
      {s.apk && (!s.release || s.apk.error) && (
        <div className="well">
          <div className="grow">
            <b>{s.apk.name}</b>
            {s.apk.sha256 && <span className="small">{s.apk.package ?? 'no package'} · {fmtSize(s.apk.size)} · SHA-256 <code>{s.apk.sha256}</code></span>}
            {s.apk.error && <span className="status err" role="alert">{s.apk.error}.</span>}
          </div>
        </div>
      )}
    </>
  );
}

function Pick({ s, session }: { s: AppState; session: AppSession }) {
  const f = session.frame;
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly f={f} />
      {s.release ? <>
        <h2 id="h">{`Install Glassworks on ${f.the}`}</h2>
        <p className="lede">{f.words['app.pick.release']}</p>
      </> : <>
        <h2 id="h">{`Install a test build on ${f.the}`}</h2>
        <p className="lede">{f.words['app.pick.local']}</p>
      </>}
      <Inputs s={s} session={session} />
      <BuiltOn credits={stepCredits(f.id, f.credits)} />
      <p className="small">{rich(f.words['app.connectHelp'])}{s.changed ? ` An earlier try already changed ${f.the}.` : ''}</p>
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(f))}, then choose Allow on ${f.the}'s screen…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <Foot busy={s.connecting}>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void session.connect()}>Connect</button>
      </Foot>
    </section>
  );
}

function Checks({ s, session }: { s: AppState; session: AppSession }) {
  const f = session.frame;
  const r = s.report!;
  const blocked = session.installBlocked();
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly f={f} />
      <h2 id="h">{r.ok ? `Your ${f.short} is ready` : 'Kiln can\'t install on this device'}</h2>
      <ol className="join">
        {r.checks.map((c) => (
          <li key={c.what} className={c.ok ? 'done' : 'fail'}>
            {c.what}: {c.found}{!c.ok && <span className="sub-step">Needs {c.need}.</span>}
          </li>
        ))}
      </ol>
      <p className="small">
        {r.app ? `Glassworks ${r.app.name ?? r.app.code} is installed. ` : 'Glassworks isn\'t installed. '}
        Home screen: {r.home ? <code>{r.home}</code> : 'Android asks'}.
        {r.pendingHome && <> An undo removed Glassworks, but <code>{r.pendingHome.split('/')[0]}</code> isn&apos;t the home screen again yet: undo once more to finish.</>}
      </p>
      {r.ok && <Inputs s={s} session={session} />}
      {r.ok && blocked && <p className="status err" role="alert">{blocked}</p>}
      <Foot>
        {r.app ? <button type="button" className="btn ghost" onClick={() => void session.undo()}>Remove Glassworks</button>
          : r.pendingHome ? <button type="button" className="btn ghost" onClick={() => void session.undo()}>Give the home screen back</button>
          : <span />}
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => void session.install()}>{r.app ? 'Update' : 'Install'}</button>
      </Foot>
    </section>
  );
}

const rowOf = (p: DevicePhase): string => (p.step === 'apk' ? p.stage ?? p.step : p.step);

function Working({ s, f }: { s: AppState; f: DeviceFrame }) {
  const rows = s.flow === 'undo' ? f.appRows.undo : f.appRows.install;
  const now = s.phases.at(-1);
  const at = now ? rows.findIndex((r) => r.id === rowOf(now)) : 0;
  // no step here waits on the person: a frame without touch can't answer one (a real run, 2026-11-19)
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{s.flow === 'undo' ? 'Removing Glassworks' : 'Installing Glassworks'}</h2>
      <p className="lede">{`Keep ${f.the} plugged in while Glassworks ${s.flow === 'undo' ? 'is removed' : 'installs'}.`}</p>
      <ol className="join">
        {rows.map((r, i) => <li key={r.id} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r.text}</li>)}
      </ol>
      <div className="bar wait"><i /></div>
      <Log s={s} />
    </section>
  );
}

const opened = (f?: string) => (!f ? 'nothing Kiln could read' : /ResolverActivity|ChooserActivity/.test(f) ? 'its list of home apps' : f.split('/')[0]);

/** An undo's Done (an install's is the standard flow's, with AppOutcome under it). */
function Done({ s, session }: { s: AppState; session: AppSession }) {
  const f = session.frame;
  const r = s.result as Extract<NonNullable<AppState['result']>, { flow: 'undo' }>;
  const title = r.removed.removed ? `Glassworks is off ${f.the}` : `Glassworks wasn't on ${f.the}`;
  const body = r.removed.home === 'set' ? `Your previous home screen (${r.removed.previousHome?.split('/')[0] ?? 'default'}) is restored.`
    : `Android opened ${opened(r.removed.focused)} for the home screen instead of ${r.removed.previousHome?.split('/')[0] ?? 'your previous launcher'}. Run Undo once more to finish restoring it.`;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{title}</h2>
      <p className="lede">{body}</p>
      <BuiltOn credits={stepCredits(f.id, f.credits)} />
      <Foot back={null}>
        <button type="button" className="btn ghost" onClick={() => void session.again()}>{`Read ${f.the} again`}</button>
      </Foot>
      <Log s={s} />
    </section>
  );
}

/** What the app's install left to say on the standard flow's Done: a home screen Android didn't take, and why. */
export function AppOutcome({ s, f }: { s: AppState; f: DeviceFrame }) {
  const r = s.result;
  if (r?.flow !== 'install' || r.installed.home === 'set') return null;
  const admins = r.installed.heldHomes;
  return (
    <p className="small" role="alert">{admins.length
      ? `${admins.join(', ')} is a device administrator, so Glassworks can't be the only home screen over adb. Install Glassworks over fastboot instead.`
      : `Android opened ${opened(r.installed.homeCheck.focused)} for the home screen${r.installed.homeCheck.left.length ? `, and ${r.installed.homeCheck.left.join(', ')} is still a home app` : ''}. Read ${f.the} again, then Update.`}</p>
  );
}

function Failed({ s, session }: { s: AppState; session: AppSession }) {
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">{s.flow === 'undo' ? 'Undo didn\'t finish' : 'The install didn\'t finish'}</h2>
      <p className="lede">You can check your device and try again, or review the technical details below.</p>
      <pre className="raw">{s.error}</pre>
      <Foot back={{ run: () => void session.again() }} backLabel="Back to device checks" />
      <Log s={s} />
    </section>
  );
}

const DEMOS = [...APP_DEMOS, ...IMAGE_DEMOS, ...KEEP_DEMOS];
/** Glassworks over fastboot (`flash-…`) and the choice with a community image (`image-…`) run on ImageSession. */
const isImageDemo = (d: string) => d.startsWith('flash-') || d.startsWith('image-') || d.startsWith('place-');

/**
 * With ?apk=release or ?apk=local: the image over fastboot (the default), or the app alone over adb on a device that
 * already runs a community image (`&mode=app`). Each screen links to the other. `demo`: the frame with its simulated
 * device, for ?demo=… only.
 */
export function DevicePage({ frame, demo: sim, base }: { frame: DeviceFrame; demo?: DeviceEntry; base: string }) {
  // null outside Next's router (the tests): no step route then
  const path = usePathname() ?? '';
  /** The install's end: what this page hands the device to (set below). Sessions kept from an earlier visit reach it too. */
  const handRef = useRef<HandOver | undefined>(undefined);
  const handOver = pageHandOver(base);
  const [start] = useState(() => {
    const m = deviceMode(location.search, DEMO_ON && !!sim, DEMOS, deviceOpen(frame));
    // an app flow's route (a reload there, a link) opens the app alone, as &mode=app does
    const routes = installRoutes(frame.id);
    const sub = location.pathname.slice(base.length).replace(/^\/+|\/+$/g, '');
    const appRoute = !!sub && Object.values(routes.app).includes(sub);
    // undo's routes, for a frame whose way back is its restore point: the image session's way back
    const undo = restoresBlocks(frame.id) && /^undo(\/|$)/.test(sub);
    const imageFirst = m.demo ? isImageDemo(m.demo) : undo || (!appRoute && new URLSearchParams(location.search).get('mode') !== 'app');
    // the in-place flow's routes (a link to them, a reload there with nothing to resume): its recipe, picked on the choice
    const inPlaceRoute = !!sub && Object.values(routes.inPlace).includes(sub);
    const onInPlace = <T extends { image: ImageSession }>(x: T): T => { if (inPlaceRoute) pickInPlace(x.image); return x; };
    if (m.demo) return { m, imageFirst, search: location.search, undo };
    // the sessions live in Kiln's registry, not in this component: a running install outlives the picker
    const earlier = registry.activity(base);
    if (!m.on && !earlier) return { m, imageFirst, search: location.search, real: onInPlace(realSessions(frame, false)), undo };
    const kept = keptDevice(base, frame.name, () => onInPlace(realSessions(frame, true, m.apk, handOver, base)), imageFirst);
    // back to a running install (or from a link without ?apk=…): as it was; otherwise the address's path
    if (kept === earlier && m.on && !kept.running()) {
      kept.imageMode = imageFirst;
      kept.sessions.image.followLink(location.search); // ?image=<id> on a kept, idle session
      // ?apk=… on it: the APK from there. The app-only path takes it unless an install runs (switchApk refuses then);
      // the fastboot path only before its download is past, since a later step builds with the APK it has
      const fastbootIdle = ['choose', 'image', 'download'].includes(kept.sessions.image.state.screen);
      if (m.apk && (!imageFirst || fastbootIdle)) kept.sessions.app.switchApk(m.apk, releaseRoute);
    }
    return { m, imageFirst: kept.imageMode, search: location.search, real: kept.sessions, kept, undo };
  });
  const [demo, setDemo] = useState<{ app: AppSession; image?: ImageSession }>();
  const [imageMode, setMode] = useState(start.imageFirst);
  const setImageMode = useCallback((on: boolean) => { if (start.kept) start.kept.imageMode = on; setMode(on); }, [start]);

  // the standard flow's session for this frame, once an install handed it the device or one of its routes opened
  const current = useCallback(() => { const e = registry.get(); return e?.base === base && e.standard ? e : undefined; }, [base]);
  const entry = useSyncExternalStore(registry.subscribe, current, current);
  const ws = useSyncExternalStore(entry?.session.store.subscribe ?? noSubscribe, entry?.session.store.get ?? noState, entry?.session.store.get ?? noState);
  /** Done's way back on the app alone: its first screen, to connect and undo there */
  /** A change React doesn't see (the entry's `handed`): the page draws again. */
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  const wayBack = useCallback(() => {
    // the standard flow lets go of the device first: the app's Connect opens it again (one adb connection at a time)
    const e = current();
    if (e) { e.handed = false; e.session.close(); }
    setImageMode(false);
    go(`${base}${start.search}`);
    redraw();
  }, [current, setImageMode, base, start]);
  /** The image's way back (to the community image) leaves the standard flow's Done for the install's first screen. */
  const away = useCallback(() => {
    const e = current();
    if (e) { e.handed = false; e.session.close(); }
    go(`${base}${start.search}`);
    redraw();
  }, [current, base, start]);
  // this mount's, for every session of this frame: one kept from an earlier visit hands over with today's mode and way back.
  // Gone when the page goes, so an install that ends meanwhile waits for it (PENDING) instead of replacing another session
  const mine = useRef<HandOver>((p) => handRef.current?.(p));
  HANDS.set(base, mine.current);
  useEffect(() => {
    HANDS.set(base, mine.current);
    // a hand-over that came while no page of this frame was up: this page takes it now. After a reload the device is no
    // longer held, so the page opens set-up, which connects to it again over adb
    const waiting = PENDING.get(base);
    if (waiting) { PENDING.delete(base); setUpPending(base, false); handRef.current?.(waiting); }
    else if (isSetUpPending(base)) { setUpPending(base, false); if (!current()) { const e = registry.set(standardEntry(frame, base, { wayBack: start.imageFirst ? undefined : wayBack })); e.handed = true; go(`${base}${ROUTES['set-up']}`); redraw(); } }
    return () => { if (HANDS.get(base) === mine.current) HANDS.delete(base); };
  }, [base]);
  handRef.current = (p) => {
    const demoName = start.m.demo;
    const e = registry.set(standardEntry(frame, base, { demo: demoName, device: demoName ? p : undefined, wayBack: imageMode ? undefined : wayBack }));
    e.handed = true;
    void e.session.handOver(p);
    return e.session;
  };
  const step = path.startsWith(base) ? stepOf(path.slice(base.length)) : undefined;
  const onStandard = !!step && STANDARD_ROUTES.includes(step);
  // a standard route opened with no session for it (a reload, a link): a new one, which connects to the device
  useEffect(() => {
    if (onStandard && !current() && !start.m.demo) registry.set(standardEntry(frame, base, { wayBack: start.imageFirst ? undefined : wayBack }));
  }, [onStandard, current, start, frame, base, wayBack]);
  const renew = useCallback(() => registry.set(standardEntry(frame, base, { wayBack: imageMode ? undefined : wayBack })), [frame, base, imageMode, wayBack]);

  useEffect(() => {
    const d = start.m.demo;
    if (!d || !sim) return;
    if ((KEEP_DEMOS as readonly string[]).includes(d)) void keepDemo(sim, d as KeepDemo, false, handOver).then(setDemo);
    else if (isImageDemo(d)) void imageDemo(sim, d as ImageDemo, false, {}, handOver).then(setDemo);
    else void appDemo(sim, d as AppDemo, false, handOver).then((app) => setDemo({ app }));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the demo runs once, on the page's first sessions
  }, [start, sim]);
  const live = start.real ?? demo;
  // an undo route opened (a link, a reload there): the way back's first screen, once
  useEffect(() => {
    if (start.undo && live?.image && live.image.state.screen !== 'undo') void live.image.startUndo();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per page, when its sessions are there
  }, [start, !!live?.image]);
  // the standard flow shows on its routes, and from the hand-over until it has finished
  if (entry && (onStandard || (entry.handed && status(ws) !== 'finished'))) {
    const image = imageMode && live?.image;
    const before = image ? imageSteps(frame, image.state.link, image.inPlace) : appSteps(frame);
    const outcome = live ? (image ? <LiveImageOutcome image={image} away={away} /> : <LiveAppOutcome app={live.app} />) : undefined;
    return <StandardRoutes entry={entry} renew={renew} before={before} outcome={outcome} />;
  }
  // a standard route whose session the effect above is making: nothing yet, so the install's screens don't move the address
  if (onStandard) return null;
  if (!live) return null;
  // the address follows the install's step (a demo's too, keeping its ?demo=…)
  const at = base;
  if (live.app.state.screen !== 'off' && imageMode && live.image) return <ImageFlow image={live.image} app={live.app} toApp={() => setImageMode(false)} base={at} />;
  return <AppFlow session={live.app} toImage={live.image && live.app.state.screen !== 'off' ? () => setImageMode(true) : undefined} base={at} />;
}

/** A step back to the install's screens: a history entry Next's router follows (usePathname), with no router needed here. */
const go = (url: string) => window.history.pushState(null, '', url);

const noSubscribe = () => () => {};
const noState = () => undefined as never;

export function LiveImageOutcome({ image, away }: { image: ImageSession; away: () => void }) {
  const s = useSyncExternalStore(image.subscribe, image.get, image.get);
  return image.recipe.community ? null : <ImageOutcome f={image} s={s} away={away} />;
}

export function LiveAppOutcome({ app }: { app: AppSession }) {
  const s = useSyncExternalStore(app.subscribe, app.get, app.get);
  return <AppOutcome s={s} f={app.frame} />;
}

/** `base`: the frame's route, whose step routes the address follows; none in demos and tests. */
export function ImageFlow({ image, app, toApp, base }: { image: ImageSession; app: AppSession; toApp?: () => void; base?: string }) {
  const f = image.frame;
  const s = useSyncExternalStore(image.subscribe, image.get, image.get);
  const routes = installRoutes(f.id);
  useInstallRoute(base ?? '', base === undefined ? undefined
    : s.screen === 'undo' ? undoRoute(s)
    : image.inPlace ? routes.inPlace[inPlaceStep(s, inPlaceIds(f.id))]
    : routes.image[imageStep(s)]);
  const ms = useSyncExternalStore(app.subscribe, app.get, app.get);
  const chip = useBrowserChip();
  useEffect(() => { document.title = s.screen === 'adb' || s.screen === 'receive' ? `Your turn · ${f.name} · Kiln` : `${f.name} · Kiln`; }, [s.screen, f]);
  const community = image.recipes.find((x) => x.community);
  return (
    <Shell steps={imageNav(image, s)} lock={imageLock(image)} actions={imageActions({ f: image, s })} level={imageLevel(s, image.recipe.community)} error={s.screen === 'error' || s.undo?.at === 'error'} chip={chip}>
      {/* no WebUSB: stop on the first screen, before a download and a build that only fail at the first USB call */}
      {chip?.kind === 'bad' ? <Browser /> : <ImageScreens f={image} s={s} m={app} ms={ms} />}
      {toApp && community && chip?.kind !== 'bad' && s.screen === 'image' && !image.inPlace && <p className="small">{`Already running the ${community.name} image? `}<button type="button" className="link" onClick={toApp}>Install just the app over USB</button></p>}
      <ImageStartOver f={image} s={s} />
    </Shell>
  );
}

export function AppFlow({ session, toImage, base }: { session: AppSession; toImage?: () => void; base?: string }) {
  const f = session.frame;
  const s = useSyncExternalStore(session.subscribe, session.get, session.get);
  useInstallRoute(base ?? '', base === undefined || s.screen === 'off' ? undefined : installRoutes(f.id).app[appStep(s)]);
  const chip = useBrowserChip();
  useEffect(() => {
    document.title = `${f.name} · Kiln`;
  }, [s, f]);
  const p = { s, session };
  const appNav = appNavOf(session);
  return (
    <Shell steps={appNav(s)} level={level(s, f)} error={s.screen === 'error'} chip={chip}>
      {chip?.kind === 'bad' ? <Browser />
        : s.screen === 'off' ? <Off f={f} />
        : s.screen === 'pick' ? <Pick {...p} />
        : s.screen === 'checks' ? <Checks {...p} />
        : s.screen === 'working' ? <Working s={s} f={f} />
        : s.screen === 'done' ? <Done {...p} />
        : <Failed {...p} />}
      {toImage && chip?.kind !== 'bad' && s.screen === 'pick' && <p className="small">{f.words['app.toImage']} <button type="button" className="link" onClick={toImage}>Install Glassworks over fastboot</button></p>}
    </Shell>
  );
}
