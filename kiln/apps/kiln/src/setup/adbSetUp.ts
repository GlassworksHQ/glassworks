// Set-up over adb, once Glassworks is on the device: Wi-Fi over Improv Serial on the app's socket, then Home Assistant
// and the radio over its setup channel (channelSetup.ts). The wizard's Session runs it after the app goes in, for any
// frame; it names none. The screens are setup/steps.tsx, which the panel's own page (src/lan) shares.
import type { PanelShell } from '@glassworks/adb';
import { IMPROV_SOCKET } from '@glassworks/app-adb';
import { SETUP_SOCKET, SetupChannel } from '@glassworks/setup-channel';
import { ImprovSerialClient, State as ImprovState, type ByteStream } from '@glassworks/improv-serial';
import type { Setup } from '../wizard/state';
import { ChannelSetup, type SetupSteps, type WifiSteps } from './channelSetup';
import type { PanelApp } from './radioPlan';

/** How long one open of the app's Improv socket may take: a stalled one counts as a failed try. */
export const IMPROV_OPEN_MS = 5000;

/**
 * The socket, or a rejection after `ms`: adb's stream open has no deadline of its own. One that opens after it is
 * closed, so it doesn't leak.
 */
export function openWithin(open: () => Promise<ByteStream>, ms: number): Promise<ByteStream> {
  return new Promise((resolve, reject) => {
    let late = false;
    const t = setTimeout(() => { late = true; reject(new Error(`the app's socket didn't open in ${ms / 1000} s`)); }, ms);
    open().then(
      (io) => { clearTimeout(t); if (late) void io.close().catch(() => {}); else resolve(io); },
      (e) => { clearTimeout(t); reject(e); },
    );
  });
}

export interface AdbSetUpDeps {
  /** The device over adb, once connected. */
  panel(): PanelShell | undefined;
  phase(): Setup;
  show(setup: Setup): void;
  /** Whether the app offers setup's Panes step, each time its setup state says (ChannelSetupDeps). */
  panesOffered?(offered: boolean): void;
  /** Whether the frame has this step (ChannelSetupDeps `has`). */
  has?(step: 'home-assistant' | 'panes' | 'radio'): boolean;
  /** The set-up screen is the one showing. */
  here(): boolean;
  /** Set-up is over: Done. */
  done(): void;
  /** The app stopped answering. */
  unreachable(e?: unknown): void;
  /** Glassworks on the device, read-only: decides how a Thread router gets its network. */
  app(): Promise<PanelApp | undefined>;
  /** What the screens call the app. */
  appName: string;
  /** The Wi-Fi network the device is on, read-only. */
  wifiName(p: PanelShell, signal?: AbortSignal): Promise<{ ssid?: string }>;
  /** Runs a button's work, one at a time. */
  click(name: string, run: () => Promise<void> | void): Promise<void>;
  /** A line for the technical log. */
  note(line: string): void;
  /** A signal that cancels a read-only command that takes too long. */
  lid(ms?: number): AbortSignal;
  sleep(ms: number): Promise<void>;
  openTab(url: string): Window | null;
  /** A simulated device needs no wait for the app's socket. */
  simulated?: boolean;
  /** The device has a touchscreen (installer.json `touch`). Without one, no step offers "do it on the panel". */
  touch?: boolean;
  /** The app's package, for the grant its Wi-Fi scan needs. */
  appPackage?: string;
}

export class AdbSetUp implements SetupSteps, WifiSteps {
  /** Home Assistant and the radio, over the app's setup channel. */
  readonly steps: ChannelSetup;
  private improv?: ImprovSerialClient;
  /** The device's Wi-Fi address, once it has one. */
  ip?: string;
  /** The network the device joined or is on. */
  wifiSsid?: string;
  /** Said on the Wi-Fi step, e.g. after a switch made the device forget its network. */
  wifiHint?: string;

  constructor(private readonly d: AdbSetUpDeps) {
    this.steps = new ChannelSetup({
      open: (tries) => this.openSetup(tries),
      phase: d.phase,
      show: d.show,
      panesOffered: d.panesOffered,
      has: d.has,
      here: d.here,
      done: d.done,
      unreachable: d.unreachable,
      app: d.app,
      openTab: d.openTab,
    });
  }

  click(name: string, run: () => Promise<void> | void): Promise<void> { return this.d.click(name, run); }

  /** Whether the device has a touchscreen: the steps offer "do it on the panel" only then. */
  get touch(): boolean { return this.d.touch !== false; }

  /** Lets go of the setup channel and the Improv client. */
  close(): void {
    this.steps.close();
    void this.improv?.close().catch(() => {});
    this.improv = undefined;
  }

