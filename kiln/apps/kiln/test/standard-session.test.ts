// @vitest-environment jsdom
// The standard flow's session (#621) on its own: it follows the frame's declared steps, not only on the rail, and a
// session the registry replaced lets go of a device that answers late.
import type { PanelShell } from '@glassworks/adb';
import { FakeMirror } from '@glassworks/lululemon-mirror-installer/fakes';
import { describe, expect, it, vi } from 'vitest';
import { standardOfDevice, type StandardFrame } from '../src/standard/frame';
import { StandardSession } from '../src/standard/session';
import { withAppStreams } from '../src/wizard/demo';
import { mirrorFrame } from './mirror-frame';

const on = (f: StandardFrame, p: PanelShell) => new StandardSession({
  standard: f, devices: { knownAdb: async () => p, requestAdb: async () => p },
  loadKey: async () => ({ publicKey: 'QAAA kiln@test' }) as never, sleep: async () => {}, simulated: true,
});
const mirror = () => withAppStreams(new FakeMirror({ kiosk: 'none' }), { online: true, radio: false });

describe('the declared steps', () => {
  it('a frame that leaves Wi-Fi out goes from App to Home Assistant', async () => {
    const f = { ...standardOfDevice(mirrorFrame), steps: ['set-up', 'home-assistant'] as StandardFrame['steps'] };
    const p = mirror();
    const s = on(f, p);
    await s.handOver(p);
    expect(s.store.state.setup.phase).toBe('settings');
    await s.installApp({ clock24: true, keyboard: false });
    expect(s.store.state.setup.phase).toBe('ha');
  });

  it('a frame that leaves App and Home Assistant out starts at Wi-Fi, and its Continue goes on to Done', async () => {
    const f = { ...standardOfDevice(mirrorFrame), steps: ['wifi'] as StandardFrame['steps'] };
    const p = mirror();
    const s = on(f, p);
    await s.handOver(p);
    expect(s.store.state.setup.phase).toBe('wifi');
    await s.homeAssistant(); // the Wi-Fi step's Continue
    expect(s.store.state.screen).toBe('done');
  });

  it('a frame with every step keeps them all (the Mirror: no Radio, its hardware has none)', async () => {
    expect(standardOfDevice(mirrorFrame).steps).toEqual(['set-up', 'wifi', 'home-assistant', 'panes']);
  });
});

describe('a session the registry replaced', () => {
  it('lets go of a device that answers after it closed, and doesn\'t carry on', async () => {
    let answer!: (p: PanelShell) => void;
    const close = vi.fn(async () => {});
    const late = Object.assign(mirror(), { close });
    const s = new StandardSession({
      standard: standardOfDevice(mirrorFrame),
      devices: { knownAdb: () => new Promise<PanelShell>((r) => { answer = r; }), requestAdb: () => new Promise(() => {}) },
      loadKey: async () => ({ publicKey: 'QAAA kiln@test' }) as never, sleep: async () => {}, simulated: true,
    });
    const route = s.route('wifi');
    await vi.waitFor(() => expect(answer).toBeDefined());
    s.close();
    answer(late);
    await route;
    expect(close).toHaveBeenCalled();
    expect(s.store.state.setup.phase).toBe('waiting'); // nothing went on to Wi-Fi
  });
});

describe('the App step on an app already on', () => {
  it('a device that stops answering a settings command ends in an error, not a wait for ever', async () => {
    const p = mirror();
    const stuck: PanelShell = { ...p, run: (cmd, signal) => (cmd.startsWith('settings put system time_12_24')
      ? new Promise((_, reject) => signal?.addEventListener('abort', () => reject(signal.reason))) : p.run(cmd, signal)) };
    const s = new StandardSession({
      standard: standardOfDevice(mirrorFrame), devices: { knownAdb: async () => stuck, requestAdb: async () => stuck },
      loadKey: async () => ({ publicKey: 'QAAA kiln@test' }) as never, sleep: async () => {}, simulated: true,
    });
    await s.handOver(stuck);
    await s.installApp({ clock24: true, keyboard: false });
    expect(s.store.state.setup).toMatchObject({ phase: 'settings', error: 'the panel did not answer in time' });
  });
});

describe('Wi-Fi on a device that went off USB', () => {
  it('Try again connects to it again, then asks which Wi-Fi: a frame without touch always has a way on', async () => {
    const close = vi.fn(async () => {});
    // unplugged: every command and stream fails
    const gone: PanelShell & { close(): Promise<void> } = { run: async () => { throw new Error('transferOut: the device was disconnected'); }, push: async () => {}, openStream: async () => { throw new Error('closed'); }, close };
    const back = mirror();
    let known: PanelShell = gone;
    const s = new StandardSession({
      standard: { ...standardOfDevice(mirrorFrame), steps: ['wifi', 'home-assistant'] as StandardFrame['steps'] },
      devices: { knownAdb: async () => known, requestAdb: async () => known },
      loadKey: async () => ({ publicKey: 'QAAA kiln@test' }) as never, sleep: async () => {}, simulated: true,
    });
    await s.handOver(gone);
    expect(s.store.state.setup).toMatchObject({ phase: 'wifi', unreachable: true });
    known = back; // plugged in again: the browser has it
    await s.click('wifi', () => s.wifi()); // the step's Try again
    expect(close).toHaveBeenCalled();
    expect(s.store.state.setup).toMatchObject({ phase: 'wifi' });
    expect((s.store.state.setup as { unreachable?: boolean }).unreachable).toBeFalsy();
  });
});

describe('Try again', () => {
  it('goes back to the set-up step that stopped, not to the App step', async () => {
    const p = withAppStreams(new FakeMirror({ kiosk: 'none' }), { online: true, radio: false, panes: { cards: [{ id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true }] } });
    const s = on(standardOfDevice(mirrorFrame), p);
    await s.handOver(p);
    await s.panes();
    expect(s.store.state.setup.phase).toBe('panes');
    // the app stopped answering there
    (s as unknown as { fail(at: number, e: unknown, installed: boolean): void }).fail(3, new Error('Glassworks isn\'t answering.'), true);
    expect(s.store.state.screen).toBe('error');
    await s.click('resume', () => s.route('resume'));
    expect(s.store.state).toMatchObject({ screen: 'setup', setup: { phase: 'panes' } });
  });
});

describe('Done twice', () => {
  it('a read the earlier Done started doesn\'t land on the later one', async () => {
    const p = mirror();
    let call = 0;
    const f = {
      ...standardOfDevice(mirrorFrame),
      updates: async () => (++call === 1
        ? new Promise<{ state: string; log: string }>((r) => setTimeout(() => r({ state: 'on', log: 'first' }), 50))
        : { state: 'unknown', log: 'second' }),
    };
    const s = on(f, p);
    await s.handOver(p);
    s.finish();
    s.finish();
    await new Promise((r) => setTimeout(r, 100));
    expect(s.store.state.done?.updates).toEqual(f.silent.unknown);
  });
});
