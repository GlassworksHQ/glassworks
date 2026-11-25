'use client';
// An in-place install: the device restarts into a small setup agent, Kiln sends it the bundle (from a saved copy, over
// the local network, over Bluetooth or as a file the person picks), and before anything is written Kiln keeps a restore
// point in this browser. Same design as ImageScreens (docs/design/kiln): one idea per screen, one ember button, a way
// out on every screen. Nothing here names a frame: what the device is called and what only it needs said come in
// `words`, from the frame's data.
import { useState } from 'react';

/**
 * Copy a frame may supply (installer.json `words`). Every key is optional: the screens fall back to plain, neutral
 * words. `the` is how a sentence names the device ("the panel"); `coverNote` is anything the person must keep doing
 * through the install (a cover left off, a cable left in).
 */
export interface InPlaceWords {
  the?: string;
  title?: string;
  bootHint?: string;
  coverNote?: string;
  changes?: string;
  restoreTitle?: string;
  restoreLede?: string;
  [key: string]: string | undefined;
}

export type ReceiveStage = 'waiting-boot' | 'ready' | 'connecting' | 'cache-hit' | 'transferring' | 'lna-denied' | 'file-fallback' | 'verifying' | 'error';
export type ReceiveChannel = 'cache' | 'lan' | 'ble' | 'file';

export interface ReceiveProgress {
  receivedBytes: number;
  totalBytes: number;
  bytesPerSecond: number;
  etaSeconds: number | null;
  /** Which pass over missing pieces this is: 0 or absent on the first send. */
  resendPass?: number;
}

export interface ReceiveScreenProps {
  stage: ReceiveStage;
  channel: ReceiveChannel | null;
  bluetoothAvailable: boolean;
  progress: ReceiveProgress | null;
  /** The setup agent's own page, where the bundle can be downloaded by hand. */
  agentPageUrl: string | null;
  /** Why Kiln moved to another channel (local network to Bluetooth, say): said calmly, not as an error. */
  fallbackReason: string | null;
  errorMessage: string | null;
  words: InPlaceWords;
  onConnectBluetooth(): void;
  onRetryLan?: () => void;
  onPickFile(file: File): void;
  onCancel?: () => void;
  onRetry?: () => void;
}

/** How each channel reads on its chip, with how long a bundle usually takes on it. */
export const CHANNELS: Record<ReceiveChannel, { label: string; typical?: string }> = {
  cache: { label: 'Saved copy' },
  lan: { label: 'Local network', typical: 'usually a few seconds' },
  ble: { label: 'Bluetooth', typical: 'usually about 5 minutes' },
  file: { label: 'File' },
};

const MB = 1024 * 1024;
/** Bytes as megabytes, one decimal under 10 MB. */
export const fmtMb = (n: number) => { const v = n / MB; return `${v < 10 ? v.toFixed(1) : Math.round(v)} MB`; };
/** A speed: KB/s under a megabyte a second, MB/s from there. */
export const fmtSpeed = (bps: number) => (bps >= MB ? `${(bps / MB).toFixed(1)} MB/s` : `${Math.max(0, Math.round(bps / 1024))} KB/s`);
/** Time left, the way a person says it: "~2s left", "~4 min left". */
export function fmtEta(s: number | null): string | null {
  if (s == null || !Number.isFinite(s) || s < 0) return null;
  if (s < 60) return `~${Math.max(1, Math.round(s))}s left`;
  return `~${Math.round(s / 60)} min left`;
}

const theOf = (w: InPlaceWords) => w.the ?? 'the device';
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** The bundle file, picked the way ImageScreens picks an APK. */
function BundlePicker({ onPickFile, label = 'The downloaded bundle file' }: { onPickFile(file: File): void; label?: string }) {
  return (
    <div className="fields">
      <label className="field"><span>{label}</span>
        <input className="input" type="file" accept=".bin,application/octet-stream"
          onChange={(e) => { const x = e.target.files?.[0]; if (x) onPickFile(x); }} />
      </label>
    </div>
  );
}

/** By hand: download the bundle from the agent's own page, then pick it here. */
function ByHand({ url, words, onPickFile }: { url: string | null; words: InPlaceWords; onPickFile(file: File): void }) {
  return (
    <ol className="join">
      <li>
        {url
          ? <a className="link" href={url} target="_blank" rel="noopener noreferrer">{`Open ${theOf(words)}’s download page`}</a>
          : `Open ${theOf(words)}’s download page`}
        <span className="sub-step">{url ? 'It opens in a new tab and saves the bundle to your computer.' : `Its address is shown on ${theOf(words)}.`}</span>
      </li>
      <li>
        Select the downloaded bundle file
        <span className="sub-step"><BundlePicker onPickFile={onPickFile} /></span>
      </li>
    </ol>
  );
}

