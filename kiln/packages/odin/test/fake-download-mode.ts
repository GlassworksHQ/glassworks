// A Samsung device in Download mode, as far as this library talks to it, built from Heimdall's source (no device):
// the device side of the packets in Heimdall's heimdall/source (Henrik Grimler's fork, commit 8f3044db985f), so every
// byte the host sends is checked against the format there, and every answer has Heimdall's format.
//
//   handshake   "ODIN" (4 bytes)                          → "LOKE" (4 bytes)
//   DVIF        "DVIF" (4 bytes, samloader-rs)             → "@#MODEL=…;SALES=…;VER=…;UN=…;" (ASCII)
//   session     1024 bytes: 0x64, request, args…          → 8 bytes: 0x64, value
//                 0 begin (protocol version)                   the begin answer (non-zero: takes a part size)
//                 1 device type                                the device type
//                 2 total bytes (u64: low, high)               0
//                 5 file part size                             0
//   PIT         1024 bytes: 0x65, 1 dump                   → 0x65, the PIT's size
//               1024 bytes: 0x65, 2 part, index            → that part of the PIT, at most 500 bytes
//               1024 bytes: 0x65, 3 end                    → 0x65, 0
//   file        1024 bytes: 0x66, 0 flash                  → 0x66, 0
//               1024 bytes: 0x66, 2 part, sequence bytes   → 0x66, 0
//               the parts, each exactly the part size      → 8 bytes: 0x00, the part's index in its sequence
//               1024 bytes: 0x66, 3 end, destination, effective bytes, 0, device type, identifier, end of file
//                                                          → 0x66, 0, once the sequence is written
//   end         1024 bytes: 0x67, 0 end session / 1 reboot → 0x67, 0
//
// Empty (zero-length) transfers are logged, not required: the tests check where they fall against Heimdall's
// SendPacket flags. Every partition starts filled with 0xee, so a test sees exactly which bytes a flash wrote.
import { OdinError } from '../src/errors.ts';
import { PIT_ENTRY_SIZE, PIT_HEADER_SIZE, PIT_MAGIC, type PitEntry } from '../src/pit.ts';
import type { OdinTransport } from '../src/transport.ts';

const CONTROL_SIZE = 1024;
const PIT_PART = 500;
const FAIL = 0xffffffff;

/** One line of the wire log: what crossed the cable, in the order it did. */
export type Wire =
  | { dir: 'out'; what: 'empty' }
  | { dir: 'out'; what: 'text'; text: string }
  | { dir: 'out'; what: 'control'; type: number; request: number; args: number[] }
  | { dir: 'out'; what: 'part'; bytes: number }
  | { dir: 'in'; what: 'reply'; bytes: number; type?: number; value?: number }
  | { dir: 'in'; what: 'timeout' };

export interface FakeOptions {
  /** The PIT it sends; build one with {@link buildPit}. */
  pit: Uint8Array;
  /** Partitions, by PIT identifier, with their size in bytes. */
  partitions: [identifier: number, bytes: number][];
  /** Its answer to begin session: 0 keeps 128 KiB parts, anything else takes a part size (Heimdall). */
  beginAnswer?: number;
  /** Its answer to the device type request. */
  deviceType?: number;
  /** Its answer to DVIF, or undefined for a device that doesn't know it (no answer). */
  dvif?: string;
  /** Sends a zero-length packet after the PIT's last part. */
  emptyAfterPit?: boolean;
  /** Answers the reboot request (Heimdall waits for it; some devices restart first). */
  answersReboot?: boolean;
  /** Already past the handshake: an earlier session ended without a reboot. */
  resumed?: boolean;
}

/** A synthetic PIT entry; fields left out are 0 or empty. */
export type FakeEntry = Partial<PitEntry> & { partitionName: string };

