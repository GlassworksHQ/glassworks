// The page's side of imageWorker.ts: ImageMaker over postMessage. The built images come back as the
// OPFS files' Blobs, read from disk as fastboot sends them.
import { WrongImage } from '@glassworks/lululemon-mirror-installer';
import type { ImageMaker } from './flash';

type Out = { id: number; progress?: number; step?: string; done?: boolean; error?: string; wrong?: boolean };

export function workerImages(): ImageMaker {
  let worker: Worker | undefined;
  let next = 0;
  const call = (msg: object, onProgress: (step: string | undefined, x: number) => void = () => {}) => new Promise<void>((resolve, reject) => {
    worker ??= new Worker(new URL('./imageWorker.ts', import.meta.url), { type: 'module' });
    const id = ++next;
    const w = worker;
    const on = (e: MessageEvent<Out>) => {
      const m = e.data;
      if (m.id !== id) return;
      if (m.progress !== undefined && !m.done) onProgress(m.step, m.progress);
      if (m.done || m.error) {
        w.removeEventListener('message', on);
        if (m.error) reject(m.wrong ? new WrongImage(m.error) : new Error(m.error)); else resolve();
      }
    };
    w.addEventListener('message', on);
    w.postMessage({ ...msg, id });
  });
  const file = async (name: string) => (await (await (await navigator.storage.getDirectory()).getDirectoryHandle('kiln-mirror')).getFileHandle(name)).getFile();
  return {
    load: (f, onProgress) => call({ op: 'load', file: f }, (_, x) => onProgress(x)),
    async build(o, onProgress) {
      await call({ op: 'build', ...o }, (step, x) => onProgress(step as Parameters<typeof onProgress>[0], x));
      return { system: await file('system.sparse'), userdata: await file('userdata.sparse') };
    },
    discard: () => call({ op: 'discard' }),
  };
}
