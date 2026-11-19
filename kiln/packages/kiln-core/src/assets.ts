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

export interface AssetEntry<A extends string = string> { name: A; path: string; sha256: string; size: number; license: string; source: string }
export interface Manifest<A extends string = string> { generated: string; assets: AssetEntry<A>[] }

export class Assets<A extends string> {
  private manifest?: Promise<Manifest<A>>;
  private cache = new Map<A, Promise<Uint8Array>>();

  /** `base`: where manifest.json is, ending in a slash (e.g. `/assets/`). */
  constructor(private readonly base: string, private readonly f: typeof fetch = fetch.bind(globalThis), private readonly o: AssetsOptions = {}) {}

  getManifest(): Promise<Manifest<A>> {
    this.manifest ??= this.f(`${this.base}manifest.json`, { cache: 'no-cache' }).then(async (r) => {
      if (!r.ok) throw new Error(`installer files are missing (manifest.json: HTTP ${r.status})`);
      return (await r.json()) as Manifest<A>;
    });
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
    const stallMs = this.o.stallMs ?? STALL_MS;
    // The watchdog: re-armed by every chunk, it aborts the request and fails the read it races.
    const ac = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let stalled!: (e: Error) => void;
    const watchdog = new Promise<never>((_, reject) => { stalled = reject; });
    watchdog.catch(() => {});
    const arm = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        const e = new Error(`could not download ${name}: nothing arrived for ${Math.round(stallMs / 1000)} s, so Kiln stopped waiting`);
        ac.abort(e);
        stalled(e);
      }, stallMs);
    };
    const out = new Uint8Array(entry.size);
    let got = 0;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      arm();
      const r = await Promise.race([this.f(url, { signal: ac.signal }), watchdog]);
      if (!r.ok || !r.body) throw new Error(`could not download ${name} (HTTP ${r.status})`);
      reader = r.body.getReader();
      for (;;) {
        const { done, value } = await Promise.race([reader.read(), watchdog]);
        if (done) break;
        arm();
        if (got + value.length > out.length) throw new Error(`${name} is larger than the manifest says`);
        out.set(value, got);
        got += value.length;
        onProgress?.(got, entry.size);
      }
    } catch (e) {
      void reader?.cancel().catch(() => {});
      throw e;
    } finally {
      clearTimeout(timer);
    }
    if (got !== entry.size) throw new Error(`${name}: got ${got} bytes, expected ${entry.size}`);
    const sha = toHex(await crypto.subtle.digest('SHA-256', out));
    if (sha !== entry.sha256) throw new Error(`${name} failed its SHA-256 check (got ${sha.slice(0, 16)}…): refusing to use it`);
    return out;
  }
}
