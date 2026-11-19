'use client';
// What goes on the Mirror (issue 579): the choice between Glassworks and the community images, a community
// image's download, its warning, and both Done screens' way back. The approved round-three design
// (docs/design/kiln/community-images.md): Glassworks first and picked with no badge, the community
// images one quiet click away, who makes a community image on every one of its screens, and a failed
// download shown as Kiln's bug with Try again and Report this, never as a file to fetch by hand.
import type { ImageRecipe } from '@glassworks/kiln-core';
import { fmtSize } from '@glassworks/kiln-core';
import * as RadioGroup from '@radix-ui/react-radio-group';
import type { FileCheck, FlashSession, FlashState } from '../flash';
import { TestOnly } from '../ReleaseApk';
import c from './choice.module.css';

type P = { f: FlashSession; s: FlashState };

/** "Glass Panes’", "Glassworks’", "Foo’s": the possessive of a name, as the rest of Kiln writes it. */
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

function Card({ r }: { r: ImageRecipe }) {
  return (
    <RadioGroup.Item value={r.id} className={c.card} aria-label={r.name}>
      <span className={c.head}>
        <span className="rd" aria-hidden="true" />
        <b className={c.name}>{r.name}</b>
        {r.community && <span className="pill soon">Community image</span>}
      </span>
      <span className={c.summary}>{r.summary}</span>
      <dl className={c.facts}>
        <dt>By</dt><dd>{r.by.name}</dd>
        <dt>Licence</dt><dd>{r.licence}</dd>
        <dt>Support</dt><dd>{r.community ? 'Its project, on GitHub' : r.support.name}</dd>
        <dt>Project</dt><dd><a className="link" href={r.project.href} target="_blank" rel="noopener" onClick={(e) => e.stopPropagation()}>{r.project.name}</a></dd>
      </dl>
    </RadioGroup.Item>
  );
}

/** The page's first screen. Community images stay hidden until asked for; hiding them again goes back to Glassworks. */
export function Choose({ f, s }: P) {
  const r = f.recipe;
  const from = s.from ? f.recipeOf(s.from) : undefined;
  const shown = f.recipes.filter((x) => s.open || !x.community);
  const others = f.recipes.filter((x) => x.community).length;
  return (
    <section className="card" aria-labelledby="h">
      <TestOnly />
      <h2 id="h">What goes on the Mirror?</h2>
      <p className="lede">{s.open ? 'Glassworks, or a community image as its project publishes it. Kiln downloads either one, checks it, and writes it over USB.' : 'Kiln downloads Glassworks, checks it, and writes it over USB.'}</p>
      {from && (
        <p className={`small ${c.from}`} role="status">
          The way back from {from.name}. {r.name} replaces it and everything on the Mirror; {from.name} stays a choice here.
        </p>
      )}
      <RadioGroup.Root id="images" className={`${c.cards}${s.open ? '' : ` ${c.one}`}`} value={r.id} onValueChange={(v) => f.pick(v)} aria-labelledby="h">
        {shown.map((x) => <Card key={x.id} r={x} />)}
      </RadioGroup.Root>
      {others > 0 && (
        <p className={`small ${c.more}`}>
          <button type="button" className="link" aria-expanded={s.open} aria-controls="images" onClick={() => f.showCommunity(!s.open)}>{s.open ? 'Hide community images' : `Show community images (${others})`}</button>
          <span>{s.open ? 'Both take the same steps: fastboot, Kiln’s checks, and a warning before anything is written.' : 'Made by other projects.'}</span>
        </p>
      )}
      <div className="foot">
        <span className="small">Choosing writes nothing.</span>
        <button type="button" className="btn ember" onClick={() => f.choose()}>Continue with {r.name}</button>
      </div>
    </section>
  );
}

