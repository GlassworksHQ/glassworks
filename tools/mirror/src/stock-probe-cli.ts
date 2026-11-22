// CLI runner for tools/mirror/stock-probe.ts: real fastboot/adb subprocess adapters, --dry-run
// fake stock Mirror, argument parsing, and copy-pasteable progress report output.
import { execFile } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { gzipSync } from 'node:zlib';
import { USERDATA_BYTES } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey } from '@glassworks/lululemon-mirror-installer/fakes';
import { adbPath, readSerial } from './adb';
import { DRY_USERDATA } from './dry';
import {
  parseVendorTsv, renderReportMarkdown, runStockProbe,
  type AdbState, type StockAdbSession, type StockFastboot, type StockProbeDeps,
} from './stock-probe';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const VENDOR_TSV = join(REPO, 'frames/lululemon-mirror/research/option-c-vendor-files.tsv');

export const STOCK_PROBE_HELP = `usage: stock-probe.ts [options]

Guided 6-step probe for a stock lululemon Studio Mirror (MSM8916).
Updates <out>/report.md after EVERY step so you can report progress at any point.

NEVER touches system, boot, recovery, modem, persist, or aboot.
Step 4 writes ONLY userdata (wiping stock app data, leaving stock system untouched).

Modes (default: full 6-step guided run):
  --read-only               Run Steps 1-2 only (ADB key check + fastboot read-only info)
  --build-only              Run Steps 1-3 only (also build sparse userdata locally; no flash)
  --extract-only            Skip fastboot/flash; jump straight to Steps 5-6 over ADB
  --dry-run                 Run all 6 steps on a fake stock Mirror; no device is touched

Options:
  --out <dir>               Output folder for report.md and raw/ (default: <tmpdir>/mirror-stock-probe-<ts>)
  --adb-key <file>          ADB public key to plant in userdata (default: ~/.android/adbkey.pub)
  --usb-config <value>      persist.sys.usb.config value (default: adb; alt: diag,serial_smd,rmnet_bam,adb)
  --tcp-port <port>         persist.adb.tcp.port value (default: 5555)
  --no-tcp                  Do not plant persist.adb.tcp.port
  --tcp-host <ip>           Also try \`adb connect <ip>:<tcp-port>\` in Step 5 (for Ethernet/Wi-Fi adb)
  --wifi-ssid <name> --wifi-pass-file <file>
                            Pre-save Wi-Fi (PBKDF2 PSK only) in userdata
  --no-fill                 Write zero blocks as RAW instead of FILL 0 in the sparse image
  --include-camera          Include the 321 camera vendor files in Step 6's Option C audit
  --pull-system-blobs       Also save readable stock vendor files locally under <out>/raw/system/
  --serial <id>             Target a specific fastboot/adb device serial (redacted in logs/report)
  --serial-file <file>      Read target serial from a file (e.g. /tmp/mirror-serial)
  --wait-seconds <n>        Seconds to wait for stock ADB after reboot (default: 180)
  --yes                     Answer yes to all step prompts
  --help                    Show this help`;

export class StockProbeUsageError extends Error {}
const fail = (m: string): never => { throw new StockProbeUsageError(m); };

function fastbootPath(): string {
  const p = process.env.FASTBOOT ?? join(homedir(), 'Library/Android/sdk/platform-tools/fastboot');
  return existsSync(p) ? p : 'fastboot';
}

function execText(bin: string, args: string[], timeoutMs = 120_000): Promise<{ stdout: string; stderr: string }> {
  return new Promise((res, rej) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 64 << 20, encoding: 'utf8' }, (err, stdout, stderr) => {
      if (err) rej(new Error(`${bin} ${args.join(' ')}: ${String(stderr || stdout || err.message).trim()}`));
      else res({ stdout, stderr });
    });
  });
}

