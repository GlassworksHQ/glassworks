'use client';
// What goes on the device (issue 579): the choice between Glassworks and the community images, a community
// image's download, its warning, and both Done screens' way back. The approved round-three design
// (docs/design/kiln/community-images.md): Glassworks first and picked with no badge, the community
// images one quiet click away, who makes a community image on every one of its screens, and a failed
// download shown as Kiln's bug with Try again and Report this, never as a file to fetch by hand.
import type { ImageRecipe } from '@glassworks/kiln-core';
import { cap, fmtSize } from '@glassworks/kiln-core';
import * as RadioGroup from '@radix-ui/react-radio-group';
import type { FileCheck, ImageSession, ImageState } from '../imageSession';
import { TestOnly } from '../ReleaseApk';
import { Foot } from '../../components/Nav';
import { Usually } from '../../components/Kit';
import { totalTime } from '../words';
import c from './choice.module.css';

type P = { f: ImageSession; s: ImageState };

/** "Glassworks’", "Foo’s": the possessive of a name, as the rest of Kiln writes it. */
export const possessive = (name: string) => (/s$/i.test(name) ? `${name}’` : `${name}’s`);

const Out = ({ l }: { l: { name: string; href: string } }) => <a className="link" href={l.href} target="_blank" rel="noopener">{l.name}</a>;

/**
 * Who makes the image and where questions about it go: on every screen of a community install, above
 * the heading. It is the image's credit too (frame.json `credits`), so the screens carry no second one.
 */
export function Whose({ r }: { r: ImageRecipe }) {
  return (
    <div className={c.whose}>
      <span className="pill soon">Community image</span>
      <span className="small"><b className={c.fg}>{r.name}</b> is made by <Out l={r.by} />. Questions about it go to <Out l={{ name: 'its project', href: r.project.href }} />.</span>
    </div>
  );
}

/**
 * One image. The radio is the card's whole face (stretched over it), and the project's link sits above it, beside the
 * radio rather than inside it: a link inside a button is neither a valid control nor one a screen reader can reach.
 */
function Card({ r, picked, time }: { r: ImageRecipe; picked: boolean; time?: string }) {
  const id = `image-${r.id}`;
  return (
    <div className={c.card} data-state={picked ? 'checked' : 'unchecked'}>
      <RadioGroup.Item value={r.id} className={c.pick} aria-labelledby={`${id}-name`} aria-describedby={[`${id}-sum`, r.advice && `${id}-trade`, time && `${id}-time`].filter(Boolean).join(' ')} />
      <span className={c.head}>
        <span className="rd" aria-hidden="true" />
        <b className={c.name} id={`${id}-name`}>{r.name}</b>
        {r.community && <span className="pill soon">Community image</span>}
        {/* the frame's advice (recipe `advice`): which way it recommends, and the other one */}
        {!r.community && r.advice && <span className={`pill ${r.advice.tag === 'recommended' ? c.rec : 'soon'}`}>{r.advice.tag === 'recommended' ? 'Recommended' : 'Alternative'}</span>}
      </span>
      <span className={c.summary} id={`${id}-sum`}>{r.summary}</span>
      {r.advice && <span className={c.trade} id={`${id}-trade`}>{r.advice.trade}</span>}
      {/* how long it usually takes, from the frame's words (time.total.<recipe id>): only where the frame gives one */}
      <Usually time={time} id={`${id}-time`} />
      {/* who makes it, its licence, where questions go, its project: one quiet line (community-images.md keeps all four) */}
      <p className={c.facts}>
        <span>{`By ${r.by.name}`}</span>
        <span>{r.licence}</span>
        <span>{`Support: ${r.community ? 'Its project, on GitHub' : r.support.name}`}</span>
        <span><a className={`link ${c.above}`} href={r.project.href} target="_blank" rel="noopener">{r.project.name}</a></span>
      </p>
    </div>
  );
}

