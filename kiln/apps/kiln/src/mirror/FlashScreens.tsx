'use client';
// The Mirror's install over fastboot (FlashSession): what goes on it (Glassworks or a community
// image, choice/), the download, fastboot, the warning, building and flashing, the first start, and
// Done with the way back. Same design as the rest of Kiln (docs/design/kiln): one idea per screen, one
// ember button, the log one click away.
import { credits } from '@glassworks/lululemon-mirror-installer';
import { fmtSize } from '@glassworks/kiln-core';
import * as Collapsible from '@radix-ui/react-collapsible';
import { useState } from 'react';
import { BuiltOn, Credit, stepCredits } from '../components/Credit';
import { FRAMES } from '../frames/catalog';
import { Recovery } from '../recovery/Recovery';
import type { RecoveryData } from '../recovery/player';
import recovery from '../../../../../frames/lululemon-mirror/design/recovery.json';
import type { RailMark, RailStep } from '../wizard/state';
import type { FlashSession, FlashState, WorkStep } from './flash';
import { Choose, ConfirmCommunity, DoneCommunity, Download, DownloadButton, FileRow, WayBack, Whose } from './choice/ChoiceScreens';
import { ReleaseApk, TestOnly } from './ReleaseApk';
import type { MirrorSession, MirrorState } from './session';
import { SilentUpdates, mirrorSilent } from '../components/SilentUpdates';

type P = { f: FlashSession; s: FlashState; m: MirrorSession; ms: MirrorState };

/** Arriving by an image's link skips the choice, so its rail has no Choose step. */
export function flashRail(s: FlashState): RailStep[] {
  const names = ['Mirror', 'Choose', 'Download', 'Fastboot', 'Install', 'Done'];
  const at = { choose: 1, image: 2, download: 2, fastboot: 3, confirm: 3, working: 4, adb: 4, error: 4, done: 6 }[s.screen];
  return names.map((name, i): RailStep => {
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : s.screen === 'adb' ? 'turn' : 'now';
    return { name, mark };
  }).filter((x) => !s.link || x.name !== 'Choose');
}

/** The stack's layers are Glassworks' layers: it stays empty while a community image installs. */
export function flashLevel(s: FlashState, community = false): number {
  if (community) return 0;
  if (s.screen === 'done') return 4;
  if (s.screen === 'adb') return 3;
  if (s.screen === 'working') return s.work?.step.startsWith('flash') || s.work?.step === 'restart' ? 2 : 1;
  return 0;
}

/** Glass Panes, from the Mirror's frame.json `credits` (frames/README.md#credits). */
const glassPanes = () => stepCredits('lululemon-mirror', credits, ['image']);
/** The Mirror's reversibility, as frame.json labels it (issue 146). */
const REVERSIBLE = FRAMES.find((x) => x.id === 'lululemon-mirror')?.ratings.reversible.label;
/** The JSON import's inferred type is wider than RecoveryData; test/recovery-data.test.ts checks its shape. */
const RECOVERY = recovery as unknown as RecoveryData;

/** At the step that hands over the image: who built it, and whose its vendor files are. */
function ImageCredit() {
  return (
    <>
      {glassPanes().map((c) => <Credit key={c.id} c={c} built="image" />)}
      <p className="small">Its vendor files belong to Qualcomm and Quectel and come from the Glass Panes release, not from Glassworks.</p>
    </>
  );
}

function Log({ s }: { s: FlashState }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible.Root open={open} onOpenChange={setOpen} className="tech">
      <Collapsible.Trigger className="link">{open ? 'Hide technical log' : 'Technical log'}</Collapsible.Trigger>
      <Collapsible.Content className="tech"><pre className="rawlog">{[...s.log].reverse().join('\n') || 'Nothing yet.'}</pre></Collapsible.Content>
    </Collapsible.Root>
  );
}

