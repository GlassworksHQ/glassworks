import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { Disk, MemoryStore, parseSparseHeader, readSparse } from '@glassworks/ext4';
import { GLASS_PANES_KEY_FINGERPRINT, USERDATA_BYTES } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey } from '@glassworks/lululemon-mirror-installer/fakes';
import {
  buildSurgicalSparseImage, loadBackupModule, patchPostBootForBackup, patchStockServicesJar,
  renderSurgicalReport, runStockSurgicalAdb, SELINUX_PERMISSIVE_PIN, START_CONFIRMATION_LOG_STRING,
  STOCK_DPMD_LIB_PATH, STOCK_POST_BOOT_PATH, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES,
  validateKernelModuleElf,
  type FiemapExtent, type SurgicalAdbDeps,
} from '../src/surgical-adb';
import { createHash } from 'node:crypto';

/**
 * Builds a tiny valid real DEX 035 + ZIP fixture containing `UsbDebuggingManager` with
 * `startConfirmation(String, String)`, `allowUsbDebugging(boolean, String)`, `denyUsbDebugging()`,
 * and the exact 71-byte log string from stock Android 6.0.1, using javac + d8 when available, or
 * falls back to checking the real DEX fixture.
 */
function findTool(name: string): string | undefined {
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? join(process.env.HOME ?? '', 'Library/Android/sdk');
  const bt = join(sdk, 'build-tools');
  if (!existsSync(bt)) return undefined;
  try {
    const out = execFileSync('find', [bt, '-name', name], { encoding: 'utf8' })
      .split(/\r?\n/).filter(Boolean).sort();
    return out.at(-1);
  } catch {
    return undefined;
  }
}

/** True only when an Android SDK build-tools `d8` is on this machine; gates the `it.skipIf()` calls
 * below so a missing optional prerequisite is reported as an explicit SKIP, not a silent pass. */
const HAS_D8 = Boolean(findTool('d8'));

