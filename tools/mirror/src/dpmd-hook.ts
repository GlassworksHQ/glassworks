// The `dpmd` hook: a byte-exact, in-place patch of stock 1.24.0's `libdpmframework.so` that makes
// the stock daemon load a user-supplied backup module at boot.
//
// Why this file exists: on stock `1.24.0` the `init.qcom.post_boot.sh` route is dead (that domain
// has no `sys_module`), but `/system/bin/dpmd` runs as uid 0 with `CAP_SYS_MODULE` and links
// `libdpmframework.so` directly. That library is an ordinary 0644 `vendor_file`, so it can be read
// and patched, and its constructor runs inside a root process before the daemon's main loop.
//
// What the patch does, and nothing else:
//   1. retargets ONE `R_AARCH64_RELATIVE` relocation (`.init_array[0]`) at our constructor;
//   2. writes that constructor, two chmod paths and the module image into the tail of `.eh_frame`,
//      which is already mapped read+execute and is only read by the unwinder;
//   3. keeps the file length identical, so the surgical sparse flash writes only the blocks that
//      hold those two regions and every other block is `DONT_CARE`.
// The constructor loads the module (`init_module`), makes the two block nodes world-readable, and
// then tail-jumps to the constructor it replaced, so the library's own initialisers still run.
//
// Nothing vendor-supplied or GPL is bundled here: the module is a path on the caller's disk
// (Hard Rule 4), and the stock library is read from the device at run time (Hard Rule 3).
//
// Layout facts this code relies on, read from stock 1.24.0's `libdpmframework.so` (223,232 bytes,
// ELF64/AArch64, identical size in the published image):
//   PT_LOAD R E  off 0x000000 vaddr 0x000000 filesz 0x033e08
//     .text        0x00fcf0 +0x18b38
//     .rodata      0x028830 +0x05800
//     .eh_frame    0x02ebe0 +0x05210   <- 21,008 bytes of RX space, the payload home
//   PT_LOAD RW   off 0x033f80 vaddr 0x043f80 filesz 0x0020fc
//     .init_array  0x043f80 +0x50      <- 10 RELATIVE relocations, all pointing into .text
//   no DT_INIT; DT_INIT_ARRAYSZ 80; BIND_NOW (full RELRO, so everything is resolved before
//   constructors run and our raw-syscall constructor cannot recurse into the loader)

/** Block nodes the constructor makes readable, in the same order the post-boot snippet used. */
export const HOOK_BLOCK_NODES = ['/dev/block/mmcblk0p22', '/dev/block/mmcblk0p19'] as const;

const SYS_INIT_MODULE = 105; // __NR_init_module, arm64 (asm-generic)
const SYS_FCHMODAT = 53; // __NR_fchmodat, arm64; 54 is fchownat, which would chown the node
const AT_FDCWD = -100;
const NODE_MODE = 0o444;

const SHT_RELA = 4;
const SHT_DYNAMIC = 6;
const PT_LOAD = 1;
const PF_X = 1;
const PF_R = 4;
const DT_NULL = 0;
const DT_INIT_ARRAY = 25;
const DT_INIT_ARRAYSZ = 27;
const R_AARCH64_RELATIVE = 1027;
const EM_AARCH64 = 183;
const ET_DYN = 3;

export interface Elf64Segment {
  readonly flags: number;
  readonly offset: number;
  readonly vaddr: number;
  readonly filesz: number;
  readonly memsz: number;
}

export interface Elf64Section {
  readonly name: string;
  readonly type: number;
  readonly addr: number;
  readonly offset: number;
  readonly size: number;
}

export interface Elf64Relocation {
  /** File offset of the 24-byte `Elf64_Rela` entry. */
  readonly entryOffset: number;
  readonly rOffset: number;
  readonly type: number;
  readonly addend: number;
}

export interface Elf64Layout {
  readonly bytes: Uint8Array;
  readonly segments: readonly Elf64Segment[];
  readonly sections: readonly Elf64Section[];
  readonly initArrayVaddr: number;
  readonly initArrayBytes: number;
  readonly ehFrame: Elf64Section;
  readonly relocations: readonly Elf64Relocation[];
}

