'use client';
// /android (issue 403): Glassworks on any Android 6+ device, over USB. The screens are the approved mockup's
// (maintainer, 2026-11-17): the device named or its brand asked, the brand's steps to turn on USB
// debugging, Allow on the device, the install with each grant read back, Done with the frame card or
// "untested device". Off unless the address has ?apk=local (or a demo): the APK is a file from this
// computer until Glassworks for Android has a release.
import { Panel, loadOrCreateAdbKey } from '@glassworks/adb';
import { fmtSize } from '@glassworks/kiln-core';
import { KIND_TEXT, STAGE_TEXT, type FrameDesign } from '@glassworks/frame-card';
import {
  BRANDS, SILENT_LINES, androidName, type BrandId, type GenericPhase, type Grant, type Identified, type Place, type SilentUpdatesResult,
} from '@glassworks/generic-android-installer';
import * as Collapsible from '@radix-ui/react-collapsible';
import * as RadioGroup from '@radix-ui/react-radio-group';
import Link from 'next/link';
import { Fragment, useEffect, useState, useSyncExternalStore } from 'react';
import { Shell, useBrowserChip } from '../components/Shell';
import { FRAMES, MEASURED, RENDERS } from '../frames/catalog';
import { adbKeyHolder } from '../mirror/MirrorWizard';
import type { RailMark, RailStep } from '../wizard/state';
import { registry } from '../wizard/registry';
import { ANDROID_BASE, keptAndroid } from './activity';
import { ANDROID_DEMOS, androidDemo, type AndroidDemo } from './demo';
import { AndroidSession, androidMode, type AndroidState } from './session';
import './android.css';

const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';
const SITE = 'https://www.glassworks.dev/frames';

function realSession(on: boolean): AndroidSession {
  if (!on) return new AndroidSession({ findUsb: () => Promise.reject(new Error('off')), connect: () => Promise.reject(new Error('off')) }, false);
  const keys = adbKeyHolder(loadOrCreateAdbKey);
  return new AndroidSession({
    // no filter: before USB debugging is on, the device shows only as itself (charging or file transfer)
    findUsb: async () => {
      const d = await navigator.usb.requestDevice({ filters: [] });
      return { vendorId: d.vendorId, ...(d.productName ? { productName: d.productName } : {}) };
    },
    connect: keys.connectWith((k) => Panel.connect(k)),
  }, true);
}

/** Device, Connect, Install (or Undo), Set up, Done: where the person is on them. */
function rail(s: AndroidState): RailStep[] {
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

function level(s: AndroidState): number {
  if (s.screen === 'done') return 4;
  if (s.screen === 'working') return s.granted ? 3 : 2;
  return 0;
}

const frameOf = (i?: Identified): FrameDesign | undefined => (i?.model?.frame ? FRAMES.find((f) => f.id === i.model!.frame) : undefined);

/** What Kiln calls the device: the model it knows, else what adb or USB said, else its brand. */
function deviceName(s: AndroidState): string {
  const d = s.plan?.device;
  return s.identified?.model?.name ?? (d ? `${d.manufacturer} ${d.model}`.trim() : undefined) ?? s.usbName ?? s.identified?.brand?.name ?? 'tablet';
}

function Log({ s }: { s: AndroidState }) {
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
      <h2 id="h">Kiln doesn&apos;t install on Android devices yet.</h2>
      <p className="lede">Glassworks for Android has no release yet. Follow it on GitHub, or pick another panel.</p>
      <div className="foot">
        <a className="link" href="https://github.com/GlassworksHQ/glassworks/issues/403">Follow it on GitHub</a>
        <Link className="btn ghost" href="/">Pick a panel</Link>
      </div>
    </section>
  );
}

