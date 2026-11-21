// @vitest-environment jsdom
// Set-up is the engine's stage, the same for every frame: the standard flow (standard/session.ts), which the S1's wizard
// Session extends and a device frame's page hands its device to. Both build setup/adbSetUp.ts from the frame's
// installer.json, so a failure here is every frame's. The cases come from the Mirror's run on 2026-11-20, which failed at set-up:
// - a fresh flash with Android's Wi-Fi off: the scan found nothing, and nothing turned it on;
// - the app came back after the Wi-Fi join without the sign-in it was handed: set-up must say one clear thing, then
//   ask to sign in, never read the restart as an older app and skip to Done;
// - a frame with no touchscreen was offered "do it on the panel".
// And the Panes step, which every frame's app offers (issue 621): its cards, Continue's panes.set, a refusal.
import { IMPROV_SOCKET } from '@glassworks/app-adb';
import type { PanelShell } from '@glassworks/adb';
import { ENGINE, INSTALLERS } from '@glassworks/frames/catalog';
import { SETUP_SOCKET, type PaneCard } from '@glassworks/setup-channel';
import { cleanup, render } from '@testing-library/react';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { afterEach, describe, expect, it } from 'vitest';
import { standardOfDevice } from '../../src/standard/frame';
import { StandardSession } from '../../src/standard/session';
import { AdbSetUp } from '../../src/setup/adbSetUp';
import { HomeAssistant, Wifi } from '../../src/setup/steps';
import { withAppStreams } from '../../src/wizard/demo';
import { Session } from '../../src/wizard/session';
import type { Setup } from '../../src/wizard/state';
import { android } from '../android-frame';
import { mirrorFrame } from '../mirror-frame';
import { frame as s1Frame } from '../s1-frame';

afterEach(() => cleanup());

const touchOf = (id: string) => INSTALLERS[id]!.touch !== false;
/** Each engine frame's app package, as its session passes it to set-up. */
const PACKAGES: Record<string, string> = { [s1Frame.id]: s1Frame.appId.package, [mirrorFrame.id]: mirrorFrame.app.package, [android.id]: android.apps[0]!.package };

/** Set-up as every session builds it, for frame `id`, on `panel`; `shown` is every state it showed, in order. */
function setUpOn(id: string, panel: PanelShell, simulated = true) {
  const shown: Setup[] = [];
  let setup: Setup = { phase: 'waiting', note: '' };
  const ends = { done: false, error: undefined as unknown };
  const s = new AdbSetUp({
    panel: () => panel,
    phase: () => setup,
    show: (x) => { setup = x; shown.push(x); },
    here: () => true,
    done: () => { ends.done = true; },
    unreachable: (e) => { ends.error = e ?? new Error('unreachable'); },
    app: async () => ({ version: '1.0.0', code: 100 }),
    appName: 'Glassworks',
    wifiName: async () => ({}),
    click: async (_n, run) => { await run(); },
    note: () => {},
    lid: () => new AbortController().signal,
    sleep: async () => {},
    openTab: () => null,
    simulated,
    touch: touchOf(id),
    appPackage: PACKAGES[id],
  });
  return { s, shown, ends, now: () => setup };
}

/** Cards as Glassworks' Panes step sends them: a pane the frame can't run has none. */
const CARDS: PaneCard[] = [
  { id: 'assist', title: 'Voice', detail: 'Assist and timers', on: true },
  { id: 'sendspin', title: 'Music', detail: 'A Sendspin speaker', on: false },
];

/** `p` with every request Kiln writes to the app's setup channel kept in `sent`. */
function recording(p: PanelShell): PanelShell & { sent: { op: string; on?: string }[] } {
  const sent: { op: string; on?: string }[] = [];
  return {
    ...p,
    sent,
    openStream: async (svc) => {
      const io = await p.openStream!(svc);
      if (svc !== SETUP_SOCKET) return io;
      return {
        onData: (cb) => io.onData(cb),
        close: () => io.close(),
        write: (d) => { new TextDecoder().decode(d).split('\n').filter(Boolean).forEach((l) => sent.push(JSON.parse(l))); return io.write(d); },
      };
    },
  };
}

/** A device's shell before Glassworks' streams: records what runs, answers nothing. */
const shell = () => {
  const cmds: string[] = [];
  const p: PanelShell = { run: async (cmd) => { cmds.push(cmd); return { out: '', code: 0 }; }, push: async () => {} };
  return { p, cmds };
};

