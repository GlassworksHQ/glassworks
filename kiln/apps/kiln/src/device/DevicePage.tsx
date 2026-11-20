'use client';
// A device frame's route (DeviceFrame): its install as an image over fastboot, or the app alone over adb, then the
// standard flow every frame runs after its install (standard/: App, Wi-Fi, Home Assistant, Panes, Radio, Done, on the
// wizard's own screens and routes). Locked to the public (Off) until its frame.json makes it installable (deviceOpen);
// until then ?apk=release (its newest release) and ?apk=local (a file from this computer) are test links, or a demo.
// The screens follow Kiln's (docs/design/kiln): one idea each, one ember button, the log one click away.
import { Panel, adbKeyFromPkcs8, loadOrCreateAdbKey, type AdbKey, type PanelShell } from '@glassworks/adb';
import { Assets, cap, fmtSize, webUsbDevices, type DeviceEntry, type DeviceFrame, type DevicePhase } from '@glassworks/kiln-core';
import * as Collapsible from '@radix-ui/react-collapsible';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { BuiltOn, stepCredits } from '../components/Credit';
import { Shell, useBrowserChip } from '../components/Shell';
import { standardOfDevice } from '../standard/frame';
import { StandardSession } from '../standard/session';
import { StandardRoutes } from '../standard/StandardWizard';
import { ROUTES, status, stepOf, type Step } from '../wizard/routes';
import type { RailMark, RailStep } from '../wizard/state';
import { Fastboot } from '@glassworks/fastboot';
import { APP_DEMOS, appDemo, type AppDemo } from './demo';
import { ImageSession, sessionProgress } from './imageSession';
import { IMAGE_DEMOS, imageDemo, type ImageDemo } from './imageDemo';
import { KEEP_DEMOS, keepDemo, type KeepDemo } from './keepDemo';
import { ImageOutcome, ImageScreens, imageLevel, imageRail, imageSteps } from './ImageScreens';
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
      (key ? connect(key) : ready.then(() => { throw new Error('Kiln was still making its key for this browser. Click Connect again.'); })),
  };
}

export function keyedConnect(loadKey: () => Promise<AdbKey>, connect: (key: AdbKey) => Promise<PanelShell>): () => Promise<PanelShell> {
  return adbKeyHolder(loadKey).connectWith(connect);
}

/** Kiln's release route, from the page (a bare `fetch` held by an object is an illegal invocation). */
const releaseRoute = (...a: Parameters<typeof fetch>) => fetch(...a);

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

/** The install's end: the device, handed to the standard flow (its session, when there is one to take it). */
export type HandOver = (panel: PanelShell) => StandardSession | undefined;

