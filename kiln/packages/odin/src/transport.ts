/// <reference types="w3c-web-usb" preserve="true" />
// One bulk IN/OUT pair to a Samsung device in Download mode: WebUSB in the browser, a fake in the tests.
//
// How the interface is found follows Heimdall's BridgeManager::FindDeviceInterface (MIT, Copyright (c) 2010-2017
// Benjamin Dobell, Glass Echidna; Copyright (c) 2021-2024 Henrik Grimler; see ../LICENSE): Samsung's vendor id with a
// Download-mode product id, then the first interface setting of class CDC Data (0x0A) with exactly two endpoints,
// one IN and one OUT.

import { OdinError } from './errors.ts';

export const SAMSUNG_VENDOR_ID = 0x04e8;
/** The product ids Heimdall accepts (BridgeManager.h): Galaxy S, Galaxy S2 (most later devices too), Droid Charge. */
export const DOWNLOAD_MODE_PRODUCT_IDS: readonly number[] = [0x6601, 0x685d, 0x68c3];
/** For `navigator.usb.requestDevice({ filters: ODIN_USB_FILTERS })`. */
export const ODIN_USB_FILTERS: USBDeviceFilter[] = DOWNLOAD_MODE_PRODUCT_IDS.map((productId) => ({
  vendorId: SAMSUNG_VENDOR_ID,
  productId,
}));
const USB_CLASS_CDC_DATA = 0x0a;

/** What the protocol needs from USB. `send` of an empty array is a zero-length packet; Odin needs those. */
export interface OdinTransport {
  /** One bulk OUT transfer of `data` (empty: a zero-length packet). */
  send(data: Uint8Array, timeoutMs: number): Promise<void>;
  /** One bulk IN transfer of at most `maxLength` bytes. Ends at a short packet. */
  receive(maxLength: number, timeoutMs: number): Promise<Uint8Array>;
  close(): Promise<void>;
}

/** True for a device that looks like Samsung's Download mode by its ids. */
export function isDownloadMode(device: Pick<USBDevice, 'vendorId' | 'productId'>): boolean {
  return device.vendorId === SAMSUNG_VENDOR_ID && DOWNLOAD_MODE_PRODUCT_IDS.includes(device.productId);
}

/**
 * Why a claim failed and what to do, in words for a person. Windows is the usual case: Samsung's own USB driver
 * (or none) owns the interface, and Chrome's WebUSB can only claim one that runs on WinUSB.
 */
export const CLAIM_HELP =
  "The browser couldn't take over the device's Download mode interface. On Windows, WebUSB needs the WinUSB " +
  "driver on it in place of Samsung's USB driver: install it for this device with Zadig (zadig.akeo.ie), then " +
  'unplug it, plug it back in and try again. On any system, close other programs that may hold the device ' +
  '(Heimdall, Odin, Samsung Smart Switch, another browser tab) first.';

function timeout<T>(p: Promise<T>, what: string, ms: number): Promise<T> {
  let t: ReturnType<typeof setTimeout> | undefined;
  const timer = new Promise<never>((_, reject) => {
    t = setTimeout(() => reject(new OdinError('timeout', `USB ${what} timed out after ${ms} ms`)), ms);
  });
  return Promise.race([p, timer]).finally(() => clearTimeout(t));
}

/** {@link OdinTransport} on a WebUSB device. */
export class WebUsbOdinTransport implements OdinTransport {
  /**
   * A read that timed out is still queued in the browser: WebUSB can't cancel it. The next `receive` takes its
   * result instead of queuing a second read, which would get the packet after the one it wants.
   */
  private pending: { promise: Promise<USBInTransferResult>; maxLength: number } | null = null;

  private constructor(
    readonly device: USBDevice,
    readonly interfaceNumber: number,
    private readonly epIn: number,
    private readonly epOut: number,
  ) {}

