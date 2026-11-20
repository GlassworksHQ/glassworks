// @vitest-environment jsdom
// Done's Silent updates block on the S1 and the Mirror (#422): what Kiln read back from the device, in the block
// /android's Done has. The S1 reads its boot image's root update service; the Mirror whether Glassworks is a
// privileged system app holding INSTALL_PACKAGES. A read that fails says so, and never says On.
import { act, cleanup, render, screen } from '@testing-library/react';
import { UPDATE_SERVICE_PROP } from '@glassworks/aqara-s1-plus-installer';
import { GLASSWORKS } from '@glassworks/lululemon-mirror-installer';
import { FakeMirror, fakeMirrorApk } from '@glassworks/lululemon-mirror-installer/fakes';
import type { PanelShell } from '@glassworks/adb';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { frame as s1PlusUi, demoFrame as s1Demo } from './s1-frame';
import { imageDemo } from '../src/device/imageDemo';
import { appDemo } from '../src/device/demo';
import { AppFlow } from '../src/device/DevicePage';
import type { StandardSession } from '../src/standard/session';
import { StandardWizard } from '../src/standard/StandardWizard';
import { demoSession, enterDemo } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import { Wizard } from '../src/wizard/Wizard';
import { appOn, handTo, mirrorEntry } from './mirror-frame';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
const sessions: Session[] = [];
afterEach(() => { cleanup(); for (const s of sessions.splice(0)) s.close(); });

const block = () => document.querySelector('[aria-label="Silent updates"]')?.textContent;
const until = async (what: string, f: () => boolean) => {
  for (let i = 0; i < 500 && !f(); i++) await act(() => new Promise((r) => setTimeout(r, 10)));
  if (!f()) throw new Error(`timed out waiting for ${what}`);
};

describe('the S1', () => {
  const s1 = async (demo: 'done' | 'done-old-boot') => {
    const s = demoSession(s1Demo, demo);
    sessions.push(s);
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => enterDemo(s, s1Demo, demo));
  };

  it('Done: On when the boot image has the root update service', async () => {
    await s1('done');
    expect(block()).toBe('Silent updatesOnUpdates from Home Assistant install by themselves.');
    // it lands after Done opens, so the live region says it too
    expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe('Done. Silent updates: On.');
  });

  it('Done: a tap each on a boot image from before the service, and how to add it', async () => {
    await s1('done-old-boot');
    expect(block()).toBe('Silent updatesA tap eachEach update waits for a tap on the panel. Install again with Kiln to make them silent.');
  });

  /** A set-up session on the simulated panel reaches Done; `prop` is what the panel answers for the update service's prop. */
  const finish = async (prop: PanelShell['run']) => {
    const s = demoSession(s1Demo, 'app');
    sessions.push(s);
    await s.connectPanel();
    const panel = (s as unknown as { panel: PanelShell }).panel;
    const run = panel.run.bind(panel);
    const asked: string[] = [];
    panel.run = async (cmd, signal) => { asked.push(cmd); return cmd === `getprop ${UPDATE_SERVICE_PROP}` ? prop(cmd, signal) : run(cmd, signal); };
    s.finish();
    await until('the read-back', () => !!s.store.state.done?.updates);
    expect(asked).toContain(`getprop ${UPDATE_SERVICE_PROP}`);
    return s.store.state.done!.updates!;
  };

  it('the session reads the prop back when Done opens, on whatever route reached it', async () => {
    expect((await finish(async () => ({ out: '1\n', code: 0 }))).word).toBe('On');
    expect((await finish(async () => ({ out: '\n', code: 0 }))).word).toBe('A tap each');
  });

  it('a read that fails says Not confirmed, never On', async () => {
    expect(await finish(async () => { throw new Error('adb: device offline'); })).toMatchObject({ word: 'Not confirmed', tone: 'n' });
    expect((await finish(async () => ({ out: '', code: 1 }))).word).toBe('Not confirmed');
  });
});

describe('the Mirror', () => {
  /** The standard flow's Done, as the page shows it after the hand-over; the block lands once the read is back. */
  const done = async (ss: StandardSession) => {
    render(<StandardWizard session={ss} base="/lululemon-mirror" before={[]} />);
    await until('Done', () => ss.store.state.screen === 'done' && !!block());
  };

  it('Done after the fastboot install: On, read back from the image\'s privileged copy', async () => {
    const h = handTo();
    await imageDemo(mirrorEntry, 'flash-done', true, {}, h.handOver);
    await done(h.standard());
    expect(block()).toBe('Silent updatesOnUpdates from Home Assistant install by themselves.');
  });

  it('Done after the app alone over adb: a normal app, so a click each, and what makes it silent', async () => {
    const h = handTo();
    await appDemo(mirrorEntry, 'done', true, h.handOver);
    await done(h.standard());
    expect(block()).toBe('Silent updatesA click eachAndroid asks before each update, and the Mirror has no touchscreen to answer: use a USB mouse. An install over fastboot makes updates silent.');
  });

  it('Done after an adb update of the image\'s copy: On', async () => {
    const m = new FakeMirror({ installed: 500, privileged: true });
    const { s, setUp, standard } = appOn(m);
    await s.connect();
    await s.pickApk('generic-android-legacy.apk', fakeMirrorApk());
    await s.install();
    await setUp();
    await done(standard());
    expect(screen.getByRole('group', { name: 'Silent updates' }).textContent).toMatch(/^Silent updatesOn/);
  });

  it('a read that fails says Not confirmed, and undo shows no block', async () => {
    const m = new FakeMirror();
    const run = m.run.bind(m);
    let installed = false;
    m.run = async (cmd: string) => {
      if (cmd.startsWith('pm install')) installed = true;
      if (installed && cmd === `dumpsys package ${GLASSWORKS.package}` && m.cmds.includes(`dpm set-active-admin ${GLASSWORKS.admin}`)) throw new Error('USB transfer failed');
      return run(cmd);
    };
    const { s, setUp, standard } = appOn(m);
    await s.connect();
    await s.pickApk('generic-android-legacy.apk', fakeMirrorApk());
    await s.install();
    await setUp();
    await done(standard());
    expect(block()).toBe('Silent updatesNot confirmedKiln couldn\'t read it back. Glassworks\' Device page on the Mirror says.');
    cleanup();
    render(<AppFlow session={await appDemo(mirrorEntry, 'undo-done', true)} />);
    expect(block()).toBeUndefined();
  });
});
