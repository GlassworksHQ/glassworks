'use client';
// The wizard's screens (docs/design/kiln): one idea each, a short heading, at most one line under
// it, one ember button. They read the state and call the session; none keeps hardware state.
import * as Collapsible from '@radix-ui/react-collapsible';
import * as Dialog from '@radix-ui/react-dialog';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { paneById } from '@glassworks/frame-card';
import { fmtSize } from '@glassworks/kiln-core';
import Link from 'next/link';
import { BuiltOn, stepCredits } from '../components/Credit';
import { SilentUpdates } from '../components/SilentUpdates';
import { StarOnGitHub } from '../components/StarOnGitHub';
import { LastRun } from '../components/LastRun';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Recovery } from '../recovery/Recovery';
import type { AppPhase, AskCopy, AskKind, KilnFrame } from '@glassworks/kiln-core';
import type { StandardFrame } from '../standard/frame';
import type { StandardSession } from '../standard/session';
import type { Session } from './session';
import { HomeAssistant, Panes, Radio, RadioNext, Wifi } from '../setup/steps';
import { eta, firingSteps, type Setup, type WizardState } from './state';
import x from './screens.module.css';
import { Foot } from '../components/Nav';
import { BrowserAsk, Hands, Instructions, Note, Progress, Usually, Wait, type ProgressRow } from '../components/Kit';
import { rich } from '../device/rich';

type P = { s: WizardState; session: Session; frame: KilnFrame };
/** The standard flow's screens (every frame's steps after its install): any frame's session, the frame as the flow reads it. */
type SP = { s: WizardState; session: StandardSession; frame: StandardFrame };

/** This computer's system, for a frame's one-time USB setup: Windows, Linux, or another that needs none (macOS, Android). */
export function hostOs(): 'win' | 'linux' | 'other' {
  const ua = navigator.userAgent;
  return /Android/.test(ua) ? 'other' : /Windows/.test(ua) ? 'win' : /Linux/.test(ua) ? 'linux' : 'other';
}

/**
 * 2 · Connect: the drawing, and beside it the frame's numbered steps to tick off (its `recoverySteps`), so both fit the
 * first screen. The picker opens inside the click.
 */
export function Connect({ s, session, frame }: P) {
  const driver = useMemo(() => { const o = hostOs(); return o === 'other' ? undefined : frame.drivers[o]; }, [frame]);
  const steps = frame.recoverySteps;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Put the panel in recovery mode</h2>
      <p className="lede">{s.flow === 'undo'
        ? 'Put the panel into recovery mode so Kiln can restore your saved backup.'
        : `Nothing changes on ${frame.the} yet. Kiln backs up your original software first, before writing anything.`}</p>
      {/* how long the whole install takes, from the frame's words, in the same line as a choice card's */}
      {s.flow !== 'undo' && <Usually time={frame.time?.total} />}
      {steps?.length
        ? <div className="howto">
            <div className="howto-art"><Recovery data={frame.recovery} /></div>
            <Instructions steps={steps.map((x) => rich(x))} label="Recovery mode, by hand" />
          </div>
        : <Recovery data={frame.recovery} />}
      {s.flow !== 'undo' && s.changed && <Note tone="info">{s.changed === 'unknown' ? 'Kiln can’t tell whether an earlier attempt in this tab changed the panel.' : 'An earlier attempt already changed the panel.'}</Note>}
      <BrowserAsk listed={frame.felPick} short={frame.short} the={frame.the ?? `the ${frame.short}`}>
        It shows a list of devices. Pick <code>{frame.felPick}</code>, which is your panel in recovery mode, then click Connect.
      </BrowserAsk>
      {driver && <p className="small">{driver} <a className="link" href={frame.help.drivers} target="_blank" rel="noopener">Setup guide</a></p>}
      <LastRun />
      <BuiltOn credits={stepCredits(frame.id, frame.credits, s.flow === 'undo' ? ['boot'] : undefined)} />
      <Foot busy={!!s.busy}>
        <span className="links">
          <Dialog.Root>
            <Dialog.Trigger className="btn ghost">Show me a photo</Dialog.Trigger>
            <Dialog.Portal>
              <Dialog.Overlay className="overlay" />
              <Dialog.Content className="sheet" style={{ width: 'min(640px, calc(100vw - 32px))' }}>
                <Dialog.Title asChild><h3>The recovery button</h3></Dialog.Title>
                <img className="photo" src={frame.photo.src} width={frame.photo.width} height={frame.photo.height} alt={frame.photoAlt} />
                <Dialog.Description>Look for {frame.recovery.button?.what}, rather than the reset button on the outside of the case.</Dialog.Description>
                <Dialog.Close className="btn ghost">Close</Dialog.Close>
              </Dialog.Content>
            </Dialog.Portal>
          </Dialog.Root>
          {/* an earlier attempt changed the panel (a reload mid-write): going back is as near as installing again */}
          {s.flow === 'install' && s.changed && s.changed !== 'unknown' && <button type="button" className="btn ghost" disabled={!!s.busy} onClick={() => void session.click('undo', () => session.openUndo())}>Undo with your backup</button>}
        </span>
        <button type="button" className="btn ember" disabled={!!s.busy} onClick={() => void session.click('connect', () => session.connectFel())}>Connect</button>
      </Foot>
    </section>
  );
}

