// A small A32 (ARM state) interpreter for the fake boot ROM, written from the ARM Architecture
// Reference Manual (ARMv7-A/R, DDI 0406). The fake runs whatever routine the host uploads to the
// scratch area, so the tests check what the routine does, not its bytes.
//
// Covered: data processing (immediate and register operands, shifts by immediate or register),
// MOVW/MOVT, MUL/MLA, LDR/STR/LDRB/STRB (immediate and register offsets, pre/post-index, writeback),
// LDRH/STRH (immediate), LDM/STM, B/BL/BX/BLX, MRS/MSR CPSR (with banked SP/LR per mode), MRC/MCR
// through a hook, barriers and hints (DSB, DMB, ISB, NOP, WFI). Thumb is not covered: the boot ROM
// enters FEL code in ARM state. Anything else throws, naming the word, so the next routine that
// needs more can add it here.

export interface ArmBus {
  read32(addr: number): number;
  write32(addr: number, value: number): void;
  read8(addr: number): number;
  write8(addr: number, value: number): void;
  /** MRC: returns the value. Throws for registers the fake does not model. */
  mrc(cp: number, opc1: number, crn: number, crm: number, opc2: number): number;
  mcr(cp: number, opc1: number, crn: number, crm: number, opc2: number, value: number): void;
  /** WFI: return true to stop (e.g. a warm reset is pending), false to keep going. */
  wfi(): boolean;
}

export interface ArmState {
  /** Stack pointers per mode on entry (0x13 SVC, 0x12 IRQ, ...). */
  sp: Record<number, number>;
  mode: number;
}

export type ArmExit = 'return' | 'wfi';

const RETURN = 0xfffffff0;
const MAX_STEPS = 1_000_000;
const u = (x: number) => x >>> 0;

