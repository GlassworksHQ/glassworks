'use client';
// An image over fastboot (ImageSession): what goes on the device (Glassworks or a community image, choice/), the
// download, fastboot, the warning, building and flashing, the first start, and Done with the way back. Same design as
// the rest of Kiln (docs/design/kiln): one idea per screen, one ember button, the log one click away. What the device
// is called and what only it needs said come from the frame (DeviceFrame `the`, `short`, `words`).
import { cap, fmtSize, pickInList, type DeviceFrame } from '@glassworks/kiln-core';
import Link from 'next/link';
import { useMemo, useState } from 'react';
import { BuiltOn, Credit, stepCredits } from '../components/Credit';
import { FRAMES } from '../frames/catalog';
import { Recovery } from '../recovery/Recovery';
import type { RailMark, RailStep } from '../wizard/state';
import type { ImageSession, ImageState, WorkStep } from './imageSession';
import { Choose, ConfirmCommunity, DoneCommunity, Download, DownloadButton, FileRow, WayBack, Whose } from './choice/ChoiceScreens';
import { ReleaseApk, TestOnly } from './ReleaseApk';
import type { AppSession, AppState } from './appSession';
import { SilentUpdates } from '../components/SilentUpdates';
import { ImageKeep, KeyOutcome, keyHeld } from './KeepScreens';
import { lines, rich } from './rich';
import { totalTime, word } from './words';
import { InPlaceNotice, ReceiveScreen, RestorePointScreen, type InPlaceWords } from './InPlaceScreens';
import { Foot, StartOverDialog } from '../components/Nav';
import { BrowserAsk, ClockGlyph, Details, Hands, Instructions, Note, Problem, Progress, Wait, rowsAt, useForward, type ProgressRow } from '../components/Kit';
import type { FlowActionSpec } from '../components/Shell';
import { ManagePageRow } from '../wizard/screens';
import { hostOs } from '../wizard/explain';

type P = { f: ImageSession; s: ImageState; m: AppSession; ms: AppState };

/**
 * The install's steps on the rail, before the standard flow's Set up and Done. Arriving by an image's link skips the
 * choice. An in-place install (`inPlace`) has no download: it prepares, connects fastboot, copies the parts of the system
 * it changes (the receive step), then installs.
 */
export const imageSteps = (f: Pick<DeviceFrame, 'short'>, link: boolean, inPlace = false): string[] =>
  (inPlace ? [f.short, 'Choose', 'Prepare', 'Fastboot', 'Copy', 'Install'] : [f.short, 'Choose', 'Download', 'Fastboot', 'Install']).filter((x) => !link || x !== 'Choose');

/** Where an in-place install's screens sit on its rail: its first fastboot session, the receive, then its second. */
function inPlaceAt(s: ImageState, done: number): number {
  if (s.screen === 'done') return done;
  if (s.screen === 'choose') return 1;
  if (s.screen === 'image' || s.screen === 'download') return 2;
  if (s.screen === 'receive') return 4;
  if (s.screen === 'undo') return 5;
  return s.round === 2 ? 5 : 3;
}

/** Set up is Glassworks' (the standard flow, after the first start): a community image's install ends at Install. */
export function imageRail(s: ImageState, f: Pick<DeviceFrame, 'short' | 'id'>, community = false, inPlace = false): RailStep[] {
  // the way back has its own short rail: a restore isn't an install's step
  if (s.screen === 'undo') {
    const undo = ['Restore point', 'Fastboot', 'Restore', 'Done'];
    const at = ({ start: 0, fastboot: 1, working: 2, error: 2, done: 3 } as const)[s.undo?.at ?? 'start'];
    return undo.map((name, i): RailStep => ({ name, mark: i < at || s.undo?.at === 'done' ? 'done' : i > at ? 'todo' : s.undo?.at === 'error' ? 'bad' : 'now' }));
  }
  const names = [...imageSteps(f, false, inPlace), ...(community ? [] : ['Set up']), 'Done'];
  const at = inPlace ? inPlaceAt(s, names.length)
    : ({ choose: 1, image: 2, download: 2, keep: 3, fastboot: 3, confirm: 3, working: 4, adb: 4, error: 4, done: names.length, receive: 4, 'restore-point': 4, undo: 4 } as const)[s.screen];
  return names.map((name, i): RailStep => {
    // the first start's Connect is the person's turn; once picked, the wait for it isn't
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : s.screen === 'adb' && !s.connecting ? 'turn' : 'now';
    return { name, mark };
  }).filter((x) => !s.link || x.name !== 'Choose');
}

/** The stack's layers are Glassworks' layers: it stays empty while a community image installs. */
export function imageLevel(s: ImageState, community = false): number {
  if (community) return 0;
  if (s.screen === 'done') return 4;
  if (s.screen === 'adb') return 3;
  if (s.screen === 'working') return s.work?.step.startsWith('flash') || s.work?.step === 'restart' ? 2 : 1;
  return 0;
}

/** Who built the image the frame starts from, from its frame.json `credits` (frames/README.md#credits). */
const imageCredits = (f: DeviceFrame) => stepCredits(f.id, f.credits, ['image']);
/** The frame's reversibility, as its frame.json labels it (issue 146). */
const reversible = (f: DeviceFrame) => FRAMES.find((x) => x.id === f.id)?.ratings.reversible.label;

/** At the step that hands over the image: who built it, and whose its vendor files are. */
function ImageCredit({ f }: { f: DeviceFrame }) {
  return (
    <>
      {imageCredits(f).map((c) => <Credit key={c.id} c={c} built="image" />)}
      <p className="small">{f.words['image.vendor']}</p>
    </>
  );
}

/** The technical log, closed until asked for. */
function Log({ s }: { s: ImageState }) {
  return <Details label="Technical log"><pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre></Details>;
}


/** The in-place screens' words, from the frame's data (its `inPlace.*` words). */
export function inPlaceWordsOf(d: DeviceFrame): InPlaceWords {
  const w = d.imageFlow.inPlace?.words;
  return {
    the: d.the,
    ...(w ? { bootHint: w['inPlace.receive.lede'], coverNote: w['inPlace.coverNote'], changes: w['inPlace.changes'], restoreLede: w['inPlace.restorePoint.lede'] } : {}),
    ...(word(d, 'time.agent') ? { agentTime: word(d, 'time.agent') } : {}),
  };
}

