// The browser-safe Stock-in-place core (src/stock.ts): the surgical sparse builder, the stock
// services.jar patch (USB-ADB authorize + the two Device Owner fixes), the Stage 1 bundle, and the
// stock-bloat `pm hide`/`pm unhide`. The Device Owner patch is checked on a real DEX built with
// javac + d8 (skipped without an Android SDK) and disassembled with dexdump.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { crc32 } from 'node:zlib';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import { zipEntry, zipNames } from '@glassworks/app-adb';
import { Disk, MemoryStore, parseSparseHeader, readSparse } from '@glassworks/ext4';
import { describe, expect, it } from 'vitest';
import {
  STOCK_BLOAT_PACKAGES, STOCK_BOOTANIMATION_BYTES, STOCK_BUNDLE_MAGIC, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES,
  buildSurgicalSparseImage, decodeStockBundle, encodeStockBundle, hideStockBloat, padZipToExactLength, patchStockServicesJar,
  unhideStockBloat, type FiemapExtent,
} from '../src';
import { GLASS_PANES_KEY_FINGERPRINT, adbFingerprint } from '../src/services';
import { fakeAdbKey } from './fakes';

// ---------------------------------------------------------------------------
// Android SDK tool discovery (optional; the DEX tests skip without it)
// ---------------------------------------------------------------------------

function findTool(name: string): string | undefined {
  for (const sdk of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, join(homedir(), 'Library/Android/sdk')]) {
    if (!sdk || !existsSync(join(sdk, 'build-tools'))) continue;
    const found = execFileSync('find', [join(sdk, 'build-tools'), '-name', name], { encoding: 'utf8' })
      .split(/\r?\n/).filter(Boolean).sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
    if (found.length) return found.at(-1);
  }
  return undefined;
}

const D8 = findTool('d8');
const DEXDUMP = findTool('dexdump');
const HAS_D8 = Boolean(D8);

const START_CONFIRMATION_LOG_STRING =
  'unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component ';

/**
 * Builds a real DEX 035 + ZIP `services.jar` with `UsbDebuggingManager` (so the startConfirmation
 * patch runs) AND `DevicePolicyManagerService` shaped like stock AOSP 6.0.1 (so both Device Owner
 * fixes run): `<init>` sets `mHasFeature` from `PackageManager.hasSystemFeature(device_admin)`, and
 * `setDeviceOwner` calls `IBackupManager.setBackupServiceActive(0, false)`. Android framework types
 * are provided as local stubs with the exact AOSP descriptors.
 */
