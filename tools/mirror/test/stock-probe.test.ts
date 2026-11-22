// Tests for tools/mirror/stock-probe.ts: sparse userdata layout (verified with e2fsck + debugfs),
// stop-gates, step-by-step progress reporting, redaction, and --dry-run CLI execution.
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Disk, MemoryStore, readSparse, writeSparse } from '@glassworks/ext4';
import { clean, debugfs, debugfsCat, dump, fsck, haveTools } from '@glassworks/ext4/testing';
import { GLASS_PANES_KEY_FINGERPRINT, USERDATA_BYTES, adbFingerprint } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey } from '@glassworks/lululemon-mirror-installer/fakes';
import {
  buildStockProbeUserdata, parseVendorTsv, runStockProbe,
  type AdbState, type StockAdbSession, type StockProbeDeps,
} from '../src/stock-probe';
import { StockProbeUsageError, stockProbeCli } from '../src/stock-probe-cli';
import { DRY_USERDATA } from '../src/dry';

const KEY = fakeAdbKey(11, 'probe@host');

let work: string;
beforeEach(() => { work = mkdtempSync(join(tmpdir(), 'gw-stock-probe-test-')); });
afterEach(() => rmSync(work, { recursive: true, force: true }));

function makeFakeDeps(o: {
  product?: string;
  adbAfterFlash?: AdbState;
  preFlashAdb?: AdbState;
  confirm?: (q: string) => boolean;
  stockVendorBytes?: Uint8Array;
} = {}) {
  const lines: string[] = [];
  const flashed: string[] = [];
  const rebooted: boolean[] = [];
  let isFlashed = false;

  const matchData = new TextEncoder().encode('same-as-glass-panes');
  const matchSha = createHash('sha256').update(matchData).digest('hex');
  const diffData = o.stockVendorBytes ?? new TextEncoder().encode('lululemon-stock-custom-blob');

  const files = new Map<string, Uint8Array>([
    ['/proc/config.gz', new Uint8Array(gzipSync(Buffer.from('CONFIG_LOCALVERSION="-perf-gf46dad5260f"\n# CONFIG_MODULE_SIG is not set\nCONFIG_MODVERSIONS=y\n')))],
    ['/sepolicy', new TextEncoder().encode('stock-sepolicy')],
    ['/file_contexts', new TextEncoder().encode('/data/property(/.*)? u:object_r:property_data_file:s0\n')],
    ['/system/build.prop', new TextEncoder().encode('ro.build.fingerprint=mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys\n')],
    ['/system/etc/mixer_paths_mtp.xml', new TextEncoder().encode('<mixer/>\n')],
    ['/system/etc/firmware/a300_pfp.fw', matchData],
    ['/system/etc/firmware/a300_pm4.fw', diffData],
  ]);

  const session: StockAdbSession = {
    async shell(cmd: string) {
      if (cmd === 'getprop sys.boot_completed') return { out: '1\n', code: 0 };
      if (cmd === 'getprop') {
        return {
          out: [
            '[ro.serialno]: [SECRET_SERIAL_999]',
            '[ro.build.fingerprint]: [mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys]',
            '[ro.bootimage.build.fingerprint]: [mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys]',
            '[ro.board.platform]: [msm8916]',
            '[ro.adb.secure]: [1]',
            '[init.svc.adbd]: [running]',
            '[init.svc.thermal-engine]: [running]',
          ].join('\n') + '\n',
          code: 0,
        };
      }
      if (cmd === 'getenforce') return { out: 'Enforcing\n', code: 0 };
      if (cmd === 'id') return { out: 'uid=2000(shell)\n', code: 0 };
      if (cmd === 'cat /proc/version') return { out: 'Linux version 3.10.49-perf-gf46dad5260f\n', code: 0 };
      if (cmd === 'cat /proc/modules') return { out: 'wlan 4312975 0 - Live 0x0\n', code: 0 };
      if (cmd === 'head -3 /proc/meminfo') return { out: 'MemTotal: 924000 kB\n', code: 0 };
      if (cmd === 'cat /proc/partitions') return { out: '179 19 32768 mmcblk0p19\n', code: 0 };
      if (cmd === 'dmesg') return { out: 'init: androidboot.serialno=SECRET_SERIAL_999 mac aa:bb:cc:dd:ee:ff adsprpc insmod failed\n', code: 0 };
      if (cmd === 'dumpsys display') return { out: '1920x1080\n', code: 0 };
      if (cmd === 'dumpsys window windows') return { out: '  mRotation=3\n', code: 0 };
      if (cmd === 'dumpsys audio') return { out: 'audio ok\n', code: 0 };
      if (cmd.startsWith('ls -la /sys/class/backlight')) return { out: 'lcd-backlight\n', code: 0 };
      if (cmd.startsWith('find /proc/device-tree')) return { out: '/proc/device-tree/soc/panel: qcom,mdss-dsi-panel-name=lvds-1080p\n', code: 0 };
      if (cmd.startsWith('ls /system/etc/mixer_paths')) return { out: '/system/etc/mixer_paths_mtp.xml\n', code: 0 };
      if (cmd.startsWith('ls -ld ')) {
        const p = /'([^']+)'/.exec(cmd)?.[1] ?? '';
        if (p.includes('missing') || p.endsWith('adsprpc.ko') || p.endsWith('/su')) {
          return { out: `ls: ${p}: No such file or directory\n`, code: 1 };
        }
        return { out: `ls: ${p}: Permission denied\n`, code: 1 };
      }
      return { out: '', code: 0 };
    },
    async readFile(path: string) { return files.get(path) ?? null; },
  };

  const deps: StockProbeDeps = {
    log: (l) => lines.push(l),
    confirm: async (q) => (o.confirm ? o.confirm(q) : true),
    sleep: async () => {},
    now: () => new Date('2026-11-21T23:00:00.000Z'),
    vendorEntries: [
      { part: 'gpu-display', path: '/system/etc/firmware/a300_pfp.fw', bytes: matchData.length, sha256: matchSha, label: 'system_file', shell: 'yes' },
      { part: 'gpu-display', path: '/system/etc/firmware/a300_pm4.fw', bytes: 9220, sha256: 'deadbeef', label: 'system_file', shell: 'yes' },
      { part: 'gpu-display', path: '/system/bin/mm-pp-daemon', bytes: 141704, sha256: '1111', label: 'mm-pp-daemon_exec', shell: 'no' },
      { part: 'gpu-display', path: '/system/lib/missing.so', bytes: 100, sha256: '2222', label: 'system_file', shell: 'yes' },
      { part: 'camera', path: '/system/vendor/lib/camera_only.so', bytes: 100, sha256: '3333', label: 'system_file', shell: 'yes' },
    ],
    async checkAdb() {
      if (!isFlashed) {
        const st = o.preFlashAdb ?? 'not-seen';
        return { usb: st, ...(st === 'device' ? { session } : {}) };
      }
      const st = o.adbAfterFlash ?? 'device';
      return { usb: st, ...(st === 'device' ? { session } : {}) };
    },
    async fastboot() {
      return {
        async oemDeviceInfo() { return ['Device tampered: false', 'Device unlocked: false']; },
        async getvar(name: string) {
          const map: Record<string, string> = {
            product: o.product ?? 'MSM8916',
            variant: 'SBC eMMC',
            secure: 'yes',
            'version-bootloader': 'lk-test',
            'max-download-size': '0x10000000',
            'partition-type:system': 'ext4',
            'partition-size:system': '0x4cccd000',
            'partition-type:userdata': 'ext4',
            'partition-size:userdata': `0x${USERDATA_BYTES.toString(16)}`,
          };
          return map[name];
        },
        async flashUserdata(path: string) { flashed.push(path); isFlashed = true; },
        async reboot() { rebooted.push(true); },
      };
    },
  };

  return { deps, lines, flashed, rebooted };
}

