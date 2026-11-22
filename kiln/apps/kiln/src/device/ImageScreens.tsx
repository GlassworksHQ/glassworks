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
import { ManagePageRow } from '../wizard/screens';

type P = { f: ImageSession; s: ImageState; m: AppSession; ms: AppState };

/** The install's steps on the rail, before the standard flow's Set up and Done. Arriving by an image's link skips the choice. */
export const imageSteps = (f: Pick<DeviceFrame, 'short'>, link: boolean): string[] =>
  [f.short, 'Choose', 'Download', 'Fastboot', 'Install'].filter((x) => !link || x !== 'Choose');

/** Set up is Glassworks' (the standard flow, after the first start): a community image's install ends at Install. */
export function imageRail(s: ImageState, f: Pick<DeviceFrame, 'short' | 'id'>, community = false): RailStep[] {
  const names = [f.short, 'Choose', 'Download', 'Fastboot', 'Install', ...(community ? [] : ['Set up']), 'Done'];
  const at = { choose: 1, image: 2, download: 2, keep: 3, fastboot: 3, confirm: 3, working: 4, adb: 4, error: 4, done: names.length }[s.screen];
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

/** Glassworks: its image, downloaded and checked, and what Kiln builds into it. */
export function ImageImage({ f, s, m, ms }: P) {
  const d = f.frame;
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
      {s.restarted ? <p className="lede">{rich(d.words['fastboot.restarted'])}</p> : <>
        <Recovery data={d.recovery} />
        <ol className="join">
          {lines(d.words['fastboot.byHandSteps']).map((x) => <li key={x}>{x}</li>)}
        </ol>
        <p className="small">{d.words['fastboot.byHandNote']}</p>
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
          {!s.restarted && <><span aria-hidden="true">·</span><button type="button" className="link" disabled={s.connecting} onClick={() => (f.recipe.community ? void f.restartToFastboot() : f.toKeep())}>{d.words['fastboot.overUsb']}</button></>}
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
  wifi: [{ id: 'wifi', text: 'Save your Wi-Fi network for first start' }],
  'wifi-on': [{ id: 'wifi-on', text: 'Turn Wi-Fi on after first start' }],
});
/** Issue 580: the hand-back row joins Glassworks' first start, when the tab holds Home Assistant's key. */
const HAND_BACK = { id: 'hand-back' as WorkStep, text: 'Restore your Home Assistant connection key' };

export function ImageWorking({ f, s }: P) {
  const ROWS = rowsOf(f.frame);
  const rows = f.writes().flatMap((x) => [...(ROWS[x.do === 'flash' ? `flash-${x.partition}` : x.do] ?? []), ...(x.do === 'first-start' && keyHeld(s.key) ? [HAND_BACK] : [])]);
  const at = Math.max(0, rows.findIndex((r) => r.id === s.work?.step));
  const x = s.work?.x ?? 0;
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h" className="huge">Keep it plugged in</h2>
      <ol className="join">
        {rows.map((r, i) => <li key={r.id} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r.text}{i === at && x > 0 && x < 1 && <span className="sub-step">{Math.floor(x * 100)}%</span>}
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

export function ImageAdb({ f, s }: P) {
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
      {s.connecting && !s.adbWaiting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.adbWaiting && <><p className="status work" role="status">{`Waiting for ${d.the} to finish starting…`}</p><div className="bar wait"><i /></div></>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ember pulsing" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect</button>
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
      {other && <WayBack f={f} other={other} then={away} />}
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
      <p className="lede">{s.work && !['first-boot', 'hand-back', 'restart', 'wifi-on'].includes(s.work.step)
        ? `${cap(d.the)} is still in fastboot mode, so leave it plugged in. ${s.files.every((x) => x.state === 'ok') ? 'Click Try again to reconnect and pick up from fastboot.' : 'Click Try again to redownload the files and continue.'}`
        : 'Review the details below and click Try again when you\'re ready.'}</p>
      <pre className="raw">{s.error}</pre>
      {s.flashFailed && !f.recipe.community && !s.noFill && (
        <p className="small">{d.words['error.noFill']}</p>
      )}
      <div className="foot">
        <Log s={s} />
        <span className="links">
          {s.flashFailed && !f.recipe.community && !s.noFill && <button type="button" className="btn ghost" onClick={() => f.retryWithoutFill()}>Try compatibility write mode</button>}
          {s.wifiOnFailed && s.connecting && <span className="status work" role="status">{`${cap(pickInList(d))}…`}</span>}
          {s.wifiOnFailed && s.connectError && <span className="status err" role="alert">{s.connectError}</span>}
          {s.wifiOnFailed && <button type="button" className="btn ghost" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect again</button>}
          {!s.wifiOnFailed && f.tryAgainLands() !== 'image' && <button type="button" className="btn ghost" onClick={() => f.retry()}>Back to download</button>}
        </span>
        {/* the step that stopped, on its own screen: the first start over adb, a write over fastboot, else the download */}
        {!s.wifiOnFailed && <button type="button" className="btn ember" onClick={() => f.tryAgain()}>Try again</button>}
      </div>
    </section>
  );
}

export function ImageScreens(p: P) {
  const { s } = p;
  return s.screen === 'choose' ? <Choose f={p.f} s={s} />
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