  /** Opens the device, finds the Download mode interface and claims it. Throws an {@link OdinError}. */
  static async open(device: USBDevice): Promise<WebUsbOdinTransport> {
    if (!isDownloadMode(device)) {
      const id = (n: number) => n.toString(16).padStart(4, '0');
      throw new OdinError('not-download-mode',
        `USB device ${id(device.vendorId)}:${id(device.productId)} is not a Samsung device in Download mode`);
    }
    const openedHere = !device.opened;
    try {
      if (openedHere) await device.open();
    } catch (e) {
      throw new OdinError('claim-failed', `${CLAIM_HELP} (open: ${message(e)})`, { cause: e });
    }
    try {
      if (device.configuration === null) await device.selectConfiguration(1);
    } catch (e) {
      throw new OdinError('claim-failed', `${CLAIM_HELP} (configuration: ${message(e)})`, { cause: e });
    }
    const found = findInterface(device.configuration?.interfaces ?? []);
    if (!found) {
      throw new OdinError('not-download-mode', 'this device has no Download mode interface (CDC Data, one bulk IN and one OUT)');
    }
    // Claimed already means another connection on this page holds it: two would mix their packets.
    if (found.claimed) {
      throw new OdinError('claim-failed', 'this page already has the Download mode interface open: close that connection first');
    }
    try {
      await device.claimInterface(found.interfaceNumber);
    } catch (e) {
      throw new OdinError('claim-failed', `${CLAIM_HELP} (claim: ${message(e)})`, { cause: e });
    }
    // BridgeManager::SetupDeviceInterface: only a setting other than the first needs selecting.
    if (found.alternateSetting !== 0) {
      try {
        await device.selectAlternateInterface(found.interfaceNumber, found.alternateSetting);
      } catch (e) {
        // Give back what this attempt took (the claim is always this call's: one held already is refused above),
        // so another tab or program can claim the device.
        try { await device.releaseInterface(found.interfaceNumber); } catch { /* already gone */ }
        if (openedHere) { try { await device.close(); } catch { /* already gone */ } }
        throw new OdinError('claim-failed', `${CLAIM_HELP} (alternate setting: ${message(e)})`, { cause: e });
      }
    }
    return new WebUsbOdinTransport(device, found.interfaceNumber, found.epIn, found.epOut);
  }

  async send(data: Uint8Array, timeoutMs: number): Promise<void> {
    const r = await timeout(this.device.transferOut(this.epOut, data as Uint8Array<ArrayBuffer>), 'write', timeoutMs);
    if (r.status !== 'ok') throw new OdinError('usb', `USB write failed (${r.status})`);
    if (r.bytesWritten !== data.length) {
      throw new OdinError('usb', `USB write sent ${r.bytesWritten} of ${data.length} bytes`);
    }
  }

  async receive(maxLength: number, timeoutMs: number): Promise<Uint8Array> {
    // A queued read from earlier gets the next packet; wait for it first, keeping it if it times out again.
    while (this.pending) {
      const pending = this.pending;
      const r = await timeout(pending.promise, 'read', timeoutMs);
      this.pending = null;
      const data = result(r);
      if (data.length === 0) continue; // the zero-length packet it was queued for
      if (data.length > maxLength) throw new OdinError('protocol', `got ${data.length} bytes, expected at most ${maxLength}`);
      return data;
    }
    const pending = { promise: this.device.transferIn(this.epIn, maxLength), maxLength };
    this.pending = pending;
    // A read the browser itself fails is over: forget it, so the next receive starts a new one. A read that only
    // outlived our timeout stays, for the next receive to take.
    pending.promise.catch(() => { if (this.pending === pending) this.pending = null; });
    const r = await timeout(pending.promise, 'read', timeoutMs);
    this.pending = null;
    return result(r);
  }

  async close(): Promise<void> {
    try { await this.device.releaseInterface(this.interfaceNumber); } catch { /* gone already (it rebooted) */ }
    try { await this.device.close(); } catch { /* gone already */ }
  }
}

function result(r: USBInTransferResult): Uint8Array {
  if (r.status !== 'ok' || !r.data) throw new OdinError('usb', `USB read failed (${r.status})`);
  return new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

interface Found { interfaceNumber: number; alternateSetting: number; epIn: number; epOut: number; claimed: boolean }

/** The first CDC Data setting with exactly two endpoints, one bulk IN and one bulk OUT. */
export function findInterface(interfaces: readonly USBInterface[]): Found | undefined {
  for (const iface of interfaces) {
    for (const alt of iface.alternates) {
      if (alt.interfaceClass !== USB_CLASS_CDC_DATA || alt.endpoints.length !== 2) continue;
      const epIn = alt.endpoints.find((e) => e.direction === 'in' && e.type === 'bulk');
      const epOut = alt.endpoints.find((e) => e.direction === 'out' && e.type === 'bulk');
      if (epIn && epOut) {
        return {
          interfaceNumber: iface.interfaceNumber, alternateSetting: alt.alternateSetting,
          epIn: epIn.endpointNumber, epOut: epOut.endpointNumber, claimed: iface.claimed,
        };
      }
    }
  }
  return undefined;
}
