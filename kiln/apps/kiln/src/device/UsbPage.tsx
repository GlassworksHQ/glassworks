'use client';
// An Android device over USB debugging, the engine's page for a UsbFrame (issue 621; first built for /android, issue
// 403). The screens are the approved mockup's (maintainer, 2026-11-17): the device named or its brand asked, the brand's
// steps to turn on USB debugging, Allow on the device, the install with each grant read back, then the standard flow,
// whose Done carries the frame card or "untested device". Off unless the address has ?apk=release (the newest release
// of the frame's line) or ?apk=local (a file from this computer), or a demo, until its frame.json makes it installable
// (usbOpen). The frame's words, brands and grants come from its data and its code (kiln-core UsbFrame).
import { loadOrCreateAdbKey } from '@glassworks/adb';
import { cap, fmtSize, webUsbDevices, type SilentWords, type UsbApp, type UsbFrame, type UsbGrant, type UsbIdentified, type UsbInstalled, type UsbPhase, type UsbPlace, type UsbSilentUpdates, type UsbSim } from '@glassworks/kiln-core';
import { KIND_TEXT, STAGE_TEXT, type FrameDesign } from '@glassworks/frame-card';
import * as Collapsible from '@radix-ui/react-collapsible';
import * as RadioGroup from '@radix-ui/react-radio-group';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Fragment, createContext, useCallback, useContext, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { wifiSsid } from '@glassworks/app-adb';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { sessionProgress } from './imageSession';
import { standardData, type StandardFrame } from '../standard/frame';
import { StandardSession } from '../standard/session';
import { StandardRoutes } from '../standard/StandardWizard';
import { status, stepOf, type Step } from '../wizard/routes';
import { Browser } from '../wizard/screens';
import type { Entry } from '../wizard/registry';
import { Shell, useBrowserChip } from '../components/Shell';
import { Foot } from '../components/Nav';
import { Hands, Note, Problem, Progress, type ProgressRow } from '../components/Kit';
import { SilentUpdates } from '../components/SilentUpdates';
import { FRAMES, MEASURED, RENDERS } from '../frames/catalog';
import { adbKeyHolder, webUsbAdb } from './DevicePage';
import type { RailMark, RailStep } from '../wizard/state';
import { ReleaseRow } from '../release/ReleaseRow';
import { registry } from '../wizard/registry';
import { keptUsb } from './usbActivity';
import { USB_DEMOS, usbDemo, type UsbDemo } from './usbDemo';
import { ScreenLockBlock } from './ScreenLockBlock';
import { ReportButton } from '../report/ReportDialog';
import { usbPrefill } from '../report/prefill';
import { OnDevicePage } from './onDevice/OnDevicePage';
import { UsbSession, usbMode, usbOpen, type Device, type UsbState } from './usbSession';
import type { ApkFrom } from '../release/apk';
import './usb.css';

/** The frame the screens are for: its words, brands and grants. */
const UsbFrameContext = createContext<UsbFrame | undefined>(undefined);
const useDevice = (): UsbFrame => useContext(UsbFrameContext)!;

const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';
const SITE = 'https://www.glassworks.dev/frames';

/** The release list and Refract, from the page (a bare `fetch` held by an object is an illegal invocation). */
const releaseRoute = (...a: Parameters<typeof fetch>) => fetch(...a);

function realSession(dev: UsbFrame, base: string, on: boolean, from: ApkFrom = 'local', handOver?: (p: Device, installed: UsbInstalled) => void): UsbSession {
  if (!on) return new UsbSession({ frame: dev, findUsb: () => Promise.reject(new Error('off')), connect: () => Promise.reject(new Error('off')) }, false);
  const keys = adbKeyHolder(loadOrCreateAdbKey);
  // the browser's list once, then the picked device again with no list while it refuses the key (adbAsk.ts)
  const adb = webUsbAdb(keys, Number.POSITIVE_INFINITY);
  return new UsbSession({
    frame: dev,
    // no filter: before USB debugging is on, the device shows only as itself (charging or file transfer)
    findUsb: async () => {
      const d = await navigator.usb.requestDevice({ filters: [] });
      return { vendorId: d.vendorId, ...(d.productName ? { productName: d.productName } : {}) };
    },
    connect: () => adb.request(),
    reopen: adb.reopen,
    picked: adb.picked,
    ...(from === 'release' ? { release: releaseRoute } : {}),
    handOver: (p, i) => { handOver?.(p, i); },
    progress: sessionProgress(`kiln:device:${base}:app`),
  }, true);
}

