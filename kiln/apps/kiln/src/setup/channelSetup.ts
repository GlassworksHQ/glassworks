// The setup steps that run over the app's setup channel: Home Assistant (the address, sign-in on the
// panel's own page) and the radio role. The same code whether the channel comes over USB (Kiln's wizard,
// through adb) or the LAN (the panel's own page, #34): only `open` differs.
import { kilnSignInUrl, NOT_PAIRED, NotSent, openSignInWindow, Refused, validHaUrl, type SetupChannel, type SetupState, type WifiNetwork } from '@glassworks/setup-channel';
import type { Setup } from '../wizard/state';
import { radioPlan, type PanelApp } from './radioPlan';

export interface ChannelSetupDeps {
  /** Opens the app's setup channel, trying `tries` times; undefined when the panel has none (an older app). */
  open(tries: number): Promise<SetupChannel | undefined>;
  /** The step on screen now. */
  phase(): Setup;
  show(setup: Setup): void;
  /** Whether the app offers setup's Panes step: said each time its setup state comes, so the rail can list it. */
  panesOffered?(offered: boolean): void;
  /** Whether the frame has this step (its data, standard/frame.ts): one it leaves out is passed over. Unset: every one. */
  has?(step: 'home-assistant' | 'panes' | 'radio'): boolean;
  /** Still on the setup screen: an answer that comes after the person moved on changes nothing. */
  here(): boolean;
  /** Setup is over (Finish, Skip, or an older app that has no channel). A promise keeps the button busy until it settles. */
  done(): void | Promise<void>;
  /** The app stopped answering at the radio step: why, or undefined when it has no channel at all. */
  unreachable(e?: unknown): void;
  /** The installed app, which decides how a Thread router gets its network. No answer counts as an older app. */
  app(): Promise<PanelApp | undefined>;
  /** Opens the sign-in's window (a pop-up, else a tab); must run inside the click (pop-up blockers). */
  openTab?: (url: string) => Window | null;
  /**
   * Signs in in this tab instead: the panel's own page goes to the panel's sign-in, which brings the browser back to
   * it after Home Assistant (issue 453). Kiln's wizard leaves it out: leaving its page would drop the USB connection.
   */
  signInHere?: () => void;
}

/** What the step screens call: the wizard's Session, or the panel page's own controller. */
export interface SetupSteps {
  /** The device has a touchscreen; without one (false), no step offers "do it on the panel". Unset: it has. */
  readonly touch?: boolean;
  /** The device as the steps name it (its frame's `the`); unset: "the panel". */
  readonly the?: string;
  /** Runs a button's work, one at a time. */
  click(name: string, run: () => Promise<void> | void): Promise<void>;
  /** The Home Assistant address as the person typed it, shown again if they come back to the step. */
  haAddress?: string;
  /** The Panes switches as the person set them, kept if the step's screen goes and comes back (pairing again). */
  panesDraft?: Record<string, boolean>;
  signIn(url: string): void;
  saveHa(url: string, dashboard?: string): Promise<void>;
  /** Skip the sign-in (the panel asks for it on its own screen): keeps a valid address, then moves on. */
  skipHa(url: string): Promise<void>;
  /** The Panes step, when the app offers it; else on to the radio. */
  panes(): Promise<void>;
  /** Continue on Panes: the cards whose ids are in `on` on, every other off. */
  applyPanes(on: readonly string[]): Promise<void>;
  radio(): Promise<void>;
  applyRadio(mode: string): Promise<void>;
  /** Ends setup; a promise keeps the button that ran it busy until it settles. */
  finish(): void | Promise<void>;
}