/** 3 · Your turn: a sheet over the step when the browser needs a click. */
export function YourTurn({ s, session, asks, the = 'the panel' }: { s: WizardState; session: StandardSession; asks: Partial<Record<AskKind, AskCopy>>; the?: string }) {
  const ask = s.ask;
  const copy = ask && asks[ask.kind];
  return (
    <Dialog.Root open={!!ask}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay" />
        <Dialog.Content className="sheet" onEscapeKeyDown={(e) => e.preventDefault()} onPointerDownOutside={(e) => e.preventDefault()}>
          {copy && <>
            <span className="label"><span className="pulse" />Your turn</span>
            <Dialog.Title asChild><h3>{copy.title}</h3></Dialog.Title>
            <Dialog.Description>{copy.body} Your browser asks for a fresh confirmation whenever a USB device switches modes, so pick <code>{copy.pick}</code> in the list.</Dialog.Description>
            <button type="button" className="btn ember pulsing" disabled={ask.busy} onClick={() => void session.asks.click()}>{`Choose ${the}`}</button>
            {ask.busy && <p className="status work" role="status">{`Select ${the} in the browser list…`}</p>}
            {ask.error && <p className="status err" role="alert">{ask.error}</p>}
            {/* set-up's connect only: after a reload with the device off, the person isn't stuck in the sheet */}
            {ask.kind === 'connect' && !ask.busy && session.asks.declinable() && <button type="button" className="link" onClick={() => session.asks.decline()}>Not now</button>}
          </>}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function TechLog({ s }: { s: WizardState }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
      <Collapsible.Trigger className="link">{open ? 'Hide technical log' : 'Technical log'}</Collapsible.Trigger>
      <Collapsible.Content className="tech">
        {s.parts.length > 0 && (
          <div className="parts">
            {s.parts.map(([p, x]) => (
              <span key={p} style={{ display: 'contents' }}>
                <code>{p}</code>
                <span className="small">{x.size ? fmtSize(x.size) : ''}</span>
                <span className="h">{x.sha256 ?? (x.state === 'cur' && x.pct !== undefined ? `${Math.round(x.pct * 100)}%` : x.state === 'pend' ? '–' : '')}</span>
              </span>
            ))}
          </div>
        )}
        <pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre>
      </Collapsible.Content>
    </Collapsible.Root>
  );
}

/** 4 · Install: the firing. Every step with its mark and its word, what the person does (nothing), the log on request. */
export function Firing({ s, frame }: P) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const steps = firingSteps(s, frame.short);
  const at = steps.findIndex((m) => !m.done);
  const undo = s.flow === 'undo';
  // how long a step usually takes, where the frame's words say: the restart is the install's first boot
  const time = (m: (typeof steps)[number]) => (!undo && m.step ? frame.time?.steps?.[m.step] : undefined);
  const rows: ProgressRow[] = steps.map((m, i) => ({ id: m.text, label: m.text, state: m.done ? 'done' : i === at ? 'now' : 'next', ...(time(m) ? { time: time(m) } : {}) }));
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{undo ? 'Putting your original software back' : 'Installing Glassworks'}</h2>
      <Hands the={frame.the ?? `the ${frame.short}`} />
      <Progress rows={rows} label={s.progress.title} aside={eta(s, now)} />
      {/* what keeps the person safe while it writes (issue 672): the backup comes first, and every write is read back */}
      <Note tone={!undo && (s.progress.phase === 'boot' || s.progress.phase === 'backup') ? 'info' : 'ok'}>{undo ? 'Kiln reads every write back to check it.'
        : s.progress.phase === 'boot' || s.progress.phase === 'backup' ? `Kiln backs up the ${frame.short} before it writes anything.`
        : 'Your backup is saved in this browser. Kiln reads every write back to check it.'}</Note>
      <BuiltOn credits={stepCredits(frame.id, frame.credits, undo ? ['boot'] : undefined)} />
      <TechLog s={s} />
    </section>
  );
}

