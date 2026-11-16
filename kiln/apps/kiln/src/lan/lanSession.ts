// The panel's own setup page (#34): pairing with the code on the panel's screen, then Kiln's setup steps
// (src/setup) over the panel's /setup/channel WebSocket. The panel serves this page at /setup, so every
// request goes to its own origin.
import { NOT_PAIRED, SetupChannel } from '@glassworks/setup-channel';
import { ChannelSetup, type SetupSteps } from '../setup/channelSetup';
import { THREAD_FROM_HA } from '../setup/radioPlan';
import type { Setup } from '../wizard/state';

/** Where the page is. `pair`: the panel isn't paired with this browser; `open` is false once HA adopted it. */
export type LanView =
  | { at: 'connecting' }
  | { at: 'pair'; open: boolean; shown: boolean; error?: string; note?: string }
  | { at: 'setup'; setup: Setup }
  | { at: 'done' }
  | { at: 'lost'; error: string };

export interface LanState { view: LanView; busy?: string }

/** What SetupChannel reads and writes: the WebSocket, as a byte stream. */
export type ChannelStream = ConstructorParameters<typeof SetupChannel>[0];

export interface LanDeps {
  /** The panel's HTTP endpoints, on this page's origin. */
  fetch(path: string, init?: { method: 'POST'; body?: string }): Promise<{ status: number; json(): Promise<Record<string, unknown>> }>;
  /** Opens the panel's setup channel WebSocket. */
  openStream(): Promise<ChannelStream>;
  openTab?: (url: string) => Window | null;
}

export const PAIR = '/setup/pair';
export const DONE = '/setup/done';

/** The note when the panel ended the pairing under the page: it lasts 30 minutes without a change. */
export const RAN_OUT = 'The pairing ran out: it lasts 30 minutes without a change. Pair again to carry on where you were.';

export class LanSession implements SetupSteps {
  state: LanState = { view: { at: 'connecting' } };
  private listeners = new Set<() => void>();
  private readonly steps: ChannelSetup;
  private running?: string;
  /** The step to go back to after pairing again. */
  private resume?: 'ha' | 'radio';

  constructor(private readonly d: LanDeps) {
    this.steps = new ChannelSetup({
      open: async (tries) => {
        for (let i = 0; i < tries; i++) {
          try {
            const c = new SetupChannel(await d.openStream());
            await c.getState();
            return c;
          } catch { await new Promise((r) => setTimeout(r, 1000)); }
        }
        return undefined;
      },
      phase: () => (this.state.view.at === 'setup' ? this.state.view.setup : { phase: 'waiting', note: '' }),
      show: (setup) => this.showStep(setup),
      here: () => this.state.view.at === 'setup',
      done: () => void this.done(),
      unreachable: (e) => this.set({ view: { at: 'lost', error: e instanceof Error ? e.message : 'The panel stopped answering.' } }),
      // this page comes from the app on the panel, which has everything the Thread step can ask for
      app: async () => ({ code: THREAD_FROM_HA }),
      openTab: d.openTab,
    });
  }

  subscribe = (l: () => void) => { this.listeners.add(l); return () => { this.listeners.delete(l); }; };
  get = () => this.state;
  private set(patch: Partial<LanState>) {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }

  /** A step's state, unless the panel said this browser isn't paired (the pairing ran out): then pair again. */
  private showStep(setup: Setup) {
    if ((setup.phase === 'ha' || setup.phase === 'radio') && setup.error === NOT_PAIRED) {
      this.resume = setup.phase;
      this.steps.close();
      this.set({ view: { at: 'pair', open: true, shown: false, note: RAN_OUT } });
      return;
    }
    this.set({ view: { at: 'setup', setup } });
  }

  get haAddress() { return this.steps.haAddress; }
  set haAddress(v: string | undefined) { this.steps.haAddress = v; }

  click(name: string, run: () => Promise<void> | void): Promise<void> {
    if (this.running) return Promise.resolve();
    this.running = name;
    this.set({ busy: name });
    let p: Promise<void>;
    try { p = Promise.resolve(run()); } catch (e) { p = Promise.reject(e); }
    return p.catch((e) => this.set({ view: { at: 'lost', error: (e as Error).message } })).finally(() => {
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
    if (r.paired === true) return this.begin();
    this.set({ view: { at: 'pair', open: r.open !== false, shown: false } });
  }

  /** Puts a code on the panel's screen. */
  async showCode(): Promise<void> {
    const v = this.state.view;
    if (v.at !== 'pair') return;
    const res = await this.d.fetch(PAIR, { method: 'POST' });
    const r = await res.json();
    if (r.ok === true) this.set({ view: { ...v, shown: true, error: undefined } });
    else this.set({ view: { ...v, open: res.status !== 403, error: String(r.error ?? 'The panel didn\'t show a code.') } });
  }

  /** Checks the code typed; the right one pairs this browser and setup goes on where it was. */
  async enterCode(code: string): Promise<void> {
    const v = this.state.view;
    if (v.at !== 'pair') return;
    const digits = code.replace(/\D/g, '');
    if (digits.length !== 6) { this.set({ view: { ...v, error: 'The code has 6 digits.' } }); return; }
    const res = await this.d.fetch(PAIR, { method: 'POST', body: `code=${digits}` });
    const r = await res.json();
    if (r.ok !== true) {
      // a dead code (used up, run out) needs a new one on the screen
      this.set({ view: { ...v, shown: res.status === 401 && !/last try/.test(String(r.error)), error: String(r.error ?? 'That didn\'t work.') } });
      return;
    }
    await this.begin();
  }

  /** Paired: the channel opens with this browser's session, and the steps start (or carry on). */
  private async begin(): Promise<void> {
    this.steps.close(); // a channel opened before pairing doesn't carry the session
    const back = this.resume;
    this.resume = undefined;
    this.set({ view: { at: 'setup', setup: { phase: 'waiting', note: 'Connecting to the panel.' } } });
    if (back === 'radio') await this.steps.radio();
    else await this.steps.homeAssistant();
  }

  signIn(url: string): void { this.steps.signIn(url); }
  saveHa(url: string): Promise<void> { return this.steps.saveHa(url); }
  radio(): Promise<void> { return this.steps.radio(); }
  applyRadio(mode: string): Promise<void> { return this.steps.applyRadio(mode); }
  finish(): void { void this.done(); }

  /** Setup is over: the panel ends this browser's session, and the page says so. */
  private async done(): Promise<void> {
    this.steps.close();
    await this.d.fetch(DONE, { method: 'POST' }).catch(() => undefined);
    this.set({ view: { at: 'done' } });
  }
}