/** The APK, from its release or a file: on the first screen, and on the app's install when it's missing. */
function ApkInput({ d, m, ms }: { d: DeviceFrame; m: AppSession; ms: AppState }) {
  return (
    <>
      {ms.release && <ReleaseApk m={m} ms={ms} />}
      {!ms.release && (
        <div className="fields">
          <label className="field"><span>{d.words['image.apkLabel']}</span>
            <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
              onChange={(e) => { const x = e.target.files?.[0]; if (x) void m.pickApk(x.name, x.arrayBuffer().then((b) => new Uint8Array(b))); }} />
          </label>
        </div>
      )}
      {ms.apk && (!ms.release || ms.apk.error) && <p className={`small${ms.apk.error ? ' status err' : ''}`}>{ms.apk.name}: {ms.apk.error ?? `${ms.apk.package}, ${fmtSize(ms.apk.size)}`}</p>}
    </>
  );
}

/** The person's Wi-Fi, before fastboot: Glassworks joins it on its first start. Optional. */
function YourWifi({ f, s }: Pick<P, 'f' | 's'>) {
  const d = f.frame;
  const [show, setShow] = useState(false);
  const hotspot = !!d.imageFlow.inPlace?.channels.lan?.hotspot;
  const { ssid, password } = s.wifi;
  return (
    <div className="wifi">
      <div className="wifi-head">
        <span className="ico" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M2 8.8a15 15 0 0 1 20 0" /><path d="M5 12.4a10 10 0 0 1 14 0" /><path d="M8.5 15.9a5 5 0 0 1 7 0" /><circle cx="12" cy="19.5" r="1" fill="currentColor" /></svg>
        </span>
        <span>
          <span className="t">Your Wi-Fi <span className="opt-tag">Optional</span></span>
          <span className="d" style={{ display: 'block' }}>
            {hotspot
              ? `Glassworks joins it on its first start, so ${d.the} comes up online and ready for Home Assistant. You can also add it on ${d.the} later.`
              : `With it, the transfer takes seconds instead of minutes, and Glassworks starts up online. You can also add it on ${d.the} later.`}
          </span>
        </span>
      </div>
      <div className="fields">
        <label className="field"><span>Network</span>
          <input className="input" autoComplete="off" value={ssid} onChange={(e) => f.setWifi({ ssid: e.target.value })} />
        </label>
        <label className="field"><span>Password</span>
          <span className="pw">
            <input className="input" type={show ? 'text' : 'password'} autoComplete="off" value={password} onChange={(e) => f.setWifi({ password: e.target.value })} />
            <button type="button" className="link eye" onClick={() => setShow(!show)} aria-pressed={show}>{show ? 'Hide' : 'Show'}</button>
          </span>
        </label>
      </div>
      {ssid && !f.blocked() && <p className="live ok" role="status"><span className="tick" aria-hidden="true">✓</span>{`${cap(d.the)} will join ${ssid} on its first start`}</p>}
    </div>
  );
}

/**
 * What an in-place install asks of the person and does by itself, in order, read from the recipe's own steps (kiln-core
 * plan()): the whole road on its first screen, with the parts that are the person's marked. Frame words say how long.
 */
export function inPlaceRoad(f: ImageSession): { you: boolean; text: string; time?: string }[] {
  const d = f.frame;
  const hotspot = !!d.imageFlow.inPlace?.channels.lan?.hotspot;
  let fastboots = 0;
  let kept = false;
  let wrote = false;
  return f.writes().length ? [
    { you: true, text: `Put ${d.the} in fastboot mode`, time: word(d, 'time.fastboot') },
    ...f.writes().flatMap((x) => {
      switch (x.do) {
        case 'stage': return [{ you: false, text: 'Kiln adds a small setup helper. The original system isn’t touched' }];
        case 'receive': return [{ you: hotspot, text: hotspot ? `Join ${d.the}’s own Wi-Fi, so Kiln can copy the parts of its system it will change` : 'Kiln copies the parts of its system it will change', time: word(d, 'time.agent') }];
        case 'fastboot': return fastboots++ ? [] : [{ you: true, text: 'Fastboot mode once more' }];
        case 'in-place': return [{ you: false, text: 'Kiln prepares the changes and checks them' }];
        case 'restore-point': kept = true; return [{ you: true, text: 'Kiln saves a restore point, and you download a copy' }];
        // the writes after the restore point, as one: the first session's write is the helper's, said above
        case 'flash': return wrote || !kept ? [] : (wrote = true, [{ you: false, text: 'Kiln writes the changes' }]);
        case 'app-install': return [{ you: false, text: 'It starts for the first time, and Kiln installs Glassworks', time: word(d, 'time.first-start') }];
        default: return [];
      }
    }),
    { you: true, text: 'Set it up: Wi-Fi, Home Assistant and panes' },
  ] : [];
}