const zones = () => (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
const LOCALES = ['en-US', 'en-GB', 'en-AU', 'en-CA', 'de-DE', 'fr-FR', 'fr-CA', 'es-ES', 'es-US', 'es-MX', 'it-IT', 'nl-NL', 'pt-PT', 'pt-BR',
  'pl-PL', 'cs-CZ', 'sv-SE', 'da-DK', 'nb-NO', 'fi-FI', 'ru-RU', 'uk-UA', 'tr-TR', 'el-GR', 'hu-HU', 'ro-RO', 'ja-JP', 'ko-KR', 'zh-CN', 'zh-TW', 'zh-HK'];

/**
 * The app going in, as the panel reports it (provision's AppPhase): one row per APK the recipe
 * installs, with its copy and its `pm install`, and the steps between. Android stops and restarts
 * Glassworks while it installs and optimises it, and again on its first start: say so, so a restarting
 * panel reads as progress.
 */
type Row = 'kiosk' | 'home' | 'keyboard' | 'prefs' | 'start';
const ROWS: { id: Row; text: (app: string) => string }[] = [
  { id: 'kiosk', text: (app) => `Install ${app}` },
  { id: 'home', text: () => 'Set it as the home screen' },
  { id: 'keyboard', text: () => 'Install the on-screen keyboard' },
  { id: 'prefs', text: () => 'Apply your language, time zone, and clock' },
  { id: 'start', text: () => 'Start Glassworks' },
];
const rowOf = (p: AppPhase): Row => (p.step === 'apk' ? (p.apk as Row) : p.step);

/**
 * `update`: the app was on the panel already. Then `pm install -r` stops it while it runs, it comes
 * back, and the end of dex2oat restarts it again (web-installer.md §7.1 in the frame's research). A first
 * install has no app running, so nothing there is said to restart.
 */
function nowSays(p: AppPhase, update: boolean): { sub?: string; note?: string } {
  if (p.step === 'apk' && p.stage === 'copy') return { sub: 'Copying the app to the panel' };
  if (p.step === 'apk' && p.apk === 'kiosk') {
    return { sub: 'Android is installing and optimizing it for this panel', note: update ? 'The app restarts briefly while it updates — that\'s normal.' : undefined };
  }
  if (p.step === 'apk') return { sub: 'Android is installing it' };
  return {};
}

function Installing({ frame, setup }: { frame: Pick<StandardFrame, 'app'>; setup: Extract<Setup, { phase: 'settings' }> }) {
  const seen = setup.installing ?? [];
  const now = seen.at(-1);
  const reached = new Set(seen.map(rowOf));
  const cur = now ? ROWS.findIndex((r) => r.id === rowOf(now)) : 0;
  // the keyboard row while it is still to come, if asked for; one passed without an install is left out. Applying the
  // settings to an app already on (`apply`) has only their row.
  const rows = setup.apply ? ROWS.filter((r) => r.id === 'prefs')
    : ROWS.filter((r, i) => r.id !== 'keyboard' || reached.has('keyboard') || (setup.chosen?.keyboard !== false && i > cur));
  const at = rows.findIndex((r) => r.id === ROWS[cur]?.id);
  const { sub, note } = now ? nowSays(now, setup.update) : {};
  // the list changes only its marks, which a live region doesn't read: say the step in words
  const says = [rows[at]?.text(frame.app), sub, note].filter(Boolean).join('. ').replace(/\.\./g, '.');
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{setup.apply ? 'Language and time' : setup.update ? `Updating ${frame.app}` : `Installing ${frame.app}`}</h2>
      <Hands the="the panel">Keep the panel plugged in while this finishes. It usually takes about a minute.</Hands>
      <Progress live={false} label={setup.apply ? 'Language and time' : `Installing ${frame.app}`}
        rows={rows.map((r, i): ProgressRow => ({ id: r.id, label: r.text(frame.app), state: i < at ? 'done' : i === at ? 'now' : 'next', ...(i === at && sub ? { note: sub } : {}) }))} />
      {note && <p className="small" aria-hidden="true">{note}</p>}
      <p className="visually-hidden" role="status">{says}</p>
    </section>
  );
}

