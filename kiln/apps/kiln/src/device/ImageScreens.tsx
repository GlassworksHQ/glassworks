'use client';
// An image over fastboot (ImageSession): what goes on the device (Glassworks or a community image, choice/), the
// download, fastboot, the warning, building and flashing, the first start, and Done with the way back. Same design as
// the rest of Kiln (docs/design/kiln): one idea per screen, one ember button, the log one click away. What the device
// is called and what only it needs said come from the frame (DeviceFrame `the`, `short`, `words`).
import { cap, fmtSize, pickInList, type DeviceFrame } from '@glassworks/kiln-core';
import * as Collapsible from '@radix-ui/react-collapsible';
import { useState } from 'react';
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
import { InPlaceNotice, ReceiveScreen, RestorePointScreen, type InPlaceWords } from './InPlaceScreens';
import { Foot, StartOverDialog } from '../components/Nav';
import type { FlowActionSpec } from '../components/Shell';
import { ManagePageRow } from '../wizard/screens';

type P = { f: ImageSession; s: ImageState; m: AppSession; ms: AppState };

/**
 * The install's steps on the rail, before the standard flow's Set up and Done. Arriving by an image's link skips the
 * choice. An in-place install (`inPlace`) has no download: it prepares, connects fastboot, receives, then installs.
 */
export const imageSteps = (f: Pick<DeviceFrame, 'short'>, link: boolean, inPlace = false): string[] =>
  (inPlace ? [f.short, 'Choose', 'Prepare', 'Fastboot', 'Receive', 'Install'] : [f.short, 'Choose', 'Download', 'Fastboot', 'Install']).filter((x) => !link || x !== 'Choose');

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
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : s.screen === 'adb' ? 'turn' : 'now';
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

function Log({ s }: { s: ImageState }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
      <Collapsible.Trigger className="link">{open ? 'Hide technical log' : 'Technical log'}</Collapsible.Trigger>
      <Collapsible.Content className="tech"><pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre></Collapsible.Content>
    </Collapsible.Root>
  );
}

/** The in-place screens' words, from the frame's data (its `inPlace.*` words). */
export function inPlaceWordsOf(d: DeviceFrame): InPlaceWords {
  const w = d.imageFlow.inPlace?.words;
  return {
    the: d.the,
    ...(w ? { bootHint: w['inPlace.receive.lede'], coverNote: w['inPlace.coverNote'], changes: w['inPlace.changes'], restoreLede: w['inPlace.restorePoint.lede'] } : {}),
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

/** Glassworks on the device's own software: nothing to download, the APK, and the person's Wi-Fi for its first start. */
export function InPlaceStart({ f, s, m, ms }: P) {
  const d = f.frame;
  const w = d.imageFlow.inPlace?.words;
  const blocked = f.blocked();
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly f={f.frame} />
      <h2 id="h">{`Install Glassworks on ${d.the}, keeping its system`}</h2>
      <p className="lede">{w?.['inPlace.stage.lede']}</p>
      {s.changed && <p className="small">{`An earlier try already changed ${d.the}.`}</p>}
      <InPlaceNotice words={inPlaceWordsOf(d)} />
      <YourWifi f={f} s={s} />
      <ApkInput d={d} m={m} ms={ms} />
      {blocked && <p className="status err" role="alert">{blocked}</p>}
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
      {s.changed && <p className="small">{`An earlier try already changed ${d.the}.`}</p>}
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
      {blocked && ready && <p className="status err" role="alert">{blocked}</p>}
      {/* the flashed device is fine and keeps this browser's key: only the first start over adb is left (a real run, 2026-11-19) */}
      <p className="small">{`Already wrote the system image, but first-time setup didn't finish? `}<button type="button" className="link" onClick={() => f.finishFirstStart()}>Finish first-time setup</button></p>
      <Foot>
        <DownloadButton f={f} s={s} label="Download the system image" />
      </Foot>
    </section>
  );
}

export function ImageFastboot({ f, s }: P) {
  const d = f.frame;
  const r = s.fastboot;
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h">{`Put ${d.the} in fastboot mode`}</h2>
      {/* by hand first: most people install over the vendor's software, which Kiln can't reach over adb (the maintainer, 2026-11-19) */}
      {f.inPlace && s.round === 2 ? <>
        <p className="lede">{d.imageFlow.inPlace?.words['inPlace.fastboot.again']}</p>
        {s.leftHotspot && <p className="status ok" role="note">{`✓ The bundle is in. You can switch this computer back to your usual Wi-Fi now.`}</p>}
        <p className="small">{`The bundle is saved in this browser. Click Connect and ${pickInList(d)}.`}</p>
      </> : s.restarted ? <p className="lede">{rich(d.words['fastboot.restarted'])}</p> : <>
        <Recovery data={d.recovery} />
        <ol className="join">
          {lines(d.words['fastboot.byHandSteps']).map((x) => <li key={x}>{x}</li>)}
        </ol>
        <p className="small">{d.words['fastboot.byHandNote']}</p>
        {f.inPlace && <p className="small">{d.imageFlow.inPlace?.words['inPlace.coverNote']}</p>}
        <p className="small">{`Then click Connect and ${pickInList(d)}.`}</p>
      </>}
      {r && !r.ok && (
        <ol className="join">
          {r.checks.map((c) => <li key={c.what} className={c.ok ? 'done' : 'fail'}>{c.what}: {c.found}{!c.ok && <span className="sub-step">Needs {c.need}. Nothing was written.</span>}</li>)}
        </ol>
      )}
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      {/* the second fastboot has no Back: the rail's Start over says what it resets before it does */}
      <Foot busy={s.connecting}>
        {/* Glassworks goes through the keep step, which reads Home Assistant's key and then restarts the device (PR 603) */}
        {!s.restarted && !f.inPlace ? <button type="button" className="btn ghost" disabled={s.connecting} onClick={() => (f.recipe.community ? void f.restartToFastboot() : f.toKeep())}>{d.words['fastboot.overUsb']}</button> : <span />}
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.connectFastboot()}>Connect</button>
      </Foot>
    </section>
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
        <h2 id="h">Add the setup agent</h2>
        <ol className="join">
          {r.checks.map((c) => <li key={c.what} className="done">{c.what}: {c.found}</li>)}
        </ol>
        <p className="lede">{d.imageFlow.inPlace?.words['inPlace.confirm.lede']}</p>
        <InPlaceNotice words={inPlaceWordsOf(d)} cover />
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
      <ol className="join">
        {r.checks.map((c) => <li key={c.what} className="done">{c.what}: {c.found}</li>)}
      </ol>
      <p className="lede">{d.words['confirm.lede']}</p>
      {rev && <p className="small">{rev}.</p>}
      <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} />{` ${d.words['confirm.understand']}`}</label>
      <Foot>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Install Glassworks</button>
      </Foot>
    </section>
  );
}