/** Where the patch lands. Every field is derived from the file, never hard-coded. */
export interface DpmdHookPlan {
  /** Virtual address the loader will call: start of the constructor code. */
  readonly entryVaddr: number;
  /** The constructor's original target, which the payload tail-jumps to. */
  readonly chainedVaddr: number;
  /** File offset of the `R_AARCH64_RELATIVE` entry whose addend we retarget. */
  readonly relocEntryOffset: number;
  /** File range the payload occupies inside `.eh_frame`. */
  readonly payloadOffset: number;
  readonly payloadVaddr: number;
  readonly payloadBytes: number;
  readonly moduleOffset: number;
  readonly moduleBytes: number;
}

function fail(msg: string): never {
  throw new Error(`dpmd hook: ${msg}`);
}

const u16 = (b: Uint8Array, at: number): number => b[at]! | (b[at + 1]! << 8);
const u32 = (b: Uint8Array, at: number): number => (b[at]! | (b[at + 1]! << 8) | (b[at + 2]! << 16) | (b[at + 3]! << 24)) >>> 0;
const u64 = (b: Uint8Array, at: number): number => u32(b, at) + u32(b, at + 4) * 0x1_0000_0000;
const putU64 = (b: Uint8Array, at: number, v: number): void => {
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  dv.setBigUint64(at, BigInt(v), true);
};

/** Parses the parts of an ELF64/AArch64 shared object this patch needs. */
export function parseElf64Aarch64(bytes: Uint8Array): Elf64Layout {
  if (bytes.length < 0x40) fail('file is too small to be an ELF');
  if (bytes[0] !== 0x7f || bytes[1] !== 0x45 || bytes[2] !== 0x4c || bytes[3] !== 0x46) fail('not an ELF file');
  if (bytes[4] !== 2) fail(`not ELF64 (EI_CLASS=${bytes[4]}); use the /vendor/lib64 copy, not /vendor/lib`);
  if (bytes[5] !== 1) fail('not little-endian');
  if (u16(bytes, 0x12) !== EM_AARCH64) fail(`not AArch64 (e_machine=${u16(bytes, 0x12)})`);
  if (u16(bytes, 0x10) !== ET_DYN) fail(`not a shared object (e_type=${u16(bytes, 0x10)})`);

  const phoff = u64(bytes, 0x20);
  const shoff = u64(bytes, 0x28);
  const phentsize = u16(bytes, 0x36);
  const phnum = u16(bytes, 0x38);
  const shentsize = u16(bytes, 0x3a);
  const shnum = u16(bytes, 0x3c);
  const shstrndx = u16(bytes, 0x3e);
  if (phentsize < 56 || shoff === 0 || shentsize < 64 || shnum === 0) fail('missing program or section headers');

  const segments: Elf64Segment[] = [];
  for (let i = 0; i < phnum; i += 1) {
    const at = phoff + i * phentsize;
    if (at + 56 > bytes.length) fail('program header table runs past the file');
    if (u32(bytes, at) !== PT_LOAD) continue;
    segments.push({
      flags: u32(bytes, at + 4),
      offset: u64(bytes, at + 8),
      vaddr: u64(bytes, at + 16),
      filesz: u64(bytes, at + 32),
      memsz: u64(bytes, at + 40),
    });
  }
  if (segments.length < 2) fail('expected at least two PT_LOAD segments');

  const rawSections: { nameOff: number; type: number; addr: number; offset: number; size: number }[] = [];
  for (let i = 0; i < shnum; i += 1) {
    const at = shoff + i * shentsize;
    if (at + 64 > bytes.length) fail('section header table runs past the file');
    rawSections.push({
      nameOff: u32(bytes, at),
      type: u32(bytes, at + 4),
      addr: u64(bytes, at + 16),
      offset: u64(bytes, at + 24),
      size: u64(bytes, at + 32),
    });
  }
  const strtabAt = shoff + shstrndx * shentsize;
  const strtab = rawSections[shstrndx] ?? fail('missing section name table');
  const nameAt = (off: number): string => {
    if (strtabAt + 64 > bytes.length) fail('bad section name table index');
    let end = strtab.offset + off;
    const limit = Math.min(bytes.length, strtab.offset + strtab.size);
    while (end < limit && bytes[end] !== 0) end += 1;
    return new TextDecoder().decode(bytes.subarray(strtab.offset + off, end));
  };
  const sections: Elf64Section[] = rawSections.map((s) => ({
    name: nameAt(s.nameOff),
    type: s.type,
    addr: s.addr,
    offset: s.offset,
    size: s.size,
  }));

  const dynamic = sections.find((s) => s.type === SHT_DYNAMIC) ?? fail('no PT_DYNAMIC/SHT_DYNAMIC section');
  let initArrayVaddr = 0;
  let initArrayBytes = 0;
  for (let at = dynamic.offset; at + 16 <= dynamic.offset + dynamic.size; at += 16) {
    const tag = u64(bytes, at);
    const val = u64(bytes, at + 8);
    if (tag === DT_NULL) break;
    if (tag === DT_INIT_ARRAY) initArrayVaddr = val;
    if (tag === DT_INIT_ARRAYSZ) initArrayBytes = val;
  }
  if (initArrayVaddr === 0 || initArrayBytes === 0) fail('no DT_INIT_ARRAY/DT_INIT_ARRAYSZ; cannot hook a constructor');
  if (initArrayBytes < 8) fail(`DT_INIT_ARRAYSZ is ${initArrayBytes}, too small for one entry`);

  const ehFrame = sections.find((s) => s.name === '.eh_frame') ?? fail('no .eh_frame section to hold the payload');
  if (ehFrame.size === 0) fail('.eh_frame is empty');

  const relocations: Elf64Relocation[] = [];
  for (const s of sections) {
    if (s.type !== SHT_RELA) continue;
    for (let at = s.offset; at + 24 <= s.offset + s.size; at += 24) {
      relocations.push({
        entryOffset: at,
        rOffset: u64(bytes, at),
        type: u32(bytes, at + 8),
        addend: u64(bytes, at + 16),
      });
    }
  }

  return { bytes, segments, sections, initArrayVaddr, initArrayBytes, ehFrame, relocations };
}

