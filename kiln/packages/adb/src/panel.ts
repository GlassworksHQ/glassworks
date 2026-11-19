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
    let d: AdbDaemonWebUsbDevice | undefined;
    if (device) {
      const known = await manager.getDevices();
      d = known.find((x) => x.raw === device) ?? (device.serialNumber ? known.find((x) => x.serial === device.serialNumber) : undefined);
    } else {
      d = await manager.requestDevice();
    }
    if (!d) throw new Error('no panel selected');
    let connection;
    try {
      connection = await d.connect();
    } catch (e) {
      if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) throw new AdbBusy();
      throw e;
    }
    const auth = AdbDaemonTransport.authenticate({
      serial: d.serial, connection, credentialStore: key.store,
    });
    let timer: ReturnType<typeof setTimeout> | undefined;
    const late = o.authMs === undefined ? undefined
      : new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new AdbAuthTimeout()), o.authMs); });
    try {
      return new Panel(new Adb(await (late ? Promise.race([auth, late]) : auth)));
    } catch (e) {
      // refused (adbd up before it reads its keys, or not this browser's key) or too late: let go, so a later try can claim it
      if (e instanceof AdbAuthTimeout) auth.then((t) => t.close(), () => {}); // a key allowed after all: let go of it
      await d.raw.close().catch(() => {});
      throw e;
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
    await this.adb.power.bootloader().catch(() => {});
    await this.adb.close().catch(() => {});
  }

  async close(): Promise<void> {
    await this.adb.close();
  }
}
