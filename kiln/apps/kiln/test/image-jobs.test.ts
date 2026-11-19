// The worker's jobs on memory instead of OPFS: the system image is loaded once and stays until
// discard(), so after a failed flash "install again" builds again without selecting the image again.
import { createHash } from 'node:crypto';
import { fakeAdbKey, fakeMirrorApk, fakeServicesJar } from '@glassworks/lululemon-mirror-installer/fakes';
import { describe, expect, it } from 'vitest';
import { Disk, MemoryStore, mkfs, writeSparse } from '@glassworks/ext4';
import { GLASS_PANES } from '@glassworks/lululemon-mirror-installer';
import { imageJobs, type JobHandle } from '../src/mirror/imageJobs';

/** A file in memory, shaped like OPFS's FileSystemSyncAccessHandle. */
class MemHandle implements JobHandle {
  buf = new Uint8Array(0);
  closed = false;
  read(b: Uint8Array, { at }: { at: number }) { b.set(this.buf.subarray(at, at + b.length)); return Math.max(0, Math.min(b.length, this.buf.length - at)); }
  write(b: Uint8Array, { at }: { at: number }) {
    if (at + b.length > this.buf.length) { const n = new Uint8Array(at + b.length); n.set(this.buf); this.buf = n; }
    this.buf.set(b, at);
    return b.length;
  }
  truncate(n: number) { this.buf = this.buf.slice(0, n); }
  flush() {}
  close() { this.closed = true; }
}

async function smallSystemImage(): Promise<Blob> {
  const d = new Disk(new MemoryStore(), 4096, 9000, { zeroed: true });
  const fs = await mkfs(d, { time: 1, journalBlocks: 0 });
  const dir = { mode: 0o755, uid: 0, gid: 0, label: 'u:object_r:system_file:s0', time: 1 };
  await fs.mkdir('/priv-app', dir);
  await fs.mkdir('/media', dir);
  await fs.mkdir('/framework', dir);
  await fs.writeFile('/framework/services.jar', await fakeServicesJar(), { ...dir, mode: 0o644 });
  await fs.writeFile('/build.prop', new TextEncoder().encode(`ro.build.fingerprint=${GLASS_PANES.fingerprint}\n`), { ...dir, mode: 0o644 });
  await fs.flush();
  const parts: Uint8Array<ArrayBuffer>[] = [];
  await writeSparse(d, (b) => { parts.push(b.slice()); });
  return new Blob(parts);
}

describe('the image worker\'s jobs', () => {
  it('build again after a failed flash, from the image loaded once; discard drops it', async () => {
    const image = await smallSystemImage();
    const bytes = new Uint8Array(await image.arrayBuffer());
    const want = { name: 'system.img', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    const files = new Map<string, MemHandle>();
    let removed = 0;
    const jobs = imageJobs(async (name) => { const h = new MemHandle(); files.set(name, h); return h; }, async () => { removed++; }, want);
    await jobs.load(image, () => {});
    const o = { apk: fakeMirrorApk(), adbKey: fakeAdbKey(), userdataBytes: 64 * 1024 * 1024 };
    await jobs.build(o, () => {});
    const first = files.get('system.sparse')!.buf.length;
    expect(first).toBeGreaterThan(28);
    expect(files.get('system.raw')!.closed).toBe(false); // kept: the loaded image lives on it
    await jobs.build({ ...o, fill: false }, () => {}); // the flash failed: install again, or without FILL
    expect(jobs.isLoaded()).toBe(true);
    expect(files.get('userdata.sparse')!.buf.length).toBeGreaterThan(0);
    await jobs.discard();
    expect(jobs.isLoaded()).toBe(false);
    expect(removed).toBe(1);
    await expect(jobs.build(o, () => {})).rejects.toThrow(/download the system image first/);
  });

  it('drops the image when a build fails, so the next one starts from the checked download', async () => {
    const image = await smallSystemImage();
    const bytes = new Uint8Array(await image.arrayBuffer());
    const want = { name: 'system.img', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    const jobs = imageJobs(async () => new MemHandle(), async () => {}, want);
    await jobs.load(image, () => {});
    const o = { apk: fakeMirrorApk({ minSdk: 26 }), adbKey: fakeAdbKey(), userdataBytes: 64 * 1024 * 1024 };
    await expect(jobs.build(o, () => {})).rejects.toThrow(/needs Android API 26/);
    expect(jobs.isLoaded()).toBe(false);
    await expect(jobs.build({ ...o, apk: fakeMirrorApk() }, () => {})).rejects.toThrow(/download the system image first/);
  });

  it('downloads a community file as published, hashing as it writes; one that isn\'t the release\'s is emptied (#579)', async () => {
    const bytes: Uint8Array<ArrayBuffer> = new Uint8Array(10_000).map((_, i) => i % 251);
    const want = { name: 'userdata.sparse', url: 'https://example.com/userdata.sparse', refract: 'x/userdata.sparse', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    const serve = (b: Uint8Array<ArrayBuffer>) => (async () => new Response(b, { status: 200, headers: { 'content-length': String(b.length) } })) as unknown as typeof fetch;
    const files = new Map<string, MemHandle>();
    const jobs = imageJobs(async (name) => { const h = files.get(name) ?? new MemHandle(); files.set(name, h); return h; }, async () => {});
    const seen: number[] = [];
    await jobs.download('https://refract.test/x', want, (x) => seen.push(x), serve(bytes));
    expect(files.get('download-userdata.sparse')!.buf).toEqual(bytes);
    expect(files.get('download-userdata.sparse')!.closed).toBe(true);
    expect(seen.at(-1)).toBe(1);
    const other = bytes.slice(); other[5] = other[5]! ^ 1;
    await expect(jobs.download('https://refract.test/x', want, () => {}, serve(other))).rejects.toThrow(/not the [0-9a-f]{64} the release publishes/);
    expect(files.get('download-userdata.sparse')!.buf.length).toBe(0);
  });
});