/** Glassworks on the device's own software: what happens, the person's Wi-Fi for its first start, and the app. */
export function InPlaceStart({ f, s, m, ms }: P) {
  const d = f.frame;
  const w = d.imageFlow.inPlace?.words;
  const blocked = f.blocked();
  const road = inPlaceRoad(f);
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly f={f.frame} />
      <h2 id="h">{`Install Glassworks on ${d.the}, keeping its system`}</h2>
      <p className="lede">{w?.['inPlace.stage.lede']}</p>
      {s.changed && <Note tone="info">{`An earlier try already changed ${d.the}. Going on puts it right.`}</Note>}
      {road.length > 0 && (
        <div className="road">
          <span className="road-h">{totalTime(d, f.recipe.id) ? `What happens · ${totalTime(d, f.recipe.id)}` : 'What happens'}</span>
          <ol className="road-list">
            {road.map((x) => (
              <li key={x.text} className={x.you ? 'you' : undefined}>
                <span className="road-t">{x.text}</span>
                <span className="road-m">{x.time && <span className="road-time">{x.time}</span>}{x.you && <span className="road-you">You</span>}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
      <InPlaceNotice words={inPlaceWordsOf(d)} />
      <YourWifi f={f} s={s} />
      <ApkInput d={d} m={m} ms={ms} />
      {blocked && <Note tone="bad">{blocked}</Note>}
      <Foot>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => f.toFastboot()}>Continue</button>
      </Foot>
    </section>
  );
}

/** Glassworks: its image, downloaded and checked, and what Kiln builds into it. */
export function ImageImage({ f, s, m, ms }: P) {
  const d = f.frame;
  if (f.inPlace) return <InPlaceStart f={f} s={s} m={m} ms={ms} />;
  const blocked = f.blocked();
  const ready = s.files.every((x) => x.state === 'ok');
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly f={f.frame} />
      <h2 id="h">{`Install Glassworks on ${d.the}`}</h2>
      <p className="lede">{d.words['image.lede']}</p>
      {s.changed && <Note tone="info">{`An earlier try already changed ${d.the}. Going on puts it right.`}</Note>}
      {s.files.map((x) => <FileRow key={x.name} f={f} file={x} />)}
      <ImageCredit f={d} />
      {ms.release && <ReleaseApk m={m} ms={ms} />}
      {/* Home Assistant and the Wi-Fi are set-up's, after the first start, as on every frame */}
      {!ms.release && (
        <div className="fields">
          <label className="field"><span>{d.words['image.apkLabel']}</span>
            <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
              onChange={(e) => { const x = e.target.files?.[0]; if (x) void m.pickApk(x.name, x.arrayBuffer().then((b) => new Uint8Array(b))); }} />
          </label>
        </div>
      )}
      {ms.apk && (!ms.release || ms.apk.error) && <p className={`small${ms.apk.error ? ' status err' : ''}`}>{ms.apk.name}: {ms.apk.error ?? `${ms.apk.package}, ${fmtSize(ms.apk.size)}`}</p>}
      {blocked && ready && <Note tone="bad">{blocked}</Note>}
      {/* the flashed device is fine and keeps this browser's key: only the first start over adb is left (a real run, 2026-11-19) */}
      <p className="small">{`Already wrote the system image, but first-time setup didn't finish? `}<button type="button" className="link" onClick={() => f.finishFirstStart()}>Finish first-time setup</button></p>
      <Foot>
        <DownloadButton f={f} s={s} label="Download the system image" />
      </Foot>
    </section>
  );
}

/** Fastboot by hand: the drawing, the numbered steps to tick off, what to watch for, then the browser's list. */
function ByHandFastboot({ d, cover }: { d: DeviceFrame; cover?: string }) {
  return (
    <>
      {/* the drawing beside the steps on a computer, so the steps stay in the first screen; above them on a phone */}
      <div className="howto">
        <div className="howto-art"><Recovery data={d.recovery} /></div>
        <Instructions steps={lines(d.words['fastboot.byHandSteps']).map((x) => rich(x))} label="Fastboot mode, by hand" />
      </div>
      <Note tone="info">{d.words['fastboot.byHandNote']}</Note>
      {cover && <Note tone="turn" title="Keep the back cover off">{cover}</Note>}
    </>
  );
}

export function ImageFastboot({ f, s }: P) {
  const d = f.frame;
  const r = s.fastboot;
  const again = f.inPlace && s.round === 2;
  const intro = word(d, 'fastboot.lede');
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h">{again ? `Fastboot mode once more` : `Put ${d.the} in fastboot mode`}</h2>
      {/* by hand first: most people install over the vendor's software, which Kiln can't reach over adb (the maintainer, 2026-11-19) */}
      {again ? <>
        {s.leftHotspot && <Note tone="ok" title={`Kiln has what it needs from ${d.the}`}>You can switch this computer back to your usual Wi-Fi now.</Note>}
        <p className="lede">{`The same button as before, one more time. Then Kiln saves a restore point before it changes anything.`}</p>
        <Instructions steps={lines(d.imageFlow.inPlace?.words['inPlace.fastboot.again'] ?? '').map((x) => rich(x))} label="Fastboot mode, once more" />
      </> : s.restarted ? <p className="lede">{rich(d.words['fastboot.restarted'])}</p> : <>
        {intro && <p className="lede">{intro}</p>}
        <ByHandFastboot d={d} cover={f.inPlace ? d.imageFlow.inPlace?.words['inPlace.coverNote'] : undefined} />
      </>}
      {r && !r.ok && (
        <Note tone="bad" title="This isn’t the device Kiln expected. Nothing was written">
          <ul className="checks">{r.checks.map((c) => <li key={c.what} className={c.ok ? 'ok' : 'fail'}>{`${c.what}: ${c.found}`}{!c.ok && ` (needs ${c.need})`}</li>)}</ul>
        </Note>
      )}
      {!s.connecting && <BrowserAsk listed={d.listed} short={d.short} the={d.the} />}
      <UsbSetup d={d} />
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.connectError && <Note tone="bad">{s.connectError}</Note>}
      <Foot busy={s.connecting}>
        {/* Glassworks goes through the keep step, which reads Home Assistant's key and then restarts the device (PR 603); an
            in-place reinstall restarts straight away, after the frame's check of whether the device's own Wi-Fi is gone */}
        {!s.restarted && !again ? <button type="button" className="btn ghost" disabled={s.connecting} onClick={() => (f.recipe.community || f.inPlace ? void f.restartToFastboot() : f.toKeep())}>{d.words['fastboot.overUsb']}</button> : <span />}
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.connectFastboot()}>Connect</button>
      </Foot>
    </section>
  );
}

/**
 * The frame's one-time USB setup for this computer's system, under a fastboot screen's browser ask (installer.json
 * `device.drivers`, as the wizard's Connect shows a wizard frame's `drivers`): nothing on macOS, or for a frame that gives none.
 */
function UsbSetup({ d }: { d: DeviceFrame }) {
  const line = useMemo(() => { const o = hostOs(); return o === 'other' ? undefined : d.drivers?.[o]; }, [d]);
  if (!line) return null;
  return <p className="small">{line}{d.drivers?.guide && <>{' '}<a className="link" href={d.drivers.guide} target="_blank" rel="noopener">Setup guide</a></>}</p>;
}

