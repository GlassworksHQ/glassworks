// The `dpmd` hook, off-device: a synthetic ELF64/AArch64 shared object shaped like stock 1.24.0's
// `libdpmframework.so` (one RX PT_LOAD holding .text/.eh_frame/.dynamic/.rela.dyn, one RW PT_LOAD
// holding .init_array, no DT_INIT, one R_AARCH64_RELATIVE per init_array slot), so the planner, the
// payload layout and the verifier are all exercised without a vendor binary anywhere near the repo.
//
// The real-library run is in `dpmd-hook.dev.test.ts`, which skips unless a stock .so and a .ko are
// named in the environment.
import { describe, expect, it } from 'vitest';
import {
  buildDpmdHook, changedRanges, ehFrameBoundaries, HOOK_BLOCK_NODES, parseElf64Aarch64,
  planDpmdHook, renderPayload, type Elf64Layout,
} from '../src/dpmd-hook';

const PAGE = 0x1000;

const TEXT_VADDR = 0x100;
const EH_FRAME_VADDR = 0x140;
const EH_FRAME_SIZE = 0x800;
const DYNAMIC_VADDR = 0x940;
const RELA_VADDR = 0x980;
const INIT_ARRAY_VADDR = 0x1000;
const RX_FILESZ = 0xa00;
const SHSTRTAB_OFF = 0x2000;
const SHOFF = 0x2400;

const u16 = (b: Uint8Array, at: number, v: number): void => {
  b[at] = v & 0xff;
  b[at + 1] = (v >>> 8) & 0xff;
};
const u32 = (b: Uint8Array, at: number, v: number): void => {
  for (let i = 0; i < 4; i += 1) b[at + i] = (v >>> (8 * i)) & 0xff;
};
const u64 = (b: Uint8Array, at: number, v: number): void => {
  for (let i = 0; i < 8; i += 1) b[at + i] = Math.floor(v / 2 ** (8 * i)) & 0xff;
};

/** `.eh_frame`: a CIE, one FDE, then the terminator, then zero padding to EH_FRAME_SIZE. */
function ehFrameBytes(): Uint8Array {
  const b = new Uint8Array(EH_FRAME_SIZE);
  u32(b, 0x00, 0x0c); // CIE: 12 bytes of body after the length
  u32(b, 0x04, 0x00); // CIE id
  u32(b, 0x10, 0x14); // FDE: 20 bytes of body
  u32(b, 0x14, 0x18); // CIE pointer, back 0x18 bytes from here
  u32(b, 0x28, 0x00); // terminator
  return b;
}

interface Fixture {
  readonly so: Uint8Array;
  readonly chainedVaddr: number;
}

