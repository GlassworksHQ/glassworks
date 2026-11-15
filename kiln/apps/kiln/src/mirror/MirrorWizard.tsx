'use client';
// /lululemon-mirror: the Mirror's test install. Off unless the address has ?apk=local (or a demo):
// the Mirror is a planned frame, and the only APK for it is a test build picked from this computer.
// The screens follow Kiln's (docs/design/kiln): one idea each, one ember button, the log one click away.
import { Panel, loadOrCreateAdbKey, type AdbKey, type PanelShell } from '@glassworks/adb';
import { fmtSize } from '@glassworks/kiln-core';
import { credits, type MirrorPhase } from '@glassworks/lululemon-mirror-installer';
import * as Collapsible from '@radix-ui/react-collapsible';
import Link from 'next/link';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { BuiltOn, stepCredits } from '../components/Credit';
import { Shell, useBrowserChip } from '../components/Shell';
import type { RailMark, RailStep } from '../wizard/state';
import { MIRROR_DEMOS, mirrorDemo, type MirrorDemo } from './demo';
import { MirrorSession, mirrorMode, type MirrorState } from './session';

/** Demos run in `next dev` and in a build made with NEXT_PUBLIC_KILN_DEMO=1, as the S1's do. */
const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

/**
 * The adb key loads as the page opens, so Connect can ask for the device inside its click with no
 * await in front (WebUSB shows its list only during the click), as the S1's session does.
 */
export function keyedConnect(loadKey: () => Promise<AdbKey>, connect: (key: AdbKey) => Promise<PanelShell>): () => Promise<PanelShell> {
  let key: AdbKey | undefined;
  const ready = loadKey().then((k) => { key = k; });
  ready.catch(() => {}); // a click reports it, below
  return () => (key ? connect(key) : ready.then(() => { throw new Error('Kiln was still making its key for this browser. Click Connect again.'); }));
}

function realSession(on: boolean): MirrorSession {
  const connect = on ? keyedConnect(loadOrCreateAdbKey, (k) => Panel.connect(k)) : () => Promise.reject(new Error('off'));
  return new MirrorSession({ connect }, on);
}

/** Install or undo, then Done. The rail's steps and where the person is on them. */
function rail(s: MirrorState): RailStep[] {
  const names = ['Mirror', 'Connect', 'Check', s.flow === 'undo' ? 'Undo' : 'Install', 'Done'];
  const at = { off: 0, pick: 1, checks: 2, working: 3, done: 5, error: 3 }[s.screen];
  const turn = s.screen === 'working' && s.phases.at(-1)?.step === 'home';
  return names.map((name, i): RailStep => {
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : turn ? 'turn' : 'now';
    return { name, mark };
  });
}

function level(s: MirrorState): number {
  if (s.screen === 'done') return 4;
  if (s.screen === 'working') return s.phases.some((p) => p.step === 'grant' || p.step === 'remove') ? 3 : 2;
  return s.screen === 'checks' ? 1 : 0;
}

function TestOnly() {
  return <span className="pill soon">Test only</span>;
}

function Log({ s }: { s: MirrorState }) {
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

function Off() {
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">The Mirror doesn&apos;t install from Kiln yet.</h2>
      <p className="lede">It is a planned frame. Follow it on GitHub, or pick another panel.</p>
      <div className="foot">
        <a className="link" href="https://github.com/GlassworksHQ/glassworks/tree/main/frames/lululemon-mirror">The Mirror on GitHub</a>
        <Link className="btn ghost" href="/">Pick a panel</Link>
      </div>
    </section>
  );
}

