// CLI runner for tools/mirror/stock-surgical-adb.ts: Phase 2 reversible surgical sparse ADB +
// optional full raw system/boot backup on an untouched stock 1.2x+ lululemon Studio Mirror.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  closeSync, createReadStream, createWriteStream, existsSync, lstatSync, mkdirSync, openSync,
  readFileSync, realpathSync, writeFileSync, writeSync,
} from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { USERDATA_BYTES } from '@glassworks/lululemon-mirror-installer';
import { fakeAdbKey } from '@glassworks/lululemon-mirror-installer/fakes';
import { adbPath, readSerial } from './adb';
import {
  createHostAdbSession, createHostHttpSession, execText, fastbootPath, loadOrBuildProbeApp,
  parseAdbDevices, parseFastbootDevices, redact,
} from './stock-probe-cli';
import {
  DEFAULT_PROBE_HTTP_PORT, patchStockServicesJar, runStockSurgicalAdb,
  STOCK_POST_BOOT_PATH, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES,
  type SurgicalAdbDeps, type SurgicalAdbSession, type SurgicalFastboot,
} from './surgical-adb';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');

export const SURGICAL_ADB_HELP = `usage: stock-surgical-adb.ts [options]

Phase 2 guided tool for an untouched stock 1.2x+ lululemon Studio Mirror (MSM8916) that already
has the Phase 1 HTTP probe app installed on userdata (mirror-<serial> SoftAP at 192.168.43.1).

Steps:
  1. Preflight (read-only over SoftAP HTTP): checks /system/framework for services.odex, lists
     /system/app and /system/priv-app, pulls services.jar + init.qcom.post_boot.sh + their
     FS_IOC_FIEMAP physical blocks, and immediately writes byte-for-byte undo images under <out>/backup/.
     With --backup (or --backup-module), also pulls /system/vendor/lib64/libdpmframework.so (the ELF64
     copy dpmd links) and writes restore-dpmd.sparse before anything is offered for flashing.
     Hard stop-gate if services.odex is detected, or if that library cannot hold the hook.
  2. Build locally (no writes): patches UsbDebuggingManager.startConfirmation() in services.jar to
     trust ONLY your --adb-key (exact original byte size), builds patch-services.sparse (DONT_CARE
     everywhere except services.jar's blocks) and userdata-adb.sparse (keeps probe APK as safety net).
     With --backup, also builds patch-dpmd.sparse: same file length, one relocation retargeted,
     the .ko embedded in the .eh_frame tail.
  3. Flash & verify USB ADB: prompts [y/N], flashes patch-services.sparse to system + fresh userdata,
     reboots, and verifies authorized USB \`adb shell\`. Reversible anytime via restore-services.sparse.
  4. Optional raw backup (--backup): flashes patch-dpmd.sparse so dpmd's constructor loads the backup
     module and makes the system and boot block nodes readable for one boot, streams raw system
     (1.2 GiB) + boot (32 MiB) over USB ADB, scrubs those two files back to stock inside the saved
     image, then flashes restore-dpmd.sparse. The module is the pinned, hash-checked GPL-2.0
     selinux_permissive artifact from glassworks-device-bins (never pushed to the device), or your own
     .ko with --backup-module. init.qcom.post_boot.sh is not patched: qti_init_shell cannot load modules.

Modes:
  --preflight-only          Run Step 1 only (read-only over SoftAP; saves backup/ + report.md)
  --build-only              Run Steps 1-2 only (build patch-services.sparse locally; no flash)
  --undo --backup-dir <d>   Flash restore-dpmd.sparse (if any) + restore-post-boot.sparse (if any) + restore-services.sparse
  --apply-adb --backup-dir <d>  Re-flash saved patch-services.sparse to re-enable USB ADB (Enforcing)
  --backup-only --backup-dir <d> [--backup-module <ko>]
                            USB-only Step 4 backup on a Mirror that already has authorized USB ADB
                            (tunnels probe app via adb forward — no Wi-Fi SoftAP needed); uses the
                            pinned device-bins module unless --backup-module overrides it
  --dry-run                 Simulate Step 1 preflight only, against a fake stock Mirror fixture
                             (forces --preflight-only; no device touched, Steps 2-4 are not exercised)

Options:
  --probe-host <ip>         Mirror IP for Phase 1 probe app (default: 192.168.43.1)
  --probe-http-port <port>  Probe HTTP port (default: ${DEFAULT_PROBE_HTTP_PORT})
  --adb-key <file>          Host ADB public key to authorize (default: ~/.android/adbkey.pub)
  --out <dir>               Output folder outside the repo (default: <tmpdir>/mirror-surgical-adb-<ts>)
  --backup                  Run Step 4's raw backup with the pinned device-bins selinux_permissive module
  --backup-module <file>    Override the pinned module with a local AArch64 .ko (implies --backup)
  --keep-adb                Keep authorized USB ADB (services.jar) active after Step 4 backup (default)
  --restore-all             With --backup, also restore original services.jar after backup
  --erase-userdata          With --undo, also run \`fastboot erase userdata\` for factory-reset stock
  --serial <id>             Target a specific fastboot/adb device serial (redacted in logs)
  --serial-file <file>      Read target serial from a file
  --wait-seconds <n>        Seconds to wait for fastboot / ADB boot (default: 420)
  --yes                     Answer yes to prompts
  --help                    Show this help`;