function buildSyntheticStockServicesJar(): Uint8Array {
  if (!D8) throw new Error('buildSyntheticStockServicesJar requires d8 (guard call sites with it.skipIf(!HAS_D8))');
  const dir = mkdtempSync(join(tmpdir(), 'gw-stock-dex-'));
  try {
    const write = (rel: string, src: string): void => {
      const p = join(dir, 'src', rel);
      execFileSync('mkdir', ['-p', p.slice(0, p.lastIndexOf('/'))]);
      writeFileSync(p, src);
    };
    write('android/content/pm/PackageManager.java', `package android.content.pm;
public class PackageManager { public boolean hasSystemFeature(String name) { return false; } }
`);
    write('android/content/Context.java', `package android.content;
import android.content.pm.PackageManager;
public class Context { public PackageManager getPackageManager() { return null; } }
`);
    write('android/app/backup/IBackupManager.java', `package android.app.backup;
public interface IBackupManager { void setBackupServiceActive(int userHandle, boolean makeActive); }
`);
    write('com/android/server/usb/UsbDebuggingManager.java', `package com.android.server.usb;
public class UsbDebuggingManager {
  public boolean allowed;
  public String allowedKey;
  public boolean denied;
  public String lastLog;
  public void allowUsbDebugging(boolean alwaysAllow, String publicKey) { this.allowed = alwaysAllow; this.allowedKey = publicKey; }
  public void denyUsbDebugging() { this.denied = true; }
  private void startConfirmation(String key, String fingerprints) {
    String msg = "${START_CONFIRMATION_LOG_STRING}";
    String a = key.substring(0, 1);
    String b = fingerprints.substring(0, 1);
    if (a.equals(b)) { this.lastLog = msg + key + fingerprints + " padding padding padding padding"; }
    else { this.lastLog = msg + fingerprints + key + " extra extra extra extra extra"; }
    this.lastLog = this.lastLog + " more bytecode so insns_size exceeds 30 units " + a + b;
  }
}
`);
    write('com/android/server/devicepolicy/DevicePolicyManagerService.java', `package com.android.server.devicepolicy;
import android.content.Context;
import android.app.backup.IBackupManager;
public class DevicePolicyManagerService {
  public boolean mHasFeature;
  IBackupManager mBackup;
  String owner;
  public DevicePolicyManagerService(Context context) {
    this.mHasFeature = context.getPackageManager().hasSystemFeature("android.software.device_admin");
  }
  public boolean setDeviceOwner(String packageName, String ownerName) {
    mBackup.setBackupServiceActive(0, false);
    this.owner = packageName;
    return true;
  }
}
`);
    const sources = [
      'android/content/pm/PackageManager.java', 'android/content/Context.java',
      'android/app/backup/IBackupManager.java', 'com/android/server/usb/UsbDebuggingManager.java',
      'com/android/server/devicepolicy/DevicePolicyManagerService.java',
    ].map((r) => join(dir, 'src', r));
    const clsDir = join(dir, 'classes');
    execFileSync('mkdir', ['-p', clsDir]);
    execFileSync('javac', ['-source', '1.8', '-target', '1.8', '-d', clsDir, ...sources]);
    const classFiles = execFileSync('find', [clsDir, '-name', '*.class'], { encoding: 'utf8' }).split(/\r?\n/).filter(Boolean);
    const dexDir = join(dir, 'dex');
    execFileSync('mkdir', ['-p', dexDir]);
    execFileSync(D8, ['--min-api', '23', '--output', dexDir, ...classFiles]);
    const jarOut = join(dir, 'services.jar');
    execFileSync('python3', ['-I', '-c', `
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\\r\\n\\r\\n')
    z.write(sys.argv[2], 'classes.dex')
    z.comment = b' ' * 512
`, jarOut, join(dexDir, 'classes.dex')]);
    return new Uint8Array(readFileSync(jarOut));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** dexdump -d output for one class, from its `Class descriptor :` header to the next class or end. */
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

// ---------------------------------------------------------------------------
// padZipToExactLength
// ---------------------------------------------------------------------------

/** A STORED zip of the given files with an optional comment, laid out as Python's zipfile writes it. */
function storedZip(files: Record<string, string>, comment = new Uint8Array(0)): Uint8Array {
  const te = new TextEncoder();
  const locals: Uint8Array[] = [], centrals: Uint8Array[] = [];
  let offset = 0;
  for (const [name, text] of Object.entries(files)) {
    const n = te.encode(name), data = te.encode(text), crc = crc32(data);
    const lh = new Uint8Array(30 + n.length + data.length), l = new DataView(lh.buffer);
    l.setUint32(0, 0x04034b50, true); l.setUint16(4, 20, true); l.setUint32(14, crc, true);
    l.setUint32(18, data.length, true); l.setUint32(22, data.length, true); l.setUint16(26, n.length, true);
    lh.set(n, 30); lh.set(data, 30 + n.length);
    const ch = new Uint8Array(46 + n.length), c = new DataView(ch.buffer);
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint32(16, crc, true);
    c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, n.length, true);
    c.setUint32(42, offset, true); ch.set(n, 46);
    locals.push(lh); centrals.push(ch); offset += lh.length;
  }
  const cdSize = centrals.reduce((a, b) => a + b.length, 0);
  const eocd = new Uint8Array(22 + comment.length), e = new DataView(eocd.buffer);
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, centrals.length, true); e.setUint16(10, centrals.length, true);
  e.setUint32(12, cdSize, true); e.setUint32(16, offset, true); e.setUint16(20, comment.length, true); eocd.set(comment, 22);
  const out = new Uint8Array(offset + cdSize + eocd.length);
  let at = 0;
  for (const part of [...locals, ...centrals, eocd]) { out.set(part, at); at += part.length; }
  return out;
}