/** Device, Connect, Install (or Undo), Set up, Done: where the person is on them. */
function rail(s: UsbState): RailStep[] {
  const names = s.flow === 'undo' ? ['Device', 'Connect', 'Undo', 'Done'] : ['Device', 'Connect', 'Install', 'Set up', 'Done'];
  const last = s.phases.at(-1);
  const working = s.flow === 'install' && last?.step === 'handoff' ? 3 : 2;
  const at = s.screen === 'done' ? names.length
    : s.screen === 'working' ? working
    : s.screen === 'error' ? (s.phases.length || s.plan ? working : 1)
    : s.screen === 'debug' || s.screen === 'allow' ? 1 : 0;
  const turn = (s.screen === 'allow' && !s.connectError) || (s.screen === 'working' && last?.step === 'home' && !!last.pick);
  return names.map((name, i): RailStep => {
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : turn ? 'turn' : 'now';
    return { name, mark };
  });
}

function level(s: UsbState): number {
  if (s.screen === 'done') return 4;
  if (s.screen === 'working') return s.granted ? 3 : 2;
  return 0;
}

const frameOf = (i?: UsbIdentified): FrameDesign | undefined => (i?.model?.frame ? FRAMES.find((f) => f.id === i.model!.frame) : undefined);

/** What Kiln calls the device: the model it knows, else what adb or USB said, else its brand. */
function deviceName(dev: UsbFrame, s: UsbState): string {
  const d = s.plan?.device;
  return s.identified?.model?.name ?? (d ? `${d.manufacturer} ${d.model}`.trim() : undefined) ?? s.usbName ?? s.identified?.brand?.name ?? dev.short;
}

function Log({ s }: { s: UsbState }) {
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
  const dev = useDevice();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{dev.off.title}</h2>
      <p className="lede">Its installer is still being tested. Follow it on GitHub, or pick another panel.</p>
      <Foot back={null}>
        <a className="btn ghost" href={dev.off.follow}>Follow it on GitHub</a>
        <Link className="btn ghost" href="/">Pick a panel</Link>
      </Foot>
    </section>
  );
}

/** The APK: the newest release's, checked, or (test only) a file from this computer. */
function ApkField({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const load = useCallback(() => { void session.loadRelease(); }, [session]);
  if (s.flow === 'undo') return null;
  if (s.release) return (
    <>
      {!usbOpen(dev) && <span className="row"><span className="pill soon">Test only</span></span>}
      <ReleaseRow r={s.release} line={dev.apk.line} what={dev.app} device={dev.the} apkError={s.apk?.error} load={load} />
      {s.apk?.error && <span className="status err" role="alert">{s.apk.error}.</span>}
    </>
  );
  return (
    <div className="well">
      <div className="grow">
        <span className="row"><span className="pill soon">Test only</span><span className="small">Choose a test APK file.</span></span>
        <label className="field"><span>APK file</span>
          <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void session.pickApk(f.name, f.arrayBuffer().then((b) => new Uint8Array(b))); }} />
        </label>
        {s.apk?.sha256 && <>
          <span className="small">{s.apk.name} · {s.apk.package ?? 'no package'} · {fmtSize(s.apk.size)}</span>
          <span className="small">SHA-256 <code className="sha" title={s.apk.sha256}>{s.apk.sha256.slice(0, 12)}…{s.apk.sha256.slice(-12)}</code></span>
        </>}
        {s.apk?.error && <span className="status err" role="alert">{s.apk.error}.</span>}
      </div>
    </div>
  );
}

const WHAT_KILN_DOES = 'Kiln installs Glassworks, grants the permissions it needs, and sets it as the home screen. Your existing apps and data stay untouched, and Undo removes Glassworks anytime.';

function Find({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{s.flow === 'undo' ? `Plug in ${dev.the} to undo` : `Plug in ${dev.the}`}</h2>
      <p className="lede">{s.flow === 'undo'
        ? `Kiln removes Glassworks over USB: turn on USB debugging, then tap Allow. Kiln checks ${dev.the} first, so nothing changes until then.`
        : `Plug it in with its USB cable (USB debugging can stay off for now). Kiln only identifies ${dev.the} here — nothing on it changes.`}</p>
      <ApkField s={s} session={session} />
      {s.flow === 'install' && <p className="small">Needs {s.release ? dev.needs.release : dev.needs.local} or newer. {WHAT_KILN_DOES}</p>}
      {s.changed && <p className="small">{`An earlier attempt already changed ${dev.the}.`}</p>}
      {s.finding && <p className="status work" role="status">{`Pick ${dev.the} in the browser list…`}</p>}
      {s.findError && <p className="status err" role="alert">{s.findError}</p>}
      <Foot back={{ href: '/' }} busy={s.finding}>
        <span className="links">
          <button type="button" className="btn ghost" onClick={() => session.brands()}>Not in the list? Pick your brand</button>
          {s.flow === 'install'
            ? <button type="button" className="btn ghost" onClick={() => session.setFlow('undo')}>Undo an install</button>
            : <button type="button" className="btn ghost" onClick={() => session.setFlow('install')}>Install instead</button>}
        </span>
        <button type="button" className="btn ember" disabled={s.finding} onClick={() => void session.find()}>Find device</button>
      </Foot>
    </section>
  );
}

