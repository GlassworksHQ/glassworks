// Simulated devices for the tests: the A133 boot ROM's FEL and our U-Boot's fastboot.
//
// The fake boot ROM knows the FEL wire format and the chip, not the host's code: routines the host
// uploads to the scratch area run in a small ARM interpreter (arm.ts), and only the SPL thunk
// (MIT, src/thunk.ts) is recognised by its bytes. See docs/research/kiln-cleanroom/fel-spec.md.
import { expect } from 'vitest';
import { A133, FEL_CHUNK, type BulkTransport } from '../src';
import { FEL_TO_SPL_THUNK } from '../src/thunk';
import { runArm, type ArmBus } from './arm';

// FEL wire facts, device side (linux-sunxi wiki, FEL/Protocol).
const USB_READ = 0x11, USB_WRITE = 0x12;
const FEL_VERSION = 0x001, FEL_WRITE = 0x101, FEL_EXEC = 0x102, FEL_READ = 0x103;
const REQ = { [FEL_VERSION]: 'version', [FEL_WRITE]: 'write', [FEL_EXEC]: 'exec', [FEL_READ]: 'read' } as const;

/** Host routines and their results must stay in this window (the spec's "scratch area"). */
export const SCRATCH = { start: A133.scratchAddr, end: A133.scratchAddr + 0x400 };
/** Below DRAM and above SRAM: the A133's peripherals (SID, CPUCFG, ...). */
const isMmio = (a: number) => a >= 0x01000000 && a < 0x40000000;

export const u32 = (b: Uint8Array, o: number) => new DataView(b.buffer, b.byteOffset).getUint32(o, true);
export const eq = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i]);
const ascii = (s: string) => new TextEncoder().encode(s);

/** A fake eGON SPL of `len` bytes with a valid checksum. */
export function makeSpl(len: number, extra = 0): Uint8Array {
  const b = new Uint8Array(len + extra);
  for (let i = 32; i < len; i++) b[i] = (i * 7 + 3) & 0xff;
  const v = new DataView(b.buffer);
  v.setUint32(0, 0xea000016, true);
  b.set(ascii('eGON.BT0'), 4);
  v.setUint32(16, len, true);
  let sum = 0x5f0a6c39;
  for (let i = 0; i < len; i += 4) if (i !== 12) sum = (sum + v.getUint32(i, true)) >>> 0;
  v.setUint32(12, sum, true);
  return b;
}

/** What the boot ROM would accept as an SPL at `b` (the same rule makeSpl writes). */
function egonValid(b: Uint8Array): boolean {
  if (b.length < 32 || String.fromCharCode(...b.subarray(4, 12)) !== 'eGON.BT0') return false;
  const len = u32(b, 16);
  if (len < 32 || len % 4 || len > b.length) return false;
  let sum = 0x5f0a6c39;
  for (let i = 0; i < len; i += 4) if (i !== 12) sum = (sum + u32(b, i)) >>> 0;
  return sum === u32(b, 12);
}

/** One USB transfer as the device saw it. */
export interface Xfer { dir: 'out' | 'in'; data: Uint8Array }
/** One FEL request with all its USB transfers. `routine`: it touched only the scratch area. */
export interface FelOp { req: 'version' | 'write' | 'exec' | 'read'; addr: number; len: number; routine: boolean; xfers: Xfer[] }
/** What host routines did on the chip. `after`: how many non-routine FEL ops came before it. */
export type Effect = { after: number } & (
  | { kind: 'mmio-read'; addr: number }
  | { kind: 'mmio-write'; addr: number; value: number }
  | { kind: 'cp15-read'; reg: 'SCTLR' | 'RMR' }
  | { kind: 'reset'; entry: number; aarch64: boolean });