/** Test only: the APK file from this computer. */
function ApkField({ s, session }: { s: AndroidState; session: AndroidSession }) {
  if (s.flow === 'undo') return null;
  return (
    <div className="well">
      <div className="grow">
        <span className="row"><span className="pill soon">Test only</span><span className="small">Glassworks for Android has no release yet: pick a test build.</span></span>
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

const WHAT_KILN_DOES = 'Installs the app, grants what it can, sets it as the home screen. Your apps and data stay. Undo removes it.';

function Find({ s, session }: { s: AndroidState; session: AndroidSession }) {
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{s.flow === 'undo' ? 'Plug in the tablet to undo.' : 'Plug in the tablet.'}</h2>
      <p className="lede">{s.flow === 'undo'
        ? 'Kiln takes Glassworks off the tablet over USB, as it put it on: turn on USB debugging, then tap Allow. Kiln reads the tablet first; nothing changes until then.'
        : 'Use its USB cable, with USB debugging still off. Kiln reads what the tablet is; nothing on it changes.'}</p>
      <ApkField s={s} session={session} />
      {s.flow === 'install' && <p className="small">Android 6 or newer, over USB. {WHAT_KILN_DOES}</p>}
      {s.finding && <p className="status work" role="status">Pick the tablet in the browser&apos;s list…</p>}
      {s.findError && <p className="status err" role="alert">{s.findError}</p>}
      <div className="foot">
        <span className="links">
          <button type="button" className="link" onClick={() => session.brands()}>Not in the list? Pick your brand</button>
          <span aria-hidden="true">·</span>
          {s.flow === 'install'
            ? <button type="button" className="link" onClick={() => session.setFlow('undo')}>Undo an install</button>
            : <button type="button" className="link" onClick={() => session.setFlow('install')}>Install instead</button>}
        </span>
        <button type="button" className="btn ember" disabled={s.finding} onClick={() => void session.find()}>Find it</button>
      </div>
    </section>
  );
}

function Named({ s, session }: { s: AndroidState; session: AndroidSession }) {
  const i = s.identified!;
  const f = frameOf(i);
  const who = i.model?.name ?? i.brand?.name ?? deviceName(s);
  const lede = f ? `${f.brand ?? i.brand?.name} · a ${KIND_TEXT[f.kind].toLowerCase()}.` : i.model ? `${i.brand?.name} · not tested yet.` : `Kiln knows the brand, not the model.`;
  const blocked = session.blocked();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">This looks like a {who}.</h2>
      <p className="lede">{lede} Next: turn on USB debugging, then Kiln {s.flow === 'undo' ? 'removes' : 'installs'} Glassworks.</p>
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
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => session.brands()}>Not right? Pick your brand</button></span>
        <button type="button" className="btn ember" disabled={!!blocked} onClick={() => session.toSteps()}>Continue</button>
      </div>
    </section>
  );
}

/** The brands drawn in the mockup; the rest get Android's general steps, as Other does. */
const PICKS: BrandId[] = ['google', 'samsung', 'amazon', 'lenovo', 'xiaomi'];

function Brand({ s, session }: { s: AndroidState; session: AndroidSession }) {
  const blocked = session.blocked();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Which brand is it?</h2>
      <p className="lede">{s.identified?.why ?? 'Kiln shows its steps to turn on USB debugging.'}</p>
      <RadioGroup.Root className="brands" value={s.brand ?? ''} onValueChange={(v) => session.pickBrand(v as BrandId | 'other')} aria-labelledby="h">
        {PICKS.map((id) => (
          <RadioGroup.Item key={id} value={id} className="brand-pick">{BRANDS.find((b) => b.id === id)!.name}</RadioGroup.Item>
        ))}
        <RadioGroup.Item value="other" className="brand-pick">Other<small>Android&apos;s general steps</small></RadioGroup.Item>
      </RadioGroup.Root>
      <ApkField s={s} session={session} />
      {blocked && s.apk && <p className="status err" role="alert">{blocked}</p>}
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => session.back()}>Back</button><span aria-hidden="true">·</span>Pick one to see its steps.</span>
        <button type="button" className="btn ember" disabled={!s.brand || !!blocked} onClick={() => session.chooseBrand()}>Continue</button>
      </div>
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
const pageOf = (p: Place) => (p.path.at(-1) === SWITCH ? p.path.slice(0, -1) : p.path);