function Named({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const i = s.identified!;
  const f = frameOf(i);
  const who = i.model?.name ?? i.brand?.name ?? deviceName(dev, s);
  const lede = f ? `${f.brand ?? i.brand?.name} · ${KIND_TEXT[f.kind].toLowerCase()}.` : i.model ? `${i.brand?.name} · not tested yet.` : 'Kiln recognized the brand, though not the exact model.';
  const blocked = session.blocked();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">This looks like a {who}</h2>
      <p className="lede">{lede} Next, we&apos;ll walk you through turning on USB debugging so Kiln can {s.flow === 'undo' ? 'remove' : 'install'} Glassworks.</p>
      <div className={f ? 'split' : undefined}>
        {f && (
          <div className="fc">
            {RENDERS[f.id] && <img className="ph" src={RENDERS[f.id]} alt="" />}
            <div className="spread"><b>{f.name}</b><span className="pill soon">{STAGE_TEXT[f.status]}</span></div>
            <span className="kind">{KIND_TEXT[f.kind]}</span>
          </div>
        )}
        <div className="small what"><span>What Kiln does</span><span className="muted">{WHAT_KILN_DOES}</span></div>
      </div>
      <ApkField s={s} session={session} />
      {blocked && s.apk && <p className="status err" role="alert">{blocked}</p>}
      <Foot back={{ run: () => session.restart() }}>
        <button type="button" className="btn ghost" onClick={() => session.brands()}>Not right? Pick your brand</button>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => session.toSteps()}>Continue</button>
      </Foot>
    </section>
  );
}


function Brand({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const blocked = session.blocked();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Which brand is it?</h2>
      <p className="lede">{s.identified?.why ?? 'Kiln will show the exact steps to turn on USB debugging for your brand.'}</p>
      <RadioGroup.Root className="brands" value={s.brand ?? ''} onValueChange={(v) => session.pickBrand(v)} aria-labelledby="h">
        {dev.brands.map((b) => (
          <RadioGroup.Item key={b.id} value={b.id} className="brand-pick">{b.name}</RadioGroup.Item>
        ))}
        <RadioGroup.Item value="other" className="brand-pick">Other<small>Android&apos;s general steps</small></RadioGroup.Item>
      </RadioGroup.Root>
      <ApkField s={s} session={session} />
      {blocked && s.apk && <p className="status err" role="alert">{blocked}</p>}
      {!s.brand && <p className="small">Pick one to see its steps.</p>}
      <Foot back={{ run: () => session.back() }}>
        <button type="button" className="btn ember" disabled={!s.brand || !!blocked} onClick={() => session.chooseBrand()}>Continue</button>
      </Foot>
    </section>
  );
}

function Path({ path }: { path: string[] }) {
  return (
    <span className="path">
      {path.map((p, i) => <Fragment key={i}>{i > 0 && <i aria-label="then">›</i>}<span>{p}</span></Fragment>)}
    </span>
  );
}

const SWITCH = 'USB debugging';
/** Where the switch is: the path to the page it is on. */
const pageOf = (p: UsbPlace) => (p.path.at(-1) === SWITCH ? p.path.slice(0, -1) : p.path);

function Debug({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const st = s.identified!.steps;
  const one = st.debugging.length === 1;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Turn on USB debugging</h2>
      <p className="lede">{`Follow these steps on ${dev.the}. Android requires you to turn this on by hand for security.`}</p>
      <ol className="num">
        <li><b>Open</b> <Path path={st.unlock.path} />
          <small>Tap <b>{st.unlock.tap}</b> seven times, until it says you&apos;re a developer.{st.unlock.note && <> {st.unlock.note}</>}</small>
        </li>
        <li><b>Then</b> {one && <Path path={pageOf(st.debugging[0]!)} />}
          {!one && (
            <ul className="when">
              {st.debugging.map((p) => <li key={p.path.join('/')}>{p.when && <span className="small">{p.when}: </span>}<Path path={pageOf(p)} /></li>)}
            </ul>
          )}
          <small>Turn on <b>{SWITCH}</b>.{st.debugging.map((p) => p.note).filter(Boolean).map((n) => ` ${n}`).join('')}</small>
        </li>
      </ol>
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <p className="small">
        Steps from {st.sources.map((src, i) => <span key={src.href}>{i > 0 && ' and '}<a className="link" href={src.href} target="_blank" rel="noreferrer">{src.title.split(':')[0]}</a></span>)}
      </p>
      <Foot back={{ run: () => session.backFromSteps() }}>
        <button type="button" className="btn ghost" onClick={() => session.brands()}>Different on yours?</button>
        <button type="button" className="btn ember" onClick={() => void session.connect()}>USB debugging is on</button>
      </Foot>
    </section>
  );
}