/** What the Wi-Fi step calls: the wizard's Session (Improv Serial over USB), or the panel page's controller. */
export interface WifiSteps {
  /** As SetupSteps `touch`. */
  readonly touch?: boolean;
  /** As SetupSteps `the`. */
  readonly the?: string;
  click(name: string, run: () => Promise<void> | void): Promise<void>;
  /** The step from the start (Try again). */
  wifi(): Promise<void>;
  /** Looks for networks again ("Use another network"). */
  scanWifi(): Promise<void>;
  /** Sends the network ("" for an open one). */
  joinWifi(ssid: string, psk: string): Promise<void>;
  /** On to Home Assistant: Continue, or skip Wi-Fi. */
  homeAssistant(): Promise<void>;
}

/**
 * The panel's page once the network is sent: the join closes the panel's setup Wi-Fi, and this page's connection with
 * it, within moments, so this is the page's last word (issue 460).
 */
export const joinNote = (ssid: string) =>
  `The panel is switching to ${ssid} now. This page can't follow it across networks, so continue on the panel's screen and reconnect this device to your regular Wi-Fi.`;

/** How long after a join the page asks whether the panel is still on its setup Wi-Fi (the join didn't happen). */
export const JOIN_CHECK_MS = 45_000;

/** The panel answered [JOIN_CHECK_MS] after a join: its setup Wi-Fi is still up, so it didn't join. */
export const stillOnHotspot = (ssid: string) =>
  `The panel still has its setup Wi-Fi up, so it didn't join ${ssid}. Check the network and password, and try again.`;

const NOT_AN_ADDRESS = 'That doesn\'t look like a web address (http://… or https://…).';
const hasDashboardChoice = (ha: SetupState['ha']) => (ha.dashboards?.length ?? 0) > 1;

export class ChannelSetup {
  haAddress?: string;
  panesDraft?: Record<string, boolean>;
  /** The panel's address on Wi-Fi, from the last state it sent. */
  ip?: string;
  private channel?: SetupChannel;
  private haTimer?: ReturnType<typeof setInterval>;
  private joinTimer?: ReturnType<typeof setTimeout>;
  /** Watches Kiln's sign-in window: one closed without a sign-in gives Sign in back. */
  private windowTimer?: ReturnType<typeof setInterval>;
  /** Counts close()s: an open that close() overtook is closed, never kept or shown. */
  private gen = 0;

  constructor(private readonly d: ChannelSetupDeps) {}

  /** The open channel, opened now if it isn't; undefined when the panel has none. */
  async connect(tries = 5): Promise<SetupChannel | undefined> {
    if (this.channel) return this.channel;
    const g = this.gen;
    const c = await this.d.open(tries);
    if (g !== this.gen) { void c?.close().catch(() => {}); return undefined; }
    this.channel ??= c;
    return this.channel;
  }

  /**
   * Runs a request. Saving a new Home Assistant address can restart the app, so when the panel doesn't answer,
   * reconnect once and retry. A refusal is an answer: it goes straight to the step (over the LAN, a second
   * WebSocket would only hear "pair first" again).
   */
  async request<T>(f: (c: SetupChannel) => Promise<T>): Promise<T> {
    try {
      return await f(this.channel!);
    } catch (first) {
      if (first instanceof Refused) throw first;
      const g = this.gen;
      const old = this.channel;
      const again = await this.d.open(15);
      if (g !== this.gen) { void again?.close().catch(() => {}); throw first; }
      if (!again) throw first;
      if (old?.onState) again.onState = old.onState;
      void old?.close().catch(() => {});
      this.channel = again;
      return f(again);
    }
  }

  /** The panel's setup state now, or undefined when it can't say. Keeps its address. */
  async state(): Promise<SetupState | undefined> {
    if (!(await this.connect())) return undefined;
    const st = await this.request((x) => x.getState()).catch(() => undefined);
    if (st?.ip) this.ip = st.ip;
    if (st) this.offers(st);
    return st;
  }

  /** Tells the session whether the app offers the Panes step, from a state it sent. */
  private offers(st: SetupState): void { this.d.panesOffered?.(!!st.panes?.length); }

