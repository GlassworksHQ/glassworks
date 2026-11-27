// What imageWorker.ts does, without the worker: the system image Glassworks patches, fetched through
// Refract once and kept until discard(), so a build can run again after a failed flash; the built
// sparse images; and a community image's files, downloaded as published and checked as they arrive
// (issue 579). Files come from `open` (OPFS sync access handles in the worker, memory in the tests).
import type { ImageFile } from '@glassworks/kiln-core';
import { Sha256, hex, syncHandleStore, type SyncHandle } from '@glassworks/ext4';
import { WrongImage, type PrepareStep } from '@glassworks/kiln-core';
import { resumableBody } from '../download';
import { checkDownload, loadSystem, type Loaded } from '../flash';
import { buildImages } from '../flash';
import { GLASS_PANES } from '../image';
import { systemWithKey, userdataWithKey, userdataWithWifi, wifiTemplate } from '../wifi';

/**
 * Each page's own storage folder, so another Kiln tab can't change a file between its check and its
 * write. The worker holds a Web Lock named after it for as long as it lives.
 */
export const DIR_PREFIX = 'kiln-mirror';
export const sessionDir = (id: string) => `${DIR_PREFIX}-${id}`;
/**
 * The folders to delete when a worker starts: Kiln's (the old shared one, or a page's) whose lock no
 * page holds or waits for, so a closed tab's downloads don't stay behind. Never its own.
 */
export function staleDirs(names: readonly string[], locked: readonly string[], own: string): string[] {
  return names.filter((n) => (n === DIR_PREFIX || n.startsWith(`${DIR_PREFIX}-`)) && n !== own && !locked.includes(n));
}

/** The part of the Web Locks API the sweep uses (navigator.locks in a worker; a fake in the tests). */
export interface Locks {
  request(name: string, cb: () => Promise<void>): Promise<void>;
  request(name: string, o: { ifAvailable: true }, cb: (lock: unknown) => Promise<void>): Promise<void>;
}

/**
 * Claims this page's folder, then sweeps: waits until it holds its own folder's lock (it creates no
 * file before), then takes each other Kiln folder's lock if it is free, and deletes that folder only
 * while holding it. A folder whose page is alive (its lock held) is never deleted, however the starts
 * of two pages interleave. Resolves once the sweep is done; the own lock stays held until the worker ends.
 */
export async function claimAndSweep(locks: Locks, own: string, list: () => Promise<string[]>, remove: (name: string) => Promise<void>): Promise<void> {
  // a lock the browser refuses (SecurityError, no lock manager) rejects here: the caller makes no file without it
  await new Promise<void>((held, refused) => { locks.request(own, () => { held(); return new Promise<void>(() => {}); }).catch(refused); });
  let names: string[];
  try { names = await list(); } catch { return; } // a sweep that fails leaves storage as it was
  for (const n of staleDirs(names, [], own)) {
    await locks.request(n, { ifAvailable: true }, async (lock) => { if (lock) await remove(n).catch(() => {}); }).catch(() => {});
  }
}

/** Where download() keeps a file, apart from the images Kiln builds. */
export const downloaded = (name: string) => `download-${name}`;
/**
 * Where a community image's `wifi`, `adb-key` and `system-key` steps keep what they make: always `made-…`, so
 * it can't be Kiln's own scratch file, and one stored name per recipe name (`_` and every character outside
 * letters, digits, `.` and `-` become `_` and its code in hex), so two steps' copies never share a file.
 */
export const madeFile = (name: string) => `made-${[...name].map((c) => (/[A-Za-z0-9.-]/.test(c) ? c : `_${c.codePointAt(0)!.toString(16).padStart(2, '0')}`)).join('')}`;
/** The wifi, adb-key and system-key jobs' working copy of the image: outside the `made-` names a recipe can choose. */
export const WIFI_SCRATCH = 'scratch-wifi.raw';

export interface JobHandle extends SyncHandle { truncate(n: number): void; flush(): void; close(): void }

