// @vitest-environment jsdom
// The setup steps run over any setup channel, not only the wizard's adb one (#34): the same code and
// screens serve the panel's own page over the LAN, where an unpaired browser is refused every change.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NOT_PAIRED, SetupChannel, type SetupState } from '@glassworks/setup-channel';
import type { ByteStream } from '@glassworks/improv-serial';
import { afterEach, describe, expect, it } from 'vitest';
import { ChannelSetup, type SetupSteps } from '../src/setup/channelSetup';
import { HomeAssistant, Panes, Radio, RadioNext } from '../src/setup/steps';
import type { Setup } from '../src/wizard/state';

afterEach(() => cleanup());

const panelState: SetupState = {
  ip: '192.0.2.10',
  ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.0.2.10:8099/signin' },
  radio: { mode: 'off', status: 'Off', helper: true, options: [{ id: 'off', label: 'Off', detail: '' }, { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: '' }] },
};

/** The panel's side of the channel: `state` always answers; a change answers only once `paired`. */
function lanPanel(o: { noRadio?: boolean } = {}) {
  let cb: (c: Uint8Array) => void = () => {};
  const st = structuredClone(panelState);
  if (o.noRadio) delete (st as Partial<typeof panelState>).radio; // a generic frame's state has no radio
  const self = {
    paired: false,
    /** The next write fails as a dropped connection would (no answer at all). */
    failNext: false,
    asked: [] as string[],
    stream: {
      onData: (f: (c: Uint8Array) => void) => { cb = f; },
      close: async () => {},
      write: async (d: Uint8Array) => {
        if (self.failNext) { self.failNext = false; throw new Error('the connection dropped'); }
        for (const line of new TextDecoder().decode(d).split('\n').filter(Boolean)) {
          const m = JSON.parse(line);
          self.asked.push(m.op);
          let reply: object;
          if (m.op !== 'state' && !self.paired) reply = { id: m.id, ok: false, error: NOT_PAIRED };
          else {
            if (m.op === 'set_ha') st.ha.url = m.url;
            if (m.op === 'set_radio') st.radio.mode = m.mode;
            reply = { id: m.id, ok: true, state: st };
          }
          queueMicrotask(() => cb(new TextEncoder().encode(`${JSON.stringify(reply)}\n`)));
        }
      },
    } satisfies ByteStream,
  };
  return self;
}

function controller(panel: ReturnType<typeof lanPanel>) {
  let phase: Setup = { phase: 'waiting', note: '' };
  const steps = new ChannelSetup({
    open: async () => { const c = new SetupChannel(panel.stream); await c.getState(); return c; },
    phase: () => phase,
    show: (s) => { phase = s; },
    here: () => true,
    done: () => {},
    unreachable: (e) => { throw e; },
    app: async () => undefined,
  });
  return { steps, phase: () => phase };
}

