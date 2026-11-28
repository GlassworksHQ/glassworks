// The setup steps on the Kiln design: Wi-Fi, Home Assistant, the radio role and what to do next in Home
// Assistant. Kiln's wizard shows them over USB, the panel's own page over the LAN or the panel's setup
// hotspot (#34, issue 197); `steps` is the wizard's Session or the page's controller.
import * as Collapsible from '@radix-ui/react-collapsible';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useEffect, useState } from 'react';
import type { Network, RadioStep, Setup } from '../wizard/state';
import type { SetupSteps, WifiSteps } from './channelSetup';
import { Foot } from '../components/Nav';
import { Note, Progress, rowsAt } from '../components/Kit';

/** A step's props: a button's work is running (`busy`), what runs it, and the step's state. */
export type StepProps<K extends Setup['phase']> = { busy: boolean; steps: SetupSteps; setup: Extract<Setup, { phase: K }> };

function Signal({ rssi }: { rssi: number }) {
  const level = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -78 ? 2 : 1;
  return <span className="sig" aria-label={`signal ${level} of 4`}>{[1, 2, 3, 4].map((i) => <i key={i} style={{ height: i * 3 + 2, opacity: i <= level ? 1 : 0.25 }} />)}</span>;
}
const Lock = () => <svg className="lock" viewBox="0 0 24 24" aria-label="needs a password"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>;

/**
 * Wi-Fi: which network, the password, connect. The list is what the panel can see. Kiln's wizard sends it
 * over USB (Improv Serial); the panel's own page over its setup channel, reached on the panel's setup hotspot
 * (issue 197), where the join ends this page's connection, so the panel's screen gives the outcome.
 */
