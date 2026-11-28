// The panel's own setup page (#34): pairing with the code on the panel's screen, then Kiln's setup steps
// (src/setup) over the panel's /setup/channel WebSocket. The panel serves this page at /setup, so every
// request goes to its own origin.
import { NOT_PAIRED, SetupChannel, type SetupState } from '@glassworks/setup-channel';
import { ChannelSetup, type SetupSteps, type WifiSteps } from '../setup/channelSetup';
import { THREAD_FROM_HA } from '../setup/radioPlan';
import type { Setup } from '../wizard/state';

/** Where the page is. `pair`: the panel isn't paired with this browser; `open` is false once HA adopted it. */
export type LanView =
  | { at: 'connecting' }
  | { at: 'pair'; open: boolean; shown: boolean; error?: string; note?: string }
  | { at: 'setup'; setup: Setup }
  | { at: 'done' }
  /** `again`: what Try again does (the start, or ending the session once more). */
  | { at: 'lost'; error: string; again?: 'start' | 'done' };

/** A step on the page's rail. */
export type RailStep = 'pair' | 'wifi' | 'ha' | 'panes' | 'radio' | 'done';

export interface LanState {
  view: LanView;
  busy?: string;
  /** The steps this panel has, from what it reports: a frame without a radio has no radio step. */
  rail: readonly RailStep[];
}

/** The rail before the panel has said whether it has a radio, and for one that has. */
export const FULL_RAIL: readonly RailStep[] = ['pair', 'wifi', 'ha', 'radio', 'done'];

/** The steps a panel that reported [st] has: Panes when its app offers it (issue 621), the radio when it has one. */
export const railOf = (st: SetupState): readonly RailStep[] =>
  ['pair', 'wifi', 'ha', ...(st.panes?.length ? ['panes' as const] : []), ...(st.radio ? ['radio' as const] : []), 'done'];

/** What SetupChannel reads and writes: the WebSocket, as a byte stream. */
export type ChannelStream = ConstructorParameters<typeof SetupChannel>[0];

export interface LanDeps {
  /** The panel's HTTP endpoints, on this page's origin. */
  fetch(path: string, init?: { method: 'POST'; body?: string }): Promise<{ status: number; json(): Promise<Record<string, unknown>> }>;
  /** Opens the panel's setup channel WebSocket. */
  openStream(): Promise<ChannelStream>;
  openTab?: (url: string) => Window | null;
  /** Goes to a page on the panel in this tab (the sign-in, [SIGN_IN_HERE]); without it, sign-in opens a tab. */
  navigate?: (path: string) => void;
  /** This load came back from that sign-in ([BACK_FROM_SIGN_IN] in the address): setup carries on after Home Assistant. */
  backFromSignIn?: boolean;
  /** The pause between tries to open the channel (tests skip it). */
  sleep?: (ms: number) => Promise<void>;
}

export const PAIR = '/setup/pair';
export const DONE = '/setup/done';
/**
 * The panel's sign-in, started by this page in its own tab (issue 453). The panel binds it to this browser and, once
 * signed in, loads this page again with [BACK_FROM_SIGN_IN]. A path on this page's own host, so the pairing's
 * cookie is still there when the browser comes back.
 */
export const SIGN_IN_HERE = '/signin/continue?then=setup';
export const BACK_FROM_SIGN_IN = '#signed-in';

/**
 * Whether this load came back from the panel's sign-in. Takes the mark off the address, so a reload starts where
 * the panel is instead of handing off again.
 */
export function cameBackFromSignIn(loc: Pick<Location, 'hash' | 'pathname' | 'search'>, history: Pick<History, 'replaceState'>): boolean {
  if (loc.hash !== BACK_FROM_SIGN_IN) return false;
  history.replaceState(null, '', loc.pathname + loc.search);
  return true;
}

/** The note when the panel ended the pairing under the page: it lasts 30 minutes without a change. */
export const RAN_OUT = 'Your pairing session expired after 30 minutes of inactivity. Pair again to pick up where you left off.';

export class LanSession implements SetupSteps, WifiSteps {
  state: LanState = { view: { at: 'connecting' }, rail: FULL_RAIL };
  private listeners = new Set<() => void>();
  private readonly steps: ChannelSetup;
  private running?: string;
  /** The step to go back to after pairing again; `signed-in`: the step after Home Assistant. */
  private resume?: 'wifi' | 'ha' | 'panes' | 'radio' | 'signed-in';