function Debug({ s, session }: { s: AndroidState; session: AndroidSession }) {
  const st = s.identified!.steps;
  const one = st.debugging.length === 1;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Turn on USB debugging.</h2>
      <p className="lede">On the tablet. Only you can do this; Kiln can&apos;t.</p>
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
      <div className="foot">
        <span className="small">
          Steps from {st.sources.map((src, i) => <span key={src.href}>{i > 0 && ' and '}<a className="link" href={src.href} target="_blank" rel="noreferrer">{src.title.split(':')[0]}</a></span>)}
          {' · '}<button type="button" className="link" onClick={() => session.brands()}>Different on yours?</button>
        </span>
        <button type="button" className="btn ember" onClick={() => void session.connect()}>It&apos;s on</button>
      </div>
    </section>
  );
}

function Allow({ s, session }: { s: AndroidState; session: AndroidSession }) {
  const [help, setHelp] = useState(false);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h" className="huge">Tap Allow on the tablet.</h2>
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
            <li>Pick the tablet in the browser&apos;s list first. It shows once USB debugging is on.</li>
            <li>Unplug the cable and plug it in again, then click <b>Try again</b>.</li>
            <li>Turn USB debugging off and on again.</li>
            <li>Another adb on this computer may hold the tablet: run <code>adb kill-server</code>.</li>
          </ul>
        </Collapsible.Content>
        <div className="foot">
          {s.connectError
            ? <button type="button" className="btn ember" onClick={() => void session.connect()}>Try again</button>
            : <span className="chip inline"><span className="pulse" />Waiting for the tablet</span>}
          <Collapsible.Trigger className="link">Nothing on the screen?</Collapsible.Trigger>
        </div>
      </Collapsible.Root>
    </section>
  );
}

type RowId = 'read' | 'apk' | 'grant' | 'start' | 'home' | 'handoff';
const ROWS: { id: RowId; doing: string; done: string }[] = [
  { id: 'read', doing: 'Reading the tablet', done: '' },
  { id: 'apk', doing: 'Installing Glassworks', done: 'Glassworks installed' },
  { id: 'grant', doing: 'Giving permissions', done: 'Permissions given' },
  { id: 'start', doing: 'Starting Glassworks', done: 'Glassworks started' },
  { id: 'home', doing: 'Setting the home screen', done: 'It is the home screen' },
  { id: 'handoff', doing: 'Handing over to setup', done: 'Handed over' },
];
const rowOf = (p: GenericPhase): RowId => (p.step === 'updates' || p.step === 'granted' ? 'grant' : p.step);

const GRANT_TEXT: Record<Grant['what'], { name: string; without: string }> = {
  'microphone': { name: 'Microphone', without: 'Assist can\'t hear you' },
  'camera': { name: 'Camera', without: 'the camera pane has no picture' },
  // What the generic app does with each (read in its code, 2026-11-18): secure settings let the Bluetooth proxy turn
  // Location on; the device admin is what turns the screen off when idle (lockNow).
  'secure-settings': { name: 'Secure settings', without: 'the Bluetooth proxy can\'t turn Location on by itself' },
  'overlay': { name: 'Show over other apps', without: 'its codes can\'t show over other apps' },
  'device-admin': { name: 'Turn the screen off', without: 'it can\'t turn the screen off when idle' },
};

function GrantList({ grants, updates }: { grants: Grant[]; updates?: SilentUpdatesResult }) {
  const shown = grants.filter((g) => g.result !== 'not-asked' || g.what === 'camera');
  return (
    <div className="grants">
      {shown.map((g) => (
        <div key={g.what}>{GRANT_TEXT[g.what].name}
          {g.result === 'granted' ? <i className="y">On</i> : g.result === 'refused' ? <i className="w" title={g.detail}>This tablet said no</i> : <i className="n">Not asked by this build</i>}
        </div>
      ))}
      {updates && updates.state !== 'not-asked' && (
        <div>Updates without a tap
          {updates.state === 'allowed' ? <i className="y">On</i> : updates.state === 'refused' ? <i className="w" title={updates.detail}>This tablet said no</i> : <i className="n">{updates.state === 'old-android' ? 'Needs Android 12' : 'Off'}</i>}
        </div>
      )}
    </div>
  );
}