export function Wifi({ busy, steps, setup }: { busy: boolean; steps: WifiSteps; setup: Extract<Setup, { phase: 'wifi' }> }) {
  const the = steps.the ?? 'the panel';
  const capThe = the.charAt(0).toUpperCase() + the.slice(1);
  const nets: Network[] = setup.networks ?? [];
  const [pick, setPick] = useState('');
  const [other, setOther] = useState('');
  const [psk, setPsk] = useState('');
  // a pick that a rescan no longer lists goes too: Connect must not send a network the panel can't see,
  // nor the dropped network's password to the one that replaces it
  useEffect(() => {
    const nets = setup.networks;
    if (!nets) return;
    if (!pick || (pick !== '\u0000other' && !nets.some((n) => n.ssid === pick))) {
      setPick(nets[0]?.ssid ?? '\u0000other');
      setPsk('');
    }
  }, [setup.networks, pick]);
  const isOther = pick === '\u0000other';
  const chosen = nets.find((n) => n.ssid === pick);
  const secure = isOther || !!chosen?.secure;
  const j = setup.joining;

  // On the panel's own page the join closes the setup Wi-Fi, and this page's connection with it, within moments: no
  // step can follow, so the page says so once and stops (issue 460). The 45 s check can still turn it into an error.
  if (j && setup.over === 'lan' && j.step !== 'err') {
    return (
      <section className="card" aria-labelledby="h">
        {/* One polite region from "Sending…" on, so a screen reader hears the change to the last word (CodeRabbit on PR 470). */}
        <div role="status" aria-live="polite">
          <h2 id="h">{j.step === 'sent' ? `Sending to ${the}…` : `Sent to ${the}`}</h2>
          {setup.joinNote && <p className="lede">{setup.joinNote}</p>}
        </div>
      </section>
    );
  }
  if (j) {
    const order = ['sent', 'join', 'ok'] as const;
    const at = j.step === 'err' ? 1 : order.indexOf(j.step);
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">{j.step === 'ok' ? `Connected to ${j.ssid}` : j.step === 'err' ? `Couldn't join ${j.ssid}` : `Joining ${j.ssid}…`}</h2>
        <Progress label={`Joining ${j.ssid}`} rows={rowsAt([
          { id: 'sent', label: setup.over === 'lan' ? `Send it to ${the}` : `Send it to ${the} over USB` },
          { id: 'join', label: `${capThe} joins the network`, note: 'This can take up to a minute.' },
          { id: 'ok', label: 'Check it’s connected' },
        ], j.step === 'ok' ? 3 : at, j.step === 'err' ? 'bad' : 'now')} />
        {j.error && <Note tone="bad">{j.error}</Note>}
        {setup.joinNote && <p className="lede">{setup.joinNote}</p>}
        <Foot>
          {/* without touch the panel can't take Wi-Fi from its own screen: no skip to it */}
          {j.step === 'err' && steps.touch !== false ? <button type="button" className="btn ghost" disabled={busy} onClick={() => void steps.click('ha', () => steps.homeAssistant())}>{`Skip, do it on ${the}`}</button> : <span />}
          {j.step === 'ok' && <button type="button" className="btn ember" disabled={busy} onClick={() => void steps.click('ha', () => steps.homeAssistant())}>Continue</button>}
          {j.step === 'err' && <button type="button" className="btn ember" disabled={busy} onClick={() => void steps.click('wifi', () => steps.wifi())}>Try again</button>}
        </Foot>
      </section>
    );
  }
  if (setup.on && !setup.changing) {
    const { ssid, ip } = setup.on;
    return (
      <section className="card" aria-labelledby="h">
        <h2 id="h">{ssid ? `Connected to ${ssid}` : 'Connected to Wi-Fi'}</h2>
        <p className="lede">{capThe} is on this network already, at {ip}. Keep it, or use another network.</p>
        <Foot>
          <button type="button" className="btn ghost" disabled={busy} onClick={() => void steps.click('wifi-scan', () => steps.scanWifi())}>Use another network</button>
          <button type="button" className="btn ember" disabled={busy} onClick={() => void steps.click('ha', () => steps.homeAssistant())}>Continue</button>
        </Foot>
      </section>
    );
  }
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Connect to Wi-Fi</h2>
      {setup.hint && <p className="lede">{setup.hint}</p>}
      {setup.networks === undefined && !setup.unreachable ? (
        <><p className="status" role="status">{setup.status}</p><div className="bar wait"><i /></div></>
      ) : setup.unreachable ? (
        <p className="status err" role="alert">{setup.status}{steps.touch !== false ? ` Try again, or set up Wi-Fi directly on ${the}.` : ' Try again.'}</p>
      ) : (
        <>
          <RadioGroup.Root className="list" value={pick} onValueChange={setPick} aria-label="Networks">
            {nets.map((n) => (
              <RadioGroup.Item key={n.ssid} value={n.ssid} className="opt" title={`${n.rssi} dBm`}>
                <span className="rd" /><span className="t">{n.ssid}</span>{n.secure && <Lock />}<Signal rssi={n.rssi} />
              </RadioGroup.Item>
            ))}
            <RadioGroup.Item value={'\u0000other'} className="opt"><span className="rd" /><span className="t">Enter a network manually…</span></RadioGroup.Item>
          </RadioGroup.Root>
          <p className="small">{setup.status}</p>
          {isOther && <label className="field"><span>Network name</span><input className="input" value={other} onChange={(e) => setOther(e.target.value)} autoComplete="off" /></label>}
          {secure && <label className="field"><span>Password</span><input className="input" type="password" value={psk} onChange={(e) => setPsk(e.target.value)} autoComplete="off" /></label>}
        </>
      )}
      <Foot>
        {setup.on || steps.touch !== false ? (
          <button type="button" className="btn ghost" disabled={busy} onClick={() => void steps.click('ha', () => steps.homeAssistant())}>
            {setup.on ? `Keep ${setup.on.ssid ?? 'its Wi-Fi'}` : `Skip, do it on ${the}`}
          </button>
        ) : <span />}
        {setup.unreachable
          ? <button type="button" className="btn ember" disabled={busy} onClick={() => void steps.click('wifi', () => steps.wifi())}>Try again</button>
          : <button type="button" className="btn ember" disabled={!setup.networks || busy} onClick={() => void steps.click('join', () => steps.joinWifi(isOther ? other : pick, secure ? psk : ''))}>Connect</button>}
      </Foot>
    </section>
  );
}

