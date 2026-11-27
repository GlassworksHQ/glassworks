'use client';
// An in-place install: the device restarts into a small setup helper (the agent), Kiln copies from it what the install
// changes (the bundle: from a saved copy, over the local network, over Bluetooth or as a file the person picks), and
// before anything is written Kiln keeps a restore point in this browser. The screens never say "bundle" or "agent":
// people see the parts of their device's system that Kiln copies, and a setup helper. Same design as ImageScreens (docs/design/kiln): one idea per screen, one ember button, a way
// out on every screen. Nothing here names a frame: what the device is called and what only it needs said come in
// `words`, from the frame's data.
import { useState, type ReactNode } from 'react';
import { Foot } from '../components/Nav';
import { BrowserAsk, Hands, Instructions, Note, Problem, Progress, Wait, rowsAt } from '../components/Kit';

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
  /** How long the device takes to start its setup helper ("1–2 minutes"). */
  agentTime?: string;
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
  /** `agentPageUrl` is the device's address from before its restart: the agent may have another now. */
  agentPageGuessed?: boolean;
  /** The agent's HTTP port, for the download by hand when Kiln doesn't know its address. */
  agentPort?: number;
  /** Why Kiln moved to another channel (local network to Bluetooth, say): said calmly, not as an error. */
  fallbackReason: string | null;
  errorMessage: string | null;
  words: InPlaceWords;
  onConnectBluetooth(): void;
  onRetryLan?: () => void;
  onPickFile(file: File): void;
  onCancel?: () => void;
  onRetry?: () => void;
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
  /** The technical log, last inside the card. */
  log?: ReactNode;
}

const WifiGlyph = ({ size = 22 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
    <path d="M2 8.8a15 15 0 0 1 20 0" /><path d="M5 12.4a10 10 0 0 1 14 0" /><path d="M8.5 15.9a5 5 0 0 1 7 0" /><circle cx="12" cy="19.5" r="1" fill="currentColor" />
  </svg>
);
const BluetoothGlyph = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M7 7l10 10-5 5V2l5 5L7 17" /></svg>
);


const DownloadGlyph = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 4v11M7 10l5 5 5-5M5 20h14" /></svg>
);

/** The device's own network, by name: the one thing the person needs to join it. */
function NetworkTile({ name, on }: { name: string; on: boolean }) {
  return (
    <div className={`net${on ? ' on' : ''}`}>
      <span className="ico"><WifiGlyph /></span>
      <span className="grow"><span className="lab">{on ? 'Joined' : 'Network to join'}</span><span className="name">{name}</span><span className="sub">No password</span></span>
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
      <h2 id="h">{p.joined ? `You’re on ${the}’s Wi-Fi` : `Join ${the}’s Wi-Fi`}</h2>
      <p className="lede">{`${capital(the)} is starting a small setup helper, which opens a Wi-Fi network of its own. Kiln copies what it needs over it in a few seconds.`}</p>
      {waiting && <Wait what="Waiting for this computer to join it" typical={p.words.agentTime ? `${p.words.agentTime} for it to appear` : undefined} note="Its screen may stay dark meanwhile. That’s normal." />}
      <Instructions label="Join its Wi-Fi" steps={[
        <>Open this computer’s Wi-Fi menu.</>,
        <>Join the network below. It has no password.<NetworkTile name={p.network} on={!!p.joined} /></>,
        <>Come back to this tab and click Continue.</>,
      ]} />
      {!p.joined && <p className="small">While you’re on it, this computer may say there’s no internet. That’s expected, and Kiln keeps working.</p>}
      {connecting
        ? <p className="status work" role="status">Connecting…</p>
        : p.joined
          ? <p className="live ok" role="status"><span className="tick" aria-hidden="true">✓</span>{`This computer is on ${the}’s Wi-Fi, and its setup helper answered.`}</p>
          : !waiting && <p className="live" role="status"><span className="pulse" />Waiting for this computer to join…</p>}
      {p.words.coverNote && <Note tone="turn" title="Keep the back cover off">{p.words.coverNote}</Note>}
      <Foot>
        <span className="links">
          {p.onCancel && <button type="button" className="btn ghost" onClick={p.onCancel}>Stop</button>}
          {p.bluetoothAvailable && !connecting && <button type="button" className="btn ghost" onClick={() => p.onConnectBluetooth()}>Use Bluetooth instead</button>}
          {/* no Bluetooth here: the helper's own page is the other way, without waiting for Continue to fail first */}
          {!p.bluetoothAvailable && !connecting && p.agentPageUrl && p.onByHand && <button type="button" className="btn ghost" onClick={() => p.onByHand?.()}>Download it by hand instead</button>}
        </span>
        {!connecting && <button type="button" className={`btn ${p.joined ? 'ember pulsing' : 'ghost'}`} onClick={() => p.onContinueWifi?.()}>Continue</button>}
      </Foot>
      {p.log}
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
      <Note tone="ok">{`${capital(the)}’s system hasn’t changed. Pick another way to go on.`}</Note>
      <p className="lede">
        {denied
          ? `Chrome asked whether Kiln may reach devices on your local network, and it was blocked. Allow it in this site’s settings and try again, or use Bluetooth, which needs no network and takes about 5 minutes.`
          : <>Check that this computer is on <b>{p.network}</b>, then try again. Or use Bluetooth, which needs no network and takes about 5 minutes.</>}
      </p>
      <div className="choices">
        <div className="route">
          <span className="ico"><BluetoothGlyph /></span>
          <span className="grow"><span className="t">Bluetooth</span><span className="d">{p.bluetoothAvailable ? `About 5 minutes. Your browser asks you to pick ${the}` : 'This browser can’t use Bluetooth. Chrome or Edge on a computer can.'}</span></span>
          <button type="button" className={`btn sm ${p.bluetoothAvailable ? 'ember' : 'ghost'}`} disabled={!p.bluetoothAvailable} onClick={() => p.onConnectBluetooth()}>Use Bluetooth</button>
        </div>
        <div className="route">
          <span className="ico"><WifiGlyph size={18} /></span>
          <span className="grow"><span className="t">Wi-Fi</span><span className="d">{denied ? 'After you allow the local network' : `After you join ${p.network}`}</span></span>
          <button type="button" className="btn ghost sm" onClick={() => p.onContinueWifi?.()}>Try again</button>
        </div>
      </div>
      <Foot>
        <span className="links">
          {p.onCancel && <button type="button" className="btn ghost" onClick={p.onCancel}>Stop</button>}
          <button type="button" className="btn ghost" onClick={() => p.onByHand?.()}>Download it by hand instead</button>
        </span>
      </Foot>
      {p.log}
    </section>
  );
}

