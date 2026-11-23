/// <reference types="w3c-web-usb" preserve="true" />
// A session with a Samsung device in Download mode: handshake, begin, PIT, one guarded write, end.
//
// The sequences are ported from Heimdall's BridgeManager.cpp and FlashAction.cpp (MIT, Copyright (c) 2010-2017
// Benjamin Dobell, Glass Echidna; Copyright (c) 2021-2024 Henrik Grimler; see ../LICENSE), Henrik Grimler's fork at
// commit 8f3044db985fd9710038f04886b51240ddbb2834: InitialiseProtocol, BeginSession, RequestDeviceType,
// ReceivePitFile, SendFile, EndSession, and FlashAction's total-bytes packet and partition size check. The empty
// (zero-length) transfers are where Heimdall has them. Where this differs from Heimdall, the README says so
// ("Compared with Heimdall"). Nothing here comes from Samsung's own software.

import { OdinError } from './errors.ts';
import { BinaryType, type Pit, type PitEntry, parsePit, partitionBytes } from './pit.ts';
import * as P from './protocol.ts';
import { type OdinTransport, WebUsbOdinTransport } from './transport.ts';

/**
 * The partitions {@link OdinDevice.flash} can write at all. `RECOVERY` is the default; `VBMETA` needs
 * `writable: ['RECOVERY', 'VBMETA']`. Nothing else, by design: this library is for putting a recovery on a device.
 */
export type WritablePartition = 'RECOVERY' | 'VBMETA';
const WRITABLE: readonly WritablePartition[] = ['RECOVERY', 'VBMETA'];

export interface OdinOptions {
  /** What {@link OdinDevice.flash} may write. Default `['RECOVERY']`. */
  writable?: readonly WritablePartition[];
}

export interface SessionOptions {
  /**
   * The bytes this session will write, the sum of every image's size, sent to the device before the PIT as Heimdall
   * does. A flash can't write more than this. Leave it out for a session that only reads (the PIT, the device type).
   */
  totalBytes?: number;
  /**
   * Skip the ODIN/LOKE handshake: the device already did it in an earlier session that ended without a reboot
   * (Heimdall's `--resume`).
   */
  resume?: boolean;
}

/** What the device says about itself before a session (DVIF). Its serial number (`UN`) is left out on purpose. */
export interface DeviceInfo {
  /** E.g. `SM-T580`. */
  model?: string;
  /** The sales code (CSC), e.g. `XAR`. */
  salesCode?: string;
  /** The bootloader's version string. */
  bootloaderVersion?: string;
  /** Every key and value the device sent, without `UN`. */
  properties: Readonly<Record<string, string>>;
}

export interface FlashProgress {
  /** Image bytes the device has acknowledged so far. */
  sentBytes: number;
  totalBytes: number;
}

export interface FlashRequest {
  /** The partition's name, as the PIT has it. Must be one of the device's `writable` partitions. */
  partition: WritablePartition;
  /** That partition's entry, from the PIT this session downloaded ({@link OdinDevice.pit}). */
  entry: PitEntry;
  /** The image, written as it is (no sparse images, no compression). */
  image: Blob | Uint8Array;
  onProgress?: (progress: FlashProgress) => void;
  /**
   * Stops between USB packets. A flash stopped part way leaves the partition partly written: the device stays in
   * Download mode, and needs a reboot into Download mode before a new session can write it again.
   */
  signal?: AbortSignal;
}

/** `failed`: a session setup went wrong part way, so the device may hold settings this side doesn't know. */
type State = 'connected' | 'session' | 'ended' | 'failed' | 'closed';

/** No PIT is near this (one is 28 bytes plus 132 an entry); a bigger announcement means something is wrong. */
const MAX_PIT_BYTES = 1024 * 1024;
/** The response type the bootloader sends instead of the expected one when it fails, with a signed status. */
const STATUS_FAIL = 0xffffffff;

