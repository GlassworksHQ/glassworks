// The Mirror's images in a dedicated worker, where OPFS hands out sync access handles: random reads
// and writes over a gigabyte without holding it in memory. Everything stays in this browser's private
// storage (folder `kiln-mirror`) and is deleted once flashed or when the page discards it.
import { syncHandleStore, type SyncHandle } from '@glassworks/ext4';
import { GLASS_PANES, ProxyError, WrongImage, buildImages, loadSystem, resumableBody, type Loaded } from '@glassworks/lululemon-mirror-installer';

interface Handle extends SyncHandle { truncate(n: number): void; flush(): void; close(): void }
type In = { id: number; op: 'load'; file: Blob } | { id: number; op: 'fetch'; url: string } | { id: number; op: 'build'; apk: Uint8Array; bootAnimation?: Uint8Array; adbKey: string; userdataBytes: number } | { id: number; op: 'discard' };

const DIR = 'kiln-mirror';
let loaded: Loaded | undefined;
const open = new Map<string, Handle>();

async function folder() { return (await navigator.storage.getDirectory()).getDirectoryHandle(DIR, { create: true }); }

async function fresh(name: string): Promise<Handle> {
  open.get(name)?.close();
  const fh = await (await folder()).getFileHandle(name, { create: true });
  const h = await (fh as unknown as { createSyncAccessHandle(): Promise<Handle> }).createSyncAccessHandle();
  h.truncate(0);
  open.set(name, h);
  return h;
}

function closeAll() { for (const h of open.values()) { try { h.flush(); h.close(); } catch { /* closed */ } } open.clear(); }

const post = (m: object) => (self as unknown as Worker).postMessage(m);

self.onmessage = async (e: MessageEvent<In>) => {
  const m = e.data;
  try {
    if (m.op === 'load' || m.op === 'fetch') {
      const raw = await fresh('system.raw');
      const source = m.op === 'load' ? m.file : resumableBody(m.url, GLASS_PANES.system.size);
      loaded = await loadSystem(source, syncHandleStore(raw), (x) => post({ id: m.id, progress: x }));
      post({ id: m.id, done: true });
    } else if (m.op === 'build') {
      if (!loaded) throw new Error('select the system image first');
      const sinks = { system: await fresh('system.sparse'), userdata: await fresh('userdata.sparse') };
      const pos = { system: 0, userdata: 0 };
      const sink = (k: 'system' | 'userdata') => (b: Uint8Array) => { sinks[k].write(b, { at: pos[k] }); pos[k] += b.length; };
      await buildImages(loaded, { apk: m.apk, bootAnimation: m.bootAnimation, hwrotation: 270, adbKey: m.adbKey, userdataBytes: m.userdataBytes },
        { system: sink('system'), userdata: sink('userdata') }, (step, x) => post({ id: m.id, step, progress: x }));
      loaded = undefined;
      closeAll();
      post({ id: m.id, done: true });
    } else {
      loaded = undefined;
      closeAll();
      await (await navigator.storage.getDirectory()).removeEntry(DIR, { recursive: true }).catch(() => {});
      post({ id: m.id, done: true });
    }
  } catch (err) {
    post({ id: m.id, error: (err as Error).message, wrong: err instanceof WrongImage, proxy: err instanceof ProxyError });
  }
};
