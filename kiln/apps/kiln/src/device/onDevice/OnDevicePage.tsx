'use client';
// The install from the device's own browser, no computer (issue 403, part 3; issue 621): the engine's page for a UsbFrame's
// `onDevice` (at /android/tablet for the frame whose route is /android). It names the device as far as the browser lets it (the frame's
// `identify`), offers the right APK from its build's latest release through Refract, says what the app's
// own setup asks next, and how to finish with Kiln over USB. Its words, brands and lines are the frame's.
// Kiln's look (docs/design/kiln): one idea per card, one ember button, details on request.
import type { OnDevice, UsbFrame, UsbIdentified as Identified, UsbOnDevice, UsbPlace as Place, UsbSim } from '@glassworks/kiln-core';
import { DeviceModel, KIND_TEXT, STAGE_TEXT, type FrameDesign } from '@glassworks/frame-card';
import * as RadioGroup from '@radix-ui/react-radio-group';
import Link from 'next/link';
import { Fragment, createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Shell } from '../../components/Shell';
import { FRAMES, RENDERS } from '../../frames/catalog';
import { ReportButton } from '../../report/ReportDialog';
import { onDevicePrefill } from '../../report/prefill';
import { readFacts } from './facts';
import { onDeviceDemo } from './demo';
import {
  SETTINGS_LINKS, abiFor, buildFor, download, firstScreen, loadRelease, rail, type Abi, type Line, type Release, type TabletScreen,
} from './model';
import '../usb.css';
import './onDevice.css';

const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

/** The frame and where its pages are: `base` its route, `here` this page's. */
interface Where { dev: UsbFrame & { onDevice: UsbOnDevice }; base: string; here: string }
const WhereContext = createContext<Where | undefined>(undefined);
const useWhere = (): Where => useContext(WhereContext)!;

function Path({ path }: { path: string[] }) {
  return (
    <span className="path">
      {path.map((p, i) => <Fragment key={i}>{i > 0 && <i aria-label="then">›</i>}<span>{p}</span></Fragment>)}
    </span>
  );
}

const SWITCH = 'USB debugging';
const pageOf = (p: Place) => (p.path.at(-1) === SWITCH ? p.path.slice(0, -1) : p.path);

/** What the page calls the device, and whether it is sure (a model read, not guessed from GPU and screen). */
function nameOf(on: OnDevice): string {
  const { model, brand } = on.identified;
  if (model) return `${on.modelCode ? 'This is' : 'This looks like'} a ${model.name}`;
  if (brand) return `This is a ${brand.name} ${on.form}`;
  return `This is an Android ${on.form}`;
}

function versionText(on: OnDevice, needs: UsbOnDevice['needs']): string {
  if (on.release) return `Android ${on.release}`;
  return on.app === 'generic' ? needs.generic : 'Android version not shown';
}

interface P { on: OnDevice; go: (s: TabletScreen) => void }

/**
 * The device's 3D model, as the frame card draws it (its finish follows the page's theme): a model Kiln knows as a frame of
 * its own is that frame's; any other tablet is this page's frame's. A phone gets none, since this page's frame is a tablet.
 */
export function lookOf(on: OnDevice, page: string, frames: readonly FrameDesign[] = FRAMES): FrameDesign | undefined {
  const own = on.identified.model?.frame;
  if (own) return frames.find((x) => x.id === own);
  return on.form === 'tablet' ? frames.find((x) => x.id === page) : undefined;
}

function Device({ on, go }: P) {
  const { dev, base } = useWhere();
  const f = on.identified.model?.frame ? FRAMES.find((x) => x.id === on.identified.model!.frame) : undefined;
  const look = lookOf(on, dev.id);
  const facts = f ? (
    <div className="fc tab-fc">
      <div className="spread"><b>{f.name}</b><span className="pill soon">{STAGE_TEXT[f.status]}</span></div>
      <span className="kind">{KIND_TEXT[f.kind]} · {versionText(on, dev.onDevice.needs)}</span>
    </div>
  ) : (
    <dl className="kv">
      <dt>Android</dt><dd>{versionText(on, dev.onDevice.needs).replace(/^Android /, '')}</dd>
      {on.modelCode && <><dt>Model</dt><dd>{on.identified.model ? `${on.identified.model.name} (${on.modelCode})` : on.modelCode}</dd></>}
      <dt>Tested</dt><dd>Not yet. <ReportButton prefill={onDevicePrefill(on, 'device')} /></dd>
    </dl>
  );
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{nameOf(on)}</h2>
      <p className="lede">Install Glassworks right from this browser — no computer needed.</p>
      {/* the model and what the page read of it side by side (stacked on a phone); without a model, the facts alone */}
      {look ? (
        <div className="tab-device">
          <div className="tab-model">
            <DeviceModel id={`on-device-${look.id}`} model={look.model} label={f ? f.name : `An Android ${on.form}`} screen={RENDERS[look.id]} priority />
          </div>
          {facts}
        </div>
      ) : facts}
      {on.app === 'either' && <p className="small">{dev.onDevice.needs.either}</p>}
      <div className="foot">
        <span className="links"><Link className="link" href={base}>Installing from a computer?</Link></span>
        <button type="button" className="btn ember" onClick={() => go('get')}>Continue</button>
      </div>
    </section>
  );
}

