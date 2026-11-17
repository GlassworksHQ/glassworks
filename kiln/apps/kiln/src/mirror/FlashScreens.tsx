'use client';
// The Mirror's install over fastboot (FlashSession): the system image in two clicks, fastboot, the
// stock warning, building and flashing, the first start, Done, and the way back. Same design as the
// rest of Kiln (docs/design/kiln): one idea per screen, one ember button, the log one click away.
import { GLASS_PANES, GLASS_PANES_USERDATA, credits } from '@glassworks/lululemon-mirror-installer';
import { fmtSize } from '@glassworks/kiln-core';
import * as Collapsible from '@radix-ui/react-collapsible';
import { useRef, useState } from 'react';
import { BuiltOn, Credit, stepCredits } from '../components/Credit';
import { FRAMES } from '../frames/catalog';
import type { RailMark, RailStep } from '../wizard/state';
import type { FileCheck, FlashSession, FlashState, WorkStep } from './flash';
import type { MirrorSession, MirrorState } from './session';

type P = { f: FlashSession; s: FlashState; m: MirrorSession; ms: MirrorState };

export function flashRail(s: FlashState): RailStep[] {
  const names = ['Mirror', 'Image', 'Fastboot', 'Install', 'Done'];
  const at = { image: 1, back: 1, fastboot: 2, confirm: 2, working: 3, adb: 3, error: 3, done: 5 }[s.screen];
  return names.map((name, i): RailStep => {
    const mark: RailMark = i < at ? 'done' : i > at ? 'todo' : s.screen === 'error' ? 'bad' : s.screen === 'adb' ? 'turn' : 'now';
    return { name, mark };
  });
}

export function flashLevel(s: FlashState): number {
  if (s.screen === 'done') return 4;
  if (s.screen === 'adb') return 3;
  if (s.screen === 'working') return s.work?.step.startsWith('flash') || s.work?.step === 'restart' ? 2 : 1;
  return 0;
}

/** Glass Panes, from the Mirror's frame.json `credits` (frames/README.md#credits). */
const glassPanes = () => stepCredits('lululemon-mirror', credits, ['image']);
/** The Mirror's reversibility, as frame.json labels it (issue 146). */
const REVERSIBLE = FRAMES.find((x) => x.id === 'lululemon-mirror')?.ratings.reversible.label;

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

/** Chrome and Edge open the picker in Downloads; elsewhere the plain file input. */
async function openInDownloads(fallback: () => void): Promise<File | undefined> {
  const w = window as unknown as { showOpenFilePicker?: (o: object) => Promise<{ getFile(): Promise<File> }[]> };
  if (!w.showOpenFilePicker) { fallback(); return undefined; }
  try {
    const [h] = await w.showOpenFilePicker({ startIn: 'downloads', types: [{ description: 'Android image', accept: { 'application/octet-stream': ['.img', '.sparse'] } }] });
    return h ? await h.getFile() : undefined;
  } catch (e) {
    if ((e as DOMException).name !== 'AbortError') fallback();
    return undefined;
  }
}

function FileStep({ n, title, check, select, want, children }: {
  n: number; title: string; check?: FileCheck; select: (f: File) => void; want: { name: string; size: number }; children?: React.ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  const pick = () => void openInDownloads(() => input.current?.click()).then((f) => f && select(f));
  return (
    <div className="well">
      <div className="grow">
        <span className="small">{n} · {title}</span>
        {children}
        <CheckLine check={check} />
      </div>
      <input ref={input} type="file" accept=".img,.sparse" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) select(f); e.target.value = ''; }} />
      <button type="button" className="btn ghost" disabled={check?.state === 'checking' || check?.state === 'downloading'} onClick={pick}>Select the downloaded {want.name}</button>
    </div>
  );
}

function Download({ n, want }: { n: number; want: { name: string; url: string; size: number } }) {
  return (
    <div className="well">
      <div className="grow">
        <span className="small">{n} · Download {want.name}</span>
        <span><code>{want.name}</code>, about {Math.round(want.size / 1e6)} MB, from the Glass Panes release on GitHub. Your browser saves it to Downloads.</span>
      </div>
      <a className="btn ghost" href={want.url} download={want.name} rel="noopener">Download {want.name}</a>
    </div>
  );
}

/** Progress, the result, or the error of a file being downloaded or checked. */
function CheckLine({ check }: { check?: FileCheck }) {
  if (!check) return null;
  if (check.state === 'downloading' || check.state === 'checking') {
    const what = check.state === 'downloading' ? `Downloading and checking ${check.name}` : `Checking ${check.name}`;
    return (
      <div style={{ display: 'grid', gap: 6 }} role="status">
        <span className="small">{what} · {Math.floor(check.progress * 100)}%</span>
        <div className="bar" role="progressbar" aria-label={what} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor(check.progress * 100)}><i style={{ width: `${Math.max(2, check.progress * 100)}%` }} /></div>
      </div>
    );
  }
  if (check.state === 'ok') return <span className="status ok">✓ {check.name} checks out ({fmtSize(check.size)}, SHA-256 as published).</span>;
  return <span className="status err" role="alert">{check.error}</span>;
}