function Allow({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const [help, setHelp] = useState(false);
  return (
    <section className="card" aria-labelledby="h">
      <span className="turn-label"><span className="pulse" />Your turn</span>
      <h2 id="h">{`Tap Allow on ${dev.the}`}</h2>
      <p className="lede">{`${cap(dev.the)} asks whether to trust this computer. Tick “Always allow from this computer”, then tap Allow.`}</p>
      <div className="tab" aria-hidden="true">
        <div className="scr">
          <div className="dlg">
            <b>Allow USB debugging?</b>
            <span>The computer&apos;s RSA key fingerprint is: …</span>
            <span className="cb"><i />Always allow from this computer</span>
            <div className="acts"><span>Cancel</span><span className="ring">Allow</span></div>
          </div>
        </div>
      </div>
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <Collapsible.Root open={help} onOpenChange={setHelp} className="tech">
        <Collapsible.Content>
          <ul className="tips">
            <li>{`Pick ${dev.the} in the browser's list first. It shows once USB debugging is on.`}</li>
            <li>Unplug the cable and plug it in again, then click <b>Try again</b>.</li>
            <li>Turn USB debugging off and on again.</li>
            <li>{`Another tool on this computer may be using ${dev.the}: close other Kiln tabs or Android tools, or run `}<code>adb kill-server</code>.</li>
          </ul>
        </Collapsible.Content>
        {/* Back to the steps once the ask has stopped (a refusal, nothing picked): not while it still waits for the tap */}
        <Foot back={s.connectError ? { run: () => session.backToSteps() } : null} backLabel="Back to the steps">
          <Collapsible.Trigger className="btn ghost">Nothing on the screen?</Collapsible.Trigger>
          {s.connectError
            ? <button type="button" className="btn ember" onClick={() => void session.connect()}>Try again</button>
            : <span className="chip inline"><span className="pulse" />{`Waiting for ${dev.the}`}</span>}
        </Foot>
      </Collapsible.Root>
    </section>
  );
}

type RowId = 'read' | 'apk' | 'grant' | 'start' | 'home' | 'handoff';
/** Each row: as a step still to come (`step`), while it runs (`doing`), and once done (`done`). */
const ROWS: { id: RowId; step: string; doing: string; done: string }[] = [
  { id: 'read', step: 'Read the device', doing: 'Reading device info', done: '' },
  { id: 'apk', step: 'Install Glassworks', doing: 'Installing Glassworks', done: 'Glassworks installed' },
  { id: 'grant', step: 'Give it its permissions', doing: 'Granting permissions', done: 'Permissions granted' },
  { id: 'start', step: 'Start Glassworks', doing: 'Starting Glassworks', done: 'Glassworks started' },
  { id: 'home', step: 'Make it the home screen', doing: 'Setting as home screen', done: 'Set as home screen' },
  { id: 'handoff', step: 'Get set-up ready', doing: 'Preparing setup', done: 'Ready for setup' },
];
const rowOf = (p: UsbPhase): RowId => (p.step === 'updates' || p.step === 'granted' ? 'grant' : p.step);

/** A grant's words, from the frame; one its recipe added since, by its own id. */
const grantText = (dev: UsbFrame, what: string) => dev.grants[what] ?? { name: what, without: `${what} is off` };

function GrantList({ grants, updates }: { grants: UsbGrant[]; updates?: UsbSilentUpdates }) {
  const dev = useDevice();
  const shown = grants.filter((g) => g.result !== 'not-asked' || g.what === 'camera');
  return (
    <div className="grants">
      {shown.map((g) => (
        <div key={g.what}>{grantText(dev, g.what).name}
          {g.result === 'granted' ? <i className="y">On</i> : g.result === 'refused' ? <i className="w" title={g.detail}>Not allowed</i> : <i className="n">Not requested</i>}
        </div>
      ))}
      {updates && updates.state !== 'not-asked' && (
        <div>Updates without a tap
          {updates.state === 'allowed' ? <i className="y">On</i> : updates.state === 'refused' ? <i className="w" title={updates.detail}>Not allowed</i> : <i className="n">{updates.state === 'old-android' ? 'Needs Android 12' : 'Off'}</i>}
        </div>
      )}
    </div>
  );
}

/** What didn't apply, said once, with what it means. [updates]: left out where Done's block says it. */
function Refusals({ grants, updates }: { grants: UsbGrant[]; updates?: UsbSilentUpdates }) {
  const dev = useDevice();
  const lines = grants.filter((g) => g.result === 'refused').map((g) => grantText(dev, g.what).without);
  if (updates?.state === 'refused') lines.push('each update may ask for a tap');
  if (!lines.length) return null;
  const what = grants.filter((g) => g.result === 'refused').map((g) => grantText(dev, g.what).name.toLowerCase());
  if (updates?.state === 'refused') what.push('silent updates');
  return <div className="warnbox" role="status">{`Android on this ${dev.short} didn't allow `}{what.join(', ')}. Glassworks still works without it — {lines.join(', and ')}.</div>;
}

function deviceLine(dev: UsbFrame, s: UsbState): string {
  const d = s.plan!.device;
  const abi = s.plan!.abi ?? d.abis[0] ?? '';
  return `${deviceName(dev, s)}, Android ${d.release || dev.androidName(d.sdk)}${abi ? `, ${abi.includes('64') ? '64' : '32'}-bit` : ''}`;
}

function Working({ s }: { s: UsbState }) {
  const dev = useDevice();
  const last = s.phases.at(-1);
  if (s.flow === 'undo') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Removing Glassworks</h2>
        <p className="lede">Kiln removes Glassworks&apos; screen-lock permission and uninstalls the app.</p>
        <Hands the={dev.the} />
        <div className="bar wait"><i /></div>
        <p className="small">{`Nothing else on ${dev.the} changes.`}</p>
        <Log s={s} />
      </section>
    );
  }
  const at = last ? ROWS.findIndex((r) => r.id === rowOf(last)) : 0;
  const pick = last?.step === 'home' ? last.pick : undefined;
  const grants = s.granted?.grants;
  const now = ROWS[at]!;
  const doing = last?.step === 'apk' && last.stage === 'copy' ? `Copying Glassworks to ${dev.the}` : now.id === 'read' ? `Reading ${dev.the}` : now.doing;
  const done = (r: (typeof ROWS)[number]) => (r.id === 'read' && s.plan ? deviceLine(dev, s) : r.id === 'apk' && s.plan?.installed ? 'Glassworks updated' : r.done);
  return (
    <section className={`card${pick ? ' turn' : ''}`} aria-labelledby="h">
      {pick ? <>
        <span className="turn-label"><span className="pulse" />Your turn</span>
        <h2 id="h">{`One tap on ${dev.the}`}</h2>
        <p className="lede">Android asks which app to use as the home screen. Pick <b className="fg">{pick}</b>, then <b className="fg">Always</b>.</p>
      </> : <>
        <h2 id="h">Installing Glassworks</h2>
        <Hands the={dev.the} />
        <Progress label="Installing Glassworks" rows={ROWS.map((r, i): ProgressRow => ({
          id: r.id,
          label: i < at ? done(r) || r.step : i === at ? doing : r.step,
          state: i < at ? 'done' : i === at ? 'now' : 'next',
        }))} />
      </>}
      {grants && <GrantList grants={grants} updates={s.granted?.silentUpdates} />}
      {grants && <Refusals grants={grants} updates={s.granted?.silentUpdates} />}
      {pick ? <span className="chip inline"><span className="pulse" />Waiting for your tap</span> : <p className="small">{`Nothing else on ${dev.the} changes.`}</p>}
      <Log s={s} />
    </section>
  );
}