/** The page's first screen. Community images stay hidden until asked for; hiding them again goes back to Glassworks. */
export function Choose({ f, s }: P) {
  const r = f.recipe;
  const from = s.from ? f.recipeOf(s.from) : undefined;
  // the recommended way first, then the alternative, then the rest in the frame's order
  const rank = (x: ImageRecipe) => (x.advice?.tag === 'recommended' ? 0 : x.advice?.tag === 'alternative' ? 1 : 2);
  const shown = f.recipes.filter((x) => s.open || !x.community).map((x, i) => ({ x, i })).sort((a, b) => rank(a.x) - rank(b.x) || a.i - b.i).map(({ x }) => x);
  const others = f.recipes.filter((x) => x.community).length;
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly f={f.frame} />
      <h2 id="h">{`Choose what to install on ${f.frame.the}`}</h2>
      {s.open && <p className="lede">Install Glassworks, or choose a community image published by its own project.</p>}
      {from && (
        <p className={`small ${c.from}`} role="status">
          {r.name}{` replaces ${from.name} and everything on ${f.frame.the}.`}
        </p>
      )}
      <RadioGroup.Root id="images" className={`${c.cards}${s.open ? '' : ` ${c.one}`}`} value={r.id} onValueChange={(v) => f.pick(v)} aria-labelledby="h">
        {shown.map((x) => <Card key={x.id} r={x} picked={x.id === r.id} time={totalTime(f.frame, x.id)} />)}
      </RadioGroup.Root>
      {others > 0 && (
        <p className={`small ${c.more}`}>
          <button type="button" className="link" aria-expanded={s.open} aria-controls="images" onClick={() => f.showCommunity(!s.open)}>{s.open ? 'Hide community images' : `Show community images (${others})`}</button>
          {!s.open && <span>Maintained by other community projects.</span>}
        </p>
      )}
      <Foot>
        <button type="button" className="btn ember" onClick={() => f.choose()}>Continue with {r.name}</button>
      </Foot>
    </section>
  );
}

/** One file's progress, its check, or what went wrong, with the report a failure needs. */
export function FileRow({ f, file }: { f: ImageSession; file: FileCheck }) {
  const pct = Math.floor(file.progress * 100);
  const failed = file.state === 'bad' || file.state === 'error';
  const before = f.state.files[f.state.files.indexOf(file) - 1];
  const started = f.state.files.some((x) => x.state !== 'waiting');
  return (
    <div className="well">
      <div className="grow">
        <span className={c.fileHead}><code>{file.name}</code><span className="small">{fmtSize(file.size)}</span></span>
        {file.state === 'downloading' && (
          <div className={c.progress} role="status">
            <span className="small">Downloading and checking · {pct}%</span>
            <div className="bar" role="progressbar" aria-label={`Downloading ${file.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><i style={{ width: `${Math.max(2, pct)}%` }} /></div>
          </div>
        )}
        {file.state === 'waiting' && <span className="small">{started && before && before.state !== 'ok' ? `Waiting for ${before.name} to finish.` : 'Ready to download.'}</span>}
        {file.state === 'ok' && <span className="status ok">✓ Download verified</span>}
        {failed && (
          <span className="status err" role="alert">
            {/* the error itself goes to the technical log and the report, not on the screen (issue 672) */}
            {file.state === 'bad' ? 'This download doesn’t match the published release, so Kiln discarded it.' : 'Kiln couldn’t download this file.'}
            {f.state.changed
              ? ` This download didn't write anything, though an earlier attempt may have changed ${f.frame.the}. This is a problem on Kiln's side.`
              : ` Nothing was written to ${f.frame.the} — this is a problem on Kiln's side.`}
          </span>
        )}
        {failed && <span className="small"><a className="link" href={f.reportUrl(file)} target="_blank" rel="noopener">Report this</a> on GitHub.</span>}
      </div>
    </div>
  );
}

/** The ember button of a download screen: download, Try again after a failure, Continue once every file checks out. */
export function DownloadButton({ f, s, label }: P & { label: string }) {
  const idle = s.files.every((x) => x.state === 'waiting');
  const failed = s.files.some((x) => x.state === 'bad' || x.state === 'error');
  const busy = s.files.some((x) => x.state === 'downloading') || (!idle && !failed && s.files.some((x) => x.state === 'waiting'));
  if (idle) return <button type="button" className="btn ember" onClick={() => void f.download()}>{label}</button>;
  if (failed) return <button type="button" className="btn ember" onClick={() => void f.download()}>Try again</button>;
  return <button type="button" className="btn ember" disabled={busy || !!f.blocked()} onClick={() => f.toFastboot()}>{busy ? 'Downloading…' : 'Continue'}</button>;
}

/**
 * A community image's download. Arriving by the image's link (`?image=<id>`) this is the page's first
 * screen: nothing starts until the click, and the switch to Glassworks sits in the foot.
 */
export function Download({ f, s }: P) {
  const r = f.recipe;
  const idle = s.files.every((x) => x.state === 'waiting');
  return (
    <section className="card" aria-labelledby="h">
      <Whose r={r} />
      <h2 id="h">{idle ? `Install ${r.name} on ${f.frame.the}` : `Downloading ${r.name}`}</h2>
      <p className="lede">Downloaded from its official GitHub release and verified before writing.</p>
      {s.files.map((x) => <FileRow key={x.name} f={f} file={x} />)}
      {r.steps.some((x) => x.do === 'wifi') && <WifiFields f={f} s={s} r={r} />}
      {(r.release || r.vendor) && <p className="small">{r.release && <>Release <code>{r.release.name}</code>.</>}{r.release && r.vendor && ' '}{r.vendor}</p>}
      <Foot>
        <button type="button" className="btn ghost" onClick={() => f.toGlassworks()}>Install Glassworks instead</button>
        <DownloadButton f={f} s={s} label={`Download ${r.name}`} />
      </Foot>
    </section>
  );
}

/**
 * A community image whose recipe saves the Wi-Fi: an optional network, so it joins by itself after its
 * first start with nothing opened in its Settings. Empty: it goes on as published.
 */
function WifiFields({ f, s, r }: P & { r: ImageRecipe }) {
  const blocked = s.wifi.ssid || s.wifi.password ? f.blocked() : undefined;
  return (
    <div className="well">
      <div className="grow">
        <span className="small">Wi-Fi (optional)</span>
        <span className="small">{`${cap(f.frame.the)} joins it by itself after its first start. Leave it empty to set up Wi-Fi in `}{r.name} instead.</span>
        <div className="fields">
          <label className="field"><span>Wi-Fi network</span>
            <input className="input" autoComplete="off" value={s.wifi.ssid} onChange={(e) => f.setWifi({ ssid: e.target.value })} />
          </label>
          <label className="field"><span>Wi-Fi password</span>
            <input className="input" type="password" autoComplete="off" value={s.wifi.password} onChange={(e) => f.setWifi({ password: e.target.value })} />
          </label>
        </div>
        {blocked && !blocked.startsWith('Download') && <span className="status err" role="alert">{blocked}</span>}
      </div>
    </div>
  );
}

/** Kiln's checks and the wipe warning before a community image is written: its safety steps, as for Glassworks. */
export function ConfirmCommunity({ f, s, reversible }: P & { reversible?: string }) {
  const r = f.recipe;
  return (
    <section className="card" aria-labelledby="h">
      <Whose r={r} />
      <h2 id="h">{`This replaces everything on ${f.frame.the}`}</h2>
      <ol className="join">{s.fastboot!.checks.map((x) => <li key={x.what} className="done">{x.what}: {x.found}</li>)}</ol>
      <p className="lede">
        {`What ${f.frame.the} runs now and its data are replaced, Glassworks included. ${f.frame.words['community.ifVendor']}`}
      </p>
      {reversible && <p className="small">{reversible}.</p>}
      <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} />{` I understand everything on ${f.frame.the} is replaced.`}</label>
      <Foot>
        <button type="button" className="btn ghost" onClick={() => f.toGlassworks()}>Install Glassworks instead</button>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Install {r.name}</button>
      </Foot>
    </section>
  );
}