/** 6 · Home Assistant: the address it found, and sign-in on the panel's own page (no token in this browser). */
export function HomeAssistant({ busy, steps, setup }: StepProps<'ha'>) {
  const the = steps.the ?? 'the panel';
  const capThe = the.charAt(0).toUpperCase() + the.slice(1);
  const suggested = setup.ha.url || setup.ha.discovered || 'http://homeassistant.local:8123';
  // kept in the session as it is typed, so another set-up step and back doesn't lose it
  const [typed, setTyped] = useState<string | undefined>(() => steps.haAddress);
  const url = typed ?? suggested; // follows what the panel finds until the person types
  const dashboards = setup.ha.dashboards;
  const showDashboard = setup.ha.signedIn && !!dashboards && dashboards.length > 1;
  const currentDashboard = setup.ha.dashboard ?? dashboards?.[0] ?? 'Default';
  const [pickedDashboard, setPickedDashboard] = useState<string | undefined>(undefined);
  const dashboard = pickedDashboard && dashboards?.includes(pickedDashboard) ? pickedDashboard : currentDashboard;
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">{setup.ha.signedIn ? 'Signed in to Home Assistant' : 'Sign in to Home Assistant'}</h2>
      <p className="lede">{setup.ha.discovered ? `Found Home Assistant at ${new URL(setup.ha.discovered).host}.` : 'We couldn\'t find Home Assistant automatically. Enter its address below.'}</p>
      <label className="field"><span>Address</span><input className="input" value={url} onChange={(e) => { setTyped(e.target.value); steps.haAddress = e.target.value; }} inputMode="url" autoComplete="off" /></label>
      {setup.ha.signedIn && <p className="status ok">{`${capThe} is signed in.`}</p>}
      {showDashboard && (
        <label className="field">
          <span>Dashboard</span>
          <select className="select" value={dashboard} disabled={setup.busy || busy} onChange={(e) => setPickedDashboard(e.target.value)}>
            {dashboards.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
          <span className="small">Leave on Default for your Home Assistant default dashboard.</span>
        </label>
      )}
      {setup.note && !setup.ha.signedIn && <p className="status" role="status">{setup.note}</p>}
      {setup.error && <p className="status err" role="alert">{setup.error}</p>}
      {/* One way on: Sign in until the panel is signed in, Continue after. */}
      {setup.ha.signedIn ? (
        <Foot>
          <span />
          <button type="button" className="btn ember" disabled={setup.busy || busy} onClick={() => void steps.click('save-ha', () => steps.saveHa(url.trim(), showDashboard ? dashboard : undefined))}>Continue</button>
        </Foot>
      ) : (
        <Foot>
          <button type="button" className="btn ghost" disabled={setup.busy || busy} onClick={() => void steps.click('skip-ha', () => steps.skipHa(url.trim()))}>{steps.touch !== false ? `Skip, sign in on ${the}` : 'Skip, sign in later'}</button>
          <button type="button" className="btn ember" disabled={setup.busy || setup.signingIn || busy} onClick={() => void steps.click('sign-in', () => steps.signIn(url.trim()))}>Sign in</button>
        </Foot>
      )}
    </section>
  );
}

/**
 * A pane's icon: an SVG of 24 × 24 strokes in currentColor, from the page's `icons` (Kiln's: the pane catalog, each pane's
 * own icon.svg as glassworks.dev draws it). Never anything the device sent. The panel's own page has none: its bundle
 * goes into the shared app, which names no frame's pane (DeviceNamesTest).
 */
function PaneIcon({ svg }: { svg?: string }) {
  return svg ? <span className="ic" aria-hidden="true" dangerouslySetInnerHTML={{ __html: svg }} /> : null;
}

/**
 * Panes: what the device does, one switch per pane its app offers (the panel's own Panes step, its cards and words;
 * Kiln's sketch, docs/design/kiln: "Panes are switches"). Continue applies them; a pane that is on and has a step of its
 * own (the radio) comes next.
 */
