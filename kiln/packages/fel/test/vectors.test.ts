// Black-box vectors for kiln/packages/fel: what the panel's boot ROM sees on the wire when Kiln boots
// U-Boot over FEL, and what Kiln refuses. Any implementation of the public API must reproduce them.
// The spec they belong to: docs/research/kiln-cleanroom/fel-spec.md.
//
// test/vectors/fel.json was recorded from the implementation of 2026-11-15:
//   FEL_VECTORS_WRITE=1 npx vitest run test/vectors.test.ts   (from kiln/packages/fel)
// Don't rewrite it to make a new implementation pass: the file is the contract.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { A133, FelDevice, bootUboot } from '../src';
import { FakeBrom, makeSpl, type Effect, type FelOp, type Xfer } from './fakes';

const FILE = join(__dirname, 'vectors/fel.json');
const WRITE = !!process.env.FEL_VECTORS_WRITE;

const hex = (b: Uint8Array) => Buffer.from(b).toString('hex');
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex');
const xfer = (x: Xfer) => ({ dir: x.dir, len: x.data.length, ...(x.data.length <= 64 ? { hex: hex(x.data) } : { sha256: sha(x.data) }) });
const op = (o: FelOp) => ({ req: o.req, addr: `0x${o.addr.toString(16)}`, len: o.len, xfers: o.xfers.map(xfer) });

/** The inputs, by formula, so the vectors need no binary fixtures. */
const SPL_LEN = 0x9000;
const spl = () => makeSpl(SPL_LEN, 0x40000); // u-boot-sunxi-with-spl.bin: the eGON SPL, then padding
const uboot = () => new Uint8Array(300_000).map((_, i) => (i * 13) & 0xff);

interface Refusal { name: string; error: string; setup: (b: FakeBrom) => void; spl: () => Uint8Array }
const refusals: Refusal[] = [
  { name: 'not an A133', error: 'not an Aqara S1 Plus', setup: (b) => { b.socId = 0x1823; }, spl },
  { name: 'secure boot on', error: 'secure boot is enabled', setup: (b) => { b.regs.set(A133.sidSecureBootReg, 1); }, spl },
  { name: 'MMU on', error: 'MMU', setup: (b) => { b.sctlr |= 1; }, spl },
  { name: 'no eGON header', error: 'eGON', setup: () => {}, spl: () => { const s = spl(); s[4] = 0x58; return s; } },
  { name: 'bad checksum', error: 'checksum', setup: () => {}, spl: () => { const s = spl(); s[100]! ^= 1; return s; } },
  { name: 'length not a multiple of 4', error: 'length', setup: () => {}, spl: () => { const s = spl(); new DataView(s.buffer).setUint32(16, SPL_LEN + 2, true); return s; } },
  { name: 'length past the end of the file', error: 'length', setup: () => {}, spl: () => makeSpl(SPL_LEN).subarray(0, SPL_LEN - 4) },
  { name: 'SPL too large', error: 'too large', setup: () => {}, spl: () => makeSpl(0x20004) },
  { name: 'SPL reports failure', error: 'eGON.BAD', setup: (b) => { b.splFailure = 'eGON.BAD'; }, spl },
];

async function record() {
  const brom = new FakeBrom();
  const progress: number[] = [];
  await bootUboot(new FelDevice(brom), spl(), uboot(), (p) => progress.push(p));
  return { brom, progress };
}

describe('FEL vectors', () => {
  it.runIf(WRITE)('records test/vectors/fel.json', async () => {
    const { brom } = await record();
    const out = {
      note: 'Recorded 2026-11-15 from the implementation then in kiln/packages/fel. See docs/research/kiln-cleanroom/fel-spec.md.',
      inputs: { spl: `makeSpl(0x${SPL_LEN.toString(16)}, 0x40000) from test/fakes.ts`, uboot: '300000 bytes, byte i = (i * 13) & 0xff' },
      bootUboot: {
        ops: brom.ops.filter((o) => !o.routine).map(op),
        splRanSha256: sha(brom.splRan!),
        resetEntry: `0x${brom.resetEntry!.toString(16)}`,
      },
      refusals: refusals.map((r) => ({ name: r.name, errorContains: r.error })),
    };
    writeFileSync(FILE, `${JSON.stringify(out, null, 1)}\n`);
  });

  const want = WRITE ? null : JSON.parse(readFileSync(FILE, 'utf8'));

  it.skipIf(WRITE)('bootUboot: every non-routine FEL request and USB transfer, in order', async () => {
    const { brom, progress } = await record();
    expect(brom.ops.filter((o) => !o.routine).map(op)).toEqual(want.bootUboot.ops);
    expect(sha(brom.splRan!)).toBe(want.bootUboot.splRanSha256);
    expect(progress.at(-1)).toBe(1);
    expect(progress.every((p, i) => i === 0 || p >= progress[i - 1]!)).toBe(true);
    expect(brom.closed).toBe(true);
  });

  it.skipIf(WRITE)('bootUboot: what the routines did on the chip', async () => {
    const { brom } = await record();
    const main = brom.ops.filter((o) => !o.routine);
    const firstWrite = main.findIndex((o) => o.req === 'write');
    const thunkExec = main.findIndex((o) => o.req === 'exec' && o.addr === A133.thunkAddr);
    const is = <K extends Effect['kind']>(k: K) => (e: Effect): e is Extract<Effect, { kind: K }> => e.kind === k;
    // The secure-boot word is read after the version and before anything is written.
    const sid = brom.effects.filter(is('mmio-read')).filter((e) => e.addr === A133.sidSecureBootReg);
    expect(sid.length).toBeGreaterThan(0);
    expect(sid[0]!.after).toBeGreaterThanOrEqual(1);
    expect(sid[0]!.after).toBeLessThanOrEqual(firstWrite);
    // SCTLR is read before the thunk runs.
    const sctlr = brom.effects.filter(is('cp15-read')).filter((e) => e.reg === 'SCTLR');
    expect(sctlr.length).toBeGreaterThan(0);
    expect(sctlr[0]!.after).toBeLessThanOrEqual(thunkExec);
    // No register is read but the secure-boot word, and none is written but RVBAR, with the entry point.
    expect(brom.effects.filter(is('mmio-read')).every((e) => e.addr === A133.sidSecureBootReg)).toBe(true);
    expect(brom.effects.filter(is('mmio-write')).map((e) => [e.addr, e.value])).toEqual([[A133.rvbarReg, A133.ubootAddr]]);
    // One warm reset into AArch64 at U-Boot, as the very last thing.
    const resets = brom.effects.filter(is('reset'));
    expect(resets).toEqual([{ kind: 'reset', entry: A133.ubootAddr, aarch64: true, after: main.length }]);
    expect(brom.effects.at(-1)!.kind).toBe('reset');
    expect(`0x${brom.resetEntry!.toString(16)}`).toBe(want.bootUboot.resetEntry);
  });

  for (const r of refusals) {
    it.skipIf(WRITE)(`refuses: ${r.name}`, async () => {
      const brom = new FakeBrom();
      r.setup(brom);
      await expect(bootUboot(new FelDevice(brom), r.spl(), uboot())).rejects.toThrow(r.error);
      expect(want.refusals.find((x: { name: string }) => x.name === r.name)?.errorContains).toBe(r.error);
      if (r.name !== 'SPL reports failure') {
        expect(brom.splRan).toBeNull();
        expect(brom.ops.some((o) => !o.routine && o.req === 'write')).toBe(false);
      }
      expect(brom.resetEntry).toBeNull();
    });
  }
});
