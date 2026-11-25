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

/** Glassworks on the device's own software: nothing to download, the APK, and a network for a quick transfer. */
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
      <div className="fields">
        <label className="field"><span>Wi-Fi network (optional)</span>
          <input className="input" autoComplete="off" value={s.wifi.ssid} onChange={(e) => f.setWifi({ ssid: e.target.value })} />
        </label>
        <label className="field"><span>Wi-Fi password</span>
          <input className="input" type="password" autoComplete="off" value={s.wifi.password} onChange={(e) => f.setWifi({ password: e.target.value })} />
        </label>
      </div>
      <ApkInput d={d} m={m} ms={ms} />
      {blocked && <p className="status err" role="alert">{blocked}</p>}
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.backToChoice()}>Back</button></span>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => f.toFastboot()}>Continue</button>
      </div>
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
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.backToChoice()}>Back</button></span>
        <DownloadButton f={f} s={s} label="Download the system image" />
      </div>
      {/* the flashed device is fine and keeps this browser's key: only the first start over adb is left (a real run, 2026-11-19) */}
      <p className="small">{`Already wrote the system image, but first-time setup didn't finish? `}<button type="button" className="link" onClick={() => f.finishFirstStart()}>Finish first-time setup</button>.</p>
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
      <div className="foot">
        <span className="links">
          <button type="button" className="link" onClick={() => f.retry()}>Back</button>
          {/* Glassworks goes through the keep step, which reads Home Assistant's key and then restarts the device (PR 603) */}
          {!s.restarted && !f.inPlace && <><span aria-hidden="true">·</span><button type="button" className="link" disabled={s.connecting} onClick={() => (f.recipe.community ? void f.restartToFastboot() : f.toKeep())}>{d.words['fastboot.overUsb']}</button></>}
        </span>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.connectFastboot()}>Connect</button>
      </div>
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
        <InPlaceNotice words={inPlaceWordsOf(d)} />
        <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} />{` I understand ${d.the}'s app data starts fresh.`}</label>
        <div className="foot">
          <span className="links"><button type="button" className="link" onClick={() => f.retry()}>Back</button></span>
          <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Continue</button>
        </div>
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
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.retry()}>Back</button></span>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Install Glassworks</button>
      </div>
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
  const at = Math.max(0, rows.findIndex((r) => r.id === s.work?.step));
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
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ember pulsing" disabled={s.connecting || (f.inPlace && !m.apkBytes())} onClick={() => void f.connectAdb()}>Connect</button>
      </div>
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
        : s.bundleLost === 'restore' ? `Kiln can't finish this install without the bundle. Restore the original system with the restore point saved before the first write.`
        : s.work && !s.bundleLost && !['first-boot', 'hand-back', 'restart', 'wifi-on', 'app-install'].includes(s.work.step)
        ? `${cap(d.the)} is still in fastboot mode, so leave it plugged in. ${s.files.every((x) => x.state === 'ok') ? 'Click Try again to reconnect and pick up from fastboot.' : 'Click Try again to redownload the files and continue.'}`
        : 'Review the details below and click Try again when you\'re ready.'}</p>
      <pre className="raw">{s.error}</pre>
      {s.flashFailed && !f.recipe.community && !f.inPlace && !s.noFill && (
        <p className="small">{d.words['error.noFill']}</p>
      )}
      <div className="foot">
        <Log s={s} />
        <span className="links">
          {s.flashFailed && !f.recipe.community && !f.inPlace && !s.noFill && <button type="button" className="btn ghost" onClick={() => f.retryWithoutFill()}>Try compatibility write mode</button>}
          {s.wifiOnFailed && s.connecting && <span className="status work" role="status">{`${cap(pickInList(d))}…`}</span>}
          {s.wifiOnFailed && s.connectError && <span className="status err" role="alert">{s.connectError}</span>}
          {s.wifiOnFailed && <button type="button" className="btn ghost" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect again</button>}
          {!s.wifiOnFailed && !f.inPlace && f.tryAgainLands() !== 'image' && <button type="button" className="btn ghost" onClick={() => f.retry()}>Back to download</button>}
          {/* a stop after the restore point was saved: the way back, from fastboot, next to finishing the install */}
          {/* a stop in the app's install after a reload too: the restore point is in this browser, or in the zip */}
          {f.inPlace && s.round === 2 && (s.restorePoint?.status === 'ready' || s.work?.step === 'app-install') && s.bundleLost !== 'restore' && <button type="button" className="btn ghost" onClick={() => void f.restoreAfterError()}>Restore the original system</button>}
        </span>
        {/* the step that stopped, on its own screen: the first start over adb, a write over fastboot, else the download. A
            bundle lost after a write can't be built from again: the way back, on the restore point saved before it */}
        {f.inPlace && s.bundleLost === 'restore' ? <button type="button" className="btn ember" onClick={() => void f.restoreAfterError()}>Restore the original system</button>
          : !s.wifiOnFailed && <button type="button" className="btn ember" onClick={() => f.tryAgain()}>Try again</button>}
      </div>
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
  const r = s.receive ?? { stage: 'waiting-boot', channel: null, progress: null, pageUrl: null, fallback: null, error: null, bluetooth: true };
  return (
    <>
      <ReceiveScreen stage={r.stage} channel={r.channel} bluetoothAvailable={r.bluetooth} progress={r.progress} agentPageUrl={r.pageUrl}
        fallbackReason={r.fallback} errorMessage={r.error} words={inPlaceWordsOf(f.frame)}
        onConnectBluetooth={() => void f.receive()} onRetryLan={() => void f.receive()} onPickFile={(x) => void f.receiveFile(x)}
        onCancel={r.stage === 'transferring' || r.stage === 'connecting' ? () => void f.cancelReceive() : undefined}
        onRetry={() => f.receiveAgain()} />
      <Log s={s} />
    </>
  );
}

