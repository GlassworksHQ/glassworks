// Pinned blobs (kiln/scripts/stage-assets.mjs writes <base>manifest.json). Every blob is checked
// against its SHA-256 before use; a mismatch is refused. A download that stops sending ends with an
// error after `stallMs` without a byte, so the step can be tried again (#621). `A` is the recipe's asset names.
import { toHex } from './backup';

/** How long a download may go without a byte before it fails: the stall watchdog's default. */
export const STALL_MS = 30_000;

export interface AssetsOptions {
  /** No bytes (or no answer) for this long ends the download with an error; default STALL_MS. */
  stallMs?: number;
}

/**
 * A stall watchdog for one download: `arm()` starts (or restarts) the clock, and `race(p)` fails `p` once
 * `ms` pass with no `arm()`. When it fires it also aborts `signal`, so fetch stops the request.
 */
function watchdog(ms: number, what: string) {
  const ac = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let fire!: (e: Error) => void;
  const fired = new Promise<never>((_, reject) => { fire = reject; });
  fired.catch(() => {});
  return {
    signal: ac.signal,
    arm() {
      clearTimeout(timer);
      timer = setTimeout(() => {
        const e = new Error(`could not download ${what}: nothing arrived for ${Math.round(ms / 1000)} s, so Kiln stopped waiting`);
        ac.abort(e);
        fire(e);
      }, ms);
    },
    race: <T>(p: Promise<T>) => Promise.race([p, fired]),
    stop() { clearTimeout(timer); },
  };
}

export interface AssetEntry<A extends string = string> { name: A; path: string; sha256: string; size: number; license: string; source: string }
export interface Manifest<A extends string = string> { generated: string; assets: AssetEntry<A>[] }

export class Assets<A extends string> {
  private manifest?: Promise<Manifest<A>>;
  private cache = new Map<A, Promise<Uint8Array>>();

  /** `base`: where manifest.json is, ending in a slash (e.g. `/assets/`). */
  constructor(private readonly base: string, private readonly f: typeof fetch = fetch.bind(globalThis), private readonly o: AssetsOptions = {}) {}

  private get stallMs() { return this.o.stallMs ?? STALL_MS; }

  /** The manifest, under the same stall watchdog as the blobs. A failed read isn't kept, so a retry reads it again. */
  getManifest(): Promise<Manifest<A>> {
    if (!this.manifest) {
      const w = watchdog(this.stallMs, 'manifest.json');
      w.arm();
      const p = w.race(this.f(`${this.base}manifest.json`, { cache: 'no-cache', signal: w.signal }).then(async (r) => {
        if (!r.ok) throw new Error(`installer files are missing (manifest.json: HTTP ${r.status})`);
        return (await w.race(r.json())) as Manifest<A>;
      })).finally(() => w.stop());
      p.catch(() => { if (this.manifest === p) this.manifest = undefined; });
      this.manifest = p;
    }
    return this.manifest;
  }

  async has(name: A): Promise<boolean> {
    return (await this.getManifest()).assets.some((a) => a.name === name);
  }

  /** Fetches and verifies one blob; reports download progress when the size is known. */
  get(name: A, onProgress?: (done: number, total: number) => void): Promise<Uint8Array> {
    let p = this.cache.get(name);
    if (!p) {
      p = this.load(name, onProgress);
      p.catch(() => this.cache.delete(name));
      this.cache.set(name, p);
    }
    return p;
  }

  private async load(name: A, onProgress?: (done: number, total: number) => void): Promise<Uint8Array> {
    const entry = (await this.getManifest()).assets.find((a) => a.name === name);
    if (!entry) throw new Error(`installer file ${name} is not in the manifest`);
    const url = /^(\/|https?:)/.test(entry.path) ? entry.path : `${this.base}${entry.path}`;
    // re-armed by every chunk: a download that stops sending fails, and its request is aborted
    const w = watchdog(this.stallMs, name);
    const out = new Uint8Array(entry.size);
    let got = 0;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      w.arm();
      const r = await w.race(this.f(url, { signal: w.signal }));
      if (!r.ok || !r.body) throw new Error(`could not download ${name} (HTTP ${r.status})`);
      reader = r.body.getReader();
      for (;;) {
        const { done, value } = await w.race(reader.read());
        if (done) break;
        w.arm();
        if (got + value.length > out.length) throw new Error(`${name} is larger than the manifest says`);
        out.set(value, got);
        got += value.length;
        onProgress?.(got, entry.size);
      }
    } catch (e) {
      void reader?.cancel().catch(() => {});
      throw e;
    } finally {
      w.stop();
    }
    if (got !== entry.size) throw new Error(`${name}: got ${got} bytes, expected ${entry.size}`);
    const sha = toHex(await crypto.subtle.digest('SHA-256', out));
    if (sha !== entry.sha256) throw new Error(`${name} failed its SHA-256 check (got ${sha.slice(0, 16)}…): refusing to use it`);
    return out;
  }
}
