// The setup steps that run over the app's setup channel, on the Kiln design: Home Assistant, the radio
// role and what to do next in Home Assistant. Kiln's wizard shows them over USB, the panel's own page over
// the LAN (#34); `steps` is the wizard's Session or the page's controller.
import * as Collapsible from '@radix-ui/react-collapsible';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useState } from 'react';
import type { RadioStep, Setup } from '../wizard/state';
import type { SetupSteps } from './channelSetup';

/** A step's props: a button's work is running (`busy`), what runs it, and the step's state. */
export type StepProps<K extends Setup['phase']> = { busy: boolean; steps: SetupSteps; setup: Extract<Setup, { phase: K }> };

/** 6 · Home Assistant: the address it found, and sign-in on the panel's own page (no token in this browser). */
export function HomeAssistant({ busy, steps, setup }: StepProps<'ha'>) {
  const suggested = setup.ha.url || setup.ha.discovered || 'http://homeassistant.local:8123';
  // kept in the session as it is typed, so another set-up step and back doesn't lose it
  const [typed, setTyped] = useState<string | undefined>(() => steps.haAddress);
  const url = typed ?? suggested; // follows what the panel finds until the person types
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{setup.ha.signedIn ? 'Signed in to Home Assistant.' : 'Sign in to Home Assistant.'}</h2>
      <p className="lede">{setup.ha.discovered ? `Found at ${new URL(setup.ha.discovered).host}.` : 'Not found by itself. Type its address.'}</p>
      <label className="field"><span>Address</span><input className="input" value={url} onChange={(e) => { setTyped(e.target.value); steps.haAddress = e.target.value; }} inputMode="url" autoComplete="off" /></label>
      {!setup.ha.signedIn && <button type="button" className="btn ghost" style={{ justifySelf: 'start' }} disabled={busy} onClick={() => void steps.click('sign-in', () => steps.signIn(url.trim()))}>Sign in</button>}
      {setup.ha.signedIn && <p className="status ok">The panel is signed in.</p>}
      {setup.note && !setup.ha.signedIn && <p className="status" role="status">{setup.note}</p>}
      {setup.error && <p className="status err" role="alert">{setup.error}</p>}
      <div className="foot">
        <button type="button" className="link" disabled={busy} onClick={() => void steps.click('radio', () => steps.radio())}>Skip, do it on the panel</button>
        <button type="button" className="btn ember" disabled={setup.busy || busy} onClick={() => void steps.click('save-ha', () => steps.saveHa(url.trim()))}>Continue</button>
      </div>
    </section>
  );
}

/** The radio pane's one choice: what the Zigbee/Thread radio does. */
export function Radio({ busy, steps, setup }: StepProps<'radio'>) {
  const [mode, setMode] = useState(setup.radio.mode);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">What should the radio do?</h2>
      <p className="lede">The panel has a Zigbee and Thread radio. Leave it off, or let Home Assistant use it.</p>
      <RadioGroup.Root className="list" value={mode} onValueChange={setMode} aria-label="Radio role" disabled={setup.busy}>
        {setup.radio.options.map((o) => (
          <RadioGroup.Item key={o.id} value={o.id} className="opt" disabled={!setup.radio.helper && o.id !== 'off'}>
            <span className="rd" />
            <span className="t"><span>{o.label}{o.experimental && <em className="tag">Experimental</em>}</span><small>{o.detail}</small></span>
          </RadioGroup.Item>
        ))}
      </RadioGroup.Root>
      <p className={setup.error ? 'status err' : 'status'} role="status">{setup.error ?? setup.note}</p>
      <div className="foot">
        <button type="button" className="link" disabled={setup.busy || busy} onClick={() => void steps.click('finish', () => steps.finish())}>Skip, leave it as it is</button>
        <button type="button" className="btn ember" disabled={setup.busy || busy} onClick={() => void steps.click('radio', () => steps.applyRadio(mode))}>Finish</button>
      </div>
    </section>
  );
}

function Copy({ value }: { value: string }) {
  const [t, setT] = useState('Copy');
  return <button type="button" className="link" onClick={async () => {
    try { await navigator.clipboard.writeText(value); setT('Copied'); } catch { setT('Select it'); }
    setTimeout(() => setT('Copy'), 1600);
  }}>{t}</button>;
}

function PlanStep({ st }: { st: RadioStep }) {
  return (
    <li style={{ display: 'grid', gap: 6, border: '1px solid var(--edge)', borderRadius: 14, padding: '12px 14px' }}>
      <b style={{ fontWeight: 500 }}>{st.title}</b>
      <span className="small" style={{ color: 'var(--muted)' }}>{st.body}</span>
      {st.kv && <dl className="kv">{st.kv.map(([k, v, copy]) => <span key={k} style={{ display: 'contents' }}><dt>{k}</dt><dd>{copy ? <><code>{v}</code><Copy value={v} /></> : v}</dd></span>)}</dl>}
      {st.link && (st.primary
        ? <a className="btn ember" style={{ justifySelf: 'start' }} href={st.link} target="_blank" rel="noopener">{st.primary}</a>
        : <a className="link" href={st.link} target="_blank" rel="noopener">Open in Home Assistant</a>)}
      {st.how && (
        <div className="how">
          <b>{st.how.title}</b>
          <span className="small">{st.how.body}</span>
          <a className="link" href={st.how.link} target="_blank" rel="noopener">{st.how.linkText}</a>
        </div>
      )}
    </li>
  );
}

export function RadioNext({ steps, setup }: StepProps<'radio-next'>) {
  const { plan } = setup;
  const led = plan.steps.some((st) => st.primary);
  const [more, setMore] = useState(false);
  const list = { listStyle: 'none', margin: 0, padding: 0 } as const;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{plan.title}</h2>
      <p className="lede">{plan.lead}</p>
      <ol className="next" style={list}>{plan.steps.map((st) => <PlanStep key={st.title} st={st} />)}</ol>
      {plan.more && (
        <Collapsible.Root open={more} onOpenChange={setMore} className="tech">
          <Collapsible.Trigger className="link" style={{ justifySelf: 'start' }}>{more ? 'Less' : 'More help'}</Collapsible.Trigger>
          <Collapsible.Content><ol className="next" style={list}>{plan.more.map((st) => <PlanStep key={st.title} st={st} />)}</ol></Collapsible.Content>
        </Collapsible.Root>
      )}
      <div className="foot"><span /><button type="button" className={`btn ${led ? 'ghost' : 'ember'}`} onClick={() => steps.finish()}>Finish</button></div>
    </section>
  );
}
