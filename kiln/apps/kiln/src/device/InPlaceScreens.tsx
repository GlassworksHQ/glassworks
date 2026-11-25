'use client';
// An in-place install: the device restarts into a small setup agent, Kiln takes the bundle from it (from a saved copy, over
// the local network, over Bluetooth or as a file the person picks), and before anything is written Kiln keeps a restore
// point in this browser. Same design as ImageScreens (docs/design/kiln): one idea per screen, one ember button, a way
// out on every screen. Nothing here names a frame: what the device is called and what only it needs said come in
// `words`, from the frame's data.
import { useState, type ReactNode } from 'react';

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

export type ReceiveStage = 'waiting-boot' | 'ready' | 'connecting' | 'cache-hit' | 'transferring' | 'lna-denied' | 'wifi-unreachable' | 'file-fallback' | 'verifying' | 'unverified' | 'error';
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
  /** Start over (StartOver), shown above the buttons where the install can still begin again. */
  startOver?: ReactNode;
  /** Continue anyway, on the signature warning. */
  onContinueUnverified?: () => void;
  /**
   * The device's own Wi-Fi network, when it opens one: the screen asks the person to join it, and Continue fetches the
   * bundle over it (`onContinueWifi`). Bluetooth is then the other way, chosen, never switched to on its own.
   */
  network?: string | null;
  /** The agent answers on that network: this computer joined it. */
  joined?: boolean;
  /** Why the last try over that network failed. */
  wifiFailed?: 'lna-denied' | 'unreachable';
  onContinueWifi?: () => void;
  /** The by-hand screen (download from the agent's page, then pick the file). */
  onByHand?: () => void;
}

const WifiGlyph = ({ size = 22 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
    <path d="M2 8.8a15 15 0 0 1 20 0" /><path d="M5 12.4a10 10 0 0 1 14 0" /><path d="M8.5 15.9a5 5 0 0 1 7 0" /><circle cx="12" cy="19.5" r="1" fill="currentColor" />
  </svg>
);
const BluetoothGlyph = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M7 7l10 10-5 5V2l5 5L7 17" /></svg>
);

/** The device's own network, by name: the one thing the person needs to join it. */
function NetworkTile({ name, on }: { name: string; on: boolean }) {
  return (
    <div className={`net${on ? ' on' : ''}`}>
      <span className="ico"><WifiGlyph /></span>
      <span className="grow"><span className="lab">Network</span><span className="name">{name}</span><span className="sub">No password</span></span>
    </div>
  );
}

/** Joining the device's own Wi-Fi, then Continue: the transfer's first screen on a device that opens one. */
function JoinWifi(p: ReceiveScreenProps & { network: string }) {
  const the = theOf(p.words);
  const waiting = p.stage === 'waiting-boot' && !p.joined;
  const connecting = p.stage === 'connecting';
  return (
    <section className="card" aria-labelledby="h">
      {waiting && <span className="turn-label"><span className="pulse" />Restarting into setup</span>}
      <h2 id="h">{`Join ${the}’s Wi-Fi`}</h2>
      <p className="lede">{`${capital(the)} is starting its setup agent. In a minute or two it opens a Wi-Fi network of its own. Join it on this computer, and Kiln gets the bundle from it in a few seconds.`}</p>
      <NetworkTile name={p.network} on={!!p.joined} />
      {!p.joined && <p className="small">While you’re on it, this computer may say there’s no internet. That’s expected, and Kiln keeps working.</p>}
      {connecting
        ? <><p className="status work" role="status">Connecting…</p><div className="bar wait"><i /></div></>
        : p.joined
          ? <p className="live ok" role="status"><span className="tick" aria-hidden="true">✓</span>{`This computer is on ${the}’s Wi-Fi, and its setup agent answered.`}</p>
          : <p className="live" role="status"><span className="pulse" />Waiting for this computer to join…</p>}
      {p.words.coverNote && <p className="small">{p.words.coverNote}</p>}
      {p.startOver}
      <div className="foot">
        <span className="links">
          {p.onCancel && <><button type="button" className="link" onClick={p.onCancel}>Stop</button>{p.bluetoothAvailable && <span aria-hidden="true">·</span>}</>}
          {p.bluetoothAvailable && !connecting && <button type="button" className="link" onClick={() => p.onConnectBluetooth()}>Use Bluetooth instead</button>}
        </span>
        {!connecting && <button type="button" className={`btn ${p.joined ? 'ember pulsing' : 'ghost'}`} onClick={() => p.onContinueWifi?.()}>Continue</button>}
      </div>
    </section>
  );
}