/** The page's two paths on one adb key: the app over adb (AppSession), or the image over fastboot (ImageSession). */
function realSessions(frame: DeviceFrame, on: boolean, from: ApkFrom = 'local', handOver?: HandOver, base = ''): { app: AppSession; image: ImageSession } {
  const flow = frame.imageFlow;
  if (!on) {
    const app = new AppSession({ frame, connect: () => Promise.reject(new Error('off')) }, false);
    return { app, image: new ImageSession({ frame, images: flow.images(), requestFastboot: () => Promise.reject(new Error('off')), requestAdb: () => Promise.reject(new Error('off')), adbKey: () => undefined, apk: () => undefined }) };
  }
  const keys = adbKeyHolder(loadOrCreateAdbKey);
  const app = new AppSession({
    frame,
    connect: keys.connectWith((k) => Panel.connect(k)),
    ...(from === 'release' ? { release: releaseRoute } : {}),
    handOver: (p) => { handOver?.(p); },
    progress: sessionProgress(`kiln:device:${base}:app`),
  }, true);
  const assets = new Assets<string>('/assets/');
  /** a staged asset the frame names, checked by SHA-256, when Kiln has it */
  const staged = (name?: string) => async () => (name && (await assets.has(name)) ? assets.get(name) : undefined);
  // the device picked for the first start's adb: asked again with no list while the device is still starting
  let adbDevice: USBDevice | undefined;
  const image = new ImageSession({
    frame,
    // the frame's boot animation and settings APK, staged with Kiln's other assets
    bootAnimation: staged(flow.assets?.bootAnimation),
    settings: staged(flow.assets?.settings),
    images: flow.images(),
    // Kiln's download route (PR 347); NEXT_PUBLIC_KILN_PROXY at build time points it elsewhere, e.g. a local Worker
    refract: process.env.NEXT_PUBLIC_KILN_PROXY ?? flow.proxy,
    // ?image=<id>: a community image's install, with no choice first (issue 579)
    search: location.search,
    requestFastboot: () => Fastboot.request([flow.fastbootFilter]),
    requestAdb: (o) => {
      adbDevice = undefined; // a click that fails before the list (the key still loading) picks nothing
      return keys.connectWith(async (k) => {
        adbDevice = await Panel.pick();
        o?.onPicked?.();
        return Panel.connect(k, adbDevice, { authMs: o?.authMs });
      })();
    },
    adbPicked: () => !!adbDevice,
    reopenAdb: async (o) => {
      const k = keys.key();
      if (!k || !adbDevice) throw new Error('no panel selected');
      return Panel.connect(k, adbDevice, { authMs: o?.authMs ?? frame.timings.authMs });
    },
    // a community image's own published key (its wifi-on step): in memory, for that one connection
    adbWithKey: (pkcs8) => adbKeyHolder(async () => adbKeyFromPkcs8(pkcs8, 'kiln@published-key')).connectWith((k) => Panel.connect(k)),
    adbKey: () => keys.key()?.publicKey,
    flashedHere: flashedHere(flow.storage.flashedFlag),
    holdingKey: leaveWarning(),
    apk: () => app.apkBytes(),
    apkBlocked: () => app.releaseBlocked(),
    handOver: (p) => { handOver?.(p); },
    progress: sessionProgress(`kiln:device:${base}:image`),
  });
  return { app, image };
}

/**
 * The frame's standard flow, as Kiln's registry entry for its base: a StandardSession over WebUSB, or for a demo, over
 * its simulated `device`. `wayBack`: Done's way back to the frame's install.
 */
function standardEntry(frame: DeviceFrame, base: string, o: { demo?: string; device?: PanelShell; wayBack?: () => void } = {}): Entry {
  const device = o.device;
  const session = new StandardSession({
    standard: standardOfDevice(frame),
    devices: device ? { knownAdb: async () => device, requestAdb: async () => device } : webUsbDevices,
    loadKey: device ? async () => ({ publicKey: 'QAAAAdemo kiln@demo' }) as unknown as AdbKey : loadOrCreateAdbKey,
    ...(device ? { simulated: true, sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, Math.min(ms, 800))) } : {}),
    wayBack: o.wayBack,
  });
  return { base, name: frame.name, session, demo: o.demo, standard: true };
}

/**
 * The install's step routes, from the frame's installer.json: its image flow (the one with a fastboot step) and its app
 * flow (the other), each step's `route` by id. The address follows the install's step; going to one starts nothing.
 */