function Elsewhere({ onTablet }: { onTablet: () => void }) {
  const { dev, base, here } = useWhere();
  const [shown, setShown] = useState('');
  useEffect(() => setShown(`${location.host}${here}`), [here]);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{`Open this page on ${dev.the}`}</h2>
      <p className="lede">{`This page installs Glassworks directly from the browser on your Android ${dev.short}.`}</p>
      <div className="well"><span className="grow"><span className="small">{`On ${dev.the}, open`}</span><code className="addr">{shown}</code></span></div>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={onTablet}>{`I'm already on ${dev.the}`}</button></span>
        <Link className="btn ghost" href={base}>Install from this computer</Link>
      </div>
    </section>
  );
}

function TooOld({ on }: { on: OnDevice }) {
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">Glassworks needs Android 6 or newer</h2>
      <p className="lede">This {on.form} runs Android {on.release}. Nothing was downloaded.</p>
      <div className="foot"><span /><Link className="btn ghost" href="/">Pick another panel</Link></div>
    </section>
  );
}

function Get({ on, go, releases, load }: P & { releases: Partial<Record<Line, Release>>; load: (l: Line) => void }) {
  const { dev } = useWhere();
  const [legacy, setLegacy] = useState(buildFor(on) === 'legacy');
  // Kiln picks the build from what the browser runs as (issue 700); the person never picks 32 or 64-bit
  const guess = abiFor(on, FRAMES);
  const line = dev.onDevice.lines[legacy ? 'legacy' : 'generic'];
  const r = releases[line] ?? { state: 'loading' };
  const dl = download(r, guess.abi);
  const other: Abi = guess.abi === 'arm64-v8a' ? 'armeabi-v7a' : 'arm64-v8a';
  // only where the browser said nothing: the other build, should Android refuse this one
  const retry = !guess.sure && download(r, other);
  const buildSwitch = on.app === 'either' && <button key="build" type="button" className="link" onClick={() => setLegacy(!legacy)}>{legacy ? 'Android 8 or newer? Get Glassworks' : 'Android 6 or 7? Get the legacy build'}</button>;
  const links = (...b: ReactNode[]) => b.filter(Boolean).map((x, i) => <Fragment key={i}><span aria-hidden="true">·</span>{x}</Fragment>);

  if (r.state === 'none') {
    return (
      <section className="card" aria-labelledby="h">
        <span className="row"><span className="pill soon">Not released yet</span></span>
        <h2 id="h">{`${dev.app} isn't out yet`}</h2>
        <p className="lede">Once a release is published, you&apos;ll be able to download it right here. Follow the project on GitHub to hear first.</p>
        <div className="foot">
          <span className="links"><button type="button" className="link" onClick={() => go('device')}>Back</button>{links(buildSwitch)}</span>
          <a className="btn ghost" href={dev.off.follow}>Follow it on GitHub</a>
        </div>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Get Glassworks</h2>
      <p className="lede">Download the app below, then open the file to install it.</p>
      {r.state === 'loading' && <p className="status work" role="status">Checking for the latest release…</p>}
      {r.state === 'error' && <>
        <p className="status err" role="alert">Kiln couldn&apos;t load the releases ({r.why}). Try again in a moment.</p>
        <span className="row"><button type="button" className="btn ghost" onClick={() => load(line)}>Try again</button></span>
      </>}
      {r.state === 'out' && !dl && <p className="status err" role="alert">{`Glassworks ${r.version} has no build for this ${on.form} yet.`}</p>}
      {dl && (
        <div className="well">
          <span className="grow">
            <b className="fg">Glassworks {dl.version}{legacy ? ', legacy build' : ''}</b>
            <span className="small">{legacy ? 'For Android 6 and 7' : 'For Android 8 and newer'}</span>
          </span>
          <a className="btn ember" href={dl.href} download={dl.name}>Download</a>
        </div>
      )}
      <ol className="num">
        {legacy && (
          <li><b>First</b> <Path path={['Settings', 'Security', 'Unknown sources']} /> <a className="link" href={SETTINGS_LINKS.settings}>Open Settings</a>
            <small>Turn on <b>Unknown sources</b>, so Android installs an app from outside its store.</small>
          </li>
        )}
        <li><b>Open</b> the downloaded file when it finishes.<small>Tap it in your notifications or in your browser&apos;s Downloads list.</small></li>
        {!legacy && <li><b>Allow</b> this browser if Android asks.<small>Tap <b>Settings</b>, turn on <b>Allow from this source</b>, then go back.</small></li>}
        <li><b>Install</b><small>If Android says “App not installed”, {retry
          ? <a className="link" href={retry.href} download={retry.name}>download the other build</a>
          : <ReportButton prefill={onDevicePrefill(on, 'get', dl?.version)}>let us know</ReportButton>}.</small></li>
      </ol>
      <div className="foot">
        <span className="links">
          <button type="button" className="link" onClick={() => go('device')}>Back</button>
          {links(buildSwitch)}
        </span>
        <button type="button" className="btn ghost" onClick={() => go('setup')}>I&apos;ve installed it</button>
      </div>
    </section>
  );
}

function Setup({ go }: Pick<P, 'go'>) {
  const { dev } = useWhere();
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Open Glassworks</h2>
      <p className="lede">{`Setup walks you through the rest right on ${dev.the}.`}</p>
      <ol className="num">
        <li><b>Home Assistant</b><small>{`Sign in, then add ${dev.the} in Home Assistant.`}</small></li>
        <li><b>Panes</b><small>Choose which features you want. Android asks for the microphone for Assist, and the camera for the camera pane.</small></li>
        <li><b>This device</b><small>Make Glassworks the home screen, and let it turn the screen off when idle.</small></li>
        <li><b>Silent updates</b><small>On Android 12 and newer, let Glassworks install its own updates automatically.</small></li>
      </ol>
      <div className="well">
        <span className="grow">
          <span>One optional permission needs a computer</span>
          <span className="small">Allowing the Bluetooth proxy to turn on Location automatically requires a quick one-time USB step. Glassworks works great without it.</span>
        </span>
      </div>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => go('get')}>Back</button></span>
        <button type="button" className="btn ghost" onClick={() => go('kiln')}>Finish with Kiln</button>
      </div>
    </section>
  );
}

