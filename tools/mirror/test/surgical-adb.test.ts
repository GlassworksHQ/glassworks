import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { Disk, MemoryStore, parseSparseHeader, readSparse } from '@glassworks/ext4';
import { GLASS_PANES_KEY_FINGERPRINT, SYSTEM_BYTES, USERDATA_BYTES } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey } from '@glassworks/lululemon-mirror-installer/fakes';
import {
  buildSurgicalSparseImage, patchPostBootForBackup, patchStockServicesJar,
  renderSurgicalReport, runStockSurgicalAdb, START_CONFIRMATION_LOG_STRING,
  STOCK_POST_BOOT_PATH, STOCK_SERVICES_JAR_PATH, validateKernelModuleElf,
  type FiemapExtent, type SurgicalAdbDeps,
} from '../src/surgical-adb';

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

function buildSyntheticStockServicesJar(): Uint8Array | undefined {
  const d8 = findTool('d8');
  if (!d8) return undefined;
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
  } catch {
    return undefined;
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
    expect(text).toContain('[ -f /data/local/tmp/gw_backup.ko ] && insmod /data/local/tmp/gw_backup.ko');
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

describe('patchStockServicesJar + runStockSurgicalAdb', () => {
  it('patches UsbDebuggingManager.startConfirmation to exact byte length and passes dexdump -c', async () => {
    const jar = buildSyntheticStockServicesJar();
    if (!jar) return; // skip cleanly on machines without Android build-tools d8
    const key = fakeAdbKey(0x33, 'unit@glassworks');
    const res = await patchStockServicesJar(jar, key);
    expect(res.patched.length).toBe(jar.length);
    expect(res.fingerprint).not.toBe(GLASS_PANES_KEY_FINGERPRINT);
    expect(res.patchedCodeUnits).toBeGreaterThanOrEqual(24);

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
  });

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

  it('runs full Steps 1-3 + Step 4 backup & auto-restore on a fake stock Mirror', async () => {
    const jar = buildSyntheticStockServicesJar();
    if (!jar) return;
    const writes = new Map<string, Uint8Array | string>();
    const flashed: Array<{ part: string; path: string }> = [];
    const postBoot = new TextEncoder().encode(
      `#!/system/bin/sh\n# ${'A'.repeat(200)}\nsetprop persist.sys.usb.config mtp,adb\n`,
    );
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
          return { status: 'missing' };
        },
        async fiemap(p: string) {
          if (p === STOCK_SERVICES_JAR_PATH) {
            const blocks = Math.ceil(jar.length / 4096);
            return { path: p, size: jar.length, ino: 10, extents: [{ logical: 0, physical: 30000 * 4096, block: 30000, length: blocks * 4096, blocks, flags: 1 }] };
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
          if (name === 'partition-size:system') return `0x${SYSTEM_BYTES.toString(16)}`;
          if (name === 'partition-size:userdata') return `0x${USERDATA_BYTES.toString(16)}`;
          return undefined;
        },
        async flashPartition(part, path) {
          flashed.push({ part, path });
          if (path.includes('patch-post-boot')) step4Boot = true;
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
            if (cmd.includes('ro.build.version.mirror')) return { out: '1.24.0\n', code: 0 };
            if (cmd === 'pm list packages') return { out: 'package:com.mirror.app\n', code: 0 };
            return { out: '', code: 0 };
          },
          async readFile() { return null; },
          async push() {},
          async rebootBootloader() {},
          async streamCommandToFile(cmd: string) {
            if (cmd.includes('mmcblk0p22')) return { bytes: SYSTEM_BYTES, sha256: 'a'.repeat(64) };
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
    expect(report.backupSystemBytes).toBe(SYSTEM_BYTES);
    expect(report.restoredPostBoot).toBe(true);
    expect(report.restoredServices).toBe(true);
    expect(flashed.map((f) => `${f.part}:${f.path.split('/').pop()}`)).toEqual([
      'system:patch-services.sparse',
      'userdata:userdata-adb.sparse',
      'system:patch-post-boot.sparse',
      'system:restore-post-boot.sparse',
      'system:restore-services.sparse',
    ]);
  });
});
