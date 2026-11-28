// The kiosk app's setup channel, reached through adb like Improv Serial (packages/improv-serial).
// Everything the panel's own onboarding asks for besides Wi-Fi, so the installer can do it from the
// computer: Home Assistant address, sign-in status, radio mode.
//
// Protocol (the app implements the same; keep both in sync):
//   abstract socket `s1kiosk_setup`; the app only accepts adb's shell user (uid 2000) or root.
//   One JSON object per line, UTF-8, both ways. Requests carry an `id`; the reply echoes it.
//     → {"id":1,"op":"state"}
//     ← {"id":1,"ok":true,"state":SetupState}
//     → {"id":2,"op":"set_ha","url":"http://homeassistant.local:8123"}
//     ← {"id":2,"ok":true,"state":SetupState}            or {"id":2,"ok":false,"error":"…"}
//     → {"id":10,"op":"ha.set_dashboard","dashboard":"Tablet"}   (apps with dashboard picker; reloads in place)
//     ← {"id":10,"ok":true,"state":SetupState}           or {"id":10,"ok":false,"error":"…"}
//     → {"id":3,"op":"set_radio","mode":"zigbee_tcp"}
//     ← {"id":3,"ok":true,"state":SetupState}            or {"id":3,"ok":false,"error":"…"}
//     → {"id":4,"op":"set_radio_allow","allow":"192.0.2.10 198.51.100.0/24"}   ("" = Home Assistant only, "any")
//     ← {"id":4,"ok":true,"state":SetupState}            or {"id":4,"ok":false,"error":"…"}
//     → {"id":5,"op":"radio.thread_join"}   (apps after 0.4.3; the radio must be in thread_router and signed in)
//     ← {"id":5,"ok":true,"state":SetupState}            or {"id":5,"ok":false,"error":"…"}
//       Progress follows in state.radio.threadJoin.
//     → {"id":6,"op":"pairing.arm"}   (apps with setup over the LAN, #34)
//     ← {"id":6,"ok":true,"state":SetupState}            or {"id":6,"ok":false,"error":"…"}
//       Opens pairing over the LAN again for 30 minutes, once Home Assistant has adopted the panel.
//     → {"id":7,"op":"wifi.scan"}   (apps with the setup hotspot, #197)
//     ← {"id":7,"ok":true,"state":SetupState,"networks":[{"ssid":"…","rssi":-50,"secure":true}]}
//     → {"id":9,"op":"panes.set","on":"assist,sendspin"}   (apps whose state has `panes`, #621)
//     ← {"id":9,"ok":true,"state":SetupState}            or {"id":9,"ok":false,"error":"…"}
//       Answers the Panes step as the panel's own Continue does, for the panel's screen too: the ids in `on` are on,
//       every other card off, through the same switch as the pane's settings and its Home Assistant switch. A card
//       that can't apply its switch refuses with why, in words; an id the app has no card for refuses the whole request.
//     → {"id":8,"op":"wifi.join","ssid":"…","password":"…"}   ("" for an open network)
//     ← {"id":8,"ok":true,"state":SetupState}            or {"id":8,"ok":false,"error":"…"}
//       The panel joins after it replies, and its own screen shows how it goes: the join can take it off the
//       network the browser reached it on. Refused while the panel is on a network (state.online).
//   The app may also push {"event":"state","state":SetupState} whenever something changes
//   (signed in, radio status, Wi-Fi), so the installer doesn't have to poll.
//   No tokens or keys cross this channel. The one secret is wifi.join's password, which the panel never logs nor
//   sends back, and refuses while it is on a network: so it comes over the panel's WPA2 setup hotspot, never as
//   plain HTTP over a working LAN. Kiln's USB wizard keeps Improv Serial for Wi-Fi.
//
//   The same protocol runs over the LAN (#34): a WebSocket at http://<panel>:8099/setup/channel
//   (lan.ts). There the panel answers `state` only, until the browser pairs with the code on the panel's
//   screen (POST /setup/pair); every other request gets NOT_PAIRED.
import type { ByteStream } from '@glassworks/improv-serial';

export { LAN_CHANNEL_PATH, NOT_PAIRED, openWebSocketStream, type WebSocketStream } from './lan';

export const SETUP_SOCKET = 'localabstract:s1kiosk_setup';

export interface RadioOption { id: string; label: string; detail: string; experimental?: boolean; repeater?: boolean }