export interface BuildOptions {
  apk: Uint8Array; bootAnimation?: Uint8Array; settings?: Uint8Array; framework?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
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
/** `f` under the job's signal, and under the request's own when it has one (resumableBody aborts a silent attempt). */
const withSignal = (signal: AbortSignal, f: typeof fetch = fetch.bind(globalThis)): typeof fetch =>
  ((u: RequestInfo | URL, init?: RequestInit) => f(u, { ...init, signal: init?.signal ? AbortSignal.any([signal, init.signal]) : signal })) as typeof fetch;

export function imageJobs(open: (name: string) => Promise<JobHandle>, removeAll: () => Promise<void>,
  want: { name: string; size: number; sha256: string } = GLASS_PANES.system,
  /** A stored file as a Blob, for reads where it lies (OPFS getFile in the worker). */
  blobOf: (name: string) => Promise<Blob> = () => Promise.reject(new Error('no file reader here'))) {
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
  /** An image copy `make` writes to madeFile(makes), through the scratch file; emptied if it fails. */
  const copy = async (makes: string, make: (store: ReturnType<typeof syncHandleStore>, sink: (b: Uint8Array) => void) => Promise<void>) => {
    const scratch = await fresh(WIFI_SCRATCH);
    const out = await fresh(madeFile(makes));
    let at = 0;
    try {
      await make(syncHandleStore(scratch), (b) => {
        const wrote = out.write(b, { at });
        if (wrote !== b.length) throw new Error(`the browser stored ${wrote} of ${b.length} bytes (is its storage full?)`);
        at += b.length;
      });
    } catch (e) {
      out.truncate(0);
      throw e;
    } finally {
      close(madeFile(makes));
      scratch.truncate(0);
      close(WIFI_SCRATCH);
    }
  };
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
        // Kiln's `settings` and `framework` are this image's overlays (GlassworksSettings.apk, GlassworksFramework.apk)
        const { settings, framework, ...rest } = o;
        await buildImages(loaded, { ...rest, ...(settings ? { settingsOverlay: settings } : {}), ...(framework ? { frameworkOverlay: framework } : {}) }, { system: sink('system'), userdata: sink('userdata') }, onProgress);
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
    /**
     * A community image's `wifi` step: the template read from the stored system image where it lies,
     * the stored userdata read and checked against its pin as it goes (or, with `made`, the copy an
     * earlier step made from it), the network saved (one file), and the copy written to madeFile(makes).
     */
    async wifi(o: { userdata: ImageFile; made?: string; system: ImageFile; makes: string; wifi: { ssid: string; password: string } }, onProgress: (x: number) => void): Promise<void> {
      const template = await wifiTemplate(await blobOf(downloaded(o.system.name)));
      const source = await blobOf(o.made ? madeFile(o.made) : downloaded(o.userdata.name));
      await copy(o.makes, (store, sink) => userdataWithWifi(source, o.made ? undefined : o.userdata, store, template, o.wifi, sink, onProgress));
    },
    /**
     * A community image's `adb-key` step: the stored userdata read and checked against its pin as it
     * goes, `adbKey` put in place of the published key (one file), and the copy written to madeFile(makes).
     */
    async adbKey(o: { userdata: ImageFile; makes: string; adbKey: string }, onProgress: (x: number) => void): Promise<void> {
      const source = await blobOf(downloaded(o.userdata.name));
      await copy(o.makes, (store, sink) => userdataWithKey(source, o.userdata, store, o.adbKey, sink, onProgress));
    },
    /**
     * A community image's `system-key` step: the stored system image read and checked against its pin as
     * it goes, its framework made to let in `adbKey` in place of the key it hardcodes (one file), and the
     * copy written to madeFile(makes).
     */
    async systemKey(o: { system: ImageFile; makes: string; adbKey: string }, onProgress: (x: number) => void): Promise<void> {
      const source = await blobOf(downloaded(o.system.name));
      await copy(o.makes, (store, sink) => systemWithKey(source, o.system, store, o.adbKey, sink, onProgress));
    },
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
