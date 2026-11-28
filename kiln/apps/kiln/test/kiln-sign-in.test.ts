// Kiln over USB signs the panel in to Home Assistant in a window it opens (issue 453): the panel's sign-in with
// then=kiln, whose last page closes that window, and Kiln moves on when the panel reports the sign-in, with no click.
import { SetupChannel, type SetupState } from '@glassworks/setup-channel';
import type { ByteStream } from '@glassworks/improv-serial';
import { describe, expect, it, vi } from 'vitest';
import { ChannelSetup } from '../src/setup/channelSetup';
import type { Setup } from '../src/wizard/state';

/** The panel over the channel; `signIn()` is HA's callback reaching it, which it announces as a state event. */
function panel() {
  let cb: (c: Uint8Array) => void = () => {};
  const st: SetupState = {
    ip: '192.0.2.10',
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.0.2.10:8099/signin' },
    radio: { mode: 'off', status: 'Off', helper: true, options: [{ id: 'off', label: 'Off', detail: '' }] },
  };
  const send = (o: object) => queueMicrotask(() => cb(new TextEncoder().encode(`${JSON.stringify(o)}\n`)));
  const self = {
    st,
    /** set_ha answers only once this settles (a slow save). */
    setHaGate: undefined as Promise<void> | undefined,
    /** state answers only once this settles. */
    stateGate: undefined as Promise<void> | undefined,
    signIn: (dashboards?: string[]) => {
      st.ha.signedIn = true;
      if (dashboards) { st.ha.dashboards = dashboards; st.ha.dashboard = 'Default'; }
      send({ event: 'state', state: st });
    },
    asked: [] as { op: string; dashboard?: string }[],
    stream: {
      onData: (f: (c: Uint8Array) => void) => { cb = f; },
      close: async () => {},
      write: async (d: Uint8Array) => {
        for (const line of new TextDecoder().decode(d).split('\n').filter(Boolean)) {
          const m = JSON.parse(line);
          self.asked.push(m);
          if (m.op === 'state' && self.stateGate) { const r = { id: m.id, ok: true, state: st }; void self.stateGate.then(() => send(r)); continue; }
          if (m.op === 'set_ha') {
            st.ha.url = m.url;
            if (self.setHaGate) { const r = { id: m.id, ok: true, state: st }; void self.setHaGate.then(() => send(r)); continue; }
          }
          if (m.op === 'ha.set_dashboard') st.ha.dashboard = m.dashboard;
          send({ id: m.id, ok: true, state: st });
        }
      },
    } satisfies ByteStream,
  };
  return self;
}

function kiln(p: ReturnType<typeof panel>) {
  let phase: Setup = { phase: 'waiting', note: '' };
  const win = { location: { href: 'about:blank' }, closed: false, close: vi.fn() } as unknown as { location: { href: string }; closed: boolean } & Window;
  const openTab = vi.fn(() => win);
  let opens = 0;
  const done = vi.fn();
  const steps = new ChannelSetup({
    open: async () => { opens++; const c = new SetupChannel(p.stream); await c.getState(); return c; },
    phase: () => phase,
    show: (s) => { phase = s; },
    here: () => true,
    done,
    unreachable: (e) => { throw e; },
    app: async () => undefined,
    openTab,
  });
  return { steps, win, openTab, phase: () => phase, opens: () => opens, done };
}