function buildSyntheticStockServicesJar(): Uint8Array {
  const d8 = findTool('d8');
  if (!d8) throw new Error('buildSyntheticStockServicesJar requires d8 (guard call sites with `it.skipIf(!HAS_D8)`)');
  const dir = mkdtempSync(join(tmpdir(), 'gw-surgical-dex-'));
  try {
    const srcDir = join(dir, 'src/com/android/server/usb');
    execFileSync('mkdir', ['-p', srcDir]);
    writeFileSync(
      join(srcDir, 'UsbDebuggingManager.java'),
      `package com.android.server.usb;
public class UsbDebuggingManager {
  public boolean allowed;
  public String allowedKey;
  public boolean denied;
  public String lastLog;
  public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
    this.allowed = alwaysAllow;
    this.allowedKey = publicKey;
  }
  public void denyUsbDebugging() {
    this.denied = true;
  }
  private void startConfirmation(String key, String fingerprints) {
    String msg = "${START_CONFIRMATION_LOG_STRING}";
    String a = key.substring(0, 1);
    String b = fingerprints.substring(0, 1);
    if (a.equals(b)) {
      this.lastLog = msg + key + fingerprints + " padding padding padding padding";
    } else {
      this.lastLog = msg + fingerprints + key + " extra extra extra extra extra";
    }
    this.lastLog = this.lastLog + " more bytecode so insns_size exceeds 30 units " + a + b;
  }
}
`,
    );
    const clsDir = join(dir, 'classes');
    execFileSync('mkdir', ['-p', clsDir]);
    execFileSync('javac', ['-source', '1.8', '-target', '1.8', '-d', clsDir, join(srcDir, 'UsbDebuggingManager.java')]);
    const dexDir = join(dir, 'dex');
    execFileSync('mkdir', ['-p', dexDir]);
    execFileSync(d8, ['--min-api', '23', '--output', dexDir, join(clsDir, 'com/android/server/usb/UsbDebuggingManager.class')]);
    // Pack classes.dex + META-INF/MANIFEST.MF with standard ZIP flags=0 (no data descriptor)
    // and a 512-byte comment so tiny 1-class JAR has compression headroom like real 2.4 MB services.jar.
    const jarOut = join(dir, 'services.jar');
    execFileSync('python3', ['-c', `
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

describe('buildSurgicalSparseImage', () => {
  it('writes ONLY the target FIEMAP blocks and preserves all untouched blocks via DONT_CARE', async () => {
    const blockSize = 4096;
    const totalBlocks = 64;
    const partBytes = totalBlocks * blockSize;

    // Pre-populate a mock partition with sentinel 0xaa bytes everywhere
    const disk = new Disk(new MemoryStore(), blockSize, totalBlocks, { zeroed: true });
    const sentinel = new Uint8Array(blockSize).fill(0xaa);
    for (let b = 0; b < totalBlocks; b++) await disk.write(b, sentinel);

    // Multi-extent file spanning non-contiguous, out-of-order physical blocks:
    // logical [0..8192) -> phys block 40..41, logical [8192..12288) -> phys block 10
    const fileData = new Uint8Array(10_000);
    for (let i = 0; i < fileData.length; i++) fileData[i] = (i * 37 + 13) & 0xff;
    const extents: FiemapExtent[] = [
      { logical: 0, physical: 40 * blockSize, block: 40, length: 2 * blockSize, blocks: 2, flags: 0 },
      { logical: 2 * blockSize, physical: 10 * blockSize, block: 10, length: blockSize, blocks: 1, flags: 1 },
    ];

    const sparse = buildSurgicalSparseImage(partBytes, extents, fileData);
    const hdr = parseSparseHeader(sparse);
    expect(hdr).toBeDefined();
    expect(hdr!.blocks).toBe(totalBlocks);

    // Apply sparse image onto the pre-populated disk (DONT_CARE leaves existing blocks untouched!)
    async function* once(b: Uint8Array) { yield b; }
    await readSparse(once(sparse), () => disk);

    // Verify untouched blocks still hold 0xaa
    for (const untouched of [0, 9, 11, 39, 42, 63]) {
      const blk = await disk.read(untouched, 1);
      expect(blk.every((x) => x === 0xaa)).toBe(true);
    }

    // Verify logical slice [0..8192) landed at physical blocks 40..41
    const b40 = await disk.read(40, 2);
    expect(Buffer.from(b40).equals(Buffer.from(fileData.subarray(0, 8192)))).toBe(true);

    // Verify logical slice [8192..10000) landed at physical block 10 with zero tail
    const b10 = await disk.read(10, 1);
    expect(Buffer.from(b10.subarray(0, 10_000 - 8192)).equals(Buffer.from(fileData.subarray(8192)))).toBe(true);
    expect(b10.subarray(10_000 - 8192).every((x) => x === 0)).toBe(true);
  });

  it('rejects misaligned, overlapping, or out-of-bounds FIEMAP extents', () => {
    const data = new Uint8Array(4096).fill(1);
    expect(() => buildSurgicalSparseImage(16 * 4096, [
      { logical: 0, physical: 20 * 4096, block: 20, length: 4096, blocks: 1, flags: 1 },
    ], data)).toThrow(/exceeds partition/);

    expect(() => buildSurgicalSparseImage(16 * 4096, [
      { logical: 0, physical: 4 * 4096, block: 4, length: 4096, blocks: 1, flags: 0 },
      { logical: 4096, physical: 4 * 4096, block: 4, length: 4096, blocks: 1, flags: 1 },
    ], new Uint8Array(8192).fill(1))).toThrow(/overlapping physical/);
  });
});

describe('patchPostBootForBackup', () => {
  it('appends insmod hook while preserving exact original byte length', () => {
    const orig = new TextEncoder().encode(
      `#!/system/bin/sh\n# Copyright (c) 2012-2015, The Linux Foundation. All rights reserved.\n# Long license comment line for padding space in init.qcom.post_boot.sh\nsetprop persist.sys.usb.config mtp,adb\n`,
    );
    const patched = patchPostBootForBackup(orig);
    expect(patched.length).toBe(orig.length);
    const text = new TextDecoder().decode(patched);
    expect(text.startsWith('#!/system/bin/sh\n')).toBe(true);
    expect(text).toContain('setprop persist.sys.usb.config mtp,adb');
    expect(text).toContain('[ -f /data/local/tmp/gw_backup.ko ] && insmod /data/local/tmp/gw_backup.ko && chmod 0444 /dev/block/mmcblk0p22 /dev/block/mmcblk0p19');
  });

  it('rejects unsafe shell paths', () => {
    const orig = new TextEncoder().encode('#!/system/bin/sh\n# ' + 'x'.repeat(200) + '\n');
    expect(() => patchPostBootForBackup(orig, '/data/local/tmp/a;rm -rf /.ko')).toThrow(/unsafe backup module path/);
  });
});

describe('validateKernelModuleElf', () => {
  it('accepts valid AArch64 ET_REL ELF and extracts vermagic + module_layout CRC', () => {
    const buf = new Uint8Array(256);
    buf.set([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0], 0);
    const dv = new DataView(buf.buffer);
    dv.setUint16(16, 1, true); // ET_REL
    dv.setUint16(18, 183, true); // EM_AARCH64
    const vm = new TextEncoder().encode('vermagic=3.10.49-perf-g4869d64961b SMP preempt mod_unload modversions aarch64\0');
    buf.set(vm, 64);
    dv.setUint32(160, 0xa5b7f7dd, true);
    buf.set(new TextEncoder().encode('module_layout\0'), 168);
    const info = validateKernelModuleElf(buf);
    expect(info.arch).toBe('aarch64');
    expect(info.vermagic).toContain('3.10.49-perf-g4869d64961b');
    expect(info.moduleLayoutCrc).toBe('0xa5b7f7dd');
  });

  it('rejects non-AArch64 ELF', () => {
    const buf = new Uint8Array(64);
    buf.set([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0], 0);
    const dv = new DataView(buf.buffer);
    dv.setUint16(16, 1, true);
    dv.setUint16(18, 62, true); // x86_64
    expect(() => validateKernelModuleElf(buf)).toThrow(/EM_AARCH64/);
  });
});