function Done({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const r = s.result!;
  if (r.flow === 'undo') {
    return (
      <section className="card" aria-labelledby="h">
        <span className="okmark" aria-hidden="true">✓</span>
        <h2 id="h">{r.removed.length ? `Glassworks is off ${dev.the}` : `Glassworks wasn't on ${dev.the}`}</h2>
        <p className="lede">{r.removed.length ? 'Android opens the home screen it had, or asks which one to use.' : 'Nothing was changed.'} Unplug it when you like.</p>
        <Foot back={null}><Link className="btn ghost" href="/">Back to Kiln</Link></Foot>
        <Log s={s} />
      </section>
    );
  }
  const { installed } = r;
  const f = frameOf(s.identified);
  const name = deviceName(dev, s);
  const home = installed.home.state === 'set';
  const page = installed.handoff.setupPage;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">You're all set</h2>
      <p className="lede">
        {home && f ? `Glassworks is now the home screen on your ${name}.` : `Glassworks is installed on your ${name}.`}
        {!home && ` Kiln couldn't confirm the home screen automatically. In Settings on ${dev.the}, open Default apps → Home app, and choose Glassworks.`}
      </p>
      {f ? (
        <div className="fc">
          <div className="spread"><b>{f.name}</b>{!MEASURED.has(f.id) && <span className="ctag">Clarity · not measured yet</span>}</div>
          <span className="kind">{KIND_TEXT[f.kind]} · <a className="link" href={`${SITE}/${f.id}`}>Its page on glassworks.dev</a> · <ReportButton prefill={usbPrefill(dev, s, 'done')} /></span>
        </div>
      ) : (
        <div className="fc">
          <b>Untested device</b>
          <span className="small">Nobody has run Glassworks on this model yet. <ReportButton prefill={usbPrefill(dev, s, 'done')} /></span>
        </div>
      )}
      <SilentUpdates words={dev.silent(installed.silentUpdates, s.plan!.device.release || dev.androidName(s.plan!.device.sdk))} />
      <ScreenLockBlock lock={installed.screenLock} words={dev.screenLock} />
      <Refusals grants={installed.grants} />
      {page ? <>
        <div className="well">
          <span className="grow muted">Finish setting up on your network</span>
          <a className="btn ember" href={page} target="_blank" rel="noreferrer">Open setup</a>
        </div>
        <span className="small">{page} · unplug when you like</span>
      </> : (
        <p className="small">{installed.handoff.why}{` Finish setting up on ${dev.the}: Glassworks shows its setup screen.`}</p>
      )}
      <Foot back={null}>
        <button type="button" className="btn ghost" onClick={() => void session.undoFromDone()}>Undo</button>
        <span />
      </Foot>
      <Log s={s} />
    </section>
  );
}