/**
 * Language, time zone and clock, defaulting to this computer's; then the app goes in, or (`apply`, an app the install
 * put on) they go to it. The on-screen keyboard only on a frame with touch, and only where the step installs.
 */
function Settings({ s, session, frame, setup }: SP & { setup: Extract<Setup, { phase: 'settings' }> }) {
  const here = typeof navigator === 'undefined' ? 'en-US' : navigator.language || 'en-US';
  const locales = useMemo(() => {
    const tags = [...LOCALES];
    if (/^[a-z]{2,3}-[A-Z]{2}$/.test(here) && !tags.includes(here)) tags.push(here);
    const names = new Intl.DisplayNames([here], { type: 'language' });
    return tags.map((t) => ({ t, n: names.of(t) ?? t })).sort((a, b) => a.n.localeCompare(b.n, here));
  }, [here]);
  const tzHere = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const tzs = useMemo(zones, []);
  const was = setup.chosen;
  const [locale, setLocale] = useState(() => was ? was.locale ?? '' : (LOCALES.includes(here) ? here : LOCALES.find((t) => t.split('-')[0] === here.split('-')[0]) ?? ''));
  const [tz, setTz] = useState(was ? was.timezone ?? '' : tzs.includes(tzHere) ? tzHere : '');
  const [clock, setClock] = useState(was ? (was.clock24 ? '24' : '12') : new Intl.DateTimeFormat(here, { hour: 'numeric' }).resolvedOptions().hour12 ? '12' : '24');
  const [keyboard, setKeyboard] = useState(was?.keyboard ?? frame.touch);
  const busy = !!setup.installing;
  // kept as it changes, so going to another set-up step and back doesn't lose an edit not yet installed
  useEffect(() => {
    session.keepSettings({ locale: locale || undefined, timezone: tz || undefined, clock24: clock === '24', keyboard });
  }, [session, locale, tz, clock, keyboard]);
  if (setup.installing) return <Installing frame={frame} setup={setup} />;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{setup.update && !setup.apply ? `Update ${frame.app}` : 'Language and time'}</h2>
      <p className="lede">{setup.update && !setup.apply
        ? 'Your panel\'s Wi-Fi and Home Assistant setup stay intact. Review language and time below (or choose “Leave as it is”), then install the update.'
        : 'These start from your computer\'s settings, and you can change them anytime later.'}</p>
      <div className="fields">
        <label className="field"><span>Language</span>
          <select className="select" value={locale} onChange={(e) => setLocale(e.target.value)} disabled={busy}>
            <option value="">Leave as it is</option>
            {locales.map(({ t, n }) => <option key={t} value={t}>{n} ({t})</option>)}
          </select>
        </label>
        <label className="field"><span>Time zone</span>
          <select className="select" value={tz} onChange={(e) => setTz(e.target.value)} disabled={busy}>
            <option value="">Leave as it is</option>
            {tzs.map((z) => <option key={z} value={z}>{z.replace(/_/g, ' ')}</option>)}
          </select>
        </label>
      </div>
      <div className="spread">
        <RadioGroup.Root className="seg" value={clock} onValueChange={setClock} aria-label="Clock" disabled={busy}>
          <RadioGroup.Item value="12">12-hour</RadioGroup.Item>
          <RadioGroup.Item value="24">24-hour</RadioGroup.Item>
        </RadioGroup.Root>
        {frame.touch && !setup.apply && <label className="check"><input type="checkbox" checked={keyboard} onChange={(e) => setKeyboard(e.target.checked)} disabled={busy} /> On-screen keyboard</label>}
      </div>
      {setup.error && <p className="status err" role="alert">{setup.error}</p>}
      <Foot>
        {setup.apply ? <span /> : <span className="small">Takes about a minute.</span>}
        <button type="button" className="btn ember" disabled={busy || !!s.busy}
          onClick={() => void session.click('install-app', () => session.installApp({ locale: locale || undefined, timezone: tz || undefined, clock24: clock === '24', keyboard }))}>
          {setup.apply ? 'Continue' : setup.update ? 'Update and continue' : `Install ${frame.app}`}
        </button>
      </Foot>
    </section>
  );
}

