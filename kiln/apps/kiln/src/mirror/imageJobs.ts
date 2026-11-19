// What imageWorker.ts does, without the worker: the system image loaded once (from the person's file
// or through Refract) and kept until discard(), so a build can run again after a failed flash; the
// built sparse images and the way back's file checks. Files come from `open` (OPFS sync access
// handles in the worker, memory in the tests).
import { syncHandleStore, type SyncHandle } from '@glassworks/ext4';
import {
  GLASS_PANES, GLASS_PANES_USERDATA, buildImages, checkDownload, loadSystem, resumableBody, type Loaded, type PrepareStep,
} from '@glassworks/lululemon-mirror-installer';

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
    load: (file: Blob, onProgress: (x: number) => void) => loadFrom(file, onProgress),
    fetch: (url: string, onProgress: (x: number) => void) => loadFrom(resumableBody(url, want.size), onProgress),
    /**
     * Writes system.sparse and userdata.sparse. The loaded image stays, so a retry after a failed
     * flash can build again. A build that fails drops it: patching may have stopped halfway, and the
     * next build must start from the checked download, not from that.
     */
    async build(o: BuildOptions, onProgress: (step: PrepareStep, x: number) => void): Promise<void> {
      if (!loaded) throw new Error('select the system image first');
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
    /** The way back's downloads, checked here rather than on the page's thread. */
    check: (file: Blob, which: 'system' | 'userdata', onProgress: (x: number) => void) =>
      checkDownload(file, which === 'system' ? GLASS_PANES.system : GLASS_PANES_USERDATA, onProgress),
    async discard(): Promise<void> {
      loaded = undefined;
      for (const name of [...handles.keys()]) close(name);
      await removeAll();
    },
    /** Tests only. */
    isLoaded: () => !!loaded,
  };
}
