'use client';
// /android/tablet (issue 403, part 3): Glassworks from the tablet's own browser, no computer. The page names the device
// as far as the browser lets it (identifyBrowser), offers the right APK from the generic app's latest release
// through Kiln's release route, says what the app's own setup asks next, and how to finish with Kiln over USB.
// Kiln's look (docs/design/kiln): one idea per card, one ember button, details on request.
import { BRANDS, identifyBrowser, identifyPicked, type BrandId, type Identified, type OnDevice, type Place } from '@glassworks/generic-android-installer';
import { KIND_TEXT, STAGE_TEXT } from '@glassworks/frame-card';
import * as RadioGroup from '@radix-ui/react-radio-group';
import Link from 'next/link';
import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { Shell } from '../../components/Shell';
import { FRAMES, RENDERS } from '../../frames/catalog';
import { readFacts } from './facts';
import { tabletDemo } from './demo';
import {
  LINES, PICKS, SETTINGS_LINKS, abiFor, buildFor, download, firstScreen, loadRelease, rail, type Abi, type Line, type Release, type TabletScreen,
} from './model';
import '../../device/usb.css';
import './tablet.css';

const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';
const ISSUE = 'https://github.com/GlassworksHQ/glassworks/issues/403';

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
  if (model) return `${on.modelCode ? 'This is' : 'This looks like'} a ${model.name}.`;
  if (brand) return `This is a ${brand.name} ${on.form}.`;
  return `This is an Android ${on.form}.`;
}

function versionText(on: OnDevice): string {
  if (on.release) return `Android ${on.release}`;
  return on.app === 'generic' ? 'Android 8 or newer' : 'Android version not shown';
}

interface P { on: OnDevice; go: (s: TabletScreen) => void }

function Device({ on, go }: P) {
  const f = on.identified.model?.frame ? FRAMES.find((x) => x.id === on.identified.model!.frame) : undefined;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{nameOf(on)}</h2>
      <p className="lede">Glassworks installs from this page, as an app. No computer needed.</p>
      {f ? (
        <div className="fc tab-fc">
          {RENDERS[f.id] && <img className="ph" src={RENDERS[f.id]} alt="" />}
          <div className="spread"><b>{f.name}</b><span className="pill soon">{STAGE_TEXT[f.status]}</span></div>
          <span className="kind">{KIND_TEXT[f.kind]} · {versionText(on)}</span>
        </div>
      ) : (
        <dl className="kv">
          <dt>Android</dt><dd>{versionText(on).replace(/^Android /, '')}</dd>
          {on.modelCode && <><dt>Model</dt><dd>{on.identified.model ? `${on.identified.model.name} (${on.modelCode})` : on.modelCode}</dd></>}
          <dt>Tested</dt><dd>Not yet. <a className="link" href={`https://github.com/GlassworksHQ/glassworks/issues/new?title=${encodeURIComponent(`Tested: ${on.identified.model?.name ?? on.modelCode ?? 'an Android tablet'}`)}`}>Tell us how it goes</a></dd>
        </dl>
      )}
      {on.app === 'either' && <p className="small">The browser doesn&apos;t say the Android version. Glassworks needs Android 8, or its legacy build on Android 6 and 7. The next step has both.</p>}
      <div className="foot">
        <span className="links"><Link className="link" href="/android">Installing from a computer?</Link></span>
        <button type="button" className="btn ember" onClick={() => go('get')}>Continue</button>
      </div>
    </section>
  );
}

function Elsewhere({ onTablet }: { onTablet: () => void }) {
  const [here, setHere] = useState('');
  useEffect(() => setHere(`${location.host}/android/tablet`), []);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Open this page on the tablet.</h2>
      <p className="lede">This page installs Glassworks on an Android tablet from the tablet&apos;s own browser.</p>
      <div className="well"><span className="grow"><span className="small">On the tablet, open</span><code className="addr">{here}</code></span></div>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={onTablet}>This is the tablet</button></span>
        <Link className="btn ghost" href="/android">Install from this computer</Link>
      </div>
    </section>
  );
}

function TooOld({ on }: { on: OnDevice }) {
  return (
    <section className="card err" aria-labelledby="h">
      <h2 id="h">Glassworks needs Android 6 or newer.</h2>
      <p className="lede">This {on.form} runs Android {on.release}. Nothing was downloaded.</p>
      <div className="foot"><span /><Link className="btn ghost" href="/">Pick another panel</Link></div>
    </section>
  );
}