export function FlashImage({ f, s, m, ms }: P) {
  const blocked = f.blocked();
  const want = GLASS_PANES.system;
  const busy = s.image?.state === 'downloading' || s.image?.state === 'checking';
  const ready = s.image?.state === 'ok';
  return (
    <section className="card" aria-labelledby="h">
      <div className="row"><span className="pill soon">Test only</span></div>
      <h2 id="h">Install Glassworks on the Mirror.</h2>
      <p className="lede">Kiln adds Glassworks to the Mirror&apos;s community Android image in this browser, then writes it over USB.</p>
      <div className="well">
        <div className="grow">
          <span className="small">1 · The system image</span>
          <span><code>{want.name}</code>, about {Math.round(want.size / 1e6)} MB, from the Glass Panes release. Kiln downloads it into this browser and checks it as it arrives.</span>
          <CheckLine check={s.image} />
        </div>
      </div>
      <ImageCredit />
      {s.proxyFailed && !ready && (
        <>
          <p className="status err" role="alert">Kiln couldn&apos;t download it directly: {s.proxyFailed}. Download it yourself, then select it.</p>
          <Download n={2} want={want} />
          <FileStep n={3} title="Select the downloaded image" check={s.image?.state === 'downloading' ? undefined : s.image} select={(file) => void f.selectImage(file)} want={want} />
        </>
      )}
      <div className="fields">
        <label className="field"><span>Glassworks APK (legacy build, test only)</span>
          <input className="input" type="file" accept=".apk,application/vnd.android.package-archive"
            onChange={(e) => { const x = e.target.files?.[0]; if (x) void m.pickApk(x.name, x.arrayBuffer().then((b) => new Uint8Array(b))); }} />
        </label>
        <label className="field"><span>Home Assistant (optional)</span>
          <input className="input" type="url" placeholder="http://homeassistant.local:8123" value={s.haUrl} onChange={(e) => f.setHaUrl(e.target.value.trim())} />
        </label>
        <label className="field"><span>Wi-Fi network (optional)</span>
          <input className="input" autoComplete="off" value={s.wifi.ssid} onChange={(e) => f.setWifi({ ssid: e.target.value })} />
        </label>
        <label className="field"><span>Wi-Fi password</span>
          <input className="input" type="password" autoComplete="off" value={s.wifi.password} onChange={(e) => f.setWifi({ password: e.target.value })} />
        </label>
      </div>
      {ms.apk && <p className={`small${ms.apk.error ? ' status err' : ''}`}>{ms.apk.name}: {ms.apk.error ?? `${ms.apk.package}, ${fmtSize(ms.apk.size)}`}</p>}
      {blocked && ready && <p className="status err" role="alert">{blocked}</p>}
      <div className="foot">
        <span className="links">
          <button type="button" className="link" onClick={() => f.wayBack(true)}>Way back: the Glass Panes image</button>
        </span>
        {ready || s.proxyFailed
          ? <button type="button" className="btn ember" disabled={!!blocked} onClick={() => f.toFastboot()}>Continue</button>
          : <button type="button" className="btn ember" disabled={busy} onClick={() => void f.downloadImage()}>Download the system image</button>}
      </div>
    </section>
  );
}

export function FlashBack({ f, s }: P) {
  const blocked = f.blocked();
  return (
    <section className="card" aria-labelledby="h">
      <div className="row"><span className="pill soon">Test only</span></div>
      <h2 id="h">Put the Glass Panes image back.</h2>
      <p className="lede">The way back from Glassworks: the community image as its release has it, with a fresh start. It doesn&apos;t bring lululemon&apos;s software back.</p>
      <Download n={1} want={GLASS_PANES.system} />
      <FileStep n={2} title="Select the downloaded system.img" check={s.image} select={(file) => void f.selectImage(file)} want={GLASS_PANES.system} />
      <FileStep n={3} title="Download and select userdata.sparse" check={s.userdataFile} select={(file) => void f.selectUserdata(file)} want={GLASS_PANES_USERDATA}>
        <a className="link" href={GLASS_PANES_USERDATA.url} download={GLASS_PANES_USERDATA.name}>Download {GLASS_PANES_USERDATA.name}</a>
      </FileStep>
      <ImageCredit />
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.wayBack(false)}>Back to installing Glassworks</button></span>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => f.toFastboot()}>Continue</button>
      </div>
    </section>
  );
}