function Kiln({ on, go }: P) {
  const { dev, base } = useWhere();
  const [picking, setPicking] = useState(false);
  const [picked, setPicked] = useState<Identified>();
  const id = picked ?? on.identified;
  const st = id.steps;
  const one = st.debugging.length === 1;
  const [here, setHere] = useState('');
  useEffect(() => setHere(`${location.host}${base}`), [base]);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Turn on USB debugging</h2>
      <p className="lede">Then connect to Kiln on a computer to grant that extra permission over USB. Your Glassworks settings stay untouched.</p>
      <ol className="num">
        <li><b>Open</b> <Path path={st.unlock.path} /> <a className="link" href={SETTINGS_LINKS.about}>Open it</a>
          <small>Tap <b>{st.unlock.tap}</b> seven times, until it says you&apos;re a developer.{st.unlock.note && <> {st.unlock.note}</>}</small>
        </li>
        <li><b>Then</b> {one && <Path path={pageOf(st.debugging[0]!)} />}
          {!one && (
            <ul className="when">
              {st.debugging.map((p) => <li key={p.path.join('/')}>{p.when && <span className="small">{p.when}: </span>}<Path path={pageOf(p)} /></li>)}
            </ul>
          )}
          <small>Turn on <b>{SWITCH}</b>. Android doesn&apos;t open Developer options from a link.{st.debugging.map((p) => p.note).filter(Boolean).map((n) => ` ${n}`).join('')}</small>
        </li>
        <li><b>On a computer</b>, open <code>{here}</code> in Chrome or Edge, and plug the {on.form} in.</li>
      </ol>
      {picking && (
        <RadioGroup.Root className="brands" value={id.brand?.id ?? (picked ? 'other' : '')} aria-label="Your brand"
          onValueChange={(v) => setPicked(dev.identifyPicked(v === 'other' ? undefined : v))}>
          {dev.brands.map((b) => <RadioGroup.Item key={b.id} value={b.id} className="brand-pick">{b.name}</RadioGroup.Item>)}
          <RadioGroup.Item value="other" className="brand-pick">Other<small>Android&apos;s general steps</small></RadioGroup.Item>
        </RadioGroup.Root>
      )}
      <div className="foot">
        <span className="small">
          Steps from {st.sources.map((src, i) => <span key={src.href}>{i > 0 && ' and '}<a className="link" href={src.href} target="_blank" rel="noreferrer">{src.title.split(':')[0]}</a></span>)}
          {' · '}{!picking && <button type="button" className="link" onClick={() => setPicking(true)}>{id.pick ? 'Pick your brand' : 'Different on yours?'}</button>}
        </span>
        <button type="button" className="btn ghost" onClick={() => go('setup')}>Back</button>
      </div>
    </section>
  );
}

