// The Mirror's images in a dedicated worker, where OPFS hands out sync access handles: random reads
// and writes over a gigabyte without holding it in memory. Everything stays in this browser's private
// storage (folder `kiln-mirror`) and is deleted once flashed or when the page discards it. The work
// itself is imageJobs.ts; this file only carries messages.
import type { ImageFile } from '@glassworks/kiln-core';
import { ProxyError, WrongImage } from '@glassworks/lululemon-mirror-installer';
import { imageJobs, type BuildOptions, type JobHandle } from './imageJobs';

type In = { id: number; op: 'fetch'; url: string } | { id: number; op: 'download'; url: string; file: ImageFile } | ({ id: number; op: 'build' } & BuildOptions)
  | { id: number; op: 'discard' };

const DIR = 'kiln-mirror';
const root = () => navigator.storage.getDirectory();
const jobs = imageJobs(
  async (name) => {
    const fh = await (await (await root()).getDirectoryHandle(DIR, { create: true })).getFileHandle(name, { create: true });
    return (fh as unknown as { createSyncAccessHandle(): Promise<JobHandle> }).createSyncAccessHandle();
  },
  async () => { await (await root()).removeEntry(DIR, { recursive: true }).catch(() => {}); },
);

const post = (m: object) => (self as unknown as Worker).postMessage(m);

self.onmessage = async (e: MessageEvent<In>) => {
  const m = e.data;
  const progress = (x: number, step?: string) => post({ id: m.id, progress: x, step });
  try {
    if (m.op === 'fetch') await jobs.fetch(m.url, progress);
    else if (m.op === 'download') await jobs.download(m.url, m.file, progress);
    else if (m.op === 'build') await jobs.build(m, (step, x) => progress(x, step));
    else await jobs.discard();
    post({ id: m.id, done: true });
  } catch (err) {
    post({ id: m.id, error: (err as Error).message, wrong: err instanceof WrongImage, proxy: err instanceof ProxyError });
  }
};
