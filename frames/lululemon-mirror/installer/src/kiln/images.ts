// The page's side of imageWorker.ts: ImageMaker over postMessage. The built images come back as the
// OPFS files' Blobs, read from disk as fastboot sends them.
import { ProxyError, WrongImage, type ImageMaker } from '@glassworks/kiln-core';
import { downloaded, madeFile, sessionDir } from './imageJobs';

type Out = { id: number; progress?: number; step?: string; done?: boolean; error?: string; wrong?: boolean; proxy?: boolean };

export function workerImages(): ImageMaker {
  let worker: Worker | undefined;
  /** This page's own storage folder: the worker's name, so another tab never shares its files */
  const dir = sessionDir(crypto.randomUUID());
  let next = 0;
  const call = (msg: object, onProgress: (step: string | undefined, x: number) => void = () => {}) => new Promise<void>((resolve, reject) => {
    worker ??= new Worker(new URL('./imageWorker.ts', import.meta.url), { type: 'module', name: dir });
    const id = ++next;
    const w = worker;
    const on = (e: MessageEvent<Out>) => {
      const m = e.data;
      if (m.id !== id) return;
      if (m.progress !== undefined && !m.done) onProgress(m.step, m.progress);
      if (m.done || m.error) {
        w.removeEventListener('message', on);
        if (m.error) reject(m.proxy ? new ProxyError(m.error) : m.wrong ? new WrongImage(m.error) : new Error(m.error)); else resolve();
      }
    };
    w.addEventListener('message', on);
    w.postMessage({ ...msg, id });
  });
  const file = async (name: string) => (await (await (await navigator.storage.getDirectory()).getDirectoryHandle(dir)).getFileHandle(name)).getFile();
  return {
    fetch: (url, onProgress) => call({ op: 'fetch', url }, (_, x) => onProgress(x)),
    download: (url, want, onProgress) => call({ op: 'download', url, file: want }, (_, x) => onProgress(x)),
    file: (name) => file(downloaded(name)),
    check: (blob, want, onProgress) => call({ op: 'check', file: blob, want }, (_, x) => onProgress(x)),
    async wifi(o, onProgress) {
      await call({ op: 'wifi', ...o }, (_, x) => onProgress(x));
      return file(madeFile(o.makes));
    },
    async adbKey(o, onProgress) {
      await call({ op: 'adb-key', ...o }, (_, x) => onProgress(x));
      return file(madeFile(o.makes));
    },
    async systemKey(o, onProgress) {
      await call({ op: 'system-key', ...o }, (_, x) => onProgress(x));
      return file(madeFile(o.makes));
    },
    async build(o, onProgress) {
      await call({ op: 'build', ...o }, (step, x) => onProgress(step as Parameters<typeof onProgress>[0], x));
      return { system: await file('system.sparse'), userdata: await file('userdata.sparse') };
    },
    discard: () => call({ op: 'discard' }),
  };
}