export function TabletScreens({ on: read, start, load }: { on: OnDevice; start?: TabletScreen; load: (line: Line) => Promise<Release> }) {
  const { dev } = useWhere();
  const [on, setOn] = useState(read);
  const [screen, setScreen] = useState<TabletScreen>(start ?? firstScreen(read));
  const [releases, setReleases] = useState<Partial<Record<Line, Release>>>({});
  const fetchLine = (line: Line) => {
    setReleases((m) => ({ ...m, [line]: { state: 'loading' } }));
    void load(line).then((r) => setReleases((m) => ({ ...m, [line]: r })));
  };
  useEffect(() => {
    if (screen !== 'get') return;
    const lines: Line[] = on.app === 'either' ? [dev.onDevice.lines.generic, dev.onDevice.lines.legacy] : [dev.onDevice.lines[buildFor(on)]];
    for (const l of lines) if (!releases[l]) fetchLine(l);
  }, [screen, on]);
  useEffect(() => { document.title = `Android ${dev.short} · Kiln`; }, [dev]);
  useEffect(() => { window.scrollTo?.(0, 0); }, [screen]);
  const steps = rail(screen);
  const level = { device: 0, elsewhere: 0, 'too-old': 0, get: 1, setup: 2, kiln: 2 }[screen];
  const p = { on, go: setScreen };
  return (
    <Shell steps={steps} level={level} error={screen === 'too-old'}>
      {screen === 'elsewhere' ? <Elsewhere onTablet={() => { setOn({ ...on, android: true, app: 'either', form: 'tablet' }); setScreen('device'); }} />
        : screen === 'too-old' ? <TooOld on={on} />
        : screen === 'device' ? <Device {...p} />
        : screen === 'get' ? <Get {...p} releases={releases} load={fetchLine} />
        : screen === 'setup' ? <Setup go={setScreen} />
        : <Kiln {...p} />}
    </Shell>
  );
}

/** The on-device screens' frame and routes, for the screens below it (OnDevicePage gives them; tests too). */
export function OnDeviceFrame({ frame: dev, base, children }: { frame: UsbFrame & { onDevice: UsbOnDevice }; base: string; children: ReactNode }) {
  return <WhereContext.Provider value={{ dev, base, here: `${base}/${dev.onDevice.route}` }}>{children}</WhereContext.Provider>;
}

/**
 * The page at the frame's `onDevice` route (`base` its own route): the browser's facts read, or a demo's browser from
 * the frame's sim (?demo=…, development only).
 */
export function OnDevicePage({ frame: dev, sim, base }: { frame: UsbFrame & { onDevice: UsbOnDevice }; sim?: UsbSim; base: string }) {
  const [start, setStart] = useState<{ on: OnDevice; screen?: TabletScreen; load: (line: Line) => Promise<Release> }>();
  useEffect(() => {
    const demo = DEMO_ON ? new URLSearchParams(location.search).get('demo') : null;
    const d = demo && sim?.browsers ? onDeviceDemo(dev.onDevice, sim.browsers, demo) : undefined;
    if (d) { setStart(d); return; }
    void readFacts().then((f) => setStart({ on: dev.onDevice.identify(f), load: (line) => loadRelease(line) }));
  }, [dev, sim]);
  return (
    <OnDeviceFrame frame={dev} base={base}>
      {start ? <TabletScreens on={start.on} start={start.screen} load={start.load} /> : (
        <Shell steps={rail('device')} level={0}>
          <section className="card"><p className="status work" role="status">Checking your device…</p></section>
        </Shell>
      )}
    </OnDeviceFrame>
  );
}