/** One file's progress, its check, or what went wrong, with the report a failure needs. */
export function FileRow({ f, file }: { f: FlashSession; file: FileCheck }) {
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
        {file.state === 'waiting' && <span className="small">{started && before && before.state !== 'ok' ? `Next, once ${before.name} is in.` : 'Not downloaded yet.'}</span>}
        {file.state === 'ok' && <span className="status ok">✓ Checks out: SHA-256 as the release publishes it.</span>}
        {failed && (
          <span className="status err" role="alert">
            {file.state === 'bad'
              ? 'Kiln got a different file from the one the release publishes (its SHA-256 differs), so it deleted it.'
              : `Kiln couldn’t download it: ${file.error}.`}
            {' '}Nothing was written to the Mirror. This is a bug in Kiln, not something you did.
          </span>
        )}
        {failed && <span className="small"><a className="link" href={f.reportUrl(file)} target="_blank" rel="noopener">Report this</a> on GitHub, with what Kiln saw filled in.</span>}
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
      <h2 id="h">{idle ? `Install ${r.name} on the Mirror.` : `Downloading ${r.name}.`}</h2>
      <p className="lede">{idle
        ? `Kiln downloads ${r.name} from its own release on GitHub, checks every file against the SHA-256 the release publishes, then takes you through fastboot, its checks and a warning before it writes anything.`
        : 'From its own release on GitHub, into this browser. Kiln checks every file against the SHA-256 the release publishes before anything goes near the Mirror.'}</p>
      {s.files.map((x) => <FileRow key={x.name} f={f} file={x} />)}
      {r.steps.some((x) => x.do === 'wifi') && <WifiFields f={f} s={s} r={r} />}
      <p className="small">{r.release ? <>Release <code>{r.release.name}</code>, through</> : 'Through'} Refract, Kiln&apos;s download proxy, which passes it on and stores nothing.{r.id === 'glass-panes' && ' Its vendor files belong to Qualcomm and Quectel.'}</p>
      <div className="foot">
        <span className="links">
          {!s.link && <><button type="button" className="link" onClick={() => f.backToChoice()}>Back</button><span aria-hidden="true">·</span></>}
          <button type="button" className="link" onClick={() => f.toGlassworks()}>Install Glassworks instead</button>
        </span>
        <DownloadButton f={f} s={s} label={`Download ${r.name}`} />
      </div>
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
        <span className="small">Kiln saves the network in {r.name} before it writes anything, then switches Wi-Fi on after its first start, so the Mirror joins by itself. Only the network&apos;s key is saved, never the password. Leave it empty to set Wi-Fi up in {r.name} instead.</span>
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
      <h2 id="h">This replaces everything on the Mirror.</h2>
      <ol className="join">{s.fastboot!.checks.map((x) => <li key={x.what} className="done">{x.what}: {x.found}</li>)}</ol>
      <p className="lede">
        {r.name} goes on as its release has it, with a fresh start: what the Mirror runs now and its data are replaced, Glassworks included.
        If it still has lululemon&apos;s software, that can&apos;t be restored, by Kiln or anyone.
      </p>
      {reversible && <p className="small">{reversible}.</p>}
      <label className="check"><input type="checkbox" checked={s.understood} onChange={(e) => f.understand(e.target.checked)} /> I understand everything on the Mirror is replaced.</label>
      <div className="foot">
        <span className="links">
          <button type="button" className="link" onClick={() => f.retry()}>Back</button><span aria-hidden="true">·</span>
          <button type="button" className="link" onClick={() => f.toGlassworks()}>Install Glassworks instead</button>
        </span>
        <button type="button" className="btn ember" disabled={!s.understood} onClick={() => void f.flash()}>Install {r.name}</button>
      </div>
    </section>
  );
}

/** The way back, on both Done screens: the other image is one install away, on this same page. */
export function WayBack({ f, other }: { f: FlashSession; other: ImageRecipe }) {
  const now = f.recipe;
  return (
    <div className="well">
      <div className="grow">
        <span className="small">The way back</span>
        <span>{other.community ? `For ${other.name} instead` : `To go back to ${other.name}`}, pick {other.name} here{now.community ? ' and put the Mirror in fastboot' : ': Kiln restarts the Mirror into fastboot over USB, with no button to hold'}. It replaces {now.community ? `${now.name} and its data` : 'Glassworks and its settings'}.</span>
      </div>
      <button type="button" className="btn ghost" onClick={() => f.wayBack(other.id)}>{other.community ? `Install ${other.name} instead` : `Install ${other.name}`}</button>
    </div>
  );
}

/** A community image's Done: Kiln's part is over; what's next is in its own project. */
export function DoneCommunity({ f, log }: { f: FlashSession; log: React.ReactNode }) {
  const r = f.recipe;
  return (
    <section className="card" aria-labelledby="h">
      <Whose r={r} />
      <span className="okmark" aria-hidden="true">✓</span>
      <h2 id="h">{r.name} is on the Mirror.</h2>
      <p className="lede">{f.state.communityWifi === 'on'
        ? 'Wi-Fi is on, and the Mirror joins your network by itself, a few minutes after it starts. Kiln’s part is done.'
        : 'The Mirror is starting it. Its first start takes a few minutes. Kiln’s part is done.'}</p>
      {f.state.communityWifi === 'unconfirmed' && <p className="small" role="status">Android didn&apos;t confirm Wi-Fi is on. Your network is saved: switch Wi-Fi on once in {possessive(r.name)} Settings, and it joins by itself.</p>}
      <div className="well">
        <div className="grow">
          <span className="small">Next, in {r.name}</span>
          <span>Set it up as {r.next ? r.next.name : 'its project'} says. Unplug the Mirror from this computer first.</span>
        </div>
      </div>
      <WayBack f={f} other={f.recipes[0]!} />
      <div className="foot">{log}</div>
    </section>
  );
}
