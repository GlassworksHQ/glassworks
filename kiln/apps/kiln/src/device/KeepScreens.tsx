'use client';
// Keeping the device's Home Assistant device over a reflash (issue 580, design approved in PR 590): the step
// before the flash that reads Home Assistant's ESPHome key from the running app over adb, the way on
// when Kiln can't reach it, and what Done says. Same design as the rest of Kiln (docs/design/kiln).
// The key never reaches these screens: ImageState holds its 4-hex fingerprint at most.
import { Foot } from '../components/Nav';
import { cap, pickInList, type DeviceFrame } from '@glassworks/kiln-core';
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
        {rich(`In Settings → Devices & services, ${d.the} is under **Attention required**. Click **Reconfigure**, then **Submit**. It stays the same device.`)}
      </span>
    </div>
  );
}

function Fingerprint({ fingerprint }: { fingerprint: string }) {
  return (
    <div className="well">
      <div className="grow">
        <span className="small">Home Assistant&apos;s key</span>
        <span>Fingerprint <code>{fingerprint}</code></span>
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
      <h2 id="h">{`Keep ${d.the} in Home Assistant`}</h2>
      <p className="lede">Kiln can read your existing connection key first so Home Assistant recognizes {d.the} as the same device after reinstalling.</p>
      {reading ? (
        <ol className="join" aria-live="polite">
          {READ_ROWS.map((r, i) => <li key={r} className={i < at! ? 'done' : i === at ? 'cur' : ''}>{r}</li>)}
        </ol>
      ) : (
        <ol className="join">
          {lines(d.words['keep.steps']).map((x) => <li key={x}>{x}</li>)}
        </ol>
      )}
      {!reading && <p className="small">{`Then click Connect and ${pickInList(d)}.`}</p>}
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <Foot busy={reading || s.connecting}>
        <button type="button" className="btn ghost" onClick={() => f.skipKeep()}>First-time install? Enter fastboot manually</button>
        <button type="button" className="btn ember" disabled={reading || s.connecting} onClick={() => void f.readKey()}>{reading ? 'Reading…' : 'Connect'}</button>
      </Foot>
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
      <h2 id="h">{`${cap(d.the)} is restarting into fastboot`}</h2>
      <p className="lede">
        {`Click Connect and ${pickInList(d)}.`}
        {k.state === 'none' ? ' Home Assistant hasn’t added it yet, so there’s nothing to keep.' : k.state === 'unreachable' ? ' After the install, Home Assistant asks once to keep the device.' : ''}
      </p>
      {k.state === 'held' && <Fingerprint fingerprint={k.fingerprint} />}
      {k.state === 'held' && <p className="small">Keep this tab open until Done, or Home Assistant asks once after the install.</p>}
      {s.connecting && <p className="status work" role="status">{`${cap(pickInList(d))}…`}</p>}
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <Foot busy={s.connecting}>
        <button type="button" className="btn ghost" onClick={() => f.skipKeep()}>Still showing Glassworks? Enter fastboot manually</button>
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => void f.connectFastboot()}>Connect</button>
      </Foot>
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
      <h2 id="h">{old ? 'This version of Glassworks can\'t transfer its key' : `Kiln can't reach Glassworks on this ${d.short}`}</h2>
      <p className="lede">You can still reinstall cleanly — Home Assistant will just ask you to confirm the device once afterward.</p>
      <p className="small">
        {old
          ? <>Glassworks {k.app} predates automatic key transfer.</>
          : <>Once Glassworks is running, only the browser that installed it can connect over USB.</>}
      </p>
      <HaAsksOnce d={d} />
      {s.connectError && <p className="status err" role="alert">{s.connectError}</p>}
      <Foot busy={s.connecting}>
        {old
          ? <button type="button" className="btn ghost" onClick={() => f.skipKeep()}>Enter fastboot manually</button>
          : <button type="button" className="btn ghost" onClick={() => f.toKeep()}>Try again</button>}
        <button type="button" className="btn ember" disabled={s.connecting} onClick={() => (old ? void f.restartOld() : f.skipKeep())}>{old ? 'Restart into fastboot' : 'Continue with install'}</button>
      </Foot>
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
    k.state === 'handed' ? ['Same device', 'y', 'Glassworks restored your Home Assistant connection key, so it stays the same device.']
    : k.state === 'none' ? ['Not added yet', 'n', 'Add it in Settings → Devices & services, under Discovered.']
    : ['Confirm once', 'w', k.state === 'refused' ? 'Glassworks couldn\'t restore the saved connection key.' : 'Kiln couldn\'t read the existing connection key before reinstalling.'];
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