/** Packs PIT entries as libpit's PitData::Pack does: the 28-byte header, then 132 bytes an entry. */
export function buildPit(entries: FakeEntry[], header: { comTar2?: string; cpuBlId?: string; luCount?: number } = {}): Uint8Array {
  const data = new Uint8Array(PIT_HEADER_SIZE + entries.length * PIT_ENTRY_SIZE);
  const view = new DataView(data.buffer);
  const put = (at: number, s: string, max: number) => data.set(new TextEncoder().encode(s).subarray(0, max), at);
  view.setUint32(0, PIT_MAGIC, true);
  view.setUint32(4, entries.length, true);
  put(8, header.comTar2 ?? '', 8);
  put(16, header.cpuBlId ?? '', 8);
  view.setUint16(24, header.luCount ?? 0, true);
  entries.forEach((e, i) => {
    const at = PIT_HEADER_SIZE + i * PIT_ENTRY_SIZE;
    const words = [e.binaryType, e.deviceType, e.identifier, e.attributes, e.updateAttributes, e.blockSizeOrOffset,
      e.blockCount, e.fileOffset, e.fileSize];
    words.forEach((w, n) => view.setUint32(at + n * 4, w ?? 0, true));
    put(at + 36, e.partitionName, 31);
    put(at + 68, e.flashFilename ?? '', 31);
    put(at + 100, e.fotaFilename ?? '', 31);
  });
  return data;
}

/**
 * A made-up PIT, shaped like an eMMC tablet's: a bootloader, the PIT itself, BOOT, RECOVERY, VBMETA, SYSTEM,
 * USERDATA, a modem partition, and an unnamed entry. No real device's values.
 */
export function syntheticPit(options: { recoveryBlocks?: number; deviceType?: number } = {}): { pit: Uint8Array; entries: FakeEntry[] } {
  const mmc = options.deviceType ?? 2;
  const entries: FakeEntry[] = [
    { partitionName: 'BOOTLOADER', identifier: 80, deviceType: mmc, blockCount: 8192, flashFilename: 'sboot.bin' },
    { partitionName: 'PIT', identifier: 70, deviceType: mmc, blockSizeOrOffset: 8192, blockCount: 16, flashFilename: 'test.pit' },
    { partitionName: 'BOOT', identifier: 5, deviceType: mmc, blockSizeOrOffset: 16384, blockCount: 4096, flashFilename: 'boot.img' },
    { partitionName: 'RECOVERY', identifier: 6, deviceType: mmc, blockSizeOrOffset: 20480, blockCount: options.recoveryBlocks ?? 4096, flashFilename: 'recovery.img', attributes: 1 },
    { partitionName: 'VBMETA', identifier: 7, deviceType: mmc, blockSizeOrOffset: 24576, blockCount: 128, flashFilename: 'vbmeta.img' },
    { partitionName: 'SYSTEM', identifier: 20, deviceType: mmc, blockSizeOrOffset: 32768, blockCount: 65536, flashFilename: 'system.img', fotaFilename: 'system.fota' },
    { partitionName: 'USERDATA', identifier: 21, deviceType: mmc, blockSizeOrOffset: 98304, blockCount: 0, flashFilename: 'userdata.img' },
    { partitionName: 'RADIO', identifier: 30, binaryType: 1, deviceType: mmc, blockCount: 2048, flashFilename: 'modem.bin' },
    { partitionName: '', identifier: 99, deviceType: mmc },
  ];
  return { pit: buildPit(entries, { comTar2: 'TESTTAR', cpuBlId: 'FAKECPU' }), entries };
}

export class FakeDownloadMode implements OdinTransport {
  readonly wire: Wire[] = [];
  /** Each partition's contents, by PIT identifier. */
  readonly partitions = new Map<number, Uint8Array>();
  /** The protocol version the host's begin session named. */
  protocolVersion: number | undefined;
  /** The total the host announced, if any. */
  totalBytes: number | undefined;
  /** Bytes written by end packets, against the announced total. */
  written = 0;
  rebooted = false;
  closed = false;
  /** A test sets this to answer a control packet its own way: the reply, null for none, undefined for the usual one. */
  override: ((type: number, request: number, args: number[]) => Uint8Array | null | undefined) | undefined;