/** Glassworks: its image, downloaded and checked, and what Kiln builds into it. */
export function FlashImage({ f, s, m, ms }: P) {
  const blocked = f.blocked();
  const ready = s.files.every((x) => x.state === 'ok');
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly />
      <h2 id="h">Install Glassworks on the Mirror.</h2>
      <p className="lede">Kiln adds Glassworks to the Mirror&apos;s community Android image in this browser, then writes it over USB. It downloads the image from the Glass Panes release and checks it as it arrives.</p>
      {s.files.map((x) => <FileRow key={x.name} f={f} file={x} />)}
      <ImageCredit />
      {ms.release && <ReleaseApk m={m} ms={ms} />}
      <div className="fields">
        {!ms.release && (
          <label className="field"><span>Glassworks APK (legacy build, test only)</span>
            <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
              onChange={(e) => { const x = e.target.files?.[0]; if (x) void m.pickApk(x.name, x.arrayBuffer().then((b) => new Uint8Array(b))); }} />
          </label>
        )}
        <label className="field"><span>Home Assistant (optional)</span>
          <input className="input" type="url" placeholder="http://homeassistant.local:8123" value={s.haUrl} onChange={(e) => f.setHaUrl(e.target.value.trim())} />
        </label>
        <label className="field"><span>Wi-Fi network (optional)</span>
          <input className="input" autoComplete="off" value={s.wifi.ssid} onChange={(e) => f.setWifi({ ssid: e.target.value })} />
        </label>
        <label className="field"><span>Wi-Fi password (if it has one)</span>
          <input className="input" type="password" autoComplete="off" value={s.wifi.password} onChange={(e) => f.setWifi({ password: e.target.value })} />
        </label>
      </div>
      {ms.apk && (!ms.release || ms.apk.error) && <p className={`small${ms.apk.error ? ' status err' : ''}`}>{ms.apk.name}: {ms.apk.error ?? `${ms.apk.package}, ${fmtSize(ms.apk.size)}`}</p>}
      {blocked && ready && <p className="status err" role="alert">{blocked}</p>}
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.backToChoice()}>Back</button></span>
        <DownloadButton f={f} s={s} label="Download the system image" />
      </div>
      {/* the flashed Mirror is fine and keeps this browser's key: only the first start over adb is left (Mirror run, 2026-11-19) */}
      <p className="small">Flashed this Mirror already, and its first start didn&apos;t finish? <button type="button" className="link" disabled={!!f.inputsBlocked()} onClick={() => f.finishFirstStart()}>Finish its first start</button>, with Home Assistant and the Wi-Fi above.</p>
    </section>
  );
}

export function FlashFastboot({ f, s }: P) {
  const r = s.fastboot;
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h">Put the Mirror in fastboot.</h2>
      <Recovery data={RECOVERY} />
      <ol className="join">
        <li>Switch the Mirror off with the rocker switch in its bottom edge, unplug its power cord, and lay it face down. Take out the 4 screws in the bottom edge that hold the stand, and take the stand off. Then take out the 14 screws in the back, 2 of them either side of the recess by the feet, and lift the back off.</li>
        <li>Plug the power cord back into its socket in that recess. Leave the switch off.</li>
        <li>Plug the micro-USB port, in the top end&apos;s corner, into this computer.</li>
        <li>On the small green board at the top end, hold VOL&minus; (SW3): of its two buttons side by side, the one nearer the top end&apos;s edge.</li>
        <li>Keep holding while you switch the Mirror on, and for about 5 seconds more. Its screen stays black: that&apos;s fastboot.</li>
      </ol>
      <p className="small">Hold only VOL&minus;. Both buttons together start a Qualcomm mode Kiln can&apos;t use, and VOL+ opens an update screen. Some Mirrors have a service hatch in the back that reaches the board: open that instead.</p>
      <p className="small">Then click Connect and pick the Mirror (<code>Android</code>) in the browser&apos;s list. Kiln only reads it here.</p>
      {r && !r.ok && (
        <ol className="join">
          {r.checks.map((c) => <li key={c.what} className={c.ok ? 'done' : 'fail'}>{c.what}: {c.found}{!c.ok && <span className="sub-step">Needs {c.need}. Kiln stops here; nothing was written.</span>}</li>)}
        </ol>
      )}
      {s.connecting && <p className="status work" role="status">Pick the Mirror in the browser&apos;s list…</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.retry()}>Back</button></span>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.connectFastboot()}>Connect</button>
      </div>
    </section>
  );
}

