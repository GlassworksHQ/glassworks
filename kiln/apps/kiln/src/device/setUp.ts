// Set-up over adb on a device frame, after Glassworks went on: the wizard's own set-up (setup/adbSetUp.ts: Wi-Fi, Home
// Assistant, the radio) for both of the page's paths, the image's first start (imageSession.ts) and the app alone
// (appSession.ts). Each session keeps the state below in its own; this wires the buttons, the timeouts and Done.
import type { PanelShell } from '@glassworks/adb';
import { kioskVersion } from '@glassworks/app-adb';
import type { DeviceFrame } from '@glassworks/kiln-core';
import { openSignInWindow } from '@glassworks/setup-channel';
import { INSTALLERS } from '@glassworks/frames/catalog';
import { AdbSetUp } from '../setup/adbSetUp';
import type { Setup } from '../wizard/state';

/** The part of a session's state set-up owns. */
export interface SetUpState {
  /** Set-up's step: Wi-Fi, Home Assistant, the radio. */
  setup: Setup;
  /** the set-up button that is running */
  busy?: string;
  /** the device's Manage page, once set-up armed its pairing (Done) */
  managePage?: string;
}

export const setUpWaiting = (): Setup => ({ phase: 'waiting', note: '' });

export interface SetUpHost {
  frame: DeviceFrame;
  panel(): PanelShell | undefined;
  state(): SetUpState;
  patch(p: Partial<SetUpState>): void;
  /** The set-up screen is the one showing. */
  here(): boolean;
  /** Set-up is over: show Done. */
  toDone(): void;
  /** Done still shows. */
  onDone(): boolean;
  /** The app stopped answering: the error screen, saying why. */
  stopped(message: string): void;
  /** The session's technical log: a step, or a line under it (`step` empty). */
  log(step: string, detail?: string): void;
  sleep?: (ms: number) => Promise<void>;
  openTab?: (url: string) => Window | null;
  /** A simulated device needs no wait for the app's socket. */
  simulated?: boolean;
}

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** A signal that cancels a read-only command after `ms`, so a wedged one is stopped on the device. */
export function lid(frame: Pick<DeviceFrame, 'the'>, sleep: (ms: number) => Promise<void> = wait, ms = 8000): AbortSignal {
  const ac = new AbortController();
  void sleep(ms).then(() => ac.abort(new Error(`${frame.the} did not answer in time`)));
  return ac.signal;
}

/** Set-up for one session. Every button runs through one guard: a second click while one runs does nothing. */
export function deviceSetUp(h: SetUpHost): AdbSetUp {
  const sleep = h.sleep ?? wait;
  let clicking: string | undefined;
  const click = (name: string, run: () => Promise<void> | void): Promise<void> => {
    if (clicking) return Promise.resolve();
    clicking = name;
    h.patch({ busy: name });
    let p: Promise<void>;
    try { p = Promise.resolve(run()); } catch (e) { p = Promise.reject(e); }
    return p.finally(() => { clicking = undefined; h.patch({ busy: undefined }); });
  };
  // Done, then the device's Manage page once it opens pairing (as the wizard's Done)
  const finish = async () => {
    const ip = setUp.steps.ip ?? setUp.ip;
    h.toDone();
    const now = ip ? (await setUp.steps.armPairing())?.ip : undefined;
    setUp.steps.close();
    if (now && h.onDone()) h.patch({ managePage: `http://${now}:8099/` });
  };
  const setUp: AdbSetUp = new AdbSetUp({
    panel: h.panel,
    phase: () => h.state().setup,
    show: (setup) => h.patch({ setup }),
    here: h.here,
    done: () => { void finish(); },
    unreachable: (e) => {
      const err = e instanceof Error ? e : new Error(`${h.frame.app.name} isn't answering. Is it installed and running?`);
      h.log('Stopped', err.message);
      h.stopped(err.message);
    },
    // which Glassworks is on the device decides how a Thread router gets its network (read-only)
    app: async () => {
      const v = await kioskVersion(h.panel()!, lid(h.frame, sleep), h.frame.app);
      return { version: v?.name, code: v?.code };
    },
    appName: h.frame.app.name,
    wifiName: (p, signal) => (h.frame.wifiName ? h.frame.wifiName(p, signal) : Promise.resolve({})),
    click,
    note: (line) => h.log('', line),
    lid: (ms) => lid(h.frame, sleep, ms),
    sleep,
    openTab: (url) => (h.openTab ? h.openTab(url) : openSignInWindow()),
    simulated: h.simulated,
    touch: INSTALLERS[h.frame.id]?.touch !== false,
    appPackage: h.frame.app.package,
  });
  return setUp;
}