  private replies: Uint8Array[] = [];
  private handshaken = false;
  private partSize = 131072;
  /** Set between a file "part" packet and its sequence's end packet. */
  private sequence: { expected: number; parts: Uint8Array[] } | null = null;
  private fileOpen = false;
  private fileOffset = 0;
  private fileTarget: number | undefined;
  private emptyNext = false;

  constructor(readonly options: FakeOptions) {
    for (const [id, size] of options.partitions) this.partitions.set(id, new Uint8Array(size).fill(0xee));
    this.handshaken = options.resumed ?? false;
  }

  async send(data: Uint8Array): Promise<void> {
    if (data.length === 0) { this.wire.push({ dir: 'out', what: 'empty' }); return; }
    if (!this.handshaken && data.length === 4) {
      const text = new TextDecoder().decode(data);
      this.wire.push({ dir: 'out', what: 'text', text });
      if (text === 'ODIN') { this.handshaken = true; this.reply(new TextEncoder().encode('LOKE')); }
      else if (text === 'DVIF' && this.options.dvif !== undefined) this.reply(new TextEncoder().encode(this.options.dvif));
      return;
    }
    if (this.sequence && this.sequence.parts.length < this.sequence.expected) {
      if (data.length !== this.partSize) throw new Error(`a file part must be ${this.partSize} bytes, got ${data.length}`);
      this.wire.push({ dir: 'out', what: 'part', bytes: data.length });
      this.sequence.parts.push(data.slice());
      this.reply(response(0x00, this.sequence.parts.length - 1));
      return;
    }
    if (data.length !== CONTROL_SIZE) throw new Error(`a control packet is ${CONTROL_SIZE} bytes, got ${data.length}`);
    if (!this.handshaken) throw new Error('a control packet before the handshake');
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const words = Array.from({ length: 8 }, (_, i) => view.getUint32(i * 4, true));
    const [type, request, ...rest] = words as [number, number, ...number[]];
    const args = trim(rest);
    this.wire.push({ dir: 'out', what: 'control', type, request, args });
    if (data.subarray(32).some((b) => b !== 0)) throw new Error('a control packet is zero after its first 8 words');
    const custom = this.override?.(type, request, args);
    const answer = custom !== undefined ? custom : this.answer(type, request, args);
    if (answer) this.reply(answer);
    if (this.emptyNext) { this.reply(new Uint8Array(0)); this.emptyNext = false; }
  }

  async receive(maxLength: number): Promise<Uint8Array> {
    const r = this.replies.shift();
    if (!r) {
      this.wire.push({ dir: 'in', what: 'timeout' });
      throw new OdinError('timeout', 'USB read timed out (fake device: nothing to send)');
    }
    if (r.length > maxLength) throw new Error(`the host asked for ${maxLength} bytes and the device sent ${r.length}`);
    const v = r.length === 8 ? new DataView(r.buffer, r.byteOffset, 8) : undefined;
    this.wire.push(v
      ? { dir: 'in', what: 'reply', bytes: 8, type: v.getUint32(0, true), value: v.getUint32(4, true) }
      : { dir: 'in', what: 'reply', bytes: r.length });
    return r;
  }

  async close(): Promise<void> {
    this.closed = true;
  }

  /** Control packets only, as `type.request(args)`, e.g. `0x64.0(4)`: the order a test checks. */
  controls(): string[] {
    return this.wire.flatMap((w) => w.what === 'control' ? [`0x${w.type.toString(16)}.${w.request}(${w.args.join(',')})`] : []);
  }

  private reply(data: Uint8Array) {
    this.replies.push(data);
  }