/** What Kiln's checks read in fastboot, all passed: one calm line, the details beside it. */
function Checked({ d, checks }: { d: DeviceFrame; checks: readonly { what: string; found: string }[] }) {
  return (
    <Note tone="ok" title={`Found ${d.the} in fastboot mode`}>
      Kiln checked it, and it matches what the install expects. Nothing is written until you continue.
      <Details label="What Kiln checked"><ul className="checks">{checks.map((c) => <li key={c.what}>{`${c.what}: ${c.found}`}</li>)}</ul></Details>
    </Note>
  );
}

export function ImageConfirm(p: P) {
  const { f, s } = p;
  const d = f.frame;
  const rev = reversible(d);
  if (f.recipe.community) return <ConfirmCommunity f={f} s={s} reversible={rev} />;
  const r = s.fastboot!;
  if (f.inPlace) {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Add the setup helper</h2>
        <Checked d={d} checks={r.checks} />
        <p className="lede">{d.imageFlow.inPlace?.words['inPlace.confirm.lede']}</p>
        <InPlaceNotice words={inPlaceWordsOf(d)} />
        <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} />{` I understand ${d.the}'s app data starts fresh.`}</label>
        <Foot>
          <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Continue</button>
        </Foot>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{d.words['confirm.title']}</h2>
      <Checked d={d} checks={r.checks} />
      <Note tone="turn" title="Before you go on">{d.words['confirm.lede']}{rev ? ` ${rev}.` : ''}</Note>
      <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} />{` ${d.words['confirm.understand']}`}</label>
      <Foot>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Install Glassworks</button>
      </Foot>
    </section>
  );
}

type RowId = WorkStep | 'connect';
type RowDef = { id: RowId; label: string; time?: string; note?: string };

/**
 * The working screen's rows for each planned step from the warning on (ImageSession.writes()), in plain words: what
 * happens to the device, never how. `stage`: the in-place install's first fastboot session, which writes the helper.
 */
function rowsOf(d: DeviceFrame, stage: boolean): Record<string, RowDef[]> {
  const t = (k: string) => word(d, `time.${k}`);
  const firstStart = { id: 'first-boot' as const, label: 'Let it finish its first start', time: t('first-start'), note: word(d, 'firstStart.note') ?? 'Its screen may stay dark until it’s ready. That’s normal.' };
  return {
    patch: [
      { id: 'patch', label: 'Add Glassworks to the new system' },
      { id: 'system', label: 'Get the new system ready' },
      { id: 'userdata', label: 'Get clean app storage ready' },
    ],
    'flash-system': [{ id: 'flash-system', label: stage ? 'Write the new system' : 'Write the new system', time: t('flash-system') }],
    'flash-userdata': [stage
      ? { id: 'flash-userdata', label: 'Put the setup helper on it' }
      : { id: 'flash-userdata', label: 'Start its app storage fresh', time: t('flash-userdata'), note: 'The bar may pause here for a minute or two. That’s normal.' }],
    restart: [{ id: 'restart', label: stage ? `Restart ${d.the} into the setup helper` : `Restart ${d.the}` }],
    'first-start': [firstStart],
    'system-key': [{ id: 'system-key', label: 'Let only this browser connect to it' }],
    'adb-key': [{ id: 'adb-key', label: 'Remove the shared key anyone could connect with' }],
    wifi: [{ id: 'wifi', label: 'Save your Wi-Fi for its first start' }],
    'wifi-on': [{ id: 'wifi-on', label: 'Turn on its Wi-Fi' }],
    stage: [{ id: 'stage', label: 'Prepare the setup helper' }],
    'in-place': [{ id: 'in-place', label: 'Prepare the changes and check them' }],
    // an in-place install has no first start of its own: its wait is here, before the app goes in
    'app-install': [firstStart, { id: 'app-install', label: 'Install Glassworks and hide the original apps' }],
  };
}

/** Issue 580: the hand-back row joins Glassworks' first start, when the tab holds Home Assistant's key. */
const HAND_BACK: RowDef = { id: 'hand-back', label: 'Keep your Home Assistant connection' };

/**
 * Every row of this part of the install, from the first write to the end, with the person's one click among them: the
 * working screen and the first start's screen show the same list, so nothing appears to go back. An in-place install
 * shows its first fastboot session's rows, or its second's.
 */
export function installRows(f: ImageSession, s: ImageState): ProgressRow[] {
  const d = f.frame;
  const all = f.writes();
  const cut = all.findIndex((x) => x.do === 'receive');
  const stage = f.inPlace && s.round !== 2;
  const steps = !f.inPlace ? all : stage ? all.slice(0, cut) : all.slice(cut + 1);
  const ROWS = rowsOf(d, stage);
  const defs: RowDef[] = steps.flatMap((x) => [...(ROWS[x.do === 'flash' ? `flash-${x.partition}` : x.do] ?? []), ...(x.do === 'first-start' && keyHeld(s.key) ? [HAND_BACK] : [])]);
  // the person's click: before the first start (or a community image's Wi-Fi on)
  const click = defs.findIndex((r) => r.id === 'first-boot' || r.id === 'wifi-on');
  if (click >= 0) defs.splice(click, 0, { id: 'connect', label: `Connect to ${d.the}`, note: 'Click Connect, then pick it in your browser’s list.' });
  const idx = (id?: string) => defs.findIndex((r) => r.id === id);
  let at: number;
  let cur: 'now' | 'turn' | 'bad' = 'now';
  if (s.screen === 'adb') {
    at = s.connecting && s.adbWaiting && idx('first-boot') >= 0 ? idx('first-boot') : idx('connect');
    cur = s.connecting ? 'now' : 'turn';
  } else {
    const step = s.work?.step;
    at = idx(step);
    if (at < 0 && step === 'first-boot') at = idx('connect') + 1;
    if (at < 0) at = 0;
    if (s.screen === 'error') cur = 'bad';
  }
  return rowsAt(defs.map((r, i) => (i === at && s.screen === 'working' && s.work?.step === r.id ? { ...r, x: s.work.x } : r)), Math.max(0, at), cur);
}