/** The RX segment that fully contains `vaddr..vaddr+len`, if any. */
function rxSegmentHolding(layout: Elf64Layout, vaddr: number, len: number): Elf64Segment {
  const seg = layout.segments.find(
    (s) => (s.flags & PF_X) !== 0 && (s.flags & PF_R) !== 0 && vaddr >= s.vaddr && vaddr + len <= s.vaddr + s.filesz,
  );
  return seg ?? fail(`0x${vaddr.toString(16)}+${len} is not inside one read+execute PT_LOAD`);
}

/**
 * Works out where the payload and the constructor go, and refuses anything that does not look like
 * the stock library (so a wrong ABI, an already-patched file or a truncated pull stops here rather
 * than after a flash).
 */
export function planDpmdHook(so: Uint8Array, module: Uint8Array): DpmdHookPlan {
  if (module.length === 0) fail('module image is empty');
  if (module.length > 0x100_0000) fail(`module image is implausibly large (${module.length} bytes)`);
  const layout = parseElf64Aarch64(so);

  const slot = layout.relocations.find((r) => r.rOffset === layout.initArrayVaddr);
  if (!slot) fail(`no relocation for .init_array[0] at 0x${layout.initArrayVaddr.toString(16)}`);
  if (slot.type !== R_AARCH64_RELATIVE) fail(`.init_array[0] is not R_AARCH64_RELATIVE (type ${slot.type})`);
  if (slot.addend === 0) fail('.init_array[0] relocation has a zero addend');
  rxSegmentHolding(layout, slot.addend, 4); // the constructor we chain to must itself be code

  const payloadBytes = payloadSizeFor(module.length);
  if (payloadBytes > layout.ehFrame.size) {
    fail(`payload needs ${payloadBytes} bytes but .eh_frame is only ${layout.ehFrame.size}`);
  }
  // Start the payload on a CIE/FDE boundary so the unwind table in front of it stays whole.
  const boundaries = ehFrameBoundaries(layout);
  const payloadVaddr = [...boundaries].reverse().find((b) => b + payloadBytes <= layout.ehFrame.addr + layout.ehFrame.size);
  if (payloadVaddr === undefined || payloadVaddr < layout.ehFrame.addr) {
    fail(`no CIE/FDE boundary in .eh_frame leaves room for ${payloadBytes} bytes`);
  }
  rxSegmentHolding(layout, payloadVaddr, payloadBytes);

  const payloadOffset = offsetOf(layout, payloadVaddr, payloadBytes);
  if (payloadOffset < 0) fail('could not map the payload address back to a file offset');

  return {
    entryVaddr: payloadVaddr + PAYLOAD_CODE_START,
    chainedVaddr: slot.addend,
    relocEntryOffset: slot.entryOffset,
    payloadOffset,
    payloadVaddr,
    payloadBytes,
    moduleOffset: payloadOffset + moduleRelOffset(),
    moduleBytes: module.length,
  };
}

