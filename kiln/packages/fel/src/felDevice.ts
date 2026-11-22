// SPDX-License-Identifier: MIT
/// <reference types="w3c-web-usb" preserve="true" />
// Copyright (c) 2026 Glassworks contributors
// Written clean-room from docs/research/kiln-cleanroom/fel-spec.md (#355).
//
// One FEL session with an Allwinner boot ROM over a bulk transport. The wire format is on the
// linux-sunxi wiki (FEL/Protocol): every exchange is a 32-byte AWUC request, an optional data
// phase and a 13-byte AWUS status, and a FEL request is a 16-byte message sent as such an exchange.
import { A133, FEL_CHUNK } from './a133.ts';
import { readSctlr, readWord, warmReset64, type ReadRoutine, type Routine } from './routines.ts';
import { splLength, splWrites, thunkImage } from './spl.ts';
import { WebUsbBulk, type BulkTransport } from './transport.ts';

export const FEL_FILTER = { vendorId: 0x1f3a, productId: 0xefe8 } as const;

export interface FelVersion { signature: string; socId: number; protocol: number; scratchpad: number; raw: Uint8Array }

/** `n` as 8 upper-case hex digits, no prefix. */
export const hex = (n: number) => (n >>> 0).toString(16).toUpperCase().padStart(8, '0');

const HOST_SENDS = 0x12, HOST_RECEIVES = 0x11;
const FEL = { version: 0x001, write: 0x101, exec: 0x102, read: 0x103 } as const;
const SCRATCH_END = A133.scratchAddr + 0x400;
const SCTLR_M = 1 << 0;
/** The ROM needs this pause after the SPL returns, before the next request (untested; kept). */
const AFTER_SPL_MS = 250;
/** The chip resets as the reset routine runs, so its FEL status may never come. */
const RESET_STATUS_TIMEOUT_MS = 2000;

const ascii = (b: Uint8Array) => String.fromCharCode(...b);
const printable = (b: Uint8Array) => [...b].map((x) => (x >= 0x20 && x < 0x7f ? String.fromCharCode(x) : '.')).join('');

function awuc(length: number, direction: number): Uint8Array {
  const b = new Uint8Array(32);
  const v = new DataView(b.buffer);
  b.set([0x41, 0x57, 0x55, 0x43]); // "AWUC"
  v.setUint32(8, length, true);
  v.setUint32(12, 0x0c000000, true);
  v.setUint16(16, direction, true);
  v.setUint32(18, length, true);
  return b;
}

function felMessage(code: number, addr: number, length: number): Uint8Array {
  const m = new Uint8Array(16);
  const v = new DataView(m.buffer);
  v.setUint32(0, code, true);
  v.setUint32(4, addr, true);
  v.setUint32(8, length, true);
  return m;
}

export class FelDevice {
  log: (line: string) => void = () => {};

  constructor(private readonly t: BulkTransport) {}

  static async request(): Promise<FelDevice> {
    return FelDevice.fromDevice(await navigator.usb.requestDevice({ filters: [FEL_FILTER] }));
  }

  static async fromDevice(d: USBDevice): Promise<FelDevice> {
    return new FelDevice(await WebUsbBulk.open(d));
  }

  /** FEL devices this page may already open without a prompt. */
  static async known(): Promise<USBDevice[]> {
    return (await navigator.usb.getDevices())
      .filter((d) => d.vendorId === FEL_FILTER.vendorId && d.productId === FEL_FILTER.productId);
  }

  private async status(timeoutMs?: number): Promise<void> {
    const s = await this.t.in(13, timeoutMs);
    if (ascii(s.subarray(0, 4)) !== 'AWUS') throw new Error('FEL: unexpected USB status (no AWUS).');
  }
  private async send(data: Uint8Array): Promise<void> {
    await this.t.out(awuc(data.length, HOST_SENDS));
    await this.t.out(data);
    await this.status();
  }
  private async receive(length: number, timeoutMs?: number): Promise<Uint8Array> {
    await this.t.out(awuc(length, HOST_RECEIVES), timeoutMs);
    const data = await this.t.in(length, timeoutMs);
    await this.status(timeoutMs);
    return data;
  }