/** How "Join Home Assistant's Thread network" goes (`radio.thread_join`). Never carries the dataset. */
export interface ThreadJoinState {
  phase: 'idle' | 'fetching' | 'needs_admin' | 'joining' | 'joined' | 'failed';
  /** Why, in plain words, for `needs_admin` and `failed`. */
  reason?: string;
  /** `needs_admin`: where an administrator signs in once (the panel shows it as a QR code). */
  adminUrl?: string;
  /** The radio's Thread role once it has a dataset: `detached`, `child`, `router`, `leader`, … */
  role?: string;
}

/** A network the panel sees (`wifi.scan`). */
export interface WifiNetwork { ssid: string; rssi: number; secure: boolean }

/** A card on setup's Panes step (the app's PaneChoice): what the pane does, and whether it is on now. */
export interface PaneCard { id: string; title: string; detail: string; on: boolean }

export interface SetupState {
  /** Panel's Wi-Fi IPv4 address, if connected. */
  ip?: string;
  /** The panel is on a network (Wi-Fi or Ethernet). Absent from apps before the setup hotspot (#197). */
  online?: boolean;
  /** The screen takes a tap. False on a frame without touch: the page offers nothing to do on the panel. Absent from older apps. */
  touch?: boolean;
  /**
   * Setup's Silent updates step (#422), from apps that have it, once the app has read the answer. `offer` is what the
   * step offers (`allow`, `device_owner`, `boot_image`), or null with nothing to do.
   */
  silent_updates?: { silent: boolean; reason: string; offer: 'allow' | 'device_owner' | 'boot_image' | null; answered: boolean };
  /**
   * Setup's Panes step (#621): one card per pane the app offers there, in its order, by the pane's id (Kiln draws the
   * pane catalog's icon for it). A pane the frame lacks the hardware for has no card: it is hidden, as everywhere in
   * the app. Absent from apps without the step; Kiln then has no Panes step.
   */
  panes?: PaneCard[];
  ha: {
    /** Address the app found on the network by itself (mDNS), if any. */
    discovered?: string;
    /** Address the app uses now (empty until set). */
    url?: string;
    signedIn: boolean;
    /** The panel's sign-in page (`http://<ip>:8099/signin`): the same link as its QR code. */
    signinUrl?: string;
    /** Selectable Lovelace dashboards (`Default`, then named dashboards), once signed in and custom dashboards exist. */
    dashboards?: string[];
    /** Currently selected dashboard label from `dashboards` (e.g. `Default`, `Tablet`). */
    dashboard?: string;
  };
  /**
   * The panel's Zigbee/Thread radio. A frame without one (most Android devices) leaves it out; Kiln's phone setup
   * checks for that at run time. The old installer page (kiln/src) only meets a frame with a radio, which always sends it.
   */
  radio: {
    mode: string;
    status: string;
    /** Modes offered in onboarding (Off, Zigbee coordinator, Thread border router). */
    options: RadioOption[];
    /** False when the root helper is missing: only Off works then. */
    helper: boolean;
    /**
     * Who the panel's firewall lets reach the radio's network ports (helper v3): "" = nobody yet
     * (Home Assistant hasn't added the panel over ESPHome), "any", or addresses. Absent in Off or
     * with an older helper.
     */
    allowed?: string;
    /**
     * The panel's own radio step is answered (a mode applied there, in Home Assistant or by Kiln).
     * Absent from apps that don't send it; their same-mode `set_radio` restarts the radio.
     */
    answered?: boolean;
    /** Only in `thread_router`, and only from apps that have `radio.thread_join`. */
    threadJoin?: ThreadJoinState;
  };
}

type Reply = { id: number; ok: boolean; state?: SetupState; error?: string; networks?: WifiNetwork[] };

/** The panel answered and said no (`ok:false`), as opposed to not answering at all. Its message is the panel's. */
export class Refused extends Error {
  override name = 'Refused';
}

/** The request never left this browser: the connection was gone before it could be written. Nothing reached the panel. */
export class NotSent extends Error {
  override name = 'NotSent';
}

export class SetupChannel {
  private buf = '';
  private nextId = 1;
  private pending = new Map<number, (r: Reply) => void>();
  state?: SetupState;
  onState: (s: SetupState) => void = () => {};

