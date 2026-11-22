// CLI runner for tools/mirror/stock-probe.ts: real fastboot/adb subprocess adapters, HTTP probe
// client for the 1.2x+ no-ADB fallback, --dry-run fake stock Mirror, and progress report output.
import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, realpathSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { gzipSync } from 'node:zlib';
import { USERDATA_BYTES } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey } from '@glassworks/lululemon-mirror-installer/fakes';
import { adbPath, readSerial } from './adb';
import { DRY_USERDATA } from './dry';
import {
  DEFAULT_PROBE_HTTP_PORT, parseVendorTsv, renderReportMarkdown, runStockProbe,
  type AdbState, type FiemapResult, type ProbeAppPayload, type StockAdbSession, type StockFastboot, type StockHttpSession, type StockProbeDeps,
} from './stock-probe';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const VENDOR_TSV = join(REPO, 'frames/lululemon-mirror/research/option-c-vendor-files.tsv');
const PROBE_APP_DIR = join(REPO, 'tools/mirror/stock-probe-app');

export const STOCK_PROBE_HELP = `usage: stock-probe.ts [options]

Guided 6-step probe for a stock lululemon Studio Mirror (MSM8916).
Updates <out>/report.md after EVERY step so you can report progress at any point.

NEVER touches system, boot, recovery, modem, persist, or aboot.
Step 4 writes ONLY userdata (wiping stock app data, leaving stock system untouched).
If Step 5 gets no ADB (1.2x+ unauthorized / not-seen), it automatically offers the no-ADB
fallback: planting the read-only HTTP probe APK in /data/app/ + a 1-entry packages.xml
(still userdata-only!) and extracting Step 6 + FS_IOC_FIEMAP physical block offsets over HTTP.

Modes (default: full 6-step guided run):
  --read-only               Run Steps 1-2 only (ADB key check + fastboot read-only info)
  --build-only              Run Steps 1-3 only (also build sparse userdata locally; no flash)
  --extract-only            Run Step 1 preflight, skip Steps 2-4, and run Steps 5-6 over ADB
  --http-only               Run Step 1 preflight, skip Steps 2-4, and connect to HTTP probe app (--probe-host)
  --dry-run                 Run all 6 steps on a fake stock Mirror; no device is touched

Options:
  --out <dir>               Output folder for report.md and raw/ (default: <tmpdir>/mirror-stock-probe-<ts>)
  --adb-key <file>          ADB public key to plant in userdata (default: ~/.android/adbkey.pub)
  --usb-config <value>      persist.sys.usb.config value (default: adb; alt: diag,serial_smd,rmnet_bam,adb)
  --tcp-port <port>         persist.adb.tcp.port value (default: 5555)
  --no-tcp                  Do not plant persist.adb.tcp.port
  --tcp-host <ip>           Also try \`adb connect <ip>:<tcp-port>\` in Step 5 (for Ethernet/Wi-Fi adb)
  --with-probe-apk          Plant /data/app probe APK + 1-entry packages.xml in Step 3 right away (1 flash for 1.2x+)
  --probe-apk <file>        Path to stock-probe.apk (default: auto-build tools/mirror/stock-probe-app/out/)
  --probe-cert-hex <file>   Path to hex DER cert for --probe-apk (default: cert.hex beside the APK)
  --probe-fiemap-so <file>  Optional arm64 libfiemap.so (default: libfiemap.so beside the APK if present)
  --probe-host <ip>         Mirror LAN IP for the no-ADB HTTP probe app (port ${DEFAULT_PROBE_HTTP_PORT})
  --probe-http-port <port>  Client port to contact for HTTP probe (default: ${DEFAULT_PROBE_HTTP_PORT}; bundled APK listens on ${DEFAULT_PROBE_HTTP_PORT}, so another port needs a matching listener/forward)
  --wifi-ssid <name> --wifi-pass-file <file>
                            Pre-save Wi-Fi (PBKDF2 PSK only) in userdata
  --no-fill                 Write zero blocks as RAW instead of FILL 0 in the sparse image
  --include-camera          Include the 321 camera vendor files in Step 6's Option C audit
  --pull-system-blobs       Also save readable stock vendor files locally under <out>/raw/system/
  --serial <id>             Target a specific fastboot/adb device serial (redacted in logs/report)
  --serial-file <file>      Read target serial from a file (e.g. /tmp/mirror-serial)
  --wait-seconds <n>        Seconds to wait for stock ADB / HTTP after reboot (default: 180)
  --yes                     Answer yes to all step prompts
  --help                    Show this help`;

export class StockProbeUsageError extends Error {}
const fail = (m: string): never => { throw new StockProbeUsageError(m); };

function fastbootPath(): string {
  const p = process.env.FASTBOOT ?? join(homedir(), 'Library/Android/sdk/platform-tools/fastboot');
  return existsSync(p) ? p : 'fastboot';
}