export class OdinDevice {
  private state: State = 'connected';
  private readonly writable: ReadonlySet<WritablePartition>;
  private shape: P.TransferShape = P.LEGACY_TRANSFER;
  private budget = 0;
  private downloaded: Pit | null = null;
  /** Set when a write stopped part way: the device's state is unknown, so no more writes this session. */
  private broken = false;
  /** The call using the transport now. One at a time: two would mix their packets and answers. */
  private busy: string | null = null;

  /** `transport`: WebUSB from {@link OdinDevice.open}, or any other (the tests' fake). */
  constructor(readonly transport: OdinTransport, options: OdinOptions = {}) {
    this.writable = writableSet(options);
  }

  /** Opens a WebUSB device (from `navigator.usb.requestDevice({ filters: ODIN_USB_FILTERS })`) and claims it. */
  static async open(device: USBDevice, options: OdinOptions = {}): Promise<OdinDevice> {
    // Checked and copied before the claim, so a bad option, or one changed while the device opens, can't leave the
    // interface held.
    const writable = [...writableSet(options)];
    const transport = await WebUsbOdinTransport.open(device);
    try {
      return new OdinDevice(transport, { writable });
    } catch (e) {
      await transport.close();
      throw e;
    }
  }

  /** The file transfer's packet and sequence sizes, which {@link beginSession} settles. */
  get transferShape(): P.TransferShape {
    return this.shape;
  }

  /**
   * Asks the device for its model, sales code and bootloader version (DVIF), before any session. Not from Heimdall:
   * the query and its `@#KEY=VALUE;…` answer are as samloader-rs reads them from Samsung's bootloader. Devices
   * that don't know it don't answer, and this throws.
   */
  async info(): Promise<DeviceInfo> {
    return this.exclusive('info()', async () => {
      this.need('connected', 'device info is asked before the session begins');
      await this.transport.send(new TextEncoder().encode('DVIF'), P.Timeouts.handshake);
      let data: Uint8Array;
      try {
        data = await this.transport.receive(1024, P.Timeouts.handshake);
      } catch (e) {
        throw new OdinError('protocol', "the device didn't answer the device info query (DVIF)", { cause: e });
      }
      return parseDeviceInfo(new TextDecoder('latin1').decode(data));
    });
  }

  /**
   * The handshake (unless `resume`), then the session: the device's answer picks the transfer shape, and
   * `totalBytes`, if given, is announced. Heimdall's order: Initialise, BeginSession, then FlashAction's total bytes.
   */
  async beginSession(options: SessionOptions = {}): Promise<void> {
    return this.exclusive('beginSession()', async () => {
      this.need('connected', 'a session begins once per connection');
      const total = options.totalBytes ?? 0;
      if (!Number.isSafeInteger(total) || total < 0) throw new RangeError(`totalBytes must be a whole number, not ${total}`);
      // From here a failure ends this connection: the device may have taken a part size or a total that this side
      // can't undo, so a retry starts over (a new connection, with `resume` if the handshake was done).
      this.state = 'failed';
      if (!options.resume) await this.handshake();

      const answer = await this.command(P.beginSession(), P.Control.Session);
      // "0 means changing the packet size is not supported" (BridgeManager::BeginSession).
      let shape = P.LEGACY_TRANSFER;
      if (answer !== 0) {
        const result = await this.command(P.filePartSize(P.FAST_TRANSFER.packetSize), P.Control.Session);
        if (result !== 0) throw new OdinError('protocol', `the device refused a ${P.FAST_TRANSFER.packetSize}-byte part size (answer ${result})`);
        shape = P.FAST_TRANSFER;
      }
      if (total > 0) {
        const result = await this.command(P.totalBytes(total), P.Control.Session);
        if (result !== 0) throw new OdinError('protocol', `the device refused the session's total of ${total} bytes (answer ${result})`);
      }
      this.budget = total;
      this.shape = shape;
      this.state = 'session';
    });
  }