/** How each channel reads on its chip, with how long a transfer usually takes on it. */
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
/** Time left as the transfer's headline: "About 4 minutes left", "A few seconds left". */
export function etaWords(s: number | null): string | null {
  if (s == null || !Number.isFinite(s) || s < 0) return null;
  if (s < 20) return 'A few seconds left';
  if (s < 90) return 'About a minute left';
  return `About ${Math.round(s / 60)} minutes left`;
}

const theOf = (w: InPlaceWords) => w.the ?? 'the device';
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** The downloaded file, picked the way ImageScreens picks an APK. */
function FilePicker({ onPickFile, label = 'The downloaded file' }: { onPickFile(file: File): void; label?: string }) {
  return (
    <div className="fields">
      <label className="field"><span>{label}</span>
        <input className="input" type="file" accept=".bin,application/octet-stream"
          onChange={(e) => { const x = e.target.files?.[0]; if (x) onPickFile(x); }} />
      </label>
    </div>
  );
}

/**
 * By hand: download the file from the helper's own page, then pick it here. Without its address (no Bluetooth to ask,
 * no network of the device's own), the person finds it in their router; a guessed one says it may have changed.
 */
function ByHand({ p, onPickFile }: { p: Pick<ReceiveScreenProps, 'agentPageUrl' | 'agentPageGuessed' | 'agentPort' | 'words'>; onPickFile(file: File): void }) {
  const url = p.agentPageUrl, the = theOf(p.words);
  const lookUp = `your router’s list of devices`;
  const sub = url
    ? `It opens in a new tab and saves a file to this computer.${p.agentPageGuessed ? ` If nothing opens once ${the} has restarted, its address may have changed: ${lookUp} shows the new one.` : ''}`
    : p.agentPort
      ? `Find ${the}’s address in ${lookUp}, then go to http://<address>:${p.agentPort} in a new tab.`
      : `Find ${the}’s address in ${lookUp}, then open it in a new tab.`;
  return (
    <Instructions label="By hand" steps={[
      <>
        {url
          ? <a className="link" href={url} target="_blank" rel="noopener noreferrer">{`Open ${the}’s download page`}</a>
          : `Open ${the}’s download page`}
        <span className="sub-step">{sub}</span>
      </>,
      <>Select the downloaded file<FilePicker onPickFile={onPickFile} /></>,
    ]} />
  );
}

