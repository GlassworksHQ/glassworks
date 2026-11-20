'use client';
// A device frame's route (DeviceFrame): its install as an image over fastboot, or the app alone over adb. Locked to the
// public (Off) until its frame.json makes it installable (deviceOpen); until then ?apk=release (its newest release) and
// ?apk=local (a file from this computer) are test links, or a demo.
// The screens follow Kiln's (docs/design/kiln): one idea each, one ember button, the log one click away.
import { Panel, adbKeyFromPkcs8, loadOrCreateAdbKey, type AdbKey, type PanelShell } from '@glassworks/adb';
import { Assets, cap, fmtSize, type DeviceEntry, type DeviceFrame, type DevicePhase } from '@glassworks/kiln-core';
import * as Collapsible from '@radix-ui/react-collapsible';
import Link from 'next/link';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { BuiltOn, stepCredits } from '../components/Credit';
import { Shell, useBrowserChip } from '../components/Shell';
import { SilentUpdates } from '../components/SilentUpdates';
import type { RailMark, RailStep } from '../wizard/state';
import { Fastboot } from '@glassworks/fastboot';
import { APP_DEMOS, appDemo, type AppDemo } from './demo';
import { ImageSession } from './imageSession';
import { IMAGE_DEMOS, imageDemo, type ImageDemo } from './imageDemo';
import { KEEP_DEMOS, keepDemo, type KeepDemo } from './keepDemo';
import { ImageScreens, imageLevel, imageRail } from './ImageScreens';
import { registry } from '../wizard/registry';
import { keptDevice } from './activity';
import { ReleaseApk, TestOnly } from './ReleaseApk';
import { AppSession, deviceMode, deviceOpen, type ApkFrom, type AppState } from './appSession';
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

/** The page's two paths on one adb key: the app over adb (AppSession), or the image over fastboot (ImageSession). */
function realSessions(frame: DeviceFrame, on: boolean, from: ApkFrom = 'local'): { app: AppSession; image: ImageSession } {
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
  });
  return { app, image };
}