/** The A133 boot ROM as far as FEL goes: AWUC/AWUS framing, the four FEL requests, sparse memory. */
export class FakeBrom implements BulkTransport {
  mem = new Map<number, Uint8Array>(); // 64 KiB pages
  regs = new Map<number, number>([[A133.sidSecureBootReg, 0]]);
  /** RES1 bits, CP15BEN and Z set, MMU off. */
  sctlr = 0x00c50838;
  /** Stack pointers the routines see, per mode (SVC 0x13 on entry, IRQ 0x12). */
  sp = { 0x13: 0x7000, 0x12: 0x2000 };
  /** The SoC id the version reply carries. */
  socId = 0x1855;
  /** Set to simulate an SPL that fails (DRAM init): the thunk leaves this code instead of eGON.FEL. */
  splFailure: string | null = null;
  pendingIn: Uint8Array[] = [];
  private usb: { left: number; buf: number[] } | null = null;
  private fel: { type: number; addr: number; len: number; stage: number } | null = null;
  felWrites: { addr: number; len: number }[] = [];
  ops: FelOp[] = [];
  effects: Effect[] = [];
  splRan: Uint8Array | null = null;
  resetEntry: number | null = null;
  closed = false;
  private rvbar = 0;
  private resetPending: { aarch64: boolean } | null = null;

  private get op(): FelOp | undefined { return this.ops.at(-1); }
  private get mainOps(): number { return this.ops.filter((o) => !o.routine).length; }

  async out(data: Uint8Array): Promise<void> {
    if (!this.usb) {
      expect(data.length).toBe(32);
      expect(String.fromCharCode(...data.subarray(0, 4))).toBe('AWUC');
      expect(u32(data, 12)).toBe(0x0c000000);
      expect(u32(data, 18)).toBe(u32(data, 8));
      if (!this.fel) this.ops.push({ req: 'version', addr: 0, len: 0, routine: false, xfers: [] });
      this.op!.xfers.push({ dir: 'out', data: data.slice() });
      const type = data[16]! | (data[17]! << 8);
      const len = u32(data, 8);
      if (type === USB_READ) { this.onUsbRead(len); return; }
      expect(type).toBe(USB_WRITE);
      this.usb = { left: len, buf: [] };
      return;
    }
    // data phase of a USB write (may arrive in pieces)
    this.op!.xfers.push({ dir: 'out', data: data.slice() });
    for (const x of data) this.usb.buf.push(x);
    this.usb.left -= data.length;
    expect(this.usb.left).toBeGreaterThanOrEqual(0);
    if (this.usb.left === 0) {
      const payload = Uint8Array.from(this.usb.buf);
      this.usb = null;
      this.onUsbWrite(payload);
      this.pendingIn.push(status13());
    }
  }

  async in(length: number): Promise<Uint8Array> {
    const next = this.pendingIn.shift();
    if (!next) throw new Error('host read with nothing queued');
    expect(next.length).toBe(length);
    this.op?.xfers.push({ dir: 'in', data: next });
    return next;
  }

  async inPacket(max: number): Promise<Uint8Array> {
    const next = this.pendingIn.shift();
    if (!next || next.length > max) throw new Error('unexpected packet read');
    this.op?.xfers.push({ dir: 'in', data: next });
    return next;
  }

  async close() { this.closed = true; }

  private onUsbWrite(p: Uint8Array) {
    if (this.fel?.type === FEL_WRITE && this.fel.stage === 0) {
      expect(p.length).toBe(this.fel.len);
      expect(p.length).toBeLessThanOrEqual(FEL_CHUNK);
      this.store(this.fel.addr, p);
      this.felWrites.push({ addr: this.fel.addr, len: p.length });
      this.fel.stage = 1; // FEL status next
      return;
    }
    expect(this.fel).toBeNull();
    expect(p.length).toBe(16);
    const type = u32(p, 0), addr = u32(p, 4), len = u32(p, 8);
    const req = REQ[type as keyof typeof REQ];
    if (!req) throw new Error(`unknown FEL request 0x${type.toString(16)}`);
    const op = this.op!;
    op.req = req; op.addr = addr; op.len = len;
    op.routine = req !== 'version' && addr >= SCRATCH.start && addr + Math.max(len, 1) <= SCRATCH.end;
    this.fel = { type, addr, len, stage: 0 };
    if (type === FEL_EXEC) { this.exec(addr); this.fel.stage = 1; }
  }