function redact(text: string, serial?: string): string {
  return serial ? text.split(serial).join('<serial>') : text;
}

function execText(bin: string, args: string[], timeoutMs = 120_000, serialToRedact?: string): Promise<{ stdout: string; stderr: string }> {
  return new Promise((res, rej) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 64 << 20, encoding: 'utf8' }, (err, stdout, stderr) => {
      if (err) {
        const msg = `${bin} ${args.join(' ')}: ${String(stderr || stdout || err.message).trim()}`;
        rej(new Error(redact(msg, serialToRedact)));
      } else {
        res({ stdout, stderr });
      }
    });
  });
}

function execBuffer(bin: string, args: string[], timeoutMs = 120_000, serialToRedact?: string): Promise<Buffer> {
  return new Promise((res, rej) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 128 << 20, encoding: 'buffer' }, (err, stdout, stderr) => {
      if (err) {
        const msg = `${bin} ${args.join(' ')}: ${String(stderr || stdout || err.message).trim()}`;
        rej(new Error(redact(msg, serialToRedact)));
      } else {
        res(stdout);
      }
    });
  });
}

function parseFastbootDevices(out: string): string[] {
  return out.split(/\r?\n/)
    .map((l) => l.trim().split(/\s+/))
    .filter(([id, state]) => !!id && state === 'fastboot')
    .map(([id]) => id!);
}

function parseAdbDevices(out: string): Map<string, AdbState> {
  const map = new Map<string, AdbState>();
  for (const line of out.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('List of devices') || trimmed.startsWith('*')) continue;
    const [id, st] = trimmed.split(/\s+/);
    if (!id || !st) continue;
    if (st === 'device' || st === 'unauthorized' || st === 'offline') map.set(id, st);
  }
  return map;
}

function createHostAdbSession(target: string, bin: string, serialToRedact?: string): StockAdbSession {
  const RC = '__GW_SP_RC=';
  const targetArgs = ['-s', target];
  return {
    async shell(cmd: string) {
      try {
        const { stdout } = await execText(bin, [...targetArgs, 'shell', `${cmd}; echo "${RC}$?"`], 120_000, serialToRedact);
        const i = stdout.lastIndexOf(RC);
        const rawOut = i < 0 ? stdout : stdout.slice(0, i);
        const code = i < 0 ? -1 : Number.parseInt(stdout.slice(i + RC.length).trim(), 10);
        return { out: redact(rawOut.replace(/\r\n/g, '\n'), serialToRedact), code: Number.isNaN(code) ? -1 : code };
      } catch (e) {
        return { out: redact((e as Error).message, serialToRedact), code: -1 };
      }
    },
    async readFile(path: string) {
      const check = await this.shell(`cat '${path}' >/dev/null 2>&1`);
      if (check.code === -1) throw new Error(`ADB transport lost reading ${path}: ${check.out}`);
      if (check.code !== 0) return null;
      const buf = await execBuffer(bin, [...targetArgs, 'exec-out', 'cat', path], 120_000, serialToRedact);
      return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    },
  };
}

function createHostFastboot(serial: string, bin: string): StockFastboot {
  const sArgs = ['-s', serial];
  const runFb = (opArgs: string[], timeoutMs: number) =>
    execText(bin, [...sArgs, ...opArgs], timeoutMs, serial);
  return {
    serial,
    async oemDeviceInfo() {
      const { stdout, stderr } = await runFb(['oem', 'device-info'], 15_000);
      return `${stdout}\n${stderr}`
        .split(/\r?\n/)
        .map((l) => redact(l.replace(/^\(bootloader\)\s*/i, '').trim(), serial))
        .filter((l) => l && !/^OKAY|^Finished\./i.test(l));
    },
    async getvar(name: string) {
      try {
        const { stdout, stderr } = await runFb(['getvar', name], 15_000);
        const combined = `${stdout}\n${stderr}`;
        const prefix = `${name}:`;
        for (const raw of combined.split(/\r?\n/)) {
          const idx = raw.indexOf(prefix);
          if (idx >= 0) return redact(raw.slice(idx + prefix.length).trim(), serial);
        }
        return undefined;
      } catch {
        return undefined;
      }
    },
    async flashUserdata(sparsePath: string) {
      await runFb(['flash', 'userdata', sparsePath], 180_000);
    },
    async reboot() {
      await runFb(['reboot'], 30_000);
    },
  };
}

const isFiniteNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