function Get({ on, go, releases, load }: P & { releases: Partial<Record<Line, Release>>; load: (l: Line) => void }) {
  const [legacy, setLegacy] = useState(buildFor(on) === 'legacy');
  const guess = abiFor(on, FRAMES);
  const [abi, setAbi] = useState<Abi>(guess.abi);
  const line = LINES[legacy ? 'legacy' : 'generic'];
  const r = releases[line] ?? { state: 'loading' };
  const dl = download(line, r, abi);
  const other: Abi = abi === 'arm64-v8a' ? 'armeabi-v7a' : 'arm64-v8a';
  const bits = (a: Abi) => (a === 'arm64-v8a' ? '64-bit' : '32-bit');
  const buildSwitch = on.app === 'either' && <button key="build" type="button" className="link" onClick={() => setLegacy(!legacy)}>{legacy ? 'Android 8 or newer? Get Glassworks' : 'Android 6 or 7? Get the legacy build'}</button>;
  const abiSwitch = !guess.sure && <button key="abi" type="button" className="link" onClick={() => setAbi(other)}>{`A ${bits(other)} ${on.form}? Get that build`}</button>;
  const links = (...b: ReactNode[]) => b.filter(Boolean).map((x, i) => <Fragment key={i}><span aria-hidden="true">·</span>{x}</Fragment>);

  if (r.state === 'none') {
    return (
      <section className="card" aria-labelledby="h">
        <span className="row"><span className="pill soon">Not released yet</span></span>
        <h2 id="h">Glassworks for Android isn&apos;t out yet.</h2>
        <p className="lede">When it is, it downloads from this page. Follow it on GitHub to hear first.</p>
        <div className="foot">
          <span className="links"><button type="button" className="link" onClick={() => go('device')}>Back</button>{links(buildSwitch)}</span>
          <a className="btn ghost" href={ISSUE}>Follow it on GitHub</a>
        </div>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Get Glassworks.</h2>
      <p className="lede">Download it here, then install it from the download.</p>
      {r.state === 'loading' && <p className="status work" role="status">Looking for the latest release…</p>}
      {r.state === 'error' && <>
        <p className="status err" role="alert">Kiln couldn&apos;t read the releases: {r.why}.</p>
        <span className="row"><button type="button" className="btn ghost" onClick={() => load(line)}>Try again</button></span>
      </>}
      {r.state === 'out' && !dl && <p className="status err" role="alert">Glassworks {r.version} has no {bits(abi)} build.</p>}
      {dl && (
        <div className="well">
          <span className="grow">
            <b className="fg">Glassworks {dl.version}{legacy ? ', legacy build' : ''}</b>
            <span className="small">{legacy ? 'For Android 6 and 7' : 'For Android 8 and newer'} · {bits(abi)}</span>
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
        <li><b>Open</b> the download when it finishes.<small>From the notification, or the browser&apos;s Downloads.</small></li>
        {!legacy && <li><b>Allow</b> this browser if Android asks.<small>Tap <b>Settings</b>, turn on <b>Allow from this source</b>, then go back.</small></li>}
        <li><b>Install</b><small>Android says “App not installed”? {guess.sure ? 'Tell us on GitHub.' : `This ${on.form} may need the ${bits(other)} build.`}</small></li>
      </ol>
      <div className="foot">
        <span className="links">
          <button type="button" className="link" onClick={() => go('device')}>Back</button>
          {links(buildSwitch, abiSwitch)}
        </span>
        <button type="button" className="btn ghost" onClick={() => go('setup')}>It&apos;s installed</button>
      </div>
    </section>
  );
}

function Setup({ go }: Pick<P, 'go'>) {
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Open Glassworks.</h2>
      <p className="lede">Its setup walks you through the rest, on the tablet.</p>
      <ol className="num">
        <li><b>Home Assistant</b><small>Sign in, then add the tablet in Home Assistant.</small></li>
        <li><b>Panes</b><small>Pick what it does. Android asks for the microphone for Assist, and the camera for the camera pane.</small></li>
        <li><b>This device</b><small>Make Glassworks the home screen, and let it turn the screen off when idle.</small></li>
        <li><b>Silent updates</b><small>On Android 12 and newer, let it install its own updates.</small></li>
      </ol>
      <div className="well">
        <span className="grow">
          <span>One setting needs a computer</span>
          <span className="small">Secure settings, which lets the Bluetooth proxy turn on Location by itself. Glassworks works without it.</span>
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
  const [picking, setPicking] = useState(false);
  const [picked, setPicked] = useState<Identified>();
  const id = picked ?? on.identified;
  const st = id.steps;
  const one = st.debugging.length === 1;
  const [here, setHere] = useState('');
  useEffect(() => setHere(`${location.host}/android`), []);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Turn on USB debugging.</h2>
      <p className="lede">Then Kiln on a computer adds Secure settings over USB. Glassworks and its setup stay.</p>
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
          onValueChange={(v) => setPicked(identifyPicked(v === 'other' ? undefined : (v as BrandId)))}>
          {PICKS.map((b) => <RadioGroup.Item key={b} value={b} className="brand-pick">{BRANDS.find((x) => x.id === b)!.name}</RadioGroup.Item>)}
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
  const [on, setOn] = useState(read);
  const [screen, setScreen] = useState<TabletScreen>(start ?? firstScreen(read));
  const [releases, setReleases] = useState<Partial<Record<Line, Release>>>({});
  const fetchLine = (line: Line) => {
    setReleases((m) => ({ ...m, [line]: { state: 'loading' } }));
    void load(line).then((r) => setReleases((m) => ({ ...m, [line]: r })));
  };
  useEffect(() => {
    if (screen !== 'get') return;
    const lines: Line[] = on.app === 'either' ? [LINES.generic, LINES.legacy] : [LINES[buildFor(on)]];
    for (const l of lines) if (!releases[l]) fetchLine(l);
  }, [screen, on]);
  useEffect(() => { document.title = 'Android tablet · Kiln'; }, []);
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

export function TabletPage() {
  const [start, setStart] = useState<{ on: OnDevice; screen?: TabletScreen; load: (line: Line) => Promise<Release> }>();
  useEffect(() => {
    const demo = DEMO_ON ? new URLSearchParams(location.search).get('demo') : null;
    const d = demo ? tabletDemo(demo) : undefined;
    if (d) { setStart(d); return; }
    void readFacts().then((f) => setStart({ on: identifyBrowser(f), load: (line) => loadRelease(line) }));
  }, []);
  if (start) return <TabletScreens on={start.on} start={start.screen} load={start.load} />;
  return (
    <Shell steps={rail('device')} level={0}>
      <section className="card"><p className="status work" role="status">Reading this device…</p></section>
    </Shell>
  );
}