function Failed({ s, session }: { s: UsbState; session: UsbSession }) {
  if (s.own) {
    const article = /^[aeiou]/i.test(s.own.name) ? 'an' : 'a';
    return (
      <section className="card err" aria-labelledby="h">
        <h2 id="h">This is {article} {s.own.name}</h2>
        <p className="lede">It has its own dedicated installer in Kiln. Installing Glassworks for Android here would replace its built-in app, so Kiln stopped before changing anything.</p>
        <Foot back={null}>
          <Link className="btn ember" href={s.own.route}>Go to the {s.own.name}</Link>
        </Foot>
        <Log s={s} />
      </section>
    );
  }
  return <Stopped s={s} session={session} />;
}

/** The run stopped. Try again reconnects if the device went away; a refused APK can be swapped here, keeping the device. */
function Stopped({ s, session }: { s: UsbState; session: UsbSession }) {
  const dev = useDevice();
  const [repick, setRepick] = useState(false);
  const blocked = s.flow === 'install' ? session.blocked() : undefined;
  const untouched = /Nothing was changed\.$/.test(s.error ?? '');
  return (
    <Problem title={s.flow === 'undo' ? 'Undo didn\'t finish' : untouched ? `Kiln can't install on this ${dev.short}` : 'The install stopped'}
      fix={untouched ? undefined : 'Check the cable and the device, then click Try again.'}
      details={<><pre className="raw">{s.error}</pre><pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre></>}>
      {untouched && <p className="lede">{s.error}</p>}
      {repick && <ApkField s={s} session={session} />}
      {repick && blocked && s.apk && <Note tone="bad">{blocked}</Note>}
      <Foot back={null}>
        <span className="links">
          {s.flow === 'install' && <ReportButton prefill={usbPrefill(dev, s, 'stopped')}>Tell us what happened</ReportButton>}
          {s.flow === 'install' && !s.release && !repick && <button type="button" className="btn ghost" onClick={() => setRepick(true)}>Pick another APK</button>}
        </span>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => void session.connect()}>Try again</button>
      </Foot>
    </Problem>
  );
}

/** `base`: the page's route, whose step routes the address follows (installer.json); none in tests. */
export function Screens({ session, base }: { session: UsbSession; base?: string }) {
  const s = useSyncExternalStore(session.subscribe, session.get, session.get);
  const dev = session.frame;
  const route = base === undefined || s.screen === 'off' ? undefined : installRoute(dev.id, s);
  useEffect(() => {
    if (route === undefined || base === undefined) return;
    const want = `${base}${route ? `/${route}` : ''}`;
    if (window.location.pathname.replace(/\/+$/, '') !== want) window.history.replaceState(null, '', `${want}${window.location.search}`);
  }, [base, route]);
  const chip = useBrowserChip();
  const turn = rail(s).some((x) => x.mark === 'turn');
  useEffect(() => { document.title = `${turn ? 'Your turn · ' : ''}${dev.name} · Kiln`; }, [turn, dev]);
  const p = { s, session };
  return (
    <UsbFrameContext.Provider value={dev}>
      <Shell steps={rail(s)} level={level(s)} error={s.screen === 'error'} chip={chip}>
        {chip?.kind === 'bad' ? <Browser />
          : s.screen === 'off' ? <Off />
          : s.screen === 'find' ? <Find {...p} />
          : s.screen === 'named' ? <Named {...p} />
          : s.screen === 'brand' ? <Brand {...p} />
          : s.screen === 'debug' ? <Debug {...p} />
          : s.screen === 'allow' ? <Allow {...p} />
          : s.screen === 'working' ? <Working s={s} />
          : s.screen === 'done' ? <Done {...p} />
          : <Failed {...p} />}
      </Shell>
    </UsbFrameContext.Provider>
  );
}

