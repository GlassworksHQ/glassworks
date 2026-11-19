// The panel over adb (Tango / ya-webadb on WebUSB), once Android is up.
import { Adb, AdbDaemonTransport } from '@yume-chan/adb';
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb';
import { ReadableStream } from '@yume-chan/stream-extra';
import { run } from './run';
import { openAdbStream } from 'improv-serial/adb';
import type { ByteStream } from 'improv-serial';
import type { AdbKey } from './key';

/** What provisioning needs from a panel. Panel implements it over adb; the tests use a fake. */
export interface PanelShell {
  /**
   * Runs a shell command; returns its output (stdout + stderr) and exit code. `signal` cancels it: the
   * command's stream is closed on the panel and the call rejects with the signal's reason.
   */
  run(cmd: string, signal?: AbortSignal): Promise<{ out: string; code: number }>;
  push(path: string, data: Uint8Array, mode?: number): Promise<void>;
  /** A raw stream to a service on the panel (e.g. 'localabstract:…'), if the transport has one. */
  openStream?(service: string): Promise<ByteStream>;
  /** Restarts the device into its bootloader (adb's `reboot:bootloader` service, as `adb reboot bootloader`), if the transport can. */
  rebootToBootloader?(): Promise<void>;
  /** Lets go of the connection, so the next connect to the same device starts clean. */
  close?(): Promise<void>;
}

/**
 * The device took the connection but didn't let this key in within the time given: it may be waiting
 * for someone to allow the key on its screen, or it trusts another computer's key only.
 */
/** How long a timed-out connect waits for the device's release before it rejects anyway. */
const RELEASE_MS = 1000;

/** How long a restart into the bootloader waits for adb's reply before it counts as done. */
const REBOOT_MS = 5000;

/** Another program (the computer's own adb) holds the device's adb interface: asking again won't help until it lets go. */
export class AdbBusy extends Error {
  constructor() { super('another program holds the panel\'s adb connection: run "adb kill-server" on this computer and try again'); this.name = 'AdbBusy'; }
}

export class AdbAuthTimeout extends Error {
  constructor() { super('the device didn\'t let this browser\'s adb key in'); this.name = 'AdbAuthTimeout'; }
}

export class Panel implements PanelShell {
  private constructor(private readonly adb: Adb) {}

  get serial(): string { return this.adb.serial; }

  /** The browser's list of adb devices (needs a user gesture): the device picked, not yet connected. */
  static async pick(): Promise<USBDevice> {
    const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
    if (!manager) throw new Error('this browser has no WebUSB (use Chrome or Edge)');
    const d = await manager.requestDevice();
    if (!d) throw new Error('no panel selected');
    return d.raw;
  }

  /**
   * Asks for the panel's adb interface (needs a user gesture), then authenticates with `key`. With `device`, no
   * list: that device, or the permitted one with its serial number when it came back after a restart. With `authMs`, a
   * device that hasn't let the key in after that long (it waits for a tap on its screen) is closed and AdbAuthTimeout
   * thrown; without it, the wait has no end, as adb's own. A refused key closes the device too, so a later try can claim it.
   */
  static async connect(key: AdbKey, device?: USBDevice, o: { authMs?: number } = {}): Promise<Panel> {
    const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
    if (!manager) throw new Error('this browser has no WebUSB (use Chrome or Edge)');
    // the browser's list waits on the person; with authMs, one deadline covers every wait after it (finding the device
    // again, claiming it, the key), so a stalled USB call can't hold a caller that asks again
    const picked = device ? undefined : await manager.requestDevice();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = o.authMs === undefined ? undefined : Date.now() + o.authMs;
    const late = o.authMs === undefined ? undefined
      : new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new AdbAuthTimeout()), o.authMs); });
    late?.catch(() => {});
    const within = <T>(p: Promise<T>): Promise<T> => (late ? Promise.race([p, late]) : p);
    try {
      let d: AdbDaemonWebUsbDevice | undefined = picked;
      if (device) {
        const known = await within(manager.getDevices());
        d = known.find((x) => x.raw === device) ?? (device.serialNumber ? known.find((x) => x.serial === device.serialNumber) : undefined);
      }
      // a device given but not in the list is off USB for now (restarting), not a list closed with nothing picked
      if (!d) throw new Error(device ? 'the device isn\'t on USB right now (it may be restarting)' : 'no panel selected');
      const claiming = d.connect();
      let connection;
      try {
        connection = await within(claiming);
      } catch (e) {
        if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) throw new AdbBusy();
        if (e instanceof AdbAuthTimeout) { const dev = d; claiming.then(() => dev.raw.close(), () => {}).catch(() => {}); } // claimed too late: let go
        throw e;
      }
      const auth = AdbDaemonTransport.authenticate({
        serial: d.serial, connection, credentialStore: key.store,
      });
      try {
        return new Panel(new Adb(await within(auth)));
      } catch (e) {
        // refused (adbd up before it reads its keys, or not this browser's key) or too late: let go, so a later try can claim it
        if (e instanceof AdbAuthTimeout) auth.then((t) => t.close(), () => {}); // a key allowed after all: let go of it
        // let go before throwing, so a later try can claim it. WebUSB's close has no deadline: with authMs the release gets
        // only what is left of it (nothing after a timeout: it goes on in the background), so connect rejects within
        // authMs; without, at most RELEASE_MS
        const release = d.raw.close().catch(() => {});
        const wait = deadline === undefined ? RELEASE_MS : Math.max(0, deadline - Date.now());
        await Promise.race([release, new Promise((r) => setTimeout(r, wait))]);
        throw e;
      }
    } finally {
      clearTimeout(timer);
    }
  }


  /** Already-permitted adb devices (no prompt). */
  static async known(): Promise<USBDevice[]> {
    const m = AdbDaemonWebUsbDeviceManager.BROWSER;
    return m ? (await m.getDevices()).map((d) => d.raw) : [];
  }

  run(cmd: string, signal?: AbortSignal): Promise<{ out: string; code: number }> {
    return run(this.adb.subprocess.noneProtocol, cmd, signal);
  }

  async getProp(key: string): Promise<string> {
    return (await this.adb.getProp(key)).trim();
  }

  async push(path: string, data: Uint8Array, mode = 0o644): Promise<void> {
    const sync = await this.adb.sync();
    try {
      await sync.write({
        filename: path,
        file: new ReadableStream<Uint8Array>({ start(c) { c.enqueue(data); c.close(); } }),
        permission: mode,
      });
    } finally {
      await sync.dispose();
    }
  }

  openStream(service: string): Promise<ByteStream> {
    // Adds the NUL terminator Android 10's adbd needs (see openAdbStream).
    return openAdbStream(this.adb, service);
  }

  /** adb's `reboot:bootloader`. The device drops the connection as it restarts, so a lost reply counts as done. */
  async rebootToBootloader(): Promise<void> {
    // a reply that never comes counts as done too (the device may be gone already), after REBOOT_MS
    await Promise.race([this.adb.power.bootloader().catch(() => {}), new Promise((r) => setTimeout(r, REBOOT_MS))]);
    await Promise.race([this.adb.close().catch(() => {}), new Promise((r) => setTimeout(r, RELEASE_MS))]);
  }

  async close(): Promise<void> {
    await this.adb.close();
  }
}