  /** Heimdall's RequestDeviceType: a number the device sends back. What it means isn't known ("kDeviceType = 1, // ?"). */
  async deviceType(): Promise<number> {
    return this.exclusive('deviceType()', async () => {
      this.need('session', 'the device type is asked during a session');
      return this.command(P.deviceType(), P.Control.Session);
    });
  }

  /** Downloads and parses the device's PIT (BridgeManager::ReceivePitFile). A flash needs an entry from it. */
  async pit(): Promise<Pit> {
    return this.exclusive('pit()', async () => {
      this.need('session', 'the PIT is downloaded during a session');
      const size = await this.command(P.pitDump(), P.Control.PitFile);
      if (size === 0 || size > MAX_PIT_BYTES) throw new OdinError('protocol', `the device announced a ${size}-byte PIT`);
      const parts = Math.ceil(size / P.PIT_PART_SIZE);
      const data = new Uint8Array(size);
      let got = 0;
      for (let i = 0; i < parts; i++) {
        await this.sendPacket(P.pitPart(i), { after: true });
        const part = await this.transport.receive(P.PIT_PART_SIZE, P.Timeouts.receive);
        if (got + part.length > size) throw new OdinError('protocol', `the PIT ran past its announced ${size} bytes`);
        data.set(part, got);
        got += part.length;
      }
      await this.emptyReceive(); // after the last part, as Heimdall reads it
      if (got !== size) throw new OdinError('protocol', `got ${got} of the PIT's ${size} bytes`);
      await this.command(P.pitEnd(), P.Control.PitFile);
      const pit = parsePit(data);
      this.downloaded = pit;
      return pit;
    });
  }

  /**
   * Writes one image to one partition (BridgeManager::SendFile, to the application processor). Refuses, before
   * anything is sent, unless: the partition is writable on this device; `entry` is that partition's entry in the
   * PIT this session downloaded, the only one with its name; it's an application processor partition on eMMC or UFS
   * with a known size; the image isn't empty and fits it; and the session's `totalBytes` covers it.
   */
  async flash(request: FlashRequest): Promise<void> {
    return this.exclusive('flash()', async () => {
      const { partition, entry, image, onProgress, signal } = request;
      this.need('session', 'a flash happens during a session');
      const size = image instanceof Uint8Array ? image.length : image.size;
      this.check(partition, entry, size);
      signal?.throwIfAborted();

      this.budget -= size;
      try {
        await this.command(P.fileFlash(), P.Control.FileTransfer);
        const { packetSize, sequenceTimeoutMs } = this.shape;
        const packet = new Uint8Array(packetSize);
        const sequences = P.plan(size, this.shape);
        let sent = 0;
        for (const [s, sequence] of sequences.entries()) {
          signal?.throwIfAborted();
          await this.command(P.fileSequence(sequence.parts * packetSize), P.Control.FileTransfer);
          for (let part = 0; part < sequence.parts; part++) {
            signal?.throwIfAborted();
            const bytes = Math.min(packetSize, size - sent);
            packet.set(await read(image, sent, bytes));
            if (bytes < packetSize) packet.fill(0, bytes); // Heimdall sends every part whole, zero-padded
            // "This empty transfer thing is entirely ridiculous, but sadly it seems to be required." (Heimdall)
            await this.sendPacket(packet, { before: part > 0 });
            const index = await this.response(P.RESPONSE_FILE_PART, P.Timeouts.receive);
            if (index !== part) throw new OdinError('protocol', `the device acknowledged part ${index}, expected ${part}`);
            sent += bytes;
            onProgress?.({ sentBytes: sent, totalBytes: size });
          }
          signal?.throwIfAborted(); // before the end packet, which has the device write the sequence
          const last = s === sequences.length - 1;
          await this.sendPacket(P.filePhoneEnd(sequence.effectiveBytes, entry.deviceType, entry.identifier, last),
            { before: true, after: true });
          await this.response(P.Control.FileTransfer, sequenceTimeoutMs);
        }
      } catch (e) {
        this.broken = true;
        throw e;
      }
    });
  }