export function Waiting({ setup }: { setup: Extract<Setup, { phase: 'waiting' }> }) {
  const connecting = /^Connecting\b/.test(setup.note);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{connecting ? 'Connecting to the panel' : 'Starting the panel'}</h2>
      <Wait what={setup.note} typical={setup.typical} note="Nothing to do. Kiln goes on by itself when it’s ready." />
    </section>
  );
}

/** A pane's icon from the pane catalog (packages/frame-card: each pane's icon.svg). */
const paneIcon = (id: string) => paneById(id)?.icon;

/** The set-up step, one phase at a time. */
export function SetUp(p: SP) {
  const setup = p.s.setup;
  switch (setup.phase) {
    case 'waiting': return <Waiting setup={setup} />;
    case 'settings': return <Settings {...p} setup={setup} />;
    case 'wifi': return <Wifi busy={!!p.s.busy} steps={p.session} setup={setup} />;
    case 'ha': return <HomeAssistant key="ha" busy={!!p.s.busy} steps={p.session} setup={setup} />;
    case 'panes': return <Panes busy={!!p.s.busy} steps={p.session} setup={setup} icons={paneIcon} />;
    case 'radio': return <Radio key={setup.radio.mode} busy={!!p.s.busy} steps={p.session} setup={setup} />;
    case 'radio-next': return <RadioNext busy={!!p.s.busy} steps={p.session} setup={setup} />;
  }
}

/**
 * On Done: the device's Manage page (`http://<address>:8099/`, the app's own page once it is set up), where set-up's
 * choices can change later. It asks for a code the device shows. `the`: how a sentence names the device (a DeviceFrame `the`).
 */
export function ManagePageRow({ href, the }: { href: string; the: string }) {
  return (
    <div className="well">
      <span className="grow"><span>{`You can change any of these later on the Manage page, using a 6-digit code shown on ${the}.`}</span><span className="small"><code>{href}</code></span></span>
      <a className="btn ghost" href={href} target="_blank" rel="noopener">Open</a>
    </div>
  );
}