/** Validates the JSON payload returned by `/fiemap` before it reaches report rendering. */
export function parseFiemapResponse(path: string, raw: unknown): FiemapResult {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return { path, error: 'invalid FIEMAP response (expected JSON object)' };
  }
  const obj = raw as Record<string, unknown>;
  if (typeof obj.error === 'string') {
    return { path, error: obj.error };
  }
  if (!Array.isArray(obj.extents)) {
    return { path, error: 'invalid FIEMAP response (missing extents array)' };
  }
  const extents: NonNullable<FiemapResult['extents']> = [];
  for (const item of obj.extents) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return { path, error: 'invalid FIEMAP response (malformed extent entry)' };
    }
    const e = item as Record<string, unknown>;
    if (
      !isFiniteNum(e.logical) || !isFiniteNum(e.physical) || !isFiniteNum(e.block)
      || !isFiniteNum(e.length) || !isFiniteNum(e.blocks) || !isFiniteNum(e.flags)
    ) {
      return { path, error: 'invalid FIEMAP response (non-numeric extent fields)' };
    }
    extents.push({
      logical: e.logical,
      physical: e.physical,
      block: e.block,
      length: e.length,
      blocks: e.blocks,
      flags: e.flags,
    });
  }
  if (obj.size !== undefined && !isFiniteNum(obj.size)) {
    return { path, error: 'invalid FIEMAP response (non-numeric size)' };
  }
  if (obj.ino !== undefined && !isFiniteNum(obj.ino)) {
    return { path, error: 'invalid FIEMAP response (non-numeric inode)' };
  }
  return {
    path,
    ...(obj.size !== undefined ? { size: obj.size } : {}),
    ...(obj.ino !== undefined ? { ino: obj.ino } : {}),
    extents,
  };
}

function createHostHttpSession(host: string, port: number): StockHttpSession {
  const base = `http://${host}:${port}`;
  const withTimeout = async <T>(path: string, timeoutMs: number, read: (r: Response) => Promise<T>): Promise<T> => {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const r = await fetch(`${base}${path}`, { signal: ctl.signal });
      return await read(r);
    } finally {
      clearTimeout(timer);
    }
  };
  return {
    async status(timeoutMs = 5000) {
      try {
        return await withTimeout('/status', timeoutMs, async (r) => {
          if (!r.ok) return null;
          const body = (await r.json()) as Record<string, unknown>;
          if (!body || body.ok !== true || !isFiniteNum(body.uid) || !isFiniteNum(body.pid) || !isFiniteNum(body.sdk) || typeof body.fiemap !== 'boolean') {
            return null;
          }
          return {
            ok: true,
            uid: body.uid,
            pid: body.pid,
            sdk: body.sdk,
            fiemap: body.fiemap,
            bootCompleted: typeof body.bootCompleted === 'string' ? body.bootCompleted : '',
          };
        });
      } catch {
        return null;
      }
    },
    async props() {
      return withTimeout('/props', 15_000, async (r) => {
        if (!r.ok) throw new Error(`HTTP probe /props failed with status ${r.status}`);
        return await r.text();
      });
    },
    async dtPanel() {
      return withTimeout('/dt-panel', 30_000, async (r) => {
        if (!r.ok) throw new Error(`HTTP probe /dt-panel failed with status ${r.status}`);
        return await r.text();
      });
    },
    async list(path: string) {
      return withTimeout(`/list?path=${encodeURIComponent(path)}`, 15_000, async (r) => {
        if (r.status === 404 || r.status === 403) return [];
        if (!r.ok) throw new Error(`HTTP probe /list(${path}) failed with status ${r.status}`);
        return (await r.text()).split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
      });
    },
    async readFile(path: string) {
      return withTimeout(`/file?path=${encodeURIComponent(path)}`, 60_000, async (r) => {
        if (r.status === 200) return { status: 'ok' as const, data: new Uint8Array(await r.arrayBuffer()) };
        if (r.status === 404) return { status: 'missing' as const };
        if (r.status === 403) return { status: 'denied' as const };
        throw new Error(`HTTP probe /file(${path}) returned unexpected status ${r.status}`);
      });
    },
    async fiemap(path: string): Promise<FiemapResult> {
      try {
        return await withTimeout(`/fiemap?path=${encodeURIComponent(path)}`, 15_000, async (r) => {
          const text = await r.text();
          let parsed: unknown;
          try {
            parsed = JSON.parse(text);
          } catch {
            return { path, error: `HTTP ${r.status}: non-JSON FIEMAP response` };
          }
          return parseFiemapResponse(path, parsed);
        });
      } catch (e) {
        return { path, error: (e as Error).message };
      }
    },
  };
}