  /**
   * Ends the session (BridgeManager::EndSession). With `reboot`, the device then restarts. Without it, it stays in
   * Download mode, and a new connection can begin another session with `resume`.
   */
  async end(options: { reboot?: boolean } = {}): Promise<void> {
    return this.exclusive('end()', async () => {
      this.need('session', 'only a session can end');
      this.state = 'ended';
      await this.command(P.endSession(), P.Control.EndSession);
      if (options.reboot) {
        await this.sendPacket(P.reboot(), { after: true });
        // Heimdall waits for this answer; a device may restart before it sends one (samloader-rs reads it
        // best-effort), and by then nothing is left to go wrong.
        try { await this.response(P.Control.EndSession, P.Timeouts.receive); } catch { /* rebooting */ }
      }
    });
  }

  /** Releases the USB interface. */
  async close(): Promise<void> {
    return this.exclusive('close()', async () => {
      this.state = 'closed';
      await this.transport.close();
    });
  }

  /**
   * Runs one public call with the transport to itself. A call made while another runs (from a progress callback,
   * or without awaiting the last one) is refused with a `state` error; it isn't queued.
   */
  private async exclusive<T>(what: string, run: () => Promise<T>): Promise<T> {
    if (this.busy) throw new OdinError('state', `${what} can't start while ${this.busy} is running: one call at a time`);
    this.busy = what;
    try {
      return await run();
    } finally {
      this.busy = null;
    }
  }

  // --- the checks before a write

  private check(partition: WritablePartition, entry: PitEntry, size: number): void {
    const refuse = (why: string) => { throw new OdinError('refused', `won't write ${String(partition)}: ${why}`); };
    if (this.broken) refuse('an earlier write in this session stopped part way');
    if (!WRITABLE.includes(partition)) refuse(`only ${WRITABLE.join(' and ')} can ever be written`);
    if (!this.writable.has(partition)) refuse(`it isn't writable on this device (writable: ${[...this.writable].join(', ')})`);
    const pit = this.downloaded;
    if (!pit) refuse('download the PIT in this session first');
    if (!pit!.entries.includes(entry)) refuse("the entry isn't from the PIT this session downloaded");
    if (entry.partitionName !== partition) refuse(`the entry is ${JSON.stringify(entry.partitionName)}`);
    if (pit!.entries.filter((e) => e.partitionName === partition).length !== 1) refuse('the PIT has it more than once');
    if (entry.binaryType !== BinaryType.ApplicationProcessor) refuse("it isn't an application processor partition");
    const capacity = partitionBytes(entry);
    if (capacity === undefined) refuse(`the PIT doesn't give its size (device type ${entry.deviceType}, ${entry.blockCount} blocks)`);
    if (size === 0) refuse('the image is empty');
    if (size > capacity!) refuse(`the image is ${size} bytes and the partition ${capacity}`);
    if (size > this.budget) refuse(`the session announced ${this.budget} more bytes and the image is ${size}`);
  }

  // --- packets

  private need(state: State, why: string): void {
    if (this.state !== state) throw new OdinError('state', `${why} (the connection is ${this.state})`);
  }

  private async handshake(): Promise<void> {
    await this.transport.send(P.HANDSHAKE, P.Timeouts.handshake);
    let reply: string;
    try {
      reply = text(await this.transport.receive(64, P.Timeouts.handshake));
      // A FAIL left over from an earlier exchange can come first (samloader-rs), alone or in the same read.
      if (reply === 'FAIL') reply = text(await this.transport.receive(64, P.Timeouts.handshake));
      else if (reply === 'FAILLOKE') reply = 'LOKE';
    } catch (e) {
      throw new OdinError('handshake', "the device didn't answer the handshake: is it in Download mode?", { cause: e });
    }
    if (reply !== 'LOKE') throw new OdinError('handshake', `the device answered the handshake with ${JSON.stringify(reply)}, not "LOKE"`);
  }