  /** The reply to a control packet, or null for none. */
  private answer(type: number, request: number, args: number[]): Uint8Array | null {
    const a = (i: number) => args[i] ?? 0;
    switch (type) {
      case 0x64:
        if (request === 0) { this.protocolVersion = a(0); return response(0x64, this.options.beginAnswer ?? 0); }
        if (request === 1) return response(0x64, this.options.deviceType ?? 0);
        if (request === 2) { this.totalBytes = a(0) + a(1) * 2 ** 32; return response(0x64, 0); }
        if (request === 5) { this.partSize = a(0); return response(0x64, 0); }
        break;
      case 0x65: {
        const pit = this.options.pit;
        if (request === 1) return response(0x65, pit.length);
        if (request === 2) {
          const part = pit.slice(a(0) * PIT_PART, (a(0) + 1) * PIT_PART);
          const last = (a(0) + 1) * PIT_PART >= pit.length;
          if (last && this.options.emptyAfterPit) this.emptyNext = true;
          return part;
        }
        if (request === 3) return response(0x65, 0);
        break;
      }
      case 0x66:
        if (request === 0) { this.fileOpen = true; this.fileOffset = 0; this.fileTarget = undefined; return response(0x66, 0); }
        if (request === 2) {
          if (!this.fileOpen) return response(FAIL, -1);
          if (a(0) % this.partSize !== 0) throw new Error(`a sequence of ${a(0)} bytes isn't whole parts of ${this.partSize}`);
          this.sequence = { expected: a(0) / this.partSize, parts: [] };
          return response(0x66, 0);
        }
        if (request === 3) return this.endSequence(args);
        break;
      case 0x67:
        if (request === 0) return response(0x67, 0);
        if (request === 1) {
          this.rebooted = true;
          return this.options.answersReboot === false ? null : response(0x67, 0);
        }
        break;
    }
    return response(FAIL, -2);
  }

  private endSequence(args: number[]): Uint8Array {
    const [destination = 0, effective = 0, unknown1 = 0, deviceType = 0, identifier = 0, endOfFile = 0] = args;
    const seq = this.sequence;
    if (!seq || seq.parts.length !== seq.expected) throw new Error('a sequence ended before all its parts came');
    if (destination !== 0 || unknown1 !== 0) throw new Error(`end packet: destination ${destination}, unknown ${unknown1}`);
    if (this.fileTarget !== undefined && this.fileTarget !== identifier) throw new Error('a file changed partitions mid-way');
    this.fileTarget = identifier;
    const target = this.partitions.get(identifier);
    const entry = this.entryOf(identifier);
    if (!target || !entry || entry.deviceType !== deviceType) return response(FAIL, -3);
    const bytes = concat(seq.parts).subarray(0, effective);
    if (this.fileOffset + bytes.length > target.length) return response(FAIL, -4);
    target.set(bytes, this.fileOffset);
    this.fileOffset += bytes.length;
    this.written += bytes.length;
    this.sequence = null;
    if (endOfFile) this.fileOpen = false;
    return response(0x66, 0);
  }

  private entryOf(identifier: number): { deviceType: number } | undefined {
    const pit = this.options.pit;
    const view = new DataView(pit.buffer, pit.byteOffset, pit.byteLength);
    for (let i = 0; i < view.getUint32(4, true); i++) {
      const at = PIT_HEADER_SIZE + i * PIT_ENTRY_SIZE;
      if (view.getUint32(at + 8, true) === identifier) return { deviceType: view.getUint32(at + 4, true) };
    }
    return undefined;
  }
}

/** An 8-byte response: type, value (little-endian; a negative value as its two's complement). */
export function response(type: number, value: number): Uint8Array {
  const data = new Uint8Array(8);
  const view = new DataView(data.buffer);
  view.setUint32(0, type >>> 0, true);
  view.setUint32(4, value >>> 0, true);
  return data;
}

function trim(words: number[]): number[] {
  let end = words.length;
  while (end > 0 && words[end - 1] === 0) end--;
  return words.slice(0, end);
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}