describe('every session builds set-up from its frame\'s installer.json', () => {
  it('touch, as the frame declares it: the S1 has a touchscreen, the Mirror none', () => {
    const s1 = new Session({
      recipe: s1Plus, frame: s1Frame, requestFel: () => new Promise(() => {}), asset: async () => new Uint8Array(), hasAsset: async () => false,
      backups: { save: async () => {}, original: async () => undefined, files: async () => [] }, loadKey: () => new Promise(() => {}),
      devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) },
    });
    expect((s1 as unknown as { setUp: AdbSetUp }).setUp.touch).toBe(touchOf(s1Frame.id));
    const mirror = new StandardSession({ standard: standardOfDevice(mirrorFrame), devices: { knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) }, loadKey: () => new Promise(() => {}) });
    const mirrorSetUp = (mirror as unknown as { setUp: AdbSetUp }).setUp;
    expect(mirrorSetUp.touch).toBe(touchOf(mirrorFrame.id));
    expect([touchOf(s1Frame.id), touchOf(mirrorFrame.id)]).toEqual([true, false]);
    // and the app's package, for the grant its scan needs
    expect((s1 as unknown as { setUp: { d: { appPackage?: string } } }).setUp.d.appPackage).toBe(s1Frame.appId.package);
    expect((mirrorSetUp as unknown as { d: { appPackage?: string } }).d.appPackage).toBe(mirrorFrame.app.package);
    expect(Object.keys(PACKAGES).sort()).toEqual([...ENGINE].sort());
  });
});

