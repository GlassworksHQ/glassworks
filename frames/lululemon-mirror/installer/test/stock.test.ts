// The browser-safe Stock-in-place core (src/stock.ts): the surgical sparse builder, the stock
// services.jar patch (USB-ADB authorize + the two Device Owner fixes), the Stage 1 bundle, and the
// stock-bloat `pm hide`/`pm unhide`. The Device Owner patch is checked on a real DEX built with
// javac + d8 (skipped without an Android SDK) and disassembled with dexdump.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import { Disk, MemoryStore, parseSparseHeader, readSparse } from '@glassworks/ext4';
import { describe, expect, it } from 'vitest';
import {
  STOCK_BLOAT_PACKAGES, STOCK_BUNDLE_MAGIC, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES,
  buildSurgicalSparseImage, decodeStockBundle, encodeStockBundle, hideStockBloat, patchStockServicesJar,
  unhideStockBloat, type FiemapExtent,
} from '../src';
import { GLASS_PANES_KEY_FINGERPRINT, adbFingerprint } from '../src/services';
import { fakeAdbKey } from './fakes';

import { DEXDUMP, HAS_D8, START_CONFIRMATION_LOG_STRING, buildSyntheticStockServicesJar } from './synthetic-services';

function classBlock(disasm: string, descriptor: string): string {
  const needle = `Class descriptor  : '${descriptor}'`;
  const i = disasm.indexOf(needle);
  if (i < 0) return '';
  const next = disasm.indexOf("Class descriptor  : '", i + needle.length);
  return disasm.slice(i, next < 0 ? undefined : next);
}

/** dexdump -d output for one method body within `block`: from its `name :` header to the next method. */
function methodBlock(block: string, name: string): string {
  const needle = `name          : '${name}'`;
  const i = block.indexOf(needle);
  if (i < 0) return '';
  const next = block.indexOf("name          : '", i + needle.length);
  return block.slice(i, next < 0 ? undefined : next);
}

const DPMS_DESC = 'Lcom/android/server/devicepolicy/DevicePolicyManagerService;';

// ---------------------------------------------------------------------------
// buildSurgicalSparseImage
// ---------------------------------------------------------------------------