  private async felRequest(code: number, addr: number, length: number): Promise<void> {
    await this.send(felMessage(code, addr, length));
  }
  private async felStatus(timeoutMs?: number): Promise<void> {
    await this.receive(8, timeoutMs);
  }

  async version(): Promise<FelVersion> {
    await this.felRequest(FEL.version, 0, 0);
    const raw = await this.receive(32);
    await this.felStatus();
    const v = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
    return {
      signature: ascii(raw.subarray(0, 8)).replace(/\0+$/, ''),
      socId: (v.getUint32(8, true) >>> 8) & 0xffff,
      protocol: v.getUint16(16, true),
      scratchpad: v.getUint32(20, true),
      raw,
    };
  }

  async read(addr: number, len: number): Promise<Uint8Array> {
    await this.felRequest(FEL.read, addr, len);
    const data = await this.receive(len);
    await this.felStatus();
    return data;
  }

  async write(addr: number, data: Uint8Array, onProgress?: (done: number, total: number) => void): Promise<void> {
    for (let off = 0; off < data.length; off += FEL_CHUNK) {
      const piece = data.subarray(off, Math.min(off + FEL_CHUNK, data.length));
      await this.felRequest(FEL.write, addr + off, piece.length);
      await this.send(piece);
      await this.felStatus();
      onProgress?.(off + piece.length, data.length);
    }
  }

  async exec(addr: number): Promise<void> {
    await this.felRequest(FEL.exec, addr, 0);
    await this.felStatus();
  }

  private async upload(r: Routine): Promise<void> {
    if (A133.scratchAddr + r.image.length > SCRATCH_END) throw new Error('FEL routine does not fit in the scratch area');
    await this.write(A133.scratchAddr, r.image);
  }

  /** Uploads a routine to the scratch area, runs it, and returns its result word. */
  private async run(r: ReadRoutine): Promise<number> {
    await this.upload(r);
    await this.exec(A133.scratchAddr);
    const b = await this.read(A133.scratchAddr + r.resultAt, 4);
    return new DataView(b.buffer, b.byteOffset, 4).getUint32(0, true);
  }

  async readl(addr: number): Promise<number> {
    return this.run(readWord(addr));
  }

  /** Runs the SPL from u-boot-sunxi-with-spl.bin. DRAM is up when this resolves. */
  async runSpl(splWithUboot: Uint8Array): Promise<void> {
    const len = splLength(splWithUboot);
    const sctlr = await this.run(readSctlr());
    if (sctlr & SCTLR_M) {
      throw new Error(`The boot ROM left the MMU on (SCTLR 0x${hex(sctlr)}), which Kiln does not expect on an A133.`);
    }
    this.log(`SPL: ${len} bytes, SCTLR 0x${hex(sctlr)}`);
    for (const w of splWrites(splWithUboot.subarray(0, len))) await this.write(w.addr, w.bytes);
    await this.write(A133.thunkAddr, thunkImage());
    await this.exec(A133.thunkAddr);
    await new Promise((r) => setTimeout(r, AFTER_SPL_MS));
    const mark = await this.read(A133.splAddr + 4, 8);
    if (ascii(mark) !== 'eGON.FEL') throw new Error(`The SPL did not finish (it left '${printable(mark)}').`);
    this.log('SPL: done, back in FEL with DRAM up');
  }

  /** Warm-resets the CPU into AArch64 at `entry`. The chip leaves FEL. */
  async reset64(entry: number): Promise<void> {
    await this.upload(warmReset64(A133.rvbarReg, entry));
    await this.t.out(awuc(16, HOST_SENDS));
    await this.t.out(felMessage(FEL.exec, A133.scratchAddr, 0));
    // The execute is sent. From here the chip may reset before it answers, or USB may drop.
    try {
      await this.status(RESET_STATUS_TIMEOUT_MS);
      await this.felStatus(RESET_STATUS_TIMEOUT_MS);
    } catch (e) {
      this.log(`reset: no FEL status after the execute (${(e as Error).message ?? e}), as expected while the chip resets`);
    }
    this.log(`reset: warm reset into AArch64 at 0x${hex(entry)}`);
  }

  async close(): Promise<void> {
    await this.t.close();
  }
}
