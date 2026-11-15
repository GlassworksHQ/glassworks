// The public API against the simulated boot ROM. The wire-level contract is in vectors.test.ts.
import { describe, expect, it } from 'vitest';
import { A133, FEL_CHUNK, FelDevice, bootUboot } from '../src';
import { FakeBrom, eq, makeSpl } from './fakes';

describe('FelDevice against a simulated boot ROM', () => {
  it('reads the version, a register, and round-trips memory in 64 KiB writes', async () => {
    const brom = new FakeBrom();
    const fel = new FelDevice(brom);
    const v = await fel.version();
    expect(v.socId).toBe(0x1855);
    expect(v.signature).toBe('AWUSBFEX');
    brom.regs.set(0x03006004, 0x12345678);
    expect(await fel.readl(0x03006004)).toBe(0x12345678);
    const data = new Uint8Array(FEL_CHUNK * 2 + 100).map((_, i) => i & 0xff);
    await fel.write(0x50000000, data);
    expect(brom.felWrites.map((w) => w.len).slice(-3)).toEqual([FEL_CHUNK, FEL_CHUNK, 100]);
    expect(eq(await fel.read(0x50000000, data.length), data)).toBe(true);
  });

  it('bootUboot: SPL runs with the right SRAM layout, U-Boot lands at 0x4a000000, reset64 there', async () => {
    const brom = new FakeBrom();
    const fel = new FelDevice(brom);
    const spl = makeSpl(0x9000, 0x40000); // with U-Boot appended, as u-boot-sunxi-with-spl.bin
    const uboot = new Uint8Array(300_000).map((_, i) => (i * 13) & 0xff);
    const progress: number[] = [];
    await bootUboot(fel, spl, uboot, (p) => progress.push(p));
    expect(brom.splRan && eq(brom.splRan, spl.subarray(0, 0x9000))).toBe(true);
    expect(eq(brom.load(A133.ubootAddr, uboot.length), uboot)).toBe(true);
    expect(brom.resetEntry).toBe(A133.ubootAddr);
    expect(brom.felWrites.every((w) => w.len <= FEL_CHUNK)).toBe(true);
    expect(progress.at(-1)).toBe(1);
    expect(brom.closed).toBe(true);
  });

  it('refuses a chip with secure boot enabled before running anything', async () => {
    const brom = new FakeBrom();
    brom.regs.set(A133.sidSecureBootReg, 1);
    await expect(bootUboot(new FelDevice(brom), makeSpl(0x9000), new Uint8Array(16))).rejects.toThrow(/secure boot/);
    expect(brom.splRan).toBeNull();
  });

  it('refuses when the boot ROM left the MMU on', async () => {
    const brom = new FakeBrom();
    brom.sctlr |= 1;
    await expect(new FelDevice(brom).runSpl(makeSpl(0x9000))).rejects.toThrow(/MMU/);
  });
});