async function loadOrBuildProbeApp(opts: {
  apkPath?: string;
  certHexPath?: string;
  fiemapPath?: string;
  log: (l: string) => void;
}): Promise<ProbeAppPayload | undefined> {
  const defaultOut = join(PROBE_APP_DIR, 'out');
  const apkPath = opts.apkPath ?? join(defaultOut, 'stock-probe.apk');
  const certPath = opts.certHexPath ?? join(dirname(apkPath), 'cert.hex');
  const fiemapPath = opts.fiemapPath ?? join(dirname(apkPath), 'libfiemap.so');

  if (!existsSync(apkPath) || !existsSync(certPath)) {
    const buildSh = join(PROBE_APP_DIR, 'build.sh');
    if (opts.apkPath || !existsSync(buildSh)) return undefined;
    opts.log('    building stock-probe-app (tools/mirror/stock-probe-app/build.sh)...');
    await execText(buildSh, [], 60_000);
  }
  if (!existsSync(apkPath) || !existsSync(certPath)) return undefined;
  return {
    apk: new Uint8Array(readFileSync(apkPath)),
    certHex: readFileSync(certPath, 'utf8').trim(),
    ...(existsSync(fiemapPath) ? { fiemapLib: new Uint8Array(readFileSync(fiemapPath)) } : {}),
  };
}

function makePrompters(yes: boolean) {
  let rl: ReturnType<typeof createInterface> | undefined;
  const getRl = () => {
    if (!rl) rl = createInterface({ input: process.stdin, output: process.stdout });
    return rl;
  };
  return {
    confirm: yes
      ? async () => true
      : async (q: string) => /^y(es)?$/i.test((await getRl().question(`    ${q} [y/N] `)).trim()),
    promptText: async (q: string) => (await getRl().question(`    ${q} `)).trim(),
    close: () => { rl?.close(); rl = undefined; },
  };
}