/** The APK and the Home Assistant address: on the first screen, and again on Checks to correct them. */
function Inputs({ s, session }: { s: MirrorState; session: MirrorSession }) {
  return (
    <>
      <div className="fields">
        <label className="field"><span>APK file</span>
          <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void session.pickApk(f.name, f.arrayBuffer().then((b) => new Uint8Array(b))); }} />
        </label>
        <label className="field"><span>Home Assistant (optional)</span>
          <input className="input" type="url" placeholder="http://homeassistant.local:8123" value={s.haUrl} onChange={(e) => session.setHaUrl(e.target.value.trim())} />
        </label>
      </div>
      {s.apk && (
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

function Pick({ s, session }: { s: MirrorState; session: MirrorSession }) {
  return (
    <section className="card" aria-labelledby="h">
      <div className="row"><TestOnly /></div>
      <h2 id="h">Install a test build on the Mirror.</h2>
      <p className="lede">Kiln installs an APK file from this computer: S1 Kiosk&apos;s legacy build for Android 6. Only for testing. Nothing else on the Mirror changes.</p>
      <Inputs s={s} session={session} />
      <BuiltOn credits={stepCredits('lululemon-mirror', credits)} />
      <p className="small">On the Mirror, USB debugging is on. Plug it into this computer, click Connect and pick it in the browser&apos;s list. Then the Mirror asks to allow USB debugging for this browser: choose <b>Allow</b>. It has no touchscreen, so use a USB mouse.</p>
      {s.connecting && <p className="status work" role="status">Pick the Mirror in the browser&apos;s list, then choose Allow on the Mirror&apos;s screen…</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <span className="small">Connecting only reads the Mirror.</span>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void session.connect()}>Connect</button>
      </div>
    </section>
  );
}

function Checks({ s, session }: { s: MirrorState; session: MirrorSession }) {
  const r = s.report!;
  const blocked = session.installBlocked();
  return (
    <section className="card" aria-labelledby="h">
      <div className="row"><TestOnly /></div>
      <h2 id="h">{r.ok ? 'This is your Mirror.' : 'Kiln can\'t install on this device.'}</h2>
      <ol className="join">
        {r.checks.map((c) => (
          <li key={c.what} className={c.ok ? 'done' : 'fail'}>
            {c.what}: {c.found}{!c.ok && <span className="sub-step">Needs {c.need}.</span>}
          </li>
        ))}
      </ol>
      <p className="small">
        {r.app ? `S1 Kiosk ${r.app.name ?? r.app.code} is installed. ` : 'S1 Kiosk isn\'t installed. '}
        Home screen: {r.home ? <code>{r.home}</code> : 'Android asks'}.
        {r.pendingHome && <> An undo removed S1 Kiosk, but <code>{r.pendingHome.split('/')[0]}</code> isn&apos;t the home screen again yet: undo once more to finish.</>}
      </p>
      {r.ok && <Inputs s={s} session={session} />}
      {r.ok && blocked && <p className="status err" role="alert">{blocked}</p>}
      <div className="foot">
        <span className="links">
          {r.app && <button type="button" className="link" onClick={() => void session.undo()}>Undo: remove S1 Kiosk</button>}
          {!r.app && r.pendingHome && <button type="button" className="link" onClick={() => void session.undo()}>Undo: give the home screen back</button>}
        </span>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => void session.install()}>{r.app ? 'Update' : 'Install'}</button>
      </div>
    </section>
  );
}

type Row = { id: MirrorPhase['step'] | 'copy' | 'install'; text: string };
const INSTALL_ROWS: Row[] = [
  { id: 'copy', text: 'Copy the APK to the Mirror' },
  { id: 'install', text: 'Android installs it and optimises it' },
  { id: 'grant', text: 'Permissions' },
  { id: 'start', text: 'First start, with your Home Assistant' },
  { id: 'home', text: 'Make it the home screen' },
];
const UNDO_ROWS: Row[] = [
  { id: 'admin', text: 'Take back S1 Kiosk\'s device admin' },
  { id: 'remove', text: 'Remove S1 Kiosk' },
  { id: 'home', text: 'Give the home screen back' },
];
const rowOf = (p: MirrorPhase): Row['id'] => (p.step === 'apk' ? p.stage : p.step);

function Working({ s }: { s: MirrorState }) {
  const rows = s.flow === 'undo' ? UNDO_ROWS : INSTALL_ROWS;
  const now = s.phases.at(-1);
  const at = now ? rows.findIndex((r) => r.id === rowOf(now)) : 0;
  const pick = now?.step === 'home' ? now.pick : undefined;
  const clear = now?.step === 'home' ? now.clear : undefined;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{s.flow === 'undo' ? 'Removing S1 Kiosk.' : 'Installing S1 Kiosk.'}</h2>
      <p className="lede">Keep the Mirror plugged in.</p>
      <ol className="join">
        {rows.map((r, i) => <li key={r.id} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r.text}</li>)}
      </ol>
      {pick ? (
        <div className="well" role="status">
          <div className="grow">
            <span className="turn-label"><span className="pulse" />Your turn, on the Mirror</span>
            {clear ? <>
              <b>Choose Clear defaults.</b>
              <span className="small">Android keeps <code>{clear}</code> as the home screen, so Kiln opened its settings. After Clear defaults, Android shows its list of home screens: choose {pick.includes('/') ? <code>{pick.split('/')[0]}</code> : pick}, then Always.</span>
            </> : <>
              <b>Choose {pick.includes('/') ? <code>{pick.split('/')[0]}</code> : pick}, then Always.</b>
              <span className="small">Android shows its list of home screens. Kiln carries on once it sees your choice.</span>
            </>}
          </div>
        </div>
      ) : <div className="bar wait"><i /></div>}
      <Log s={s} />
    </section>
  );
}