/** Builds the patched library. The result is always the same length as the input. */
export function buildDpmdHook(so: Uint8Array, module: Uint8Array): { bytes: Uint8Array; plan: DpmdHookPlan } {
  const plan = planDpmdHook(so, module);
  const out = new Uint8Array(so); // copy: never touch the caller's buffer

  out.set(renderPayload(plan, module), plan.payloadOffset);

  // Point `.init_array[0]` at the constructor. The slot's bytes are zero in the file and the linker
  // writes base+addend at load time, so the addend is the thing to change.
  putU64(out, plan.relocEntryOffset + 16, plan.entryVaddr);

  verifyDpmdHook(so, out, plan);
  return { bytes: out, plan };
}

/** Re-parses the patched file and checks the patch is exactly what we meant to write. */
export function verifyDpmdHook(before: Uint8Array, after: Uint8Array, plan: DpmdHookPlan): void {
  if (before.length !== after.length) fail(`length changed: ${before.length} -> ${after.length}`);
  const layout = parseElf64Aarch64(after);
  const slot = layout.relocations.find((r) => r.rOffset === layout.initArrayVaddr);
  if (!slot) fail('patched file lost its .init_array[0] relocation');
  if (slot.addend !== plan.entryVaddr) {
    fail(`.init_array[0] addend is 0x${slot.addend.toString(16)}, expected 0x${plan.entryVaddr.toString(16)}`);
  }
  if (layout.initArrayBytes !== parseElf64Aarch64(before).initArrayBytes) fail('DT_INIT_ARRAYSZ changed');
  rxSegmentHolding(layout, plan.entryVaddr, CODE_BYTES);
  rxSegmentHolding(layout, plan.payloadVaddr, plan.payloadBytes);

  const changed = changedRanges(before, after);
  const allowed = [
    { start: plan.payloadOffset, end: plan.payloadOffset + plan.payloadBytes, name: 'payload' },
    { start: plan.relocEntryOffset + 16, end: plan.relocEntryOffset + 24, name: 'init_array[0] addend' },
  ];
  for (const range of changed) {
    const inside = allowed.some((a) => range.start >= a.start && range.end <= a.end);
    if (!inside) fail(`unexpected change at 0x${range.start.toString(16)}..0x${range.end.toString(16)}`);
  }
  // Every region we meant to write must actually have changed; a run of equal bytes inside the
  // payload splits the diff, so the check is per region, not per run.
  for (const region of allowed) {
    const touched = changed.some((r) => r.start < region.end && r.end > region.start);
    if (!touched) fail(`nothing changed in the ${region.name} region`);
  }
}

/** Byte ranges that differ, as [start, end) file offsets. */
export function changedRanges(before: Uint8Array, after: Uint8Array): { start: number; end: number }[] {
  const out: { start: number; end: number }[] = [];
  let start = -1;
  for (let i = 0; i < Math.max(before.length, after.length); i += 1) {
    const differs = before[i] !== after[i];
    if (differs && start < 0) start = i;
    if (!differs && start >= 0) {
      out.push({ start, end: i });
      start = -1;
    }
  }
  if (start >= 0) out.push({ start, end: Math.max(before.length, after.length) });
  return out;
}

// --- payload layout -------------------------------------------------------------------------

/** 4 zero bytes end the unwind table where our payload begins, then code, paths, module. */
const PAYLOAD_CODE_START = 8;
/** 35 fixed-width instructions; see `hookCode` for the listing. */
const CODE_BYTES = 35 * 4;

