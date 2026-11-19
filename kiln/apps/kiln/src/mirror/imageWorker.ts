// The Mirror's images in a dedicated worker, where OPFS hands out sync access handles: random reads
// and writes over a gigabyte without holding it in memory. Everything stays in this browser's private
// storage, in this page's own folder (`kiln-mirror-<id>`, the worker's name), and is deleted once
// flashed or when the page discards it. The worker holds a Web Lock on that folder's name while it
// lives, and on start deletes Kiln folders no live page holds. The work itself is imageJobs.ts; this
// file only carries messages.
import type { ImageFile } from '@glassworks/kiln-core';
import { ProxyError, WrongImage } from '@glassworks/lululemon-mirror-installer';
import { claimAndSweep, imageJobs, jobQueue, sessionDir, type BuildOptions, type JobHandle, type Locks } from './imageJobs';

type In = { id: number; op: 'fetch'; url: string } | { id: number; op: 'download'; url: string; file: ImageFile } | ({ id: number; op: 'build' } & BuildOptions)
  | { id: number; op: 'check'; file: Blob; want: ImageFile } | { id: number; op: 'discard' };

const DIR = (self as unknown as { name?: string }).name || sessionDir(crypto.randomUUID());
const root = () => navigator.storage.getDirectory();

/**
 * This page's lock on its folder, held until the worker ends, before any file is made; then closed tabs'
 * folders get swept, each only under its own lock (claimAndSweep). Every job waits for this.
 */
const ready: Promise<Error | undefined> = (async () => {
  const locks = (navigator as Navigator & { locks?: Locks }).locks;
  if (!locks) return undefined; // no Web Locks: the page's own folder still keeps tabs apart, and nothing is swept
  try {
    await claimAndSweep(locks, DIR, async () => {
      const names: string[] = [];
      for await (const name of (await root() as unknown as { keys(): AsyncIterable<string> }).keys()) names.push(name);
      return names;
    }, async (name) => { await (await root()).removeEntry(name, { recursive: true }); });
    return undefined;
  } catch (e) {
    // the page's lock was refused: no job runs, so no file is made outside it; each says why
    return new Error(`Kiln couldn't claim this page's storage (${(e as Error).message}). Reload the page and try again.`);
  }
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
const receive = jobQueue(jobs, async (m: In, signal: AbortSignal) => {
  const failed = await ready;
  if (failed) { post({ id: m.id, error: failed.message }); return; }
  return run(m, signal);
});
self.onmessage = (e: MessageEvent<In>) => { void receive(e.data); };