/** The install screen's rows for each planned step from the warning on (ImageSession.writes()). */
const rowsOf = (d: DeviceFrame): Record<string, { id: WorkStep; text: string }[]> => ({
  patch: [
    { id: 'patch', text: 'Add Glassworks and the boot animation to the image' },
    { id: 'system', text: 'Prepare the system image for writing' },
    { id: 'userdata', text: 'Prepare fresh storage for this browser' },
  ],
  'flash-system': [{ id: 'flash-system', text: 'Write the operating system' }],
  'flash-userdata': [{ id: 'flash-userdata', text: 'Write fresh storage' }],
  restart: [{ id: 'restart', text: `Restart ${d.the}` }],
  'first-start': [{ id: 'first-boot', text: 'Finish first-time setup' }],
  'system-key': [{ id: 'system-key', text: 'Replace the built-in USB key with this browser’s' }],
  'adb-key': [{ id: 'adb-key', text: 'Replace the shared USB key with this browser’s' }],
  wifi: [{ id: 'wifi', text: 'Save your Wi-Fi network for first start' }],
  'wifi-on': [{ id: 'wifi-on', text: 'Turn Wi-Fi on after first start' }],
  stage: [{ id: 'stage', text: 'Prepare the setup agent' }],
  'in-place': [{ id: 'in-place', text: 'Build and check the changes from the bundle' }],
  'app-install': [{ id: 'app-install', text: 'Install Glassworks and hide the stock apps' }],
});

/** An in-place install's steps on the working screen: its first fastboot session's, or its second's. */
function stepsOnScreen(f: ImageSession, s: ImageState) {
  const all = f.writes();
  if (!f.inPlace) return all;
  const cut = all.findIndex((x) => x.do === 'receive');
  return s.round === 2 ? all.slice(cut + 1) : all.slice(0, cut);
}
/** Issue 580: the hand-back row joins Glassworks' first start, when the tab holds Home Assistant's key. */
const HAND_BACK = { id: 'hand-back' as WorkStep, text: 'Restore your Home Assistant connection key' };