/**
 * 7 · Done: in one line. The backup is one row whose download is the ember action; undo is a link. `children`: what a
 * frame's own install left to say (a device frame's page), under the standard flow's lines.
 */
export function Done({ s, session, frame, children }: SP & { children?: ReactNode }) {
  const d = s.done!;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{d.title}</h2>
      {/* with a backup, two steps in order (issue 672): keep the backup first, then Home Assistant */}
      {d.backup ? <>
        <div className={x.step} role="group" aria-labelledby="backup-h">
          <h3 id="backup-h">First, save your backup</h3>
          <div className="well">
            <span className="grow"><span>{d.backup.hint}</span><span className="small">{d.backup.name} · {d.backup.info}</span></span>
            <button type="button" className="btn ember" onClick={() => void session.downloadBackup()}>Download</button>
          </div>
          {d.backup.error && <p className="status err" role="alert">{d.backup.error}</p>}
        </div>
        <div className={x.step} role="group" aria-labelledby="next-h">
          <h3 id="next-h">Then, in Home Assistant</h3>
          <p className="lede">{d.body}</p>
        </div>
      </> : <p className="lede">{d.body}</p>}
      {d.updates && <SilentUpdates words={d.updates} />}
      {children}
      {d.managePage && <ManagePageRow href={d.managePage} the={`the ${frame.short}`} />}
      {s.flow === 'install' && <BuiltOn credits={stepCredits(frame.id, frame.credits)} />}
      {s.flow === 'install' && <StarOnGitHub />}
      <Foot back={null}>
        {s.flow === 'install' && session.canUndo ? <button type="button" className="btn ghost" onClick={() => void session.undo()}>Undo</button> : <span />}
        <Link className={`btn ${d.backup ? 'ghost' : 'ember'}`} href="/">Back to Kiln</Link>
      </Foot>
    </section>
  );
}

/** 8 · Undo: how far back, as two choices. It asks for a file only if this browser has no backup. */
export function Undo({ s, session, frame }: P) {
  const u = s.undo;
  const file = useRef<HTMLInputElement>(null);
  const keep = u.depth === 'keep';
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Restore original software</h2>
      <p className="lede">Kiln uses your saved backup to put the original software back, and checks every write along the way.</p>
      <RadioGroup.Root className="list" value={u.depth} onValueChange={(v) => session.store.dispatch({ type: 'undo', patch: { depth: v as 'factory' | 'keep', appsBack: false, note: undefined } })} aria-label="How far back">
        {(['factory', 'keep'] as const).map((d) => (
          <RadioGroup.Item key={d} value={d} className="opt"><span className="rd" /><span className="t"><span>{frame.undo[d].title}</span><small>{frame.undo[d].detail}</small></span></RadioGroup.Item>
        ))}
      </RadioGroup.Root>
      {u.note && <p className={`status ${u.note.kind === 'err' ? 'err' : u.note.kind === 'ok' ? 'ok' : 'work'}`} role="status"><b style={{ fontWeight: 500 }}>{u.note.title}</b> {u.note.sub}{u.note.link && <> <a className="link" href={u.note.link.href} target="_blank" rel="noopener">{u.note.link.text}</a></>}</p>}
      {u.source === 'browser' && u.inBrowser && (
        <div className="well">
          <span className="grow"><span>Saved backup in this browser</span><span className="small">{u.inBrowser}</span></span>
          <button type="button" className="btn ghost" onClick={() => session.downloadUndoBackup()}>Download this backup</button>
        </div>
      )}
      <input ref={file} type="file" accept=".zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void session.undoFromFile(f); }} />
      {u.source !== 'browser' && <p className="small">{u.file ?? 'No backup saved in this browser — choose a backup .zip file to continue.'}</p>}
      <Foot back={{ href: '/' }} busy={!!s.busy}>
        <span className="links">
          {u.source === 'file' && u.inBrowser && <button type="button" className="btn ghost" disabled={u.checking || u.locked || !!s.busy} onClick={() => void session.undoFromBrowser()}>Use saved backup</button>}
          <button type="button" className="btn ghost" disabled={u.checking || u.locked || !!s.busy} onClick={() => file.current?.click()}>{u.source === 'file' ? 'Choose a different file' : 'Use a backup file'}</button>
        </span>
        <button type="button" className="btn ember" disabled={!u.ready || !!s.busy} onClick={() => void session.click('undo-continue', () => session.undoContinue())}>
          {keep && !u.appsBack ? frame.undo.keep.button : 'Continue'}
        </button>
      </Foot>
      <p className="small">Prefer the command line? <code>{frame.help.undoCli}</code></p>
    </section>
  );
}