/**
 * Done's Silent updates block (issue 422, the maintainer, 2026-11-18): in place of the grants list's row, what Kiln did and
 * what it means, with a word in the grants list's colours. Null where this build doesn't ask.
 */
export function silentBlock(u: SilentUpdatesResult, release: string): { word: string; tone: 'y' | 'n' | 'w'; line: string } | null {
  switch (u.state) {
    case 'allowed':
      return { word: 'On', tone: 'y', line: 'Updates from Home Assistant install with nobody at the tablet. Kiln allowed “Install unknown apps” for Glassworks only. Undo takes it back.' };
    case 'old-android':
      return { word: 'A tap each', tone: 'n', line: `Android ${release} asks for a tap on each update. Updates still come from Home Assistant; someone taps Install on the tablet. Only a device owner skips the tap.` };
    case 'declined':
      return { word: 'A tap each', tone: 'n', line: 'Each update waits for a tap on the tablet, as asked. Updates still come from Home Assistant.' };
    case 'refused':
      if (u.line === SILENT_LINES.off) return { word: 'This tablet said no', tone: 'w', line: 'This tablet\'s software keeps “Install unknown apps” off. Updates still come from Home Assistant, with a tap on the tablet.' };
      if (u.line === SILENT_LINES.allowed) return { word: 'Still on', tone: 'w', line: 'Android kept “Install unknown apps” allowed from an earlier install, so updates still install with nobody at the tablet.' };
      return { word: 'Not confirmed', tone: 'n', line: 'Kiln couldn\'t read “Install unknown apps” back. Glassworks\' Device page on the tablet says which it is.' };
    case 'not-asked':
      return null;
  }
}

function SilentBlock({ updates, release }: { updates: SilentUpdatesResult; release: string }) {
  const b = silentBlock(updates, release);
  if (!b) return null;
  return (
    <div className="su" role="group" aria-label="Silent updates">
      <div className="spread"><span>Silent updates</span><i className={b.tone}>{b.word}</i></div>
      <span className="small">{b.line}</span>
    </div>
  );
}

/** What didn't apply, said once, with what it means. [updates]: left out where Done's block says it. */
function Refusals({ grants, updates }: { grants: Grant[]; updates?: SilentUpdatesResult }) {
  const lines = grants.filter((g) => g.result === 'refused').map((g) => GRANT_TEXT[g.what].without);
  if (updates?.state === 'refused') lines.push('each update may ask for a tap');
  if (!lines.length) return null;
  const what = grants.filter((g) => g.result === 'refused').map((g) => GRANT_TEXT[g.what].name.toLowerCase());
  if (updates?.state === 'refused') what.push('silent updates');
  return <div className="warnbox" role="status">This tablet&apos;s software refused {what.join(', ')}. Glassworks works without it; {lines.join(', and ')}.</div>;
}

function deviceLine(s: AndroidState): string {
  const d = s.plan!.device;
  const abi = s.plan!.abi ?? d.abis[0] ?? '';
  return `${deviceName(s)}, Android ${d.release || androidName(d.sdk)}${abi ? `, ${abi.includes('64') ? '64' : '32'}-bit` : ''}`;
}