  /**
   * Opens pairing over the LAN for 30 minutes (`pairing.arm`, #34) on the channel already open, and answers with the
   * panel's state as it replied: its address now. Undefined when there is no channel, or the app doesn't have it (an
   * older app answers "Unknown request"): then it has no setup page either.
   */
  async armPairing(): Promise<SetupState | undefined> {
    if (!this.channel) return undefined;
    return this.channel.armPairing().catch(() => undefined);
  }

  /** Stops the poll and lets the channel go; the next step opens it again. */
  close(): void {
    this.gen++;
    clearInterval(this.haTimer);
    clearTimeout(this.joinTimer);
    clearInterval(this.windowTimer);
    void this.channel?.close().catch(() => {});
    this.channel = undefined;
  }

  private haState(s: SetupState, extra: { note?: string; error?: string; busy?: boolean; signingIn?: boolean } = {}) {
    this.ip = s.ip ?? this.ip;
    this.offers(s);
    this.d.show({ phase: 'ha', ha: s.ha, ...extra });
  }

  /** Home Assistant: the address (what the panel found), sign-in on the panel's own page. */
  async homeAssistant(): Promise<void> {
    if (this.d.has?.('home-assistant') === false) return this.panes();
    const g = this.gen;
    const c = await this.connect();
    if (g !== this.gen) return;
    if (!c) return await this.d.done(); // older app: the panel's own setup does the rest
    c.onState = (s) => {
      const cur = this.d.phase();
      // a state that comes once set-up is over (Done, another screen) changes nothing
      if (cur.phase !== 'ha' || !this.d.here()) return;
      // A sign-in this page started has finished in its own window: stay to pick a dashboard when custom ones exist, else move on with no click.
      if (cur.signingIn && s.ha.signedIn) {
        this.haState(s);
        if (!hasDashboardChoice(s.ha)) void this.panes();
        return;
      }
      this.haState(s, { note: cur.note, signingIn: cur.signingIn });
    };
    this.haState(c.state!);
    clearInterval(this.haTimer);
    this.haTimer = setInterval(() => {
      if (!this.d.here() || this.d.phase().phase !== 'ha') { clearInterval(this.haTimer); return; }
      void this.request((x) => x.getState()).catch(() => {});
    }, 3000);
  }

  /**
   * Wi-Fi over the channel: the panel's own page, reached on its setup hotspot (issue 197). A panel already on a network,
   * or an app that doesn't say, goes on to Home Assistant: the panel refuses a join while it is on a network.
   */
  async wifi(): Promise<void> {
    const g = this.gen;
    const c = await this.connect();
    if (g !== this.gen) return;
    if (!c) { this.d.unreachable(); return; }
    if (c.state?.online !== false) { await this.homeAssistant(); return; }
    await this.scanWifi();
  }

  /** The networks the panel sees, from a fresh scan. */
  async scanWifi(): Promise<void> {
    const looking = { phase: 'wifi' as const, over: 'lan' as const, status: 'Looking for networks the panel can see…' };
    this.d.show(looking);
    let networks: WifiNetwork[] = [];
    try { networks = await this.request((x) => x.scanWifi()); } catch (e) {
      if (!(e instanceof Refused)) { this.d.unreachable(e); return; }
      if (e.message === NOT_PAIRED) { this.d.show({ ...looking, status: e.message }); return; }
    }
    if (!this.d.here() || this.d.phase().phase !== 'wifi') return;
    this.d.show({ ...looking, networks, status: networks.length ? `${networks.length} in range of the panel` : 'No networks found. Choose manual entry to type a network name.' });
  }