/** Continue found nothing on the device's own Wi-Fi: Bluetooth, or another try once this computer is on it. */
function WifiUnreachable(p: ReceiveScreenProps & { network: string }) {
  const the = theOf(p.words);
  const denied = p.wifiFailed === 'lna-denied';
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{denied ? 'The browser blocked the local network' : `Kiln can’t reach ${the} over Wi-Fi`}</h2>
      <p className="lede">
        {denied
          ? `Chrome asked whether Kiln may reach devices on your local network, and it was blocked. Allow it in this site’s settings and try again, or get the bundle over Bluetooth, which needs no network and takes about 5 minutes.`
          : <>Check that this computer is on <b>{p.network}</b>, then try again. Or get the bundle over Bluetooth, which needs no network and takes about 5 minutes.</>}
      </p>
      <div className="choices">
        <div className="route">
          <span className="ico"><BluetoothGlyph /></span>
          <span className="grow"><span className="t">Bluetooth</span><span className="d">{p.bluetoothAvailable ? `Chrome asks you to pick ${the}` : 'This browser can’t use Bluetooth. Chrome or Edge on a computer can.'}</span></span>
          <button type="button" className={`btn sm ${p.bluetoothAvailable ? 'ember' : 'ghost'}`} disabled={!p.bluetoothAvailable} onClick={() => p.onConnectBluetooth()}>Use Bluetooth</button>
        </div>
        <div className="route">
          <span className="ico"><WifiGlyph size={18} /></span>
          <span className="grow"><span className="t">Wi-Fi</span><span className="d">{denied ? 'After you allow the local network' : `After you join ${p.network}`}</span></span>
          <button type="button" className="btn ghost sm" onClick={() => p.onContinueWifi?.()}>Try again</button>
        </div>
      </div>
      {p.startOver}
      <div className="foot">
        <span className="links">
          {p.onCancel && <><button type="button" className="link" onClick={p.onCancel}>Stop</button><span aria-hidden="true">·</span></>}
          <button type="button" className="link" onClick={() => p.onByHand?.()}>Download it by hand instead</button>
        </span>
      </div>
    </section>
  );
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

/** Stops the transfer in flight; the agent keeps running, so the install goes on from here. */
function Cancel({ onCancel }: { onCancel?: () => void }) {
  return <span className="links">{onCancel && <button type="button" className="link" onClick={onCancel}>Stop transfer</button>}</span>;
}

function NoBluetooth() {
  return <p className="small" role="note">This browser can’t use Bluetooth. Chrome or Edge on a computer can, or you can download the bundle by hand below.</p>;
}

/** Getting the bundle from the device's setup agent, from the restart to the integrity check. */
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
        <p className="lede">{`${capital(the)}’s system hasn’t changed. Check the details below and try again when you’re ready.`}</p>
        {p.errorMessage && <pre className="raw">{p.errorMessage}</pre>}
        <p className="small">Or download the bundle by hand:</p><ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} />
        {p.startOver}
      <div className="foot">
          <Cancel onCancel={p.onCancel} />
          {p.onRetry && <button type="button" className="btn ember" onClick={p.onRetry}>Try again</button>}
        </div>
      </section>
    );
  }

  if (stage === 'unverified') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Kiln can’t confirm this bundle</h2>
        <p className="lede">{`It comes from ${the} and arrived whole, but its signature doesn’t match the setup agent this browser started. That happens when the install began in another browser, or this browser’s data was cleared since.`}</p>
        <p className="small">{`Go on only if you just took it from ${the} yourself. Its system hasn’t changed, and Kiln saves a restore point before it changes anything.`}</p>
        {p.startOver}
      <div className="foot">
          <span className="links">{p.onRetry && <button type="button" className="link" onClick={p.onRetry}>Back</button>}</span>
          {p.onContinueUnverified && <button type="button" className="btn ember" onClick={p.onContinueUnverified}>Continue anyway</button>}
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
        {p.startOver}
      <div className="foot">
          <span className="links">
            {p.onCancel && <><button type="button" className="link" onClick={p.onCancel}>Stop</button><span aria-hidden="true">·</span></>}
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
        {p.startOver}
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

  if (p.network && stage === 'wifi-unreachable') return <WifiUnreachable {...p} network={p.network} />;
  if (p.network && (stage === 'waiting-boot' || stage === 'ready' || (stage === 'connecting' && p.channel === 'lan'))) return <JoinWifi {...p} network={p.network} />;

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
    // over the device's own network it is Wi-Fi to the person, not "the local network"
    const ch = p.channel ? (p.channel === 'lan' && p.network ? { ...CHANNELS.lan, label: 'Wi-Fi' } : CHANNELS[p.channel]) : null;
    const eta = fmtEta(pr?.etaSeconds ?? null);
    return (
      <section className="card" aria-labelledby="h">
        {ch && <span className="chip"><span className="dot" />{ch.label}{ch.typical && <span className="small">{` · ${ch.typical}`}</span>}</span>}
        <h2 id="h">{`Getting the bundle from ${the}`}</h2>
        {p.fallbackReason && <p className="small" role="note">{p.fallbackReason}</p>}
        <div className="bar" role="progressbar" aria-label="Getting the bundle" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
          <i style={{ width: `${Math.max(2, frac * 100)}%` }} />
        </div>
        {pr && (
          <p className="status" aria-live="polite">
            {`${pct}% · ${fmtMb(pr.receivedBytes)} / ${fmtMb(pr.totalBytes)} · ${fmtSpeed(pr.bytesPerSecond)}${eta ? ` · ${eta}` : ''}`}
          </p>
        )}
        {(pr?.resendPass ?? 0) > 0 && <p className="status work" role="status">Checking missing pieces…</p>}
        {manual && <ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} />}
        {p.startOver}
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
        : <p className="lede">{`${capital(the)} is ready to hand over the bundle. Kiln takes it over your local network when it can, and over Bluetooth when it can’t.`}</p>}
      {words.coverNote && <p className="small">{words.coverNote}</p>}
      {waiting && <div className="bar wait"><i /></div>}
      {connecting && <><p className="status work" role="status">Connecting…</p><div className="bar wait"><i /></div></>}
      {!p.bluetoothAvailable && <><NoBluetooth /><ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} /></>}
      {p.bluetoothAvailable && manual && <ByHand url={p.agentPageUrl} words={words} onPickFile={p.onPickFile} />}
      {p.startOver}
      <div className="foot">
        <span className="links">
          {p.onCancel && <button type="button" className="link" onClick={p.onCancel}>Stop</button>}
          {/* Bluetooth can be there and still find nothing (the device's radio off, the browser not allowed to scan) */}
          {!waiting && p.bluetoothAvailable && !manual && <>{p.onCancel && <span aria-hidden="true">·</span>}<button type="button" className="link" onClick={() => setManual(true)}>Download it by hand instead</button></>}
        </span>
        {!connecting && bt(true)}
      </div>
    </section>
  );
}