function Working({ s }: { s: AndroidState }) {
  const last = s.phases.at(-1);
  if (s.flow === 'undo') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h" className="huge">Keep it plugged in.</h2>
        <p className="lede">Kiln takes back Glassworks&apos; device admin, then removes it.</p>
        <div className="bar wait"><i /></div>
        <div className="foot"><span className="small">Nothing else on the tablet changes.</span><Log s={s} /></div>
      </section>
    );
  }
  const at = last ? ROWS.findIndex((r) => r.id === rowOf(last)) : 0;
  const pick = last?.step === 'home' ? last.pick : undefined;
  const grants = s.granted?.grants;
  const now = ROWS[at]!;
  const doing = last?.step === 'apk' && last.stage === 'copy' ? 'Copying Glassworks to the tablet' : now.doing;
  const done = (r: (typeof ROWS)[number]) => (r.id === 'read' && s.plan ? deviceLine(s) : r.id === 'apk' && s.plan?.installed ? 'Glassworks updated' : r.done);
  return (
    <section className={`card${pick ? ' turn' : ''}`} aria-labelledby="h">
      {pick ? <>
        <h2 id="h" className="huge">One tap on the tablet.</h2>
        <p className="lede">Pick <b className="fg">{pick}</b>, then <b className="fg">Always</b>.</p>
      </> : <>
        <h2 id="h" className="huge">Keep it plugged in.</h2>
        <div className="prog">
          <div className="spread"><span>{doing}</span><span className="small">{at + 1} of {ROWS.length}</span></div>
          <div className="bar"><i style={{ width: `${((at + 0.5) / ROWS.length) * 100}%` }} /></div>
        </div>
        <ol className="plain" aria-label="Done so far">
          {ROWS.slice(0, at).map((r) => <li key={r.id}><span className="ok">✓</span> {done(r)}</li>)}
          <li><span className="go">→</span> {doing}</li>
        </ol>
      </>}
      {grants && <GrantList grants={grants} updates={s.granted?.silentUpdates} />}
      {grants && <Refusals grants={grants} updates={s.granted?.silentUpdates} />}
      <div className="foot">
        {pick ? <span className="chip inline"><span className="pulse" />Waiting for your tap</span> : <span className="small">Nothing else on the tablet changes.</span>}
        <Log s={s} />
      </div>
    </section>
  );
}

function Done({ s, session }: { s: AndroidState; session: AndroidSession }) {
  const r = s.result!;
  if (r.flow === 'undo') {
    return (
      <section className="card" aria-labelledby="h">
        <span className="okmark" aria-hidden="true">✓</span>
        <h2 id="h">{r.removed.length ? 'Glassworks is off the tablet.' : 'Glassworks wasn\'t on the tablet.'}</h2>
        <p className="lede">{r.removed.length ? 'Android opens the home screen it had, or asks which one to use.' : 'Nothing was changed.'} Unplug it when you like.</p>
        <div className="foot"><Log s={s} /><Link className="btn ghost" href="/">Back to Kiln</Link></div>
      </section>
    );
  }
  const { installed } = r;
  const f = frameOf(s.identified);
  const name = deviceName(s);
  const home = installed.home.state === 'set';
  const page = installed.handoff.setupPage;
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">Done.</h2>
      <p className="lede">
        {home && f ? `Glassworks is the ${name}'s home screen.` : `Glassworks is installed on your ${name}.`}
        {!home && ' Kiln didn\'t see it picked as the home screen: on the tablet, pick Glassworks, then Always.'}
      </p>
      {f ? (
        <div className="fc">
          <div className="spread"><b>{f.name}</b>{!MEASURED.has(f.id) && <span className="ctag">Clarity · not measured yet</span>}</div>
          <span className="kind">{KIND_TEXT[f.kind]} · <a className="link" href={`${SITE}/${f.id}`}>Its page on glassworks.dev</a></span>
        </div>
      ) : (
        <div className="fc">
          <b>Untested device</b>
          <span className="small">Nobody has run Glassworks on this model yet. <a className="link" href={`https://github.com/GlassworksHQ/glassworks/issues/new?title=${encodeURIComponent(`Tested: ${name}`)}`}>Tell us how it goes</a></span>
        </div>
      )}
      <SilentBlock updates={installed.silentUpdates} release={s.plan!.device.release || androidName(s.plan!.device.sdk)} />
      <Refusals grants={installed.grants} />
      {page ? <>
        <div className="well">
          <span className="grow muted">Finish setting up on your network</span>
          <a className="btn ember" href={page} target="_blank" rel="noreferrer">Open setup</a>
        </div>
        <span className="small">{page} · unplug when you like</span>
      </> : (
        <p className="small">{installed.handoff.why} Finish setting up on the tablet: Glassworks shows its setup screen.</p>
      )}
      <div className="foot">
        <button type="button" className="link" onClick={() => void session.undoFromDone()}>Undo</button>
        <Log s={s} />
      </div>
    </section>
  );
}