  private onUsbRead(len: number) {
    const f = this.fel;
    if (!f) throw new Error('USB read with no FEL request');
    if (f.stage === 1) { // FEL status
      expect(len).toBe(8);
      this.pendingIn.push(new Uint8Array(8), status13());
      this.fel = null;
      return;
    }
    if (f.type === FEL_VERSION) {
      expect(len).toBe(32);
      const v = new Uint8Array(32);
      v.set(ascii('AWUSBFEX'));
      new DataView(v.buffer).setUint32(8, (this.socId << 8) >>> 0, true);
      new DataView(v.buffer).setUint16(16, 1, true);
      new DataView(v.buffer).setUint32(20, 0x7e00, true);
      this.pendingIn.push(v, status13());
    } else {
      expect(f.type).toBe(FEL_READ);
      expect(len).toBe(f.len);
      this.pendingIn.push(this.load(f.addr, len), status13());
    }
    f.stage = 1;
  }

  private effect(e: Omit<Effect, 'after'> & Partial<Effect>) {
    this.effects.push({ ...e, after: this.mainOps } as Effect);
  }

  private exec(addr: number) {
    if (addr >= SCRATCH.start && addr < SCRATCH.end) { this.runRoutine(addr); return; }
    if (addr === A133.thunkAddr) { this.runThunk(); return; }
    throw new Error(`exec at 0x${addr.toString(16)}: not the scratch area or the thunk address`);
  }

  private runRoutine(addr: number) {
    const bus: ArmBus = {
      read32: (a) => {
        if (a & 3) throw new Error(`unaligned read at 0x${a.toString(16)}`);
        if (isMmio(a)) { this.effect({ kind: 'mmio-read', addr: a }); return this.regs.get(a) ?? 0xdeadbeef; }
        return u32(this.load(a, 4), 0);
      },
      write32: (a, v) => {
        if (a & 3) throw new Error(`unaligned write at 0x${a.toString(16)}`);
        if (isMmio(a)) {
          this.effect({ kind: 'mmio-write', addr: a, value: v >>> 0 });
          if (a === A133.rvbarReg) this.rvbar = v >>> 0;
          this.regs.set(a, v >>> 0);
          return;
        }
        this.storeU32(a, v);
      },
      read8: (a) => {
        if (isMmio(a)) throw new Error(`byte read of a register at 0x${a.toString(16)}`);
        return this.load(a, 1)[0]!;
      },
      write8: (a, v) => {
        if (isMmio(a)) throw new Error(`byte write of a register at 0x${a.toString(16)}`);
        this.store(a, Uint8Array.of(v));
      },
      mrc: (cp, opc1, crn, crm, opc2) => {
        if (cp === 15 && opc1 === 0 && crn === 1 && crm === 0 && opc2 === 0) { this.effect({ kind: 'cp15-read', reg: 'SCTLR' }); return this.sctlr; }
        if (cp === 15 && opc1 === 0 && crn === 12 && crm === 0 && opc2 === 2) { this.effect({ kind: 'cp15-read', reg: 'RMR' }); return 0; }
        throw new Error(`MRC p${cp}, ${opc1}, c${crn}, c${crm}, ${opc2}: not modelled by the fake boot ROM`);
      },
      mcr: (cp, opc1, crn, crm, opc2, value) => {
        if (cp === 15 && opc1 === 0 && crn === 12 && crm === 0 && opc2 === 2) {
          if (value & 2) this.resetPending = { aarch64: (value & 1) === 1 };
          return;
        }
        throw new Error(`MCR p${cp}, ${opc1}, c${crn}, c${crm}, ${opc2}: not modelled by the fake boot ROM`);
      },
      wfi: () => {
        if (!this.resetPending) throw new Error('WFI with no warm reset requested');
        this.resetEntry = this.rvbar;
        this.effect({ kind: 'reset', entry: this.rvbar, aarch64: this.resetPending.aarch64 });
        return true;
      },
    };
    runArm(bus, addr, { sp: this.sp, mode: 0x13 });
  }