/** The way back, on both Done screens: the other image is one install away, on this same page. */
/** `then`: after the way back is taken (the standard flow's Done leaves for the install's first screen). */
export function WayBack({ f, other, then }: { f: ImageSession; other: ImageRecipe; then?: () => void }) {
  const now = f.recipe;
  return (
    <div className="well">
      <div className="grow">
        <span className="small">The way back</span>
        <span>{`Install ${other.name} from here. It replaces ${now.community ? `${now.name} and its data` : 'Glassworks and its settings'}.`}</span>
      </div>
      <button type="button" className="btn ghost" onClick={() => { f.wayBack(other.id); then?.(); }}>{other.community ? `Install ${other.name} instead` : `Install ${other.name}`}</button>
    </div>
  );
}

/** A community image's Done: Kiln's part is over; what's next is in its own project. */
export function DoneCommunity({ f, log }: { f: ImageSession; log: React.ReactNode }) {
  const r = f.recipe;
  return (
    <section className="card" aria-labelledby="h">
      <Whose r={r} />
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{r.name}{` is on ${f.frame.the}`}</h2>
      <p className="lede">{f.state.communityWifi === 'on'
        ? `${cap(f.frame.the)} joins your Wi-Fi a few minutes after it starts.`
        : 'Its first start takes a few minutes.'}</p>
      {f.state.communityWifi === 'unconfirmed' && <p className="small" role="status">Android didn&apos;t confirm Wi-Fi is on — switch it on once in Settings inside {r.name}.</p>}
      <div className="well">
        <div className="grow">
          <span className="small">Next, in {r.name}</span>
          <span>Follow the setup instructions in {r.next ? r.next.name : 'its project page'}.</span>
        </div>
      </div>
      <WayBack f={f} other={f.recipes[0]!} />
      {log}
    </section>
  );
}