/** 9 · When something fails: what happened, whether anything was written, the most likely fix. */
/** `help`: the frame's help links (the wizard's install frames have them). */
export function Failure({ s, session, help }: { s: WizardState; session: StandardSession; help?: KilnFrame['help'] }) {
  const f = s.failure!;
  const [open, setOpen] = useState(false);
  const retry = () => {
    if (f.at >= 3) void session.click('resume', () => session.route('resume'));
    else session.store.dispatch({ type: 'go', screen: 'connect' });
  };
  return (
    <section className="card err" aria-labelledby="h" role="alert">
      <span className="err-mark" aria-hidden="true">!</span>
      <h2 id="h">{f.title}</h2>
      {f.happened && f.fix ? <>
        {/* whether the device is safe, always first (docs/design/kiln/system.md, errors) */}
        <Note tone={f.wrote ? 'info' : 'ok'} role="status">{f.happened}</Note>
        <div className={x.fix} role="group" aria-labelledby="fix-h">
          <b id="fix-h">How to fix it</b>
          <span>{f.fix}</span>
        </div>
      </> : <p className="lede">{f.body}</p>}
      <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
        <Foot back={null}>
          <span className="links">
            <Collapsible.Trigger className="btn ghost">{open ? 'Hide help' : 'More help'}</Collapsible.Trigger>
            {f.wrote && <button type="button" className="btn ghost" onClick={retry}>Try again</button>}
          </span>
          {f.wrote
            ? <button type="button" className="btn ember" onClick={() => void session.undo()}>Undo with your backup</button>
            : <button type="button" className="btn ember" onClick={retry}>Try again</button>}
        </Foot>
        <Collapsible.Content className="more">
          <ol className="tips">{f.tips.map(([b, t]) => <li key={b}><b>{b}</b> {t}</li>)}</ol>
          {f.raw && <pre className="raw">{f.raw}</pre>}
          {help && <p className="links">
            <a className="link" href={help.recovery} target="_blank" rel="noopener">Recovery and troubleshooting</a>
            <span aria-hidden="true">·</span>
            <button type="button" className="link" onClick={() => void session.undo()}>Undo with your backup</button>
          </p>}
          {help && <p className="small">Prefer the command line? <code>{help.undoCli}</code> (<a className="link" href={help.undo} target="_blank" rel="noopener">restore guide</a>)</p>}
          <TechLog s={s} />
        </Collapsible.Content>
      </Collapsible.Root>
    </section>
  );
}

/** The browser check, as a screen only when it fails. */
export function Browser() {
  const secure = typeof window !== 'undefined' && isSecureContext;
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">{secure ? 'Use Chrome or Edge' : 'Open this page over HTTPS'}</h2>
      <p className="lede">{secure
        ? 'This browser can’t talk to USB devices. Open Kiln in Chrome or Edge on a computer, or Chrome on Android.'
        : 'Browsers only allow USB connections from a secure page (HTTPS or localhost).'}</p>
    </section>
  );
}