describe.each(ENGINE)('set-up on %s', (id) => {
  it('a scan with Android\'s Wi-Fi off turns it on over adb, then lists the networks', async () => {
    const { p, cmds } = shell();
    const panel = withAppStreams(p, { online: false, wifiOff: true, radio: false });
    const run = panel.run.bind(panel);
    panel.run = async (cmd, signal) => { cmds.push(cmd); return run(cmd, signal); };
    const { s, now } = setUpOn(id, panel);
    await s.wifi();
    const st = now();
    expect(st.phase).toBe('wifi');
    expect(st.phase === 'wifi' && st.networks?.length).toBeGreaterThan(0);
    expect(cmds).toContain('svc wifi enable');
  });

  it('an app without the location grant its scan needs gets it before the scan, which then lists the networks', async () => {
    const { p, cmds } = shell();
    const panel = withAppStreams(p, { online: false, noLocation: true, radio: false });
    const run = panel.run.bind(panel);
    panel.run = async (cmd, signal) => { cmds.push(cmd); return run(cmd, signal); };
    const { s, now } = setUpOn(id, panel);
    await s.wifi();
    const st = now();
    expect(st.phase === 'wifi' && st.networks?.length).toBeGreaterThan(0);
    expect(cmds.filter((c) => c.startsWith('pm grant'))).toEqual([`pm grant ${PACKAGES[id]} android.permission.ACCESS_FINE_LOCATION`]);
  });

  it('Wi-Fi already on: nothing is switched', async () => {
    const { p, cmds } = shell();
    const panel = withAppStreams(p, { online: false, radio: false });
    const run = panel.run.bind(panel);
    panel.run = async (cmd, signal) => { cmds.push(cmd); return run(cmd, signal); };
    await setUpOn(id, panel).s.wifi();
    expect(cmds).not.toContain('svc wifi enable');
  });

  it('the app back from a restart without its sign-in: one waiting state, then the sign-in step, never Done', async () => {
    const { p } = shell();
    const streams = withAppStreams(p, { online: true, radio: false });
    // the app restarting after the join: its setup socket comes back after a few reads, signed out
    let reads = 0;
    const panel: PanelShell = {
      ...streams,
      run: async (cmd, signal) => {
        if (cmd.includes(SETUP_SOCKET.replace('localabstract:', '@')) || cmd.includes(IMPROV_SOCKET.replace('localabstract:', '@'))) return { out: ++reads > 3 ? '1\n' : '0\n', code: 0 };
        return streams.run(cmd, signal);
      },
    };
    const { s, shown, ends, now } = setUpOn(id, panel, false);
    await s.homeAssistant();
    expect(shown[0]).toEqual({ phase: 'waiting', note: 'Waiting for Glassworks to start again…' });
    expect(now()).toMatchObject({ phase: 'ha', ha: { signedIn: false } });
    expect(shown.map((x) => x.phase).filter((x, i, a) => x !== a[i - 1])).toEqual(['waiting', 'ha']); // no bounce
    expect(ends).toEqual({ done: false, error: undefined });
    s.close();
  });

  it('an app that doesn\'t come back within the minute is an error to try again, never Done', async () => {
    const { p } = shell();
    const streams = withAppStreams(p, { online: true, radio: false });
    const panel: PanelShell = { ...streams, run: async (cmd, signal) => (cmd.startsWith('grep -c ') ? { out: '0\n', code: 0 } : streams.run(cmd, signal)) };
    const { s, ends } = setUpOn(id, panel, false);
    const now = Date.now;
    let t = now();
    Date.now = () => (t += 5000); // the minute passes in a few reads
    try { await s.homeAssistant(); } finally { Date.now = now; }
    expect(ends.done).toBe(false);
    expect((ends.error as Error).message).toBe('Glassworks didn\'t start again within a minute. Try again.');
  });

  it('the Panes step: the app\'s cards, and Continue answers them with panes.set (issue 621)', async () => {
    const { p } = shell();
    const panel = recording(withAppStreams(p, { online: true, radio: false, panes: { cards: CARDS } }));
    const { s, ends, now } = setUpOn(id, panel, false);
    await s.panes();
    const st = now();
    expect(st.phase).toBe('panes');
    expect(st.phase === 'panes' && st.panes.map((c) => c.id)).toEqual(CARDS.map((c) => c.id));
    await s.applyPanes(['sendspin']);
    expect(panel.sent.filter((m) => m.op === 'panes.set')).toEqual([expect.objectContaining({ op: 'panes.set', on: 'sendspin' })]);
    expect(ends).toEqual({ done: true, error: undefined }); // no radio on this app: on to Done
    s.close();
  });

  it('a pane that refuses keeps the Panes step, saying why', async () => {
    const { p } = shell();
    const panel = withAppStreams(p, { online: true, radio: false, panes: { cards: CARDS, refuse: 'Music: the player didn\'t start' } });
    const { s, ends, now } = setUpOn(id, panel, false);
    await s.panes();
    await s.applyPanes(['assist', 'sendspin']);
    expect(now()).toMatchObject({ phase: 'panes', busy: false, error: 'Music: the player didn\'t start' });
    expect(ends.done).toBe(false);
    s.close();
  });

  it('an app without the Panes step (before issue 621) has none: on past it, nothing sent', async () => {
    const { p } = shell();
    const panel = recording(withAppStreams(p, { online: true, radio: false }));
    const { s, shown, ends } = setUpOn(id, panel, false);
    await s.panes();
    expect(shown.some((x) => x.phase === 'panes')).toBe(false);
    expect(panel.sent.some((m) => m.op === 'panes.set')).toBe(false);
    expect(ends.done).toBe(true);
    s.close();
  });

  it(touchOf(id) ? 'a frame with touch may send the person to the panel' : 'a frame without touch is never offered "do it on the panel"', () => {
    const { s } = setUpOn(id, withAppStreams(shell().p, { online: false }));
    const screens = [
      <Wifi key="1" busy={false} steps={s} setup={{ phase: 'wifi', networks: [{ ssid: 'Home Net', rssi: -50, secure: true }], status: '1 in range' }} />,
      <Wifi key="2" busy={false} steps={s} setup={{ phase: 'wifi', networks: [], unreachable: true, status: 'Couldn\'t reach Glassworks on the panel.' }} />,
      <Wifi key="3" busy={false} steps={s} setup={{ phase: 'wifi', status: '', joining: { ssid: 'Home Net', step: 'err', error: 'wrong password' } }} />,
      <HomeAssistant key="4" busy={false} steps={s} setup={{ phase: 'ha', ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: '' } }} />,
    ];
    const text = screens.map((x) => { const r = render(x); const t = r.container.textContent ?? ''; r.unmount(); return t; }).join('\n');
    // an offer or an instruction to act on the panel itself, not a status that names it
    const onPanel = /(do it|sign in|set up Wi-Fi) on the panel/;
    if (touchOf(id)) expect(text).toMatch(onPanel);
    else expect(text).not.toMatch(onPanel);
  });
});