function execBuffer(bin: string, args: string[], timeoutMs = 120_000): Promise<Buffer> {
  return new Promise((res, rej) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 128 << 20, encoding: 'buffer' }, (err, stdout, stderr) => {
      if (err) rej(new Error(`${bin} ${args.join(' ')}: ${String(stderr || err.message).trim()}`));
      else res(stdout);
    });
  });
}

function redact(text: string, serial?: string): string {
  return serial ? text.split(serial).join('<serial>') : text;
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
        const { stdout } = await execText(bin, [...targetArgs, 'shell', `${cmd}; echo "${RC}$?"`]);
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
      if (check.code !== 0) return null;
      try {
        const buf = await execBuffer(bin, [...targetArgs, 'exec-out', 'cat', path]);
        return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
      } catch {
        return null;
      }
    },
  };
}

function createHostFastboot(serial: string, bin: string): StockFastboot {
  const sArgs = ['-s', serial];
  return {
    async oemDeviceInfo() {
      const { stdout, stderr } = await execText(bin, [...sArgs, 'oem', 'device-info'], 15_000);
      return `${stdout}\n${stderr}`
        .split(/\r?\n/)
        .map((l) => redact(l.replace(/^\(bootloader\)\s*/i, '').trim(), serial))
        .filter((l) => l && !/^OKAY|^Finished\./i.test(l));
    },
    async getvar(name: string) {
      try {
        const { stdout, stderr } = await execText(bin, [...sArgs, 'getvar', name], 15_000);
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
      await execText(bin, [...sArgs, 'flash', 'userdata', sparsePath], 180_000);
    },
    async reboot() {
      await execText(bin, [...sArgs, 'reboot'], 30_000);
    },
  };
}

async function confirmer(yes: boolean): Promise<(q: string) => Promise<boolean>> {
  if (yes) return async () => true;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  process.on('exit', () => rl.close());
  return async (q: string) => /^y(es)?$/i.test((await rl.question(`    ${q} [y/N] `)).trim());
}

/** Realistic fake stock Mirror for --dry-run (touches no device). */
function createDryDeps(log: (l: string) => void): StockProbeDeps {
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

  let flashed = false;
  const session: StockAdbSession = {
    async shell(cmd: string) {
      if (cmd === 'getprop sys.boot_completed') return { out: '1\n', code: 0 };
      if (cmd === 'getprop') {
        return {
          out: [
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
          ].join('\n') + '\n',
          code: 0,
        };
      }
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

  return {
    log,
    confirm: async () => true,
    sleep: async () => {},
    vendorEntries: [
      { part: 'gpu-display', path: '/system/etc/firmware/a300_pfp.fw', bytes: 1156, sha256: '0000', label: 'system_file', shell: 'yes' },
      { part: 'gpu-display', path: '/system/bin/mm-pp-daemon', bytes: 141704, sha256: '1111', label: 'mm-pp-daemon_exec', shell: 'no' },
    ],
    async checkAdb() {
      return flashed ? { usb: 'device', session } : { usb: 'not-seen' };
    },
    async fastboot() {
      return {
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

function isInsideRepo(dir: string): boolean {
  const rel = relative(REPO, resolve(dir));
  return rel === '' || (!rel.startsWith('..') && !rel.startsWith('/'));
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
      yes: { type: 'boolean' },
      out: { type: 'string' },
      'adb-key': { type: 'string' },
      'usb-config': { type: 'string' },
      'tcp-port': { type: 'string' },
      'no-tcp': { type: 'boolean' },
      'tcp-host': { type: 'string' },
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

  const modes = [a['read-only'], a['build-only'], a['extract-only']].filter(Boolean).length;
  if (modes > 1) fail('pick at most one of --read-only, --build-only, --extract-only');

  const dry = !!a['dry-run'];
  const outDir = resolve(a.out ?? join(tmpdir(), `mirror-stock-probe-${timestampSlug()}`));
  if (a['pull-system-blobs'] && isInsideRepo(outDir)) {
    fail(`--out (${outDir}) is inside the git repo; pick a folder outside the repo when using --pull-system-blobs`);
  }
  mkdirSync(outDir, { recursive: true });

  const tcpPort = a['no-tcp'] ? undefined : a['tcp-port'] ? Number.parseInt(a['tcp-port'], 10) : 5555;
  const waitSeconds = a['wait-seconds'] ? Number.parseInt(a['wait-seconds'], 10) : 180;
  if (!Number.isInteger(waitSeconds) || waitSeconds < 1) fail('--wait-seconds must be a positive integer');

  const wifi = a['wifi-ssid']
    ? {
        ssid: a['wifi-ssid'],
        password: a['wifi-pass-file']
          ? readFileSync(a['wifi-pass-file'], 'utf8').replace(/\r?\n$/, '')
          : fail('--wifi-ssid requires --wifi-pass-file'),
      }
    : undefined;

  let adbKey: string;
  if (dry && !a['adb-key']) {
    adbKey = fakeAdbKey(7, 'dry@stock-probe');
  } else {
    const keyFile = a['adb-key'] ?? join(homedir(), '.android/adbkey.pub');
    if (!existsSync(keyFile)) fail(`no adb public key at ${keyFile} (run \`adb devices\` once or pass --adb-key <file>)`);
    adbKey = readFileSync(keyFile, 'utf8').trim();
  }

  let deps: StockProbeDeps;
  if (dry) {
    log('DRY RUN: fake stock Mirror; no real device is touched.');
    deps = createDryDeps(log);
  } else {
    const fbBin = fastbootPath();
    const adbBin = adbPath();
    let pinnedSerial = a.serial ?? (a['serial-file'] ? readSerial(a['serial-file']) : undefined);
    const confirm = await confirmer(!!a.yes);
    const vendorEntries = existsSync(VENDOR_TSV) ? parseVendorTsv(readFileSync(VENDOR_TSV, 'utf8')) : [];

    deps = {
      log,
      confirm,
      vendorEntries,
      async checkAdb(tcpTarget?: string) {
        if (tcpTarget) {
          await execText(adbBin, ['connect', tcpTarget], 10_000).catch(() => {});
        }
        const { stdout } = await execText(adbBin, ['devices'], 10_000).catch(() => ({ stdout: '' }));
        const map = parseAdbDevices(stdout);

        let usb: AdbState = 'not-seen';
        let usbTarget: string | undefined;
        if (pinnedSerial) {
          usb = map.get(pinnedSerial) ?? 'not-seen';
          if (usb === 'device') usbTarget = pinnedSerial;
        } else {
          const usbEntries = [...map.entries()].filter(([id]) => !id.includes(':'));
          if (usbEntries.length > 1) {
            throw new Error(`${usbEntries.length} USB adb devices are connected: pass --serial-file or unplug other devices`);
          }
          if (usbEntries.length === 1) {
            usb = usbEntries[0]![1];
            if (usb === 'device') {
              usbTarget = usbEntries[0]![0];
              pinnedSerial = usbTarget;
            }
          }
        }

        let tcp: AdbState | undefined;
        if (tcpTarget) tcp = map.get(tcpTarget) ?? 'not-seen';

        const activeTarget = usb === 'device' ? usbTarget : tcp === 'device' ? tcpTarget : undefined;
        const session = activeTarget ? createHostAdbSession(activeTarget, adbBin, pinnedSerial) : undefined;
        return { usb, ...(tcp !== undefined ? { tcp } : {}), ...(session ? { session } : {}) };
      },
      async fastboot() {
        for (let i = 0; i < 60; i++) {
          const { stdout } = await execText(fbBin, ['devices'], 10_000);
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

  const res = await runStockProbe({
    outDir,
    adbKey,
    usbConfig: a['usb-config'],
    tcpPort,
    tcpHost: a['tcp-host'],
    wifi,
    fill: !a['no-fill'],
    readOnly: !!a['read-only'],
    buildOnly: !!a['build-only'],
    extractOnly: !!a['extract-only'],
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
}