export function FlashFastboot({ f, s }: P) {
  const r = s.fastboot;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Put the Mirror in fastboot.</h2>
      <ol className="join">
        <li>Unplug the Mirror&apos;s power, and open its back.</li>
        <li>Hold the button inside while you plug the power back in. Keep holding for about 5 seconds.</li>
        <li>Plug its USB port into this computer. Its screen stays black: that&apos;s fastboot.</li>
      </ol>
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

export function FlashConfirm({ f, s }: P) {
  const r = s.fastboot!;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{s.back ? 'Write the Glass Panes image?' : 'This replaces lululemon’s software for good.'}</h2>
      <ol className="join">
        {r.checks.map((c) => <li key={c.what} className="done">{c.what}: {c.found}</li>)}
      </ol>
      <p className="lede">
        Kiln can&apos;t back up the Mirror first: its bootloader can&apos;t read anything back. So lululemon&apos;s software can&apos;t be
        restored, by Kiln or anyone. The way back from here is the plain Glass Panes image. Everything on the Mirror is replaced.
      </p>
      {REVERSIBLE && !s.back && <p className="small">{REVERSIBLE}.</p>}
      <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} /> I understand lululemon&apos;s software can&apos;t be restored.</label>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.retry()}>Back</button></span>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>{s.back ? 'Write it' : 'Install Glassworks'}</button>
      </div>
    </section>
  );
}

const ROWS: { id: WorkStep; text: string; back?: boolean }[] = [
  { id: 'patch', text: 'Add Glassworks and the boot animation to the image' },
  { id: 'system', text: 'Write the system image as fastboot sends it' },
  { id: 'userdata', text: 'Make a fresh userdata with this browser’s key' },
  { id: 'flash-system', text: 'Flash system', back: true },
  { id: 'flash-userdata', text: 'Flash userdata', back: true },
  { id: 'restart', text: 'Restart the Mirror', back: true },
  { id: 'first-boot', text: 'First start: Home Assistant, Wi-Fi, home screen' },
];

export function FlashWorking({ s }: P) {
  const rows = ROWS.filter((r) => !s.back || r.back);
  const at = Math.max(0, rows.findIndex((r) => r.id === s.work?.step));
  const x = s.work?.x ?? 0;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h" className="huge">Keep it plugged in.</h2>
      <ol className="join">
        {rows.map((r, i) => <li key={r.id} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r.text}{i === at && x > 0 && x < 1 && <span className="sub-step">{Math.floor(x * 100)}%</span>}</li>)}
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
      <p className="lede">The Mirror is starting Glassworks. Its first start takes a few minutes. Click Connect and pick the Mirror in the browser&apos;s list: it knows this browser&apos;s key, so it doesn&apos;t ask on its screen.</p>
      {s.connecting && <p className="status work" role="status">Pick the Mirror in the browser&apos;s list…</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <Log s={s} />
        <button type="button" className="btn ember pulsing" disabled={s.connecting} onClick={() => void f.connectAdb()}>Connect</button>
      </div>
    </section>
  );
}

export function FlashDone({ f, s }: P) {
  const fb = s.firstBoot;
  const noHome = !s.back && fb?.home.result === 'none';
  const stranded = !s.back && fb && fb.home.result !== 'set' && !noHome;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{s.back ? 'The Glass Panes image is back on the Mirror.' : 'Glassworks is on the Mirror.'}</h2>
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
      {!s.back && fb && (
        <p className="lede">
          Glassworks {fb.app.name ?? fb.app.code} {stranded || noHome ? 'is installed' : 'is its home screen'}{fb.hidden.length ? ` (${fb.hidden.join(', ')} hidden)` : ''}.
          {fb.wifi === false && ' It didn’t confirm the Wi-Fi; the network saved in the image still applies.'}
        </p>
      )}
      {s.back && <p className="lede">It starts like a new Glass Panes Mirror: set it up as its install guide says.</p>}
      <BuiltOn credits={glassPanes()} />
      {REVERSIBLE && <p className="small">{REVERSIBLE}.</p>}
      <p className="small">The way back is the Glass Panes image: open this page with the Mirror in fastboot and choose Way back.</p>
      <div className="foot"><Log s={s} /></div>
    </section>
  );
}

export function FlashError({ f, s }: P) {
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">The install stopped.</h2>
      <pre className="raw">{s.error}</pre>
      <p className="small">If it stopped while flashing, the Mirror may not start: put it in fastboot again and install again, or write the Glass Panes image (Way back).</p>
      {s.flashFailed && !s.back && !s.noFill && (
        <p className="small">If the bootloader refused a FILL chunk (a sparse image&apos;s run of zeros), try again without FILL: Kiln builds the zeros as plain blocks, about 200 MB more to send.</p>
      )}
      <div className="foot">
        <Log s={s} />
        <span className="links">
          {s.flashFailed && !s.back && !s.noFill && <button type="button" className="btn ghost" onClick={() => f.retryWithoutFill()}>Try again without FILL</button>}
          <button type="button" className="btn ghost" onClick={() => f.retry()}>Back to the files</button>
        </span>
      </div>
    </section>
  );
}

export function FlashScreens(p: P) {
  const { s } = p;
  return s.screen === 'image' ? <FlashImage {...p} />
    : s.screen === 'back' ? <FlashBack {...p} />
    : s.screen === 'fastboot' ? <FlashFastboot {...p} />
    : s.screen === 'confirm' ? <FlashConfirm {...p} />
    : s.screen === 'working' ? <FlashWorking {...p} />
    : s.screen === 'adb' ? <FlashAdb {...p} />
    : s.screen === 'done' ? <FlashDone {...p} />
    : <FlashError {...p} />;
}