export function runArm(bus: ArmBus, entry: number, state: ArmState): ArmExit {
  const r = new Uint32Array(16);
  const banked = new Map<number, { sp: number; lr: number }>();
  for (const [m, sp] of Object.entries(state.sp)) banked.set(Number(m), { sp, lr: 0 });
  let cpsr = 0x1c0 | state.mode;
  const bank = (m: number) => (m === 0x10 || m === 0x1f ? 0x1f : m);
  const enter = (m: number) => {
    const b = banked.get(bank(m)) ?? { sp: 0, lr: 0 };
    r[13] = b.sp; r[14] = b.lr;
  };
  const leave = (m: number) => banked.set(bank(m), { sp: r[13]!, lr: r[14]! });
  enter(state.mode);
  r[14] = RETURN;
  let pc = entry;

  const N = () => (cpsr >>> 31) & 1, Z = () => (cpsr >>> 30) & 1, C = () => (cpsr >>> 29) & 1, V = () => (cpsr >>> 28) & 1;
  const setNZCV = (n: number, z: number, c: number, v: number) => {
    cpsr = u((cpsr & 0x0fffffff) | (n << 31) | (z << 30) | (c << 29) | (v << 28));
  };
  const cond = (c: number): boolean => {
    switch (c) {
      case 0x0: return Z() === 1;
      case 0x1: return Z() === 0;
      case 0x2: return C() === 1;
      case 0x3: return C() === 0;
      case 0x4: return N() === 1;
      case 0x5: return N() === 0;
      case 0x6: return V() === 1;
      case 0x7: return V() === 0;
      case 0x8: return C() === 1 && Z() === 0;
      case 0x9: return C() === 0 || Z() === 1;
      case 0xa: return N() === V();
      case 0xb: return N() !== V();
      case 0xc: return Z() === 0 && N() === V();
      case 0xd: return Z() === 1 || N() !== V();
      default: return true;
    }
  };
  const reg = (n: number) => (n === 15 ? u(pc + 8) : r[n]!);
  const writeCpsr = (value: number, mask: number) => {
    let next = cpsr;
    if (mask & 8) next = (next & 0x00ffffff) | (value & 0xff000000);
    if (mask & 1) next = (next & ~0xff) | (value & 0xff);
    next = u(next);
    const from = cpsr & 0x1f, to = next & 0x1f;
    if (bank(from) !== bank(to)) { leave(from); cpsr = next; enter(to); } else cpsr = next;
  };
  const shift = (v: number, type: number, amount: number, byReg: boolean): [number, number] => {
    const c = C();
    if (byReg && amount === 0) return [v, c];
    switch (type) {
      case 0: // LSL
        if (amount === 0) return [v, c];
        if (amount < 32) return [u(v << amount), (v >>> (32 - amount)) & 1];
        return [0, amount === 32 ? v & 1 : 0];
      case 1: // LSR (immediate 0 means 32)
        if (amount === 0 && !byReg) amount = 32;
        if (amount < 32) return [v >>> amount, (v >>> (amount - 1)) & 1];
        return [0, amount === 32 ? v >>> 31 : 0];
      case 2: // ASR (immediate 0 means 32)
        if (amount === 0 && !byReg) amount = 32;
        if (amount < 32) return [u(v >> amount), (v >>> (amount - 1)) & 1];
        return [v >>> 31 ? 0xffffffff : 0, v >>> 31];
      default: { // ROR, or RRX for immediate 0
        if (amount === 0 && !byReg) return [u((c << 31) | (v >>> 1)), v & 1];
        const a = amount & 31;
        if (a === 0) return [v, v >>> 31];
        const res = u((v >>> a) | (v << (32 - a)));
        return [res, res >>> 31];
      }
    }
  };
  const fail = (ins: number, why = 'unsupported instruction'): never => {
    throw new Error(`arm.ts: ${why} 0x${u(ins).toString(16).padStart(8, '0')} at 0x${u(pc).toString(16)} (extend kiln/packages/fel/test/arm.ts)`);
  };

  for (let steps = 0; steps < MAX_STEPS; steps++) {
    if (pc === RETURN) return 'return';
    if (pc & 3) fail(pc, 'unaligned or Thumb PC');
    const ins = bus.read32(pc);
    const c = ins >>> 28;
    let next = u(pc + 4);

    if (c === 0xf) {
      const top = u(ins & 0xfffffff0);
      if (top === 0xf57ff040 || top === 0xf57ff050 || top === 0xf57ff060) {
        pc = next; continue; // DSB, DMB, ISB
      }
      fail(ins);
    }
    if (!cond(c)) { pc = next; continue; }

    if ((ins & 0x0fffff00) === 0x0320f000) { // hints
      const op = ins & 0xff;
      if (op === 3) { if (bus.wfi()) return 'wfi'; } else if (op > 4) fail(ins);
      pc = next; continue;
    }
    if ((ins & 0x0ffffff0) === 0x012fff10 || (ins & 0x0ffffff0) === 0x012fff30) { // BX, BLX (register)
      const target = reg(ins & 0xf);
      if (ins & 0x20) r[14] = next;
      if (target === RETURN) return 'return';
      if (target & 1) fail(ins, 'switch to Thumb');
      pc = target; continue;
    }
    if ((ins & 0x0fbf0fff) === 0x010f0000) { // MRS
      if (ins & (1 << 22)) fail(ins, 'SPSR');
      r[(ins >>> 12) & 0xf] = cpsr; pc = next; continue;
    }
    if ((ins & 0x0fb0fff0) === 0x0120f000) { // MSR (register)
      if (ins & (1 << 22)) fail(ins, 'SPSR');
      writeCpsr(reg(ins & 0xf), (ins >>> 16) & 0xf); pc = next; continue;
    }
    if ((ins & 0x0fb0f000) === 0x0320f000) { // MSR (immediate)
      if (ins & (1 << 22)) fail(ins, 'SPSR');
      const rot = ((ins >>> 8) & 0xf) * 2, imm = ins & 0xff;
      writeCpsr(rot ? u((imm >>> rot) | (imm << (32 - rot))) : imm, (ins >>> 16) & 0xf); pc = next; continue;
    }
    if ((ins & 0x0ff00000) === 0x03000000 || (ins & 0x0ff00000) === 0x03400000) { // MOVW, MOVT
      const rd = (ins >>> 12) & 0xf, imm = ((ins >>> 4) & 0xf000) | (ins & 0xfff);
      r[rd] = ins & 0x00400000 ? u((imm << 16) | (r[rd]! & 0xffff)) : imm;
      pc = next; continue;
    }
    if ((ins & 0x0fc000f0) === 0x00000090) { // MUL, MLA
      const rd = (ins >>> 16) & 0xf, rn = (ins >>> 12) & 0xf, rs = (ins >>> 8) & 0xf, rm = ins & 0xf;
      let v = Math.imul(r[rm]!, r[rs]!);
      if (ins & (1 << 21)) v += r[rn]!;
      r[rd] = u(v);
      if (ins & (1 << 20)) setNZCV(r[rd]! >>> 31, r[rd] === 0 ? 1 : 0, C(), V());
      pc = next; continue;
    }
    if ((ins & 0x0e4000f0) === 0x004000b0) { // LDRH/STRH (immediate)
      const P = (ins >>> 24) & 1, U = (ins >>> 23) & 1, W = (ins >>> 21) & 1, L = (ins >>> 20) & 1;
      const rn = (ins >>> 16) & 0xf, rt = (ins >>> 12) & 0xf, off = ((ins >>> 4) & 0xf0) | (ins & 0xf);
      const base = reg(rn), moved = u(U ? base + off : base - off), addr = P ? moved : base;
      if (L) r[rt] = bus.read8(addr) | (bus.read8(addr + 1) << 8);
      else { bus.write8(addr, r[rt]! & 0xff); bus.write8(addr + 1, (r[rt]! >>> 8) & 0xff); }
      if (!P || W) r[rn] = moved;
      pc = next; continue;
    }
    if ((ins & 0x0f000010) === 0x0e000010) { // MRC, MCR
      const cp = (ins >>> 8) & 0xf, opc1 = (ins >>> 21) & 7, crn = (ins >>> 16) & 0xf, crm = ins & 0xf, opc2 = (ins >>> 5) & 7;
      const rt = (ins >>> 12) & 0xf;
      if (ins & (1 << 20)) {
        const v = bus.mrc(cp, opc1, crn, crm, opc2);
        if (rt === 15) setNZCV(v >>> 31, (v >>> 30) & 1, (v >>> 29) & 1, (v >>> 28) & 1); else r[rt] = u(v);
      } else bus.mcr(cp, opc1, crn, crm, opc2, reg(rt));
      pc = next; continue;
    }
    if ((ins & 0x0c000000) === 0x00000000) { // data processing
      if (!(ins & (1 << 25)) && (ins & 0x90) === 0x90) fail(ins);
      const op = (ins >>> 21) & 0xf, S = (ins >>> 20) & 1, rn = (ins >>> 16) & 0xf, rd = (ins >>> 12) & 0xf;
      if (op >= 8 && op <= 11 && !S) fail(ins);
      let b: number, carry: number;
      if (ins & (1 << 25)) {
        const rot = ((ins >>> 8) & 0xf) * 2, imm = ins & 0xff;
        b = rot ? u((imm >>> rot) | (imm << (32 - rot))) : imm;
        carry = rot ? b >>> 31 : C();
      } else {
        const rm = ins & 0xf, type = (ins >>> 5) & 3;
        if (ins & 0x10) {
          const rs = (ins >>> 8) & 0xf;
          const rmv = rm === 15 ? u(pc + 12) : r[rm]!;
          [b, carry] = shift(rmv, type, r[rs]! & 0xff, true);
        } else [b, carry] = shift(reg(rm), type, (ins >>> 7) & 0x1f, false);
      }
      const a = reg(rn);
      let res = 0, cOut = carry, vOut = V();
      const add = (x: number, y: number, cin: number) => {
        const sum = x + y + cin;
        res = u(sum); cOut = sum > 0xffffffff ? 1 : 0;
        vOut = ((~(x ^ y) & (x ^ res)) >>> 31) & 1;
      };
      switch (op) {
        case 0: case 8: res = u(a & b); break;
        case 1: case 9: res = u(a ^ b); break;
        case 2: case 10: add(a, u(~b), 1); break;
        case 3: add(b, u(~a), 1); break;
        case 4: case 11: add(a, b, 0); break;
        case 5: add(a, b, C()); break;
        case 6: add(a, u(~b), C()); break;
        case 7: add(b, u(~a), C()); break;
        case 12: res = u(a | b); break;
        case 13: res = b; break;
        case 14: res = u(a & ~b); break;
        default: res = u(~b); break;
      }
      if (S) {
        if (rd === 15 && !(op >= 8 && op <= 11)) fail(ins, 'flag-setting write to PC');
        setNZCV(res >>> 31, res === 0 ? 1 : 0, cOut, vOut);
      }
      if (op < 8 || op > 11) {
        if (rd === 15) { pc = res; if (pc === RETURN) return 'return'; continue; }
        r[rd] = res;
      }
      pc = next; continue;
    }
    if ((ins & 0x0c000000) === 0x04000000) { // LDR/STR (word, byte)
      const I = (ins >>> 25) & 1, P = (ins >>> 24) & 1, U = (ins >>> 23) & 1, B = (ins >>> 22) & 1;
      const W = (ins >>> 21) & 1, L = (ins >>> 20) & 1, rn = (ins >>> 16) & 0xf, rt = (ins >>> 12) & 0xf;
      if (I && (ins & 0x10)) fail(ins);
      const off = I ? shift(reg(ins & 0xf), (ins >>> 5) & 3, (ins >>> 7) & 0x1f, false)[0] : ins & 0xfff;
      const base = reg(rn), moved = u(U ? base + off : base - off), addr = P ? moved : base;
      if (L) {
        const v = B ? bus.read8(addr) : bus.read32(addr);
        if (!P || W) r[rn] = moved;
        if (rt === 15) { pc = v; if (pc === RETURN) return 'return'; continue; }
        r[rt] = v;
      } else {
        const v = rt === 15 ? u(pc + 8) : r[rt]!;
        if (B) bus.write8(addr, v & 0xff); else bus.write32(addr, v);
        if (!P || W) r[rn] = moved;
      }
      pc = next; continue;
    }
    if ((ins & 0x0e000000) === 0x08000000) { // LDM/STM
      if (ins & (1 << 22)) fail(ins, 'LDM/STM with user registers');
      const P = (ins >>> 24) & 1, U = (ins >>> 23) & 1, W = (ins >>> 21) & 1, L = (ins >>> 20) & 1, rn = (ins >>> 16) & 0xf;
      const list = ins & 0xffff, n = list.toString(2).replace(/0/g, '').length, base = r[rn]!;
      let addr = U ? (P ? base + 4 : base) : (P ? base - 4 * n : base - 4 * n + 4);
      let jump: number | null = null;
      for (let i = 0; i < 16; i++) {
        if (!(list & (1 << i))) continue;
        if (L) { const v = bus.read32(u(addr)); if (i === 15) jump = v; else r[i] = v; }
        else bus.write32(u(addr), i === 15 ? u(pc + 8) : r[i]!);
        addr += 4;
      }
      if (W) r[rn] = u(U ? base + 4 * n : base - 4 * n);
      if (jump !== null) { pc = jump; if (pc === RETURN) return 'return'; continue; }
      pc = next; continue;
    }
    if ((ins & 0x0e000000) === 0x0a000000) { // B, BL
      const off = ((ins & 0xffffff) << 8) >> 6;
      if (ins & (1 << 24)) r[14] = next;
      pc = u(pc + 8 + off); continue;
    }
    fail(ins);
  }
  throw new Error(`arm.ts: no return after ${MAX_STEPS} instructions (routine at 0x${u(entry).toString(16)})`);
}