/** The app the last install put on, kept for the tab: a reload at a set-up step talks to the right one. */
const appKey = (base: string) => `kiln:standard:${base}:app`;
const keptApp = (dev: UsbFrame, base: string): UsbApp => {
  try { const pkg = sessionStorage.getItem(appKey(base)); return dev.apps.find((a) => a.package === pkg) ?? dev.apps[0]!; } catch { return dev.apps[0]!; }
};

/**
 * The device as the standard flow reads it (issue 621): the same steps on the same screens as every frame after its
 * install. `installed`: what the install found, for Done's Silent updates; without it (a reload at a set-up step), the
 * frame's words for an update setting Kiln didn't read.
 */
export function usbStandard(dev: UsbFrame, app: UsbApp, installed?: UsbInstalled, model?: string): StandardFrame {
  const release = installed ? installed.plan.device.release || dev.androidName(installed.plan.device.sdk) : '';
  return {
    id: dev.id, name: dev.name, short: dev.short, the: dev.the, app: app.name, appId: app,
    connect: { ...dev.connect, pick: model ?? dev.connect.pick },
    credits: {},
    silent: { install: dev.silent(installed?.silentUpdates, release) ?? dev.silent(undefined, release)! },
    wifiName: (p, signal) => wifiSsid(p, signal),
    updates: async () => ({ state: 'install', log: 'silent updates: as the install read them back' }),
    ...standardData(dev.id),
  };
}

/** The device's standard flow, as Kiln's registry entry for its page. `device`: a demo's simulated device. */
function standardEntry(f: StandardFrame, base: string, o: { demo?: string; device?: Device } = {}): Entry {
  const device = o.device;
  const session = new StandardSession({
    standard: f,
    devices: device ? { knownAdb: async () => device, requestAdb: async () => device } : webUsbDevices,
    loadKey: device ? async () => ({ publicKey: 'QAAAAdemo kiln@demo' }) as never : loadOrCreateAdbKey,
    ...(device ? { simulated: true, sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, Math.min(ms, 800))) } : {}),
  });
  return { base, name: f.name, session, demo: o.demo, standard: true };
}

/** The install's step routes from the frame's installer.json flow (`find`, `connect`, `install`). */
function installRoute(id: string, s: UsbState): string | undefined {
  const steps = (Object.values(INSTALLERS[id]?.flows ?? {})[0]?.steps ?? []) as { id: string; route?: string }[];
  const step = s.screen === 'working' || (s.screen === 'error' && s.phases.length) ? 'install' : s.screen === 'debug' || s.screen === 'allow' ? 'connect' : 'find';
  return steps.find((x) => x.id === step)?.route;
}

/** What the install left to say on the standard flow's Done: the frame card, the screen lock, what didn't apply, the home screen. */
function UsbOutcome({ session }: { session: UsbSession }) {
  const s = useSyncExternalStore(session.subscribe, session.get, session.get);
  const dev = session.frame;
  const r = s.result;
  if (r?.flow !== 'install') return null;
  const { installed } = r;
  const f = frameOf(s.identified);
  return (
    <UsbFrameContext.Provider value={dev}>
      {installed.home.state !== 'set' && <p className="small" role="alert">{`Kiln couldn't confirm Glassworks as the home screen. In ${dev.the}'s Settings, find Default apps, then Home app, and choose Glassworks.`}</p>}
      {f ? (
        <div className="fc">
          <div className="spread"><b>{f.name}</b>{!MEASURED.has(f.id) && <span className="ctag">Clarity · not measured yet</span>}</div>
          <span className="kind">{KIND_TEXT[f.kind]} · <a className="link" href={`${SITE}/${f.id}`}>Its page on glassworks.dev</a> · <ReportButton prefill={usbPrefill(dev, s, 'done')} /></span>
        </div>
      ) : (
        <div className="fc">
          <b>Untested device</b>
          <span className="small">Nobody has run Glassworks on this model yet. <ReportButton prefill={usbPrefill(dev, s, 'done')} /></span>
        </div>
      )}
      <ScreenLockBlock lock={installed.screenLock} words={dev.screenLock} />
      <Refusals grants={installed.grants} />
    </UsbFrameContext.Provider>
  );
}

type UsbHandOver = (p: Device, installed: UsbInstalled) => StandardSession | undefined;
/** The page up now for each base: sessions kept in the registry reach today's, not the mount that made them. */
const HANDS = new Map<string, UsbHandOver>();
/** An install that ended while no page of its frame was up: its page takes it when it opens (as DevicePage's PENDING). */
const PENDING = new Map<string, { p: Device; installed: UsbInstalled }>();

