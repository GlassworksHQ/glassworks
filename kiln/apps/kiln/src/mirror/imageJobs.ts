// What imageWorker.ts does, without the worker: the system image Glassworks patches, fetched through
// Refract once and kept until discard(), so a build can run again after a failed flash; the built
// sparse images; and a community image's files, downloaded as published and checked as they arrive
// (issue 579). Files come from `open` (OPFS sync access handles in the worker, memory in the tests).
import type { ImageFile } from '@glassworks/kiln-core';
import { Sha256, hex, syncHandleStore, type SyncHandle } from '@glassworks/ext4';
import {
  GLASS_PANES, WrongImage, buildImages, loadSystem, resumableBody, type Loaded, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

/** Where download() keeps a file, apart from the images Kiln builds. */
export const downloaded = (name: string) => `download-${name}`;

export interface JobHandle extends SyncHandle { truncate(n: number): void; flush(): void; close(): void }

export interface BuildOptions {
  apk: Uint8Array; bootAnimation?: Uint8Array; settingsOverlay?: Uint8Array; adbKey: string; userdataBytes: number; fill?: boolean;
  deviceMac?: string; wifi?: { ssid: string; password: string };
}

export function imageJobs(open: (name: string) => Promise<JobHandle>, removeAll: () => Promise<void>,
  want: { name: string; size: number; sha256: string } = GLASS_PANES.system) {
  let loaded: Loaded | undefined;
  const handles = new Map<string, JobHandle>();
  const fresh = async (name: string) => {
    handles.get(name)?.close();
    const h = await open(name);
    h.truncate(0);
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
    fetch: (url: string, onProgress: (x: number) => void) => loadFrom(resumableBody(url, want.size), onProgress),
    /**
     * A file as its release publishes it, from Refract into storage, hashed as it is written. One
     * that isn't the release's (another SHA-256) is emptied before the error, so nothing of it is kept.
     */
    async download(url: string, file: ImageFile, onProgress: (x: number) => void, f?: typeof fetch): Promise<void> {
      const name = downloaded(file.name);
      const h = await fresh(name);
      const sha = new Sha256();
      let at = 0;
      try {
        const r = resumableBody(url, file.size, f ? { fetch: f } : {}).stream().getReader();
        for (;;) {
          const { done, value } = await r.read();
          if (done) break;
          h.write(value, { at });
          sha.update(value);
          at += value.length;
          onProgress(at / file.size);
        }
        const got = hex(sha.digest());
        if (at !== file.size || got !== file.sha256) throw new WrongImage(`its SHA-256 is ${got}, not the ${file.sha256} the release publishes`);
      } catch (e) {
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
    async discard(): Promise<void> {
      loaded = undefined;
      for (const name of [...handles.keys()]) close(name);
      await removeAll();
    },
    /** Tests only. */
    isLoaded: () => !!loaded,
  };
}