  /** One control packet with an empty transfer after it (SendPacket's default), and its response's value. */
  private async command(packet: Uint8Array, type: number, timeoutMs: number = P.Timeouts.receive): Promise<number> {
    await this.sendPacket(packet, { after: true });
    return this.response(type, timeoutMs);
  }

  private async sendPacket(data: Uint8Array, empty: { before?: boolean; after?: boolean }): Promise<void> {
    if (empty.before) await this.emptySend();
    await this.transport.send(data, P.Timeouts.send);
    if (empty.after) await this.emptySend();
  }

  /** Heimdall goes on when an empty transfer fails ("Continuing anyway..."). */
  private async emptySend(): Promise<void> {
    try { await this.transport.send(new Uint8Array(0), P.Timeouts.empty); } catch { /* as Heimdall */ }
  }

  private async emptyReceive(): Promise<void> {
    try { await this.transport.receive(P.EMPTY_READ_SIZE, P.Timeouts.empty); } catch { /* as Heimdall */ }
  }

  /** An 8-byte response of `type`; its value. The device's failure status throws with its code. */
  private async response(type: number, timeoutMs: number): Promise<number> {
    let data = await this.transport.receive(P.RESPONSE_SIZE, timeoutMs);
    if (data.length === 0) data = await this.transport.receive(P.RESPONSE_SIZE, timeoutMs); // a stray empty packet
    let r: P.Response;
    try {
      r = P.parseResponse(data);
    } catch (e) {
      throw new OdinError('protocol', (e as Error).message, { cause: e });
    }
    const status = r.value | 0;
    if (r.type === STATUS_FAIL) throw new OdinError('device', `the device reported a failure (status ${status})`, { status });
    if (r.type !== type) throw new OdinError('protocol', `expected a response of type 0x${type.toString(16)}, got 0x${r.type.toString(16)}`);
    if (status < 0) throw new OdinError('device', `the device reported a failure (status ${status})`, { status });
    return r.value;
  }
}

async function read(image: Blob | Uint8Array, offset: number, length: number): Promise<Uint8Array> {
  if (image instanceof Uint8Array) return image.subarray(offset, offset + length);
  return new Uint8Array(await image.slice(offset, offset + length).arrayBuffer());
}

function text(data: Uint8Array): string {
  return new TextDecoder('latin1').decode(data).replace(/\0+$/, '');
}

/** The writable partitions in `options`, or a `refused` error for any other. */
function writableSet(options: OdinOptions): ReadonlySet<WritablePartition> {
  const writable = options.writable ?? ['RECOVERY'];
  for (const p of writable) {
    if (!WRITABLE.includes(p)) throw new OdinError('refused', `${String(p)} can't be made writable: only ${WRITABLE.join(' and ')} can`);
  }
  return new Set(writable);
}

/** Parses DVIF's `@#KEY=VALUE;KEY=VALUE;…`. Drops `UN`, the device's serial number. */
export function parseDeviceInfo(reply: string): DeviceInfo {
  const at = reply.indexOf('@#');
  const body = at >= 0 ? reply.slice(at + 2) : reply;
  const properties: Record<string, string> = {};
  for (const token of body.split(';')) {
    const eq = token.indexOf('=');
    if (eq <= 0) continue;
    const key = token.slice(0, eq).trim();
    if (key === '' || key === 'UN') continue;
    properties[key] = token.slice(eq + 1).replace(/\0+$/, '').trim();
  }
  if (Object.keys(properties).length === 0) throw new OdinError('protocol', "the device's info answer has no KEY=VALUE pairs");
  const value = (key: string) => properties[key] || undefined;
  return Object.freeze({
    model: value('MODEL'),
    salesCode: value('SALES'),
    bootloaderVersion: value('VER'),
    properties: Object.freeze(properties),
  });
}
