// Tests for tools/mirror/stock-probe.ts: sparse userdata layout (verified with e2fsck + debugfs,
// including /data/app + 1-entry packages.xml + libfiemap.so), stop-gates, automatic no-ADB HTTP
// fallback + FS_IOC_FIEMAP reporting, redaction, and --dry-run CLI execution.
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync } from 'node:fs';
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
  buildPackagesXml, buildStockProbeUserdata, parseVendorTsv, renderReportMarkdown, runStockProbe, validateStockAdbKey,
  type AdbState, type StockAdbSession, type StockHttpSession, type StockProbeDeps,
} from '../src/stock-probe';
import { StockProbeUsageError, checkHostAdb, parseFiemapResponse, stockProbeCli } from '../src/stock-probe-cli';
import { DRY_USERDATA } from '../src/dry';

const KEY = fakeAdbKey(11, 'probe@host');
const FAKE_CERT_HEX = '3082'.padEnd(128, 'ab');
const FAKE_APK = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00]);
const FAKE_SO = new Uint8Array([0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01]);

let work: string;
beforeEach(() => { work = mkdtempSync(join(tmpdir(), 'gw-stock-probe-test-')); });
afterEach(() => rmSync(work, { recursive: true, force: true }));

function makeFakeDeps(o: {
  product?: string;
  adbAfterFlash?: AdbState;
  preFlashAdb?: AdbState;
  confirm?: (q: string) => boolean;
  stockVendorBytes?: Uint8Array;
  enableHttpFallback?: boolean;
  probeHostAnswer?: string;
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
    ['/proc/version', new TextEncoder().encode('Linux version 3.10.49-perf-gf46dad5260f\n')],
    ['/proc/modules', new TextEncoder().encode('wlan 4312975 0 - Live 0x0\n')],
    ['/proc/meminfo', new TextEncoder().encode('MemTotal: 924000 kB\nMemFree: 200000 kB\n')],
    ['/proc/partitions', new TextEncoder().encode('179 19 32768 mmcblk0p19\n')],
    ['/sepolicy', new TextEncoder().encode('stock-sepolicy')],
    ['/file_contexts', new TextEncoder().encode('/data/property(/.*)? u:object_r:property_data_file:s0\n')],
    ['/system/build.prop', new TextEncoder().encode('ro.build.fingerprint=mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR.S22Q401/2:user/test-keys\n')],
    ['/system/etc/init.qcom.post_boot.sh', new TextEncoder().encode('#!/system/bin/sh\n# stock\n')],
    ['/system/etc/mixer_paths_mtp.xml', new TextEncoder().encode('<mixer/>\n')],
    ['/system/etc/firmware/a300_pfp.fw', matchData],
    ['/system/etc/firmware/a300_pm4.fw', diffData],
  ]);

  const rawProps = [
    '[ro.serialno]: [SECRET_SERIAL_999]',
    '[ro.build.fingerprint]: [mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys]',
    '[ro.bootimage.build.fingerprint]: [mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys]',
    '[ro.board.platform]: [msm8916]',
    '[ro.adb.secure]: [1]',
    '[init.svc.adbd]: [running]',
    '[init.svc.thermal-engine]: [running]',
  ].join('\n') + '\n';

  const session: StockAdbSession = {
    async shell(cmd: string) {
      if (cmd === 'getprop sys.boot_completed') return { out: '1\n', code: 0 };
      if (cmd === 'getprop') return { out: rawProps, code: 0 };
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

  const httpSess: StockHttpSession = {
    async status() { return { ok: true, uid: 10099, pid: 2220, sdk: 23, fiemap: true, bootCompleted: '1' }; },
    async props() { return rawProps; },
    async dtPanel() { return '/proc/device-tree/soc/panel: qcom,mdss-dsi-panel-name=lvds-1080p\n'; },
    async list(path: string) {
      if (path === '/sys/class/backlight') return ['d\tr\t0\t/sys/class/backlight/lcd-backlight'];
      if (path === '/system/etc') return ['f\tr\t9\t/system/etc/mixer_paths_mtp.xml'];
      return [];
    },
    async readFile(path: string) {
      const data = files.get(path);
      if (data) return { status: 'ok', data };
      if (path.includes('missing') || path.endsWith('adsprpc.ko') || path.endsWith('/su')) return { status: 'missing' };
      return { status: 'denied' };
    },
    async fiemap(path: string) {
      if (path === '/system/etc/init.qcom.post_boot.sh') {
        return { path, size: 80502, ino: 631, extents: [{ logical: 0, physical: 137732096, block: 33626, length: 81920, blocks: 20, flags: 1 }] };
      }
      return { path, size: 7539, ino: 16, extents: [{ logical: 0, physical: 19640320, block: 4795, length: 8192, blocks: 2, flags: 1 }] };
    },
  };

  const deps: StockProbeDeps = {
    log: (l) => lines.push(l),
    confirm: async (q) => (o.confirm ? o.confirm(q) : true),
    promptText: async () => o.probeHostAnswer ?? '192.0.2.50',
    sleep: async () => {},
    now: () => new Date('2026-11-22T08:00:00.000Z'),
    ...(o.enableHttpFallback ? {
      loadProbeApp: async () => ({ apk: FAKE_APK, certHex: FAKE_CERT_HEX, fiemapLib: FAKE_SO }),
      connectHttp: () => httpSess,
    } : {}),
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
        serial: 'fake-mirror-1',
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
  it('writes e2fsck-clean ext4 with adb_keys, exact-byte property files, Wi-Fi PSK, /data/app probe APK + libfiemap.so + 1-entry packages.xml', async () => {
    const blocks = DRY_USERDATA / 4096;
    const disk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
    const { plantedPaths } = await buildStockProbeUserdata(disk, {
      adbKey: KEY,
      usbConfig: 'adb',
      tcpPort: 5555,
      wifi: { ssid: 'TestNet', password: 'super-secret-passphrase' },
      probeApp: { apk: FAKE_APK, certHex: FAKE_CERT_HEX, fiemapLib: FAKE_SO },
      time: 1_700_000_000,
    });
    expect(plantedPaths).toEqual([
      '/data/misc/adb/adb_keys',
      '/data/misc/wifi/wpa_supplicant.conf',
      '/data/property/persist.sys.usb.config',
      '/data/property/persist.adb.tcp.port',
      '/data/app/dev.glassworks.stockprobe-1/base.apk',
      '/data/app/dev.glassworks.stockprobe-1/lib/arm64/libfiemap.so',
      '/data/app/dev.glassworks.stockprobe-1/enable_wifi',
      '/data/system/packages.xml',
    ]);

    // Without wifi credentials, enable_wifi marker is omitted so stock SoftAP stays untouched.
    const noWifiDisk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
    const noWifiRes = await buildStockProbeUserdata(noWifiDisk, {
      adbKey: KEY,
      probeApp: { apk: FAKE_APK, certHex: FAKE_CERT_HEX },
      time: 1_700_000_000,
    });
    expect(noWifiRes.plantedPaths).not.toContain('/data/app/dev.glassworks.stockprobe-1/enable_wifi');

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

    // adb_keys + property files (exact bytes, NO trailing newline)
    expect(new TextDecoder().decode(debugfsCat(raw, '/misc/adb/adb_keys'))).toBe(`${KEY}\n`);
    expect(new TextDecoder().decode(debugfsCat(raw, '/property/persist.sys.usb.config'))).toBe('adb');
    expect(new TextDecoder().decode(debugfsCat(raw, '/property/persist.adb.tcp.port'))).toBe('5555');
    expect(debugfs(raw, 'stat /property')).toMatch(/Type:\s+directory\s+Mode:\s+0700/);
    expect(debugfs(raw, 'stat /property/persist.sys.usb.config')).toMatch(/Type:\s+regular\s+Mode:\s+0600/);
    expect(debugfs(raw, 'ea_list /property/persist.sys.usb.config')).toContain('u:object_r:property_data_file:s0');

    // Wi-Fi: PBKDF2 PSK only, never plain password
    const wpa = new TextDecoder().decode(debugfsCat(raw, '/misc/wifi/wpa_supplicant.conf'));
    expect(wpa).toMatch(/\tpsk=[0-9a-f]{64}\n/);
    expect(wpa).not.toContain('super-secret-passphrase');

    // Probe APK + optional libfiemap.so + 1-entry packages.xml
    expect(debugfsCat(raw, '/app/dev.glassworks.stockprobe-1/base.apk')).toEqual(FAKE_APK);
    expect(debugfsCat(raw, '/app/dev.glassworks.stockprobe-1/lib/arm64/libfiemap.so')).toEqual(FAKE_SO);
    expect(debugfs(raw, 'ea_list /app/dev.glassworks.stockprobe-1/base.apk')).toContain('u:object_r:apk_data_file:s0');
    expect(debugfs(raw, 'ea_list /app/dev.glassworks.stockprobe-1/lib/arm64/libfiemap.so')).toContain('u:object_r:apk_data_file:s0');

    const xml = new TextDecoder().decode(debugfsCat(raw, '/system/packages.xml'));
    expect(xml).toContain('<version sdkVersion="23" databaseVersion="3" fingerprint="placeholder" />');
    expect(xml).toContain('volumeUuid="primary_physical"');
    expect(xml).toContain('<item name="android.permission.RECEIVE_BOOT_COMPLETED" package="android" />');
    expect(xml).toContain('<item name="android.permission.CHANGE_WIFI_STATE" package="android" />');
    // BLE GATT server (BleProbeServer) needs BLUETOOTH + BLUETOOTH_ADMIN declared and granted.
    expect(xml).toContain('<item name="android.permission.BLUETOOTH" package="android" />');
    expect(xml).toContain('<item name="android.permission.BLUETOOTH_ADMIN" package="android" />');
    expect(xml).toContain('<item name="android.permission.BLUETOOTH" granted="true" flags="0" />');
    expect(xml).toContain('<item name="android.permission.BLUETOOTH_ADMIN" granted="true" flags="0" />');
    // No stageSecret: no app data dir planted.
    expect(plantedPaths.some((p) => p.includes('stage.secret'))).toBe(false);
    expect(xml).toContain('primaryCpuAbi="arm64-v8a"');
    expect(xml).toContain(`key="${FAKE_CERT_HEX}"`);
    expect(debugfs(raw, 'stat /system/packages.xml')).toMatch(/Type:\s+regular\s+Mode:\s+0660/);
    expect(debugfs(raw, 'ea_list /system/packages.xml')).toContain('u:object_r:system_data_file:s0');
  }, 30_000);

  it('plants a 32-byte stageSecret at /data/<pkg>/files/stage.secret (0600, app uid, app_data_file) and rejects wrong lengths', async () => {
    const blocks = DRY_USERDATA / 4096;
    const secret = new Uint8Array(32).map((_, i) => (i * 37 + 5) & 0xff);
    const base = { adbKey: KEY, probeApp: { apk: FAKE_APK, certHex: FAKE_CERT_HEX }, time: 1_700_000_000 };

    for (const bad of [new Uint8Array(0), new Uint8Array(31), new Uint8Array(33), new Uint8Array(64)]) {
      const d = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
      await expect(buildStockProbeUserdata(d, { ...base, stageSecret: bad })).rejects.toThrow(/stageSecret/);
    }
    const noApp = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
    await expect(buildStockProbeUserdata(noApp, { adbKey: KEY, stageSecret: secret })).rejects.toThrow(/probeApp/);

    const disk = new Disk(new MemoryStore(), 4096, blocks, { zeroed: true });
    const { plantedPaths } = await buildStockProbeUserdata(disk, { ...base, stageSecret: secret });
    expect(plantedPaths).toContain('/data/data/dev.glassworks.stockprobe/files/stage.secret');
    expect(plantedPaths.at(-1)).toBe('/data/data/dev.glassworks.stockprobe/files/stage.secret');

    if (!haveTools) return;
    const raw = join(work, 'userdata-secret.raw');
    await dump(disk, raw);
    const check = fsck(raw);
    expect(clean(check), check.out).toBe(true);

    const dir = '/data/dev.glassworks.stockprobe';
    expect(debugfsCat(raw, `${dir}/files/stage.secret`)).toEqual(secret);
    expect(debugfs(raw, `stat ${dir}/files/stage.secret`)).toMatch(/Type:\s+regular\s+Mode:\s+0600/);
    expect(debugfs(raw, `stat ${dir}/files/stage.secret`)).toMatch(/User:\s+10099\s+Group:\s+10099/);
    expect(debugfs(raw, `ea_list ${dir}/files/stage.secret`)).toContain('u:object_r:app_data_file:s0');
    expect(debugfs(raw, `stat ${dir}/files`)).toMatch(/Type:\s+directory\s+Mode:\s+0771/);
    expect(debugfs(raw, `stat ${dir}/files`)).toMatch(/User:\s+10099\s+Group:\s+10099/);
    expect(debugfs(raw, `ea_list ${dir}/files`)).toContain('u:object_r:app_data_file:s0');
    expect(debugfs(raw, `stat ${dir}`)).toMatch(/Type:\s+directory\s+Mode:\s+0751/);
    expect(debugfs(raw, `stat ${dir}`)).toMatch(/User:\s+10099\s+Group:\s+10099/);
    expect(debugfs(raw, `ea_list ${dir}`)).toContain('u:object_r:app_data_file:s0');
    expect(debugfs(raw, 'stat /data')).toMatch(/Type:\s+directory\s+Mode:\s+0771/);
    expect(debugfs(raw, 'stat /data')).toMatch(/User:\s+1000\s+Group:\s+1000/);
    expect(debugfs(raw, 'ea_list /data')).toContain('u:object_r:system_data_file:s0');
  }, 30_000);
});

describe('runStockProbe guided flow', () => {
  it('completes all 6 steps over ADB on pre-1.2x stock, flashes ONLY userdata, audits vendor files, and redacts secrets', async () => {
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
    expect(res.report.extraction?.transport).toBe('adb');
    expect(res.report.steps.every((s) => s.status === 'done')).toBe(true);
    expect(res.report.flash).toEqual({ userdataFlashed: true, systemTouched: false, rebooted: true });

    const md = readFileSync(res.reportPath, 'utf8');
    expect(md).toContain('- [x] **Step 1/6:');
    expect(md).toContain('- [x] **Step 6/6:');
    expect(md).toContain('| `gpu-display` | 4 | 1 | 1 | 1 | 1 |');
    expect(md).not.toContain('camera_only.so');
    expect(md).toContain(adbFingerprint(KEY));
    expect(md).not.toContain('SECRET_SERIAL_999');
    expect(md).not.toContain('aa:bb:cc:dd:ee:ff');
    expect(md).not.toContain('my-secret-wifi-password');
    expect(md).not.toContain('HomeWifi');
    expect(md).not.toContain(KEY);
    const pulledBlob = join(res.rawDir, 'system/etc/firmware/a300_pm4.fw');
    expect(existsSync(pulledBlob)).toBe(true);
    if (process.platform !== 'win32') {
      expect(statSync(res.rawDir).mode & 0o777).toBe(0o700);
      expect(statSync(res.sparsePath!).mode & 0o777).toBe(0o600);
      expect(statSync(pulledBlob).mode & 0o777).toBe(0o600);
    }
  });

  it('automatically transitions from 1.2x+ unauthorized ADB into the no-ADB HTTP probe app fallback and extracts stock info + FIEMAP', async () => {
    const outDir = join(work, 'fallback');
    const { deps, flashed, rebooted } = makeFakeDeps({
      adbAfterFlash: 'unauthorized',
      enableHttpFallback: true,
    });
    const res = await runStockProbe({
      outDir,
      adbKey: KEY,
      userdataBytesOverride: DRY_USERDATA,
      waitSeconds: 2,
    }, deps);

    expect(res.outcome).toBe('complete');
    // 1st flash: standard ADB userdata; 2nd flash: userdata with probe APK + 1-entry packages.xml (never system!)
    expect(flashed).toHaveLength(2);
    expect(flashed[1]).toContain('userdata-probe-app.sparse');
    expect(rebooted).toHaveLength(2);
    expect(res.report.adbWait?.outcome).toBe('unauthorized');
    expect(res.report.httpFallback?.triggered).toBe(true);
    expect(res.report.httpFallback?.reFlashedUserdataWithApp).toBe(true);
    expect(res.report.httpFallback?.connected).toBe(true);
    expect(res.report.extraction?.transport).toBe('http');
    expect(res.report.extraction?.fiemapResults).toHaveLength(2);

    const md = readFileSync(res.reportPath, 'utf8');
    expect(md).toContain('## Step 5b — No-ADB HTTP Probe App Fallback');
    expect(md).toContain('## Step 6 — Stock System Extraction (via `http`, redacted)');
    expect(md).toContain('### Physical Block Extents (`FS_IOC_FIEMAP`, read-only)');
    expect(md).toContain('block `33626` × `20`');
    expect(md).toContain('| `gpu-display` | 4 | 1 | 1 | 1 | 1 |');
    expect(md).not.toContain('SECRET_SERIAL_999');
  });

  it('plants the probe APK on the first flash with --with-probe-apk so 1.2x+ needs only ONE userdata flash', async () => {
    const outDir = join(work, 'single-flash');
    const { deps, flashed } = makeFakeDeps({
      adbAfterFlash: 'unauthorized',
      enableHttpFallback: true,
    });
    const res = await runStockProbe({
      outDir,
      adbKey: KEY,
      withProbeApk: true,
      probeHost: '192.0.2.50',
      userdataBytesOverride: DRY_USERDATA,
      waitSeconds: 2,
    }, deps);

    expect(res.outcome).toBe('complete');
    expect(flashed).toHaveLength(1);
    expect(res.report.httpFallback?.reFlashedUserdataWithApp).toBe(false);
    expect(res.report.extraction?.transport).toBe('http');
  });

  it('stops cleanly at Step 2 if product is not MSM8916 (e.g. NXP i.MX8), writing no files to the device', async () => {
    const outDir = join(work, 'nxp');
    const { deps, flashed } = makeFakeDeps({ product: 'IMX8MM' });
    const res = await runStockProbe({ outDir, adbKey: KEY, userdataBytesOverride: DRY_USERDATA }, deps);

    expect(res.outcome).toBe('blocked');
    expect(flashed).toHaveLength(0);
    expect(res.report.steps[1]!.status).toBe('blocked');
    expect(readFileSync(res.reportPath, 'utf8')).toContain('FAIL — STOP');
  });

  it('stops cleanly at Step 5 when stock adbd rejects adb_keys and fallback is declined', async () => {
    const outDir = join(work, 'unauth');
    const { deps } = makeFakeDeps({ adbAfterFlash: 'unauthorized' });
    const res = await runStockProbe({ outDir, adbKey: KEY, userdataBytesOverride: DRY_USERDATA, waitSeconds: 2 }, deps);

    expect(res.outcome).toBe('blocked');
    expect(res.report.steps[4]!.status).toBe('blocked');
    expect(res.report.steps[5]!.status).toBe('skipped');
    expect(res.report.adbWait?.outcome).toBe('unauthorized');
  });

  it('supports --read-only, --build-only, --extract-only, --http-only, and declining Step 4', async () => {
    const r1 = makeFakeDeps();
    const res1 = await runStockProbe({ outDir: join(work, 'ro'), adbKey: KEY, readOnly: true }, r1.deps);
    expect(res1.outcome).toBe('read-only-done');
    expect(r1.flashed).toHaveLength(0);

    const r2 = makeFakeDeps();
    const res2 = await runStockProbe({ outDir: join(work, 'bo'), adbKey: KEY, buildOnly: true, userdataBytesOverride: DRY_USERDATA }, r2.deps);
    expect(res2.outcome).toBe('build-only-done');
    expect(r2.flashed).toHaveLength(0);
    expect(existsSync(res2.sparsePath!)).toBe(true);

    const r3 = makeFakeDeps({ preFlashAdb: 'device' });
    const res3 = await runStockProbe({ outDir: join(work, 'eo'), adbKey: KEY, extractOnly: true }, r3.deps);
    expect(res3.outcome).toBe('complete');
    expect(r3.flashed).toHaveLength(0);

    const r4 = makeFakeDeps({ enableHttpFallback: true });
    const res4 = await runStockProbe({ outDir: join(work, 'ho'), adbKey: KEY, httpOnly: true, probeHost: '192.0.2.50' }, r4.deps);
    expect(res4.outcome).toBe('complete');
    expect(r4.flashed).toHaveLength(0);
    expect(res4.report.extraction?.transport).toBe('http');

    const r5 = makeFakeDeps({ confirm: (q) => !q.includes('FLASH userdata') });
    const res5 = await runStockProbe({ outDir: join(work, 'decline'), adbKey: KEY, userdataBytesOverride: DRY_USERDATA }, r5.deps);
    expect(res5.outcome).toBe('stopped');
    expect(r5.flashed).toHaveLength(0);
  });

  it('parses option-c-vendor-files.tsv, validates packages.xml inputs, and rejects unsafe args', async () => {
    const tsv = readFileSync(join(__dirname, '../../../frames/lululemon-mirror/research/option-c-vendor-files.tsv'), 'utf8');
    expect(parseVendorTsv(tsv).length).toBeGreaterThan(400);
    expect(() => parseVendorTsv('gpu\t/system/bin/foo;rm -rf /\t10\t00\tsystem_file\tyes\tbad')).toThrow(/unsafe vendor path/);
    expect(() => parseVendorTsv('gpu\t/system/etc/../escape\t10\t00\tsystem_file\tyes')).toThrow(/unsafe vendor path/);
    expect(() => buildPackagesXml({ packageName: 'bad pkg', certHex: FAKE_CERT_HEX, userId: 10099, hasNativeArm64: false })).toThrow(/invalid packageName/);
    expect(() => buildPackagesXml({ packageName: 'dev.glassworks.stockprobe', certHex: 'not-hex', userId: 10099, hasNativeArm64: false })).toThrow(/invalid certHex/);

    const disk = new Disk(new MemoryStore(), 4096, DRY_USERDATA / 4096, { zeroed: true });
    await expect(buildStockProbeUserdata(disk, { adbKey: KEY, usbConfig: 'adb\n' })).rejects.toThrow(/invalid usbConfig/);
    await expect(buildStockProbeUserdata(disk, { adbKey: KEY, tcpPort: 80 })).rejects.toThrow(/invalid tcpPort/);
    expect(GLASS_PANES_KEY_FINGERPRINT).toMatch(/^([0-9A-F]{2}:){15}[0-9A-F]{2}$/);

    // Any --out inside the repo is rejected (including ..-prefixed repo children and symlinked parents).
    const repoRoot = join(__dirname, '../../..');
    await expect(stockProbeCli(['--dry-run', '--out', join(__dirname, 'inside-repo')])).rejects.toBeInstanceOf(StockProbeUsageError);
    await expect(stockProbeCli(['--dry-run', '--out', join(repoRoot, '..probe-output')])).rejects.toBeInstanceOf(StockProbeUsageError);
    await expect(stockProbeCli(['--dry-run', '--extract-only', '--out', join(__dirname, 'inside-repo')])).rejects.toBeInstanceOf(StockProbeUsageError);
    const repoLink = join(work, 'repo-symlink');
    symlinkSync(__dirname, repoLink);
    await expect(stockProbeCli(['--dry-run', '--out', join(repoLink, 'sub')])).rejects.toBeInstanceOf(StockProbeUsageError);
  });

  it('isolates sparse images and checkpoint reports per run, persists flash before reboot failure, and enforces shared deadlines', async () => {
    // 1. Two buildOnly runs sharing --out get distinct private per-run sparse image AND authoritative report paths.
    const sharedOut = join(work, 'shared');
    const b1 = await runStockProbe({ outDir: sharedOut, adbKey: KEY, usbConfig: 'adb', buildOnly: true, userdataBytesOverride: DRY_USERDATA }, makeFakeDeps().deps);
    const b2 = await runStockProbe({ outDir: sharedOut, adbKey: KEY, usbConfig: 'diag,adb', buildOnly: true, userdataBytesOverride: DRY_USERDATA }, makeFakeDeps().deps);
    expect(b1.sparsePath).not.toBe(b2.sparsePath);
    expect(b1.reportPath).not.toBe(b2.reportPath);
    expect(existsSync(b1.sparsePath!)).toBe(true);
    expect(existsSync(b2.sparsePath!)).toBe(true);
    expect(readFileSync(b1.reportPath, 'utf8')).toContain('| Planned `persist.sys.usb.config` | `adb` |');
    expect(readFileSync(b2.reportPath, 'utf8')).toContain('| Planned `persist.sys.usb.config` | `diag,adb` |');

    // 2. If reboot fails after flashUserdata succeeds, report.md still records userdataFlashed=true.
    const rebootFailOut = join(work, 'reboot-fail');
    const base = makeFakeDeps();
    const origFb = base.deps.fastboot;
    base.deps.fastboot = async () => {
      const f = await origFb();
      return { ...f, async reboot() { throw new Error('USB cable bumped during reboot'); } };
    };
    await expect(runStockProbe({ outDir: rebootFailOut, adbKey: KEY, userdataBytesOverride: DRY_USERDATA }, base.deps)).rejects.toThrow(/USB cable bumped/);
    const rebootMd = readFileSync(join(rebootFailOut, 'report.md'), 'utf8');
    expect(rebootMd).toContain('**`userdata` flashed:** yes');
    expect(rebootMd).toContain('Reboot sent:** no');
    expect(rebootMd).toContain('USB cable bumped during reboot');

    // 3. --wait-seconds caps elapsed wall-clock time, subprocess timeoutMs, and sleep duration.
    let clockMs = 1_000_000;
    const seenTimeouts: number[] = [];
    const seenSleeps: number[] = [];
    const slow = makeFakeDeps({ adbAfterFlash: 'not-seen' });
    slow.deps.monotonicMs = () => clockMs;
    slow.deps.sleep = async (ms) => { seenSleeps.push(ms); clockMs += ms; };
    const origCheck = slow.deps.checkAdb;
    slow.deps.checkAdb = async (tcp, opts) => {
      if (!opts?.preflight && opts?.timeoutMs !== undefined) {
        seenTimeouts.push(opts.timeoutMs);
        clockMs += 6000;
      }
      return origCheck(tcp, opts);
    };
    const resWait = await runStockProbe({ outDir: join(work, 'deadline'), adbKey: KEY, userdataBytesOverride: DRY_USERDATA, waitSeconds: 10 }, slow.deps);
    expect(resWait.outcome).toBe('blocked');
    expect(seenTimeouts).toEqual([10_000, 2000]);
    expect(seenSleeps).toEqual([2000]);

    // 4a. checkHostAdb returns an already-authorized USB session before touching optional --tcp-host.
    const usbCalls: string[] = [];
    const usbRes = await checkHostAdb({
      adbBin: 'adb',
      tcpTarget: '192.0.2.10:5555',
      activePin: 'MIRROR123',
      timeoutMs: 2000,
      run: async (_bin, args) => {
        usbCalls.push(args[0]!);
        return { stdout: 'List of devices attached\nMIRROR123\tdevice\n', stderr: '' };
      },
    });
    expect(usbCalls).toEqual(['devices']);
    expect(usbRes.usb).toBe('device');
    expect(usbRes.session).toBeDefined();

    // 4b. When USB is absent, checkHostAdb applies one shared integer-ms deadline (even with fractional performance.now() ticks).
    const fractionalTicks = [1000, 1000.25, 1500.75, 2499.4, 2499.4];
    let tickIdx = 0;
    let devicesCalls = 0;
    const cmdBudgets: Array<{ cmd: string; timeoutMs: number }> = [];
    const pollRes = await checkHostAdb({
      adbBin: 'adb',
      tcpTarget: '192.0.2.10:5555',
      activePin: 'MIRROR123',
      timeoutMs: 1500,
      nowMs: () => fractionalTicks[Math.min(tickIdx++, fractionalTicks.length - 1)]!,
      run: async (_bin, args, timeoutMs) => {
        expect(Number.isInteger(timeoutMs) && timeoutMs >= 1).toBe(true);
        cmdBudgets.push({ cmd: args[0]!, timeoutMs });
        if (args[0] === 'devices') {
          devicesCalls += 1;
          return {
            stdout: devicesCalls === 1
              ? 'List of devices attached\n'
              : 'List of devices attached\n192.0.2.10:5555\tdevice\n',
            stderr: '',
          };
        }
        if (args[0] === 'connect') return { stdout: 'connected', stderr: '' };
        return { stdout: 'MIRROR123\n', stderr: '' };
      },
    });
    // Remaining falls from 1499.75 -> floor 1499, 999.25 -> floor 999, then 0.6ms (< 1ms) so no 0ms timeout is passed.
    expect(cmdBudgets).toEqual([
      { cmd: 'devices', timeoutMs: 1499 },
      { cmd: 'connect', timeoutMs: 999 },
    ]);
    expect(pollRes.tcp).toBe('not-seen');
    expect(pollRes.session).toBeUndefined();
  });

  it('aborts Step 6 on ADB disconnect, blocks extract-only fallback writes, rejects multi-line published keys, and sanitizes report notes', async () => {
    // 1. Extract-only never triggers fallback userdata writes even if userdataBytesOverride & loadProbeApp are set.
    const u = makeFakeDeps({ preFlashAdb: 'unauthorized', enableHttpFallback: true });
    const uRes = await runStockProbe({
      outDir: join(work, 'eo-unauth'),
      adbKey: KEY,
      extractOnly: true,
      userdataBytesOverride: DRY_USERDATA,
      waitSeconds: 1,
    }, u.deps);
    expect(uRes.outcome).toBe('blocked');
    expect(u.flashed).toHaveLength(0);
    expect(uRes.report.adbWait?.nextHint).toContain('no userdata was flashed');
    expect(uRes.report.adbWait?.nextHint).not.toContain('Stock adbd rejected');

    // 2. Rejects a forbidden project key unconditionally even when placed on line 2 after a personal key.
    const forbiddenKey = fakeAdbKey(99, 'published@project');
    const forbiddenFp = adbFingerprint(forbiddenKey);
    expect(forbiddenFp).not.toBe(adbFingerprint(KEY));
    expect(() => validateStockAdbKey(`${KEY}\n${forbiddenKey}\n`, forbiddenFp)).toThrow(/Glass Panes project key/);
    expect(validateStockAdbKey(KEY, forbiddenFp)).toBe(adbFingerprint(KEY));

    // 3. Step 6 fails (not complete) if ADB disconnects (code -1) during extraction.
    const disc = makeFakeDeps({ preFlashAdb: 'device' });
    const discOrig = disc.deps.checkAdb;
    disc.deps.checkAdb = async (tcp, opts) => {
      const r = await discOrig(tcp, opts);
      if (!r.session) return r;
      return {
        ...r,
        session: {
          ...r.session,
          async shell(cmd: string) {
            if (cmd === 'getprop') return { out: 'error: device offline', code: -1 };
            return r.session!.shell(cmd);
          },
        },
      };
    };
    await expect(runStockProbe({ outDir: join(work, 'adb-disc'), extractOnly: true }, disc.deps)).rejects.toThrow(/ADB transport lost/);

    // 4. Fallback Step 5b refuses to flash if the second fastboot session is a different device.
    const mis = makeFakeDeps({ adbAfterFlash: 'unauthorized', enableHttpFallback: true });
    let fbCalls = 0;
    const origMisFb = mis.deps.fastboot;
    mis.deps.fastboot = async () => {
      fbCalls += 1;
      const f = await origMisFb();
      return { ...f, serial: fbCalls === 1 ? 'mirror-A' : 'other-phone-B' };
    };
    const misRes = await runStockProbe({ outDir: join(work, 'fb-mismatch'), adbKey: KEY, userdataBytesOverride: DRY_USERDATA, waitSeconds: 1 }, mis.deps);
    expect(misRes.outcome).toBe('blocked');
    expect(mis.flashed).toHaveLength(1);

    // 5. Malformed FIEMAP payloads become per-file errors and Markdown-injecting notes/errors are escaped cleanly.
    expect(parseFiemapResponse('/system/build.prop', { extents: { bad: true } }).error).toMatch(/missing extents array/);
    expect(parseFiemapResponse('/system/build.prop', { error: 'libfiemap.so not bundled' })).toEqual({
      path: '/system/build.prop',
      error: 'libfiemap.so not bundled',
    });
    const fakeReport = renderReportMarkdown({
      startedAt: '2026-11-22T08:00:00.000Z',
      updatedAt: '2026-11-22T08:00:00.000Z',
      mode: 'http-only',
      dryRun: true,
      steps: [{ number: 2, title: 'Fastboot', status: 'blocked', note: 'product is "[click](https://evil.example)", not MSM8916' }],
      extraction: {
        transport: 'http',
        props: {},
        kernelConfigFlags: [],
        initServices: {},
        ramdiskFiles: [],
        backlightEntries: [],
        dtPanelLines: [],
        dmesgHighlights: [],
        keySystemFiles: [],
        extraConfigFiles: [],
        fiemapResults: [{ path: '/system/build.prop', error: 'bad `tick` | pipe\n## Injected' }],
        vendorSummary: [],
        vendorCameraIncluded: false,
        pulledVendorBlobs: 0,
      },
    });
    expect(fakeReport).toContain('— `product is "[click](https://evil.example)", not MSM8916`');
    expect(fakeReport).toContain('``bad `tick` \\| pipe ## Injected``');
    expect(fakeReport).not.toContain('\n## Injected');
  });

  it('runs the CLI --dry-run (full, --extract-only, and --http-only) end to end and rejects conflicting mode flags', async () => {
    const outDir = join(work, 'cli-dry');
    const logs: string[] = [];
    const code = await stockProbeCli(['--dry-run', '--out', outDir], (l) => logs.push(l));
    expect(code).toBe(0);
    expect(existsSync(join(outDir, 'report.md'))).toBe(true);
    expect(logs.join('\n')).toContain('--- copy-paste progress report below ---');

    const eoOut = join(work, 'cli-dry-eo');
    expect(await stockProbeCli(['--dry-run', '--extract-only', '--out', eoOut], () => {})).toBe(0);
    expect(readFileSync(join(eoOut, 'report.md'), 'utf8')).toContain('Step 6 — Stock System Extraction (via `adb`, redacted)');

    const hoOut = join(work, 'cli-dry-ho');
    expect(await stockProbeCli(['--dry-run', '--http-only', '--probe-host', '192.0.2.10', '--out', hoOut], () => {})).toBe(0);
    const hoMd = readFileSync(join(hoOut, 'report.md'), 'utf8');
    expect(hoMd).toContain('Step 6 — Stock System Extraction (via `http`, redacted)');
    expect(hoMd).toContain('block `33626` × `20`');

    // Bundled prebuilt probe APK artifacts exist and produce valid packages.xml + userdata.
    const prebuiltDir = join(__dirname, '../stock-probe-app/prebuilt');
    const prebuiltApk = new Uint8Array(readFileSync(join(prebuiltDir, 'stock-probe.apk')));
    const prebuiltCert = readFileSync(join(prebuiltDir, 'cert.hex'), 'utf8').trim();
    const prebuiltSo = new Uint8Array(readFileSync(join(prebuiltDir, 'libfiemap.so')));
    expect(prebuiltApk.slice(0, 4)).toEqual(new Uint8Array([0x50, 0x4b, 0x03, 0x04]));
    expect(prebuiltSo.slice(0, 4)).toEqual(new Uint8Array([0x7f, 0x45, 0x4c, 0x46]));
    expect(buildPackagesXml({
      packageName: 'dev.glassworks.stockprobe',
      certHex: prebuiltCert,
      userId: 10099,
      hasNativeArm64: true,
    })).toContain(`key="${prebuiltCert.toLowerCase()}"`);

    await expect(stockProbeCli(['--read-only', '--http-only'])).rejects.toBeInstanceOf(StockProbeUsageError);
  });
});