const alignUp = (v: number, a: number): number => v % a === 0 ? v : v + (a - (v % a));

function paths(): { text: Uint8Array; first: number; second: number } {
  const enc = new TextEncoder();
  const parts = HOOK_BLOCK_NODES.map((p) => {
    const b = enc.encode(p);
    return { bytes: b, length: b.length + 1 };
  });
  const text = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    text.set(p.bytes, at);
    at += p.length;
  }
  return { text, first: 0, second: parts[0]!.length };
}

const PATHS = paths();

/**
 * Every entry boundary in `.eh_frame`, as virtual addresses, ending with the table's terminator.
 *
 * The payload overwrites the tail of `.eh_frame`, so it must start where an entry ends: cutting a
 * CIE or FDE in half would leave the unwinder reading a length field that runs into our code.
 */
export function ehFrameBoundaries(layout: Elf64Layout): number[] {
  const section = layout.ehFrame;
  const end = section.addr + section.size;
  const out: number[] = [];
  let at = section.addr;
  while (at + 4 <= end) {
    out.push(at);
    const lengthAt = offsetOf(layout, at, 4);
    if (lengthAt < 0) break;
    const length = u32(layout.bytes, lengthAt);
    if (length === 0) break; // terminator: the table is complete
    if (length === 0xffffffff) {
      if (at + 12 > end) break;
      const extendedAt = offsetOf(layout, at + 4, 8);
      if (extendedAt < 0) break;
      const extended = u64(layout.bytes, extendedAt);
      at += 12 + extended;
    } else {
      at += 4 + length;
    }
  }
  return out;
}

function payloadSizeFor(moduleBytes: number): number {
  const afterCode = PAYLOAD_CODE_START + CODE_BYTES + PATHS.text.length;
  return alignUp(afterCode, 8) + moduleBytes;
}

/** The module's offset inside the payload region. */
const moduleRelOffset = (): number => alignUp(PAYLOAD_CODE_START + CODE_BYTES + PATHS.text.length, 8);

function offsetOf(layout: Elf64Layout, vaddr: number, len: number): number {
  const seg = layout.segments.find((s) => vaddr >= s.vaddr && vaddr + len <= s.vaddr + s.filesz);
  if (!seg) return -1;
  return seg.offset + (vaddr - seg.vaddr);
}

/** Assembles the payload: terminator, constructor code, both paths, then the module image. */
export function renderPayload(plan: DpmdHookPlan, module: Uint8Array): Uint8Array {
  const size = payloadSizeFor(module.length);
  if (size !== plan.payloadBytes) fail(`payload size ${size} disagrees with the plan (${plan.payloadBytes})`);
  const out = new Uint8Array(size); // terminator and padding stay zero
  const code = hookCode(plan);
  if (code.length !== CODE_BYTES) fail(`constructor is ${code.length} bytes, expected ${CODE_BYTES}`);
  out.set(code, PAYLOAD_CODE_START);
  out.set(PATHS.text, PAYLOAD_CODE_START + CODE_BYTES);
  out.set(module, moduleRelOffset());
  return out;
}

/**
 * AArch64 constructor. Position-independent, no libc: it derives the load bias from its own PC,
 * calls `init_module`, chmods the two block nodes, then tail-jumps to the initialiser it replaced so
 * the library's own constructors still run.
 *
 *   adr  x16, code            ; base + entryVaddr
 *   mov  x17, entryVaddr
 *   sub  x16, x16, x17        ; x16 = load bias
 *   adr  x0, module
 *   mov  x1, moduleBytes
 *   adr  x2, 0                ; uargs = "" (payload starts with NUL bytes; NULL fails with -EFAULT)
 *   mov  x8, __NR_init_module
 *   svc  #0
 *   (twice) movn x0, #99      ; AT_FDCWD = -100
 *           adr  x1, path
 *           mov  x2, 0444
 *           mov  x3, xzr
 *           mov  x8, __NR_fchmodat
 *           svc  #0
 *   adr  x16, bias2           ; x16 = load_address + bias2
 *   mov  x17, bias2 + payloadVaddr
 *   sub  x16, x16, x17        ; x16 = load_address - payloadVaddr = bias
 *   mov  x17, chainedVaddr
 *   add  x16, x16, x17
 *   br   x16
 *
 * Every constant is a fixed two-instruction `movz`+`movk`, so the code is always `CODE_BYTES` long
 * and the payload layout can be computed before the code exists.
 */