describe('the setup steps over the LAN', () => {
  it('an unpaired browser reads the state, and is told to pair when it changes something', async () => {
    const panel = lanPanel();
    const c = controller(panel);
    await c.steps.homeAssistant();
    expect(c.phase()).toMatchObject({ phase: 'ha', ha: { discovered: 'http://homeassistant.local:8123' } });
    await c.steps.saveHa('http://192.0.2.20:8123');
    expect(c.phase()).toMatchObject({ phase: 'ha', busy: false, error: NOT_PAIRED });
    // a refusal is an answer: no second channel just to hear it again
    expect(panel.asked).toEqual(['state', 'set_ha']);
    c.steps.close();
  });

  it('a paired browser saves the address and sets the radio, then sees what to do in Home Assistant', async () => {
    const panel = lanPanel();
    panel.paired = true;
    const c = controller(panel);
    await c.steps.homeAssistant();
    await c.steps.saveHa('http://192.0.2.20:8123');
    expect(c.phase()).toMatchObject({ phase: 'radio', radio: { mode: 'off' } });
    await c.steps.applyRadio('zigbee_tcp');
    expect(c.phase()).toMatchObject({ phase: 'radio-next', plan: { title: 'Use the radio in Home Assistant' } });
    expect(panel.asked).toEqual(['state', 'set_ha', 'state', 'set_radio']);
    c.steps.close();
  });

  it('a panel without a radio (the Mirror, any Android device) goes from Home Assistant straight to Done', async () => {
    const panel = lanPanel({ noRadio: true });
    panel.paired = true;
    let done = 0;
    let phase: Setup = { phase: 'waiting', note: '' };
    const steps = new ChannelSetup({
      open: async () => { const c = new SetupChannel(panel.stream); await c.getState(); return c; },
      phase: () => phase, show: (s) => { phase = s; }, here: () => true,
      done: () => { done++; }, unreachable: (e) => { throw e; }, app: async () => undefined,
    });
    await steps.homeAssistant();
    await steps.saveHa('http://192.0.2.20:8123');
    expect(done).toBe(1);
    expect(phase.phase).not.toBe('radio');
    expect(panel.asked).not.toContain('set_radio');
    steps.close();
  });

  it('a Thread router kept as it is, and already answered, sends nothing to the radio', async () => {
    const panel = lanPanel();
    panel.paired = true;
    const c = controller(panel);
    await c.steps.homeAssistant();
    await c.steps.saveHa('');
    (c.steps as unknown as { channel: SetupChannel }).channel.state!.radio = { ...panelState.radio, mode: 'thread_router', status: 'Running', answered: true };
    expect(c.phase()).toMatchObject({ phase: 'radio' });
    await c.steps.applyRadio('thread_router');
    expect(panel.asked).not.toContain('set_radio');
    expect(c.phase()).toMatchObject({ phase: 'radio-next' });
    c.steps.close();
  });

  it('a close while the channel is still opening keeps nothing open and shows nothing', async () => {
    const panel = lanPanel();
    let phase: Setup = { phase: 'waiting', note: '' };
    let release!: () => void;
    const closed: string[] = [];
    const steps = new ChannelSetup({
      open: async () => {
        await new Promise<void>((r) => { release = r; });
        const c = new SetupChannel(panel.stream);
        await c.getState();
        c.close = async () => { closed.push('late channel'); };
        return c;
      },
      phase: () => phase, show: (st) => { phase = st; }, here: () => true,
      done: () => { phase = { phase: 'waiting', note: 'done' }; }, unreachable: () => {}, app: async () => undefined,
    });
    const opening = steps.homeAssistant();
    await new Promise((r) => setTimeout(r, 0));
    steps.close();
    release();
    await opening;
    expect(phase).toEqual({ phase: 'waiting', note: '' });
    expect(closed).toEqual(['late channel']);
  });

  it('a reconnect closes the channel it replaces', async () => {
    const panel = lanPanel();
    const opened: { closed: boolean }[] = [];
    let phase: Setup = { phase: 'waiting', note: '' };
    const steps = new ChannelSetup({
      open: async () => {
        const c = new SetupChannel(panel.stream);
        await c.getState();
        const mark = { closed: false };
        opened.push(mark);
        c.close = async () => { mark.closed = true; };
        return c;
      },
      phase: () => phase, show: (st) => { phase = st; }, here: () => true, done: () => {}, unreachable: () => {}, app: async () => undefined,
    });
    panel.paired = true;
    await steps.homeAssistant();
    panel.failNext = true;
    await steps.saveHa('http://192.0.2.20:8123');
    expect(opened.map((o) => o.closed)).toEqual([true, false]);
    expect(phase).toMatchObject({ phase: 'radio' });
    steps.close();
  });

  it('the next steps\' Finish waits while a button\'s work runs', () => {
    const finished: string[] = [];
    const steps: SetupSteps = { click: async () => {}, signIn: () => {}, saveHa: async () => {}, skipHa: async () => {}, panes: async () => {}, applyPanes: async () => {}, radio: async () => {}, applyRadio: async () => {}, finish: () => { finished.push('finish'); } };
    const plan = { title: 'Use the radio in Home Assistant', lead: '', steps: [] };
    const { rerender } = render(<RadioNext busy steps={steps} setup={{ phase: 'radio-next', plan }} />);
    const finish = screen.getByRole('button', { name: 'Finish' });
    expect(finish).toHaveProperty('disabled', true);
    fireEvent.click(finish);
    expect(finished).toEqual([]);
    rerender(<RadioNext busy={false} steps={steps} setup={{ phase: 'radio-next', plan }} />);
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    expect(finished).toEqual(['finish']);
  });

  it('Copy works on the panel\'s plain-HTTP page, where there is no Clipboard API', async () => {
    const copied: string[] = [];
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    document.execCommand = ((cmd: string) => {
      copied.push(`${cmd}:${document.querySelector('textarea')?.value ?? ''}`);
      return true;
    }) as typeof document.execCommand;
    const steps: SetupSteps = { click: async () => {}, signIn: () => {}, saveHa: async () => {}, skipHa: async () => {}, panes: async () => {}, applyPanes: async () => {}, radio: async () => {}, applyRadio: async () => {}, finish: () => {} };
    const plan = { title: 'Use the radio in Home Assistant', lead: '', steps: [{ title: 'Add the radio', body: '', kv: [['Serial path', 'socket://192.0.2.10:6638', true]] as [string, string, boolean][] }] };
    render(<RadioNext busy={false} steps={steps} setup={{ phase: 'radio-next', plan }} />);
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await screen.findByRole('button', { name: 'Copied' });
    expect(copied).toEqual(['copy:socket://192.0.2.10:6638']);
  });

  it('the step screens need only SetupSteps, not the wizard\'s session', () => {
    const calls: string[] = [];
    const steps: SetupSteps = {
      click: async (name, run) => { calls.push(name); await run(); },
      signIn: (u) => { calls.push(`signIn ${u}`); }, saveHa: async (u) => { calls.push(`saveHa ${u}`); },
      skipHa: async (u) => { calls.push(`skipHa ${u}`); }, panes: async () => {}, applyPanes: async () => {}, radio: async () => {},
      applyRadio: async (m) => { calls.push(`applyRadio ${m}`); }, finish: () => {},
    };
    const { unmount } = render(<HomeAssistant busy={false} steps={steps} setup={{ phase: 'ha', ha: panelState.ha }} />);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    fireEvent.click(screen.getByRole('button', { name: 'Skip, sign in on the panel' }));
    expect(calls).toEqual(['sign-in', 'signIn http://homeassistant.local:8123', 'skip-ha', 'skipHa http://homeassistant.local:8123']);
    unmount();
    render(<Radio busy={false} steps={steps} setup={{ phase: 'radio', radio: panelState.radio, note: '' }} />);
    act(() => { fireEvent.click(screen.getByRole('button', { name: 'Finish' })); });
    expect(calls.at(-1)).toBe('applyRadio off');
  });

  it('HomeAssistant shows a Dashboard select once signed in with custom dashboards and submits it on Continue', async () => {
    const saved: [string, string | undefined][] = [];
    const steps: SetupSteps = {
      click: async (_name, run) => { await run(); },
      signIn: () => {},
      saveHa: async (u, d) => { saved.push([u, d]); },
      skipHa: async () => {},
      panes: async () => {},
      applyPanes: async () => {},
      radio: async () => {},
      applyRadio: async () => {},
      finish: () => {},
    };
    const { rerender } = render(
      <HomeAssistant busy={false} steps={steps} setup={{
        phase: 'ha',
        ha: { ...panelState.ha, url: 'http://homeassistant.local:8123', signedIn: true, dashboards: ['Default', 'Tablet', 'Energy'], dashboard: 'Default' },
      }} />,
    );
    const select = screen.getByRole('combobox', { name: /Dashboard/ }) as HTMLSelectElement;
    expect(select.value).toBe('Default');
    expect(screen.getByText('Leave on Default for your Home Assistant default dashboard.')).toBeTruthy();
    fireEvent.change(select, { target: { value: 'Tablet' } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(saved).toEqual([['http://homeassistant.local:8123', 'Tablet']]);

    // Hidden when only Default exists (no custom dashboards).
    rerender(
      <HomeAssistant busy={false} steps={steps} setup={{
        phase: 'ha',
        ha: { ...panelState.ha, url: 'http://homeassistant.local:8123', signedIn: true, dashboards: ['Default'], dashboard: 'Default' },
      }} />,
    );
    expect(screen.queryByRole('combobox')).toBeNull();
  });
});

describe('the Panes step\'s switches', () => {
  it('are kept by the steps, so a screen that goes and comes back (pairing again) shows what the person set', () => {
    const steps = { click: async (_n: string, run: () => Promise<void> | void) => { await run(); }, signIn: () => {}, saveHa: async () => {}, skipHa: async () => {},
      panes: async () => {}, applyPanes: async () => {}, radio: async () => {}, applyRadio: async () => {}, finish: () => {} } as SetupSteps;
    const setup = { phase: 'panes' as const, panes: [{ id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true }, { id: 'notifications', title: 'Notifications', detail: 'On-screen alerts', on: false }] };
    const first = render(<Panes busy={false} steps={steps} setup={setup} />);
    fireEvent.click(screen.getByRole('switch', { name: /Voice/ }));
    expect(steps.panesDraft).toEqual({ assist: false, notifications: false });
    first.unmount();
    render(<Panes busy={false} steps={steps} setup={setup} />);
    expect((screen.getByRole('switch', { name: /Voice/ }) as HTMLInputElement).checked).toBe(false);
  });
});
