// e2fsprogs as the judge: e2fsck says whether an image is consistent, debugfs reads it back. The
// tests that need them skip without them, except in CI (GW_EXT4_TOOLS=require), where they must run.
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, openSync, closeSync, writeSync, readFileSync, rmSync, truncateSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Disk, MemoryStore } from '../src';

const DIRS = ['/opt/homebrew/opt/e2fsprogs/sbin', '/usr/local/opt/e2fsprogs/sbin', '/sbin', '/usr/sbin', '/usr/bin'];

function find(tool: string): string | undefined {
  for (const d of DIRS) if (existsSync(join(d, tool))) return join(d, tool);
  const w = spawnSync('which', [tool], { encoding: 'utf8' });
  return w.status === 0 ? w.stdout.trim() : undefined;
}

export const tools = { e2fsck: find('e2fsck'), debugfs: find('debugfs'), mke2fs: find('mke2fs') };
export const haveTools = !!(tools.e2fsck && tools.debugfs && tools.mke2fs);
if (!haveTools && process.env.GW_EXT4_TOOLS === 'require') throw new Error('e2fsprogs (e2fsck, debugfs, mke2fs) is required here');

export const scratch = () => mkdtempSync(join(tmpdir(), 'gw-ext4-'));
export const cleanup = (d: string) => rmSync(d, { recursive: true, force: true });

/** The disk's bytes as a raw image file, for e2fsck and debugfs. */
export async function dump(disk: Disk, file: string): Promise<void> {
  const fd = openSync(file, 'w');
  try {
    for (let b = 0; b < disk.blocks; b += 256) {
      const n = Math.min(256, disk.blocks - b);
      writeSync(fd, await disk.read(b, n), 0, n * disk.blockSize, b * disk.blockSize);
    }
  } finally { closeSync(fd); }
}

/** A raw image file as a Disk in memory, every block marked as data. */
export async function load(file: string, blockSize = 4096): Promise<Disk> {
  const bytes = new Uint8Array(readFileSync(file));
  const disk = new Disk(new MemoryStore(), blockSize, bytes.length / blockSize);
  for (let b = 0; b < disk.blocks; b += 256) await disk.write(b, bytes.subarray(b * blockSize, Math.min(disk.blocks, b + 256) * blockSize));
  return disk;
}

/** e2fsck -fn: the exit code (0 = clean) and what it said. */
export function fsck(file: string): { code: number; out: string } {
  const r = spawnSync(tools.e2fsck!, ['-fn', file], { encoding: 'utf8' });
  return { code: r.status ?? -1, out: `${r.stdout}${r.stderr}` };
}

/**
 * Clean, as e2fsck -fn means it: exit 0, and nothing it noticed and let pass. e2fsck -n exits 0 for a
 * group descriptor checksum it finds wrong ("checksum is …, should be …. IGNORED").
 */
export function clean(r: { code: number; out: string }): boolean {
  return r.code === 0 && !/checksum is|IGNORED/i.test(r.out);
}

export function debugfs(file: string, cmd: string): string {
  return execFileSync(tools.debugfs!, ['-R', cmd, file], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

/** A file's bytes as debugfs reads them (its `dump` to a file: `cat` through a pipe crawls on big files). */
export function debugfsCat(file: string, path: string): Uint8Array {
  const out = `${file}.out-${Math.random().toString(36).slice(2)}`;
  execFileSync(tools.debugfs!, ['-R', `dump ${path} ${out}`, file], { stdio: ['ignore', 'pipe', 'pipe'] });
  try { return new Uint8Array(readFileSync(out)); } finally { rmSync(out, { force: true }); }
}

export function mke2fs(file: string, sizeMiB: number, args: string[], populate?: string): void {
  const fd = openSync(file, 'w'); closeSync(fd);
  truncateSync(file, sizeMiB * 1024 * 1024);
  execFileSync(tools.mke2fs!, ['-q', '-F', '-t', 'ext4', '-b', '4096', '-I', '256', ...args, ...(populate ? ['-d', populate] : []), file], {
    env: { ...process.env, E2FSPROGS_FAKE_TIME: '1700000000', MKE2FS_DEVICE_SECTSIZE: '512' }, stdio: 'pipe',
  });
}
