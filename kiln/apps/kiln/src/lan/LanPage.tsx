// The panel's own setup page (#34): the frame, rail and cards of Kiln's wizard, with pairing first and
// Kiln's setup steps (src/setup/steps.tsx) after it. It wears the panel's look, not Kiln's: the Glassworks
// lockup and stack here, the panel's colours in lan/lan.css.
import lockup from '@glassworks/brand/lockup-on-dark.svg?raw';
import { useState, useSyncExternalStore } from 'react';
import { HomeAssistant, Radio, RadioNext, Wifi } from '../setup/steps';
import type { LanSession, LanView, RailStep } from './lanSession';
import { PanelStack, type PlateLook } from './PanelStack';

const LOCKUP = `data:image/svg+xml,${encodeURIComponent(lockup)}`;

const NAMES: Record<RailStep, string> = { pair: 'Pair with the panel', wifi: 'Wi-Fi', ha: 'Home Assistant', radio: 'Radio', done: 'Done' };

function stepOf(v: LanView): RailStep {
  if (v.at === 'done') return 'done';
  if (v.at !== 'setup') return 'pair';
  if (v.setup.phase === 'wifi') return 'wifi';
  return v.setup.phase === 'radio' || v.setup.phase === 'radio-next' ? 'radio' : 'ha';
}

/**
 * The stack as the panel's setup screen draws it: the plates set one per step, none on the first and all
 * three on the last; done lights the top plate. A lost panel shows the next plate in rose.
 */
function stackLooks(v: LanView, at: number, steps: number): [PlateLook, PlateLook, PlateLook] {
  if (v.at === 'done') return ['lit', 'outline', 'outline'];
  const set = Math.floor((3 * at) / (steps - 1));
  const looks = [0, 1, 2].map((i): PlateLook => (2 - i < set ? 'outline' : 'ghost')) as [PlateLook, PlateLook, PlateLook];
  if (v.at === 'lost') looks[Math.max(0, 2 - set)] = 'bad';
  return looks;
}

function Pair({ lan, v, busy }: { lan: LanSession; v: Extract<LanView, { at: 'pair' }>; busy: boolean }) {
  const [code, setCode] = useState('');
  if (!v.open) {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Pairing is off.</h2>
        <p className="lede">Home Assistant has added this panel, so it pairs with a browser only when you allow it. Press <b>Allow setup over the network</b>: on the panel&apos;s device page in Home Assistant, or on the panel in its settings, under Home Assistant &amp; dashboard. It stays allowed for 30 minutes.</p>
        {v.error && <p className="small">{v.error}</p>}
        <div className="foot"><span /><button type="button" className="btn ember" disabled={busy} onClick={() => void lan.click('check', () => lan.start())}>Check again</button></div>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Pair with the panel.</h2>
      <p className="lede">{v.note ?? 'The panel shows a 6-digit code on its screen. Typing it here shows you can see the panel, so nobody else on the network can set it up.'}</p>
      {v.shown && (
        <label className="field"><span>Code on the panel</span>
          <input className="input" value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={7} autoFocus
            onKeyDown={(e) => { if (e.key === 'Enter') void lan.click('pair', () => lan.enterCode(code)); }} />
        </label>
      )}
      {v.error && <p className="status err" role="alert">{v.error}</p>}
      <div className="foot">
        {v.shown ? <button type="button" className="link" disabled={busy} onClick={() => void lan.click('code', () => lan.showCode())}>Show a new code</button> : <span />}
        {v.shown
          ? <button type="button" className="btn ember" disabled={busy || code.replace(/\D/g, '').length !== 6} onClick={() => void lan.click('pair', () => lan.enterCode(code))}>Pair</button>
          : <button type="button" className="btn ember" disabled={busy} onClick={() => void lan.click('code', () => lan.showCode())}>Show the code on the panel</button>}
      </div>
    </section>
  );
}

function Screen({ lan, v, busy }: { lan: LanSession; v: LanView; busy: boolean }) {
  switch (v.at) {
    case 'connecting':
      return <section className="card" aria-labelledby="h"><h2 id="h">Connecting to the panel.</h2><div className="bar wait"><i /></div></section>;
    case 'pair': return <Pair lan={lan} v={v} busy={busy} />;
    case 'lost':
      return (
        <section className="card err" aria-labelledby="h">
          <h2 id="h">{v.again === 'done' ? 'Almost done.' : 'The panel stopped answering.'}</h2>
          <p className="lede">{v.error}</p>
          <div className="foot"><span /><button type="button" className="btn ember" disabled={busy} onClick={() => void lan.click('retry', () => lan.retry())}>Try again</button></div>
        </section>
      );
    case 'done':
      return (
        <section className="card" aria-labelledby="h">
          <h2 id="h">Done.</h2>
          <p className="lede">The panel is set up, and this browser is no longer paired with it. If Home Assistant hasn&apos;t added the panel yet, it finds it by itself as an ESPHome device. You can close this page.</p>
        </section>
      );
    case 'setup': {
      const s = v.setup;
      switch (s.phase) {
        case 'wifi': return <Wifi busy={busy} steps={lan} setup={s} />;
        case 'ha': return <HomeAssistant key="ha" busy={busy} steps={lan} setup={s} />;
        case 'radio': return <Radio key={s.radio.mode} busy={busy} steps={lan} setup={s} />;
        case 'radio-next': return <RadioNext busy={busy} steps={lan} setup={s} />;
        default:
          return <section className="card" aria-labelledby="h"><h2 id="h">Connecting to the panel.</h2><div className="bar wait"><i /></div></section>;
      }
    }
  }
}

export function LanPage({ lan }: { lan: LanSession }) {
  const st = useSyncExternalStore(lan.subscribe, lan.get, lan.get);
  const v = st.view;
  const rail = st.rail;
  const at = v.at === 'done' ? rail.length - 1 : Math.max(0, rail.indexOf(stepOf(v)));
  const paired = v.at === 'setup' || v.at === 'done';
  const looks = stackLooks(v, at, rail.length);
  return (
    <div className="app">
      <header className="top">
        <span className="brand"><img className="lockup" src={LOCKUP} alt="Glassworks" /></span>
        <span className="narrow small">{v.at === 'done' ? 'Done' : `${at + 1} of ${rail.length}`}</span>
        <span className={`chip ${paired ? 'ok' : 'work'}`}><span className="dot" />{v.at === 'done' ? 'Set up' : paired ? 'Paired' : 'Not paired'}</span>
      </header>
      <div className="body">
        <nav className="rail" aria-label="Steps">
          <ol className="steps">
            {rail.map((step, i) => {
              const mark = v.at === 'done' || i < at ? 'done' : i > at ? 'todo' : v.at === 'lost' ? 'bad' : 'now';
              return <li key={step} className={mark} aria-current={mark === 'now' ? 'step' : undefined}><span className="mk" /><span>{NAMES[step]}</span></li>;
            })}
          </ol>
          <div className="rail-stack"><PanelStack looks={looks} width={120} /></div>
        </nav>
        <div className="phone-stack"><PanelStack looks={looks} width={88} /></div>
        <main className="main"><Screen lan={lan} v={v} busy={!!st.busy} /></main>
      </div>
    </div>
  );
}