/** Install or undo, then Done. The rail's steps and where the person is on them. */
function rail(s: AppState, f: DeviceFrame): RailStep[] {
  const names = [f.short, 'Connect', 'Check', s.flow === 'undo' ? 'Undo' : 'Install', 'Done'];
  const at = { off: 0, pick: 1, checks: 2, working: 3, done: 5, error: 3 }[s.screen];
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

/** The APK and the Home Assistant address: on the first screen, and again on Checks to correct them. */
function Inputs({ s, session }: { s: AppState; session: AppSession }) {
  return (
    <>
      {s.release && <ReleaseApk m={session} ms={s} />}
      <div className="fields">
        {!s.release && (
          <label className="field"><span>APK file</span>
            <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void session.pickApk(f.name, f.arrayBuffer().then((b) => new Uint8Array(b))); }} />
          </label>
        )}
        <label className="field"><span>Home Assistant (optional)</span>
          <input className="input" type="url" placeholder="http://homeassistant.local:8123" value={s.haUrl} onChange={(e) => session.setHaUrl(e.target.value.trim())} />
        </label>
      </div>
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
      <p className="small">{rich(f.words['app.connectHelp'])}</p>
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

function Done({ s, session }: { s: AppState; session: AppSession }) {
  const f = session.frame;
  const r = s.result!;
  const home = r.flow === 'install' ? r.installed.home : r.removed.home;
  const title = r.flow === 'install' ? `Glassworks ${r.installed.app.name ?? ''} is on ${f.the}.` : r.removed.removed ? `Glassworks is off ${f.the}.` : `Glassworks wasn't on ${f.the}.`;
  const opened = (f?: string) => (!f ? 'nothing Kiln could read' : /ResolverActivity|ChooserActivity/.test(f) ? 'its list of home apps' : f.split('/')[0]);
  const admins = r.flow === 'install' ? r.installed.heldHomes : [];
  const body = r.flow === 'install'
    ? home === 'set' ? `It opens when ${f.the} starts.`
      : admins.length ? `${admins.join(', ')} is a device administrator, so Glassworks can't be the only home screen over adb. Install Glassworks over fastboot instead.`
      : `Android opened ${opened(r.installed.homeCheck.focused)} for the home screen${r.installed.homeCheck.left.length ? `, and ${r.installed.homeCheck.left.join(', ')} is still a home app` : ''}. Read ${f.the} again, then Update.`
    : home === 'set' ? `The home screen is ${r.removed.previousHome?.split('/')[0] ?? 'yours'} again.`
      : `Android opened ${opened(r.removed.focused)} for the home screen, not ${r.removed.previousHome?.split('/')[0] ?? 'the app you had before'}. Undo again.`;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{title}</h2>
      <p className="lede">{body}</p>
      {r.flow === 'install' && <SilentUpdates words={f.silent[r.installed.updates.state] ?? null} />}
      <BuiltOn credits={stepCredits(f.id, f.credits)} />
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ghost" onClick={() => void session.again()}>{`Read ${f.the} again`}</button>
      </div>
    </section>
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
  const [start] = useState(() => {
    const m = deviceMode(location.search, DEMO_ON && !!sim, DEMOS, deviceOpen(frame));
    const imageFirst = m.demo ? isImageDemo(m.demo) : new URLSearchParams(location.search).get('mode') !== 'app';
    if (m.demo) return { m, imageFirst };
    // the sessions live in Kiln's registry, not in this component: a running install outlives the picker
    const earlier = registry.activity(base);
    if (!m.on && !earlier) return { m, imageFirst, real: realSessions(frame, false) };
    const kept = keptDevice(base, frame.name, () => realSessions(frame, true, m.apk), imageFirst);
    // back to a running install (or from a link without ?apk=…): as it was; otherwise the address's path
    if (kept === earlier && m.on && !kept.running()) {
      kept.imageMode = imageFirst;
      kept.sessions.image.followLink(location.search); // ?image=<id> on a kept, idle session
      // ?apk=… on it: the APK from there. The app-only path takes it unless an install runs (switchApk refuses then);
      // the fastboot path only before its download is past, since a later step builds with the APK it has
      const fastbootIdle = ['choose', 'image', 'download'].includes(kept.sessions.image.state.screen);
      if (m.apk && (!imageFirst || fastbootIdle)) kept.sessions.app.switchApk(m.apk, releaseRoute);
    }
    return { m, imageFirst: kept.imageMode, real: kept.sessions, kept };
  });
  const [demo, setDemo] = useState<{ app: AppSession; image?: ImageSession }>();
  const [imageMode, setMode] = useState(start.imageFirst);
  const setImageMode = (on: boolean) => { if (start.kept) start.kept.imageMode = on; setMode(on); };
  useEffect(() => {
    const d = start.m.demo;
    if (!d || !sim) return;
    if ((KEEP_DEMOS as readonly string[]).includes(d)) void keepDemo(sim, d as KeepDemo).then(setDemo);
    else if (isImageDemo(d)) void imageDemo(sim, d as ImageDemo).then(setDemo);
    else void appDemo(sim, d as AppDemo).then((app) => setDemo({ app }));
  }, [start, sim]);
  const live = start.real ?? demo;
  if (!live) return null;
  if (live.app.state.screen !== 'off' && imageMode && live.image) return <ImageFlow image={live.image} app={live.app} toApp={() => setImageMode(false)} />;
  return <AppFlow session={live.app} toImage={live.image && live.app.state.screen !== 'off' ? () => setImageMode(true) : undefined} />;
}

export function ImageFlow({ image, app, toApp }: { image: ImageSession; app: AppSession; toApp?: () => void }) {
  const f = image.frame;
  const s = useSyncExternalStore(image.subscribe, image.get, image.get);
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

export function AppFlow({ session, toImage }: { session: AppSession; toImage?: () => void }) {
  const f = session.frame;
  const s = useSyncExternalStore(session.subscribe, session.get, session.get);
  const chip = useBrowserChip();
  useEffect(() => {
    document.title = `${f.name} · Kiln`;
  }, [s, f]);
  const p = { s, session };
  return (
    <Shell steps={rail(s, f)} level={level(s, f)} error={s.screen === 'error'} chip={chip}>
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
