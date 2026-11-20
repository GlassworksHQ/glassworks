'use client';
// Keeping the device's Home Assistant device over a reflash (issue 580, design approved in PR 590): the step
// before the flash that reads Home Assistant's ESPHome key from the running app over adb, the way on
// when Kiln can't reach it, and what Done says. Same design as the rest of Kiln (docs/design/kiln).
// The key never reaches these screens: ImageState holds its 4-hex fingerprint at most.
import { cap, type DeviceFrame } from '@glassworks/kiln-core';
import type { ImageSession, ImageState, KeyStep } from './imageSession';
import { lines, rich } from './rich';
import '../components/SilentUpdates.css';

type P = { f: ImageSession; s: ImageState };

/** The key went into this tab and still has to go back (Kiln warns before the tab closes). */
export const keyHeld = (k?: KeyStep) => k?.state === 'held' || k?.state === 'handed' || k?.state === 'refused';

/** What Home Assistant asks after a reflash that couldn't keep its key: one confirmation, the same device. */
export function HaAsksOnce({ d }: { d: DeviceFrame }) {
  return (
    <div className="how">
      <b>In Home Assistant, after the install</b>
      <span className="small">
        {rich(`Open Settings, then Devices & services. ${cap(d.the)} is under **Attention required**: Home Assistant says it “disabled transport encryption”. Click **Reconfigure**, then **Submit**. Home Assistant keeps the same device, with its entities and their history.`)}
      </span>
    </div>
  );
}

function Fingerprint({ fingerprint }: { fingerprint: string }) {
  return (
    <div className="well">
      <div className="grow">
        <span className="small">Home Assistant&apos;s key</span>
        <span>Fingerprint <code>{fingerprint}</code>, as Glassworks logs it. Kiln holds the key in this tab only: it never saves it, shows it or logs it.</span>
      </div>
    </div>
  );
}

const readRows = (d: DeviceFrame) => ['Glassworks answers over USB', 'Read the key Home Assistant set', `Restart ${d.the} into fastboot`];

function Connect({ f, s }: P) {
  const d = f.frame;
  const READ_ROWS = readRows(d);
  const at = s.key?.state === 'reading' ? s.key.at : undefined;
  const reading = at !== undefined && !s.connecting;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{`Keep ${d.the} in Home Assistant.`}</h2>
      <p className="lede">
        {`Glassworks on this ${d.short} has a key from Home Assistant. Kiln reads it now and gives it back after the install, so Home Assistant keeps the same device with no click.`}
      </p>
      {reading ? (
        <ol className="join" aria-live="polite">
          {READ_ROWS.map((r, i) => <li key={r} className={i < at! ? 'done' : i === at ? 'cur' : ''}>{r}</li>)}
        </ol>
      ) : (
        <ol className="join">
          {lines(d.words['keep.steps']).map((x) => <li key={x}>{x}</li>)}
        </ol>
      )}
      {!reading && <p className="small">{rich(`Then click Connect and pick ${d.the} (\`Android\`) in the browser's list. Kiln only reads it, then restarts it into fastboot itself. Nothing is written yet.`)}</p>}
      {s.connecting && <p className="status work" role="status">{`Pick ${d.the} in the browser's list…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.skipKeep()}>It doesn&apos;t run Glassworks: fastboot by hand</button></span>
        <button type="button" className="btn ember" disabled={reading || s.connecting} onClick={() => void f.readKey()}>{reading ? 'Reading…' : 'Connect'}</button>
      </div>
    </section>
  );
}

/**
 * The key is read (or there was none, or an old app can't give it), and Kiln restarted the device into fastboot: one
 * more pick in the browser's list.
 */
function Restarted({ f, s }: P) {
  const d = f.frame;
  const k = s.key as Extract<KeyStep, { state: 'held' } | { state: 'none' } | { state: 'unreachable' }>;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{k.state === 'held' ? 'Kiln has the key.' : k.state === 'none' ? 'Nothing to keep.' : `${cap(d.the)} is restarting into fastboot.`}</h2>
      <p className="lede">
        {k.state === 'held'
          ? `${cap(d.the)} is restarting into fastboot, so its screen goes black. Click Connect and pick it in the browser’s list.`
          : k.state === 'none'
            ? `Glassworks on this ${d.short} has no key from Home Assistant yet, so there’s no device to keep. ${cap(d.the)} is restarting into fastboot: click Connect and pick it in the browser’s list.`
            : 'Its screen goes black. Click Connect and pick it in the browser’s list. After the install, Home Assistant asks you once to keep the device.'}
      </p>
      {k.state === 'held' && <Fingerprint fingerprint={k.fingerprint} />}
      {k.state === 'held' && <p className="small">Keep this tab open until Done. If it closes before then, the key is gone, and Home Assistant asks once after the install.</p>}
      {s.connecting && <p className="status work" role="status">{rich(`Pick ${d.the} (\`Android\`) in the browser's list…`)}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <p className="small">{`Fastboot only reads ${d.the} here.`}</p>
      <div className="foot">
        <span className="links"><button type="button" className="link" onClick={() => f.skipKeep()}>Still on Glassworks? Fastboot by hand</button></span>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.connectFastboot()}>Connect</button>
      </div>
    </section>
  );
}

