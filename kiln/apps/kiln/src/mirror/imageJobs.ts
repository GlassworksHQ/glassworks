// What imageWorker.ts does, without the worker: the system image Glassworks patches, fetched through
// Refract once and kept until discard(), so a build can run again after a failed flash; the built
// sparse images; and a community image's files, downloaded as published and checked as they arrive
// (issue 579). Files come from `open` (OPFS sync access handles in the worker, memory in the tests).
import type { ImageFile } from '@glassworks/kiln-core';
import { Sha256, hex, syncHandleStore, type SyncHandle } from '@glassworks/ext4';
import {
  GLASS_PANES, WrongImage, buildImages, checkDownload, loadSystem, resumableBody, type Loaded, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

/** Where download() keeps a file, apart from the images Kiln builds. */
export const downloaded = (name: string) => `download-${name}`;

export interface JobHandle extends SyncHandle { truncate(n: number): void; flush(): void; close(): void }

export interface BuildOptions {
  apk: Uint8Array; bootAnimation?: Uint8Array; settingsOverlay?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
  deviceMac?: string; wifi?: { ssid: string; password: string };
  /** ro.glassworks.api_key.handover=1 (issue 580): a flag, never the key */
  apiKeyHandover?: boolean;
}

/** cancel() stopped a download or fetch: the person picked another image, or left. */
export class Cancelled extends Error {}

/**
 * `source`'s stream, ending in Cancelled as soon as `signal` aborts, even while a read is waiting on
 * the network. Each transfer has one abort listener, removed when it ends, fails or is cancelled: on
 * abort it cancels the source, which settles a read in flight, and the stream ends in Cancelled.
 */
function abortable(source: { size: number; stream(): ReadableStream<Uint8Array> }, signal: AbortSignal) {
  const stopped = () => new Cancelled('stopped: another image was picked');
  return {
    size: source.size,
    stream(): ReadableStream<Uint8Array> {
      // cancelled before it began (e.g. while its file was opening): don't open the source, which would start fetching
      if (signal.aborted) return new ReadableStream<Uint8Array>({ start(c) { c.error(stopped()); } });
      const r = source.stream().getReader();
      const onAbort = () => { void r.cancel(stopped()).catch(() => {}); };
      signal.addEventListener('abort', onAbort, { once: true });
      const release = () => signal.removeEventListener('abort', onAbort);
      return new ReadableStream<Uint8Array>({
        async pull(c) {
          try {
            if (signal.aborted) throw stopped();
            const { done, value } = await r.read();
            if (signal.aborted) throw stopped(); // the read settled because the source was cancelled
            if (done) { release(); c.close(); } else c.enqueue(value);
          } catch (e) {
            release();
            void r.cancel(e).catch(() => {});
            c.error(e);
          }
        },
        cancel(reason) { release(); void r.cancel(reason).catch(() => {}); },
      });
    },
  };
}

/** `f` (or the global fetch) with `signal` on every request, so a cancel also ends the connection. */
const withSignal = (signal: AbortSignal, f: typeof fetch = fetch.bind(globalThis)): typeof fetch =>
  ((u: RequestInfo | URL, init?: RequestInit) => f(u, { ...init, signal })) as typeof fetch;

export function imageJobs(open: (name: string) => Promise<JobHandle>, removeAll: () => Promise<void>,
  want: { name: string; size: number; sha256: string } = GLASS_PANES.system) {
  let loaded: Loaded | undefined;
  let abort = new AbortController();
  const handles = new Map<string, JobHandle>();
  const fresh = async (name: string) => {
    handles.get(name)?.close();
    const h = await open(name);
    try { h.truncate(0); } catch (e) { h.close(); throw e; } // a handle that failed to empty isn't kept open
    handles.set(name, h);
    return h;
  };
  const close = (name: string) => { const h = handles.get(name); if (h) { h.flush(); h.close(); handles.delete(name); } };
  const loadFrom = async (source: { size: number; stream(): ReadableStream<Uint8Array> }, onProgress: (x: number) => void) => {
    loaded = undefined;
    loaded = await loadSystem(source, syncHandleStore(await fresh('system.raw')), onProgress, want);
  };
  return {
    /** From a Blob instead of Refract: the tests' way in. */
    load: (file: Blob, onProgress: (x: number) => void) => loadFrom(file, onProgress),
    /** `signal`: the one current when the worker received the request (signal()), so a discard sent after it stops it. */
    fetch: (url: string, onProgress: (x: number) => void, f?: typeof fetch, signal = abort.signal) =>
      loadFrom(abortable(resumableBody(url, want.size, { fetch: withSignal(signal, f) }), signal), onProgress),
    /** Stops every download or fetch received so far, running or queued. The worker calls it as a discard arrives. */
    cancel() { abort.abort(); abort = new AbortController(); },
    /** The signal a request received now runs under. */
    signal: () => abort.signal,
    /**
     * A file as its release publishes it, from Refract into storage, hashed as it is written. One
     * that isn't the release's (another SHA-256) is emptied before the error, so nothing of it is kept.
     */
    async download(url: string, file: ImageFile, onProgress: (x: number) => void, f?: typeof fetch, signal = abort.signal): Promise<void> {
      const name = downloaded(file.name);
      const h = await fresh(name);
      const sha = new Sha256();
      let at = 0;
      let r: ReadableStreamDefaultReader<Uint8Array> | undefined;
      try {
        r = abortable(resumableBody(url, file.size, { fetch: withSignal(signal, f) }), signal).stream().getReader();
        for (;;) {
          const { done, value } = await r.read();
          if (done) break;
          // a sync access handle may write less than asked without throwing (File System standard)
          const wrote = h.write(value, { at });
          if (wrote !== value.length) throw new Error(`the browser stored ${wrote} of ${value.length} bytes at ${at} (is its storage full?)`);
          sha.update(value);
          at += value.length;
          onProgress(at / file.size);
        }
        const got = hex(sha.digest());
        if (at !== file.size || got !== file.sha256) throw new WrongImage(`its SHA-256 is ${got}, not the ${file.sha256} the release publishes`);
      } catch (e) {
        // this download only: its own reader and request stop, the shared signal and other jobs don't
        await r?.cancel(e).catch(() => {});
        h.truncate(0);
        throw e;
      } finally {
        close(name);
      }
    },
    /**
     * Writes system.sparse and userdata.sparse. The loaded image stays, so a retry after a failed
     * flash can build again. A build that fails drops it: patching may have stopped halfway, and the
     * next build must start from the checked download, not from that.
     */
    async build(o: BuildOptions, onProgress: (step: PrepareStep, x: number) => void): Promise<void> {
      if (!loaded) throw new Error('download the system image first');
      const out = { system: await fresh('system.sparse'), userdata: await fresh('userdata.sparse') };
      const pos = { system: 0, userdata: 0 };
      const sink = (k: 'system' | 'userdata') => (b: Uint8Array) => { out[k].write(b, { at: pos[k] }); pos[k] += b.length; };
      try {
        await buildImages(loaded, o, { system: sink('system'), userdata: sink('userdata') }, onProgress);
      } catch (e) {
        loaded = undefined;
        throw e;
      } finally {
        close('system.sparse');
        close('userdata.sparse');
      }
    },
    /**
     * The exact Blob about to be flashed, against its pinned size and SHA-256. The Blob is a snapshot:
     * if the stored file changes after it was taken, reading it fails, so what passes is what gets written.
     */
    check: (file: Blob, want: ImageFile, onProgress: (x: number) => void) => checkDownload(file, want, onProgress),
    async discard(): Promise<void> {
      loaded = undefined;
      for (const name of [...handles.keys()]) close(name);
      await removeAll();
    },
    /** Tests only. */
    isLoaded: () => !!loaded,
  };
}

/**
 * The worker's order: one operation at a time, in the order the page sent them, all on the one
 * `kiln-mirror` folder, so a discard ends before the next download creates its file there. A discard
 * first stops the download in flight, and those still queued, so it doesn't wait for a gigabyte
 * nobody wants any more: each request keeps the signal current when it arrived. Returns when that
 * operation has run.
 */
export function jobQueue<M extends { op: string }>(jobs: { cancel(): void; signal(): AbortSignal }, run: (m: M, signal: AbortSignal) => Promise<void>) {
  let queue = Promise.resolve();
  return (m: M): Promise<void> => {
    if (m.op === 'discard') jobs.cancel();
    const signal = jobs.signal();
    queue = queue.then(() => run(m, signal));
    return queue;
  };
}