export function ImageWorking({ f, s }: P) {
  const d = f.frame;
  const rows = installRows(f, s);
  const at = useForward(rows.findIndex((r) => r.state !== 'done'));
  const shown = rows.map((r, i): ProgressRow => (i < at ? { ...r, state: 'done' } : i === at ? { ...r, state: r.state === 'next' ? 'now' : r.state } : { ...r, state: 'next' }));
  const starting = s.work?.step === 'first-boot';
  const stage = f.inPlace && s.round !== 2;
  const title = starting ? `${cap(d.the)} is starting for the first time`
    : stage ? 'Adding the setup helper'
    : f.recipe.community ? `Installing ${f.recipe.name}` : 'Installing Glassworks';
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h">{title}</h2>
      {starting
        ? <Wait what="Waiting for its first start" typical={word(d, 'time.first-start')} note={`Nothing to do. Keep ${d.the} plugged in; Kiln goes on by itself.`} />
        : <Hands the={d.the} />}
      <Progress rows={shown} label={title} />
      <Log s={s} />
    </section>
  );
}

export function ImageAdb({ f, s, m, ms }: P) {
  const d = f.frame;
  const r = f.recipe;
  const waiting = s.connecting && s.adbWaiting;
  const rows = installRows(f, s);
  return (
    <section className="card" aria-labelledby="h">
      {r.community && <Whose r={r} />}
      {!s.connecting && <span className="turn-label"><span className="pulse" />Your turn</span>}
      <h2 id="h">{waiting ? `${cap(d.the)} is starting for the first time` : `Connect to ${d.the}`}</h2>
      {!waiting && (r.community
        ? <p className="lede">{`Its first start takes a few minutes. Click Connect, and Kiln waits for it, then switches its Wi-Fi on.`}</p>
        : <p className="lede">{rich(d.words['adb.firstStart'])}</p>)}
      {f.inPlace && !waiting && <p className="small">{d.imageFlow.inPlace?.words['inPlace.appInstall.lede']}</p>}
      {/* until an APK passes its checks: one the checks refused (another app's, say) keeps the picker, with why */}
      {f.inPlace && !m.apkBytes() && <ApkInput d={d} m={m} ms={ms} />}
      {waiting
        ? <Wait what="Waiting for it to finish starting" typical={word(d, 'time.first-start')} note={`Its screen may stay dark until it’s ready. That’s normal. Kiln goes on by itself.`} />
        : s.connecting ? <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>
        : <BrowserAsk listed={d.listed} short={d.short} the={d.the} />}
      {s.connectError && <Note tone="bad">{s.connectError}</Note>}
      <Progress rows={rows} label="The install" />
      {/* the system is written: no Start over. The honest ways out are the restore point (the rail's), or writing the image again */}
      {!s.connecting && (
        <Foot>
          {!r.community && !f.inPlace ? <button type="button" className="btn ghost" onClick={() => f.retry()}>Write the image again</button> : <span />}
          <button type="button" className="btn ember pulsing" disabled={f.inPlace && !m.apkBytes()} onClick={() => void f.connectAdb()}>Connect</button>
        </Foot>
      )}
      <Log s={s} />
    </section>
  );
}

/** A community image's Done (Glassworks' is the standard flow's, with ImageOutcome under it). */
export function ImageDone({ f, s }: P) {
  return <DoneCommunity f={f} log={<Log s={s} />} />;
}

/**
 * What Glassworks' first start left to say on the standard flow's Done: whether it is the only home screen (with the
 * fix), Home Assistant's key, the way back to the community image, and how reversible it is. `away`: the way back
 * opens the install's first screen again.
 */
export function ImageOutcome({ f, s, away }: { f: ImageSession; s: ImageState; away: () => void }) {
  const d = f.frame;
  const rev = reversible(d);
  const fb = s.firstBoot;
  const noHome = fb?.home.result === 'none';
  const stranded = fb && fb.home.result !== 'set' && !noHome;
  const other = f.recipes.find((r) => r.community);
  return (
    <>
      {stranded && (
        <Note tone="bad" title="Glassworks isn’t the only home screen yet"
          action={<button type="button" className="btn ghost" disabled={s.fixing} onClick={() => void f.fixHome()}>{s.fixing ? 'Hiding it…' : 'Hide the other launcher again'}</button>}>
          Android opened {fb.home.result === 'chooser' ? 'its list of home screens' : <code>{fb.home.focused ?? 'something else'}</code>}{fb.home.left.length ? <>: <code>{fb.home.left.join(', ')}</code> is still a home app</> : ''}.
          {` ${d.words['done.noTouch']}`}
        </Note>
      )}
      {noHome && (
        <Note tone="bad" title="This Glassworks build has no home screen">
          {fb.home.left.length ? <code>{fb.home.left.join(', ')}</code> : 'The image’s launcher'}{' stays its home screen.'}
          {` ${d.words['done.noHomeFix']}`}
        </Note>
      )}
      {fb && <KeyOutcome k={s.key} d={d} />}
      {f.inPlace && (
        <Note tone="info" title="The way back" icon={<UndoGlyph />}
          action={<button type="button" className="btn ghost" onClick={() => { away(); void f.startUndo(); }}>Restore the original system</button>}>
          {d.imageFlow.inPlace?.words['inPlace.wayBack']}
        </Note>
      )}
      {other && !f.inPlace && <WayBack f={f} other={other} then={away} />}
      {/* the frame's rating is for the image install; an in-place install keeps the stock system and its way back above */}
      {rev && !f.inPlace && <p className="small">{rev}.</p>}
    </>
  );
}

const UndoGlyph = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M9 14L4 9l5-5" /><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" /></svg>
);