export class SurgicalAdbUsageError extends Error {}
const fail = (m: string): never => { throw new SurgicalAdbUsageError(m); };

function isInsideRepo(candidate: string): boolean {
  const repoReal = existsSync(REPO) ? realpathSync(REPO) : resolve(REPO);
  const abs = resolve(candidate);
  let cur = abs;
  const tail: string[] = [];
  while (!existsSync(cur)) {
    const parent = dirname(cur);
    if (parent === cur) break;
    tail.unshift(cur.slice(parent.length).replace(/^[/\\]+/, ''));
    cur = parent;
  }
  const resolved = existsSync(cur) ? resolve(realpathSync(cur), ...tail) : abs;
  const rel = relative(repoReal, resolved);
  return rel === '' || (!rel.startsWith(`..${sep}`) && rel !== '..' && !isAbsolute(rel));
}

/**
 * Resolves `relPath` under `baseDir`, refusing to escape it via `..` or via a symlink in any
 * existing path component (so a pre-planted symlink, e.g. `<outDir>/backup -> /elsewhere`, cannot
 * redirect a backup/restore-image write outside the output folder), and refusing a final path that
 * is itself a symlink. `baseDir` must already exist.
 */
function resolveContainedPath(baseDir: string, relPath: string): string {
  const baseReal = realpathSync(baseDir);
  const lexical = resolve(baseDir, relPath);
  const lexicalRel = relative(baseReal, lexical);
  if (lexicalRel.startsWith(`..${sep}`) || lexicalRel === '..' || isAbsolute(lexicalRel)) {
    throw new Error(`unsafe output path: ${relPath}`);
  }
  let cur = lexical;
  const tail: string[] = [];
  while (!existsSync(cur)) {
    const parent = dirname(cur);
    if (parent === cur) break;
    tail.unshift(cur.slice(parent.length).replace(/^[/\\]+/, ''));
    cur = parent;
  }
  if (tail.length === 0 && lstatSync(cur).isSymbolicLink()) {
    throw new Error(`refusing to write through a symlink: ${relPath}`);
  }
  const resolved = tail.length > 0 ? resolve(realpathSync(cur), ...tail) : realpathSync(cur);
  const finalRel = relative(baseReal, resolved);
  if (finalRel.startsWith(`..${sep}`) || finalRel === '..' || isAbsolute(finalRel)) {
    throw new Error(`refusing to write outside the output folder: ${relPath}`);
  }
  return resolved;
}

function createSurgicalFastboot(serial: string, bin: string): SurgicalFastboot {
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
    async flashPartition(partition, sparsePath) {
      await runFb(['flash', partition, sparsePath], 180_000);
    },
    async erasePartition(partition) {
      await runFb(['erase', partition], 60_000);
    },
    async reboot() {
      await runFb(['reboot'], 30_000);
    },
  };
}