/** The restore point, saved in this browser and read back before any system write. */
export function InPlaceRestorePoint({ f, s }: P) {
  const r = s.restorePoint ?? { status: 'saving', digest: null, downloaded: false, error: null, filename: 'restore.sparse' };
  return (
    <>
      <RestorePointScreen status={r.status} verifiedDigest={r.digest} downloaded={r.downloaded} restoreFilename={r.filename} errorMessage={r.error}
        words={inPlaceWordsOf(f.frame)}
        onDownload={() => { void f.restoreZip().then(({ blob, name }) => saveFile(blob, name)).catch(() => {}); }}
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
        <div className="foot"><Log s={s} /><span /></div>
      </section>
    );
  }
  if (u.at === 'working' || u.at === 'error') {
    return (
      <section className={`card${u.at === 'error' ? ' err' : ''}`} aria-labelledby="h">
        <h2 id="h" className={u.at === 'working' ? 'huge' : undefined}>{u.at === 'working' ? 'Keep it plugged in' : 'The restore didn\'t finish'}</h2>
        {u.at === 'working' && <div className="bar" role="progressbar" aria-label="Writing the restore point" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor((u.x ?? 0) * 100)}><i style={{ width: `${Math.max(2, (u.x ?? 0) * 100)}%` }} /></div>}
        {u.error && <pre className="raw">{u.error}</pre>}
        <div className="foot">
          <Log s={s} />
          {u.at === 'error' && <button type="button" className="btn ember" onClick={() => void f.undoFlash()}>Try again</button>}
        </div>
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
        <div className="foot">
          <span className="links"><button type="button" className="link" onClick={() => void f.startUndo()}>Back</button></span>
          <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.undoFlash()}>Connect and restore</button>
        </div>
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
      <div className="foot">
        <span className="links"><button type="button" className="link" disabled={!u.source} onClick={() => f.undoByHand()}>Fastboot by hand</button></span>
        <button type="button" className="btn ember" disabled={!u.source || s.connecting} onClick={() => void f.undoRestart()}>Restart over USB</button>
      </div>
    </section>
  );
}

export function ImageScreens(p: P) {
  const { s } = p;
  return s.screen === 'receive' ? <InPlaceReceive {...p} />
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