export function ImageWorking({ f, s }: P) {
  const ROWS = rowsOf(f.frame);
  const rows = stepsOnScreen(f, s).flatMap((x) => [...(ROWS[x.do === 'flash' ? `flash-${x.partition}` : x.do] ?? []), ...(x.do === 'first-start' && keyHeld(s.key) ? [HAND_BACK] : [])]);
  const row = (id?: string) => rows.findIndex((r) => r.id === id);
  // an in-place install lists no first start of its own: the wait for it belongs to its restart row, not the first row
  const at = Math.max(0, row(s.work?.step) >= 0 ? row(s.work?.step) : s.work?.step === 'first-boot' ? row('restart') : -1);
  const x = s.work?.x ?? 0;
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h" className="huge">Keep it plugged in</h2>
      <ol className="join">
        {rows.map((r, i) => <li key={`${r.id}-${i}`} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r.text}{i === at && x > 0 && x < 1 && <span className="sub-step">{Math.floor(x * 100)}%</span>}
          {/* the image is small to send; the device then writes an empty filesystem (212 MiB on the first frame), with nothing to report (a real run, 2026-11-19) */}
          {i === at && r.id === 'flash-userdata' && <span className="sub-step">This takes a minute or two, and the progress bar may pause — that&apos;s normal.</span>}</li>)}
      </ol>
      <div className="bar" role="progressbar" aria-label={rows[at]?.text} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor(((at + x) / rows.length) * 100)}>
        <i style={{ width: `${Math.max(2, ((at + x) / rows.length) * 100)}%` }} />
      </div>
      <Log s={s} />
    </section>
  );
}

export function ImageAdb({ f, s, m, ms }: P) {
  const d = f.frame;
  const r = f.recipe;
  return (
    <section className="card" aria-labelledby="h">
      {r.community && <Whose r={r} />}
      <span className="turn-label"><span className="pulse" />Your turn</span>
      <h2 id="h">One last click</h2>
      {r.community
        ? <p className="lede">{`Its first start takes a few minutes. Click Connect and ${pickInList(d)}, and Kiln switches its Wi-Fi on.`}</p>
        : <p className="lede">{rich(d.words['adb.firstStart'])}</p>}
      {f.inPlace && <p className="small">{d.imageFlow.inPlace?.words['inPlace.appInstall.lede']}</p>}
      {/* until an APK passes its checks: one the checks refused (another app's, say) keeps the picker, with why */}
      {f.inPlace && !m.apkBytes() && <ApkInput d={d} m={m} ms={ms} />}
      {s.connecting && !s.adbWaiting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.adbWaiting && <><p className="status work" role="status">{`Waiting for ${d.the} to finish starting…`}</p><div className="bar wait"><i /></div></>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      {/* the system is written: no Start over. The honest ways out are the restore point (the rail's), or writing the image again */}
      <Foot>
        {!s.connecting && !r.community && !f.inPlace ? <button type="button" className="btn ghost" onClick={() => f.retry()}>Write the image again</button> : <span />}
        <button type="button" className="btn ember pulsing" disabled={s.connecting || (f.inPlace && !m.apkBytes())} onClick={() => void f.connectAdb()}>Connect</button>
      </Foot>
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
        <div className="well" role="alert">
          <div className="grow">
            <span className="status err">Glassworks isn&apos;t the only home screen yet.</span>
            <span className="small">
              Android opened {fb.home.result === 'chooser' ? 'its list of home screens' : <code>{fb.home.focused ?? 'something else'}</code>}{fb.home.left.length ? <>: <code>{fb.home.left.join(', ')}</code> is still a home app</> : ''}.
              {` ${d.words['done.noTouch']}`}
            </span>
          </div>
          <button type="button" className="btn ghost" disabled={s.fixing} onClick={() => void f.fixHome()}>{s.fixing ? 'Hiding it…' : 'Hide the other launcher again'}</button>
        </div>
      )}
      {noHome && (
        <div className="well" role="alert">
          <div className="grow">
            <span className="status err">This Glassworks build has no home screen.</span>
            <span className="small">
              {fb.home.left.length ? <code>{fb.home.left.join(', ')}</code> : 'The image’s launcher'}{' stays its home screen.'}
              {` ${d.words['done.noHomeFix']}`}
            </span>
          </div>
        </div>
      )}
      {fb && <KeyOutcome k={s.key} d={d} />}
      {f.inPlace && (
        <div className="well">
          <div className="grow">
            <span className="status">The way back</span>
            <span className="small">{d.imageFlow.inPlace?.words['inPlace.wayBack']}</span>
          </div>
          <button type="button" className="btn ghost" onClick={() => { away(); void f.startUndo(); }}>Restore the original system</button>
        </div>
      )}
      {other && !f.inPlace && <WayBack f={f} other={other} then={away} />}
      {rev && <p className="small">{rev}.</p>}
    </>
  );
}