/** No bytes for this long on a raw `dd` stream means the transfer is stalled, not just slow. */
const RAW_STREAM_INACTIVITY_MS = 120_000;

function createSurgicalAdbSession(target: string, bin: string, serialToRedact?: string, outDir?: string): SurgicalAdbSession {
  const base = createHostAdbSession(target, bin, serialToRedact);
  const tArgs = ['-s', target];
  return {
    ...base,
    async push(localPath: string, remotePath: string) {
      await execText(bin, [...tArgs, 'push', localPath, remotePath], 120_000, serialToRedact);
    },
    async rebootBootloader() {
      await execText(bin, [...tArgs, 'reboot', 'bootloader'], 30_000, serialToRedact);
    },
    async forwardTcp(localPort: number, remotePort: number) {
      await execText(bin, [...tArgs, 'forward', `tcp:${localPort}`, `tcp:${remotePort}`], 15_000, serialToRedact);
    },
    async removeForward(localPort: number) {
      await execText(bin, [...tArgs, 'forward', '--remove', `tcp:${localPort}`], 15_000, serialToRedact).catch(() => {});
    },
    async streamCommandToFile(remoteCmd: string, outPath: string) {
      const safeOutPath = outDir ? resolveContainedPath(outDir, relative(outDir, resolve(outPath))) : outPath;
      mkdirSync(dirname(safeOutPath), { recursive: true });
      return new Promise<{ bytes: number; sha256: string }>((res, rej) => {
        const child = spawn(bin, [...tArgs, 'exec-out', remoteCmd], { stdio: ['ignore', 'pipe', 'pipe'] });
        const ws = createWriteStream(safeOutPath, { mode: 0o600 });
        const hash = createHash('sha256');
        let bytes = 0;
        let stderr = '';
        let settled = false;
        let idleTimer: ReturnType<typeof setTimeout>;
        const doneErr = (err: Error) => {
          if (settled) return;
          settled = true;
          clearTimeout(idleTimer);
          ws.destroy();
          child.kill();
          rej(new Error(redact(err.message, serialToRedact)));
        };
        const resetIdleTimer = () => {
          clearTimeout(idleTimer);
          idleTimer = setTimeout(
            () => doneErr(new Error(`raw transfer stalled (no data for ${RAW_STREAM_INACTIVITY_MS / 1000}s)`)),
            RAW_STREAM_INACTIVITY_MS,
          );
        };
        resetIdleTimer();
        child.stderr.on('data', (d: Buffer) => { stderr += d.toString('utf8'); });
        child.stdout.on('data', (chunk: Buffer) => {
          resetIdleTimer();
          bytes += chunk.length;
          hash.update(chunk);
          if (!ws.write(chunk)) {
            child.stdout.pause();
            ws.once('drain', () => child.stdout.resume());
          }
        });
        child.on('error', doneErr);
        ws.on('error', doneErr);
        child.on('close', (code) => {
          if (settled) return;
          clearTimeout(idleTimer);
          ws.end(() => {
            if (settled) return;
            if (code !== 0) {
              settled = true;
              rej(new Error(redact(`adb exec-out exited ${code}: ${stderr.trim()}`, serialToRedact)));
              return;
            }
            settled = true;
            res({ bytes, sha256: hash.digest('hex') });
          });
        });
      });
    },
  };
}

/** Builds a minimal valid dry-run fixture JAR from the repo's DEX test fixture (or custom bytes). */
function loadDryRunFixtureJar(jarOverride?: Uint8Array): Uint8Array {
  if (jarOverride) return jarOverride;
  const fixtureDex = readFileSync(
    join(REPO, 'frames/lululemon-mirror/installer/test/fixtures/dex/classes.dex'),
  );
  // Minimal stored ZIP around classes.dex isn't enough for patchStockServicesJar unless it has
  // UsbDebuggingManager.startConfirmation — dry-run skips real DEX patching via fake HTTP fixture
  // or uses a synthetic test JAR in unit tests.
  return new Uint8Array(fixtureDex);
}