describe('buildStockProbeUserdata', () => {
  it('writes e2fsck-clean ext4 with adb_keys, exact-byte property files (no trailing newline), SELinux labels, and optional Wi-Fi PSK', async () => {
    const blocks = DRY_USERDATA / 4096;
    const disk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
    const { plantedPaths } = await buildStockProbeUserdata(disk, {
      adbKey: KEY,
      usbConfig: 'adb',
      tcpPort: 5555,
      wifi: { ssid: 'TestNet', password: 'super-secret-passphrase' },
      time: 1_700_000_000,
    });
    expect(plantedPaths).toEqual([
      '/data/misc/adb/adb_keys',
      '/data/misc/wifi/wpa_supplicant.conf',
      '/data/property/persist.sys.usb.config',
      '/data/property/persist.adb.tcp.port',
    ]);

    const chunks: Uint8Array[] = [];
    await writeSparse(disk, (b) => { chunks.push(b.slice()); });
    const sparse = Buffer.concat(chunks);
    const { disk: roundtrip } = await readSparse(
      new Blob([sparse]).stream(),
      (h) => new Disk(new MemoryStore(), h.blockSize, h.blocks, { zeroed: true }),
    );

    if (!haveTools) return;
    const raw = join(work, 'userdata.raw');
    await dump(roundtrip, raw);
    const check = fsck(raw);
    expect(clean(check), check.out).toBe(true);

    // adb_keys
    expect(new TextDecoder().decode(debugfsCat(raw, '/misc/adb/adb_keys'))).toBe(`${KEY}\n`);
    expect(debugfs(raw, 'ea_list /misc/adb/adb_keys')).toContain('u:object_r:adb_keys_file:s0');

    // persistent properties: exact bytes, NO trailing newline!
    expect(new TextDecoder().decode(debugfsCat(raw, '/property/persist.sys.usb.config'))).toBe('adb');
    expect(new TextDecoder().decode(debugfsCat(raw, '/property/persist.adb.tcp.port'))).toBe('5555');
    expect(debugfs(raw, 'stat /property')).toMatch(/Type:\s+directory\s+Mode:\s+0700/);
    expect(debugfs(raw, 'stat /property/persist.sys.usb.config')).toMatch(/Type:\s+regular\s+Mode:\s+0600/);
    expect(debugfs(raw, 'ea_list /property')).toContain('u:object_r:property_data_file:s0');
    expect(debugfs(raw, 'ea_list /property/persist.sys.usb.config')).toContain('u:object_r:property_data_file:s0');
    expect(debugfs(raw, 'ea_list /property/persist.adb.tcp.port')).toContain('u:object_r:property_data_file:s0');

    // Wi-Fi: PBKDF2 PSK only, never plain password
    const wpa = new TextDecoder().decode(debugfsCat(raw, '/misc/wifi/wpa_supplicant.conf'));
    expect(wpa).toContain('ssid="TestNet"');
    expect(wpa).toMatch(/\tpsk=[0-9a-f]{64}\n/);
    expect(wpa).not.toContain('super-secret-passphrase');
    expect(debugfs(raw, 'ea_list /misc/wifi/wpa_supplicant.conf')).toContain('u:object_r:wifi_data_file:s0');
  }, 30_000);
});