  /**
   * Sends the network. The panel answers, then joins: on its setup hotspot that closes this page's connection, so the
   * page doesn't wait for the outcome, and a connection that drops right after sending counts as sent.
   */
  async joinWifi(ssid: string, psk: string): Promise<void> {
    const s = this.d.phase();
    if (s.phase !== 'wifi') return;
    if (!ssid) { this.d.show({ ...s, joining: { ssid, step: 'err', error: 'Choose a network or enter its name.' } }); return; }
    this.d.show({ ...s, joining: { ssid, step: 'sent' } });
    try {
      await this.channel!.joinWifi(ssid, psk);
    } catch (e) {
      if (e instanceof Refused) { this.d.show({ ...s, joining: { ssid, step: 'err', error: e.message } }); return; }
      // Never written: the panel has nothing, so this is an error to try again from, not a join under way.
      if (e instanceof NotSent) { this.d.show({ ...s, joining: { ssid, step: 'err', error: 'Lost connection to the panel before sending the network. Try again.' } }); return; }
      // Written, but no answer: the panel may have closed its hotspot as it joined
    }
    this.d.show({ ...s, joining: { ssid, step: 'join' }, joinNote: joinNote(ssid) });
    // The channel stays open: if the panel still answers a while later, its setup Wi-Fi never closed and the join
    // didn't happen, and the page says so with Try again instead of "Joining…" forever (issue 418 verifier).
    const g = this.gen;
    clearTimeout(this.joinTimer);
    this.joinTimer = setTimeout(() => void this.checkJoin(ssid, g), JOIN_CHECK_MS);
  }

  /** [JOIN_CHECK_MS] after a join: no answer is the expected way (the panel left), an answer means it didn't join. */
  private async checkJoin(ssid: string, g: number): Promise<void> {
    const waiting = () => {
      const cur = this.d.phase();
      return g === this.gen && this.d.here() && cur.phase === 'wifi' && cur.joining?.step === 'join' && cur.joining.ssid === ssid ? cur : undefined;
    };
    if (!waiting()) return;
    const st = await this.channel?.getState().catch(() => undefined);
    const cur = waiting();
    if (!cur) return;
    if (!st || st.online) { this.close(); return; } // gone (it is joining), or on a network after all
    this.d.show({ ...cur, joinNote: undefined, joining: { ssid, step: 'err', error: stillOnHotspot(ssid) } });
  }

  /**
   * Sign in, once the address is saved: in this tab where [ChannelSetupDeps.signInHere] can, else in a window that
   * opens inside the click (Kiln) and is pointed at the panel's sign-in. That window closes itself at the end, and
   * this page moves on when the panel reports the sign-in ([homeAssistant]'s state).
   */
  signIn(url: string): void {
    const s = this.d.phase();
    if (s.phase !== 'ha') return;
    if (!validHaUrl(url)) { this.haState({ ...this.channel!.state!, ha: s.ha }, { error: NOT_AN_ADDRESS }); return; }
    const here = this.d.signInHere;
    const tab = here ? null : (this.d.openTab ?? (() => openSignInWindow()))('about:blank');
    const note = here ? 'Opening Home Assistant sign-in. You\'ll return here automatically once you\'re signed in.' : 'Sign in to Home Assistant in the window that opened. This step moves on by itself once the panel is signed in.';
    this.d.show({ ...s, error: undefined, signingIn: true, note });
    const g = this.gen;
    // The person may have moved on (or the session closed) while the address was saved: then the sign-in is dropped,
    // so it neither leaves this page nor points a window at the panel.
    const still = () => {
      const cur = this.d.phase();
      return g === this.gen && this.d.here() && cur.phase === 'ha' && !!cur.signingIn;
    };
    void (async () => {
      try {
        const st = url === this.channel?.state?.ha.url ? this.channel.state : await this.request((x) => x.setHa(url));
        if (!still()) { tab?.close(); return; }
        if (here) here();
        else {
          // The panel's own page (above) signs in on its own address and needs no `signinUrl`, which the panel leaves
          // out when it has no Wi-Fi IPv4 address. Kiln's window needs it: it is Kiln's only way there.
          if (!st.ha.signinUrl) throw new Error('The panel doesn\'t have a sign-in page ready yet. Make sure it is connected to Wi-Fi and try again.');
          const to = kilnSignInUrl(st.ha.signinUrl);
          const w = tab ?? window.open(to, '_blank');
          if (!w) throw new Error('The browser blocked the sign-in window. Allow pop-ups for this page, then sign in again.');
          if (tab) tab.location.href = to;
          this.watchWindow(w);
        }
      } catch (e) {
        tab?.close();
        const cur = this.d.phase();
        if (cur.phase === 'ha') this.d.show({ ...cur, note: undefined, signingIn: false, error: (e as Error).message });
      }
    })();
  }