/** Builds the synthetic library. `sections` lets a test drop or resize a piece. */
function fixture(options: { initArrayBytes?: number; ehFrameSize?: number; rela?: boolean; class?: number } = {}): Fixture {
  const initArrayBytes = options.initArrayBytes ?? 8;
  const ehFrameSize = options.ehFrameSize ?? EH_FRAME_SIZE;
  const so = new Uint8Array(SHOFF + 7 * 64);

  // ELF header
  so.set([0x7f, 0x45, 0x4c, 0x46, options.class ?? 2, 1, 1, 0], 0);
  u16(so, 0x10, 3); // ET_DYN
  u16(so, 0x12, 183); // EM_AARCH64
  u32(so, 0x14, 1);
  u64(so, 0x18, TEXT_VADDR); // e_entry
  u64(so, 0x20, 64); // e_phoff
  u64(so, 0x28, SHOFF);
  u16(so, 0x34, 64); // e_ehsize
  u16(so, 0x36, 56); // e_phentsize
  u16(so, 0x38, 2); // e_phnum
  u16(so, 0x3a, 64); // e_shentsize
  u16(so, 0x3c, 7); // e_shnum
  u16(so, 0x3e, 6); // e_shstrndx

  // PT_LOAD RX and PT_LOAD RW
  const phdr = (i: number, type: number, flags: number, off: number, vaddr: number, filesz: number): void => {
    const at = 64 + i * 56;
    u32(so, at, type);
    u32(so, at + 4, flags);
    u64(so, at + 8, off);
    u64(so, at + 16, vaddr);
    u64(so, at + 32, filesz);
    u64(so, at + 40, filesz);
    u64(so, at + 48, PAGE);
  };
  phdr(0, 1, 5, 0, 0, RX_FILESZ);
  phdr(1, 1, 6, PAGE, PAGE, 0x100);

  // .text: a `ret`, which is all the chained constructor has to be
  so[TEXT_VADDR + 3] = 0xc0; // ret
  so[TEXT_VADDR + 2] = 0x03;
  so[TEXT_VADDR + 1] = 0x5f;
  so[TEXT_VADDR + 0] = 0xd6;

  so.set(ehFrameBytes().subarray(0, ehFrameSize), EH_FRAME_VADDR);

  // .dynamic: DT_INIT_ARRAY, DT_INIT_ARRAYSZ, DT_NULL
  u64(so, DYNAMIC_VADDR, 25);
  u64(so, DYNAMIC_VADDR + 8, INIT_ARRAY_VADDR);
  u64(so, DYNAMIC_VADDR + 16, 27);
  u64(so, DYNAMIC_VADDR + 24, initArrayBytes);
  u64(so, DYNAMIC_VADDR + 32, 0);
  u64(so, DYNAMIC_VADDR + 40, 0);

  // .rela.dyn: one R_AARCH64_RELATIVE per slot, each pointing at .text
  if (options.rela !== false) {
    for (let i = 0; i * 8 < initArrayBytes; i += 1) {
      const at = RELA_VADDR + i * 24;
      u64(so, at, INIT_ARRAY_VADDR + i * 8);
      u64(so, at + 8, 1027);
      u64(so, at + 16, TEXT_VADDR);
    }
  }

  // Section name table
  const names = '\0.text\0.eh_frame\0.dynamic\0.rela.dyn\0.init_array\0.shstrtab\0';
  const nameAt: Record<string, number> = {};
  for (const n of ['.text', '.eh_frame', '.dynamic', '.rela.dyn', '.init_array', '.shstrtab']) {
    nameAt[n] = names.indexOf(`${n}\0`);
  }
  for (let i = 0; i < names.length; i += 1) so[SHSTRTAB_OFF + i] = names.charCodeAt(i);

  const shdr = (i: number, name: string, type: number, flags: number, addr: number, off: number, size: number, align: number): void => {
    const at = SHOFF + i * 64;
    u32(so, at, name ? nameAt[name]! : 0);
    u32(so, at + 4, type);
    u64(so, at + 8, flags);
    u64(so, at + 16, addr);
    u64(so, at + 24, off);
    u64(so, at + 32, size);
    u64(so, at + 48, align);
  };
  shdr(0, '', 0, 0, 0, 0, 0, 0);
  shdr(1, '.text', 1, 6, TEXT_VADDR, TEXT_VADDR, 0x40, 8);
  shdr(2, '.eh_frame', 1, 2, EH_FRAME_VADDR, EH_FRAME_VADDR, ehFrameSize, 8);
  shdr(3, '.dynamic', 6, 3, DYNAMIC_VADDR, DYNAMIC_VADDR, 0x30, 8);
  shdr(4, '.rela.dyn', 4, 2, RELA_VADDR, RELA_VADDR, 0x18, 8);
  shdr(5, '.init_array', 1, 3, INIT_ARRAY_VADDR, PAGE, initArrayBytes, 8);
  shdr(6, '.shstrtab', 3, 0, 0, SHSTRTAB_OFF, names.length, 1);

  return { so, chainedVaddr: TEXT_VADDR };
}

/** A plausible `.ko`: starts with the aarch64 module magic and has a recognisable tail. */
function moduleFixture(size = 256): Uint8Array {
  const ko = new Uint8Array(size);
  ko.set([0x7f, 0x45, 0x4c, 0x46], 0);
  ko.set(new TextEncoder().encode('module-layout'), 8);
  ko.set(new TextEncoder().encode('TAIL'), size - 4);
  return ko;
}

const describeLayout = (so: Uint8Array): Elf64Layout => parseElf64Aarch64(so);