  constructor(private readonly io: ByteStream) {
    const dec = new TextDecoder();
    io.onData((chunk) => {
      this.buf += dec.decode(chunk, { stream: true });
      let nl: number;
      while ((nl = this.buf.indexOf('\n')) >= 0) {
        const line = this.buf.slice(0, nl).trim();
        this.buf = this.buf.slice(nl + 1);
        if (!line) continue;
        let msg: Partial<Reply> & { event?: string };
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.state) { this.state = msg.state; this.onState(msg.state); }
        if (typeof msg.id === 'number') {
          this.pending.get(msg.id)?.(msg as Reply);
          this.pending.delete(msg.id);
        }
      }
    });
  }

  private async request(op: string, args: Record<string, string> = {}, timeoutMs = 8000): Promise<SetupState> {
    return (await this.call(op, args, timeoutMs)).state!;
  }

  /** The whole reply to `op`, once the panel says yes; a no is a [Refused]. */
  private call(op: string, args: Record<string, string>, timeoutMs: number): Promise<Reply> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error(`the panel did not answer (${op})`)); }, timeoutMs);
      this.pending.set(id, (r) => {
        clearTimeout(t);
        if (r.ok && r.state) resolve(r);
        else reject(new Refused(r.error || `${op} failed`));
      });
      void this.io.write(new TextEncoder().encode(`${JSON.stringify({ id, op, ...args })}\n`)).catch((e: unknown) => {
        clearTimeout(t);
        this.pending.delete(id);
        reject(new NotSent(`couldn't send ${op} to the panel${e instanceof Error ? ` (${e.message})` : ''}`));
      });
    });
  }

  getState(): Promise<SetupState> { return this.request('state'); }
  setHa(url: string): Promise<SetupState> { return this.request('set_ha', { url }, 15_000); }
  /** Picks a Lovelace dashboard by its label from `state.ha.dashboards` (same origin; reloads in place). */
  setDashboard(dashboard: string): Promise<SetupState> { return this.request('ha.set_dashboard', { dashboard }, 15_000); }
  setRadio(mode: string): Promise<SetupState> { return this.request('set_radio', { mode }, 30_000); }
  // TODO(#166): once a release has radio.thread_join, use it in Kiln's Thread step instead of sending the
  // person to Home Assistant: start it, then follow state.radio.threadJoin. Older apps answer "Unknown request".
  joinThread(): Promise<SetupState> { return this.request('radio.thread_join'); }
  /** Answers setup's Silent updates step, as Not now does (apps with #422). Refused while the app hasn't read the answer. */
  answerSilentUpdates(): Promise<SetupState> { return this.request('silent_updates.answer'); }
  /** Answers the Panes step (#621): the cards in `on` on, every other off. */
  setPanes(on: readonly string[]): Promise<SetupState> { return this.request('panes.set', { on: on.join(',') }, 35_000); }
  /** Over USB: lets a browser pair over the LAN again after Home Assistant adopted the panel (apps with #34). */
  armPairing(): Promise<SetupState> { return this.request('pairing.arm'); }
  /** The networks the panel sees now, strongest first: a fresh scan, up to about 15 s (apps with #197). */
  async scanWifi(): Promise<WifiNetwork[]> { return (await this.call('wifi.scan', {}, 25_000)).networks ?? []; }
  /** Sends the network to join ("" for an open one). The panel joins after it answers; its screen shows the outcome. */
  joinWifi(ssid: string, password: string): Promise<SetupState> { return this.request('wifi.join', { ssid, password }); }
  close(): Promise<void> { return this.io.close(); }
}

/**
 * Kiln's sign-in (issue 453): the panel's own sign-in, started for a window Kiln opened, whose last page closes that
 * window (`then=kiln`). On the address the state's `signinUrl` gives, so it reaches the panel as the QR code does.
 */
export function kilnSignInUrl(signinUrl: string): string {
  return new URL('/signin/continue?then=kiln', signinUrl).href;
}

type Open = (url: string, target: string, features?: string) => Window | null;

/**
 * The window for Kiln's sign-in, opened inside the click (pop-up blockers): a pop-up, or a tab where the browser
 * won't open one. Null when it opens neither.
 */
export function openSignInWindow(open: Open = (u, t, f) => window.open(u, t, f)): Window | null {
  return open('about:blank', 'glassworks-signin', 'popup,width=520,height=720') ?? open('about:blank', '_blank');
}

/** http(s)://host[:port][/path], no spaces or quotes. */
export function validHaUrl(u: string): boolean {
  try {
    const x = new URL(u);
    return (x.protocol === 'http:' || x.protocol === 'https:') && !!x.hostname && !/[\s'"]/.test(u);
  } catch { return false; }
}
