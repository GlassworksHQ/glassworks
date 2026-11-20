// Set-up over adb runs on any device that has Glassworks, with no wizard session around it: Wi-Fi over Improv, then Home
// Assistant over the app's setup channel, then Done. (The wizard's Session runs it for the S1; a device frame's page
// can run it after its own install.)
import { describe, expect, it } from 'vitest';
import { AdbSetUp } from '../src/setup/adbSetUp';
import { simulatedPanel } from '../src/wizard/demo';
import type { Setup } from '../src/wizard/state';
import { demoFrame } from './s1-frame';

function setUp(online: boolean) {
  const panel = simulatedPanel(demoFrame, false, online);
  let setup: Setup = { phase: 'waiting', note: '' };
  let done = false;
  const s = new AdbSetUp({
    panel: () => panel,
    phase: () => setup,
    show: (x) => { setup = x; },
    here: () => true,
    done: () => { done = true; },
    unreachable: (e) => { throw e ?? new Error('unreachable'); },
    app: async () => ({ code: 500 }),
    appName: 'Glassworks',
    wifiName: async () => ({ ssid: 'Home Net' }),
    click: async (_, run) => { await run(); },
    note: () => {},
    lid: () => new AbortController().signal,
    sleep: async () => {},
    openTab: () => null,
    simulated: true,
  });
  return { s, setup: () => setup, done: () => done };
}

describe('set-up over adb, on its own', () => {
  it('a device already on Wi-Fi says so, with its network', async () => {
    const t = setUp(true);
    await t.s.wifi();
    expect(t.setup()).toMatchObject({ phase: 'wifi', on: { ip: '192.0.2.50', ssid: 'Home Net' } });
    expect(t.s.ip).toBe('192.0.2.50');
    t.s.close();
  });

  it('one that isn\'t lists the networks it can see', async () => {
    const t = setUp(false);
    await t.s.wifi();
    await new Promise((r) => setTimeout(r, 1000)); // the simulated scan answers over its own timers
    expect(t.setup()).toMatchObject({ phase: 'wifi' });
    expect((t.setup() as Extract<Setup, { phase: 'wifi' }>).networks?.map((n) => n.ssid)).toContain('Home Net');
    t.s.close();
  });

  it('goes on to Home Assistant over the setup channel, and finish is Done', async () => {
    const t = setUp(true);
    await t.s.homeAssistant();
    expect(t.setup().phase).toBe('ha');
    t.s.finish();
    expect(t.done()).toBe(true);
    t.s.close();
  });
});