describe('parseElf64Aarch64', () => {
  it('reads the synthetic stock shape', () => {
    const { so } = fixture();
    const layout = describeLayout(so);
    expect(layout.segments).toHaveLength(2);
    expect(layout.initArrayVaddr).toBe(INIT_ARRAY_VADDR);
    expect(layout.initArrayBytes).toBe(8);
    expect(layout.ehFrame.addr).toBe(EH_FRAME_VADDR);
    expect(layout.ehFrame.size).toBe(EH_FRAME_SIZE);
    expect(layout.relocations.map((r) => r.addend)).toEqual([TEXT_VADDR]);
  });

  it('refuses an ELF32 file, which is the /vendor/lib copy', () => {
    const { so } = fixture({ class: 1 });
    expect(() => describeLayout(so)).toThrow(/not ELF64/);
  });

  it('refuses a file that is not an ELF at all', () => {
    expect(() => describeLayout(new Uint8Array(0x100))).toThrow(/not an ELF/);
  });

  it('refuses a library with no DT_INIT_ARRAY to hook', () => {
    const { so } = fixture();
    const at = DYNAMIC_VADDR;
    u64(so, at, 0); // DT_NULL first: the table ends before DT_INIT_ARRAY
    expect(() => describeLayout(so)).toThrow(/no DT_INIT_ARRAY/);
  });
});

describe('ehFrameBoundaries', () => {
  it('walks CIE, FDE and the terminator', () => {
    const { so } = fixture();
    expect(ehFrameBoundaries(describeLayout(so))).toEqual([0x140, 0x150, 0x168]);
  });
});

describe('planDpmdHook', () => {
  it('puts the payload at the last boundary that leaves room, and chains to .init_array[0]', () => {
    const { so, chainedVaddr } = fixture();
    const ko = moduleFixture();
    const plan = planDpmdHook(so, ko);

    // payload = 8 bytes of terminator+padding, 35 instructions, two paths, then the module
    expect(plan.payloadVaddr).toBe(0x168);
    expect(plan.entryVaddr).toBe(0x168 + 8);
    expect(plan.chainedVaddr).toBe(chainedVaddr);
    expect(plan.payloadOffset).toBe(0x168); // RX segment maps vaddr == offset in this fixture
    expect(plan.payloadBytes % 8).toBe(0);
    expect(plan.payloadBytes).toBeGreaterThan(ko.length);
    expect(plan.moduleOffset).toBe(plan.payloadOffset + plan.payloadBytes - ko.length);
  });

  it('refuses a library whose .eh_frame is smaller than the payload', () => {
    const { so } = fixture({ ehFrameSize: 0x40 });
    expect(() => planDpmdHook(so, moduleFixture())).toThrow(/payload needs 448 bytes but .eh_frame is only 64/);
  });

  it('falls back to the section start rather than cutting an entry in half', () => {
    // 448 bytes exactly fill 448 bytes of .eh_frame: only the CIE at the start leaves room.
    const { so } = fixture({ ehFrameSize: 0x1c0 });
    const plan = planDpmdHook(so, moduleFixture());
    expect(plan.payloadVaddr).toBe(EH_FRAME_VADDR);
    expect(plan.payloadVaddr + plan.payloadBytes).toBe(EH_FRAME_VADDR + 0x1c0);
  });

  it('refuses .init_array without a matching relocation', () => {
    const { so } = fixture({ rela: false });
    expect(() => planDpmdHook(so, moduleFixture())).toThrow(/no relocation for .init_array\[0\]/);
  });

  it('refuses a slot that is not R_AARCH64_RELATIVE', () => {
    const { so } = fixture();
    u64(so, RELA_VADDR + 8, 257); // R_AARCH64_ABS64
    expect(() => planDpmdHook(so, moduleFixture())).toThrow(/not R_AARCH64_RELATIVE/);
  });

  it('refuses a library whose .init_array[0] already points into .eh_frame', () => {
    const { so } = fixture();
    const { bytes } = buildDpmdHook(so, moduleFixture());
    expect(() => planDpmdHook(bytes, moduleFixture())).toThrow(/already points into .eh_frame/);
  });

  it('refuses an empty or absurd module', () => {
    const { so } = fixture();
    expect(() => planDpmdHook(so, new Uint8Array(0))).toThrow(/module image is empty/);
    expect(() => planDpmdHook(so, new Uint8Array(0x100_0001))).toThrow(/implausibly large/);
  });
});