function unzipTest(zip: Uint8Array): string | undefined {
  try { execFileSync('unzip', ['-v'], { stdio: 'ignore' }); } catch { return undefined; }
  const dir = mkdtempSync(join(tmpdir(), 'gw-padzip-'));
  try {
    writeFileSync(join(dir, 'a.zip'), zip);
    return execFileSync('unzip', ['-t', join(dir, 'a.zip')], { encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

describe('padZipToExactLength', () => {
  const zip = storedZip({ 'desc.txt': '300 400 10\np 0 0 g3 #0a0b0f\n', 'g3/00.png': 'not really a png' });

  it('pads to the exact length with a zero comment, and the zip reads the same', async () => {
    const target = zip.length + 1000;
    const padded = padZipToExactLength(zip, target);
    expect(padded.length).toBe(target);
    expect(padded.subarray(0, zip.length - 2)).toEqual(zip.subarray(0, zip.length - 2)); // only the comment length changes
    expect(new DataView(padded.buffer).getUint16(zip.length - 2, true)).toBe(1000);
    expect(padded.subarray(zip.length).every((b) => b === 0)).toBe(true);
    expect(zipNames(padded)).toEqual(['desc.txt', 'g3/00.png']);
    expect(new TextDecoder().decode(await zipEntry(padded, 'desc.txt'))).toBe('300 400 10\np 0 0 g3 #0a0b0f\n');
    const t = unzipTest(padded);
    if (t !== undefined) expect(t).toMatch(/No errors detected/);
  });

  it('returns an equal copy at zero padding, and pads by the most a comment holds', () => {
    const same = padZipToExactLength(zip, zip.length);
    expect(same).toEqual(zip);
    expect(same).not.toBe(zip);
    const most = padZipToExactLength(zip, zip.length + 0xffff);
    expect(new DataView(most.buffer).getUint16(zip.length - 2, true)).toBe(0xffff);
    expect(zipNames(most)).toEqual(['desc.txt', 'g3/00.png']);
  });

  it('leaves the input untouched', () => {
    const copy = Uint8Array.from(zip);
    padZipToExactLength(zip, zip.length + 10);
    expect(zip).toEqual(copy);
  });

  it('works on a view into a larger buffer', () => {
    const big = new Uint8Array(zip.length + 64);
    big.set(zip, 32);
    const padded = padZipToExactLength(big.subarray(32, 32 + zip.length), zip.length + 5);
    expect(zipNames(padded)).toEqual(['desc.txt', 'g3/00.png']);
  });

  it('refuses a zip longer than the target, too much padding, a comment, or no end record', () => {
    expect(() => padZipToExactLength(zip, zip.length - 1)).toThrow(/longer than the/);
    expect(() => padZipToExactLength(zip, zip.length + 0x10000)).toThrow(/more than a zip comment holds/);
    const commented = storedZip({ 'desc.txt': 'x' }, new TextEncoder().encode('hi'));
    expect(() => padZipToExactLength(commented, commented.length + 4)).toThrow(/already has a 2-byte comment/);
    expect(() => padZipToExactLength(zip.subarray(0, zip.length - 1), zip.length + 4)).toThrow(/no End-Of-Central-Directory/);
    expect(() => padZipToExactLength(new Uint8Array(4), 10)).toThrow(/no End-Of-Central-Directory/);
    expect(() => padZipToExactLength(zip, Number.NaN)).toThrow(/not a whole number/);
  });

  it('takes the committed compact boot animation to stock 1.24.0\'s exact size', async () => {
    const compact = new Uint8Array(readFileSync(join(__dirname, '../../../../branding/lululemon-mirror/bootanimation-compact.zip')));
    const padded = padZipToExactLength(compact, STOCK_BOOTANIMATION_BYTES);
    expect(padded.length).toBe(22901);
    expect(new TextDecoder().decode(await zipEntry(padded, 'desc.txt')).split('\n')[0]).toBe('300 400 10');
    const t = unzipTest(padded);
    if (t !== undefined) expect(t).toMatch(/No errors detected/);
  });
});