/** Realistic fake stock Mirror for --dry-run (touches no device). */
function createDryDeps(log: (l: string) => void, opts: { extractOnly?: boolean } = {}): StockProbeDeps {
  const fakeConfig = gzipSync(Buffer.from([
    'CONFIG_LOCALVERSION="-perf-gf46dad5260f"',
    'CONFIG_IKCONFIG_PROC=y',
    'CONFIG_MODULES=y',
    '# CONFIG_MODULE_SIG is not set',
    'CONFIG_MODVERSIONS=y',
    'CONFIG_KALLSYMS_ALL=y',
    '# CONFIG_PID_NS is not set',
    'CONFIG_MSM_SCM=y',
  ].join('\n')));

  const fakeFiles = new Map<string, Uint8Array>([
    ['/proc/config.gz', new Uint8Array(fakeConfig)],
    ['/proc/version', new TextEncoder().encode('Linux version 3.10.49-perf-gf46dad5260f (dry@run)\n')],
    ['/proc/modules', new TextEncoder().encode('wlan 4312975 0 - Live 0x0000000000000000 (O)\n')],
    ['/proc/meminfo', new TextEncoder().encode('MemTotal: 924000 kB\nMemFree: 210000 kB\nMemAvailable: 350000 kB\n')],
    ['/proc/partitions', new TextEncoder().encode('major minor #blocks name\n 179 19 32768 mmcblk0p19\n')],
    ['/sepolicy', new TextEncoder().encode('fake-stock-sepolicy')],
    ['/file_contexts', new TextEncoder().encode('/data/property(/.*)? u:object_r:property_data_file:s0\n')],
    ['/seapp_contexts', new TextEncoder().encode('user=system seinfo=platform domain=system_app\n')],
    ['/property_contexts', new TextEncoder().encode('persist.sys. u:object_r:system_prop:s0\n')],
    ['/service_contexts', new TextEncoder().encode('usb u:object_r:usb_service:s0\n')],
    ['/default.prop', new TextEncoder().encode('ro.secure=1\nro.adb.secure=1\npersist.sys.usb.config=none\n')],
    ['/system/build.prop', new TextEncoder().encode('ro.build.fingerprint=mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys\n')],
    ['/system/etc/wifi/wpa_supplicant.conf', new TextEncoder().encode('update_config=1\neapol_version=1\nap_scan=1\n')],
    ['/system/etc/init.qcom.post_boot.sh', new TextEncoder().encode('#!/system/bin/sh\n# stock post boot\n')],
    ['/system/etc/mixer_paths_mtp.xml', new TextEncoder().encode('<mixer/>\n')],
    ['/system/etc/thermal-engine.conf', new TextEncoder().encode('sampling 1000\n')],
    ['/system/lib/modules/pronto/pronto_wlan.ko', new Uint8Array([0x7f, 0x45, 0x4c, 0x46])],
    ['/system/framework/services.jar', new Uint8Array([0x50, 0x4b, 0x03, 0x04])],
    ['/system/etc/firmware/a300_pfp.fw', new TextEncoder().encode('dry-stock-fw')],
  ]);

  const fakeProps = [
    '[ro.build.fingerprint]: [mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys]',
    '[ro.bootimage.build.fingerprint]: [mirror/mirror/msm8916_64:6.0.1/IFC-6309-2.0-MIR/673:user/test-keys]',
    '[ro.build.version.release]: [6.0.1]',
    '[ro.build.version.sdk]: [23]',
    '[ro.build.type]: [user]',
    '[ro.build.tags]: [test-keys]',
    '[ro.board.platform]: [msm8916]',
    '[ro.product.model]: [MIRROR]',
    '[ro.crypto.state]: [unencrypted]',
    '[ro.adb.secure]: [1]',
    '[ro.secure]: [1]',
    '[ro.debuggable]: [0]',
    '[persist.sys.usb.config]: [adb]',
    '[persist.adb.tcp.port]: [5555]',
    '[init.svc.adbd]: [running]',
    '[init.svc.qcom-post-boot]: [stopped]',
    '[init.svc.thermal-engine]: [running]',
    '[init.svc.wcnss-service]: [running]',
  ].join('\n') + '\n';

  let flashed = false;
  const session: StockAdbSession = {
    async shell(cmd: string) {
      if (cmd === 'getprop sys.boot_completed') return { out: '1\n', code: 0 };
      if (cmd === 'getprop') return { out: fakeProps, code: 0 };
      if (cmd === 'getenforce') return { out: 'Enforcing\n', code: 0 };
      if (cmd === 'id') return { out: 'uid=2000(shell) gid=2000(shell) context=u:r:shell:s0\n', code: 0 };
      if (cmd === 'cat /proc/version') return { out: 'Linux version 3.10.49-perf-gf46dad5260f (dry@run)\n', code: 0 };
      if (cmd === 'cat /proc/modules') return { out: 'wlan 4312975 0 - Live 0x0000000000000000 (O)\n', code: 0 };
      if (cmd === 'head -3 /proc/meminfo') return { out: 'MemTotal: 924000 kB\nMemFree: 210000 kB\nMemAvailable: 350000 kB\n', code: 0 };
      if (cmd === 'cat /proc/partitions') return { out: 'major minor #blocks name\n 179 19 32768 mmcblk0p19\n', code: 0 };
      if (cmd === 'dmesg') return { out: '[ 2.60] init: cannot find \'/system/bin/install-recovery.sh\', disabling \'flash_recovery\'\n[ 3.10] init: Starting service \'qcom-post-boot\'...\n', code: 0 };
      if (cmd === 'dumpsys display') return { out: 'DisplayDeviceInfo{"Built-in Screen": 1920 x 1080}\n', code: 0 };
      if (cmd === 'dumpsys window windows') return { out: '  mRotation=3 mLastOrientation=1\n', code: 0 };
      if (cmd === 'dumpsys audio') return { out: 'AudioService dry-run\n', code: 0 };
      if (cmd.startsWith('ls -la /sys/class/backlight')) return { out: '/sys/class/backlight:\nlrwxrwxrwx root root lcd-backlight\n', code: 0 };
      if (cmd.startsWith('find /proc/device-tree')) return { out: '/proc/device-tree/soc/qcom,mdss_dsi_panel: qcom,mdss-dsi-panel-name=dry lvds 1080p panel\n', code: 0 };
      if (cmd.startsWith('ls /system/etc/mixer_paths')) return { out: '/system/etc/mixer_paths_mtp.xml\n/system/etc/thermal-engine.conf\n', code: 0 };
      if (cmd.startsWith('ls -ld ')) {
        const m = /'([^']+)'/.exec(cmd);
        const p = m?.[1] ?? '';
        if (p === '/system/lib/modules/adsprpc.ko' || p === '/system/xbin/su') {
          return { out: `ls: ${p}: No such file or directory\n`, code: 1 };
        }
        return { out: `ls: ${p}: Permission denied\n`, code: 1 };
      }
      return { out: '', code: 0 };
    },
    async readFile(path: string) {
      return fakeFiles.get(path) ?? null;
    },
  };

  const httpSession: StockHttpSession = {
    async status() { return { ok: true, uid: 10099, pid: 1234, sdk: 23, fiemap: true, bootCompleted: '1' }; },
    async props() { return fakeProps; },
    async dtPanel() { return '/proc/device-tree/soc/qcom,mdss_dsi_panel: qcom,mdss-dsi-panel-name=dry lvds 1080p panel\n'; },
    async list(path: string) {
      if (path === '/sys/class/backlight') return ['d\tr\t0\t/sys/class/backlight/lcd-backlight'];
      if (path === '/system/etc') {
        return [
          'f\tr\t9\t/system/etc/mixer_paths_mtp.xml',
          'f\tr\t14\t/system/etc/thermal-engine.conf',
        ];
      }
      return [];
    },
    async readFile(path: string) {
      const data = fakeFiles.get(path);
      if (data) return { status: 'ok', data };
      if (path === '/system/lib/modules/adsprpc.ko' || path === '/system/xbin/su') return { status: 'missing' };
      return { status: 'denied' };
    },
    async fiemap(path: string) {
      if (path === '/system/etc/init.qcom.post_boot.sh') {
        return { path, size: 80502, ino: 631, extents: [{ logical: 0, physical: 137732096, block: 33626, length: 81920, blocks: 20, flags: 1 }] };
      }
      return { path, size: 7539, ino: 16, extents: [{ logical: 0, physical: 19640320, block: 4795, length: 8192, blocks: 2, flags: 1 }] };
    },
  };

  return {
    log,
    confirm: async () => true,
    promptText: async () => '192.0.2.10',
    sleep: async () => {},
    loadProbeApp: async () => ({
      apk: new Uint8Array([0x50, 0x4b, 0x03, 0x04]),
      certHex: 'ab'.repeat(64),
      fiemapLib: new Uint8Array([0x7f, 0x45, 0x4c, 0x46]),
    }),
    connectHttp: () => httpSession,
    vendorEntries: [
      { part: 'gpu-display', path: '/system/etc/firmware/a300_pfp.fw', bytes: 1156, sha256: '0000', label: 'system_file', shell: 'yes' },
      { part: 'gpu-display', path: '/system/bin/mm-pp-daemon', bytes: 141704, sha256: '1111', label: 'mm-pp-daemon_exec', shell: 'no' },
    ],
    async checkAdb() {
      return (flashed || opts.extractOnly) ? { usb: 'device', session } : { usb: 'not-seen' };
    },
    async fastboot() {
      return {
        serial: 'dry-mirror',
        async oemDeviceInfo() { return ['Device tampered: false', 'Device unlocked: false']; },
        async getvar(name: string) {
          const map: Record<string, string> = {
            product: 'MSM8916',
            variant: 'SBC eMMC',
            secure: 'yes',
            'version-bootloader': 'dry-lk',
            'max-download-size': '0x10000000',
            'partition-type:system': 'ext4',
            'partition-size:system': '0x4cccd000',
            'partition-type:userdata': 'ext4',
            'partition-size:userdata': `0x${USERDATA_BYTES.toString(16)}`,
            'partition-size:boot': '0x02000000',
            'partition-size:modem': '0x04000000',
            'partition-size:persist': '0x02000000',
          };
          return map[name];
        },
        async flashUserdata() { flashed = true; },
        async reboot() {},
      };
    },
  };
}