function installRoutes(id: string): { image: Record<string, string>; app: Record<string, string> } {
  const flows = Object.values(INSTALLERS[id]?.flows ?? {}) as { steps: { id: string; route?: string; transport?: string }[] }[];
  const byId = (f?: { steps: { id: string; route?: string }[] }) => Object.fromEntries((f?.steps ?? []).filter((x) => x.route !== undefined).map((x) => [x.id, x.route!]));
  return { image: byId(flows.find((f) => f.steps.some((x) => x.transport === 'fastboot'))), app: byId(flows.find((f) => !f.steps.some((x) => x.transport === 'fastboot'))) };
}

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
      <h2 id="h">{`${cap(f.the)} doesn't install from Kiln yet.`}</h2>
      <p className="lede">Its installer is still being tested.</p>
      <div className="foot">
        <a className="link" href={`https://github.com/GlassworksHQ/glassworks/tree/main/frames/${f.id}`}>{`${cap(f.the)} on GitHub`}</a>
        <Link className="btn ghost" href="/">Pick a panel</Link>
      </div>
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
        <h2 id="h">{`Install Glassworks on ${f.the}.`}</h2>
        <p className="lede">{f.words['app.pick.release']}</p>
      </> : <>
        <h2 id="h">{`Install a test build on ${f.the}.`}</h2>
        <p className="lede">{f.words['app.pick.local']}</p>
      </>}
      <Inputs s={s} session={session} />
      <BuiltOn credits={stepCredits(f.id, f.credits)} />
      <p className="small">{rich(f.words['app.connectHelp'])}{s.changed ? ` An earlier try already changed ${f.the}.` : ''}</p>
      {s.connecting && <p className="status work" role="status">{`Pick ${f.the} in the browser's list, then choose Allow on ${f.the}'s screen…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <span />
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void session.connect()}>Connect</button>
      </div>
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
      <h2 id="h">{r.ok ? `This is your ${f.short}.` : 'Kiln can\'t install on this device.'}</h2>
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
      <div className="foot">
        <span className="links">
          {r.app && <button type="button" className="link" onClick={() => void session.undo()}>Undo: remove Glassworks</button>}
          {!r.app && r.pendingHome && <button type="button" className="link" onClick={() => void session.undo()}>Undo: give the home screen back</button>}
        </span>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => void session.install()}>{r.app ? 'Update' : 'Install'}</button>
      </div>
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
      <h2 id="h">{s.flow === 'undo' ? 'Removing Glassworks.' : 'Installing Glassworks.'}</h2>
      <p className="lede">{`Keep ${f.the} plugged in.`}</p>
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
  const title = r.removed.removed ? `Glassworks is off ${f.the}.` : `Glassworks wasn't on ${f.the}.`;
  const body = r.removed.home === 'set' ? `The home screen is ${r.removed.previousHome?.split('/')[0] ?? 'yours'} again.`
    : `Android opened ${opened(r.removed.focused)} for the home screen, not ${r.removed.previousHome?.split('/')[0] ?? 'the app you had before'}. Undo again.`;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{title}</h2>
      <p className="lede">{body}</p>
      <BuiltOn credits={stepCredits(f.id, f.credits)} />
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ghost" onClick={() => void session.again()}>{`Read ${f.the} again`}</button>
      </div>
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
      <h2 id="h">{s.flow === 'undo' ? 'Undo stopped.' : 'The install stopped.'}</h2>
      <pre className="raw">{s.error}</pre>
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ghost" onClick={() => void session.again()}>Back to the checks</button>
      </div>
    </section>
  );
}

const DEMOS = [...APP_DEMOS, ...IMAGE_DEMOS, ...KEEP_DEMOS];
/** Glassworks over fastboot (`flash-…`) and the choice with a community image (`image-…`) run on ImageSession. */
const isImageDemo = (d: string) => d.startsWith('flash-') || d.startsWith('image-');

/**
 * With ?apk=release or ?apk=local: the image over fastboot (the default), or the app alone over adb on a device that
 * already runs a community image (`&mode=app`). Each screen links to the other. `demo`: the frame with its simulated
 * device, for ?demo=… only.
 */