export function ImageError({ f, s }: P) {
  const d = f.frame;
  const inFastboot = !!s.work && !s.bundleLost && !['first-boot', 'hand-back', 'restart', 'wifi-on', 'app-install'].includes(s.work.step);
  const kept = f.inPlace && s.restorePoint?.status === 'ready';
  // an in-place install that can't go on: its bundle is gone after a write, or the system didn't take the change. The
  // way back, on the restore point saved before the first write, is the one way on
  const restoreOnly = f.inPlace && (s.bundleLost === 'restore' || !!s.notTaken);
  const safe = s.wifiOnFailed ? `${cap(d.the)} is installed. Only its Wi-Fi didn’t turn on yet.`
    : f.inPlace && s.notTaken ? `${cap(d.the)} started, but its system didn’t take the change Kiln wrote, so Kiln stopped before installing anything.`
    : f.inPlace && s.bundleLost === 'restore' ? 'Kiln can’t finish this install without what it copied from the device.'
    : inFastboot ? `${cap(d.the)} is still in fastboot mode, so leave it plugged in.${kept ? ' Your restore point is saved in this browser.' : ''}`
    : kept ? 'Your restore point is saved in this browser.' : undefined;
  const fix = s.wifiOnFailed ? 'Click Connect again when you’re ready.'
    : restoreOnly ? `Restore the original system with the restore point saved before the ${s.notTaken ? 'write' : 'first write'}.`
    : inFastboot ? (s.files.every((x) => x.state === 'ok') ? 'Click Try again. Kiln reconnects and picks up from fastboot.' : 'Click Try again. Kiln downloads the files again and goes on.')
    : 'Click Try again when you’re ready.';
  return (
    <Problem title={s.wifiOnFailed ? 'Wi-Fi didn’t turn on yet' : 'The install stopped'} safe={safe} safeTone={restoreOnly || inFastboot ? 'info' : 'ok'} fix={<>
      <p>{fix}</p>
      {s.flashFailed && !f.recipe.community && !f.inPlace && !s.noFill && <p className="small">{d.words['error.noFill']}</p>}
    </>} details={<><pre className="raw">{s.error}</pre><pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre></>}>
      {f.recipe.community && <Whose r={f.recipe} />}
      {s.wifiOnFailed && s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.wifiOnFailed && s.connectError && <Note tone="bad">{s.connectError}</Note>}
      {/* Back to the download, when the step that stopped can start over from it; Start over and the restore point are the rail's */}
      <Foot back={!s.wifiOnFailed && !f.inPlace && f.tryAgainLands() !== 'image' ? { run: () => f.retry() } : undefined} backLabel="Back to download">
        <span className="links">
          {s.flashFailed && !f.recipe.community && !f.inPlace && !s.noFill && <button type="button" className="btn ghost" onClick={() => f.retryWithoutFill()}>Try compatibility write mode</button>}
          {s.wifiOnFailed && <button type="button" className="btn ghost" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect again</button>}
        </span>
        {/* the step that stopped, on its own screen: the first start over adb, a write over fastboot, else the download. A
            bundle lost after a write can't be built from again, and a system that didn't take the change can't be
            installed on: the way back, on the restore point saved before it (the rail doesn't offer it twice) */}
        {restoreOnly ? <button type="button" className="btn ember" onClick={() => void f.restoreAfterError()}>Restore the original system</button>
          : !s.wifiOnFailed && <button type="button" className="btn ember" onClick={() => f.tryAgain()}>Try again</button>}
      </Foot>
    </Problem>
  );
}