export function ImageError({ f, s }: P) {
  const d = f.frame;
  return (
    <section className="card err" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h">The install didn&apos;t finish</h2>
      <p className="lede">{s.wifiOnFailed
        ? 'Wi-Fi didn\'t turn on yet. Check the details below and click Connect again when you\'re ready.'
        : s.work && !['first-boot', 'hand-back', 'restart', 'wifi-on', 'app-install'].includes(s.work.step)
        ? `${cap(d.the)} is still in fastboot mode, so leave it plugged in. ${s.files.every((x) => x.state === 'ok') ? 'Click Try again to reconnect and pick up from fastboot.' : 'Click Try again to redownload the files and continue.'}`
        : 'Review the details below and click Try again when you\'re ready.'}</p>
      <pre className="raw">{s.error}</pre>
      {s.flashFailed && !f.recipe.community && !f.inPlace && !s.noFill && (
        <p className="small">{d.words['error.noFill']}</p>
      )}
      {s.wifiOnFailed && s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.wifiOnFailed && s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      {/* Back to the download, when the step that stopped can start over from it; Start over and the restore point are the rail's */}
      <Foot back={!s.wifiOnFailed && !f.inPlace && f.tryAgainLands() !== 'image' ? { run: () => f.retry() } : undefined} backLabel="Back to download">
        <span className="links">
          {s.flashFailed && !f.recipe.community && !f.inPlace && !s.noFill && <button type="button" className="btn ghost" onClick={() => f.retryWithoutFill()}>Try compatibility write mode</button>}
          {s.wifiOnFailed && <button type="button" className="btn ghost" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect again</button>}
        </span>
        {/* the step that stopped, on its own screen: the first start over adb, a write over fastboot, else the download */}
        {!s.wifiOnFailed && <button type="button" className="btn ember" onClick={() => f.tryAgain()}>Try again</button>}
      </Foot>
      <Log s={s} />
    </section>
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
      <ReceiveScreen stage={r.stage} channel={r.channel} bluetoothAvailable={r.bluetooth} progress={r.progress} agentPageUrl={r.pageUrl}
        fallbackReason={r.fallback} errorMessage={r.error} words={inPlaceWordsOf(f.frame)}
        onConnectBluetooth={() => void f.receive()} onRetryLan={() => void f.receive()} onPickFile={(x) => void f.receiveFile(x)}
        onCancel={r.stage === 'transferring' || r.stage === 'connecting' ? () => void f.cancelReceive() : undefined}
        onRetry={() => f.receiveAgain()} onContinueUnverified={() => void f.receiveUnverified()}
        network={r.network} joined={r.joined} wifiFailed={r.wifiFailed} onContinueWifi={() => void f.receiveWifi()} onByHand={() => f.receiveByHand()} />
      <Log s={s} />
    </>
  );
}

/** The restore point, saved in this browser and read back before any system write. */
export function InPlaceRestorePoint({ f, s }: P) {
  const r = s.restorePoint ?? { status: 'saving', digest: null, downloaded: false, error: null, filename: 'restore.sparse' };
  const [failed, setFailed] = useState<string | null>(null);
  return (
    <>
      <RestorePointScreen status={r.status} verifiedDigest={r.digest} downloaded={r.downloaded} restoreFilename={r.filename} errorMessage={r.error}
        words={inPlaceWordsOf(f.frame)} downloadError={failed}
        onDownload={() => { setFailed(null); void f.restoreZip().then(({ blob, name }) => saveFile(blob, name)).catch((e: Error) => setFailed(e.message)); }}
        onContinue={() => void f.continueInstall()} onRetry={() => f.retryRestorePoint()} />
      <Log s={s} />
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
        <p className="lede">{`Kiln wrote the restore point over every block it had changed, and ${d.the} is restarting.`}</p>
        <Foot back={null}>
          <button type="button" className="btn ghost" onClick={() => f.backToChoice()}>Install Glassworks again</button>
          <a className="btn ember" href="/">Back to Kiln</a>
        </Foot>
        <Log s={s} />
      </section>
    );
  }
  if (u.at === 'working' || u.at === 'error') {
    return (
      <section className={`card${u.at === 'error' ? ' err' : ''}`} aria-labelledby="h">
        <h2 id="h" className={u.at === 'working' ? 'huge' : undefined}>{u.at === 'working' ? 'Keep it plugged in' : 'The restore didn\'t finish'}</h2>
        {u.at === 'working' && <div className="bar" role="progressbar" aria-label="Writing the restore point" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor((u.x ?? 0) * 100)}><i style={{ width: `${Math.max(2, (u.x ?? 0) * 100)}%` }} /></div>}
        {u.error && <pre className="raw">{u.error}</pre>}
        {u.at === 'error' && <p className="small">{`${cap(d.the)} stays in fastboot mode, so leave it plugged in. You can run the restore again any time, from this browser or the .zip you downloaded.`}</p>}
        {u.at === 'error' && <Foot back={null}><button type="button" className="btn ember" onClick={() => void f.undoFlash()}>Try again</button></Foot>}
        <Log s={s} />
      </section>
    );
  }
  if (u.at === 'fastboot') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">{`Put ${d.the} in fastboot mode`}</h2>
        {u.restarted ? <p className="lede">{rich(d.words['fastboot.restarted'])}</p> : <>
          <Recovery data={d.recovery} />
          <ol className="join">{lines(d.words['fastboot.byHandSteps']).map((x) => <li key={x}>{x}</li>)}</ol>
          <p className="small">{d.words['fastboot.byHandNote']}</p>
        </>}
        {u.error && <p className="status err" role="alert">{u.error}</p>}
        {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
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
      {u.source === 'browser' && <p className="status ok" role="status">✓ This browser has the restore point it saved</p>}
      {u.source === 'file' && <p className="status ok" role="status">{`✓ ${u.fileName ?? 'The restore point'} checks out`}</p>}
      {!u.source && <p className="small">This browser has no restore point for it. Pick the copy you downloaded.</p>}
      <div className="fields">
        <label className="field"><span>Restore point (.zip)</span>
          <input className="input" type="file" accept=".zip,application/zip" onChange={(e) => { const x = e.target.files?.[0]; if (x) void f.pickRestoreZip(x); }} />
        </label>
      </div>
      {u.error && <p className="status err" role="alert">{u.error}</p>}
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <Foot back={{ run: () => f.backToChoice() }} busy={s.connecting}>
        <button type="button" className="btn ghost" disabled={!u.source || s.connecting} onClick={() => f.undoByHand()}>Fastboot by hand</button>
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
      keeps={['Your Wi-Fi and the app you picked', 'The bundle saved in this browser']} />
  );
}