function Done({ s, session }: { s: MirrorState; session: MirrorSession }) {
  const r = s.result!;
  const home = r.flow === 'install' ? r.installed.home : r.removed.home;
  const title = r.flow === 'install' ? `S1 Kiosk ${r.installed.app.name ?? ''} is on the Mirror.` : r.removed.removed ? 'S1 Kiosk is off the Mirror.' : 'S1 Kiosk wasn\'t on the Mirror.';
  const body = r.flow === 'install'
    ? home === 'set' ? 'It is the home screen, so it opens when the Mirror starts.' : 'Kiln didn\'t see it picked as the home screen. If the Mirror shows a list of apps, choose S1 Kiosk, then Always.'
    : home === 'set' ? `The home screen is ${r.removed.previousHome ?? 'yours'} again.` : `If the Mirror shows a list of apps, choose ${r.removed.previousHome?.split('/')[0] ?? 'the one you had before'}, then Always. Undo again to check.`;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{title}</h2>
      <p className="lede">{body}</p>
      <BuiltOn credits={stepCredits('lululemon-mirror', credits)} />
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ghost" onClick={() => void session.again()}>Read the Mirror again</button>
      </div>
    </section>
  );
}

function Failed({ s, session }: { s: MirrorState; session: MirrorSession }) {
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

export function MirrorWizard() {
  const [session] = useState(() => {
    const m = mirrorMode(location.search, DEMO_ON, MIRROR_DEMOS);
    return { m, s: m.demo ? undefined : realSession(m.on) };
  });
  const [demo, setDemo] = useState<MirrorSession>();
  useEffect(() => { if (session.m.demo) void mirrorDemo(session.m.demo as MirrorDemo).then(setDemo); }, [session]);
  const live = session.s ?? demo;
  return live ? <Screens session={live} /> : null;
}

export function Screens({ session }: { session: MirrorSession }) {
  const s = useSyncExternalStore(session.subscribe, session.get, session.get);
  const chip = useBrowserChip();
  useEffect(() => {
    document.title = s.screen === 'working' && s.phases.at(-1)?.step === 'home' ? 'Your turn · lululemon Studio Mirror · Kiln' : 'lululemon Studio Mirror · Kiln';
  }, [s]);
  useEffect(() => {
    if (s.screen !== 'working') return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    addEventListener('beforeunload', warn);
    return () => removeEventListener('beforeunload', warn);
  }, [s.screen]);
  const p = { s, session };
  return (
    <Shell steps={rail(s)} level={level(s)} error={s.screen === 'error'} chip={chip}>
      {s.screen === 'off' ? <Off />
        : s.screen === 'pick' ? <Pick {...p} />
        : s.screen === 'checks' ? <Checks {...p} />
        : s.screen === 'working' ? <Working s={s} />
        : s.screen === 'done' ? <Done {...p} />
        : <Failed {...p} />}
    </Shell>
  );
}
