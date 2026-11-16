// The panel's own setup page (#34): pairing with the code on the panel's screen, then Kiln's setup steps
// over the panel's WebSocket; pairing again when the panel ended the session (30 minutes without a change).
import { NOT_PAIRED, type SetupState } from '@glassworks/setup-channel';
import { describe, expect, it, vi } from 'vitest';
import { LanSession, RAN_OUT, type ChannelStream } from '../src/lan/lanSession';

const CODE = '482913';

/** The panel as the page sees it: /setup/pair, /setup/done and the channel, with one browser's pairing. */
function fakePanel({ open = true } = {}) {
  const st: SetupState = {
    ip: '192.0.2.10',
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.0.2.10:8099/signin' },
    radio: { mode: 'off', status: 'Off', helper: true, answered: false, options: [{ id: 'off', label: 'Off', detail: '' }, { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: '' }] },
  };
  const panel = {
    paired: false,
    /** Every channel the page opens fails its first request (it opens, then nothing answers). */
    channelDead: false,
    /** The panel refuses to end the session (a network error on /setup/done). */
    doneFails: false,
    closedChannels: 0,
    sleep: async () => {},
    shown: false,
    calls: [] as string[],
    asked: [] as string[],
    async fetch(path: string, init?: { method: 'POST'; body?: string }) {
      panel.calls.push(`${init?.method ?? 'GET'} ${path}${init?.body ? ` ${init.body}` : ''}`);
      const reply = (status: number, body: Record<string, unknown>) => ({ status, json: async () => body });
      if (path === '/setup/done') {
        if (panel.doneFails) throw new TypeError('Failed to fetch');
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
            let r: object;
            if (m.op !== 'state' && !(pairedAtOpen && panel.paired)) r = { id: m.id, ok: false, error: NOT_PAIRED };
            else {
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
});
