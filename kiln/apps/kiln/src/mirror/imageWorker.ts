// The Mirror's images in a dedicated worker, where OPFS hands out sync access handles: random reads
// and writes over a gigabyte without holding it in memory. Everything stays in this browser's private
// storage, in this page's own folder (`kiln-mirror-<id>`, the worker's name), and is deleted once
// flashed or when the page discards it. The worker holds a Web Lock on that folder's name while it
// lives, and on start deletes Kiln folders no live page holds. The work itself is imageJobs.ts; this
// file only carries messages.
import type { ImageFile } from '@glassworks/kiln-core';
import { ProxyError, WrongImage } from '@glassworks/lululemon-mirror-installer';
import { imageJobs, jobQueue, sessionDir, staleDirs, type BuildOptions, type JobHandle } from './imageJobs';

type In = { id: number; op: 'fetch'; url: string } | { id: number; op: 'download'; url: string; file: ImageFile } | ({ id: number; op: 'build' } & BuildOptions)
  | { id: number; op: 'check'; file: Blob; want: ImageFile } | { id: number; op: 'discard' };

const DIR = (self as unknown as { name?: string }).name || sessionDir(crypto.randomUUID());
const root = () => navigator.storage.getDirectory();

/** This page's lock on its folder, held until the worker ends; then the closed tab's folders that are left get swept. */
const ready = (async () => {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks;
  if (!locks) return;
  void locks.request(DIR, () => new Promise<void>(() => {}));
  try {
    const q = await locks.query();
    const locked = [...(q.held ?? []), ...(q.pending ?? [])].map((l) => l.name ?? '');
    const names: string[] = [];
    for await (const name of (await root() as unknown as { keys(): AsyncIterable<string> }).keys()) names.push(name);
    for (const n of staleDirs(names, locked, DIR)) await (await root()).removeEntry(n, { recursive: true }).catch(() => {});
  } catch { /* a sweep that fails leaves storage as it was */ }
})();
const jobs = imageJobs(
  async (name) => {
    const fh = await (await (await root()).getDirectoryHandle(DIR, { create: true })).getFileHandle(name, { create: true });
    return (fh as unknown as { createSyncAccessHandle(): Promise<JobHandle> }).createSyncAccessHandle();
  },
  async () => { await (await root()).removeEntry(DIR, { recursive: true }).catch(() => {}); },
);

const post = (m: object) => (self as unknown as Worker).postMessage(m);

async function run(m: In, signal: AbortSignal) {
  const progress = (x: number, step?: string) => post({ id: m.id, progress: x, step });
  try {
    if (m.op === 'fetch') await jobs.fetch(m.url, progress, undefined, signal);
    else if (m.op === 'download') await jobs.download(m.url, m.file, progress, undefined, signal);
    else if (m.op === 'build') await jobs.build(m, (step, x) => progress(x, step));
    else if (m.op === 'check') await jobs.check(m.file, m.want, progress);
    else await jobs.discard();
    post({ id: m.id, done: true });
  } catch (err) {
    post({ id: m.id, error: (err as Error).message, wrong: err instanceof WrongImage, proxy: err instanceof ProxyError });
  }
}

// One operation at a time, in the order the page sent them (jobQueue).
const receive = jobQueue(jobs, async (m: In, signal: AbortSignal) => { await ready; return run(m, signal); });
self.onmessage = (e: MessageEvent<In>) => { void receive(e.data); };
