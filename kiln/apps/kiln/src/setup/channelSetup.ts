// The setup steps that run over the app's setup channel: Home Assistant (the address, sign-in on the
// panel's own page) and the radio role. The same code whether the channel comes over USB (Kiln's wizard,
// through adb) or the LAN (the panel's own page, #34): only `open` differs.
import { validHaUrl, type SetupChannel, type SetupState } from '@glassworks/setup-channel';
import type { Setup } from '../wizard/state';
import { radioPlan, type PanelApp } from './radioPlan';

export interface ChannelSetupDeps {
  /** Opens the app's setup channel, trying `tries` times; undefined when the panel has none (an older app). */
  open(tries: number): Promise<SetupChannel | undefined>;
  /** The step on screen now. */
  phase(): Setup;
  show(setup: Setup): void;
  /** Still on the setup screen: an answer that comes after the person moved on changes nothing. */
  here(): boolean;
  /** Setup is over (Finish, Skip, or an older app that has no channel). */
  done(): void;
  /** The app stopped answering at the radio step: why, or undefined when it has no channel at all. */
  unreachable(e?: unknown): void;
  /** The installed app, which decides how a Thread router gets its network. No answer counts as an older app. */
  app(): Promise<PanelApp | undefined>;
  /** Opens a tab; must run inside the click (popup blockers). */
  openTab?: (url: string) => Window | null;
}

/** What the step screens call: the wizard's Session, or the panel page's own controller. */
export interface SetupSteps {
  /** Runs a button's work, one at a time. */
  click(name: string, run: () => Promise<void> | void): Promise<void>;
  /** The Home Assistant address as the person typed it, shown again if they come back to the step. */
  haAddress?: string;
  signIn(url: string): void;
  saveHa(url: string): Promise<void>;
  radio(): Promise<void>;
  applyRadio(mode: string): Promise<void>;
  finish(): void;
}

const NOT_AN_ADDRESS = 'That doesn\'t look like a web address (http://… or https://…).';

export class ChannelSetup {
  haAddress?: string;
  /** The panel's address on Wi-Fi, from the last state it sent. */
  ip?: string;
  private channel?: SetupChannel;
  private haTimer?: ReturnType<typeof setInterval>;

  constructor(private readonly d: ChannelSetupDeps) {}

  /** The open channel, opened now if it isn't; undefined when the panel has none. */
  async connect(tries = 5): Promise<SetupChannel | undefined> {
    this.channel ??= await this.d.open(tries);
    return this.channel;
  }

  /** Runs a request; saving a new Home Assistant address can restart the app, so reconnect once and retry. */
  async request<T>(f: (c: SetupChannel) => Promise<T>): Promise<T> {
    try {
      return await f(this.channel!);
    } catch (first) {
      const onState = this.channel?.onState;
      const again = await this.d.open(15);
      if (!again) throw first;
      if (onState) again.onState = onState;
      this.channel = again;
      return f(again);
    }
  }

  /** The panel's setup state now, or undefined when it can't say. Keeps its address. */
  async state(): Promise<SetupState | undefined> {
    if (!(await this.connect())) return undefined;
    const st = await this.request((x) => x.getState()).catch(() => undefined);
    if (st?.ip) this.ip = st.ip;
    return st;
  }

  /** Stops the poll and lets the channel go; the next step opens it again. */
  close(): void {
    clearInterval(this.haTimer);
    void this.channel?.close().catch(() => {});
    this.channel = undefined;
  }

  private haState(s: SetupState, extra: { note?: string; error?: string; busy?: boolean; signingIn?: boolean } = {}) {
    this.ip = s.ip ?? this.ip;
    this.d.show({ phase: 'ha', ha: s.ha, ...extra });
  }

  /** Home Assistant: the address (what the panel found), sign-in on the panel's own page. */
  async homeAssistant(): Promise<void> {
    const c = await this.connect();
    if (!c) return this.d.done(); // older app: the panel's own setup does the rest
    c.onState = (s) => {
      const cur = this.d.phase();
      if (cur.phase === 'ha') this.haState(s, { note: cur.note, signingIn: cur.signingIn && !s.ha.signedIn });
    };
    this.haState(c.state!);
    clearInterval(this.haTimer);
    this.haTimer = setInterval(() => {
      if (!this.d.here() || this.d.phase().phase !== 'ha') { clearInterval(this.haTimer); return; }
      void this.request((x) => x.getState()).catch(() => {});
    }, 3000);
  }

  /** Sign in: the tab opens inside the click, and is pointed at the panel's sign-in page once the address is saved. */
  signIn(url: string): void {
    const s = this.d.phase();
    if (s.phase !== 'ha') return;
    if (!validHaUrl(url)) { this.haState({ ...this.channel!.state!, ha: s.ha }, { error: NOT_AN_ADDRESS }); return; }
    const tab = (this.d.openTab ?? ((u) => window.open(u, '_blank')))('about:blank');
    this.d.show({ ...s, error: undefined, signingIn: true, note: 'Sign in on the tab that opened. This page notices when you are done.' });
    void (async () => {
      try {
        const st = url === this.channel?.state?.ha.url ? this.channel.state : await this.request((x) => x.setHa(url));
        if (!st.ha.signinUrl) throw new Error('the panel has no sign-in page yet (is it on Wi-Fi?)');
        if (tab) tab.location.href = st.ha.signinUrl;
        else if (!window.open(st.ha.signinUrl, '_blank')) throw new Error('The browser blocked the sign-in tab. Allow pop-ups for this page, then sign in again.');
      } catch (e) {
        tab?.close();
        const cur = this.d.phase();
        if (cur.phase === 'ha') this.d.show({ ...cur, note: undefined, signingIn: false, error: (e as Error).message });
      }
    })();
  }

  async saveHa(url: string): Promise<void> {
    const s = this.d.phase();
    if (s.phase !== 'ha') return;
    if (url && url !== this.channel?.state?.ha.url) {
      if (!validHaUrl(url)) { this.d.show({ ...s, error: NOT_AN_ADDRESS }); return; }
      this.d.show({ ...s, busy: true, error: undefined });
      try { await this.request((x) => x.setHa(url)); } catch (e) { this.d.show({ ...s, busy: false, error: (e as Error).message }); return; }
    }
    await this.radio();
  }

  /** The radio role. */
  async radio(): Promise<void> {
    if (!(await this.connect())) { this.d.unreachable(); return; }
    let st: SetupState;
    try {
      st = await this.request((x) => x.getState());
    } catch (e) {
      this.d.unreachable(e); // e.g. the app restarted and didn't come back: say so, not "Connecting…" forever
      return;
    }
    this.d.show({
      phase: 'radio', radio: st.radio,
      note: st.radio.helper ? `Now: ${st.radio.status}` : 'Only Off is available: this panel has no root helper for the radio.',
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
    this.d.done();
  }
}
