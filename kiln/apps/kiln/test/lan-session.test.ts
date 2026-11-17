// The panel's own setup page (#34): pairing with the code on the panel's screen, then Kiln's setup steps
// over the panel's WebSocket; pairing again when the panel ended the session (30 minutes without a change).
import { NOT_PAIRED, type SetupState } from '@glassworks/setup-channel';
import { describe, expect, it, vi } from 'vitest';
import { LanSession, RAN_OUT, type ChannelStream } from '../src/lan/lanSession';
import { JOIN_CHECK_MS, stillOnHotspot } from '../src/setup/channelSetup';

const CODE = '482913';

/** The panel as the page sees it: /setup/pair, /setup/done and the channel, with one browser's pairing. */
function fakePanel({ open = true, online = undefined as boolean | undefined } = {}) {
  const st: SetupState = {
    ip: '192.0.2.10',
    online,
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.0.2.10:8099/signin' },
    radio: { mode: 'off', status: 'Off', helper: true, answered: false, options: [{ id: 'off', label: 'Off', detail: '' }, { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: '' }] },
  };
  const panel = {
    paired: false,
    /** Every channel the page opens fails its first request (it opens, then nothing answers). */
    channelDead: false,
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
          if (panel.channelDead) throw new Error('the connection dropped');
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
              if (m.op === 'set_radio') { st.radio.mode = m.mode; st.radio.answered = true; }
              r = { id: m.id, ok: true, state: st };
            }
            queueMicrotask(() => cb(new TextEncoder().encode(`${JSON.stringify(r)}\n`)));
          }
        },
      };
    },
  };
  return panel;
}

describe('the panel\'s setup page', () => {
  it('pairs with the code on the panel, then runs Kiln\'s steps to the end', async () => {
    const panel = fakePanel();
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toEqual({ at: 'pair', open: true, shown: false });
    await lan.showCode();
    expect(lan.state.view).toMatchObject({ at: 'pair', shown: true });
    await lan.enterCode('111111');
    expect(lan.state.view).toMatchObject({ at: 'pair', shown: true, error: 'That\'s not the code. 4 tries left.' });
    expect(panel.asked).toEqual([]); // nothing reaches the channel before pairing
    await lan.enterCode('482 913');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'ha' } });
    await lan.saveHa('http://192.0.2.20:8123');
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'radio' } });
    await lan.applyRadio('off');
    await vi.waitFor(() => expect(lan.state.view).toEqual({ at: 'done' }));
    expect(panel.calls.at(-1)).toBe('POST /setup/done');
    expect(panel.asked).toEqual(['state', 'set_ha', 'state', 'set_radio']);
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
    expect(v.setup.joinNote).toMatch(/screen shows how it goes/);
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
    expect(lan.state.view).toMatchObject({ at: 'setup', setup: { phase: 'wifi', joining: { step: 'err', error: expect.stringMatching(/lost the panel/) } } });
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

  it('a short code is caught before it goes to the panel', async () => {
    const panel = fakePanel();
    const lan = new LanSession(panel);
    await lan.start();
    await lan.showCode();
    await lan.enterCode('4829');
    expect(lan.state.view).toMatchObject({ at: 'pair', error: 'The code has 6 digits.' });
    expect(panel.calls.filter((c) => c.includes('code='))).toEqual([]);
  });

  it('a channel that never answers is an error to retry, not Done, and no try leaves a socket open', async () => {
    const panel = fakePanel();
    panel.paired = true;
    panel.channelDead = true;
    const lan = new LanSession(panel);
    await lan.start();
    expect(lan.state.view).toMatchObject({ at: 'lost', again: 'start', error: expect.stringMatching(/didn't open its setup channel/) });
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
      expect(lan.state.view).toMatchObject({ at: 'lost', again: 'done', error: expect.stringMatching(/didn't end this browser's pairing/) });
      panel.doneAnswer = undefined;
      await lan.retry();
      expect(lan.state.view).toEqual({ at: 'done' });
    });
  }
});
