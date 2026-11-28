import { describe, expect, it, vi } from 'vitest';
import type { ByteStream } from '@glassworks/improv-serial';
import { kilnSignInUrl, openSignInWindow, SetupChannel, validHaUrl, type SetupState } from '../src/channel';

const base: SetupState = {
  ip: '192.168.1.50',
  ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.168.1.50:8099/signin' },
  radio: { mode: 'off', status: 'Off', helper: false, options: [{ id: 'off', label: 'Off', detail: '' }] },
};

/** The app's side: JSON lines, replies echo the id, pushes state events. */
function fakeApp(): ByteStream & { sent: object[]; push(o: object): void } {
  let cb: (c: Uint8Array) => void = () => {};
  const state = structuredClone(base);
  const self = {
    sent: [] as object[],
    onData: (f: (c: Uint8Array) => void) => { cb = f; },
    close: async () => {},
    push: (o: object) => cb(new TextEncoder().encode(`${JSON.stringify(o)}\n`)),
    write: async (d: Uint8Array) => {
      for (const line of new TextDecoder().decode(d).split('\n').filter(Boolean)) {
        const m = JSON.parse(line);
        self.sent.push(m);
        if (m.op === 'set_radio' && !state.radio.helper && m.mode !== 'off') { queueMicrotask(() => self.push({ id: m.id, ok: false, error: 'needs the root helper' })); continue; }
        if (m.op === 'set_ha') state.ha.url = m.url;
        if (m.op === 'ha.set_dashboard') state.ha.dashboard = m.dashboard;
        if (m.op === 'wifi.join' && state.online) { queueMicrotask(() => self.push({ id: m.id, ok: false, error: 'The panel is on a network already.' })); continue; }
        if (m.op === 'wifi.scan') { queueMicrotask(() => self.push({ id: m.id, ok: true, state, networks: [{ ssid: 'Example-WiFi', rssi: -50, secure: true }] })); continue; }
        if (m.op === 'set_online') { state.online = true; continue; }
        // reply split across two chunks, to exercise line reassembly
        const out = new TextEncoder().encode(`${JSON.stringify({ id: m.id, ok: true, state })}\n`);
        queueMicrotask(() => { cb(out.subarray(0, 7)); cb(out.subarray(7)); });
      }
    },
  };
  return self;
}

describe('setup channel', () => {
  it('state, set_ha round trip; replies matched by id', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    expect((await c.getState()).ha.discovered).toBe('http://homeassistant.local:8123');
    expect((await c.setHa('http://ha.local:8123')).ha.url).toBe('http://ha.local:8123');
    expect(app.sent).toEqual([{ id: 1, op: 'state' }, { id: 2, op: 'set_ha', url: 'http://ha.local:8123' }]);
  });

  it('errors come back as rejections; pushed events update the state', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    await expect(c.setRadio('zigbee_tcp')).rejects.toThrow(/root helper/);
    const seen: boolean[] = [];
    c.onState = (s) => seen.push(s.ha.signedIn);
    app.push({ event: 'state', state: { ...base, ha: { ...base.ha, signedIn: true } } });
    expect(seen).toEqual([true]);
    expect(c.state?.ha.signedIn).toBe(true);
  });

  it('thread_join starts the join; its progress comes in pushed states', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    await c.joinThread();
    expect(app.sent).toEqual([{ id: 1, op: 'radio.thread_join' }]);
    app.push({ event: 'state', state: { ...base, radio: { ...base.radio, mode: 'thread_router', threadJoin: { phase: 'joined', role: 'child' } } } });
    expect(c.state?.radio.threadJoin).toEqual({ phase: 'joined', role: 'child' });
  });

  it('wifi.scan answers with the networks; wifi.join sends the network and is refused while online', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    expect(await c.scanWifi()).toEqual([{ ssid: 'Example-WiFi', rssi: -50, secure: true }]);
    await c.joinWifi('Example-WiFi', 'correct horse');
    expect(app.sent).toEqual([{ id: 1, op: 'wifi.scan' }, { id: 2, op: 'wifi.join', ssid: 'Example-WiFi', password: 'correct horse' }]);
    await app.write(new TextEncoder().encode(`${JSON.stringify({ op: 'set_online' })}\n`));
    await expect(c.joinWifi('Example-WiFi', 'correct horse')).rejects.toThrow(/on a network/);
  });

  it('validates addresses', () => {
    expect(validHaUrl('http://homeassistant.local:8123')).toBe(true);
    expect(validHaUrl('https://ha.example.com/dash')).toBe(true);
    expect(validHaUrl('homeassistant.local')).toBe(false);
    expect(validHaUrl("http://x'; reboot")).toBe(false);
  });

  it('ha.set_dashboard sends the chosen dashboard label and updates state', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    const st = await c.setDashboard('Tablet');
    expect(st.ha.dashboard).toBe('Tablet');
    expect(app.sent).toEqual([{ id: 1, op: 'ha.set_dashboard', dashboard: 'Tablet' }]);
  });
});

describe('Kiln\'s sign-in window (issue 453)', () => {
  it('goes to the panel\'s sign-in for Kiln, on the address the panel gave', () => {
    expect(kilnSignInUrl('http://192.0.2.10:8099/signin')).toBe('http://192.0.2.10:8099/signin/continue?then=kiln');
  });

  it('opens a pop-up, or a tab where the browser won\'t', () => {
    const win = {} as Window;
    const popup = vi.fn(() => win);
    expect(openSignInWindow(popup)).toBe(win);
    expect(popup).toHaveBeenCalledExactlyOnceWith('about:blank', 'glassworks-signin', expect.stringContaining('popup'));
    const tabOnly = vi.fn((_u: string, target: string) => (target === '_blank' ? win : null));
    expect(openSignInWindow(tabOnly)).toBe(win);
    expect(tabOnly).toHaveBeenLastCalledWith('about:blank', '_blank');
    expect(openSignInWindow(() => null)).toBeNull();
  });
});
