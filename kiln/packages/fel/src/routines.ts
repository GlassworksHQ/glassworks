// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Glassworks contributors
// Written clean-room from docs/research/kiln-cleanroom/fel-spec.md (#355).
//
// The A32 routines Kiln runs on the chip, assembled from src/routines.S (ARM ARM DDI 0406C, A5/A8).
// scripts/check-routines.sh assembles that file and checks these words against it.

/** A routine image for the scratch area. */
export interface Routine { image: Uint8Array }
/** A routine that leaves a 32-bit result at byte offset `resultAt` of its image. */
export interface ReadRoutine extends Routine { resultAt: number }

function image(words: readonly number[]): Uint8Array {
  const b = new Uint8Array(words.length * 4);
  const v = new DataView(b.buffer);
  words.forEach((w, i) => v.setUint32(i * 4, w >>> 0, true));
  return b;
}

export function readWord(addr: number): ReadRoutine {
  return {
    image: image([
      0xe28f100c, // add  r1, pc, #12
      0xe5910000, // ldr  r0, [r1]
      0xe5900000, // ldr  r0, [r0]
      0xe5810004, // str  r0, [r1, #4]
      0xe12fff1e, // bx   lr
      addr, //       .word addr
      0, //          .word result
    ]),
    resultAt: 24,
  };
}

export function readSctlr(): ReadRoutine {
  return {
    image: image([
      0xee110f10, // mrc  p15, 0, r0, c1, c0, 0
      0xe28f1004, // add  r1, pc, #4
      0xe5810000, // str  r0, [r1]
      0xe12fff1e, // bx   lr
      0, //          .word result
    ]),
    resultAt: 16,
  };
}

export function warmReset64(rvbar: number, entry: number): Routine {
  return {
    image: image([
      0xe28f1028, // add  r1, pc, #40
      0xe5910000, // ldr  r0, [r1]
      0xe5912004, // ldr  r2, [r1, #4]
      0xe5802000, // str  r2, [r0]
      0xf57ff04f, // dsb  sy
      0xf57ff06f, // isb  sy
      0xee1c0f50, // mrc  p15, 0, r0, c12, c0, 2
      0xe3800003, // orr  r0, r0, #3
      0xee0c0f50, // mcr  p15, 0, r0, c12, c0, 2
      0xf57ff06f, // isb  sy
      0xe320f003, // 1: wfi
      0xeafffffd, // b    1b
      rvbar, //      .word rvbar
      entry, //      .word entry
    ]),
  };
}