function Cancel({ onCancel }: { onCancel?: () => void }) {
  return <span className="links">{onCancel && <button type="button" className="link" onClick={onCancel}>Cancel</button>}</span>;
}

function NoBluetooth() {
  return <p className="small" role="note">This browser can’t use Bluetooth. Chrome or Edge on a computer can, or you can download the bundle by hand below.</p>;
}

/** Sending the bundle to the device's setup agent, from the restart to the integrity check. */
export function ReceiveScreen(p: ReceiveScreenProps) {
  const { stage, words } = p;
  const the = theOf(words);
  const [manual, setManual] = useState(false);
  const bt = (ember: boolean, label = 'Connect over Bluetooth') => (
    <button type="button" className={`btn ${ember && p.bluetoothAvailable ? 'ember' : 'ghost'}`} disabled={!p.bluetoothAvailable} onClick={() => p.onConnectBluetooth()}>{label}</button>
  );

  if (stage === 'error') {
    return (
      <section className="card err" aria-labelledby="h">
        <h2 id="h">The transfer didn&apos;t finish</h2>
        <p className="lede">{`Nothing has been written to ${the} yet. Check the details below and try again when you’re ready.`}</p>
        {p.errorMessage && <pre className="raw">{p.errorMessage}</pre>}
        {p.agentPageUrl && <><p className="small">Or download the bundle by hand:</p><ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} /></>}
        <div className="foot">
          <Cancel onCancel={p.onCancel} />
          {p.onRetry && <button type="button" className="btn ember" onClick={p.onRetry}>Try again</button>}
        </div>
      </section>
    );
  }

  if (stage === 'lna-denied') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">The browser blocked the local network</h2>
        <p className="lede">{`Chrome asked whether Kiln may reach devices on your network, and it was blocked. Bluetooth works without it and doesn’t need Wi-Fi, or you can allow it and try again.`}</p>
        {!p.bluetoothAvailable && <NoBluetooth />}
        <ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} />
        <div className="foot">
          <span className="links">
            {p.onCancel && <><button type="button" className="link" onClick={p.onCancel}>Cancel</button><span aria-hidden="true">·</span></>}
            {p.onRetryLan && <button type="button" className="link" onClick={p.onRetryLan}>Allow local network</button>}
            {p.agentPageUrl && <><span aria-hidden="true">·</span><a className="link" href={p.agentPageUrl} target="_blank" rel="noopener noreferrer">Open device page</a></>}
          </span>
          {bt(true, 'Continue over Bluetooth')}
        </div>
      </section>
    );
  }

  if (stage === 'file-fallback') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Download the bundle by hand</h2>
        <p className="lede">{`${capital(the)} serves the bundle on its own page. Save it to this computer, then pick it here.`}</p>
        <ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} />
        <div className="foot">
          <Cancel onCancel={p.onCancel} />
          {p.bluetoothAvailable && bt(false, 'Use Bluetooth instead')}
        </div>
      </section>
    );
  }

  if (stage === 'verifying') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Checking the bundle</h2>
        <p className="status work" role="status">Making sure every byte arrived as it was sent…</p>
        <div className="bar wait"><i /></div>
        <p className="small">Nothing is written until the check passes.</p>
      </section>
    );
  }

  if (stage === 'cache-hit') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Checking the bundle</h2>
        <p className="status ok" role="status">✓ Matched a saved copy on this computer, so there’s nothing to send</p>
        <div className="bar wait"><i /></div>
      </section>
    );
  }

  if (stage === 'transferring') {
    const pr = p.progress;
    const frac = pr && pr.totalBytes > 0 ? Math.min(1, pr.receivedBytes / pr.totalBytes) : 0;
    const pct = Math.floor(frac * 100);
    const ch = p.channel ? CHANNELS[p.channel] : null;
    const eta = fmtEta(pr?.etaSeconds ?? null);
    return (
      <section className="card" aria-labelledby="h">
        {ch && <span className="chip"><span className="dot" />{ch.label}{ch.typical && <span className="small">{` · ${ch.typical}`}</span>}</span>}
        <h2 id="h">{`Sending the bundle to ${the}`}</h2>
        {p.fallbackReason && <p className="small" role="note">{p.fallbackReason}</p>}
        <div className="bar" role="progressbar" aria-label="Sending the bundle" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
          <i style={{ width: `${Math.max(2, frac * 100)}%` }} />
        </div>
        {pr && (
          <p className="status" aria-live="polite">
            {`${pct}% · ${fmtMb(pr.receivedBytes)} / ${fmtMb(pr.totalBytes)} · ${fmtSpeed(pr.bytesPerSecond)}${eta ? ` · ${eta}` : ''}`}
          </p>
        )}
        {(pr?.resendPass ?? 0) > 0 && <p className="status work" role="status">Checking missing pieces…</p>}
        {manual && <ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} />}
        <div className="foot">
          <Cancel onCancel={p.onCancel} />
          {p.agentPageUrl && !manual && <button type="button" className="link" onClick={() => setManual(true)}>Use manual download instead</button>}
        </div>
      </section>
    );
  }

  // waiting-boot, ready and connecting: the device is (re)starting into its setup agent, or waiting for a connection
  const waiting = stage === 'waiting-boot';
  const connecting = stage === 'connecting';
  return (
    <section className="card" aria-labelledby="h">
      {waiting && <span className="turn-label"><span className="pulse" />Restarting</span>}
      <h2 id="h">{words.title ?? (waiting ? `Waiting for ${the} to restart` : `Connect to ${the}`)}</h2>
      {waiting
        ? <p className="lede">{words.bootHint ?? `${capital(the)} is restarting into its setup agent. The first start can take a few minutes, so leave it plugged in. When it says it’s ready, connect over Bluetooth.`}</p>
        : <p className="lede">{`${capital(the)} is ready for the bundle. Kiln sends it over your local network when it can, and over Bluetooth when it can’t.`}</p>}
      {words.coverNote && <p className="small">{words.coverNote}</p>}
      {waiting && <div className="bar wait"><i /></div>}
      {connecting && <><p className="status work" role="status">Connecting…</p><div className="bar wait"><i /></div></>}
      {!p.bluetoothAvailable && <><NoBluetooth /><ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} /></>}
      <div className="foot">
        <Cancel onCancel={p.onCancel} />
        {!connecting && bt(true)}
      </div>
    </section>
  );
}

