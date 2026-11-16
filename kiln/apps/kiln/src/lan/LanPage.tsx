// The panel's own setup page (#34), on Kiln's design: the same frame, rail and cards as the wizard, with
// pairing first and Kiln's setup steps (src/setup/steps.tsx) after it.
import { useState, useSyncExternalStore } from 'react';
import { Mark } from '../components/Mark';
import { Stack } from '../components/Stack';
import { HomeAssistant, Radio, RadioNext } from '../setup/steps';
import type { LanSession, LanView } from './lanSession';

const RAIL = ['Pair with the panel', 'Home Assistant', 'Radio', 'Done'] as const;

function railAt(v: LanView): number {
  if (v.at === 'done') return 4;
  if (v.at !== 'setup') return 0;
  return v.setup.phase === 'radio' || v.setup.phase === 'radio-next' ? 2 : 1;
}

function Pair({ lan, v, busy }: { lan: LanSession; v: Extract<LanView, { at: 'pair' }>; busy: boolean }) {
  const [code, setCode] = useState('');
  if (!v.open) {
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">Pairing is off.</h2>
        <p className="lede">Home Assistant has added this panel, so it pairs with a browser only when you allow it. In Home Assistant, open the panel&apos;s device page and press <b>Allow setup over the network</b>. It stays allowed for 30 minutes.</p>
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
          <h2 id="h">The panel stopped answering.</h2>
          <p className="lede">{v.error}</p>
          <div className="foot"><span /><button type="button" className="btn ember" disabled={busy} onClick={() => void lan.click('retry', () => lan.start())}>Try again</button></div>
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
  const at = railAt(v);
  const paired = v.at === 'setup' || v.at === 'done';
  return (
    <div className="app">
      <header className="top">
        <span className="brand"><Mark /><span className="wide">Glassworks <b>Kiln</b></span><span className="narrow">Kiln</span></span>
        <span className="narrow small">{at >= RAIL.length ? 'Done' : `${at + 1} of ${RAIL.length}`}</span>
        <span className={`chip ${paired ? 'ok' : 'work'}`}><span className="dot" />{v.at === 'done' ? 'Set up' : paired ? 'Paired' : 'Not paired'}</span>
      </header>
      <div className="body">
        <nav className="rail" aria-label="Steps">
          <ol className="steps">
            {RAIL.map((name, i) => {
              const mark = i < at ? 'done' : i > at ? 'todo' : v.at === 'lost' ? 'bad' : 'now';
              return <li key={name} className={mark} aria-current={mark === 'now' ? 'step' : undefined}><span className="mk" /><span>{name}</span></li>;
            })}
          </ol>
          <div className="rail-stack"><Stack level={v.at === 'done' ? 4 : 3} error={v.at === 'lost'} /></div>
        </nav>
        <div className="phone-stack"><Stack level={v.at === 'done' ? 4 : 3} error={v.at === 'lost'} width={150} /></div>
        <main className="main"><Screen lan={lan} v={v} busy={!!st.busy} /></main>
      </div>
    </div>
  );
}