function timestampSlug(d = new Date()): string {
  return d.toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

/** Resolves existing ancestor symlinks so symlinked paths into the repo are caught too. */
function realResolve(target: string): string {
  let cur = resolve(target);
  const tail: string[] = [];
  while (!existsSync(cur)) {
    const parent = dirname(cur);
    if (parent === cur) break;
    tail.unshift(cur.slice(parent.length + (parent.endsWith('/') ? 0 : 1)));
    cur = parent;
  }
  const base = existsSync(cur) ? realpathSync(cur) : cur;
  return tail.length ? join(base, ...tail) : base;
}

function isInsideRepo(dir: string): boolean {
  const realRepo = existsSync(REPO) ? realpathSync(REPO) : resolve(REPO);
  const realTarget = realResolve(dir);
  const rel = relative(realRepo, realTarget);
  const isOutside = rel === '..' || rel.startsWith(`..${sep}`) || isAbsolute(rel);
  return !isOutside;
}

/**
 * Runs one ADB poll (`adb connect`, `adb devices`, and optional pinned-serial TCP identity check)
 * under a single shared deadline so sequential subprocesses cannot exceed the caller's budget.
 */
export async function checkHostAdb(o: {
  adbBin: string;
  tcpTarget?: string;
  activePin?: string;
  timeoutMs?: number;
  nowMs?: () => number;
  run?: (bin: string, args: string[], timeoutMs: number, serialToRedact?: string) => Promise<{ stdout: string; stderr: string }>;
  makeSession?: (target: string, serialToRedact?: string) => StockAdbSession;
}): Promise<{ usb: AdbState; tcp?: AdbState; session?: StockAdbSession }> {
  const nowMs = o.nowMs ?? (() => Date.now());
  const run = o.run ?? execText;
  const makeSession = o.makeSession ?? ((target, serialToRedact) => createHostAdbSession(target, o.adbBin, serialToRedact));
  const budgetMs = Math.max(0, Math.min(10_000, o.timeoutMs ?? 10_000));
  const deadline = nowMs() + budgetMs;
  const remaining = () => deadline - nowMs();

  if (o.tcpTarget) {
    const rem = remaining();
    if (rem <= 0) return { usb: 'not-seen', tcp: 'not-seen' };
    await run(o.adbBin, ['connect', o.tcpTarget], rem, o.activePin).catch(() => {});
  }

  const devRem = remaining();
  if (devRem <= 0) {
    return { usb: 'not-seen', ...(o.tcpTarget ? { tcp: 'not-seen' as AdbState } : {}) };
  }
  const { stdout } = await run(o.adbBin, ['devices'], devRem, o.activePin).catch(() => ({ stdout: '' }));
  const map = parseAdbDevices(stdout);

  let usb: AdbState = 'not-seen';
  let usbTarget: string | undefined;
  if (o.activePin) {
    usb = map.get(o.activePin) ?? 'not-seen';
    if (usb === 'device') usbTarget = o.activePin;
  } else {
    const usbEntries = [...map.entries()].filter(([id]) => !id.includes(':'));
    if (usbEntries.length > 1) {
      throw new Error(`${usbEntries.length} USB adb devices are connected: pass --serial-file or unplug other devices`);
    }
    if (usbEntries.length === 1) {
      usb = usbEntries[0]![1];
      if (usb === 'device') {
        // Do not mutate pinnedSerial from an unidentified preflight ADB device:
        // fastboot discovery (or an explicit --serial/--serial-file) owns the pin.
        usbTarget = usbEntries[0]![0];
      }
    }
  }

  let tcp: AdbState | undefined;
  let verifiedTcpTarget: string | undefined;
  if (o.tcpTarget) {
    tcp = map.get(o.tcpTarget) ?? 'not-seen';
    if (tcp === 'device') {
      if (o.activePin) {
        // When a serial is pinned, verify that the TCP endpoint belongs to that exact device.
        const verifyRem = remaining();
        if (verifyRem <= 0) {
          tcp = 'not-seen';
        } else {
          const serialCheck = await run(
            o.adbBin,
            ['-s', o.tcpTarget, 'shell', 'getprop ro.serialno'],
            Math.min(5000, verifyRem),
            o.activePin,
          ).catch(() => null);
          if (serialCheck && serialCheck.stdout.trim() === o.activePin) {
            verifiedTcpTarget = o.tcpTarget;
          } else {
            tcp = 'not-seen';
          }
        }
      } else {
        verifiedTcpTarget = o.tcpTarget;
      }
    }
  }

  const activeTarget = usb === 'device' ? usbTarget : verifiedTcpTarget;
  const serialToRedact = o.activePin ?? (usb === 'device' ? usbTarget : undefined);
  const session = activeTarget ? makeSession(activeTarget, serialToRedact) : undefined;
  return { usb, ...(tcp !== undefined ? { tcp } : {}), ...(session ? { session } : {}) };
}

export async function stockProbeCli(argv: string[], log: (l: string) => void = (l) => console.log(l)): Promise<number> {
  const a = parseArgs({
    args: argv,
    options: {
      help: { type: 'boolean' },
      'dry-run': { type: 'boolean' },
      'read-only': { type: 'boolean' },
      'build-only': { type: 'boolean' },
      'extract-only': { type: 'boolean' },
      'http-only': { type: 'boolean' },
      yes: { type: 'boolean' },
      out: { type: 'string' },
      'adb-key': { type: 'string' },
      'usb-config': { type: 'string' },
      'tcp-port': { type: 'string' },
      'no-tcp': { type: 'boolean' },
      'tcp-host': { type: 'string' },
      'with-probe-apk': { type: 'boolean' },
      'probe-apk': { type: 'string' },
      'probe-cert-hex': { type: 'string' },
      'probe-fiemap-so': { type: 'string' },
      'probe-host': { type: 'string' },
      'probe-http-port': { type: 'string' },
      'wifi-ssid': { type: 'string' },
      'wifi-pass-file': { type: 'string' },
      'no-fill': { type: 'boolean' },
      'include-camera': { type: 'boolean' },
      'pull-system-blobs': { type: 'boolean' },
      serial: { type: 'string' },
      'serial-file': { type: 'string' },
      'wait-seconds': { type: 'string' },
    },
  }).values;

  if (a.help) { log(STOCK_PROBE_HELP); return 0; }

  const modes = [a['read-only'], a['build-only'], a['extract-only'], a['http-only']].filter(Boolean).length;
  if (modes > 1) fail('pick at most one of --read-only, --build-only, --extract-only, --http-only');

  const dry = !!a['dry-run'];
  const outDir = resolve(a.out ?? join(tmpdir(), `mirror-stock-probe-${timestampSlug()}`));
  if (isInsideRepo(outDir)) {
    fail(`--out (${outDir}) is inside the git repo; pick a folder outside the repo so raw probe output can never be committed`);
  }
  mkdirSync(outDir, { recursive: true, mode: 0o700 });

  const tcpPort = a['no-tcp'] ? undefined : a['tcp-port'] ? Number.parseInt(a['tcp-port'], 10) : 5555;
  const probeHttpPort = a['probe-http-port'] ? Number.parseInt(a['probe-http-port'], 10) : DEFAULT_PROBE_HTTP_PORT;
  const waitSeconds = a['wait-seconds'] ? Number.parseInt(a['wait-seconds'], 10) : 180;
  if (!Number.isInteger(waitSeconds) || waitSeconds < 1) fail('--wait-seconds must be a positive integer');
  if (!Number.isInteger(probeHttpPort) || probeHttpPort < 1 || probeHttpPort > 65535) fail('--probe-http-port must be 1..65535');

  const wifi = a['wifi-ssid']
    ? {
        ssid: a['wifi-ssid'],
        password: a['wifi-pass-file']
          ? readFileSync(a['wifi-pass-file'], 'utf8').replace(/\r?\n$/, '')
          : fail('--wifi-ssid requires --wifi-pass-file'),
      }
    : undefined;

  const needsAdbKey = !a['extract-only'] && !a['http-only'];
  let adbKey: string | undefined;
  if (a['adb-key']) {
    if (!existsSync(a['adb-key'])) fail(`no adb public key at ${a['adb-key']}`);
    adbKey = readFileSync(a['adb-key'], 'utf8').trim();
  } else if (needsAdbKey) {
    if (dry) {
      adbKey = fakeAdbKey(7, 'dry@stock-probe');
    } else {
      const keyFile = join(homedir(), '.android/adbkey.pub');
      if (!existsSync(keyFile)) fail(`no adb public key at ${keyFile} (run \`adb devices\` once or pass --adb-key <file>)`);
      adbKey = readFileSync(keyFile, 'utf8').trim();
    }
  }

  let prompters: ReturnType<typeof makePrompters> | undefined;
  let deps: StockProbeDeps;
  if (dry) {
    log('DRY RUN: fake stock Mirror; no real device is touched.');
    deps = createDryDeps(log, { extractOnly: !!a['extract-only'] });
  } else {
    const fbBin = fastbootPath();
    const adbBin = adbPath();
    const explicitSerial = a.serial ?? (a['serial-file'] ? readSerial(a['serial-file']) : undefined);
    let pinnedSerial = explicitSerial;
    prompters = makePrompters(!!a.yes);
    const { confirm, promptText } = prompters;
    const vendorEntries = existsSync(VENDOR_TSV) ? parseVendorTsv(readFileSync(VENDOR_TSV, 'utf8')) : [];

    deps = {
      log,
      confirm,
      promptText,
      vendorEntries,
      loadProbeApp: () => loadOrBuildProbeApp({
        apkPath: a['probe-apk'],
        certHexPath: a['probe-cert-hex'],
        fiemapPath: a['probe-fiemap-so'],
        log,
      }),
      connectHttp: (host, port) => createHostHttpSession(host, port),
      async checkAdb(tcpTarget?: string, opts?: { timeoutMs?: number; preflight?: boolean }) {
        return checkHostAdb({
          adbBin,
          tcpTarget,
          activePin: opts?.preflight ? explicitSerial : pinnedSerial,
          timeoutMs: opts?.timeoutMs,
        });
      },
      async fastboot() {
        for (let i = 0; i < 60; i++) {
          const { stdout } = await execText(fbBin, ['devices'], 10_000, pinnedSerial);
          const found = parseFastbootDevices(stdout);
          const pick = pinnedSerial
            ? (found.includes(pinnedSerial) ? pinnedSerial : undefined)
            : found.length > 1
              ? fail(`${found.length} fastboot devices are connected: pass --serial-file or unplug other devices`)
              : found[0];
          if (pick) {
            pinnedSerial = pick;
            return createHostFastboot(pick, fbBin);
          }
          if (i === 0) log('    waiting for Mirror in fastboot...');
          await new Promise<void>((r) => setTimeout(r, 2000));
        }
        throw new Error('no fastboot device appeared in 2 minutes: is the Mirror in fastboot mode on this USB cable?');
      },
    };
  }

  try {
    const res = await runStockProbe({
      outDir,
      ...(adbKey !== undefined ? { adbKey } : {}),
      usbConfig: a['usb-config'],
      tcpPort,
      tcpHost: a['tcp-host'],
      probeHost: a['probe-host'],
      probeHttpPort,
      withProbeApk: !!a['with-probe-apk'],
      wifi,
      fill: !a['no-fill'],
      readOnly: !!a['read-only'],
      buildOnly: !!a['build-only'],
      extractOnly: !!a['extract-only'],
      httpOnly: !!a['http-only'],
      includeCamera: !!a['include-camera'],
      pullSystemBlobs: !!a['pull-system-blobs'],
      waitSeconds,
      ...(dry ? { userdataBytesOverride: DRY_USERDATA } : {}),
      dryRun: dry,
    }, deps);

    log('\n--- copy-paste progress report below ---\n');
    log(renderReportMarkdown(res.report));
    log(`--- report saved to: ${res.reportPath} ---`);

    return res.outcome === 'blocked' ? 1 : 0;
  } finally {
    prompters?.close();
  }
}