  constructor(private readonly d: LanDeps) {
    if (d.backFromSignIn) this.resume = 'signed-in';
    this.steps = new ChannelSetup({
      // This page comes from the app, so it always has a channel: running out of tries is an error here,
      // never the wizard's "older app without one" (which would show Done).
      open: async (tries) => {
        let last: unknown;
        for (let i = 0; i < tries; i++) {
          let c: SetupChannel | undefined;
          try {
            c = new SetupChannel(await d.openStream());
            this.saw(await c.getState());
            return c;
          } catch (e) {
            last = e;
            void c?.close().catch(() => {}); // a socket that opened but didn't answer
            await (d.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms))))(1000);
          }
        }
        throw new Error('Couldn\'t connect to the panel right now. Make sure it is powered on and on this network, then try again.');
      },
      phase: () => (this.state.view.at === 'setup' ? this.state.view.setup : { phase: 'waiting', note: '' }),
      show: (setup) => this.showStep(setup),
      here: () => this.state.view.at === 'setup',
      done: () => this.done(),
      unreachable: (e) => this.set({ view: { at: 'lost', error: e instanceof Error ? e.message : 'Lost connection to the panel. Try again in a moment.' } }),
      // this page comes from the app on the panel, which has everything the Thread step can ask for
      app: async () => ({ code: THREAD_FROM_HA }),
      openTab: d.openTab,
      signInHere: d.navigate && (() => d.navigate!(SIGN_IN_HERE)),
    });
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<LanState>) {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }

  /** The panel's screen takes a tap, as its state says (absent from older apps: it has). */
  touch?: boolean;

  /** A state the panel sent: which steps it has, and whether its screen takes a tap. */
  private saw(st: SetupState) {
    if (st.touch !== undefined && st.touch !== this.touch) { this.touch = st.touch; this.set({}); }
    const rail = railOf(st);
    if (rail.join() !== this.state.rail.join()) this.set({ rail });
  }

  /**
   * Before pairing: the panel's state, only for the rail. The channel answers `state` to anyone; it is let go at
   * once, so it doesn't hold one of the panel's few web workers while the person finds the code.
   */
  private async peek(): Promise<void> {
    let c: SetupChannel | undefined;
    try {
      c = new SetupChannel(await this.d.openStream());
      this.saw(await c.getState());
    } catch { /* the rail stays as it is until the steps open the channel */ } finally {
      void c?.close().catch(() => {});
    }
  }

  /** A step's state, unless the panel said this browser isn't paired (the pairing ran out): then pair again. */
  private showStep(setup: Setup) {
    const ranOut = ((setup.phase === 'ha' || setup.phase === 'panes' || setup.phase === 'radio') && setup.error === NOT_PAIRED) ||
      (setup.phase === 'wifi' && (setup.status === NOT_PAIRED || setup.joining?.error === NOT_PAIRED));
    if (ranOut) {
      this.resume = setup.phase;
      this.steps.close();
      this.set({ view: { at: 'pair', open: true, shown: false, note: RAN_OUT } });
      return;
    }
    this.set({ view: { at: 'setup', setup } });
  }

  get panesDraft() { return this.steps.panesDraft; }
  set panesDraft(v: Record<string, boolean> | undefined) { this.steps.panesDraft = v; }
  get haAddress() { return this.steps.haAddress; }
  set haAddress(v: string | undefined) { this.steps.haAddress = v; }

  click(name: string, run: () => Promise<void> | void): Promise<void> {
    if (this.running) return Promise.resolve();
    this.running = name;
    this.set({ busy: name });
    let p: Promise<void>;
    try { p = Promise.resolve(run()); } catch (e) { p = Promise.reject(e); }
    return p.catch((e) => this.lose(e)).finally(() => {
      this.running = undefined;
      this.set({ busy: undefined });
    });
  }

  /** Where this browser stands: paired (go on to the steps) or not (pair first). */
  async start(): Promise<void> {
    this.set({ view: { at: 'connecting' } });
    let r: Record<string, unknown>;
    try { r = await (await this.d.fetch(PAIR)).json(); } catch {
      this.set({ view: { at: 'lost', error: 'The panel didn\'t answer. Is it on, and on this network?' } });
      return;
    }
    if (r.paired === true) return this.begin().catch((e) => this.lose(e));
    this.set({ view: { at: 'pair', open: r.open !== false, shown: false } });
    void this.peek();
  }

  /** Puts a code on the panel's screen. */
  async showCode(): Promise<void> {
    const v = this.state.view;
    if (v.at !== 'pair') return;
    const res = await this.d.fetch(PAIR, { method: 'POST' });
    const r = await res.json();
    if (r.ok === true) this.set({ view: { ...v, shown: true, error: undefined } });
    else this.set({ view: { ...v, open: res.status !== 403, error: String(r.error ?? 'The panel couldn\'t show a pairing code right now. Try again.') } });
  }

  /** Checks the code typed; the right one pairs this browser and setup goes on where it was. */
  async enterCode(code: string): Promise<void> {
    const v = this.state.view;
    if (v.at !== 'pair') return;
    const digits = code.replace(/\D/g, '');
    if (digits.length !== 6) { this.set({ view: { ...v, error: 'Enter the 6-digit code shown on the panel.' } }); return; }
    const res = await this.d.fetch(PAIR, { method: 'POST', body: `code=${digits}` });
    const r = await res.json();
    if (r.ok !== true) {
      // a dead code (used up, run out) needs a new one on the screen
      this.set({ view: { ...v, shown: res.status === 401 && !/last try/.test(String(r.error)), error: String(r.error ?? 'That code wasn\'t accepted. Try again.') } });
      return;
    }
    await this.begin().catch((e) => this.lose(e));
  }

  private lose(e: unknown, again: 'start' | 'done' = 'start') {
    this.set({ view: { at: 'lost', error: e instanceof Error ? e.message : String(e), again } });
  }

  /** Try again on the error card: start over, or end the session once more. */
  retry(): Promise<void> {
    const v = this.state.view;
    return v.at === 'lost' && v.again === 'done' ? this.done() : this.start();
  }

  /** Paired: the channel opens with this browser's session, and the steps start (or carry on). */
  private async begin(): Promise<void> {
    this.steps.close(); // a channel opened before pairing doesn't carry the session
    const back = this.resume;
    this.set({ view: { at: 'setup', setup: { phase: 'waiting', note: 'Connecting to the panel.' } } });
    if (back === 'signed-in') await this.steps.afterSignIn();
    else if (back === 'panes') await this.steps.panes();
    else if (back === 'radio') await this.steps.radio();
    else if (back === 'ha') await this.steps.homeAssistant();
    else await this.steps.wifi(); // an offline panel (on its setup hotspot) starts with Wi-Fi, one on a network with HA
    // kept until the step opened: Try again after a lost panel goes back there, not to the start
    if (this.resume === back && this.state.view.at !== 'lost') this.resume = undefined;
  }

  wifi(): Promise<void> { return this.steps.wifi(); }
  scanWifi(): Promise<void> { return this.steps.scanWifi(); }
  joinWifi(ssid: string, psk: string): Promise<void> { return this.steps.joinWifi(ssid, psk); }
  homeAssistant(): Promise<void> { return this.steps.homeAssistant(); }
  signIn(url: string): void { this.steps.signIn(url); }
  saveHa(url: string, dashboard?: string): Promise<void> { return this.steps.saveHa(url, dashboard); }
  skipHa(url: string): Promise<void> { return this.steps.skipHa(url); }
  panes(): Promise<void> { return this.steps.panes(); }
  applyPanes(on: readonly string[]): Promise<void> { return this.steps.applyPanes(on); }
  radio(): Promise<void> { return this.steps.radio(); }
  applyRadio(mode: string): Promise<void> { return this.steps.applyRadio(mode); }
  /** Skip and Finish: the button stays busy until the panel has ended the pairing (or said it couldn't). */
  finish(): Promise<void> { return this.done(); }

  /** Setup is over: the panel ends this browser's session, and only then does the page say Done. */
  private async done(): Promise<void> {
    this.steps.close();
    try {
      const res = await this.d.fetch(DONE, { method: 'POST' });
      if (res.status !== 200 || (await res.json()).ok !== true) throw new Error('the panel didn\'t confirm');
    } catch {
      this.lose(new Error('Setup is saved, but the panel didn\'t finish closing this browser\'s pairing session. Click Try again to close it cleanly.'), 'done');
      return;
    }
    this.set({ view: { at: 'done' } });
  }
}