function Failed({ s, session }: { s: AndroidState; session: AndroidSession }) {
  if (s.own) {
    return (
      <section className="card err" aria-labelledby="h">
        <h2 id="h">This is an {s.own.name}.</h2>
        <p className="lede">It has its own install in Kiln. Glassworks for Android would take its app&apos;s place as the home screen, so Kiln stopped before changing anything.</p>
        <div className="foot">
          <Log s={s} />
          <Link className="btn ember" href={s.own.route}>Go to the {s.own.name}</Link>
        </div>
      </section>
    );
  }
  return <Stopped s={s} session={session} />;
}

/** The run stopped. Try again reconnects if the device went away; a refused APK can be swapped here, keeping the device. */
function Stopped({ s, session }: { s: AndroidState; session: AndroidSession }) {
  const [repick, setRepick] = useState(false);
  const blocked = s.flow === 'install' ? session.blocked() : undefined;
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">{s.flow === 'undo' ? 'Undo stopped.' : /Nothing was changed\.$/.test(s.error ?? '') ? 'Kiln can\'t install on this tablet.' : 'The install stopped.'}</h2>
      <pre className="raw">{s.error}</pre>
      {repick && <ApkField s={s} session={session} />}
      {repick && blocked && s.apk && <p className="status err" role="alert">{blocked}</p>}
      <div className="foot">
        <Log s={s} />
        <span className="row">
          {s.flow === 'install' && !repick && <button type="button" className="link" onClick={() => setRepick(true)}>Pick another APK</button>}
          <button type="button" className="btn ember" disabled={!!blocked} onClick={() => void session.connect()}>Try again</button>
        </span>
      </div>
    </section>
  );
}

export function Screens({ session }: { session: AndroidSession }) {
  const s = useSyncExternalStore(session.subscribe, session.get, session.get);
  const chip = useBrowserChip();
  const turn = rail(s).some((x) => x.mark === 'turn');
  useEffect(() => { document.title = `${turn ? 'Your turn · ' : ''}Android device · Kiln`; }, [turn]);
  const p = { s, session };
  return (
    <Shell steps={rail(s)} level={level(s)} error={s.screen === 'error'} chip={chip}>
      {s.screen === 'off' ? <Off />
        : s.screen === 'find' ? <Find {...p} />
        : s.screen === 'named' ? <Named {...p} />
        : s.screen === 'brand' ? <Brand {...p} />
        : s.screen === 'debug' ? <Debug {...p} />
        : s.screen === 'allow' ? <Allow {...p} />
        : s.screen === 'working' ? <Working s={s} />
        : s.screen === 'done' ? <Done {...p} />
        : <Failed {...p} />}
    </Shell>
  );
}

export function AndroidWizard() {
  const [start] = useState(() => {
    const m = androidMode(location.search, DEMO_ON, ANDROID_DEMOS);
    if (m.demo) return { demo: m.demo as AndroidDemo };
    // a running install outlives the picker (wizard/registry.ts), so a link back without ?apk=local finds it
    if (!m.on && !registry.activity(ANDROID_BASE)) return { real: realSession(false) };
    return { real: keptAndroid(() => realSession(true)).session };
  });
  const [demo, setDemo] = useState<AndroidSession>();
  useEffect(() => { if (start.demo) void androidDemo(start.demo).then(setDemo); }, [start]);
  const live = start.real ?? demo;
  return live ? <Screens session={live} /> : null;
}