  /** The thunk swaps each buf1/buf2 pair, runs the SPL if its eGON header checks out, swaps back. */
  private runThunk() {
    const words = FEL_TO_SPL_THUNK.length;
    const code = this.load(A133.thunkAddr, words * 4);
    expect(FEL_TO_SPL_THUNK.every((w, i) => u32(code, i * 4) === w)).toBe(true);
    let p = A133.thunkAddr + words * 4;
    const splAddr = u32(this.load(p, 4), 0); p += 4;
    const swaps: { buf1: number; buf2: number; size: number }[] = [];
    for (;;) {
      const t = this.load(p, 12); p += 12;
      const s = { buf1: u32(t, 0), buf2: u32(t, 4), size: u32(t, 8) };
      if (s.size === 0) break;
      swaps.push(s);
    }
    expect(p - A133.thunkAddr).toBeLessThanOrEqual(A133.thunkSize);
    expect({ splAddr, swaps }).toEqual({ splAddr: A133.splAddr, swaps: [...A133.swapBuffers] });
    const swap = () => {
      for (const s of swaps) {
        const a = this.load(s.buf1, s.size), b = this.load(s.buf2, s.size);
        this.store(s.buf1, b); this.store(s.buf2, a);
      }
    };
    swap();
    const head = this.load(splAddr, 32);
    const len = u32(head, 16);
    const spl = this.load(splAddr, Math.min(len, A133.sramSize));
    const ok = egonValid(spl);
    if (ok) this.splRan = spl;
    swap();
    this.store(splAddr + 4, ascii(!ok ? 'eGON.BAD' : this.splFailure ?? 'eGON.FEL'));
  }

  store(addr: number, data: Uint8Array) {
    for (let i = 0; i < data.length; i++) {
      const a = addr + i, page = a >>> 16;
      let p = this.mem.get(page);
      if (!p) { p = new Uint8Array(0x10000); this.mem.set(page, p); }
      p[a & 0xffff] = data[i]!;
    }
  }
  storeU32(addr: number, v: number) {
    const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, v >>> 0, true); this.store(addr, b);
  }
  load(addr: number, len: number): Uint8Array {
    const out = new Uint8Array(len);
    for (let i = 0; i < len; i++) { const a = addr + i; out[i] = this.mem.get(a >>> 16)?.[a & 0xffff] ?? 0; }
    return out;
  }
}

export function status13() {
  const s = new Uint8Array(13); s.set([0x41, 0x57, 0x55, 0x53]); return s;
}


const SECTOR = 512;

/** Our U-Boot's fastboot as far as the installer uses it: getvar, oem run (a few shell commands), upload, download, flash. */
export class FakeUboot implements BulkTransport {
  /** data is allocated on first use, so a 1.5 GiB super costs nothing unless read. */
  parts = new Map<string, { start: number; size: number; data?: Uint8Array }>();
  env = new Map<string, string>();
  buf: Uint8Array = new Uint8Array(0); // the staging buffer (only what we touch)
  replies: Uint8Array[] = [];
  commands: string[] = [];
  flashed: string[] = [];
  private download: { buf: Uint8Array; got: number } | null = null;
  closed = false;
  rebooted = false;

  /** Default: small synthetic partitions. Pass [name, size | data] to model the real panel. */
  constructor(layout: [string, number | Uint8Array][] = [['boot', 0x2000000 / 64], ['vbmeta', 0x10000], ['super', 0x40000]]) {
    let lba = 0x8000;
    for (const [name, x] of layout) {
      const size = typeof x === 'number' ? x : x.length;
      const data = typeof x === 'number' ? undefined : x.slice();
      this.parts.set(name, { start: lba, size, data });
      lba += size / SECTOR;
    }
  }

  data(name: string): Uint8Array {
    const p = this.parts.get(name)!;
    p.data ??= new Uint8Array(p.size).map((_, i) => (i * 31 + name.length) & 0xff);
    return p.data;
  }

  private reply(s: string | Uint8Array) {
    this.replies.push(typeof s === 'string' ? new TextEncoder().encode(s) : s);
  }