describe('renderPayload', () => {
  const { so } = fixture();
  const ko = moduleFixture();
  const plan = planDpmdHook(so, ko);
  const payload = renderPayload(plan, ko);
  const words = (): number[] => {
    const out: number[] = [];
    for (let i = 0; i < 35; i += 1) {
      const at = 8 + i * 4;
      out.push((payload[at]! | (payload[at + 1]! << 8) | (payload[at + 2]! << 16) | (payload[at + 3]! << 24)) >>> 0);
    }
    return out;
  };

  it('starts with a zero terminator so the unwind table in front of it still ends', () => {
    expect([...payload.subarray(0, 4)]).toEqual([0, 0, 0, 0]);
  });

  it('carries the module byte for byte', () => {
    expect([...payload.subarray(payload.length - ko.length)]).toEqual([...ko]);
  });

  it('carries both block paths, NUL-terminated', () => {
    const text = new TextDecoder().decode(payload.subarray(8 + 35 * 4, 8 + 35 * 4 + 44));
    expect(text).toBe(`${HOOK_BLOCK_NODES[0]}\0${HOOK_BLOCK_NODES[1]}\0`);
  });

  it('is 35 fixed-width instructions with three syscalls and one branch', () => {
    const w = words();
    expect(w[0]).toBe(0x10000010); // adr x16, .
    expect(w.filter((x) => x === 0xd4000001)).toHaveLength(3); // init_module, fchmodat, fchmodat
    expect((w.at(-1)! & 0xfffffc1f) >>> 0).toBe(0xd61f0000); // br x16
    expect(w).not.toContain(0);
  });

  it('issues fchmodat (53), never fchownat (54), and AT_FDCWD', () => {
    const w = words();
    // movz x8, #imm16 appears as 0xd2800000 | imm16 << 5 | 8
    const syscallNumbers = w
      .filter((x) => ((x & 0xff80001f) >>> 0) === 0xd2800008)
      .map((x) => (x >>> 5) & 0xffff);
    expect(syscallNumbers).toContain(105); // init_module
    expect(syscallNumbers).toContain(53); // fchmodat
    expect(syscallNumbers).not.toContain(54); // fchownat would chown the node
    // movn x0, #99 -> x0 = -100 (AT_FDCWD)
    expect(w.filter((x) => ((x & 0xff80001f) >>> 0) === 0x92800000 && ((x >>> 5) & 0xffff) === 99)).toHaveLength(2);
  });

  it('passes an empty NUL-terminated string for init_module uargs, not NULL', () => {
    const w = words();
    const uargsInsn = w[7]!;
    // ADR x2, <imm21>: op=0 (bit 31), bits[28:24]=0b10000, rd=2
    expect((uargsInsn & 0x9f00001f) >>> 0).toBe(0x10000002);
    const immlo = (uargsInsn >>> 29) & 0x3;
    const immhi = (uargsInsn >>> 5) & 0x7ffff;
    let imm21 = (immhi << 2) | immlo;
    if (imm21 & (1 << 20)) imm21 -= 1 << 21;
    const targetOffset = 8 + 7 * 4 + imm21;
    expect(targetOffset).toBe(0);
    expect(payload[targetOffset]).toBe(0);
  });
});

describe('buildDpmdHook', () => {
  it('changes exactly two ranges: the payload and the relocation addend', () => {
    const { so } = fixture();
    const ko = moduleFixture();
    const { bytes, plan } = buildDpmdHook(so, ko);

    expect(bytes.length).toBe(so.length);
    const ranges = changedRanges(so, bytes);
    const payloadRegion = { start: plan.payloadOffset, end: plan.payloadOffset + plan.payloadBytes };
    const addendRegion = { start: plan.relocEntryOffset + 16, end: plan.relocEntryOffset + 24 };
    const inside = (r: { start: number; end: number }, a: { start: number; end: number }): boolean =>
      r.start >= a.start && r.end <= a.end;
    for (const r of ranges) {
      expect(inside(r, payloadRegion) || inside(r, addendRegion)).toBe(true);
    }
    expect(ranges.some((r) => r.start < payloadRegion.end && r.end > payloadRegion.start)).toBe(true);
    expect(ranges.some((r) => r.start < addendRegion.end && r.end > addendRegion.start)).toBe(true);

    const after = describeLayout(bytes);
    expect(after.relocations[0]!.addend).toBe(plan.entryVaddr);
    expect(after.initArrayBytes).toBe(8);
  });

  it('never touches the caller\'s buffer', () => {
    const { so } = fixture();
    const before = new Uint8Array(so);
    buildDpmdHook(so, moduleFixture());
    expect([...so]).toEqual([...before]);
  });

  it('leaves the rest of .eh_frame, .text and .dynamic intact', () => {
    const { so } = fixture();
    const { bytes } = buildDpmdHook(so, moduleFixture());
    for (let at = TEXT_VADDR; at < EH_FRAME_VADDR; at += 1) expect(bytes[at]).toBe(so[at]);
    for (let at = DYNAMIC_VADDR; at < RELA_VADDR; at += 1) expect(bytes[at]).toBe(so[at]);
  });
});
