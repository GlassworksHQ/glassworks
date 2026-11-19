// The worker's jobs on memory instead of OPFS: the system image is loaded once and stays until
// discard(), so after a failed flash "install again" builds again without selecting the image again.
import { createHash } from 'node:crypto';
import { fakeAdbKey, fakeMirrorApk, fakeServicesJar } from '@glassworks/lululemon-mirror-installer/fakes';
import { describe, expect, it } from 'vitest';
import { Disk, MemoryStore, mkfs, writeSparse } from '@glassworks/ext4';
import { GLASS_PANES } from '@glassworks/lululemon-mirror-installer';
import { Cancelled, WIFI_SCRATCH, claimAndSweep, imageJobs, jobQueue, madeFile, sessionDir, staleDirs, type JobHandle, type Locks } from '../src/mirror/imageJobs';

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

  it('refuses a short write, emptying the file, and cancel() stops a download in flight', async () => {
    const bytes: Uint8Array<ArrayBuffer> = new Uint8Array(10_000).map((_, i) => i % 251);
    const want = { name: 'system.img', url: 'https://example.com/system.img', refract: 'x/system.img', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    const serve = (async () => new Response(bytes, { status: 200, headers: { 'content-length': String(bytes.length) } })) as unknown as typeof fetch;
    class Short extends MemHandle { override write(b: Uint8Array, o: { at: number }) { return super.write(b.subarray(0, b.length - 1), o); } }
    const short = new Short();
    const jobs = imageJobs(async () => short, async () => {});
    await expect(jobs.download('https://refract.test/x', want, () => {}, serve)).rejects.toThrow(/stored \d+ of \d+ bytes/);
    expect(short.buf.length).toBe(0);
    const h = new MemHandle();
    const later = imageJobs(async () => h, async () => {});
    let first = true;
    const run = later.download('https://refract.test/x', want, () => { if (first) { first = false; later.cancel(); } }, serve);
    await expect(run).rejects.toBeInstanceOf(Cancelled);
    expect(h.buf.length).toBe(0);
    await later.download('https://refract.test/x', want, () => {}, serve); // a new controller: the next one runs
    expect(h.buf).toEqual(bytes);
  });

  it('checks the very Blob about to be flashed against its pin', async () => {
    const jobs = imageJobs(async () => new MemHandle(), async () => {});
    const b = new Uint8Array([1, 2, 3]);
    const want = { name: 'u', url: 'https://e.com/u', refract: 'x/u', size: 3, sha256: createHash('sha256').update(b).digest('hex') };
    await jobs.check(new Blob([b]), want, () => {});
    await expect(jobs.check(new Blob([new Uint8Array([1, 2, 4])]), want, () => {})).rejects.toThrow(/not the published/);
  });

  it('cancel() interrupts a read that waits on the network, and a request received before the cancel stops too', async () => {
    const want = { name: 'system.img', url: 'https://example.com/system.img', refract: 'x/system.img', size: 100, sha256: 'a'.repeat(64) };
    let aborted = false;
    // the first chunk arrives, then the network stalls; the request's signal reaches the fetch
    const stall = (async (_u: unknown, init?: RequestInit) => {
      init?.signal?.addEventListener('abort', () => { aborted = true; });
      return new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(10)); } }), { status: 200, headers: { 'content-length': '100' } });
    }) as unknown as typeof fetch;
    const jobs = imageJobs(async () => new MemHandle(), async () => {});
    const run = jobs.download('https://refract.test/x', want, () => { setTimeout(() => jobs.cancel(), 0); }, stall);
    await expect(run).rejects.toBeInstanceOf(Cancelled);
    expect(aborted).toBe(true);
    const queued = jobs.signal(); // as the worker keeps it for a request still in its queue
    jobs.cancel();
    await expect(jobs.download('https://refract.test/x', want, () => {}, stall, queued)).rejects.toBeInstanceOf(Cancelled);
  });

  it('a cancel while the download is still opening its file stops it before any byte is fetched', async () => {
    const want = { name: 'system.img', url: 'https://example.com/system.img', refract: 'x/system.img', size: 100, sha256: 'a'.repeat(64) };
    let opened!: (h: MemHandle) => void;
    const h = new MemHandle();
    let fetched = 0;
    const net = (async () => { fetched++; return new Response(new Uint8Array(100), { status: 200, headers: { 'content-length': '100' } }); }) as unknown as typeof fetch;
    const jobs = imageJobs(() => new Promise<MemHandle>((r) => { opened = r; }), async () => {});
    const run = jobs.download('https://refract.test/x', want, () => {}, net);
    jobs.cancel(); // fresh() is still waiting on the file
    opened(h);
    await expect(run).rejects.toBeInstanceOf(Cancelled);
    expect(fetched).toBe(0);
    expect(h.buf.length).toBe(0);
  });

  it('the worker\'s queue: one job at a time; a discard stops the running and queued downloads and ends before the next one starts', async () => {
    const jobs = imageJobs(async () => new MemHandle(), async () => {});
    const order: string[] = [];
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    // a download that runs until its signal aborts; a discard that takes a while
    const run = async (m: { op: string; n: number }, signal: AbortSignal) => {
      order.push(`start ${m.op} ${m.n}`);
      if (m.op === 'download') {
        if (signal.aborted) { order.push(`cancelled ${m.n} before it began`); return; }
        await Promise.race([gate, new Promise<void>((r) => signal.addEventListener('abort', () => r(), { once: true }))]);
        order.push(signal.aborted ? `cancelled ${m.n}` : `done ${m.n}`);
      } else {
        await new Promise((r) => setTimeout(r, 5));
        order.push(`end ${m.op} ${m.n}`);
      }
    };
    const receive = jobQueue(jobs, run);
    void receive({ op: 'download', n: 1 });
    // download 1 is running, waiting on the network, before anything else arrives
    for (let i = 0; i < 20 && !order.includes('start download 1'); i++) await new Promise((r) => setTimeout(r, 0));
    expect(order).toEqual(['start download 1']);
    void receive({ op: 'download', n: 2 }); // queued behind it
    void receive({ op: 'discard', n: 3 });  // stops both
    const last = receive({ op: 'download', n: 4 }); // sent after the discard: runs, after it
    await new Promise((r) => setTimeout(r, 0));
    release();
    await last;
    // 1 was running and stops mid-wait; 2 was queued and stops before it begins; the discard ends before 4 starts
    expect(order).toEqual(['start download 1', 'cancelled 1', 'start download 2', 'cancelled 2 before it began', 'start discard 3', 'end discard 3', 'start download 4', 'done 4']);
  });

  it('what a wifi step makes is never Kiln\'s own scratch file, whatever the recipe names it', () => {
    for (const n of ['userdata+wifi', 'scratch.raw', 'scratch-wifi.raw', '../x', '']) expect(madeFile(n)).not.toBe(WIFI_SCRATCH);
    expect(madeFile('userdata+wifi')).toBe('made-userdata_wifi');
  });

  it('a failed write cancels that download\'s own request; a handle that won\'t empty is closed', async () => {
    const want = { name: 'system.img', url: 'https://example.com/system.img', refract: 'x/system.img', size: 100, sha256: 'a'.repeat(64) };
    let cancelled = 0;
    const net = (async () => new Response(new ReadableStream({ pull(c) { c.enqueue(new Uint8Array(10)); }, cancel() { cancelled++; } }), { status: 200, headers: { 'content-length': '100' } })) as unknown as typeof fetch;
    class Full extends MemHandle { override write() { return 0; } }
    const full = new Full();
    const jobs = imageJobs(async () => full, async () => {});
    await expect(jobs.download('https://refract.test/x', want, () => {}, net)).rejects.toThrow(/stored 0 of 10 bytes/);
    expect(cancelled).toBe(1);
    expect(full.closed).toBe(true);
    class Stuck extends MemHandle { override truncate() { throw new Error('NoModificationAllowedError'); } }
    const stuck = new Stuck();
    await expect(imageJobs(async () => stuck, async () => {}).download('https://refract.test/x', want, () => {}, net)).rejects.toThrow(/NoModificationAllowed/);
    expect(stuck.closed).toBe(true);
  });

  it('each page has its own folder; a worker sweeps Kiln folders no live page holds, never its own or another app\'s', () => {
    const own = sessionDir('b');
    expect(own).toBe('kiln-mirror-b');
    const names = ['kiln-mirror', 'kiln-mirror-a', own, 'kiln-mirror-c', 'other-app', 'kiln-mirrorish'];
    expect(staleDirs(names, ['kiln-mirror-c'], own)).toEqual(['kiln-mirror', 'kiln-mirror-a']);
  });

  it('the sweep deletes a folder only under its own lock: a page that starts mid-sweep keeps its folder (PR 596 review)', async () => {
    /** A Web Locks stand-in: names held, and requests waiting for them. */
    const held = new Set<string>();
    const waiting = new Map<string, (() => void)[]>();
    const locks: Locks = {
      async request(name: string, a: unknown, b?: unknown): Promise<void> {
        const ifAvailable = typeof a === 'object';
        const cb = (ifAvailable ? b : a) as (lock?: unknown) => Promise<void>;
        if (held.has(name)) {
          if (ifAvailable) return cb(null);
          await new Promise<void>((r) => { waiting.set(name, [...(waiting.get(name) ?? []), r]); });
        }
        held.add(name);
        try { await cb({ name }); } finally { held.delete(name); waiting.get(name)?.shift()?.(); }
      },
    } as Locks;
    const removed: string[] = [];
    // page A lists the folders, then pauses before deleting; page B starts in between and claims its own folder
    let resumeA!: () => void;
    const listA = async () => { const names = ['kiln-mirror', 'kiln-mirror-old', 'kiln-mirror-b']; await new Promise<void>((r) => { resumeA = r; }); return names; };
    const a = claimAndSweep(locks, 'kiln-mirror-a', listA, async (n) => { removed.push(n); });
    await new Promise((r) => setTimeout(r, 0));
    const b = claimAndSweep(locks, 'kiln-mirror-b', async () => ['kiln-mirror-a', 'kiln-mirror-b'], async (n) => { removed.push(`b:${n}`); });
    await b; // B holds its own lock, and leaves A's folder (A holds it)
    expect(held.has('kiln-mirror-b')).toBe(true);
    resumeA();
    await a;
    expect(removed).toEqual(['kiln-mirror', 'kiln-mirror-old']); // never B's live folder, never A's own
    expect(held.has('kiln-mirror-a') && held.has('kiln-mirror-b')).toBe(true);
  });

  it('a lock the browser refuses: the claim fails, so the worker makes no file, and nothing is swept (PR 596 review)', async () => {
    const locks = { request: () => Promise.reject(new DOMException('The request is not allowed', 'SecurityError')) } as unknown as Locks;
    let listed = 0, removed = 0;
    await expect(claimAndSweep(locks, 'kiln-mirror-a', async () => { listed++; return ['kiln-mirror']; }, async () => { removed++; })).rejects.toThrow(/not allowed/);
    expect([listed, removed]).toEqual([0, 0]);
  });
});