/** Saves a Blob as a file, from the page (the restore point's download). */
function saveFile(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** The receive step: the bundle from the device's setup agent (InPlaceScreens). */
export function InPlaceReceive({ f, s }: P) {
  const r = s.receive ?? { stage: 'waiting-boot', channel: null, progress: null, pageUrl: null, fallback: null, error: null, bluetooth: true, network: null, joined: false };
  return (
    <>
      <ReceiveScreen log={<Log s={s} />} stage={r.stage} channel={r.channel} bluetoothAvailable={r.bluetooth} progress={r.progress} agentPageUrl={r.pageUrl}
        agentPageGuessed={r.pageGuessed} agentPort={f.frame.imageFlow.inPlace?.channels.lan?.port}
        fallbackReason={r.fallback} errorMessage={r.error} words={inPlaceWordsOf(f.frame)}
        onConnectBluetooth={() => void f.receive()} onRetryLan={() => void f.receive()} onPickFile={(x) => void f.receiveFile(x)}
        onCancel={r.stage === 'transferring' || r.stage === 'connecting' ? () => void f.cancelReceive() : undefined}
        onRetry={() => f.receiveAgain()} onContinueUnverified={() => void f.receiveUnverified()}
        network={r.network} joined={r.joined} wifiFailed={r.wifiFailed} onContinueWifi={() => void f.receiveWifi()} onByHand={() => f.receiveByHand()} />
    </>
  );
}

/** The restore point, saved in this browser and read back before any system write. */
export function InPlaceRestorePoint({ f, s }: P) {
  const r = s.restorePoint ?? { status: 'saving', digest: null, downloaded: false, error: null, filename: 'restore.sparse' };
  const [failed, setFailed] = useState<string | null>(null);
  return (
    <>
      <RestorePointScreen log={<Log s={s} />} status={r.status} verifiedDigest={r.digest} downloaded={r.downloaded} offered={r.offered} restoreFilename={r.filename} errorMessage={r.error}
        words={inPlaceWordsOf(f.frame)} downloadError={failed}
        onDownload={() => { setFailed(null); void f.restoreZip().then(({ blob, name }) => saveFile(blob, name)).catch((e: Error) => setFailed(e.message)); }}
        onConfirmCopy={() => void f.confirmCopy()}
        onContinue={() => void f.continueInstall()} onRetry={() => f.retryRestorePoint()} />
    </>
  );
}

/** The way back from an in-place install: the restore point written over the blocks it changed. */
export function InPlaceUndo({ f, s }: P) {
  const d = f.frame;
  const u = s.undo ?? { at: 'start' };
  const words = d.imageFlow.inPlace?.words;
  if (u.at === 'done') {
    return (
      <section className="card" aria-labelledby="h">
        <span className="okmark" aria-hidden="true">✓</span>
        <h2 id="h">{`${cap(d.the)} is back on its original system`}</h2>
        <p className="lede">{`Kiln wrote back every part it changed, and ${d.the} is restarting. Its first start takes a few minutes.`}</p>
        {/* the restore's exception to read-back (AGENTS.md hard rule 1): checked before the write, not after it */}
        <p className="small">{`Kiln checked the restore point before writing it. ${cap(d.the)} can’t be read after a restore, so its original start screen is the sign it worked.`}</p>
        <Foot back={null}>
          <button type="button" className="btn ghost" onClick={() => f.backToChoice()}>Install Glassworks again</button>
          <Link className="btn ember" href="/">Back to Kiln</Link>
        </Foot>
        <Log s={s} />
      </section>
    );
  }
  if (u.refused) {
    // a check before the first write failed (restore.ts): nothing was written, and the way out is another restore point
    // or another install
    return (
      <Problem title="Kiln won’t write this restore point" safeTone="info" safe={u.refused}
        fix="Pick another restore point made on this device, or choose another install."
        details={<pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre>}>
        <Foot back={null}>
          <button type="button" className="btn ghost" onClick={() => void f.startUndo()}>Pick another restore point</button>
          <button type="button" className="btn ember" onClick={() => f.backToChoice()}>Choose another install</button>
        </Foot>
      </Problem>
    );
  }
  if (u.at === 'error') {
    return (
      <Problem title="The restore stopped" safeTone="info"
        safe={`${cap(d.the)} stays in fastboot mode, so leave it plugged in. Your restore point is still safe, in this browser and in the copy you downloaded.`}
        fix="Click Try again. You can also run the restore later, from this browser or from the .zip you downloaded."
        details={<>{u.error && <pre className="raw">{u.error}</pre>}<pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre></>}>
        <Foot back={null}><button type="button" className="btn ember" onClick={() => void f.undoFlash()}>Try again</button></Foot>
      </Problem>
    );
  }
  if (u.at === 'working') {
    // the checks ran before the first write; no read-back row after it: the restore can't be read back (the log says so)
    const rows = rowsAt([{ id: 'check', label: 'Check the restore point' }, { id: 'write', label: 'Put the original parts back', x: u.x ?? 0 }, { id: 'restart', label: `Restart ${d.the}` }], 1);
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Putting the original system back</h2>
        <Hands the={d.the} />
        <Progress rows={rows} label="Writing the restore point" />
        <Log s={s} />
      </section>
    );
  }
  if (u.at === 'fastboot') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">{`Put ${d.the} in fastboot mode`}</h2>
        {u.restarted ? <p className="lede">{rich(d.words['fastboot.restarted'])}</p> : <ByHandFastboot d={d} />}
        {u.error && <Note tone="bad">{u.error}</Note>}
        {!s.connecting && <BrowserAsk listed={d.listed} short={d.short} the={d.the} />}
        <UsbSetup d={d} />
        {s.connectError && <Note tone="bad">{s.connectError}</Note>}
        <Foot busy={s.connecting}>
          <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.undoFlash()}>Connect and restore</button>
        </Foot>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Restore the original system</h2>
      <p className="lede">{words?.['inPlace.wayBack']}</p>
      {u.source === 'browser' && <Note tone="ok" title="Restore point found">This browser has the restore point it saved for this device.</Note>}
      {u.source === 'file' && <Note tone="ok" title="Restore point checks out">{u.fileName ?? 'The restore point you picked'}</Note>}
      {!u.source && <Note tone="turn" title="Pick your restore point">This browser has none saved. Pick the .zip you downloaded during the install.</Note>}
      <div className="fields">
        <label className="field"><span>{u.source ? 'Or use another restore point (.zip)' : 'Restore point (.zip)'}</span>
          <input className="input" type="file" accept=".zip,application/zip" onChange={(e) => { const x = e.target.files?.[0]; if (x) void f.pickRestoreZip(x); }} />
        </label>
      </div>
      {u.error && <Note tone="bad">{u.error}</Note>}
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.connectError && <Note tone="bad">{s.connectError}</Note>}
      <p className="small">{`Kiln restarts ${d.the} into fastboot mode over USB while Glassworks runs. If it doesn’t answer, use its buttons instead.`}</p>
      <Foot back={{ run: () => f.backToChoice() }} busy={s.connecting}>
        <button type="button" className="btn ghost" disabled={!u.source || s.connecting} onClick={() => f.undoByHand()}>Use its buttons instead</button>
        <button type="button" className="btn ember" disabled={!u.source || s.connecting} onClick={() => void f.undoRestart()}>Restart over USB</button>
      </Foot>
    </section>
  );
}

export function ImageScreens(p: P) {
  const { s } = p;
  return s.resumed ? <ImageResume {...p} />
    : s.screen === 'receive' ? <InPlaceReceive {...p} />
    : s.screen === 'restore-point' ? <InPlaceRestorePoint {...p} />
    : s.screen === 'undo' ? <InPlaceUndo {...p} />
    : s.screen === 'choose' ? <Choose f={p.f} s={s} />
    : s.screen === 'image' ? <ImageImage {...p} />
    : s.screen === 'download' ? <Download f={p.f} s={s} />
    : s.screen === 'keep' ? <ImageKeep {...p} />
    : s.screen === 'fastboot' ? <ImageFastboot {...p} />
    : s.screen === 'confirm' ? <ImageConfirm {...p} />
    : s.screen === 'working' ? <ImageWorking {...p} />
    : s.screen === 'adb' ? <ImageAdb {...p} />
    : s.screen === 'done' ? <ImageDone {...p} />
    : <ImageError {...p} />;
}

/** Start over's dialog, while the session asks it (`startingOver`): what starts again, what stays, then the confirm. */
export function ImageStartOver({ f, s }: Pick<P, 'f' | 's'>) {
  const d = f.frame;
  const what = f.inPlace ? d.imageFlow.inPlace?.words['inPlace.startOver'] : undefined;
  if (!what) return null;
  return (
    <StartOverDialog open={!!s.startingOver} onOpenChange={(o) => f.askStartOver(o)} onConfirm={() => f.startOver()} the={d.the}
      resets={[what, 'Where this install got to']}
      keeps={['Your Wi-Fi and the app you picked', `What Kiln copied from ${d.the}, saved in this browser`]} />
  );
}