describe('loadBackupModule', () => {
  const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex');

  it('reads a local override from --backup-module, without fetching', async () => {
    const ko = new Uint8Array([0x7f, 0x45, 0x4c, 0x46]);
    let fetched = false;
    const r = await loadBackupModule(
      { backupModulePath: '/tmp/mine.ko' },
      { readHostFile: async () => ko, fetchUrl: async () => { fetched = true; return new Uint8Array(); } },
    );
    expect(r.bytes).toBe(ko);
    expect(r.sourceNote).toContain('/tmp/mine.ko');
    expect(fetched).toBe(false);
  });

  it('fetches the pinned module from the pin\'s Refract URL when there is no override', async () => {
    let askedUrl = '';
    await loadBackupModule({}, {
      readHostFile: async () => new Uint8Array(),
      fetchUrl: async (url) => { askedUrl = url; throw new Error('stop'); },
    }).catch(() => {});
    expect(askedUrl).toBe(SELINUX_PERMISSIVE_PIN.refractUrl);
    expect(askedUrl).toContain('refract.glassworks.dev');
  });

  it('refuses a fetched module whose size does not match the pin', async () => {
    await expect(loadBackupModule({}, {
      readHostFile: async () => new Uint8Array(),
      fetchUrl: async () => new Uint8Array(SELINUX_PERMISSIVE_PIN.size + 1),
    })).rejects.toThrow(/is \d+ B, the pin says/);
  });

  it('refuses a fetched module whose SHA-256 does not match the pin', async () => {
    // Right size, wrong bytes: the pin's hash gate must reject it before anything is built.
    const wrong = new Uint8Array(SELINUX_PERMISSIVE_PIN.size).fill(0x41);
    expect(sha(wrong)).not.toBe(SELINUX_PERMISSIVE_PIN.sha256);
    await expect(loadBackupModule({}, {
      readHostFile: async () => new Uint8Array(),
      fetchUrl: async () => wrong,
    })).rejects.toThrow(/SHA-256 .* != pinned/);
  });

  it('refuses when there is no override and no fetcher', async () => {
    await expect(loadBackupModule({}, { readHostFile: async () => new Uint8Array() }))
      .rejects.toThrow(/no way to fetch the pinned backup module/);
  });

  it('pins the selinux_permissive artifact as GPL-2.0-only from glassworks-device-bins', () => {
    expect(SELINUX_PERMISSIVE_PIN.license).toBe('GPL-2.0-only');
    expect(SELINUX_PERMISSIVE_PIN.allowlistId).toContain('glassworks-device-bins/');
    expect(SELINUX_PERMISSIVE_PIN.sha256).toMatch(/^[0-9a-f]{64}$/);
  });
});

/**
 * ELF64/AArch64 shared object shaped like stock `libdpmframework.so`: one RX PT_LOAD holding
 * .text/.eh_frame/.dynamic/.rela.dyn, one RW PT_LOAD holding .init_array, one R_AARCH64_RELATIVE
 * for .init_array[0]. Big enough for the dpmd payload plus a 256-byte module.
 */