describe('runStockProbe guided flow', () => {
  it('completes all 6 steps on a fake stock Mirror, flashes ONLY userdata, audits vendor files, and redacts secrets', async () => {
    const outDir = join(work, 'out');
    const { deps, flashed, rebooted } = makeFakeDeps();
    const res = await runStockProbe({
      outDir,
      adbKey: KEY,
      usbConfig: 'adb',
      tcpPort: 5555,
      wifi: { ssid: 'HomeWifi', password: 'my-secret-wifi-password' },
      userdataBytesOverride: DRY_USERDATA,
      pullSystemBlobs: true,
    }, deps);

    expect(res.outcome).toBe('complete');
    expect(flashed).toHaveLength(1);
    expect(rebooted).toHaveLength(1);
    expect(res.report.steps.every((s) => s.status === 'done')).toBe(true);
    expect(res.report.flash).toEqual({ userdataFlashed: true, systemTouched: false });

    const md = readFileSync(res.reportPath, 'utf8');
    expect(md).toContain('- [x] **Step 1/6:');
    expect(md).toContain('- [x] **Step 6/6:');
    expect(md).toContain(`| \`gpu-display\` | 4 | 1 | 1 | 1 | 1 |`);
    expect(md).not.toContain('camera_only.so'); // camera excluded by default
    expect(md).toContain(adbFingerprint(KEY));
    // Redaction checks
    expect(md).not.toContain('SECRET_SERIAL_999');
    expect(md).not.toContain('aa:bb:cc:dd:ee:ff');
    expect(md).not.toContain('my-secret-wifi-password');
    expect(md).not.toContain('HomeWifi');
    expect(md).not.toContain(KEY);
    expect(md).toContain('<redacted>');
    expect(md).toContain('<mac-redacted>');
    // Local vendor blobs pulled outside report
    expect(existsSync(join(outDir, 'raw/system/etc/firmware/a300_pm4.fw'))).toBe(true);
  });

  it('stops cleanly at Step 2 if product is not MSM8916 (e.g. NXP i.MX8), writing no files to the device', async () => {
    const outDir = join(work, 'nxp');
    const { deps, flashed } = makeFakeDeps({ product: 'IMX8MM' });
    const res = await runStockProbe({ outDir, adbKey: KEY, userdataBytesOverride: DRY_USERDATA }, deps);

    expect(res.outcome).toBe('blocked');
    expect(flashed).toHaveLength(0);
    expect(res.report.steps[1]!.status).toBe('blocked');
    const md = readFileSync(res.reportPath, 'utf8');
    expect(md).toContain('FAIL — STOP');
  });

  it('stops cleanly at Step 5 when stock adbd rejects adb_keys (1.2x+ / S22Q401 behavior)', async () => {
    const outDir = join(work, 'unauth');
    const { deps } = makeFakeDeps({ adbAfterFlash: 'unauthorized' });
    const res = await runStockProbe({ outDir, adbKey: KEY, userdataBytesOverride: DRY_USERDATA, waitSeconds: 2 }, deps);

    expect(res.outcome).toBe('blocked');
    expect(res.report.steps[4]!.status).toBe('blocked');
    expect(res.report.steps[5]!.status).toBe('skipped');
    expect(res.report.adbWait?.outcome).toBe('unauthorized');
    const md = readFileSync(res.reportPath, 'utf8');
    expect(md).toContain('1.2x+');
  });

  it('supports --read-only, --build-only, --extract-only, and declining Step 4', async () => {
    // --read-only
    const r1 = makeFakeDeps();
    const res1 = await runStockProbe({ outDir: join(work, 'ro'), adbKey: KEY, readOnly: true }, r1.deps);
    expect(res1.outcome).toBe('read-only-done');
    expect(r1.flashed).toHaveLength(0);
    expect(res1.report.steps[2]!.status).toBe('skipped');

    // --build-only
    const r2 = makeFakeDeps();
    const res2 = await runStockProbe({ outDir: join(work, 'bo'), adbKey: KEY, buildOnly: true, userdataBytesOverride: DRY_USERDATA }, r2.deps);
    expect(res2.outcome).toBe('build-only-done');
    expect(r2.flashed).toHaveLength(0);
    expect(existsSync(res2.sparsePath!)).toBe(true);

    // --extract-only
    const r3 = makeFakeDeps({ preFlashAdb: 'device' });
    const res3 = await runStockProbe({ outDir: join(work, 'eo'), adbKey: KEY, extractOnly: true }, r3.deps);
    expect(res3.outcome).toBe('complete');
    expect(r3.flashed).toHaveLength(0);
    expect(res3.report.steps[1]!.status).toBe('skipped');
    expect(res3.report.steps[5]!.status).toBe('done');

    // decline Step 4
    const r4 = makeFakeDeps({ confirm: (q) => !q.includes('FLASH userdata') });
    const res4 = await runStockProbe({ outDir: join(work, 'decline'), adbKey: KEY, userdataBytesOverride: DRY_USERDATA }, r4.deps);
    expect(res4.outcome).toBe('stopped');
    expect(r4.flashed).toHaveLength(0);
    expect(res4.report.steps[3]!.status).toBe('stopped');
    expect(res4.report.flash?.userdataFlashed).toBe(false);
  });

  it('parses option-c-vendor-files.tsv and rejects unsafe paths', () => {
    const tsv = readFileSync(join(__dirname, '../../../frames/lululemon-mirror/research/option-c-vendor-files.tsv'), 'utf8');
    const entries = parseVendorTsv(tsv);
    expect(entries.length).toBeGreaterThan(400);
    expect(() => parseVendorTsv('gpu\t/system/bin/foo;rm -rf /\t10\t00\tsystem_file\tyes\tbad')).toThrow(/unsafe vendor path/);
  });

  it('rejects invalid usbConfig, invalid tcpPort, and --pull-system-blobs inside the git repo', async () => {
    const disk = new Disk(new MemoryStore(), 4096, DRY_USERDATA / 4096, { zeroed: true });
    await expect(buildStockProbeUserdata(disk, { adbKey: KEY, usbConfig: 'adb\n' })).rejects.toThrow(/invalid usbConfig/);
    await expect(buildStockProbeUserdata(disk, { adbKey: KEY, tcpPort: 80 })).rejects.toThrow(/invalid tcpPort/);
    expect(GLASS_PANES_KEY_FINGERPRINT).toMatch(/^([0-9A-F]{2}:){15}[0-9A-F]{2}$/);
    await expect(stockProbeCli(['--dry-run', '--pull-system-blobs', '--out', join(__dirname, 'inside-repo')])).rejects.toBeInstanceOf(StockProbeUsageError);
  });

  it('runs the CLI --dry-run end to end and rejects conflicting mode flags', async () => {
    const outDir = join(work, 'cli-dry');
    const logs: string[] = [];
    const code = await stockProbeCli(['--dry-run', '--out', outDir], (l) => logs.push(l));
    expect(code).toBe(0);
    expect(existsSync(join(outDir, 'report.md'))).toBe(true);
    expect(logs.join('\n')).toContain('--- copy-paste progress report below ---');

    await expect(stockProbeCli(['--read-only', '--extract-only'])).rejects.toBeInstanceOf(StockProbeUsageError);
  });
});