/** What an in-place install changes, and anything the person must keep doing through it (the frame's `coverNote`). */
export function InPlaceNotice({ words }: { words: InPlaceWords }) {
  return (
    <div className="well" role="note">
      <div className="grow">
        <span className="status">What changes</span>
        <span className="small">{words.changes ?? 'The app data starts fresh, while the original system stays in place, so you can go back to it whenever you like.'}</span>
        {words.coverNote && <span className="small">{words.coverNote}</span>}
      </div>
    </div>
  );
}

export interface RestorePointScreenProps {
  status: 'saving' | 'verifying' | 'ready' | 'error';
  /** The restore point's SHA-256, as read back. */
  verifiedDigest: string | null;
  downloaded: boolean;
  restoreFilename: string;
  errorMessage: string | null;
  words: InPlaceWords;
  onDownload(): void;
  onContinue(): void;
  onRetry?: () => void;
}

/** The restore point: saved in this browser and read back before anything is written. Continue waits for it. */
export function RestorePointScreen(p: RestorePointScreenProps) {
  const { status, words } = p;
  const the = theOf(words);
  if (status === 'error') {
    return (
      <section className="card err" aria-labelledby="h">
        <h2 id="h">The restore point couldn&apos;t be saved</h2>
        <p className="lede">{`This browser couldn’t store it, so nothing on ${the} has changed yet. Free some space or allow storage for this site, then try again.`}</p>
        {p.errorMessage && <pre className="raw">{p.errorMessage}</pre>}
        <div className="foot">
          <span className="links" />
          {p.onRetry && <button type="button" className="btn ember" onClick={p.onRetry}>Try again</button>}
        </div>
      </section>
    );
  }
  const ready = status === 'ready';
  const digest = p.verifiedDigest;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{words.restoreTitle ?? 'Save a restore point'}</h2>
      <p className="lede">{words.restoreLede ?? `Kiln keeps a copy of what ${the} runs today, so you can go back to it at any time.`}</p>
      {status === 'saving' && <><p className="status work" role="status">Saving it in this browser…</p><div className="bar wait"><i /></div></>}
      {status === 'verifying' && <><p className="status work" role="status">Reading it back to check it…</p><div className="bar wait"><i /></div></>}
      {ready && (
        <div className="well">
          <div className="grow">
            <span className="status ok" role="status">✓ Saved in this browser</span>
            {digest && <span className="small">Checked, SHA-256 <code title={digest}>{digest.slice(0, 12)}</code></span>}
            <span className="small">{`Keep a copy on your computer too, as ${p.restoreFilename}, in case this browser’s storage is cleared.`}</span>
          </div>
          <button type="button" className="btn ghost" onClick={() => p.onDownload()}>{p.downloaded ? 'Downloaded ✓' : 'Download a copy'}</button>
        </div>
      )}
      <InPlaceNotice words={words} />
      <div className="foot">
        <span className="links">{!ready && <span className="small">Continue opens once the restore point is saved and checked.</span>}</span>
        <button type="button" className="btn ember" disabled={!ready} onClick={() => { if (ready) p.onContinue(); }}>Continue</button>
      </div>
    </section>
  );
}