describe('buildSurgicalSparseImage', () => {
  it('writes ONLY the target FIEMAP blocks and preserves all untouched blocks via DONT_CARE', async () => {
    const blockSize = 4096;
    const totalBlocks = 64;
    const partBytes = totalBlocks * blockSize;

    const disk = new Disk(new MemoryStore(), blockSize, totalBlocks, { zeroed: true });
    const sentinel = new Uint8Array(blockSize).fill(0xaa);
    for (let b = 0; b < totalBlocks; b++) await disk.write(b, sentinel);

    // logical [0..8192) -> phys block 40..41, logical [8192..12288) -> phys block 10 (out of order)
    const fileData = new Uint8Array(10_000);
    for (let i = 0; i < fileData.length; i++) fileData[i] = (i * 37 + 13) & 0xff;
    const extents: FiemapExtent[] = [
      { logical: 0, physical: 40 * blockSize, block: 40, length: 2 * blockSize, blocks: 2, flags: 0 },
      { logical: 2 * blockSize, physical: 10 * blockSize, block: 10, length: blockSize, blocks: 1, flags: 1 },
    ];

    const sparse = buildSurgicalSparseImage(partBytes, extents, fileData);
    const hdr = parseSparseHeader(sparse);
    expect(hdr?.blocks).toBe(totalBlocks);

    async function* once(b: Uint8Array) { yield b; }
    await readSparse(once(sparse), () => disk);

    for (const untouched of [0, 9, 11, 39, 42, 63]) {
      const blk = await disk.read(untouched, 1);
      expect(blk.every((x) => x === 0xaa)).toBe(true);
    }
    const b40 = await disk.read(40, 2);
    expect(Buffer.from(b40).equals(Buffer.from(fileData.subarray(0, 8192)))).toBe(true);
    const b10 = await disk.read(10, 1);
    expect(Buffer.from(b10.subarray(0, 10_000 - 8192)).equals(Buffer.from(fileData.subarray(8192)))).toBe(true);
    expect(b10.subarray(10_000 - 8192).every((x) => x === 0)).toBe(true);
  });

  it('rejects out-of-bounds, overlapping, misaligned, unsafe-flag, and empty extents', () => {
    const data = new Uint8Array(4096).fill(1);
    expect(() => buildSurgicalSparseImage(16 * 4096, [
      { logical: 0, physical: 20 * 4096, block: 20, length: 4096, blocks: 1, flags: 1 },
    ], data)).toThrow(/exceeds partition/);

    expect(() => buildSurgicalSparseImage(16 * 4096, [
      { logical: 0, physical: 4 * 4096, block: 4, length: 4096, blocks: 1, flags: 0 },
      { logical: 4096, physical: 4 * 4096, block: 4, length: 4096, blocks: 1, flags: 1 },
    ], new Uint8Array(8192).fill(1))).toThrow(/overlapping physical/);

    // UNWRITTEN (0x0800) and friends must be refused
    expect(() => buildSurgicalSparseImage(16 * 4096, [
      { logical: 0, physical: 4 * 4096, block: 4, length: 4096, blocks: 1, flags: 0x0800 },
    ], data)).toThrow(/not plain, initialized data/);

    // not a multiple of the block size
    expect(() => buildSurgicalSparseImage(100, [
      { logical: 0, physical: 0, block: 0, length: 4096, blocks: 1, flags: 1 },
    ], data)).toThrow(/positive multiple/);

    expect(() => buildSurgicalSparseImage(16 * 4096, [], data)).toThrow(/zero extents/);
  });

  it('splits an extent with preallocated trailing blocks at the file end', async () => {
    const blockSize = 4096;
    const totalBlocks = 32;
    // one 4-block extent but only a 1.5-block file: blocks 2 and 3 are preallocated tail -> not written
    const fileData = new Uint8Array(blockSize + 2048).fill(0x5a);
    const sparse = buildSurgicalSparseImage(totalBlocks * blockSize, [
      { logical: 0, physical: 8 * blockSize, block: 8, length: 4 * blockSize, blocks: 4, flags: 1 },
    ], fileData);
    const disk = new Disk(new MemoryStore(), blockSize, totalBlocks, { zeroed: true });
    const sentinel = new Uint8Array(blockSize).fill(0xcc);
    for (let b = 0; b < totalBlocks; b++) await disk.write(b, sentinel);
    async function* once(b: Uint8Array) { yield b; }
    await readSparse(once(sparse), () => disk);
    // blocks 8,9 written (file + zero tail); blocks 10,11 left at sentinel (preallocated, past file end)
    expect((await disk.read(8, 1)).every((x) => x === 0x5a)).toBe(true);
    expect((await disk.read(10, 1)).every((x) => x === 0xcc)).toBe(true);
    expect((await disk.read(11, 1)).every((x) => x === 0xcc)).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// patchStockServicesJar (USB-ADB authorize + Device Owner fixes)
// ---------------------------------------------------------------------------

describe('patchStockServicesJar', () => {
  it('refuses a key matching the forbidden Glass Panes fingerprint', async () => {
    const key = fakeAdbKey(0x52, 'published@glasspanes');
    const jar = new Uint8Array(64); // never reached: key validation happens first
    await expect(
      patchStockServicesJar(jar, key, { forbiddenFingerprint: adbFingerprint(key) }),
    ).rejects.toThrow(/Glass Panes project key/);
  });

  it('refuses a malformed adb key', async () => {
    const jar = new Uint8Array(64);
    await expect(patchStockServicesJar(jar, 'not a key')).rejects.toThrow(/adb key/i);
  });

  it.skipIf(!HAS_D8)('keeps the exact byte length, authorizes only our key, and applies both Device Owner fixes', async () => {
    const jar = buildSyntheticStockServicesJar();
    const key = fakeAdbKey(0x51, 'stock@glassworks');
    const res = await patchStockServicesJar(jar, key); // default enableDeviceOwner: true
    expect(res.patched.length).toBe(jar.length);
    expect(res.fingerprint).not.toBe(GLASS_PANES_KEY_FINGERPRINT);
    expect(res.patchedCodeUnits).toBeGreaterThanOrEqual(24);
    expect(res.deviceOwner).toBeDefined();
    expect(res.deviceOwner!.hasFeatureReg).toBeGreaterThanOrEqual(0);
    expect(res.deviceOwner!.hasFeatureReg).toBeLessThanOrEqual(15);
    expect(res.originalSha256).not.toBe(res.patchedSha256);

    if (DEXDUMP) {
      const dir = mkdtempSync(join(tmpdir(), 'gw-stock-verify-'));
      try {
        const p = join(dir, 'patched.jar');
        writeFileSync(p, res.patched);
        expect(execFileSync(DEXDUMP, ['-c', p], { encoding: 'utf8' })).toContain('Checksum verified');
        const dis = execFileSync(DEXDUMP, ['-d', p], { encoding: 'utf8', maxBuffer: 1 << 30 });

        // startConfirmation: only our fingerprint is approved, through allow/deny.
        expect(dis).toContain(`unable to start ${res.fingerprint}`);
        expect(dis).toContain('allowUsbDebugging:(ZLjava/lang/String;)V');
        expect(dis).toContain('denyUsbDebugging:()V');

        const dpms = classBlock(dis, DPMS_DESC);

        // (a) <init>: the move-result after hasSystemFeature became const/4 vA, #1 (so no plain
        // move-result survives — getPackageManager() still uses move-result-object).
        const ctor = methodBlock(dpms, '<init>');
        expect(ctor).toContain('hasSystemFeature');
        expect(ctor).toContain('const/4');
        expect(ctor).toMatch(/const\/4 v\d+, #int 1/);
        expect(ctor).not.toMatch(/\bmove-result\b(?!-)/); // move-result, not move-result-object

        // (b) setDeviceOwner: the setBackupServiceActive invoke-interface is now three nops.
        const sdo = methodBlock(dpms, 'setDeviceOwner');
        expect(sdo).not.toContain('setBackupServiceActive');
        expect(sdo).not.toContain('invoke-interface');
        expect(sdo).toContain('nop');
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    }
  }, 60_000);

  it.skipIf(!HAS_D8)('with enableDeviceOwner:false patches only startConfirmation (no Device Owner fields)', async () => {
    const jar = buildSyntheticStockServicesJar();
    const res = await patchStockServicesJar(jar, fakeAdbKey(0x52), { enableDeviceOwner: false });
    expect(res.patched.length).toBe(jar.length);
    expect(res.deviceOwner).toBeUndefined();
    if (DEXDUMP) {
      const dir = mkdtempSync(join(tmpdir(), 'gw-stock-noDO-'));
      try {
        const p = join(dir, 'patched.jar');
        writeFileSync(p, res.patched);
        const dis = execFileSync(DEXDUMP, ['-d', p], { encoding: 'utf8', maxBuffer: 1 << 30 });
        const sdo = methodBlock(classBlock(dis, DPMS_DESC), 'setDeviceOwner');
        // left untouched: the backup-service call is still there
        expect(sdo).toContain('invoke-interface');
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    }
  }, 60_000);
});

// ---------------------------------------------------------------------------
// encodeStockBundle / decodeStockBundle
// ---------------------------------------------------------------------------

describe('encode/decodeStockBundle', () => {
  const jar = Uint8Array.from({ length: 2048 }, (_, i) => (i * 131 + 7) & 0xff);
  const meta = { jarPath: STOCK_SERVICES_JAR_PATH, fingerprint: 'AA:BB', systemBytes: STOCK_SYSTEM_PARTITION_BYTES };

  it('round-trips the metadata and the jar, with the GWSB magic and length fields', async () => {
    const bytes = await encodeStockBundle(meta, jar);
    expect(new TextDecoder().decode(bytes.subarray(0, 4))).toBe(STOCK_BUNDLE_MAGIC);
    const { meta: back, servicesJar } = await decodeStockBundle(bytes);
    expect(back.jarPath).toBe(STOCK_SERVICES_JAR_PATH);
    expect(back.fingerprint).toBe('AA:BB');
    expect(back.systemBytes).toBe(STOCK_SYSTEM_PARTITION_BYTES);
    expect(typeof back.jarSha256).toBe('string');
    expect(Buffer.from(servicesJar).equals(Buffer.from(jar))).toBe(true);
  });

  it('rejects a bad magic, a truncated bundle, a length-field mismatch, and a corrupt jar', async () => {
    const bytes = await encodeStockBundle(meta, jar);

    await expect(decodeStockBundle(bytes.subarray(0, 8))).rejects.toThrow(/shorter than/);

    const badMagic = bytes.slice(); badMagic[0] = 0x58;
    await expect(decodeStockBundle(badMagic)).rejects.toThrow(/magic/);

    await expect(decodeStockBundle(bytes.subarray(0, bytes.length - 10))).rejects.toThrow(/length .* != header/);

    const corruptJar = bytes.slice(); corruptJar[bytes.length - 1]! ^= 0xff;
    await expect(decodeStockBundle(corruptJar)).rejects.toThrow(/SHA-256/);

    // metadata that isn't JSON
    const te = new TextEncoder();
    const notJson = te.encode('{not json');
    const bad = new Uint8Array(12 + notJson.length + jar.length);
    bad.set(te.encode(STOCK_BUNDLE_MAGIC), 0);
    const dv = new DataView(bad.buffer);
    dv.setUint32(4, notJson.length, true);
    dv.setUint32(8, jar.length, true);
    bad.set(notJson, 12);
    bad.set(jar, 12 + notJson.length);
    await expect(decodeStockBundle(bad)).rejects.toThrow(/not valid JSON/);
  });
});

// ---------------------------------------------------------------------------
// hideStockBloat / unhideStockBloat
// ---------------------------------------------------------------------------

class HideFake implements PanelShell {
  cmds: string[] = [];
  hidden = new Set<string>();
  constructor(private readonly fails = new Set<string>()) {}
  async run(cmd: string): Promise<{ out: string; code: number }> {
    this.cmds.push(cmd);
    const m = /^pm (hide|unhide) (\S+)$/.exec(cmd);
    if (m) {
      const [, verb, pkg] = m;
      if (this.fails.has(pkg!)) return { out: `Package ${pkg} new hidden state: ${verb === 'hide' ? 'false' : 'true'}\n`, code: 0 };
      if (verb === 'hide') this.hidden.add(pkg!); else this.hidden.delete(pkg!);
      return { out: `Package ${pkg} new hidden state: ${verb === 'hide'}\n`, code: 0 };
    }
    return { out: '', code: 0 };
  }
  async push(): Promise<void> {}
}

describe('hideStockBloat / unhideStockBloat', () => {
  it('runs pm hide on each of the eight stock lululemon packages, in order, and reports each', async () => {
    const p = new HideFake();
    const res = await hideStockBloat(p);
    expect(p.cmds).toEqual(STOCK_BLOAT_PACKAGES.map((pkg) => `pm hide ${pkg}`));
    expect(res.map((r) => r.pkg)).toEqual([...STOCK_BLOAT_PACKAGES]);
    expect(res.every((r) => r.ok)).toBe(true);
    expect([...p.hidden].sort()).toEqual([...STOCK_BLOAT_PACKAGES].sort());
  });

  it('never uses pm disable-user (which SecurityExceptions as shell on an Android 6 user build)', async () => {
    const p = new HideFake();
    await hideStockBloat(p);
    await unhideStockBloat(p);
    expect(p.cmds.some((c) => c.includes('disable-user') || c.includes('disable'))).toBe(false);
  });

  it('unhideStockBloat reverses it with pm unhide', async () => {
    const p = new HideFake();
    await hideStockBloat(p);
    const res = await unhideStockBloat(p);
    expect(p.cmds.slice(STOCK_BLOAT_PACKAGES.length)).toEqual(STOCK_BLOAT_PACKAGES.map((pkg) => `pm unhide ${pkg}`));
    expect(res.every((r) => r.ok)).toBe(true);
    expect(p.hidden.size).toBe(0);
  });

  it('reports a package Android refused to hide (e.g. an active device admin) as not ok', async () => {
    const p = new HideFake(new Set(['com.mirror.watchdog']));
    const res = await hideStockBloat(p);
    const watchdog = res.find((r) => r.pkg === 'com.mirror.watchdog')!;
    expect(watchdog.ok).toBe(false);
    expect(res.filter((r) => r.pkg !== 'com.mirror.watchdog').every((r) => r.ok)).toBe(true);
  });

  it('validates all package names before issuing any pm command', async () => {
    const p = new HideFake();
    await expect(hideStockBloat(p, ['com.mirror.watchdog', 'bad; id'])).rejects.toThrow(/invalid Android package name/);
    expect(p.cmds).toEqual([]);
  });
});
