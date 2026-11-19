'use client';
// Mockups for issue 579 (/lululemon-mirror?demo=image-…): what goes on the Mirror, Glassworks or a community
// image, and the community image's install, Done and way back. Kiln's own components and classes on
// still states, with no session behind them. Throwaway until the maintainer approves the design.
import { fmtSize } from '@glassworks/kiln-core';
import type { FastbootReport } from '@glassworks/lululemon-mirror-installer';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useEffect, useState } from 'react';
import { BuiltOn, Credit } from '../../components/Credit';
import { Shell, useBrowserChip } from '../../components/Shell';
import { FRAMES } from '../../frames/catalog';
import type { RailStep } from '../../wizard/state';
import { flashDemo } from '../flashDemo';
import { OPTIONS, option, type ImageOption } from './options';
import c from './choice.module.css';

export const IMAGE_DEMOS = ['image-choose', 'image-choose-panes', 'image-download', 'image-download-done', 'image-download-bad', 'image-confirm',
  'image-writing', 'image-done', 'image-done-glassworks', 'image-back-glassworks', 'image-back-panes'] as const;
export type ImageDemo = (typeof IMAGE_DEMOS)[number];

type Screen = 'choose' | 'download' | 'confirm' | 'writing' | 'done';
type FileState = { state: 'downloading' | 'waiting' | 'ok' | 'bad'; x: number };

const RAIL = ['Mirror', 'Choose', 'Download', 'Fastboot', 'Install', 'Done'];
const AT: Record<Screen, number> = { choose: 1, download: 2, confirm: 3, writing: 4, done: 6 };
const rail = (screen: Screen): RailStep[] => RAIL.map((name, i) => ({ name, mark: i < AT[screen] ? 'done' : i > AT[screen] ? 'todo' : 'now' }));
const REVERSIBLE = FRAMES.find((x) => x.id === 'lululemon-mirror')?.ratings.reversible.label;
const panesCredit = () => FRAMES.find((x) => x.id === 'lululemon-mirror')?.credits?.find((x) => x.id === 'glass-panes-image');

const Out = ({ l }: { l: { name: string; href: string } }) => <a className="link" href={l.href} target="_blank" rel="noopener">{l.name}</a>;

/** Whose software this screen installs: on every screen of a community install, above the heading. */
function Whose({ o }: { o: ImageOption }) {
  return (
    <div className={c.whose}>
      <span className="pill soon">Community image</span>
      <span className="small"><b className={c.fg}>{o.name}</b>, by <Out l={o.by} />. Kiln installs it; it isn&apos;t Glassworks.</span>
    </div>
  );
}

function Card({ o }: { o: ImageOption }) {
  return (
    <RadioGroup.Item value={o.id} className={c.card} aria-label={o.name}>
      <span className={c.head}>
        <span className="rd" aria-hidden="true" />
        <b className={c.name}>{o.name}</b>
        <span className="pill soon">{o.community ? 'Community image' : 'Default'}</span>
      </span>
      <span className={c.summary}>{o.summary}</span>
      <dl className={c.facts}>
        <dt>By</dt><dd>{o.by.name}</dd>
        <dt>Licence</dt><dd>{o.licence}</dd>
        <dt>Support</dt><dd>{o.support.name}{o.community ? ', not Glassworks' : ''}</dd>
        <dt>Project</dt><dd><a className="link" href={o.project.href} target="_blank" rel="noopener" onClick={(e) => e.stopPropagation()}>{o.project.name}</a></dd>
      </dl>
    </RadioGroup.Item>
  );
}

function Choose({ picked, setPicked, from, next }: { picked: ImageOption['id']; setPicked: (id: ImageOption['id']) => void; from?: ImageOption; next: () => void }) {
  const o = option(picked);
  return (
    <section className="card" aria-labelledby="h">
      <div className="row"><span className="pill soon">Test only</span></div>
      <h2 id="h">What goes on the Mirror?</h2>
      <p className="lede">Glassworks, or a community image as its project publishes it. Kiln downloads either one, checks it, and writes it over USB.</p>
      {from && (
        <p className={`small ${c.from}`} role="status">
          The way back from {from.name}. {o.name} replaces it and everything on the Mirror; {from.name} stays a choice here.
        </p>
      )}
      <RadioGroup.Root className={c.cards} value={picked} onValueChange={(v) => setPicked(v as ImageOption['id'])} aria-labelledby="h">
        {OPTIONS.map((x) => <Card key={x.id} o={x} />)}
      </RadioGroup.Root>
      <p className="small">Both take the same steps: fastboot, Kiln&apos;s checks, and a warning before anything is written. To switch later, come back here and pick the other one.</p>
      <div className="foot">
        <span className="small">Choosing writes nothing.</span>
        <button type="button" className="btn ember" onClick={next}>Continue with {o.name}</button>
      </div>
    </section>
  );
}