  /** Waits until the app listens on `socket` (it cold-starts after the install), then connects. */
  async waitForSocket(socket: string, timeoutMs = 90_000): Promise<boolean> {
    if (this.d.simulated) return true;
    const name = socket.replace(/^localabstract:/, '@');
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      // each read on a lid: a stalled USB transfer counts as not yet, and the deadline still holds
      const r = await this.d.panel()!.run(`grep -c ' ${name}$' /proc/net/unix`, this.d.lid(5000)).catch(() => ({ out: '0', code: 1 }));
      if (Number.parseInt(r.out.trim(), 10) > 0) return true;
      await this.d.sleep(1500);
    }
    this.d.note(`${name} never started listening on the panel`);
    return false;
  }

  private async openImprov(): Promise<ImprovSerialClient | undefined> {
    const open = this.d.panel()?.openStream?.bind(this.d.panel());
    if (!open || !(await this.waitForSocket(IMPROV_SOCKET))) return undefined;
    for (let i = 0; i < 5; i++) {
      try {
        const c = new ImprovSerialClient(await openWithin(() => open(IMPROV_SOCKET), IMPROV_OPEN_MS), { deviceLabel: 'panel' });
        // a try that doesn't answer lets go of its stream: one Improv connection at a time
        try { await c.requestState(5000); } catch (e) { void c.close().catch(() => {}); throw e; }
        return c;
      } catch (e) {
        this.d.note(`Improv over adb, try ${i + 1}: ${(e as Error).message ?? e}`);
        await this.d.sleep(1500);
      }
    }
    return undefined;
  }

  /**
   * The Wi-Fi step. A panel already on Wi-Fi says so first, with changing it as the other way; one
   * that isn't goes straight to the networks it can see.
   */
  async wifi(): Promise<void> {
    this.d.show({ phase: 'wifi', status: `Starting ${this.d.appName} on the panel…`, hint: this.wifiHint });
    void this.improv?.close().catch(() => {}); // back on this step: one Improv connection at a time
    this.improv = await this.openImprov();
    if (!this.improv) {
      this.d.show({ phase: 'wifi', networks: [], unreachable: true, status: `Couldn't reach ${this.d.appName} on the panel.` });
      return;
    }
    const on = await this.panelOnWifi();
    if (on) { this.d.show({ phase: 'wifi', on, status: '' }); return; }
    await this.scanWifi();
  }

  /**
   * Whether the panel is on Wi-Fi, from the app's setup state (its address), and which network, from
   * Android (`dumpsys wifi`, read-only). Undefined when it isn't, or an older app can't say.
   */
  private async panelOnWifi(): Promise<{ ip: string; ssid?: string } | undefined> {
    const st = await this.steps.state();
    if (!st?.ip) return undefined;
    this.ip = st.ip;
    // read-only and only for the name: a panel slow to answer doesn't hold up the step
    const named = await this.d.wifiName(this.d.panel()!, this.d.lid()).catch(() => undefined);
    const ssid = named?.ssid ?? this.wifiSsid;
    if (ssid) this.wifiSsid = ssid;
    return { ip: st.ip, ssid };
  }

  /**
   * Android's Wi-Fi, on before a scan: a freshly flashed device can start with it off, and then the app's scan finds
   * nothing (a run on a real device, 2026-11-20). Read first (`settings get global wifi_on`, 0 is off); `svc wifi enable` over adb
   * only when it is off, then a few seconds for the radio. Whether it had to turn it on.
   */
  private async wifiOn(): Promise<boolean> {
    const p = this.d.panel();
    if (!p) return false;
    const read = async () => (await p.run('settings get global wifi_on', this.d.lid()).catch(() => ({ out: '' }))).out.trim();
    if ((await read()) !== '0') return false;
    this.d.note('Wi-Fi is off on the device: turning it on (svc wifi enable)');
    await p.run('svc wifi enable', this.d.lid()).catch(() => {});
    for (let i = 0; i < 10 && (await read()) === '0'; i++) await this.d.sleep(1000);
    await this.d.sleep(3000); // the radio's first scan results
    return true;
  }

  /**
   * The grant the app's Wi-Fi scan needs: without location, Android 6 and later give it no scan results, so the scan
   * finds nothing (the image path on a run on a real device, 2026-11-20, had no `pm install -g`). Read first
   * (`dumpsys package`); `pm grant` only when Android lists it as not granted.
   */
  private async scanGrant(): Promise<void> {
    const p = this.d.panel();
    const pkg = this.d.appPackage;
    if (!p || !pkg) return;
    const out = (await p.run(`dumpsys package ${pkg}`, this.d.lid()).catch(() => ({ out: '' }))).out;
    if (!/android\.permission\.ACCESS_FINE_LOCATION: granted=false/.test(out)) return;
    this.d.note('The app has no location grant, so its Wi-Fi scan would find nothing: granting it (pm grant ACCESS_FINE_LOCATION)');
    await p.run(`pm grant ${pkg} android.permission.ACCESS_FINE_LOCATION`, this.d.lid()).catch(() => {});
  }

  /** The networks the panel can see, over Improv Serial through adb ("Use another network"). */
  async scanWifi(): Promise<void> {
    if (!this.improv) return this.wifi();
    const cur = this.d.phase();
    const was = cur.phase === 'wifi' ? cur.on : undefined;
    const already = !was && this.improv.state === ImprovState.Provisioned;
    this.d.show({ phase: 'wifi', on: was, changing: !!was, status: 'Looking for networks the panel can see…', hint: already ? 'The panel is already on Wi-Fi. Pick another network to change it, or skip.' : this.wifiHint });
    await this.scanGrant();
    const turnedOn = await this.wifiOn();
    let networks: { ssid: string; rssi: number; secure: boolean }[] = [];
    try { networks = await this.improv.scan(); } catch { /* typed by hand then */ }
    // a radio just turned on can miss the first scan: once more before "None found"
    if (!networks.length && turnedOn) { await this.d.sleep(3000); try { networks = await this.improv.scan(); } catch { /* typed by hand then */ } }
    const s = this.d.phase();
    this.d.show({
      phase: 'wifi', on: was, changing: !!was, networks, hint: s.phase === 'wifi' ? s.hint : undefined,
      status: networks.length ? `${networks.length} in range of the panel` : 'No networks found. Choose manual entry to type a network name.',
    });
  }

  async joinWifi(ssid: string, psk: string): Promise<void> {
    const cur = this.d.phase();
    const base = cur.phase === 'wifi' ? cur : { phase: 'wifi' as const, status: '' };
    const step = (st: 'sent' | 'join' | 'ok' | 'err', error?: string) => this.d.show({ ...base, joining: { ssid, step: st, error } });
    if (!ssid) { step('err', 'Choose a network or enter its name.'); return; }
    step('sent');
    const succeeded = () => {
      this.d.note(`panel connected to Wi-Fi ${ssid}`);
      void this.improv?.close();
      this.wifiSsid = ssid;
      step('ok');
    };
    try {
      await this.improv!.provision(ssid, psk, () => step('join'));
      succeeded();
    } catch (e) {
      // The app can restart right after joining (it found Home Assistant), which cuts this connection
      // before the result arrives. Reconnect and ask: Provisioned means it's on Wi-Fi.
      this.d.note(`Wi-Fi: ${(e as Error).message}; asking the panel again`);
      const again = await this.openImprov();
      const state = again ? await again.requestState().catch(() => 0) : 0;
      if (again && state === ImprovState.Provisioned) { this.improv = again; succeeded(); return; }
      step('err', (e as Error).message);
    }
  }

  private async openSetup(tries = 5): Promise<SetupChannel | undefined> {
    const open = this.d.panel()?.openStream?.bind(this.d.panel());
    if (!open) return undefined;
    for (let i = 0; i < tries; i++) {
      try {
        const c = new SetupChannel(await open(SETUP_SOCKET));
        await c.getState();
        return c;
      } catch { await this.d.sleep(1000); }
    }
    return undefined;
  }

  get panesDraft(): Record<string, boolean> | undefined { return this.steps.panesDraft; }
  set panesDraft(v: Record<string, boolean> | undefined) { this.steps.panesDraft = v; }
  get haAddress(): string | undefined { return this.steps.haAddress; }
  set haAddress(v: string | undefined) { this.steps.haAddress = v; }
  /**
   * Home Assistant. The app can restart right after a Wi-Fi join (it found Home Assistant), so its setup channel may be
   * down for a while: one clear waiting state until it is back, never read as an older app with no channel.
   */
  async homeAssistant(): Promise<void> {
    if (this.d.has?.('home-assistant') === false) return this.steps.homeAssistant(); // it passes over to the next one
    if (!this.d.simulated && !(await this.socketUp(SETUP_SOCKET))) {
      this.d.show({ phase: 'waiting', note: `Waiting for ${this.d.appName} to finish restarting…` });
      // not back within the minute: an error with Try again, never the older-app way on to Done
      if (!(await this.waitForSocket(SETUP_SOCKET, 60_000))) {
        this.d.unreachable(new Error(`${this.d.appName} took longer than expected to restart. Click Try again to reconnect.`));
        return;
      }
    }
    return this.steps.homeAssistant();
  }

  /** One read: whether the app listens on `socket` now. */
  private async socketUp(socket: string): Promise<boolean> {
    const name = socket.replace(/^localabstract:/, '@');
    const r = await this.d.panel()!.run(`grep -c ' ${name}$' /proc/net/unix`, this.d.lid(5000)).catch(() => ({ out: '0' }));
    return Number.parseInt(r.out.trim(), 10) > 0;
  }
  signIn(url: string): void { this.steps.signIn(url); }
  saveHa(url: string, dashboard?: string): Promise<void> { return this.steps.saveHa(url, dashboard); }
  skipHa(url: string): Promise<void> { return this.steps.skipHa(url); }
  panes(): Promise<void> { return this.steps.panes(); }
  applyPanes(on: readonly string[]): Promise<void> { return this.steps.applyPanes(on); }
  radio(): Promise<void> { return this.steps.radio(); }
  applyRadio(mode: string): Promise<void> { return this.steps.applyRadio(mode); }
  finish(): void { this.d.done(); }
}