export function FlashConfirm(p: P) {
  const { f, s } = p;
  if (f.recipe.community) return <ConfirmCommunity f={f} s={s} reversible={REVERSIBLE} />;
  const r = s.fastboot!;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">This replaces lululemon’s software for good.</h2>
      <ol className="join">
        {r.checks.map((c) => <li key={c.what} className="done">{c.what}: {c.found}</li>)}
      </ol>
      <p className="lede">
        Kiln can&apos;t back up the Mirror first: its bootloader can&apos;t read anything back. So lululemon&apos;s software can&apos;t be
        restored, by Kiln or anyone. The way back from here is the plain Glass Panes image. Everything on the Mirror is replaced.
      </p>
      {REVERSIBLE && <p className="small">{REVERSIBLE}.</p>}
      <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} /> I understand lululemon&apos;s software can&apos;t be restored.</label>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.retry()}>Back</button></span>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Install Glassworks</button>
      </div>
    </section>
  );
}

/** The install screen's rows for each planned step from the warning on (FlashSession.writes()). */
const ROWS: Record<string, { id: WorkStep; text: string }[]> = {
  patch: [
    { id: 'patch', text: 'Add Glassworks and the boot animation to the image' },
    { id: 'system', text: 'Write the system image as fastboot sends it' },
    { id: 'userdata', text: 'Make a fresh userdata with this browser’s key' },
  ],
  'flash-system': [{ id: 'flash-system', text: 'Flash system' }],
  'flash-userdata': [{ id: 'flash-userdata', text: 'Flash userdata' }],
  restart: [{ id: 'restart', text: 'Restart the Mirror' }],
  'first-start': [{ id: 'first-boot', text: 'First start: Home Assistant, Wi-Fi, home screen' }],
};

export function FlashWorking({ f, s }: P) {
  const rows = f.writes().flatMap((x) => ROWS[x.do === 'flash' ? `flash-${x.partition}` : x.do] ?? []);
  const at = Math.max(0, rows.findIndex((r) => r.id === s.work?.step));
  const x = s.work?.x ?? 0;
  return (
    <section className="card" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h" className="huge">Keep it plugged in.</h2>
      <ol className="join">
        {rows.map((r, i) => <li key={r.id} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r.text}{i === at && x > 0 && x < 1 && <span className="sub-step">{Math.floor(x * 100)}%</span>}
          {/* the image is small to send; the Mirror then writes an empty filesystem's 212 MiB, with nothing to report (Mirror run, 2026-11-19) */}
          {i === at && r.id === 'flash-userdata' && <span className="sub-step">This part takes a minute or two, and the bar hardly moves.</span>}</li>)}
      </ol>
      <div className="bar" role="progressbar" aria-label={rows[at]?.text} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor(((at + x) / rows.length) * 100)}>
        <i style={{ width: `${Math.max(2, ((at + x) / rows.length) * 100)}%` }} />
      </div>
      <Log s={s} />
    </section>
  );
}