  async saveHa(url: string, dashboard?: string): Promise<void> {
    const s = this.d.phase();
    if (s.phase !== 'ha') return;
    const urlChanged = !!url && url !== this.channel?.state?.ha.url;
    const currentDashboard = this.channel?.state?.ha.dashboard ?? 'Default';
    const dashboardChanged = !!dashboard && dashboard !== currentDashboard && dashboard !== 'Custom';
    if (urlChanged) {
      if (!validHaUrl(url)) { this.d.show({ ...s, error: NOT_AN_ADDRESS }); return; }
      this.d.show({ ...s, busy: true, error: undefined });
      try { await this.request((x) => x.setHa(url)); } catch (e) { this.d.show({ ...s, busy: false, error: (e as Error).message }); return; }
    }
    if (dashboardChanged && (!urlChanged || this.channel?.state?.ha.dashboards?.includes(dashboard))) {
      const cur = this.d.phase();
      if (cur.phase === 'ha') this.d.show({ ...cur, busy: true, error: undefined });
      try { await this.request((x) => x.setDashboard(dashboard)); } catch (e) {
        const now = this.d.phase();
        if (now.phase === 'ha') this.d.show({ ...now, busy: false, error: (e as Error).message });
        return;
      }
    }
    await this.panes();
  }

  /**
   * Back on the page from the panel's sign-in (issue 453): on to the step after Home Assistant when the panel is signed in,
   * else Home Assistant again. What the page knew went with the navigation; the panel's state has it.
   */
  /**
   * Sign in waits while Kiln's sign-in window is open. Once it closes (the panel's last page closed it, or the person
   * did), the panel says which: signed in moves on ([homeAssistant]'s state), anything else gives Sign in back.
   */
  private watchWindow(w: Window): void {
    const g = this.gen;
    clearInterval(this.windowTimer);
    this.windowTimer = setInterval(() => {
      const waiting = () => {
        const cur = this.d.phase();
        return g === this.gen && this.d.here() && cur.phase === 'ha' && cur.signingIn ? cur : undefined;
      };
      if (!waiting()) { clearInterval(this.windowTimer); return; }
      if (!w.closed) return;
      clearInterval(this.windowTimer);
      void this.request((x) => x.getState()).catch(() => undefined).then((st) => {
        const cur = waiting();
        if (!cur || st?.ha.signedIn) return;
        this.d.show({ ...cur, signingIn: false, note: 'The sign-in window closed before the panel was signed in. Sign in again.' });
      });
    }, 1000);
  }

  async afterSignIn(): Promise<void> {
    const g = this.gen;
    const st = await this.state().catch(() => undefined);
    if (g !== this.gen) return; // closed meanwhile: this run is over
    if (st?.ha.signedIn && !hasDashboardChoice(st.ha)) await this.panes();
    else await this.homeAssistant();
  }

  /** Moves on without signing in: a valid address is saved as Continue would; anything else is left for the panel. */
  async skipHa(url: string): Promise<void> {
    if (!validHaUrl(url)) return this.panes();
    return this.saveHa(url);
  }