/** Stops the transfer in flight; the helper keeps running, so the install goes on from here. */
function Cancel({ onCancel }: { onCancel?: () => void }) {
  return onCancel ? <button type="button" className="btn ghost" onClick={onCancel}>Stop transfer</button> : <span />;
}

function NoBluetooth() {
  return <Note tone="info">This browser can’t use Bluetooth. Chrome or Edge on a computer can, or you can download it by hand below.</Note>;
}

/** Copying what the install needs from the device's setup helper, from the restart to the check. */
export function ReceiveScreen(p: ReceiveScreenProps) {
  const { stage, words } = p;
  const the = theOf(words);
  const [manual, setManual] = useState(false);
  const bt = (ember: boolean, label = 'Connect over Bluetooth') => (
    <button type="button" className={`btn ${ember && p.bluetoothAvailable ? 'ember' : 'ghost'}`} disabled={!p.bluetoothAvailable} onClick={() => p.onConnectBluetooth()}>{label}</button>
  );

  if (stage === 'error') {
    return (
      <Problem title="The copy didn’t finish" safe={`${capital(the)}’s system hasn’t changed.`}
        fix={<><p>Try again when you’re ready. Or download it by hand:</p><ByHand p={p} onPickFile={p.onPickFile} /></>}
        details={(p.errorMessage || p.log) && <>{p.errorMessage && <pre className="raw">{p.errorMessage}</pre>}{p.log}</>}>
        <Foot>
          <Cancel onCancel={p.onCancel} />
          {p.onRetry && <button type="button" className="btn ember" onClick={p.onRetry}>Try again</button>}
        </Foot>
      </Problem>
    );
  }

  if (stage === 'unverified') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Kiln can’t confirm where this came from</h2>
        <p className="lede">{`It names ${the}’s serial and arrived whole, but it isn’t signed by the setup helper this browser started, so Kiln can’t confirm who sent it. That happens when the install began in another browser, or this browser’s data was cleared since.`}</p>
        <Note tone="turn" title="Go on only if you know it came from your own device">{`${capital(the)}’s system hasn’t changed, and Kiln saves a restore point before it changes anything.`}</Note>
        <Foot back={p.onRetry ? { run: p.onRetry } : undefined}>
          {p.onContinueUnverified && <button type="button" className="btn ember" onClick={p.onContinueUnverified}>Continue anyway</button>}
        </Foot>
        {p.log}
      </section>
    );
  }

  if (stage === 'lna-denied') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">The browser blocked the local network</h2>
        <p className="lede">{`Chrome asked whether Kiln may reach devices on your network, and it was blocked. Bluetooth works without it and doesn’t need Wi-Fi, or you can allow it and try again.`}</p>
        {!p.bluetoothAvailable && <NoBluetooth />}
        <ByHand p={p} onPickFile={p.onPickFile} />
        <Foot>
          <span className="links">
            {p.onCancel && <button type="button" className="btn ghost" onClick={p.onCancel}>Stop</button>}
            {p.onRetryLan && <button type="button" className="btn ghost" onClick={p.onRetryLan}>Allow local network</button>}
            {p.agentPageUrl && <a className="btn ghost" href={p.agentPageUrl} target="_blank" rel="noopener noreferrer">Open device page</a>}
          </span>
          {bt(true, 'Continue over Bluetooth')}
        </Foot>
        {p.log}
      </section>
    );
  }

  if (stage === 'file-fallback') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Download it by hand</h2>
        <p className="lede">{`${capital(the)} offers what Kiln needs on its own page. Save it to this computer, then pick it here.`}</p>
        <ByHand p={p} onPickFile={p.onPickFile} />
        <Foot>
          <Cancel onCancel={p.onCancel} />
          {p.bluetoothAvailable && bt(false, 'Use Bluetooth instead')}
        </Foot>
        {p.log}
      </section>
    );
  }

  if (stage === 'verifying') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Checking what arrived</h2>
        <p className="status work" role="status">Making sure every byte arrived as it was sent…</p>
        <div className="bar wait"><i /></div>
        <p className="small">Nothing is written until the check passes.</p>
        {p.log}
      </section>
    );
  }

  if (p.network && stage === 'wifi-unreachable') return <WifiUnreachable {...p} network={p.network} />;
  if (p.network && (stage === 'waiting-boot' || stage === 'ready' || (stage === 'connecting' && p.channel === 'lan'))) return <JoinWifi {...p} network={p.network} />;

  if (stage === 'cache-hit') {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Checking what arrived</h2>
        <p className="status ok" role="status">✓ Matched a saved copy on this computer, so there’s nothing to send</p>
        <div className="bar wait"><i /></div>
        {p.log}
      </section>
    );
  }

  if (stage === 'transferring') {
    const pr = p.progress;
    const frac = pr && pr.totalBytes > 0 ? Math.min(1, pr.receivedBytes / pr.totalBytes) : 0;
    const pct = Math.floor(frac * 100);
    // over the device's own network it is Wi-Fi to the person, not "the local network"
    const ch = p.channel ? (p.channel === 'lan' && p.network ? { ...CHANNELS.lan, label: 'Wi-Fi' } : CHANNELS[p.channel]) : null;
    const left = etaWords(pr?.etaSeconds ?? null);
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">{`Copying from ${the}`}</h2>
        <p className="lede">{`Kiln copies the parts of ${the}’s system that the install will change, so it can check them and keep a restore point.`}</p>
        {p.fallbackReason && <Note tone="info">{p.fallbackReason}</Note>}
        <div className="xfer">
          <div className="xfer-head">
            <span className="xfer-pct">{`${pct}%`}</span>
            <span className="xfer-left">{left ?? (ch?.typical ? capital(ch.typical) : '')}</span>
          </div>
          <div className="meter" role="progressbar" aria-label={`Copying from ${the}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
            <i style={{ width: `${Math.max(2, frac * 100)}%` }} />
          </div>
          <div className="xfer-foot">
            {ch && <span className="xfer-ch"><span className="dot" />{ch.label}</span>}
            {pr && <span className="small">{`${fmtMb(pr.receivedBytes)} of ${fmtMb(pr.totalBytes)} · ${fmtSpeed(pr.bytesPerSecond)}`}</span>}
          </div>
        </div>
        {(pr?.resendPass ?? 0) > 0 && <p className="status work" role="status">Checking missing pieces…</p>}
        {p.channel === 'ble' && <Hands the={the}>{`Bluetooth is slow, and that’s normal. Keep this computer near ${the} and this tab open.`}</Hands>}
        {manual && <ByHand p={p} onPickFile={p.onPickFile} />}
        <Foot>
          <Cancel onCancel={p.onCancel} />
          {p.agentPageUrl && !manual && <button type="button" className="btn ghost" onClick={() => setManual(true)}>Use manual download instead</button>}
        </Foot>
        {p.log}
      </section>
    );
  }

  // waiting-boot, ready and connecting: the device is (re)starting into its setup helper, or waiting for a connection
  const waiting = stage === 'waiting-boot';
  const connecting = stage === 'connecting';
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{words.title ?? (waiting ? `Waiting for ${the} to restart` : `Connect to ${the}`)}</h2>
      {waiting
        ? <p className="lede">{words.bootHint ?? `${capital(the)} is restarting into its setup helper. The first start can take a few minutes, so leave it plugged in. When it’s ready, connect over Bluetooth.`}</p>
        : <p className="lede">{`${capital(the)} is ready. Kiln copies the parts of its system that the install changes, over your local network when it can and over Bluetooth when it can’t.`}</p>}
      {waiting && <Wait what={`${capital(the)} is restarting`} typical={words.agentTime} note="Its screen may stay dark meanwhile. That’s normal." />}
      {words.coverNote && <Note tone="turn" title="Keep the back cover off">{words.coverNote}</Note>}
      {connecting && <><p className="status work" role="status">Connecting…</p><div className="bar wait"><i /></div></>}
      {!connecting && !waiting && p.bluetoothAvailable && <BrowserAsk short={the} the={the}>{`It shows nearby Bluetooth devices. Pick ${the}, then Pair.`}</BrowserAsk>}
      {!p.bluetoothAvailable && <><NoBluetooth /><ByHand p={p} onPickFile={p.onPickFile} /></>}
      {p.bluetoothAvailable && manual && <ByHand p={p} onPickFile={p.onPickFile} />}
      <Foot>
        <span className="links">
          {p.onCancel && <button type="button" className="btn ghost" onClick={p.onCancel}>Stop</button>}
          {/* Bluetooth can be there and still find nothing (the device's radio off, the browser not allowed to scan) */}
          {!waiting && p.bluetoothAvailable && !manual && <button type="button" className="btn ghost" onClick={() => setManual(true)}>Download it by hand instead</button>}
        </span>
        {!connecting && bt(true)}
      </Foot>
      {p.log}
    </section>
  );
}

/** What an in-place install changes. `cover`: with the cover note, between the two fastboot sessions only (it asks for VOL− once more). */
export function InPlaceNotice({ words, cover = false }: { words: InPlaceWords; cover?: boolean }) {
  return (
    <Note tone="info" title="What changes">
      {words.changes ?? 'The app data starts fresh, while the original system stays in place, so you can go back to it whenever you like.'}
      {cover && words.coverNote && <span className="note-more">{words.coverNote}</span>}
    </Note>
  );
}

export interface RestorePointScreenProps {
  status: 'saving' | 'verifying' | 'ready' | 'error';
  /** The restore point's SHA-256, as read back. */
  verifiedDigest: string | null;
  /** The person confirmed the copy is saved on this computer. */
  downloaded: boolean;
  /** The copy was offered as a download this time: the person can now confirm it's saved. */
  offered?: boolean;
  restoreFilename: string;
  errorMessage: string | null;
  words: InPlaceWords;
  onDownload(): void;
  /** The person says the copy is saved: a browser can't tell the page whether a download landed. */
  onConfirmCopy?: () => void;
  onContinue(): void;
  onRetry?: () => void;
  /** Why the copy didn't download, when it didn't. */
  downloadError?: string | null;
  /** The technical log, last inside the card. */
  log?: ReactNode;
}

/**
 * The restore point: saved in this browser and read back before anything is written, then a copy for this computer.
 * Continue waits for that copy: until it's downloaded, downloading it is the screen's one ember action, and one line by
 * Continue says what it waits for. Once it is (in this run, or before a reload), Continue is the ember button.
 */
export function RestorePointScreen(p: RestorePointScreenProps) {
  const { status, words } = p;
  const the = theOf(words);
  if (status === 'error') {
    return (
      <Problem title="The restore point couldn’t be saved" safe={`This browser couldn’t store it, so ${the}’s system hasn’t changed.`}
        fix="Free some space on this computer, or allow storage for this site, then try again."
        details={(p.errorMessage || p.log) && <>{p.errorMessage && <pre className="raw">{p.errorMessage}</pre>}{p.log}</>}>
        <Foot>
          {p.onRetry && <button type="button" className="btn ember" onClick={p.onRetry}>Try again</button>}
        </Foot>
      </Problem>
    );
  }
  const ready = status === 'ready';
  const go = ready && p.downloaded;
  // what Continue waits for, in one line beside it
  const wait = !ready ? 'Continue opens once the restore point is saved and checked.'
    : !p.downloaded ? (p.offered ? 'Continue opens once you confirm the copy is saved.' : 'Continue opens once a copy is on this computer.') : undefined;
  const digest = p.verifiedDigest;
  const rows = rowsAt([
    { id: 'save', label: 'Save it in this browser' },
    { id: 'check', label: 'Read it back to check it' },
  ], status === 'saving' ? 0 : status === 'verifying' ? 1 : 2);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{words.restoreTitle ?? 'Save a restore point'}</h2>
      <p className="lede">{words.restoreLede ?? `Kiln keeps a copy of what ${the} runs today, so you can go back to it at any time.`}</p>
      {!ready && <Progress rows={rows} label="Saving the restore point" />}
      {ready && <Note tone="ok" title="Saved in this browser" role="status">
        {'Read back and checked.'}{digest && <> Fingerprint <code title={digest}>{digest.slice(0, 12)}</code></>}
      </Note>}
      {ready && (
        <div className={`keep${p.downloaded ? ' kept' : ''}`}>
          <span className="ico"><DownloadGlyph /></span>
          <span className="grow">
            <b>{p.downloaded ? 'A copy is on this computer' : 'Keep a copy on this computer too'}</b>
            <span>{p.downloaded ? 'Keep it until you’re sure you won’t go back. The way back can open it.' : 'In case this browser’s storage is cleared. It downloads as a .zip, which the way back can open.'}</span>
            {p.downloadError && <span className="status err" role="alert">{`The copy didn’t download: ${p.downloadError}`}</span>}
            {p.offered && !p.downloaded && !p.downloadError && (
              <label className="check keep-confirm">
                <input type="checkbox" checked={false} onChange={() => p.onConfirmCopy?.()} />
                <span>I have the .zip saved on this computer</span>
              </label>
            )}
          </span>
          <button type="button" className={`btn ${p.downloaded || p.offered ? 'ghost' : 'ember'}`} onClick={() => p.onDownload()}>{p.downloaded ? 'Downloaded ✓' : p.offered ? 'Download again' : 'Download a copy'}</button>
        </div>
      )}
      <InPlaceNotice words={words} />
      <Foot>
        {wait && <span className="small foot-why" id="continue-why">{wait}</span>}
        <button type="button" className={`btn ${go ? 'ember' : 'ghost'}`} disabled={!go} aria-describedby={wait ? 'continue-why' : undefined} onClick={() => { if (go) p.onContinue(); }}>Continue</button>
      </Foot>
      {p.log}
    </section>
  );
}