/** What an in-place install changes, and anything the person must keep doing through it (the frame's `coverNote`). */
/** What an in-place install changes. `cover`: with the cover note, between the two fastboot sessions only (it asks for VOL− once more). */
export function InPlaceNotice({ words, cover = false }: { words: InPlaceWords; cover?: boolean }) {
  return (
    <div className="well" role="note">
      <div className="grow">
        <span className="status">What changes</span>
        <span className="small">{words.changes ?? 'The app data starts fresh, while the original system stays in place, so you can go back to it whenever you like.'}</span>
        {cover && words.coverNote && <span className="small">{words.coverNote}</span>}
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
  startOver?: ReactNode;
  /** Why the copy didn't download, when it didn't. */
  downloadError?: string | null;
}

/** The restore point: saved in this browser and read back before anything is written. Continue waits for it. */
export function RestorePointScreen(p: RestorePointScreenProps) {
  const { status, words } = p;
  const the = theOf(words);
  if (status === 'error') {
    return (
      <section className="card err" aria-labelledby="h">
        <h2 id="h">The restore point couldn&apos;t be saved</h2>
        <p className="lede">{`This browser couldn’t store it, so ${the}’s system hasn’t changed. Free some space or allow storage for this site, then try again.`}</p>
        {p.errorMessage && <pre className="raw">{p.errorMessage}</pre>}
        {p.startOver}
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
            <span className="small">Keep a copy on your computer too, in case this browser’s storage is cleared. It downloads as a .zip, which the way back can open.</span>
            {p.downloadError && <span className="status err" role="alert">{`The copy didn’t download: ${p.downloadError}`}</span>}
          </div>
          <button type="button" className="btn ghost" onClick={() => p.onDownload()}>{p.downloaded ? 'Downloaded ✓' : 'Download a copy'}</button>
        </div>
      )}
      <InPlaceNotice words={words} />
      {p.startOver}
      <div className="foot">
        <span className="links">{!ready && <span className="small">Continue opens once the restore point is saved and checked.</span>}</span>
        <button type="button" className="btn ember" disabled={!ready} onClick={() => { if (ready) p.onContinue(); }}>Continue</button>
      </div>
    </section>
  );
}

/** What Start over needs from the session (ImageSession). */
export interface StartOverHost { canStartOver(): boolean; askStartOver(ask?: boolean): void; startOver(): void }

/**
 * Start over, where an in-place install can still begin again (ImageSession.canStartOver): a link, then, once asked,
 * what it resets and the button that does it. `what`: the frame's words for what starting over changes.
 */
export function StartOver({ f, asking, what }: { f: StartOverHost; asking: boolean; what: string }) {
  if (!f.canStartOver()) return null;
  if (!asking) {
    return <p className="small startover">Want to begin again? <button type="button" className="link" onClick={() => f.askStartOver()}>Start over</button></p>;
  }
  return (
    <div className="well startover" role="group" aria-labelledby="startover-h">
      <div className="grow">
        <span id="startover-h"><b>Start over?</b></span>
        <span className="small">{what}</span>
      </div>
      <span className="links">
        <button type="button" className="link" onClick={() => f.askStartOver(false)}>Keep going</button>
        <button type="button" className="btn ghost sm" onClick={() => f.startOver()}>Start over</button>
      </span>
    </div>
  );
}