  async out(data: Uint8Array): Promise<void> {
    if (this.download) {
      const d = this.download;
      expect(d.got + data.length).toBeLessThanOrEqual(d.buf.length);
      d.buf.set(data, d.got);
      d.got += data.length;
      if (d.got === d.buf.length) { this.buf = d.buf; this.download = null; this.reply('OKAY'); }
      return;
    }
    const cmd = new TextDecoder().decode(data);
    expect(data.length).toBeLessThanOrEqual(64);
    this.commands.push(cmd);
    if (cmd.startsWith('getvar:partition-size:')) {
      const p = this.parts.get(cmd.slice(22));
      this.reply(p ? `OKAY0x${p.size.toString(16).padStart(16, '0')}` : 'FAILunknown partition');
    } else if (cmd === 'getvar:product') {
      this.reply('OKAYsunxi');
    } else if (cmd.startsWith('oem run:')) {
      this.reply(this.run(cmd.slice(8)) ? 'OKAY' : 'FAILcommand failed');
    } else if (cmd === 'upload') {
      const n = Number.parseInt(this.env.get('fastboot_upload_size') ?? '0', 16);
      this.reply(`DATA${n.toString(16).padStart(8, '0')}`);
      for (let o = 0; o < n; o += 4096) this.reply(this.buf.slice(o, o + 4096)); // 4 KiB tx requests
      this.reply('OKAY');
    } else if (cmd.startsWith('download:')) {
      this.download = { buf: new Uint8Array(Number.parseInt(cmd.slice(9), 16)), got: 0 };
      this.reply(`DATA${cmd.slice(9)}`);
    } else if (cmd.startsWith('flash:')) {
      const name = cmd.slice(6);
      if (!this.parts.has(name)) { this.reply('FAILno such partition'); return; }
      this.reply('INFOwriting');
      this.data(name).set(this.buf, 0);
      this.flashed.push(name);
      this.reply('OKAY');
    } else if (cmd === 'reboot') {
      this.rebooted = true;
      this.reply('OKAY');
    } else {
      this.reply('FAILunknown command');
    }
  }

  /** The handful of U-Boot shell commands fb-read-partition.sh sends; hex env values like U-Boot. */
  private run(line: string): boolean {
    const sub = (s: string) => s.replace(/\$(\w+)/g, (_, k: string) => this.env.get(k) ?? '');
    const a = sub(line).split(' ');
    if (a[0] === 'mmc' && a[1] === 'dev') return a[2] === '1';
    if (a[0] === 'part' && a[2] === 'mmc' && a[3] === '1') {
      const p = this.parts.get(a[4]!);
      if (!p) return false;
      this.env.set(a[5]!, (a[1] === 'start' ? p.start : p.size / SECTOR).toString(16));
      return true;
    }
    if (a[0] === 'mmc' && a[1] === 'read' && a[2] === '0x50000000') {
      const start = Number.parseInt(a[3]!, 16), n = Number.parseInt(a[4]!, 16);
      const e = [...this.parts.entries()].find(([, q]) => q.start === start);
      if (!e || n * SECTOR !== e[1].size) return false;
      this.buf = this.data(e[0]).slice();
      return true;
    }
    if (a[0] === 'setexpr' && a[1] === 'fastboot_upload_size' && a[3] === '*' && a[4] === '200') {
      this.env.set('fastboot_upload_size', (Number.parseInt(a[2]!, 16) * 0x200).toString(16));
      return true;
    }
    return false;
  }

  async inPacket(max: number): Promise<Uint8Array> {
    const r = this.replies.shift();
    if (!r) throw new Error('host read with nothing queued');
    expect(r.length).toBeLessThanOrEqual(max);
    return r;
  }

  async in(length: number): Promise<Uint8Array> {
    const out = new Uint8Array(length);
    let got = 0;
    while (got < length) {
      const r = this.replies.shift();
      if (!r) throw new Error('host read with nothing queued');
      if (r.length > length - got) throw new Error('read would swallow the next reply');
      out.set(r, got); got += r.length;
    }
    return out;
  }

  async close() { this.closed = true; }
}