  /**
   * The Panes step: a switch per card the app offers, as the panel's own Panes step has (issue 621). An app whose
   * state has no `panes` has no such step: on to the radio, as before.
   */
  async panes(): Promise<void> {
    if (this.d.has?.('panes') === false) return this.radio();
    // the state the channel has, if it has one: an app without the step (every app today) asks nothing more here
    const known = this.channel?.state;
    if (known && !known.panes?.length) { await this.radio(); return; }
    const g = this.gen;
    const stale = () => g !== this.gen || !this.d.here();
    if (!(await this.connect())) { if (!stale()) this.d.unreachable(); return; }
    let st: SetupState;
    try {
      st = await this.request((x) => x.getState());
    } catch (e) {
      if (!stale()) this.d.unreachable(e);
      return;
    }
    if (stale()) return;
    this.ip = st.ip ?? this.ip;
    this.offers(st);
    if (!st.panes?.length) { await this.radio(); return; }
    this.d.show({ phase: 'panes', panes: st.panes });
  }

  /** Continue on Panes. The radio's own step follows only while its card is on; a refusal stays on Panes, saying why. */
  async applyPanes(on: readonly string[]): Promise<void> {
    const s = this.d.phase();
    if (s.phase !== 'panes') return;
    const stale = () => !this.d.here() || this.d.phase().phase !== 'panes';
    this.d.show({ ...s, busy: true, error: undefined });
    let st: SetupState;
    try {
      st = await this.request((x) => x.setPanes(on));
    } catch (e) {
      if (!stale()) this.d.show({ ...s, busy: false, error: (e as Error).message });
      return;
    }
    if (stale()) return;
    this.panesDraft = undefined; // applied: the app's state says it now
    const radioCard = st.panes?.find((p) => p.id === 'radio');
    if (radioCard && !radioCard.on) { await this.d.done(); return; }
    await this.radio();
  }

  /** The radio role. */
  async radio(): Promise<void> {
    if (this.d.has?.('radio') === false) { await this.d.done(); return; }
    const g = this.gen;
    // closed (or replaced) while waiting on the panel: that answer, or its failure, is no one's to show
    const stale = () => g !== this.gen || !this.d.here();
    if (!(await this.connect())) { if (!stale()) this.d.unreachable(); return; }
    let st: SetupState;
    try {
      st = await this.request((x) => x.getState());
    } catch (e) {
      if (!stale()) this.d.unreachable(e); // e.g. the app restarted and didn't come back: say so, not "Connecting…" forever
      return;
    }
    if (stale()) return;
    this.ip = st.ip ?? this.ip; // also when setup starts here (#/radio): Done's hand-off needs it
    // A frame without a radio (any Android device) has no radio step: straight to Done.
    if (!st.radio) { await this.d.done(); return; }
    this.d.show({
      phase: 'radio', radio: st.radio,
      note: st.radio.helper ? `Current status: ${st.radio.status}` : 'Only Off is available until the panel\'s system software is updated with Kiln.',
    });
  }

  async applyRadio(mode: string): Promise<void> {
    const s = this.d.phase();
    if (s.phase !== 'radio') return;
    const was = this.channel!.state!.radio;
    // the answer can come after the person has moved on (Done, another route): then it changes nothing here
    const stale = () => !this.d.here() || this.d.phase().phase !== 'radio';
    // keeping the mode still answers the panel's radio step (a fresh panel left on Off); an app that
    // doesn't say whether it's answered restarts the radio on a same-mode request, so it's left alone
    if (mode !== was.mode || was.answered === false) {
      this.d.show({ ...s, busy: true, note: mode !== was.mode ? 'Switching the radio…' : s.note, error: undefined });
      try {
        const r = await this.request((x) => x.setRadio(mode));
        if (stale()) return;
        this.d.show({ ...s, radio: r.radio, busy: false, note: r.radio.status });
      } catch (e) {
        if (stale()) return;
        this.d.show({ ...s, busy: false, error: (e as Error).message });
        return;
      }
    }
    const st = this.channel!.state!;
    const app = mode === 'thread_router' ? await this.d.app().catch(() => undefined) : undefined;
    if (stale()) return;
    const plan = radioPlan(mode, st.ip ?? this.ip ?? '<panel IP>', st.ha.url ?? '', st.radio.allowed, app ?? {});
    if (plan) { this.d.show({ phase: 'radio-next', plan }); return; }
    await this.d.done();
  }
}