/** The image flow's ways out, under the rail: Start over while it is honest, else the restore point once the system is changed. */
export function imageActions({ f, s }: Pick<P, 'f' | 's'>): FlowActionSpec[] {
  if (s.screen === 'undo' || !f.idle()) return [];
  if (f.canStartOver() && f.frame.imageFlow.inPlace?.words['inPlace.startOver']) return [{ label: 'Start over', run: () => f.askStartOver() }];
  if (f.inPlace && f.written() === 'system') {
    // the error's own foot offers it as its one action: a bundle lost after a write, a system that didn't take the change
    if (s.screen === 'error' && (s.bundleLost === 'restore' || s.notTaken)) return [];
    // a stop in the second fastboot session: the restore point from fastboot, where the device still is. A stop in the
    // app's install (after a reload too): this run's restore point, in this browser or in its zip
    const fromError = s.screen === 'error' && s.round === 2 && (s.restorePoint?.status === 'ready' || s.work?.step === 'app-install');
    return [{ label: 'Restore the original system', icon: 'restore', run: () => (fromError ? f.restoreAfterError() : void f.startUndo()) }];
  }
  return [];
}

/** Why the steps behind a write can't be returned to: what is on the device now. */
export function imageLock(f: ImageSession): string | undefined {
  const d = f.frame;
  const w = f.written();
  if (w === 'none') return undefined;
  if (f.inPlace) return w === 'stage' ? `The setup helper is on ${d.the}` : `${cap(d.the)}’s system is changed`;
  return f.state.screen === 'error' ? `The write to ${d.the} didn’t finish` : `${f.recipe.name} is written to ${d.the}`;
}

/**
 * The rail with the way back to each done step (docs/design/kiln/navigation.md): the device picker, the choice and the
 * download, while nothing runs and nothing is written. Behind a write a step is locked, and `imageLock` says why.
 */
export function imageNav(f: ImageSession, s: ImageState): RailStep[] {
  const d = f.frame;
  const steps = imageRail(s, d, f.recipe.community, f.inPlace);
  const idle = f.idle() && !s.resumed;
  if (s.screen === 'undo') {
    // the way back's own rail: from its fastboot step, back to the restore point
    return steps.map((x, i) => (i === 0 && x.mark === 'done' && idle && s.undo?.at === 'fastboot' ? { ...x, go: { run: () => void f.startUndo() } } : x));
  }
  const written = f.written() !== 'none';
  const before = ['image', 'download', 'keep', 'fastboot', 'confirm'].includes(s.screen) && !(f.inPlace && s.round === 2);
  const go = (name: string): RailStep['go'] => {
    if (!idle || written || !before) return undefined;
    if (name === d.short) return { href: '/' };
    if (name === 'Choose' && !s.link && s.screen !== 'choose') return { run: () => f.backToChoice() };
    if ((name === 'Download' || name === 'Prepare') && ['keep', 'fastboot', 'confirm'].includes(s.screen)) return { run: () => f.retry() };
    return undefined;
  };
  // a write that stopped, on a device still in fastboot: its download is one step back (the error's Back to download)
  const redo = s.screen === 'error' && idle && !f.inPlace && !s.wifiOnFailed && f.tryAgainLands() !== 'image';
  return steps.map((x) => {
    if (x.mark !== 'done') return x;
    const to = s.screen === 'choose' && x.name === d.short && idle ? { href: '/' }
      : redo && x.name === 'Download' ? { run: () => f.retry() }
      : go(x.name);
    return to ? { ...x, go: to } : written ? { ...x, lock: true } : x;
  });
}

/** What the resume screen says the device has now, by the mark it opened on. */
function resumeNow(f: ImageSession, s: ImageState): { t: string; d: string } {
  const d = f.frame;
  const the = d.the;
  switch (s.resumed) {
    case 'staged': return { t: `The setup helper is on ${the}`, d: `${cap(the)}’s system hasn’t changed. Next, Kiln copies the parts of it that the install changes.` };
    case 'received': return f.written() === 'system'
      ? { t: 'The new system was partly written', d: 'Finish the install, or put the original system back from your restore point.' }
      : { t: `Kiln has what it needs from ${the}`, d: `${cap(the)}’s system hasn’t changed yet. Next, fastboot mode once more for the install.` };
    case 'flashed': return { t: `${f.recipe.name} is written to ${the}`, d: 'Only its first start is left.' };
    default: return { t: `A write to ${the} didn’t finish`, d: `Install again to put ${the} right.` };
  }
}

/**
 * Where an install stopped, when this page load opens on an earlier one's mark: what's done, what the device has now,
 * and Continue, with the ways out beside it. Continue lands on the same step the mark resumed on before.
 */
export function ImageResume({ f, s }: P) {
  const d = f.frame;
  const rail = imageRail({ ...s, resumed: undefined }, d, f.recipe.community, f.inPlace);
  const done = rail.slice(1).filter((x) => x.mark === 'done').map((x) => x.name);
  const next = rail.find((x) => x.mark !== 'done')?.name;
  const now = resumeNow(f, s);
  const restore = f.inPlace && (s.resumed === 'flashed' || f.written() === 'system');
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Pick up where you left off</h2>
      <p className="lede">{`Your install of ${f.recipe.name} is partway done, and nothing is lost. Continue from the next step, or choose another way below.`}</p>
      <Progress rows={rowsAt([...done, ...(next ? [next] : [])].map((x) => ({ id: x, label: x })), done.length, 'turn')} label="Your install so far" />
      <Note tone={s.resumed === 'received' && f.written() === 'system' ? 'turn' : 'info'} title={now.t} icon={<ClockGlyph />}>{now.d}</Note>
      <Foot back={null}>
        <span className="links">
          {f.canStartOver() && f.frame.imageFlow.inPlace?.words['inPlace.startOver'] && <button type="button" className="btn ghost" onClick={() => f.askStartOver()}>Start over</button>}
          {restore && <button type="button" className="btn ghost" onClick={() => void f.startUndo()}>Restore the original system</button>}
        </span>
        <button type="button" className="btn ember" onClick={() => f.continueResume()}>Continue</button>
      </Foot>
    </section>
  );
}