/**
 * The hand-over a frame's session is made with: its page's, while one is up, or else held for it. Never another frame's
 * standard session replaced by an install that ended in the background.
 */
export const usbHandOver = (base: string): UsbHandOver => (p, installed) => {
  const page = HANDS.get(base);
  if (page) return page(p, installed);
  PENDING.set(base, { p, installed });
  return undefined;
};

const STANDARD_STEPS: readonly Step[] = ['set-up', 'resume', 'wifi', 'home-assistant', 'panes', 'radio', 'done'];
const noSubscribe = () => () => {};
const noState = () => undefined as never;

/** The page for a UsbFrame at `base` (its installer.json route): its install, then the standard flow. */
export function UsbPage({ frame: dev, demo: sim, base }: { frame: UsbFrame; demo?: { sim: UsbSim }; base: string }) {
  const path = usePathname() ?? '';
  const [start] = useState(() => {
    const m = usbMode(location.search, DEMO_ON && !!sim, USB_DEMOS, usbOpen(dev));
    if (m.demo) return { m, demo: m.demo as UsbDemo, search: location.search };
    // a running install outlives the picker (wizard/registry.ts), so a link back without ?apk=… finds it
    if (!m.on && !registry.activity(base)) return { m, search: location.search, real: realSession(dev, base, false) };
    // a session kept from an earlier visit hands over through whichever page is up then (usbHandOver), not the one that made it
    return { m, search: location.search, real: keptUsb(base, dev.name, () => realSession(dev, base, true, m.apk, usbHandOver(base)), m.apk).session };
  });
  const handRef = useRef<UsbHandOver | undefined>(undefined);
  // registered while this page is up: gone when it goes, so an install that ends meanwhile waits for it (PENDING)
  const mine = useRef<UsbHandOver>((p, installed) => handRef.current?.(p, installed));
  HANDS.set(base, mine.current);
  useEffect(() => {
    HANDS.set(base, mine.current);
    const waiting = PENDING.get(base);
    if (waiting) { PENDING.delete(base); handRef.current?.(waiting.p, waiting.installed); }
    return () => { if (HANDS.get(base) === mine.current) HANDS.delete(base); };
  }, [base]);
  handRef.current = (p, installed) => {
    try { sessionStorage.setItem(appKey(base), installed.plan.app.package); } catch { /* storage off: a reload talks to the frame's first app */ }
    const e = registry.set(standardEntry(usbStandard(dev, installed.plan.app, installed, installed.plan.device.model), base, { demo: start.demo, device: start.demo ? p : undefined }));
    e.handed = true;
    void e.session.handOver(p);
    return e.session;
  };
  const [demo, setDemo] = useState<UsbSession>();
  useEffect(() => { if (start.demo && sim) void usbDemo(dev, sim.sim, start.demo, false, (p, i) => handRef.current?.(p, i)).then(setDemo); }, [start, dev, sim]);
  const current = useCallback(() => { const e = registry.get(); return e?.base === base && e.standard ? e : undefined; }, [base]);
  const entry = useSyncExternalStore(registry.subscribe, current, current);
  const ws = useSyncExternalStore(entry?.session.store.subscribe ?? noSubscribe, entry?.session.store.get ?? noState, entry?.session.store.get ?? noState);
  const step = path.startsWith(base) ? stepOf(path.slice(base.length)) : undefined;
  const onStandard = !!step && STANDARD_STEPS.includes(step);
  // a set-up step opened with no session for it (a reload, a link): a new one, which connects to the device
  useEffect(() => { if (onStandard && !current() && !start.demo) registry.set(standardEntry(usbStandard(dev, keptApp(dev, base)), base)); }, [onStandard, current, start, dev, base]);
  const renew = useCallback(() => registry.set(standardEntry(usbStandard(dev, keptApp(dev, base)), base)), [dev, base]);
  const live = start.real ?? demo;
  if (entry && (onStandard || (entry.handed && status(ws) !== 'finished'))) {
    return <StandardRoutes entry={entry} renew={renew} before={['Device', 'Connect', 'Install']} outcome={live ? <UsbOutcome session={live} /> : undefined} />;
  }
  if (onStandard) return null;
  return live ? <Screens session={live} base={base} /> : null;
}

/** A UsbFrame's routes: the install from the device's own browser at its `onDevice` route, the USB page everywhere else. */
export function UsbRoutes(p: { frame: UsbFrame; demo?: { sim: UsbSim }; base: string }) {
  const path = (usePathname() ?? '').replace(/\/+$/, '');
  const od = p.frame.onDevice;
  if (od && path === `${p.base}/${od.route}`) return <OnDevicePage frame={{ ...p.frame, onDevice: od }} sim={p.demo?.sim} base={p.base} />;
  return <UsbPage {...p} />;
}
