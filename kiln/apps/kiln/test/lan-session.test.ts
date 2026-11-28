// The panel's own setup page (#34): pairing with the code on the panel's screen, then Kiln's setup steps
// over the panel's WebSocket; pairing again when the panel ended the session (30 minutes without a change).
import { NOT_PAIRED, type SetupState } from '@glassworks/setup-channel';
import { describe, expect, it, vi } from 'vitest';
import { cameBackFromSignIn, FULL_RAIL, LanSession, RAN_OUT, SIGN_IN_HERE, type ChannelStream } from '../src/lan/lanSession';
import { JOIN_CHECK_MS, stillOnHotspot } from '../src/setup/channelSetup';

const CODE = '482913';

/** The panel as the page sees it: /setup/pair, /setup/done and the channel, with one browser's pairing. */
function fakePanel({ open = true, online = undefined as boolean | undefined, radio = true } = {}) {
  const st: SetupState = {
    ip: '192.0.2.10',
    online,
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.0.2.10:8099/signin' },
    radio: { mode: 'off', status: 'Off', helper: true, answered: false, options: [{ id: 'off', label: 'Off', detail: '' }, { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: '' }] },
  };
  if (!radio) delete (st as Partial<SetupState>).radio; // a frame without one (the Mirror) leaves it out
  const panel = {
    st,
    paired: false,
    /** Every channel the page opens fails its first request (it opens, then nothing answers). */
    channelDead: false,
    /** Every request after this many fails, on this channel and any new one. */
    deadAfter: undefined as number | undefined,
    /** The panel refuses to end the session (a network error on /setup/done). */
    doneFails: false,
    /** /setup/done answers this instead of ending the session. */
    doneAnswer: undefined as { status: number; body: Record<string, unknown> } | undefined,
    closedChannels: 0,
    /** wifi.join refuses with this, as a panel on a network does. */
    joinRefused: undefined as string | undefined,
    /** wifi.join is written, but no reply ever comes: the panel closed its hotspot as it joined. */
    joinDrops: false,
    /** The connection is gone when wifi.join is written: nothing reaches the panel. */
    joinUnsent: false,
    /** The panel closed its hotspot: nothing it gets is answered any more. */
    gone: false,
    /** set_ha answers only once this settles (a slow save). */
    setHaGate: undefined as Promise<void> | undefined,
    /** What wifi.join carried. */
    joined: undefined as { ssid: string; password: string } | undefined,
    sleep: async () => {},
    shown: false,
    calls: [] as string[],
    asked: [] as string[],
    async fetch(path: string, init?: { method: 'POST'; body?: string }) {
      panel.calls.push(`${init?.method ?? 'GET'} ${path}${init?.body ? ` ${init.body}` : ''}`);
      const reply = (status: number, body: Record<string, unknown>) => ({ status, json: async () => body });
      if (path === '/setup/done') {
        if (panel.doneFails) throw new TypeError('Failed to fetch');
        if (panel.doneAnswer) return reply(panel.doneAnswer.status, panel.doneAnswer.body);
        panel.paired = false;
        return reply(200, { ok: true });
      }
      if (!init) return reply(200, { ok: true, paired: panel.paired, open });
      if (!open) return reply(403, { ok: false, error: 'Home Assistant has added this panel, so pairing over the network is off.' });
      if (!init.body) { panel.shown = true; return reply(200, { ok: true, shown: true }); }
      if (!panel.shown) return reply(409, { ok: false, error: 'That code has run out. Show a new one.' });
      if (init.body !== `code=${CODE}`) return reply(401, { ok: false, error: 'That\'s not the code. 4 tries left.' });
      panel.paired = true;
      panel.shown = false;
      return reply(200, { ok: true, paired: true });
    },
    async openStream(): Promise<ChannelStream> {
      // the channel carries the session the browser had when it opened (as the panel's cookie does)
      const pairedAtOpen = panel.paired;
      let cb: (c: Uint8Array) => void = () => {};
      return {
        onData: (f) => { cb = f; },
        close: async () => { panel.closedChannels++; },
        write: async (d) => {
          if (panel.channelDead || (panel.deadAfter !== undefined && panel.asked.length >= panel.deadAfter)) throw new Error('the connection dropped');
          for (const line of new TextDecoder().decode(d).split('\n').filter(Boolean)) {
            const m = JSON.parse(line);
            panel.asked.push(m.op);
            if (panel.gone) continue;
            let r: object;
            if (m.op !== 'state' && !(pairedAtOpen && panel.paired)) r = { id: m.id, ok: false, error: NOT_PAIRED };
            else if (m.op === 'wifi.scan') r = { id: m.id, ok: true, state: st, networks: [{ ssid: 'Example-Home', rssi: -48, secure: true }] };
            else if (m.op === 'wifi.join') {
              if (panel.joinUnsent) throw new Error('the connection dropped');
              if (panel.joinDrops) { panel.joined = { ssid: m.ssid, password: m.password }; panel.gone = true; continue; }
              if (panel.joinRefused) r = { id: m.id, ok: false, error: panel.joinRefused };
              else { panel.joined = { ssid: m.ssid, password: m.password }; r = { id: m.id, ok: true, state: st }; }
            } else {
              if (m.op === 'set_ha') st.ha.url = m.url;
              if (m.op === 'ha.set_dashboard') st.ha.dashboard = m.dashboard;
              if (m.op === 'set_radio') { st.radio.mode = m.mode; st.radio.answered = true; }
              r = { id: m.id, ok: true, state: st };
            }
            const answer = () => cb(new TextEncoder().encode(`${JSON.stringify(r)}\n`));
            if (m.op === 'set_ha' && panel.setHaGate) void panel.setHaGate.then(answer);
            else queueMicrotask(answer);
          }
        },
      };
    },
  };
  return panel;
}