export function Panes({ busy, steps, setup, icons }: StepProps<'panes'> & { icons?: (id: string) => string | undefined }) {
  // the person's switches survive the screen going (pairing again on the panel's page): kept by the steps, for the cards
  // the app still offers; the others start as the app says
  const [on, setOn] = useState<Record<string, boolean>>(() => Object.fromEntries(setup.panes.map((p) => [p.id, steps.panesDraft?.[p.id] ?? p.on])));
  const set = (next: Record<string, boolean>) => { setOn(next); steps.panesDraft = next; };
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">Choose what your panel does</h2>
      <p className="lede">Turn on the features you want now. You can change any of these later in settings.</p>
      <ul className="panes" aria-label="Panes">
        {setup.panes.map((p) => (
          <li key={p.id}>
            <label className={on[p.id] ? 'pn on' : 'pn'}>
              <PaneIcon svg={icons?.(p.id)} />
              <span className="t"><span>{p.title}</span><small>{p.detail}</small></span>
              <input type="checkbox" role="switch" className="sw" checked={!!on[p.id]} disabled={setup.busy}
                onChange={(e) => set({ ...on, [p.id]: e.target.checked })} />
            </label>
          </li>
        ))}
      </ul>
      {setup.error && <p className="status err" role="alert">{setup.error}</p>}
      {setup.busy && <p className="status work" role="status">Saving your choices…</p>}
      <Foot>
        <span />
        <button type="button" className="btn ember" disabled={setup.busy || busy}
          onClick={() => void steps.click('panes', () => steps.applyPanes(setup.panes.filter((p) => on[p.id]).map((p) => p.id)))}>Continue</button>
      </Foot>
    </section>
  );
}

/** The radio pane's one choice: what the Zigbee/Thread radio does. */
export function Radio({ busy, steps, setup }: StepProps<'radio'>) {
  const [mode, setMode] = useState(setup.radio.mode);
  return (
    <section className="card" aria-labelledby="h">
      <h2 id="h">What should the radio do?</h2>
      <p className="lede">The panel includes a built-in Zigbee and Thread radio. Leave it off if you don&apos;t need it yet — you can change this anytime later.</p>
      <RadioGroup.Root className="list" value={mode} onValueChange={setMode} aria-label="Radio role" disabled={setup.busy}>
        {setup.radio.options.map((o) => (
          <RadioGroup.Item key={o.id} value={o.id} className="opt" disabled={!setup.radio.helper && o.id !== 'off'}>
            <span className="rd" />
            <span className="t"><span>{o.label}{o.experimental && <em className="tag">Experimental</em>}</span><small>{o.detail}</small></span>
          </RadioGroup.Item>
        ))}
      </RadioGroup.Root>
      <p className={setup.error ? 'status err' : 'status'} role="status">{setup.error ?? setup.note}</p>
      <Foot>
        <button type="button" className="btn ghost" disabled={setup.busy || busy} onClick={() => void steps.click('finish', () => steps.finish())}>Skip, leave it as it is</button>
        <button type="button" className="btn ember" disabled={setup.busy || busy} onClick={() => void steps.click('radio', () => steps.applyRadio(mode))}>Finish</button>
      </Foot>
    </section>
  );
}

/**
 * Copies [value]. The Clipboard API needs a secure context, which the panel's own page (plain HTTP on the
 * LAN) isn't, so it falls back to copying a hidden selection there.
 */
async function copy(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard) { await navigator.clipboard.writeText(value); return true; }
  } catch { /* refused: try the selection */ }
  const t = document.createElement('textarea');
  t.value = value;
  t.setAttribute('readonly', '');
  t.style.position = 'fixed';
  t.style.opacity = '0';
  document.body.appendChild(t);
  t.select();
  try { return document.execCommand('copy'); } catch { return false; } finally { t.remove(); }
}

function Copy({ value }: { value: string }) {
  const [t, setT] = useState('Copy');
  return <button type="button" className="link" onClick={async () => {
    setT((await copy(value)) ? 'Copied' : 'Copy manually');
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

export function RadioNext({ busy, steps, setup }: StepProps<'radio-next'>) {
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
          <Collapsible.Trigger className="link" style={{ justifySelf: 'start' }}>{more ? 'Hide help' : 'More help'}</Collapsible.Trigger>
          <Collapsible.Content><ol className="next" style={list}>{plan.more.map((st) => <PlanStep key={st.title} st={st} />)}</ol></Collapsible.Content>
        </Collapsible.Root>
      )}
      <Foot><span /><button type="button" className={`btn ${led ? 'ghost' : 'ember'}`} disabled={busy} onClick={() => steps.finish()}>Finish</button></Foot>
    </section>
  );
}
