// The Mirror through the host's adb (`adb -s <serial>`), as a PanelShell: what Kiln's page does over
// WebUSB, done by the adb server the S1 and the tablet share, so it never has to stop. The serial is
// read from a file and never printed: logs say <serial>.
import { execFile } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createConnection } from 'node:net';
import { homedir } from 'node:os';
import { join } from 'node:path';
import type { PanelShell } from '@glassworks/adb';
import type { ByteStream } from 'improv-serial';

export function adbPath(): string {
  const p = process.env.ADB ?? join(homedir(), 'Library/Android/sdk/platform-tools/adb');
  return existsSync(p) ? p : 'adb';
}

export function readSerial(file = '/tmp/mirror-serial'): string {
  const s = readFileSync(file, 'utf8').trim();
  if (!/^[A-Za-z0-9._:-]+$/.test(s)) throw new Error(`${file} doesn't hold an adb serial`);
  return s;
}

const RC = '__GWRC=';

export interface HostAdb extends PanelShell {
  /** `adb -s <serial> <args>`: stdout, or throws with stderr (serial redacted). */
  adb(args: string[], o?: { timeoutMs?: number; encoding?: 'buffer' }): Promise<string | Buffer>;
}

export function hostAdb(serial: string, adb = adbPath()): HostAdb {
  const redact = (s: string) => s.split(serial).join('<serial>');
  const call = (args: string[], o: { timeoutMs?: number; encoding?: 'buffer' } = {}) => new Promise<string | Buffer>((resolve, reject) => {
    execFile(adb, ['-s', serial, ...args], { timeout: o.timeoutMs ?? 120_000, maxBuffer: 64 << 20, encoding: o.encoding === 'buffer' ? 'buffer' : 'utf8' }, (err, stdout, stderr) => {
      if (err) reject(new Error(redact(`adb ${args.join(' ')}: ${String(stderr || err.message).trim()}`)));
      else resolve(stdout);
    });
  });
  return {
    adb: call,
    async run(cmd) {
      const out = String(await call(['shell', `${cmd}; echo "${RC}$?"`]));
      const i = out.lastIndexOf(RC);
      return i < 0 ? { out, code: -1 } : { out: out.slice(0, i), code: Number.parseInt(out.slice(i + RC.length), 10) };
    },
    async push(path, data, mode = 0o644) {
      const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
      const { tmpdir } = await import('node:os');
      const dir = mkdtempSync(join(tmpdir(), 'gw-push-'));
      try {
        writeFileSync(join(dir, 'f'), data);
        await call(['push', join(dir, 'f'), path]);
        await call(['shell', `chmod ${mode.toString(8)} ${path}`]);
      } finally { rmSync(dir, { recursive: true, force: true }); }
    },
    /** A raw stream to a socket on the Mirror, through `adb forward` (S1 Kiosk's Improv socket for the Wi-Fi). */
    async openStream(service: string): Promise<ByteStream> {
      const port = Number(String(await call(['forward', 'tcp:0', service])).trim());
      if (!port) throw new Error(`adb forward gave no port for ${service}`);
      const sock = createConnection({ host: '127.0.0.1', port });
      await new Promise<void>((res, rej) => { sock.once('connect', () => res()); sock.once('error', rej); });
      let cb: (b: Uint8Array) => void = () => {};
      sock.on('data', (b) => cb(new Uint8Array(b)));
      return {
        write: (d) => new Promise<void>((res, rej) => sock.write(d, (e) => (e ? rej(e) : res()))),
        onData: (f) => { cb = f; },
        close: async () => { sock.destroy(); await call(['forward', '--remove', `tcp:${port}`]).catch(() => {}); },
      };
    },
  };
}