describe('the panel\'s setup page', () => {
  it('takes the panel\'s touch from its state: a frame without touch is offered nothing on the panel (Mirror run, 2026-11-20)', async () => {
    const off = fakePanel();
    off.st.touch = false;
    const lan = new LanSession(off);
    await lan.start(); // its peek at the state, before pairing
    await vi.waitFor(() => expect(lan.touch).toBe(false));
    const on = new LanSession(fakePanel());
    await on.start();
    await new Promise((r) => setTimeout(r, 20));
    expect(on.touch).toBeUndefined(); // an older app says nothing: the steps take it as touch, as before
  });

  it('pairs with the code on the panel, then runs Kiln\'s steps to the end', async () => {
    const panel = fakePanel();
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toEqual({ at: 'pair', open: true, shown: false });
    await lan.showCode();
    expect(lan.state.view).toMatchObject({ at: 'pair', shown: true });
    await lan.enterCode('111111');
    expect(lan.state.view).toMatchObject({ at: 'pair', shown: true, error: 'That\'s not the code. 4 tries left.' });
    expect(panel.asked).toEqual(['state']); // before pairing only the state, for the rail
    await lan.enterCode('482 913');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha' } });
    await lan.saveHa('http://192.0.2.20:8123');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    await lan.applyRadio('off');
    await vi.waitFor(() => expect(lan.state.view).toEqual({ at: 'done' }));
    expect(panel.calls.at(-1)).toBe('POST /setup/done');
    expect(panel.asked).toEqual(['state', 'state', 'set_ha', 'state', 'set_radio']);
  });

  it('a panel on no network (its setup hotspot) starts with Wi-Fi, and a join leaves the page on "joining"', async () => {
    const panel = fakePanel({ online: false });
    panel.paired = true;
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', over: 'lan', networks: [{ ssid: 'Example-Home' }] } });
    await lan.click('join', () => lan.joinWifi('Example-Home', 'correct horse'));
    expect(panel.joined).toEqual({ ssid: 'Example-Home', password: 'correct horse' });
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { ssid: 'Example-Home', step: 'join' } } });
    const v = lan.state.view as { setup: { joinNote?: string } };
    expect(v.setup.joinNote).toMatch(/can't follow it across networks/);
    expect(panel.asked).toEqual(['state', 'wifi.scan', 'wifi.join']);
  });

  it('a join whose reply never comes (the hotspot closed first) still counts as sent, not as a lost panel', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const panel = fakePanel({ online: false });
      panel.paired = true;
      panel.joinDrops = true;
      const lan = new LanSession(panel);
      await lan.start();
      const join = lan.click('join', () => lan.joinWifi('Example-Home', 'correct horse'));
      await vi.advanceTimersByTimeAsync(9000); // the channel's answer timeout
      await join;
      expect(panel.joined?.ssid).toBe('Example-Home');
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'join' } } });
      // later, the check finds no panel (it left to join): still "joining", and the channel let go
      const closed = panel.closedChannels;
      await vi.advanceTimersByTimeAsync(JOIN_CHECK_MS + 9000);
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'join' } } });
      expect(panel.closedChannels).toBe(closed + 1);
    } finally { vi.useRealTimers(); }
  });

  it('a join after which the panel still answers (its setup Wi-Fi never closed) says so, and Try again works', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const panel = fakePanel({ online: false });
      panel.paired = true;
      const lan = new LanSession(panel);
      await lan.start();
      await lan.click('join', () => lan.joinWifi('Example-Home', 'correct horse'));
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'join' } } });
      await vi.advanceTimersByTimeAsync(JOIN_CHECK_MS - 1);
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { joining: { step: 'join' } } });
      await vi.advanceTimersByTimeAsync(1);
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'err', error: stillOnHotspot('Example-Home') } } });
      await lan.click('wifi', () => lan.wifi());
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', networks: [{ ssid: 'Example-Home' }] } });
      expect((lan.state.view as { setup: { joining?: unknown } }).setup.joining).toBeUndefined();
    } finally { vi.useRealTimers(); }
  });

  it('a join that never left this page is an error to try again from, not "joining"', async () => {
    const panel = fakePanel({ online: false });
    panel.paired = true;
    panel.joinUnsent = true;
    const lan = new LanSession(panel);
    await lan.start();
    await lan.click('join', () => lan.joinWifi('Example-Home', 'correct horse'));
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'err', error: expect.stringMatching(/Lost connection to the panel/) } } });
  });

  it('a join refused because the pairing ran out pairs again, and comes back to Wi-Fi', async () => {
    const panel = fakePanel({ online: false });
    panel.paired = true;
    const lan = new LanSession(panel);
    await lan.start();
    panel.paired = false;
    await lan.click('join', () => lan.joinWifi('Example-Home', 'correct horse'));
    expect(lan.state.view).toEqual({ at: 'pair', open: true, shown: false, note: RAN_OUT });
    await lan.showCode();
    await lan.enterCode(CODE);
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi' } });
  });

  it('the panel\'s refusal shows on the Wi-Fi step', async () => {
    const panel = fakePanel({ online: false });
    panel.paired = true;
    panel.joinRefused = 'A Wi-Fi password has at most 63 characters, or is a 64-digit hex key.';
    const lan = new LanSession(panel);
    await lan.start();
    await lan.click('join', () => lan.joinWifi('Example-Home', 'short'));
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'err', error: panel.joinRefused } } });
  });

  it('a panel on a network skips Wi-Fi: it would refuse a join', async () => {
    const panel = fakePanel({ online: true });
    panel.paired = true;
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha' } });
    expect(panel.asked).not.toContain('wifi.scan');
  });

  it('a browser already paired goes straight to the steps', async () => {
    const panel = fakePanel();
    panel.paired = true;
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha' } });
  });

  it('says pairing is off once Home Assistant has the panel', async () => {
    const lan = new LanSession(fakePanel({ open: false }));
    await lan.start();
    expect(lan.state.view).toEqual({ at: 'pair', open: false, shown: false });
  });

  it('pairs again when the panel ended the session, and carries on where it was', async () => {
    const panel = fakePanel();
    const lan = new LanSession(panel);
    await lan.start();
    await lan.showCode();
    await lan.enterCode(CODE);
    await lan.saveHa('http://192.0.2.20:8123');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    panel.paired = false; // 30 minutes without a change: reading the state didn't count
    await lan.applyRadio('zigbee_tcp');
    expect(lan.state.view).toEqual({ at: 'pair', open: true, shown: false, note: RAN_OUT });
    await lan.showCode();
    await lan.enterCode(CODE);
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    await lan.applyRadio('zigbee_tcp');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio-next' } });
  });

  it('a Panes step whose pairing ran out pairs again, and comes back to Panes', async () => {
    const panel = fakePanel();
    panel.st.panes = [{ id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true }];
    const lan = new LanSession(panel);
    await lan.start();
    await lan.showCode();
    await lan.enterCode(CODE);
    await lan.saveHa('http://192.0.2.20:8123');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'panes' } });
    panel.paired = false;
    await lan.applyPanes(['assist']);
    expect(lan.state.view).toEqual({ at: 'pair', open: true, shown: false, note: RAN_OUT });
    await lan.showCode();
    await lan.enterCode(CODE);
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'panes' } });
  });

  it('a short code is caught before it goes to the panel', async () => {
    const panel = fakePanel();
    const lan = new LanSession(panel);
    await lan.start();
    await lan.showCode();
    await lan.enterCode('4829');
    expect(lan.state.view).toMatchObject({ at: 'pair', error: 'Enter the 6-digit code shown on the panel.' });
    expect(panel.calls.filter((c) => c.includes('code='))).toEqual([]);
  });

  it('a channel that never answers is an error to retry, not Done, and no try leaves a socket open', async () => {
    const panel = fakePanel();
    panel.paired = true;
    panel.channelDead = true;
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toMatchObject({ at: 'lost', again: 'start', error: expect.stringMatching(/Couldn't connect to the panel right now/) });
    expect(panel.calls).not.toContain('POST /setup/done');
    expect(panel.closedChannels).toBe(5); // every try's socket, once its first request failed
    panel.channelDead = false;
    await lan.retry();
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha' } });
  });

  it('says Done only once the panel has ended the pairing, and Try again ends it', async () => {
    const panel = fakePanel();
    panel.paired = true;
    const lan = new LanSession(panel);
    await lan.start();
    panel.doneFails = true;
    lan.finish();
    await vi.waitFor(() => expect(lan.state.view).toMatchObject({ at: 'lost', again: 'done' }));
    expect(panel.paired).toBe(true);
    panel.doneFails = false;
    await lan.retry();
    expect(lan.state.view).toEqual({ at: 'done' });
    expect(panel.paired).toBe(false);
  });

  it('Skip and Finish keep the page busy until the panel has ended the pairing', async () => {
    const panel = fakePanel();
    panel.paired = true;
    const lan = new LanSession(panel);
    await lan.start();
    let answer!: () => void;
    const real = panel.fetch;
    panel.fetch = async (path, init) => {
      if (path === '/setup/done') await new Promise<void>((r) => { answer = r; });
      return real(path, init);
    };
    const skipping = lan.click('finish', () => lan.finish());
    await vi.waitFor(() => expect(answer).toBeTypeOf('function'));
    expect(lan.state.busy).toBe('finish');
    answer();
    await skipping;
    expect(lan.state.busy).toBeUndefined();
    expect(lan.state.view).toEqual({ at: 'done' });
  });

  for (const [what, answer] of [
    ['200 with ok:false', { status: 200, body: { ok: false } }],
    ['500', { status: 500, body: { ok: false, error: 'Something went wrong on the panel.' } }],
  ] as const) {
    it(`a ${what} from /setup/done is not Done`, async () => {
      const panel = fakePanel();
      panel.paired = true;
      const lan = new LanSession(panel);
      await lan.start();
      panel.doneAnswer = answer;
      await lan.finish();
      expect(lan.state.view).toMatchObject({ at: 'lost', again: 'done', error: expect.stringMatching(/didn't finish closing this browser's pairing session/) });
      panel.doneAnswer = undefined;
      await lan.retry();
      expect(lan.state.view).toEqual({ at: 'done' });
    });
  }

  describe('the rail lists the steps the panel has (#453)', () => {
    it('a panel without a radio has no radio step, from before pairing; the read lets the channel go', async () => {
      const panel = fakePanel({ radio: false });
      const lan = new LanSession(panel);
      await lan.start();
      await vi.waitFor(() => expect(lan.state.rail).toEqual(['pair', 'wifi', 'ha', 'done']));
      expect(panel.closedChannels).toBe(1);
      await lan.showCode();
      await lan.enterCode(CODE);
      await lan.saveHa('http://192.0.2.20:8123');
      await vi.waitFor(() => expect(lan.state.view).toEqual({ at: 'done' }));
      expect(lan.state.rail).toEqual(['pair', 'wifi', 'ha', 'done']);
    });

    it('a panel with a radio keeps it', async () => {
      const panel = fakePanel();
      panel.paired = true;
      const lan = new LanSession(panel);
      await lan.start();
      expect(lan.state.rail).toEqual(FULL_RAIL);
    });

    it('a paired browser learns it from the steps\' own channel', async () => {
      const panel = fakePanel({ radio: false });
      panel.paired = true;
      const lan = new LanSession(panel);
      await lan.start();
      expect(lan.state.rail).toEqual(['pair', 'wifi', 'ha', 'done']);
    });
  });

  describe('skipping the sign-in (the panel asks for it on its screen)', () => {
    it('keeps a valid address and moves on', async () => {
      const panel = fakePanel();
      panel.paired = true;
      const lan = new LanSession(panel);
      await lan.start();
      await lan.skipHa('http://192.0.2.20:8123');
      expect(panel.st.ha.url).toBe('http://192.0.2.20:8123');
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });

    it('moves on without saving an address that isn\'t one', async () => {
      const panel = fakePanel();
      panel.paired = true;
      const lan = new LanSession(panel);
      await lan.start();
      await lan.skipHa('not an address');
      expect(panel.asked).not.toContain('set_ha');
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });
  });

  describe('Home Assistant\'s sign-in in this tab (#453)', () => {
    it('saves the address, then goes to the panel\'s sign-in here: no tab', async () => {
      const panel = fakePanel();
      panel.paired = true;
      const went: string[] = [];
      const openTab = vi.fn(() => null);
      const lan = new LanSession({ ...panel, navigate: (p) => went.push(p), openTab });
      await lan.start();
      lan.signIn('http://192.0.2.20:8123');
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha', signingIn: true, note: expect.stringMatching(/return here automatically/) } });
      await vi.waitFor(() => expect(went).toEqual([SIGN_IN_HERE]));
      expect(panel.asked.indexOf('set_ha')).toBeGreaterThan(-1);
      expect(panel.st.ha.url).toBe('http://192.0.2.20:8123');
      expect(openTab).not.toHaveBeenCalled();
    });

    it('a panel that gives no sign-in link (no Wi-Fi IPv4, as the Mirror) still signs in here, on this page\'s address', async () => {
      const panel = fakePanel();
      panel.paired = true;
      delete panel.st.ip;
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signinUrl: undefined };
      const went: string[] = [];
      const lan = new LanSession({ ...panel, navigate: (p) => went.push(p) });
      await lan.start();
      lan.signIn('http://192.0.2.20:8123');
      await vi.waitFor(() => expect(went).toEqual([SIGN_IN_HERE]));
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha', signingIn: true } });
      expect((lan.state.view as { setup: { error?: string } }).setup.error).toBeUndefined();
    });

    it('a slow save the person moved on from doesn\'t take the page away', async () => {
      const panel = fakePanel();
      panel.paired = true;
      let saved!: () => void;
      panel.setHaGate = new Promise((r) => { saved = r; });
      const went: string[] = [];
      const lan = new LanSession({ ...panel, navigate: (p) => went.push(p) });
      await lan.start();
      lan.signIn('http://192.0.2.20:8123');
      await lan.radio(); // "Skip" while the address is still being saved
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
      saved();
      await vi.waitFor(() => expect(panel.st.ha.url).toBe('http://192.0.2.20:8123'));
      await new Promise((r) => setTimeout(r, 10));
      expect(went).toEqual([]);
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });

    it('back from it, the panel lost on the way: Try again still carries on after Home Assistant', async () => {
      const panel = fakePanel();
      panel.paired = true;
      panel.channelDead = true;
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signedIn: true };
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      expect(lan.state.view).toMatchObject({ at: 'lost' });
      panel.channelDead = false;
      await lan.retry();
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });

    it('back from it, the panel lost at the radio step: Try again still lands on Radio', async () => {
      const panel = fakePanel();
      panel.paired = true;
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signedIn: true };
      panel.deadAfter = 2; // the state reads after the return work, the radio step's own doesn't
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      expect(lan.state.view).toMatchObject({ at: 'lost' });
      panel.deadAfter = undefined;
      await lan.retry();
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });

    it('the page knows it came back by the #signed-in mark, and takes the mark off so a reload doesn\'t hand off again', () => {
      const loc = { hash: '#signed-in', pathname: '/setup', search: '' };
      const history = { replaceState: vi.fn((_s: unknown, _t: string, url: string) => { loc.hash = new URL(url, 'http://192.0.2.10:8099').hash; }) };
      expect(cameBackFromSignIn(loc, history)).toBe(true);
      expect(history.replaceState).toHaveBeenCalledWith(null, '', '/setup');
      expect(cameBackFromSignIn(loc, history)).toBe(false); // the reload
      expect(cameBackFromSignIn({ hash: '', pathname: '/setup', search: '' }, history)).toBe(false);
      expect(history.replaceState).toHaveBeenCalledTimes(1);
    });

    it('back from it, signed in: on at the radio step', async () => {
      const panel = fakePanel();
      panel.paired = true;
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signedIn: true };
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });

    it('back from it, signed in with custom dashboards: stays on Home Assistant to pick one, then advances on Continue', async () => {
      const panel = fakePanel();
      panel.paired = true;
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signedIn: true, dashboards: ['Default', 'Tablet', 'Energy'], dashboard: 'Default' };
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha', ha: { signedIn: true, dashboards: ['Default', 'Tablet', 'Energy'] } } });
      await lan.saveHa('http://192.0.2.20:8123', 'Energy');
      expect(panel.asked).toContain('ha.set_dashboard');
      expect(panel.st.ha.dashboard).toBe('Energy');
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });

    it('back from it on a panel without a radio: Done', async () => {
      const panel = fakePanel({ radio: false });
      panel.paired = true;
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signedIn: true };
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      await vi.waitFor(() => expect(lan.state.view).toEqual({ at: 'done' }));
    });

    it('back from it, not signed in after all: Home Assistant again', async () => {
      const panel = fakePanel();
      panel.paired = true;
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha' } });
    });

    it('back from it after the pairing ended: pair again, then on after Home Assistant', async () => {
      const panel = fakePanel();
      panel.st.ha = { ...panel.st.ha, url: 'http://192.0.2.20:8123', signedIn: true };
      const lan = new LanSession({ ...panel, backFromSignIn: true });
      await lan.start();
      expect(lan.state.view).toMatchObject({ at: 'pair' });
      await lan.showCode();
      await lan.enterCode(CODE);
      expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    });
  });
});