function hookCode(plan: DpmdHookPlan): Uint8Array {
  const words: number[] = [];
  const here = (): number => PAYLOAD_CODE_START + words.length * 4;
  const emitAdr = (rd: number, target: number): void => {
    words.push(adrWord(rd, target - here()));
  };
  // Two words only: every constant in this payload is an address or a length below 4 GiB, and the
  // fixed width is what lets the payload layout be computed before the code exists.
  const emitConst = (rd: number, value: number): void => {
    if (!Number.isInteger(value) || value < 0 || value > 0xffff_ffff) {
      fail(`constant ${value} does not fit the constructor's 32-bit movz/movk pair`);
    }
    words.push(movz(rd, value & 0xffff));
    words.push(movk(rd, (value >>> 16) & 0xffff, 16));
  };

  const codeAt = PAYLOAD_CODE_START;
  const path1At = PAYLOAD_CODE_START + CODE_BYTES;
  const path2At = path1At + PATHS.second;
  const moduleAt = moduleRelOffset();

  emitAdr(16, codeAt);
  emitConst(17, plan.entryVaddr);
  words.push(sub(16, 16, 17));

  emitAdr(0, moduleAt);
  emitConst(1, plan.moduleBytes);
  emitAdr(2, 0); // uargs = "" (payload offset 0 is the zeroed terminator; NULL fails strndup_user with -EFAULT)
  emitConst(8, SYS_INIT_MODULE);
  words.push(SVC);

  for (const path of [path1At, path2At]) {
    words.push(movn(0, 99)); // AT_FDCWD = -100
    emitAdr(1, path);
    emitConst(2, NODE_MODE);
    words.push(movFromXzr(3, 31));
    emitConst(8, SYS_FCHMODAT);
    words.push(SVC);
  }

  const bias2At = here();
  emitAdr(16, bias2At);
  // `adr` gave us load_address + bias2At, so subtract bias2At + payloadVaddr to recover the bias.
  emitConst(17, bias2At + plan.payloadVaddr);
  words.push(sub(16, 16, 17));
  emitConst(17, plan.chainedVaddr);
  words.push(add(16, 16, 17));
  words.push(br(16));

  if (words.length * 4 !== CODE_BYTES) {
    fail(`constructor assembled to ${words.length * 4} bytes, expected ${CODE_BYTES}`);
  }
  const bytes = new Uint8Array(CODE_BYTES);
  const dv = new DataView(bytes.buffer);
  words.forEach((w, i) => dv.setUint32(i * 4, w >>> 0, true));
  return bytes;
}

// --- AArch64 instruction encoders ------------------------------------------------------------

const adrWord = (rd: number, imm: number): number => {
  if (imm < -(1 << 20) || imm >= 1 << 20) fail(`adr out of range: ${imm}`);
  return (0x10000000 | ((imm & 3) << 29) | (((imm >> 2) & 0x7ffff) << 5) | rd) >>> 0;
};
const movz = (rd: number, imm16: number, shift = 0): number =>
  (0xd2800000 | ((shift / 16) << 21) | ((imm16 & 0xffff) << 5) | rd) >>> 0;
const movk = (rd: number, imm16: number, shift = 0): number =>
  (0xf2800000 | ((shift / 16) << 21) | ((imm16 & 0xffff) << 5) | rd) >>> 0;
const movn = (rd: number, imm16: number, shift = 0): number =>
  (0x92800000 | ((shift / 16) << 21) | ((imm16 & 0xffff) << 5) | rd) >>> 0;
const movFromXzr = (rd: number, rn: number): number => (0xaa0003e0 | (rn << 16) | rd) >>> 0;
const add = (rd: number, rn: number, rm: number): number => (0x8b000000 | (rm << 16) | (rn << 5) | rd) >>> 0;
const sub = (rd: number, rn: number, rm: number): number => (0xcb000000 | (rm << 16) | (rn << 5) | rd) >>> 0;
const br = (rn: number): number => (0xd61f0000 | (rn << 5)) >>> 0;
const SVC = 0xd4000001;