export function FlashAdb({ f, s }: P) {
  return (
    <section className="card" aria-labelledby="h">
      <span className="turn-label"><span className="pulse" />Your turn</span>
      <h2 id="h">One last click.</h2>
      <p className="lede">The Mirror is starting Glassworks for the first time. Its glass stays dark for a few minutes while it does: that&apos;s expected, so leave it plugged in. Click Connect and pick the Mirror in the browser&apos;s list now: Kiln waits until it&apos;s ready, and it knows this browser&apos;s key, so it doesn&apos;t ask on its screen.</p>
      {s.connecting && !s.adbWaiting && <p className="status work" role="status">Pick the Mirror in the browser&apos;s list…</p>}
      {s.adbWaiting && <p className="status work" role="status">The Mirror is still starting. Kiln asks again every few seconds…</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ember pulsing" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect</button>
      </div>
    </section>
  );
}

export function FlashDone({ f, s }: P) {
  if (f.recipe.community) return <DoneCommunity f={f} log={<Log s={s} />} />;
  const fb = s.firstBoot;
  const noHome = fb?.home.result === 'none';
  const stranded = fb && fb.home.result !== 'set' && !noHome;
  const other = f.recipes.find((r) => r.community);
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">Glassworks is on the Mirror.</h2>
      {stranded && (
        <div className="well" role="alert">
          <div className="grow">
            <span className="status err">Glassworks isn&apos;t the only home screen yet.</span>
            <span className="small">
              Android opened {fb.home.result === 'chooser' ? 'its list of home screens' : <code>{fb.home.focused ?? 'something else'}</code>}{fb.home.left.length ? <>: <code>{fb.home.left.join(', ')}</code> is still a home app</> : ''}.
              {' '}The Mirror has no touchscreen, so after a restart it would wait on that list. Hide the other launcher again, or plug in a USB mouse and choose Glassworks, then Always.
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
              So Kiln hid nothing: {fb.home.left.length ? <code>{fb.home.left.join(', ')}</code> : 'the image’s launcher'} stays the Mirror&apos;s home screen, and the Mirror still starts to it.
              {' '}Add a build of Glassworks made for Android 6, over adb.
            </span>
          </div>
        </div>
      )}
      {fb && (
        <p className="lede">
          Glassworks {fb.app.name ?? fb.app.code} {stranded || noHome ? 'is installed' : 'is its home screen'}{fb.hidden.length ? ` (${fb.hidden.join(', ')} hidden)` : ''}.
          {fb.wifi === false && ' It didn’t confirm the Wi-Fi; the network saved in the image still applies.'}
        </p>
      )}
      {fb && <SilentUpdates words={mirrorSilent(fb.updates)} />}
      {other && <WayBack f={f} other={other} />}
      <BuiltOn credits={glassPanes()} />
      {REVERSIBLE && <p className="small">{REVERSIBLE}.</p>}
      <div className="foot"><Log s={s} /></div>
    </section>
  );
}

export function FlashError({ f, s }: P) {
  return (
    <section className="card err" aria-labelledby="h">
      {f.recipe.community && <Whose r={f.recipe} />}
      <h2 id="h">The install stopped.</h2>
      <pre className="raw">{s.error}</pre>
      <p className="small">If it stopped while flashing, the Mirror may not start: put it in fastboot again and install again{f.recipe.community ? '' : ', or install the Glass Panes image from this page'}.</p>
      {s.flashFailed && !f.recipe.community && !s.noFill && (
        <p className="small">If the bootloader refused a FILL chunk (a sparse image&apos;s run of zeros), try again without FILL: Kiln builds the zeros as plain blocks, about 200 MB more to send.</p>
      )}
      <div className="foot">
        <Log s={s} />
        <span className="links">
          {s.work?.step === 'first-boot' && <button type="button" className="btn ghost" onClick={() => f.finishFirstStart()}>Try the first start again</button>}
          {s.flashFailed && !f.recipe.community && !s.noFill && <button type="button" className="btn ghost" onClick={() => f.retryWithoutFill()}>Try again without FILL</button>}
          <button type="button" className="btn ghost" onClick={() => f.retry()}>Back to the download</button>
        </span>
      </div>
    </section>
  );
}

export function FlashScreens(p: P) {
  const { s } = p;
  return s.screen === 'choose' ? <Choose f={p.f} s={s} />
    : s.screen === 'image' ? <FlashImage {...p} />
    : s.screen === 'download' ? <Download f={p.f} s={s} />
    : s.screen === 'fastboot' ? <FlashFastboot {...p} />
    : s.screen === 'confirm' ? <FlashConfirm {...p} />
    : s.screen === 'working' ? <FlashWorking {...p} />
    : s.screen === 'adb' ? <FlashAdb {...p} />
    : s.screen === 'done' ? <FlashDone {...p} />
    : <FlashError {...p} />;
}