describe('Kiln\'s sign-in', () => {
  it('opens its window inside the click and points it at the panel\'s sign-in for Kiln', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    k.steps.signIn('http://192.0.2.20:8123');
    expect(k.openTab).toHaveBeenCalledTimes(1); // before any await: inside the click
    expect(k.phase()).toMatchObject({ phase: 'ha', signingIn: true, note: expect.stringMatching(/window that opened.*moves on by itself/) });
    await vi.waitFor(() => expect(k.win.location.href).toBe('http://192.0.2.10:8099/signin/continue?then=kiln'));
    expect(p.st.ha.url).toBe('http://192.0.2.20:8123');
    k.steps.close();
  });

  it('moves on to the radio step when the panel reports the sign-in, with no click', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    k.steps.signIn('http://192.0.2.20:8123');
    await vi.waitFor(() => expect(k.win.location.href).toMatch(/then=kiln$/));
    p.signIn();
    await vi.waitFor(() => expect(k.phase()).toMatchObject({ phase: 'radio' }));
    k.steps.close();
  });

  it('stays on Home Assistant after sign-in when custom dashboards exist, then saves the chosen dashboard on Continue', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    k.steps.signIn('http://192.0.2.20:8123');
    await vi.waitFor(() => expect(k.win.location.href).toMatch(/then=kiln$/));
    p.signIn(['Default', 'Tablet', 'Energy']);
    await vi.waitFor(() => expect(k.phase()).toMatchObject({ phase: 'ha', ha: { signedIn: true, dashboards: ['Default', 'Tablet', 'Energy'] } }));
    await new Promise((r) => setTimeout(r, 10));
    expect(k.phase()).toMatchObject({ phase: 'ha' });
    await k.steps.saveHa('http://192.0.2.20:8123', 'Tablet');
    expect(p.asked.some((m) => m.op === 'ha.set_dashboard' && m.dashboard === 'Tablet')).toBe(true);
    expect(p.st.ha.dashboard).toBe('Tablet');
    expect(k.phase()).toMatchObject({ phase: 'radio' });
    k.steps.close();
  });

  it('a panel already signed in, with no sign-in from this page, waits for Continue', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    p.signIn();
    await vi.waitFor(() => expect(k.phase()).toMatchObject({ phase: 'ha', ha: { signedIn: true } }));
    await new Promise((r) => setTimeout(r, 10));
    expect(k.phase()).toMatchObject({ phase: 'ha' });
    k.steps.close();
  });

  it('a window closed without a sign-in gives Sign in back', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    k.steps.signIn('http://192.0.2.20:8123');
    await vi.waitFor(() => expect(k.win.location.href).toMatch(/then=kiln$/));
    k.win.closed = true; // cancelled, or closed by hand
    await vi.waitFor(() => expect(k.phase()).toMatchObject({ phase: 'ha', signingIn: false, note: expect.stringMatching(/closed before the panel was signed in/) }), { timeout: 3000 });
    k.steps.close();
  });

  it('a window the panel\'s last page closed moves on, as the sign-in is done', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    k.steps.signIn('http://192.0.2.20:8123');
    await vi.waitFor(() => expect(k.win.location.href).toMatch(/then=kiln$/));
    p.st.ha.signedIn = true; // the callback stored the sign-in, then its page closed the window
    k.win.closed = true;
    await vi.waitFor(() => expect(k.phase()).toMatchObject({ phase: 'radio' }), { timeout: 3000 });
    k.steps.close();
  });

  it('back from a sign-in after the session closed does nothing more', async () => {
    const p = panel();
    const k = kiln(p);
    const back = k.steps.afterSignIn();
    k.steps.close(); // a new session took over while the state was being read
    await back;
    await new Promise((r) => setTimeout(r, 10));
    expect(k.opens()).toBe(1);
    expect(k.phase()).toEqual({ phase: 'waiting', note: '' });
  });

  it('a session closed while the address was saved points no window at the panel', async () => {
    const p = panel();
    let saved!: () => void;
    p.setHaGate = new Promise((r) => { saved = r; });
    const k = kiln(p);
    await k.steps.homeAssistant();
    k.steps.signIn('http://192.0.2.20:8123');
    k.steps.close(); // a new session took over
    saved();
    await new Promise((r) => setTimeout(r, 20));
    expect(k.win.location.href).toBe('about:blank');
    expect(k.win.close).toHaveBeenCalled();
  });

  it('the radio step\'s answer after the session closed shows nothing', async () => {
    const p = panel();
    const k = kiln(p);
    await k.steps.homeAssistant();
    const before = k.phase();
    let answered!: () => void;
    p.stateGate = new Promise((r) => { answered = r; });
    const radio = k.steps.radio();
    k.steps.close(); // a new session took over while the panel was being read
    answered();
    await radio;
    await new Promise((r) => setTimeout(r, 10));
    expect(k.phase()).toBe(before);
    expect(k.done).not.toHaveBeenCalled();
  });
});