function FileRow({ f, st }: { f: ImageOption['files'][number]; st: FileState }) {
  const pct = Math.floor(st.x * 100);
  return (
    <div className="well">
      <div className="grow">
        <span className={c.fileHead}><code>{f.name}</code><span className="small">{fmtSize(f.size)}</span></span>
        {st.state === 'downloading' && (
          <div className={c.progress} role="status">
            <span className="small">Downloading and checking · {pct}%</span>
            <div className="bar" role="progressbar" aria-label={`Downloading ${f.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><i style={{ width: `${Math.max(2, pct)}%` }} /></div>
          </div>
        )}
        {st.state === 'waiting' && <span className="small">Next, once {f.name === 'system.img' ? 'the image' : 'system.img'} is in.</span>}
        {st.state === 'ok' && <span className="status ok">✓ Checks out: SHA-256 as the release publishes it.</span>}
        {st.state === 'bad' && <span className="status err" role="alert">This isn&apos;t the file the release publishes (its SHA-256 differs). Kiln deleted it. Nothing was written to the Mirror.</span>}
      </div>
      {st.state === 'bad' && <button type="button" className="btn ghost">Download it again</button>}
    </div>
  );
}

function Download({ o, files, back, next }: { o: ImageOption; files: FileState[]; back: () => void; next: () => void }) {
  const ready = files.every((x) => x.state === 'ok');
  const credit = panesCredit();
  return (
    <section className="card" aria-labelledby="h">
      <Whose o={o} />
      <h2 id="h">Downloading {o.name}.</h2>
      <p className="lede">From its own release on GitHub, into this browser. Kiln checks every file against the SHA-256 the release publishes before anything goes near the Mirror.</p>
      {o.files.map((f, i) => <FileRow key={f.name} f={f} st={files[i]!} />)}
      {credit && <Credit c={credit} built="image" />}
      <p className="small">Release <code>mirror-android-2</code>, through Refract, Kiln&apos;s download proxy, which passes it on and stores nothing. Its vendor files belong to Qualcomm and Quectel, not to Glassworks.</p>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={back}>Choose another image</button></span>
        <button type="button" className="btn ember" disabled={!ready} onClick={next}>{files.some((x) => x.state === 'downloading' || x.state === 'waiting') ? 'Downloading…' : 'Continue'}</button>
      </div>
    </section>
  );
}

function Confirm({ o, checks, back, next }: { o: ImageOption; checks: FastbootReport['checks']; back: () => void; next: () => void }) {
  const [ok, setOk] = useState(false);
  return (
    <section className="card" aria-labelledby="h">
      <Whose o={o} />
      <h2 id="h">This replaces everything on the Mirror.</h2>
      <ol className="join">{checks.map((x) => <li key={x.what} className="done">{x.what}: {x.found}</li>)}</ol>
      <p className="lede">
        {o.name} goes on as its release has it, with a fresh start: what the Mirror runs now and its data are replaced, Glassworks included.
        If it still has lululemon&apos;s software, that can&apos;t be restored, by Kiln or anyone.
      </p>
      <div className="well">
        <div className="grow">
          <span>After this, the Mirror runs {o.by.name}&apos;s software, not Glassworks.</span>
          <span className="small">Questions about it go to <Out l={o.support} />. Glassworks can&apos;t help with it beyond this install.</span>
        </div>
      </div>
      {REVERSIBLE && <p className="small">{REVERSIBLE}.</p>}
      <label className="check"><input type="checkbox" checked={ok} onChange={(e) => setOk(e.target.checked)} /> I understand everything on the Mirror is replaced.</label>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={back}>Back</button></span>
        <button type="button" className="btn ember" disabled={!ok} onClick={next}>Install {o.name}</button>
      </div>
    </section>
  );
}

const WRITE_ROWS = ['Flash system', 'Flash userdata', 'Restart the Mirror'];

function Writing({ o }: { o: ImageOption }) {
  const at = 0;
  const x = 0.63;
  return (
    <section className="card" aria-labelledby="h">
      <Whose o={o} />
      <h2 id="h" className="huge">Keep it plugged in.</h2>
      <ol className="join">
        {WRITE_ROWS.map((r, i) => <li key={r} className={i < at ? 'done' : i === at ? 'cur' : ''}>{r}{i === at && <span className="sub-step">{Math.floor(x * 100)}%</span>}</li>)}
      </ol>
      <div className="bar" role="progressbar" aria-label={WRITE_ROWS[at]} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.floor(((at + x) / WRITE_ROWS.length) * 100)}>
        <i style={{ width: `${((at + x) / WRITE_ROWS.length) * 100}%` }} />
      </div>
      <button type="button" className="link">Technical log</button>
    </section>
  );
}

/** The way back, on both Done screens: the other image is one install away, on this same page. */
function WayBack({ other, go }: { other: ImageOption; go: () => void }) {
  return (
    <div className="well">
      <div className="grow">
        <span className="small">The way back</span>
        <span>{other.community ? `For ${other.name} instead` : `To go back to ${other.name}`}, put the Mirror in fastboot and pick {other.name} on this page. It replaces {other.community ? 'Glassworks and its settings' : `${option('glass-panes').name} and its data`}.</span>
      </div>
      <button type="button" className="btn ghost" onClick={go}>{other.community ? `Install ${other.name} instead` : `Install ${other.name}`}</button>
    </div>
  );
}

function DoneCommunity({ o, back }: { o: ImageOption; back: () => void }) {
  const credit = panesCredit();
  return (
    <section className="card" aria-labelledby="h">
      <Whose o={o} />
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{o.name} is on the Mirror.</h2>
      <p className="lede">The Mirror is starting it. Its first start takes a few minutes, then it opens its own Mirror Kiosk app. Kiln&apos;s part is done.</p>
      <div className={c.next}>
        <div className="well">
          <div className="grow">
            <span className="small">Next, in {o.name}</span>
            <span>Set it up as {o.guide ? <Out l={o.guide} /> : 'its project'} says. Unplug the Mirror from this computer first.</span>
          </div>
        </div>
        <div className="well">
          <div className="grow">
            <span className="small">Support</span>
            <span>{o.name} is made by <Out l={o.by} />. Ask on <Out l={o.support} />, not on Glassworks&apos;.</span>
          </div>
        </div>
      </div>
      <WayBack other={option('glassworks')} go={back} />
      {credit && <Credit c={credit} built="image" />}
      <div className="foot"><button type="button" className="link">Technical log</button></div>
    </section>
  );
}

function DoneGlassworks({ back }: { back: () => void }) {
  return (
    <section className="card" aria-labelledby="h">
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">Glassworks is on the Mirror.</h2>
      <p className="lede">Glassworks 0.5.0 is its home screen (Mirror Kiosk hidden).</p>
      <WayBack other={option('glass-panes')} go={back} />
      {panesCredit() && <BuiltOn credits={[panesCredit()!]} />}
      {REVERSIBLE && <p className="small">{REVERSIBLE}.</p>}
      <div className="foot"><button type="button" className="link">Technical log</button></div>
    </section>
  );
}

const FILES: Record<string, FileState[]> = {
  'image-download': [{ state: 'downloading', x: 0.42 }, { state: 'waiting', x: 0 }],
  'image-download-done': [{ state: 'ok', x: 1 }, { state: 'ok', x: 1 }],
  'image-download-bad': [{ state: 'ok', x: 1 }, { state: 'bad', x: 1 }],
};

const START: Record<ImageDemo, { screen: Screen; picked: ImageOption['id']; from?: ImageOption['id']; ours?: boolean }> = {
  'image-choose': { screen: 'choose', picked: 'glassworks' },
  'image-choose-panes': { screen: 'choose', picked: 'glass-panes' },
  'image-download': { screen: 'download', picked: 'glass-panes' },
  'image-download-done': { screen: 'download', picked: 'glass-panes' },
  'image-download-bad': { screen: 'download', picked: 'glass-panes' },
  'image-confirm': { screen: 'confirm', picked: 'glass-panes' },
  'image-writing': { screen: 'writing', picked: 'glass-panes' },
  'image-done': { screen: 'done', picked: 'glass-panes' },
  'image-done-glassworks': { screen: 'done', picked: 'glassworks', ours: true },
  'image-back-glassworks': { screen: 'choose', picked: 'glassworks', from: 'glass-panes' },
  'image-back-panes': { screen: 'choose', picked: 'glass-panes', from: 'glassworks' },
};

export function ImageChoiceDemo({ name }: { name: ImageDemo }) {
  const start = START[name];
  const [screen, setScreen] = useState<Screen>(start.screen);
  const [picked, setPicked] = useState(start.picked);
  const [from, setFrom] = useState(start.from);
  const [checks, setChecks] = useState<FastbootReport['checks']>([]);
  const chip = useBrowserChip();
  useEffect(() => {
    // the real fastboot checks, from the Mirror's simulated bootloader
    void flashDemo('flash-confirm', true).then(({ flash }) => setChecks(flash.state.fastboot?.checks ?? []));
  }, []);
  const o = option(picked);
  const ours = screen === 'done' && start.ours;
  const backTo = (id: ImageOption['id']) => { setFrom(picked); setPicked(id); setScreen('choose'); };
  return (
    <Shell steps={rail(screen)} level={ours ? 4 : 0} chip={chip}>
      {screen === 'choose' ? <Choose picked={picked} setPicked={setPicked} from={from ? option(from) : undefined} next={() => (o.community ? setScreen('download') : location.assign('?demo=flash-image'))} />
        : screen === 'download' ? <Download o={o} files={FILES[name] ?? [{ state: 'ok', x: 1 }, { state: 'ok', x: 1 }]} back={() => setScreen('choose')} next={() => setScreen('confirm')} />
        : screen === 'confirm' ? <Confirm o={o} checks={checks} back={() => setScreen('download')} next={() => setScreen('writing')} />
        : screen === 'writing' ? <Writing o={o} />
        : ours ? <DoneGlassworks back={() => backTo('glass-panes')} />
        : <DoneCommunity o={o} back={() => backTo('glassworks')} />}
    </Shell>
  );
}
