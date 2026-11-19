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

import { frame as s1PlusUi, demoFrame as s1Demo } from '../src/app/aqara-s1-plus/frame';
import { flashDemo } from '../src/mirror/flashDemo';
import { mirrorDemo } from '../src/mirror/demo';
import { Flash, Screens } from '../src/mirror/MirrorWizard';
import { MirrorSession } from '../src/mirror/session';
import { demoSession, enterDemo } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import { Wizard } from '../src/wizard/Wizard';

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
    expect(block()).toBe('Silent updatesOnUpdates from Home Assistant install with nobody at the panel. The root update service in its boot image installs them.');
    // it lands after Done opens, so the live region says it too
    expect(document.querySelector('[aria-live="polite"]')?.textContent).toBe('Done. Silent updates: On.');
  });

  it('Done: a tap each on a boot image from before the service, and how to add it', async () => {
    await s1('done-old-boot');
    expect(block()).toBe('Silent updatesA tap eachThis panel\'s boot image has no root update service, so each update waits for a tap on the panel. Updates still come from Home Assistant. Install again with Kiln to add it.');
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
  it('Done after the fastboot install: On, read back from the image\'s privileged copy', async () => {
    const { flash, mirror } = await flashDemo('flash-done', true);
    render(<Flash flash={flash} mirror={mirror} />);
    expect(block()).toBe('Silent updatesOnUpdates from Home Assistant install with nobody at the Mirror. Glassworks is a system app in the Mirror\'s image, and Android lets it install its own updates.');
  });

  it('Done after the app alone over adb: a normal app, so a click each, and what makes it silent', async () => {
    render(<Screens session={await mirrorDemo('done', true)} />);
    expect(block()).toBe('Silent updatesA click eachGlassworks went in as a normal app, so Android asks before each update, and the Mirror has no touchscreen to answer it. A USB mouse can. Kiln\'s install over fastboot makes it a system app whose updates install by themselves.');
  });

  it('Done after an adb update of the image\'s copy: On', async () => {
    const m = new FakeMirror({ installed: 500, privileged: true });
    const s = new MirrorSession({ connect: async () => m, sleep: async () => {} }, true);
    await s.connect();
    await s.pickApk('generic-android-legacy.apk', fakeMirrorApk());
    await s.install();
    render(<Screens session={s} />);
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
    const s = new MirrorSession({ connect: async () => m, sleep: async () => {} }, true);
    await s.connect();
    await s.pickApk('generic-android-legacy.apk', fakeMirrorApk());
    await s.install();
    render(<Screens session={s} />);
    expect(block()).toBe('Silent updatesNot confirmedKiln couldn\'t read Glassworks back from Android. Glassworks\' Device page on the Mirror says which it is.');
    cleanup();
    render(<Screens session={await mirrorDemo('undo-done', true)} />);
    expect(block()).toBeUndefined();
  });
});