/** Kiln can't read the key: the reflash still works, and Home Assistant asks once. */
function Unreachable({ f, s }: P) {
  const d = f.frame;
  const k = s.key as Extract<KeyStep, { state: 'unreachable' }>;
  const old = k.why === 'old-app';
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{old ? 'This Glassworks can’t hand its key over.' : `Kiln can’t reach Glassworks on this ${d.short}.`}</h2>
      <p className="lede">You can still reflash it. Afterwards, Home Assistant asks you once to keep the device.</p>
      <p className="small">
        {old
          ? <>Glassworks {k.app}{` on ${d.the} is older than the hand-over, so it has no way to give the key out. Kiln can still restart it into fastboot.`}</>
          : <>{`${cap(d.the)} lets in only the browser that last installed Glassworks on it, and only once Glassworks has started. From another browser or computer, or on a ${d.short} that doesn't start, Kiln can't read the key.`}</>}
      </p>
      <HaAsksOnce d={d} />
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <div className="foot">
        <span className="links">{old
          ? <button type="button" className="link" onClick={() => f.skipKeep()}>Fastboot by hand</button>
          : <button type="button" className="link" onClick={() => f.toKeep()}>Try again</button>}</span>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => (old ? void f.restartOld() : f.skipKeep())}>{old ? 'Restart into fastboot' : 'Reflash anyway'}</button>
      </div>
    </section>
  );
}

export function ImageKeep(p: P) {
  const k = p.s.key;
  if (k?.state === 'held' || k?.state === 'none' || (k?.state === 'unreachable' && k.restarted)) return <Restarted {...p} />;
  if (k?.state === 'unreachable') return <Unreachable {...p} />;
  return <Connect {...p} />;
}

/** Done: whether Home Assistant kept the device, in the Silent updates block's look. Nothing on a first install. */
export function KeyOutcome({ k, d }: { k?: KeyStep; d: DeviceFrame }) {
  if (!k || k.state === 'reading' || k.state === 'held') return null;
  const [word, tone, line] =
    k.state === 'handed' ? ['Same device', 'y', 'Glassworks has the key Home Assistant set before the reflash, so Home Assistant reconnected on its own: the same device, entities and history.']
    : k.state === 'none' ? ['Not added yet', 'n', `Home Assistant hasn’t added this ${d.short}. It finds it on the network: Settings, then Devices & services, under Discovered.`]
    : ['Asks once', 'w', k.state === 'refused'
      ? 'Kiln read the key before the reflash, but Glassworks didn’t take it back at its first start, so Home Assistant asks once.'
      : 'Kiln couldn’t read the key before the reflash, so Home Assistant asks once.'];
  return (
    <>
      <div className="su" role="group" aria-label="Home Assistant">
        <div className="spread"><span>Home Assistant</span><i className={tone}>{word}</i></div>
        <span className="small">{line}</span>
      </div>
      {tone === 'w' && <HaAsksOnce d={d} />}
    </>
  );
}