export function DevicePage({ frame, demo: sim, base }: { frame: DeviceFrame; demo?: DeviceEntry; base: string }) {
  // null outside Next's router (the tests): no step route then
  const path = usePathname() ?? '';
  /** The install's end: what this page hands the device to (set below, once the router is here). */
  const handRef = useRef<HandOver | undefined>(undefined);
  const handOver: HandOver = (p) => handRef.current?.(p);
  const [start] = useState(() => {
    const m = deviceMode(location.search, DEMO_ON && !!sim, DEMOS, deviceOpen(frame));
    // an app flow's route (a reload there, a link) opens the app alone, as &mode=app does
    const routes = installRoutes(frame.id);
    const sub = location.pathname.slice(base.length).replace(/^\/+|\/+$/g, '');
    const appRoute = !!sub && Object.values(routes.app).includes(sub);
    const imageFirst = m.demo ? isImageDemo(m.demo) : !appRoute && new URLSearchParams(location.search).get('mode') !== 'app';
    if (m.demo) return { m, imageFirst, search: location.search };
    // the sessions live in Kiln's registry, not in this component: a running install outlives the picker
    const earlier = registry.activity(base);
    if (!m.on && !earlier) return { m, imageFirst, search: location.search, real: realSessions(frame, false) };
    const kept = keptDevice(base, frame.name, () => realSessions(frame, true, m.apk, handOver, base), imageFirst);
    // back to a running install (or from a link without ?apk=…): as it was; otherwise the address's path
    if (kept === earlier && m.on && !kept.running()) {
      kept.imageMode = imageFirst;
      kept.sessions.image.followLink(location.search); // ?image=<id> on a kept, idle session
      // ?apk=… on it: the APK from there. The app-only path takes it unless an install runs (switchApk refuses then);
      // the fastboot path only before its download is past, since a later step builds with the APK it has
      const fastbootIdle = ['choose', 'image', 'download'].includes(kept.sessions.image.state.screen);
      if (m.apk && (!imageFirst || fastbootIdle)) kept.sessions.app.switchApk(m.apk, releaseRoute);
    }
    return { m, imageFirst: kept.imageMode, search: location.search, real: kept.sessions, kept };
  });
  const [demo, setDemo] = useState<{ app: AppSession; image?: ImageSession }>();
  const [imageMode, setMode] = useState(start.imageFirst);
  const setImageMode = useCallback((on: boolean) => { if (start.kept) start.kept.imageMode = on; setMode(on); }, [start]);

  // the standard flow's session for this frame, once an install handed it the device or one of its routes opened
  const current = useCallback(() => { const e = registry.get(); return e?.base === base && e.standard ? e : undefined; }, [base]);
  const entry = useSyncExternalStore(registry.subscribe, current, current);
  const ws = useSyncExternalStore(entry?.session.store.subscribe ?? noSubscribe, entry?.session.store.get ?? noState, entry?.session.store.get ?? noState);
  /** Done's way back on the app alone: its first screen, to connect and undo there */
  const wayBack = useCallback(() => {
    const e = current();
    if (e) e.handed = false;
    setImageMode(false);
    go(`${base}${start.search}`);
  }, [current, setImageMode, base, start]);
  /** The image's way back (to the community image) leaves the standard flow's Done for the install's first screen. */
  const away = useCallback(() => { const e = current(); if (e) e.handed = false; go(`${base}${start.search}`); }, [current, base, start]);
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
  // the standard flow shows on its routes, and from the hand-over until it has finished
  if (entry && (onStandard || (entry.handed && status(ws) !== 'finished'))) {
    const image = imageMode && live?.image;
    const before = image ? imageSteps(frame, image.state.link) : appSteps(frame);
    const outcome = live ? (image ? <LiveImageOutcome image={image} away={away} /> : <LiveAppOutcome app={live.app} />) : undefined;
    return <StandardRoutes entry={entry} renew={renew} before={before} outcome={outcome} />;
  }
  if (!live) return null;
  // demos keep their own address (?demo=…): only a real install moves it
  const at = start.m.demo ? undefined : base;
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
  useInstallRoute(base ?? '', base === undefined ? undefined : installRoutes(f.id).image[imageStep(s)]);
  const ms = useSyncExternalStore(app.subscribe, app.get, app.get);
  const chip = useBrowserChip();
  useEffect(() => { document.title = s.screen === 'adb' ? `Your turn · ${f.name} · Kiln` : `${f.name} · Kiln`; }, [s.screen, f]);
  const community = image.recipes.find((x) => x.community);
  return (
    <Shell steps={imageRail(s, f, image.recipe.community)} level={imageLevel(s, image.recipe.community)} error={s.screen === 'error'} chip={chip}>
      <ImageScreens f={image} s={s} m={app} ms={ms} />
      {toApp && community && s.screen === 'image' && <p className="small">{`Already runs the ${community.name} image? `}<button type="button" className="link" onClick={toApp}>Add the app only, over adb</button></p>}
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
  return (
    <Shell steps={appRail(s, f)} level={level(s, f)} error={s.screen === 'error'} chip={chip}>
      {s.screen === 'off' ? <Off f={f} />
        : s.screen === 'pick' ? <Pick {...p} />
        : s.screen === 'checks' ? <Checks {...p} />
        : s.screen === 'working' ? <Working s={s} f={f} />
        : s.screen === 'done' ? <Done {...p} />
        : <Failed {...p} />}
      {toImage && s.screen === 'pick' && <p className="small">{f.words['app.toImage']} <button type="button" className="link" onClick={toImage}>Install Glassworks over fastboot</button></p>}
    </Shell>
  );
}
