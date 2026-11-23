// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Glassworks contributors
// Written clean-room from docs/research/kiln-cleanroom/fel-spec.md (#355).
//
// The SPL part of u-boot-sunxi-with-spl.bin: its eGON header (linux-sunxi wiki, EGON), where its
// bytes go so the thunk's buffer swap puts them at the SPL address, and the thunk's memory image.
import { A133 } from './a133.ts';
import { FEL_TO_SPL_THUNK } from './thunk.ts';

const EGON_MAGIC = 'eGON.BT0';
const EGON_HEADER = 32;
/** Stands in for the checksum field while the checksum is summed. */
const CHECKSUM_STAMP = 0x5f0a6c39;

/** Largest SPL that fits in SRAM below the parked buffers and the thunk (0x20000 on the A133). */
export const SPL_LIMIT = Math.min(
  A133.sramSize,
  A133.thunkAddr - A133.splAddr,
  ...A133.swapBuffers.map((s) => s.buf2 - A133.splAddr),
);

/** Checks the eGON header and returns the SPL's length, or throws the reason it can't be run. */
export function splLength(file: Uint8Array): number {
  const v = new DataView(file.buffer, file.byteOffset, file.byteLength);
  if (file.length < EGON_HEADER || String.fromCharCode(...file.subarray(4, 12)) !== EGON_MAGIC) {
    throw new Error('This file has no eGON header, so it is not an Allwinner SPL.');
  }
  const len = v.getUint32(16, true);
  if (len % 4 !== 0 || len < EGON_HEADER || len > file.length) {
    throw new Error(`The SPL's length field is wrong (${len} bytes in a ${file.length}-byte file).`);
  }
  let sum = CHECKSUM_STAMP;
  for (let o = 0; o < len; o += 4) if (o !== 12) sum = (sum + v.getUint32(o, true)) >>> 0;
  if (sum !== v.getUint32(12, true)) throw new Error("The SPL's checksum does not match: the file is damaged.");
  if (len > SPL_LIMIT) throw new Error(`The SPL is too large for SRAM (need ${len} bytes, have ${SPL_LIMIT}).`);
  return len;
}

/** One FEL write: `bytes` of the SPL, sent to `addr`. */
export interface SplWrite { addr: number; bytes: Uint8Array }

/**
 * The FEL writes that load `spl` so it sits at the SPL address once the thunk has swapped each
 * buf1/buf2 pair: the bytes that belong at buf1 go to buf2, the rest go where they belong.
 */
export function splWrites(spl: Uint8Array): SplWrite[] {
  const out: SplWrite[] = [];
  let pos = 0;
  const plain = (end: number) => {
    if (end > pos) out.push({ addr: A133.splAddr + pos, bytes: spl.subarray(pos, end) });
    pos = Math.max(pos, end);
  };
  for (const s of [...A133.swapBuffers].sort((a, b) => a.buf1 - b.buf1)) {
    const start = s.buf1 - A133.splAddr;
    if (start >= spl.length) break;
    plain(start);
    const end = Math.min(start + s.size, spl.length);
    out.push({ addr: s.buf2, bytes: spl.subarray(start, end) });
    pos = end;
  }
  plain(spl.length);
  return out;
}

/** What goes at the thunk address: the thunk, the SPL address, the swap table, a zero triple. */
export function thunkImage(): Uint8Array {
  const words = [
    ...FEL_TO_SPL_THUNK,
    A133.splAddr,
    ...A133.swapBuffers.flatMap((s) => [s.buf1, s.buf2, s.size]),
    0, 0, 0,
  ];
  if (words.length * 4 > A133.thunkSize) throw new Error('the SPL thunk does not fit at its address');
  const b = new Uint8Array(words.length * 4);
  const v = new DataView(b.buffer);
  words.forEach((w, i) => v.setUint32(i * 4, w >>> 0, true));
  return b;
}