/** The image flow's ways out, under the rail: Start over while it is honest, else the restore point once the system is changed. */
export function imageActions({ f, s }: Pick<P, 'f' | 's'>): FlowActionSpec[] {
  if (s.screen === 'undo' || !f.idle()) return [];
  if (f.canStartOver() && f.frame.imageFlow.inPlace?.words['inPlace.startOver']) return [{ label: 'Start over', run: () => f.askStartOver() }];
  if (f.inPlace && f.written() === 'system') {
    // a stop in the second fastboot session: the restore point from fastboot, where the device still is
    const fromError = s.screen === 'error' && s.round === 2 && s.restorePoint?.status === 'ready';
    return [{ label: 'Restore the original system', icon: 'restore', run: () => (fromError ? f.restoreAfterError() : void f.startUndo()) }];
  }
  return [];
}

/** Why the steps behind a write can't be returned to: what is on the device now. */
export function imageLock(f: ImageSession): string | undefined {
  const d = f.frame;
  const w = f.written();
  if (w === 'none') return undefined;
  if (f.inPlace) return w === 'stage' ? `The setup agent is on ${d.the}` : `${cap(d.the)}’s system is changed`;
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
    case 'staged': return { t: `The setup agent is on ${the}`, d: `${cap(the)}’s system hasn’t changed. Next, get the bundle from it.` };
    case 'received': return f.written() === 'system'
      ? { t: 'The new system was partly written', d: 'Finish the install, or restore the original system from the restore point.' }
      : { t: 'The bundle is saved in this browser', d: `${cap(the)}’s system hasn’t changed yet. Next, fastboot once more for the install.` };
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
      <p className="lede">{`Your install of ${f.recipe.name} is partway done.`}</p>
      <ol className="resume-done" aria-label="Done so far">
        {done.map((x) => <li key={x}>{x}</li>)}
        {next && <li className="next">{`Next: ${next}`}</li>}
      </ol>
      <div className="resume-now" role="note">
        <span className="ico" aria-hidden="true">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
        </span>
        <span><span className="t">{now.t}</span><br /><span className="d">{now.d}</span></span>
      </div>
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