function syntheticDpmdSo(): Uint8Array {
  const textVaddr = 0x100;
  const ehVaddr = 0x140;
  const ehSize = 0x800;
  const dynamicVaddr = 0x940;
  const relaVaddr = 0x980;
  const initVaddr = 0x1000;
  const shstrtabOff = 0x2000;
  const shoff = 0x2400;
  const so = new Uint8Array(shoff + 7 * 64);
  const u16 = (at: number, v: number): void => { so[at] = v & 0xff; so[at + 1] = (v >>> 8) & 0xff; };
  const u32 = (at: number, v: number): void => { for (let i = 0; i < 4; i += 1) so[at + i] = (v >>> (8 * i)) & 0xff; };
  const u64 = (at: number, v: number): void => { for (let i = 0; i < 8; i += 1) so[at + i] = Math.floor(v / 2 ** (8 * i)) & 0xff; };

  so.set([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0], 0);
  u16(0x10, 3);
  u16(0x12, 183);
  u32(0x14, 1);
  u64(0x18, textVaddr);
  u64(0x20, 64);
  u64(0x28, shoff);
  u16(0x34, 64);
  u16(0x36, 56);
  u16(0x38, 2);
  u16(0x3a, 64);
  u16(0x3c, 7);
  u16(0x3e, 6);

  const phdr = (i: number, flags: number, off: number, vaddr: number, filesz: number): void => {
    const at = 64 + i * 56;
    u32(at, 1);
    u32(at + 4, flags);
    u64(at + 8, off);
    u64(at + 16, vaddr);
    u64(at + 32, filesz);
    u64(at + 40, filesz);
    u64(at + 48, 0x1000);
  };
  phdr(0, 5, 0, 0, 0xa00);
  phdr(1, 6, 0x1000, 0x1000, 0x100);

  so[textVaddr] = 0xd6;
  so[textVaddr + 1] = 0x5f;
  so[textVaddr + 2] = 0x03;
  so[textVaddr + 3] = 0xc0;

  u32(ehVaddr, 0x0c);
  u32(ehVaddr + 0x10, 0x14);
  u32(ehVaddr + 0x14, 0x18);

  u64(dynamicVaddr, 25);
  u64(dynamicVaddr + 8, initVaddr);
  u64(dynamicVaddr + 16, 27);
  u64(dynamicVaddr + 24, 8);

  u64(relaVaddr, initVaddr);
  u64(relaVaddr + 8, 1027);
  u64(relaVaddr + 16, textVaddr);

  const names = '\0.text\0.eh_frame\0.dynamic\0.rela.dyn\0.init_array\0.shstrtab\0';
  const nameAt: Record<string, number> = {};
  for (const n of ['.text', '.eh_frame', '.dynamic', '.rela.dyn', '.init_array', '.shstrtab']) {
    nameAt[n] = names.indexOf(`${n}\0`);
  }
  for (let i = 0; i < names.length; i += 1) so[shstrtabOff + i] = names.charCodeAt(i);

  const shdr = (i: number, name: string, type: number, flags: number, addr: number, off: number, size: number): void => {
    const at = shoff + i * 64;
    u32(at, name ? nameAt[name]! : 0);
    u32(at + 4, type);
    u64(at + 8, flags);
    u64(at + 16, addr);
    u64(at + 24, off);
    u64(at + 32, size);
    u64(at + 48, 8);
  };
  shdr(1, '.text', 1, 6, textVaddr, textVaddr, 0x40);
  shdr(2, '.eh_frame', 1, 2, ehVaddr, ehVaddr, ehSize);
  shdr(3, '.dynamic', 6, 3, dynamicVaddr, dynamicVaddr, 0x30);
  shdr(4, '.rela.dyn', 4, 2, relaVaddr, relaVaddr, 0x18);
  shdr(5, '.init_array', 1, 3, initVaddr, 0x1000, 8);
  shdr(6, '.shstrtab', 3, 0, 0, shstrtabOff, names.length);
  return so;
}