export async function surgicalAdbCli(argv: string[], log: (l: string) => void = (l) => console.log(l)): Promise<number> {
  const a = parseArgs({
    args: argv,
    options: {
      help: { type: 'boolean' },
      'preflight-only': { type: 'boolean' },
      'build-only': { type: 'boolean' },
      undo: { type: 'boolean' },
      'apply-adb': { type: 'boolean' },
      'backup-only': { type: 'boolean' },
      'dry-run': { type: 'boolean' },
      'probe-host': { type: 'string' },
      'probe-http-port': { type: 'string' },
      'adb-key': { type: 'string' },
      out: { type: 'string' },
      backup: { type: 'boolean' },
      'backup-module': { type: 'string' },
      'backup-dir': { type: 'string' },
      'keep-adb': { type: 'boolean' },
      'restore-all': { type: 'boolean' },
      'erase-userdata': { type: 'boolean' },
      serial: { type: 'string' },
      'serial-file': { type: 'string' },
      'wait-seconds': { type: 'string' },
      yes: { type: 'boolean' },
    },
  }).values;

  if (a.help) {
    log(SURGICAL_ADB_HELP);
    return 0;
  }

  const modes = [a['preflight-only'], a['build-only'], a.undo, a['apply-adb'], a['backup-only']].filter(Boolean).length;
  if (modes > 1) fail('choose at most one of --preflight-only, --build-only, --undo, --apply-adb, --backup-only');
  if (a['keep-adb'] && a['restore-all']) fail('choose at most one of --keep-adb and --restore-all');

  const mode = a.undo
    ? 'undo'
    : a['apply-adb']
      ? 'apply-adb'
      : a['backup-only']
        ? 'backup-only'
        : a['preflight-only']
          ? 'preflight-only'
          : a['build-only']
            ? 'build-only'
            : 'full';
  const probeHost = a['probe-host'] ?? '192.168.43.1';
  const probeHttpPort = a['probe-http-port'] ? Number.parseInt(a['probe-http-port'], 10) : DEFAULT_PROBE_HTTP_PORT;
  if (!Number.isInteger(probeHttpPort) || probeHttpPort < 1 || probeHttpPort > 65535) {
    fail(`invalid --probe-http-port: ${a['probe-http-port']}`);
  }
  const waitSeconds = a['wait-seconds'] ? Number.parseInt(a['wait-seconds'], 10) : 420;
  if (!Number.isInteger(waitSeconds) || waitSeconds < 10) fail(`invalid --wait-seconds: ${a['wait-seconds']}`);

  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const outDir = resolve(a.out ?? join(tmpdir(), `mirror-surgical-adb-${ts}`));
  if (isInsideRepo(outDir)) {
    fail(`refusing to write probe/backup files inside the git repository (${outDir}); pass --out outside the repo`);
  }
  // --undo and --apply-adb only ever READ --backup-dir; every other mode writes a fresh recovery
  // set into --out. Refuse to reuse an --out that already holds one: a retry against an
  // already-patched device would otherwise overwrite the original restore-*.sparse images with
  // patched bytes, and concurrent writers to the same folder could interleave.
  if (a.undo !== true && a['apply-adb'] !== true && existsSync(join(outDir, 'backup', 'manifest.json'))) {
    fail(
      `refusing to reuse ${outDir}: it already holds a recovery set (backup/manifest.json). `
      + 'Pass a fresh --out so the original undo images are never overwritten.',
    );
  }
  mkdirSync(outDir, { recursive: true, mode: 0o700 });

  let adbKey: string | undefined;
  if (mode !== 'preflight-only' && mode !== 'undo' && mode !== 'apply-adb' && mode !== 'backup-only') {
    if (a['dry-run']) {
      adbKey = fakeAdbKey(0x21, 'dryrun@glassworks');
    } else {
      const keyPath = a['adb-key'] ?? join(homedir(), '.android/adbkey.pub');
      if (!existsSync(keyPath)) fail(`ADB public key not found at ${keyPath} (pass --adb-key <file>)`);
      adbKey = readFileSync(keyPath, 'utf8');
    }
  }

  let pinnedSerial = a.serial?.trim();
  if (!pinnedSerial && a['serial-file']) pinnedSerial = readSerial(a['serial-file']);

  const rl = a.yes || a['dry-run'] ? undefined : createInterface({ input: process.stdin, output: process.stdout });
  const confirm = async (q: string): Promise<boolean> => {
    if (a.yes || a['dry-run']) { log(`? ${q} [auto-yes]`); return true; }
    const ans = (await rl!.question(`? ${q} [y/N] `)).trim().toLowerCase();
    return ans === 'y' || ans === 'yes';
  };

  try {
    const fbBin = fastbootPath();
    const aBin = adbPath();

    const writeOutFile = async (relPath: string, data: Uint8Array | string): Promise<string> => {
      const full = resolveContainedPath(outDir, relPath);
      mkdirSync(dirname(full), { recursive: true, mode: 0o700 });
      writeFileSync(full, data, { mode: 0o600 });
      return full;
    };

    if (a['dry-run']) {
      log(`Dry-run output: ${outDir} (no device touched)`);
      // Dry-run exercises preflight-only cleanly unless a real stock services.jar is supplied.
      const fakeJar = loadDryRunFixtureJar();
      const fakePostBoot = new TextEncoder().encode(
        `#!/system/bin/sh\n# ${'A'.repeat(200)}\nsetprop persist.sys.usb.config mtp,adb\n`,
      );
      const deps: SurgicalAdbDeps = {
        now: () => new Date(),
        sleep: async () => {},
        log,
        confirm,
        connectHttp: () => ({
          async status() { return { ok: true, uid: 10099, pid: 1234, sdk: 23, fiemap: true, bootCompleted: '1' }; },
          async props() { return ''; },
          async dtPanel() { return ''; },
          async list(p: string) {
            if (p === '/system/framework') return ['f\tr\t1000\t/system/framework/services.jar'];
            if (p === '/system/app') return ['d\tr\t0\t/system/app/MirrorApp'];
            if (p === '/system/priv-app') return ['d\tr\t0\t/system/priv-app/SystemUI'];
            return [];
          },
          async readFile(p: string) {
            if (p === STOCK_SERVICES_JAR_PATH) return { status: 'ok', data: fakeJar };
            if (p === STOCK_POST_BOOT_PATH) return { status: 'ok', data: fakePostBoot };
            return { status: 'missing' };
          },
          async fiemap(p: string) {
            if (p === STOCK_SERVICES_JAR_PATH) {
              const blocks = Math.ceil(fakeJar.length / 4096);
              return { path: p, size: fakeJar.length, ino: 100, extents: [{ logical: 0, physical: 30000 * 4096, block: 30000, length: blocks * 4096, blocks, flags: 1 }] };
            }
            const blocks = Math.ceil(fakePostBoot.length / 4096);
            return { path: p, size: fakePostBoot.length, ino: 496, extents: [{ logical: 0, physical: 22517 * 4096, block: 22517, length: blocks * 4096, blocks, flags: 1 }] };
          },
        }),
        waitForFastboot: async () => ({
          serial: 'FAKE0001',
          async oemDeviceInfo() { return ['Device unlocked: false']; },
          async getvar(name: string) {
            if (name === 'product') return 'MSM8916';
            if (name === 'partition-size:system') return `0x${STOCK_SYSTEM_PARTITION_BYTES.toString(16)}`;
            if (name === 'partition-size:userdata') return `0x${USERDATA_BYTES.toString(16)}`;
            return undefined;
          },
          async flashPartition() {},
          async erasePartition() {},
          async reboot() {},
        }),
        checkAdb: async () => ({ usb: 'not-seen' }),
        loadProbeApp: async () => undefined,
        writeFile: writeOutFile,
        readHostFile: async (p) => new Uint8Array(readFileSync(p)),
      };
      const res = await runStockSurgicalAdb({
        mode: 'preflight-only',
        probeHost,
        probeHttpPort,
        adbKey,
        waitSeconds,
        outDir,
      }, deps);
      log(`Report: ${join(outDir, 'report.md')}`);
      return res.ok ? 0 : 1;
    }

    const deps: SurgicalAdbDeps = {
      now: () => new Date(),
      sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
      log,
      confirm,
      connectHttp: (host, port) => createHostHttpSession(host, port),
      waitForFastboot: async (timeoutMs) => {
        const deadline = performance.now() + timeoutMs;
        while (performance.now() < deadline) {
          const { stdout } = await execText(fbBin, ['devices'], 10_000, pinnedSerial).catch(() => ({ stdout: '' }));
          const devs = parseFastbootDevices(stdout);
          const pick = pinnedSerial ? (devs.includes(pinnedSerial) ? pinnedSerial : undefined) : devs[0];
          if (pick) {
            if (!pinnedSerial) pinnedSerial = pick;
            return createSurgicalFastboot(pick, fbBin);
          }
          await new Promise((r) => setTimeout(r, 2000));
        }
        return null;
      },
      checkAdb: async (timeoutMs) => {
        const { stdout } = await execText(aBin, ['devices'], timeoutMs, pinnedSerial).catch(() => ({ stdout: '' }));
        const map = parseAdbDevices(stdout);
        const usbEntries = [...map.entries()].filter(([id]) => !id.includes(':'));
        const entry = pinnedSerial
          ? usbEntries.find(([id]) => id === pinnedSerial)
          : (usbEntries.length === 1 ? usbEntries[0] : undefined);
        if (!entry) return { usb: 'not-seen' };
        const [id, st] = entry;
        return {
          usb: st,
          ...(st === 'device' ? { session: createSurgicalAdbSession(id, aBin, pinnedSerial ?? id, outDir) } : {}),
        };
      },
      loadProbeApp: () => loadOrBuildProbeApp({ confirm, log }),
      writeFile: writeOutFile,
      readHostFile: async (p) => new Uint8Array(readFileSync(p)),
      fetchUrl: async (url: string) => {
        const res = await fetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
        return new Uint8Array(await res.arrayBuffer());
      },
      restoreExtentsInRawImage: async (rawImagePath, patches) => {
        const fd = openSync(rawImagePath, 'r+');
        try {
          for (const { extents, origData } of patches) {
            const byLogical = [...extents].sort((a, b) => a.logical - b.logical);
            const neededBytes = Math.ceil(origData.length / 4096) * 4096;
            for (const e of byLogical) {
              if (e.logical >= neededBytes) break;
              const useBytes = Math.min(e.length, neededBytes - e.logical);
              const buf = new Uint8Array(useBytes);
              if (e.logical < origData.length) {
                const end = Math.min(origData.length, e.logical + useBytes);
                buf.set(origData.subarray(e.logical, end), 0);
              }
              // writeSync can write fewer bytes than asked (it's still a syscall under the hood);
              // loop until the whole extent is written so the backup never keeps stray patch bytes.
              let off = 0;
              let pos = e.physical;
              while (off < buf.length) {
                const n = writeSync(fd, buf, off, buf.length - off, pos);
                if (n <= 0) throw new Error(`writeSync made no progress at offset ${pos} (wrote ${off}/${buf.length} B)`);
                off += n;
                pos += n;
              }
            }
          }
        } finally {
          closeSync(fd);
        }
        return await new Promise<string>((res, rej) => {
          const hash = createHash('sha256');
          const rs = createReadStream(rawImagePath);
          rs.on('data', (c) => hash.update(c));
          rs.on('end', () => res(hash.digest('hex')));
          rs.on('error', rej);
        });
      },
    };

    const res = await runStockSurgicalAdb({
      mode,
      probeHost,
      probeHttpPort,
      adbKey,
      wantBackup: Boolean(a.backup || a['backup-module']),
      backupModulePath: a['backup-module'] ? resolve(a['backup-module']) : undefined,
      backupDir: a['backup-dir'] ? resolve(a['backup-dir']) : undefined,
      restoreAllAfterBackup: Boolean(a['restore-all']),
      eraseUserdataOnUndo: Boolean(a['erase-userdata']),
      waitSeconds,
      outDir,
    }, deps);
    log(`Report: ${join(outDir, 'report.md')}`);
    return res.ok ? 0 : 1;
  } finally {
    rl?.close();
  }
}

export { patchStockServicesJar };