describe('patchStockServicesJar + runStockSurgicalAdb', () => {
  // javac + d8 + the python3 zip repack are slow (and the first invocation also pays JVM/toolchain
  // cold-start); give this real build its own generous timeout instead of vitest's 5s default.
  it.skipIf(!HAS_D8)('patches UsbDebuggingManager.startConfirmation to exact byte length and passes dexdump -c', async () => {
    const jar = buildSyntheticStockServicesJar();
    const key = fakeAdbKey(0x33, 'unit@glassworks');
    const res = await patchStockServicesJar(jar, key);
    expect(res.patched.length).toBe(jar.length);
    expect(res.fingerprint).not.toBe(GLASS_PANES_KEY_FINGERPRINT);
    expect(res.patchedCodeUnits).toBeGreaterThanOrEqual(24);
    // The tool is a USB-ADB-only probe: none of Kiln's in-place extras.
    expect(res.deviceOwner).toBeUndefined();
    expect(res.stockAp).toBeUndefined();
    expect(res.usbAllow).toBeUndefined();
    expect(res.timeZone).toBeUndefined();
    expect(res.time).toBeUndefined();

    const dexdump = findTool('dexdump');
    if (dexdump) {
      const dir = mkdtempSync(join(tmpdir(), 'gw-surgical-verify-'));
      try {
        const p = join(dir, 'patched.jar');
        writeFileSync(p, res.patched);
        const check = execFileSync(dexdump, ['-c', p], { encoding: 'utf8' });
        expect(check).toContain('Checksum verified');
        const out = execFileSync(dexdump, ['-d', p], { encoding: 'utf8' });
        expect(out).toContain(`unable to start ${res.fingerprint}`);
        expect(out).toContain('allowUsbDebugging:(ZLjava/lang/String;)V');
        expect(out).toContain('denyUsbDebugging:()V');
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    }
  }, 30_000);

  it('stops cleanly before any write when services.odex is detected', async () => {
    const writes = new Map<string, Uint8Array | string>();
    let flashed = false;
    const fakeJar = new Uint8Array(4096).fill(0x50);
    const deps: SurgicalAdbDeps = {
      now: () => new Date('2026-11-23T06:00:00.000Z'),
      sleep: async () => {},
      log: () => {},
      confirm: async () => true,
      connectHttp: () => ({
        async status() { return { ok: true, uid: 10099, pid: 100, sdk: 23, fiemap: true, bootCompleted: '1' }; },
        async props() { return ''; },
        async dtPanel() { return ''; },
        async list(p: string) {
          if (p === '/system/framework') return ['d\tr\t0\t/system/framework/oat', 'f\tr\t4096\t/system/framework/services.jar'];
          if (p === '/system/framework/oat/arm64') return ['f\tr\t9999\t/system/framework/oat/arm64/services.odex'];
          return [];
        },
        async readFile(p: string) {
          if (p === STOCK_SERVICES_JAR_PATH) return { status: 'ok', data: fakeJar };
          return { status: 'missing' };
        },
        async fiemap(p: string) {
          return { path: p, size: fakeJar.length, ino: 10, extents: [{ logical: 0, physical: 4096 * 100, block: 100, length: 4096, blocks: 1, flags: 1 }] };
        },
      }),
      waitForFastboot: async () => { flashed = true; return null; },
      checkAdb: async () => ({ usb: 'not-seen' }),
      loadProbeApp: async () => undefined,
      writeFile: async (rel, data) => { writes.set(rel, data); return `/tmp/out/${rel}`; },
      readHostFile: async () => new Uint8Array(),
    };

    const { report, ok } = await runStockSurgicalAdb({
      mode: 'full',
      probeHost: '192.168.43.1',
      probeHttpPort: 18765,
      adbKey: fakeAdbKey(9, 'test@host'),
      waitSeconds: 30,
      outDir: '/tmp/out',
    }, deps);

    expect(ok).toBe(false);
    expect(flashed).toBe(false);
    expect(report.servicesOdexFound).toBe(true);
    expect(report.stopReason).toContain('services.odex');
    expect(writes.has('backup/restore-services.sparse')).toBe(true);
    expect(renderSurgicalReport(report)).toContain('services.odex');
  });

  it('stops before any flash when the dpmd library is the ELF32 copy', async () => {
    const writes = new Map<string, Uint8Array | string>();
    let flashed = false;
    const fakeJar = new Uint8Array(4096).fill(0x50);
    const elf32 = new Uint8Array(64);
    elf32.set([0x7f, 0x45, 0x4c, 0x46, 1, 1, 1, 0], 0);
    const fakeKo = new Uint8Array(64);
    fakeKo.set([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0], 0);
    const kdv = new DataView(fakeKo.buffer);
    kdv.setUint16(16, 1, true);
    kdv.setUint16(18, 183, true);
    fakeKo.set(new TextEncoder().encode('vermagic=3.10.49-perf aarch64\0'), 32);

    const deps: SurgicalAdbDeps = {
      now: () => new Date('2026-11-24T22:00:00.000Z'),
      sleep: async () => {},
      log: () => {},
      confirm: async () => true,
      connectHttp: () => ({
        async status() { return { ok: true, uid: 10099, pid: 100, sdk: 23, fiemap: true, bootCompleted: '1' }; },
        async props() { return ''; },
        async dtPanel() { return ''; },
        async list() { return []; },
        async readFile(p: string) {
          if (p === STOCK_SERVICES_JAR_PATH) return { status: 'ok', data: fakeJar };
          if (p === STOCK_DPMD_LIB_PATH) return { status: 'ok', data: elf32 };
          return { status: 'missing' };
        },
        async fiemap(p: string) {
          const data = p === STOCK_DPMD_LIB_PATH ? elf32 : fakeJar;
          const blocks = Math.ceil(data.length / 4096);
          return { path: p, size: data.length, ino: 3, extents: [{ logical: 0, physical: 4096, block: 1, length: blocks * 4096, blocks, flags: 1 }] };
        },
      }),
      waitForFastboot: async () => { flashed = true; return null; },
      checkAdb: async () => ({ usb: 'not-seen' }),
      loadProbeApp: async () => undefined,
      writeFile: async (rel, data) => { writes.set(rel, data); return `/tmp/out/${rel}`; },
      readHostFile: async () => fakeKo,
    };

    const { report, ok } = await runStockSurgicalAdb({
      mode: 'full',
      probeHost: '192.168.43.1',
      probeHttpPort: 18765,
      adbKey: fakeAdbKey(9, 'test@host'),
      backupModulePath: '/tmp/fake.ko',
      waitSeconds: 30,
      outDir: '/tmp/out',
    }, deps);

    expect(ok).toBe(false);
    expect(flashed).toBe(false);
    expect(report.stopReason).toMatch(/not ELF64/);
    expect(writes.has('backup/restore-services.sparse')).toBe(true);
    expect(writes.has('patch-dpmd.sparse')).toBe(false);
  });

  it.skipIf(!HAS_D8)('runs full Steps 1-3 + Step 4 backup & auto-restore on a fake stock Mirror', async () => {
    const jar = buildSyntheticStockServicesJar();
    const writes = new Map<string, Uint8Array | string>();
    const flashed: Array<{ part: string; path: string }> = [];
    const postBoot = new TextEncoder().encode(
      `#!/system/bin/sh\n# ${'A'.repeat(200)}\nsetprop persist.sys.usb.config mtp,adb\n`,
    );
    const dpmdSo = syntheticDpmdSo();
    const fakeKo = new Uint8Array(256);
    fakeKo.set([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0], 0);
    const kdv = new DataView(fakeKo.buffer);
    kdv.setUint16(16, 1, true);
    kdv.setUint16(18, 183, true);
    fakeKo.set(new TextEncoder().encode('vermagic=3.10.49-perf-g4869d64961b SMP preempt mod_unload modversions aarch64\0'), 64);
    kdv.setUint32(160, 0xa5b7f7dd, true);
    fakeKo.set(new TextEncoder().encode('module_layout\0'), 168);

    let step4Boot = false;
    const deps: SurgicalAdbDeps = {
      now: () => new Date('2026-11-23T06:00:00.000Z'),
      sleep: async () => {},
      log: () => {},
      confirm: async () => true,
      connectHttp: () => ({
        async status() { return { ok: true, uid: 10099, pid: 100, sdk: 23, fiemap: true, bootCompleted: '1' }; },
        async props() { return ''; },
        async dtPanel() { return ''; },
        async list(p: string) {
          if (p === '/system/framework') return ['f\tr\t1000\t/system/framework/services.jar'];
          if (p === '/system/app') return ['d\tr\t0\t/system/app/MirrorOobe'];
          return [];
        },
        async readFile(p: string) {
          if (p === STOCK_SERVICES_JAR_PATH) return { status: 'ok', data: jar };
          if (p === STOCK_POST_BOOT_PATH) return { status: 'ok', data: postBoot };
          if (p === STOCK_DPMD_LIB_PATH) return { status: 'ok', data: dpmdSo };
          return { status: 'missing' };
        },
        async fiemap(p: string) {
          if (p === STOCK_SERVICES_JAR_PATH) {
            const blocks = Math.ceil(jar.length / 4096);
            return { path: p, size: jar.length, ino: 10, extents: [{ logical: 0, physical: 30000 * 4096, block: 30000, length: blocks * 4096, blocks, flags: 1 }] };
          }
          if (p === STOCK_DPMD_LIB_PATH) {
            const blocks = Math.ceil(dpmdSo.length / 4096);
            return { path: p, size: dpmdSo.length, ino: 880, extents: [{ logical: 0, physical: 18000 * 4096, block: 18000, length: blocks * 4096, blocks, flags: 1 }] };
          }
          const blocks = Math.ceil(postBoot.length / 4096);
          return { path: p, size: postBoot.length, ino: 496, extents: [{ logical: 0, physical: 22517 * 4096, block: 22517, length: blocks * 4096, blocks, flags: 1 }] };
        },
      }),
      waitForFastboot: async () => ({
        serial: 'FAKE1234',
        async oemDeviceInfo() { return ['Device unlocked: false']; },
        async getvar(name: string) {
          if (name === 'product') return 'MSM8916';
          if (name === 'partition-size:system') return `0x${STOCK_SYSTEM_PARTITION_BYTES.toString(16)}`;
          if (name === 'partition-size:userdata') return `0x${USERDATA_BYTES.toString(16)}`;
          return undefined;
        },
        async flashPartition(part, path) {
          flashed.push({ part, path });
          if (path.includes('patch-dpmd')) step4Boot = true;
        },
        async erasePartition() {},
        async reboot() {},
      }),
      checkAdb: async () => ({
        usb: 'device',
        session: {
          async shell(cmd: string) {
            if (cmd === 'id') return { out: 'uid=2000(shell)\n', code: 0 };
            if (cmd === 'getenforce') return { out: step4Boot ? 'Permissive\n' : 'Enforcing\n', code: 0 };
            if (cmd.includes('sys.boot_completed')) return { out: '1\n', code: 0 };
            if (cmd.includes('[ -r /dev/block/mmcblk0p22 ]')) return { out: '', code: step4Boot ? 0 : 1 };
            if (cmd.includes('ro.build.version.mirror')) return { out: '1.24.0\n', code: 0 };
            if (cmd === 'pm list packages') return { out: 'package:com.mirror.app\n', code: 0 };
            return { out: '', code: 0 };
          },
          async readFile() { return null; },
          async push() {},
          async rebootBootloader() {},
          async streamCommandToFile(cmd: string) {
            if (cmd.includes('mmcblk0p22')) return { bytes: STOCK_SYSTEM_PARTITION_BYTES, sha256: 'a'.repeat(64) };
            return { bytes: 32 * 1024 * 1024, sha256: 'b'.repeat(64) };
          },
        },
      }),
      loadProbeApp: async () => undefined,
      writeFile: async (rel, data) => { writes.set(rel, data); return `/tmp/out/${rel}`; },
      readHostFile: async () => fakeKo,
    };

    const { report, ok } = await runStockSurgicalAdb({
      mode: 'full',
      probeHost: '192.168.43.1',
      probeHttpPort: 18765,
      adbKey: fakeAdbKey(12, 'tester@host'),
      backupModulePath: '/tmp/fake.ko',
      restoreAllAfterBackup: true,
      waitSeconds: 30,
      outDir: '/tmp/out',
    }, deps);

    expect(ok).toBe(true);
    expect(report.adbAuthorized).toBe(true);
    expect(report.backupSystemBytes).toBe(STOCK_SYSTEM_PARTITION_BYTES);
    expect(report.restoredDpmd).toBe(true);
    expect(report.restoredServices).toBe(true);
    expect(report.dpmdChainedVaddr).toBe(0x100);
    expect(writes.has('backup/libdpmframework.so.orig')).toBe(true);
    expect(writes.has('patch-dpmd.sparse')).toBe(true);
    expect(writes.has('patch-post-boot.sparse')).toBe(false);
    expect(flashed.map((f) => `${f.part}:${f.path.split('/').pop()}`)).toEqual([
      'system:patch-services.sparse',
      'userdata:userdata-adb.sparse',
      'system:patch-dpmd.sparse',
      'system:restore-dpmd.sparse',
      'system:restore-services.sparse',
    ]);

    // Verify --apply-adb can re-apply the saved patch-services.sparse cleanly from backup/
    flashed.length = 0;
    const applyRes = await runStockSurgicalAdb({
      mode: 'apply-adb',
      probeHost: '192.168.43.1',
      probeHttpPort: 18765,
      backupDir: '/tmp/out/backup',
      waitSeconds: 30,
      outDir: '/tmp/out-reapply',
    }, {
      ...deps,
      readHostFile: async (absPath) => {
        const rel = absPath.replace(/^\/tmp\/out\//, '');
        const v = writes.get(rel);
        if (!v) throw new Error(`missing ${rel}`);
        return typeof v === 'string' ? new TextEncoder().encode(v) : v;
      },
    });
    expect(applyRes.ok).toBe(true);
    expect(flashed.map((f) => `${f.part}:${f.path.split('/').pop()}`)).toEqual([
      'system:patch-services.sparse',
    ]);
  }, 30_000);

  it('runs --backup-only over USB adb forward without SoftAP, clearing leftover post_boot first', async () => {
    const dpmdSo = syntheticDpmdSo();
    const origServices = new Uint8Array(4096).fill(0x55);
    const fakePostBootRestore = new Uint8Array(64).fill(0x22);
    const fakeKo = new Uint8Array(256);
    fakeKo.set([0x7f, 0x45, 0x4c, 0x46, 2, 1, 1, 0], 0);
    const kdv = new DataView(fakeKo.buffer);
    kdv.setUint16(16, 1, true);
    kdv.setUint16(18, 183, true);
    fakeKo.set(new TextEncoder().encode('vermagic=3.10.49-perf-g4869d64961b SMP preempt mod_unload modversions aarch64\0'), 64);
    kdv.setUint32(160, 0xa5b7f7dd, true);
    fakeKo.set(new TextEncoder().encode('module_layout\0'), 168);

    const prevManifest = {
      version: 1,
      createdAt: '2026-11-24T21:32:26.000Z',
      systemBytes: STOCK_SYSTEM_PARTITION_BYTES,
      userdataBytes: USERDATA_BYTES,
      servicesJar: {
        path: STOCK_SERVICES_JAR_PATH,
        size: origServices.length,
        originalSha256: '0'.repeat(64),
        extents: [{ logical: 0, physical: 40597 * 4096, block: 40597, length: 4096, blocks: 1, flags: 1 }],
        origFile: 'services.jar.orig',
        restoreSparse: 'restore-services.sparse',
      },
      postBoot: {
        path: STOCK_POST_BOOT_PATH,
        size: 4096,
        originalSha256: '1'.repeat(64),
        extents: [{ logical: 0, physical: 22517 * 4096, block: 22517, length: 4096, blocks: 1, flags: 1 }],
        origFile: 'init.qcom.post_boot.sh.orig',
        restoreSparse: 'restore-post-boot.sparse',
      },
    };

    const writes = new Map<string, Uint8Array | string>();
    const flashed: Array<{ part: string; path: string }> = [];
    const forwarded: number[] = [];
    const removedForwards: number[] = [];
    const httpHosts: string[] = [];
    let step4Boot = false;
    let scrubCount = 0;

    const deps: SurgicalAdbDeps = {
      now: () => new Date('2026-11-24T23:00:00.000Z'),
      sleep: async () => {},
      log: () => {},
      confirm: async () => true,
      connectHttp: (host) => {
        httpHosts.push(host);
        return {
          async status() { return { ok: true, uid: 10099, pid: 100, sdk: 23, fiemap: true, bootCompleted: '1' }; },
          async props() { return ''; },
          async dtPanel() { return ''; },
          async list() { return []; },
          async readFile(p: string) {
            if (p === STOCK_DPMD_LIB_PATH) return { status: 'ok', data: dpmdSo };
            return { status: 'missing' };
          },
          async fiemap(p: string) {
            const blocks = Math.ceil(dpmdSo.length / 4096);
            return { path: p, size: dpmdSo.length, ino: 880, extents: [{ logical: 0, physical: 18000 * 4096, block: 18000, length: blocks * 4096, blocks, flags: 1 }] };
          },
        };
      },
      waitForFastboot: async () => ({
        serial: 'FAKE1234',
        async oemDeviceInfo() { return ['Device unlocked: false']; },
        async getvar(name: string) {
          if (name === 'product') return 'MSM8916';
          if (name === 'partition-size:system') return `0x${STOCK_SYSTEM_PARTITION_BYTES.toString(16)}`;
          return undefined;
        },
        async flashPartition(part, path) {
          flashed.push({ part, path });
          if (path.includes('patch-dpmd')) step4Boot = true;
        },
        async erasePartition() {},
        async reboot() {},
      }),
      checkAdb: async () => ({
        usb: 'device',
        session: {
          async shell(cmd: string) {
            if (cmd === 'id') return { out: 'uid=2000(shell)\n', code: 0 };
            if (cmd === 'getenforce') return { out: step4Boot ? 'Permissive\n' : 'Enforcing\n', code: 0 };
            if (cmd.includes('sys.boot_completed')) return { out: '1\n', code: 0 };
            if (cmd.includes('[ -r /dev/block/mmcblk0p22 ]')) return { out: '', code: step4Boot ? 0 : 1 };
            if (cmd.includes('ro.build.version.mirror')) return { out: '1.24.0\n', code: 0 };
            return { out: '', code: 0 };
          },
          async readFile() { return null; },
          async push() {},
          async rebootBootloader() {},
          async forwardTcp(localPort: number) { forwarded.push(localPort); },
          async removeForward(localPort: number) { removedForwards.push(localPort); },
          async streamCommandToFile(cmd: string) {
            if (cmd.includes('mmcblk0p22')) return { bytes: STOCK_SYSTEM_PARTITION_BYTES, sha256: 'a'.repeat(64) };
            return { bytes: 32 * 1024 * 1024, sha256: 'b'.repeat(64) };
          },
        },
      }),
      loadProbeApp: async () => undefined,
      writeFile: async (rel, data) => { writes.set(rel, data); return `/tmp/out-usb/${rel}`; },
      readHostFile: async (absPath) => {
        if (absPath === '/tmp/fake.ko') return fakeKo;
        if (absPath === '/tmp/prev/backup/manifest.json') return new TextEncoder().encode(JSON.stringify(prevManifest));
        if (absPath === '/tmp/prev/backup/services.jar.orig') return origServices;
        if (absPath === '/tmp/prev/backup/restore-post-boot.sparse') return fakePostBootRestore;
        throw new Error(`unexpected readHostFile: ${absPath}`);
      },
      restoreExtentsInRawImage: async (_p, patches) => {
        scrubCount = patches.length;
        return 'c'.repeat(64);
      },
    };

    const { report, ok } = await runStockSurgicalAdb({
      mode: 'backup-only',
      probeHost: '192.168.43.1',
      probeHttpPort: 18765,
      backupDir: '/tmp/prev/backup',
      backupModulePath: '/tmp/fake.ko',
      waitSeconds: 30,
      outDir: '/tmp/out-usb',
    }, deps);

    expect(ok).toBe(true);
    expect(httpHosts).toEqual(['127.0.0.1']);
    expect(forwarded).toEqual([18765]);
    expect(removedForwards).toEqual([18765]);
    expect(scrubCount).toBe(2);
    expect(report.restoredPostBoot).toBe(true);
    expect(report.restoredDpmd).toBe(true);
    expect(report.restoredServices).toBe(false);
    expect(report.backupSystemSha256).toBe('c'.repeat(64));
    expect(flashed.map((f) => `${f.part}:${f.path.split('/').pop()}`)).toEqual([
      'system:restore-post-boot.sparse',
      'system:patch-dpmd.sparse',
      'system:restore-dpmd.sparse',
    ]);
  });
});
