// The Mirror's APK from its release: the newest generic-android-legacy-v* release (drafts, prereleases and other
// lines' tags never count), its arm64-v8a APK, and that APK's .sha256, through Kiln's release route as the S1's APK
// comes. Refract can't serve it: its allowlist pins each file's size and hash before the file exists, and a
// Glassworks release isn't known until its tag is cut. Never releases/latest: that is the S1's.
import { download, loadRelease } from '../android/tablet/model';

export const MIRROR_LINE = 'generic-android-legacy';
/** The Mirror's CPU runs 64-bit apps (frames/lululemon-mirror/design/frame.json `abis`). */
export const MIRROR_ABI = 'arm64-v8a';

/** Where the release's APK is, from waiting for the first look to checked against its published SHA-256. */
export type ReleaseApk =
  | { state: 'waiting' }
  | { state: 'looking' }
  | { state: 'downloading'; tag: string; version: string; name: string; got: number; size?: number }
  | { state: 'ready'; tag: string; version: string; name: string; size: number; sha256: string }
  | { state: 'none' }
  | { state: 'error'; why: string };

/** No published generic-android-legacy-v* release has an arm64-v8a APK. */
export class NoRelease extends Error {}

/**
 * How long the release route may stay silent, waiting for an answer or for the APK's next bytes, before Kiln gives
 * up with Try again. Without it a request that never answers left the row on "Looking…" (Mirror run, 2026-11-19).
 */
export const RELEASE_STALL_MS = 30_000;

export const releasePage = (tag: string) => `https://github.com/GlassworksHQ/glassworks/releases/tag/${encodeURIComponent(tag)}`;

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');

async function body(res: Response, onGot: (got: number, size?: number) => void, poke: () => void): Promise<Uint8Array> {
  const size = Number(res.headers.get('content-length')) || undefined;
  const parts: Uint8Array[] = [];
  let got = 0;
  const reader = res.body!.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    poke();
    parts.push(value);
    const before = got;
    got += value.length;
    // a whole-percent step (or each MiB without a size): one screen update, not one per USB-sized chunk
    if (size ? Math.floor((got * 100) / size) !== Math.floor((before * 100) / size) : got >> 20 !== before >> 20) onGot(got, size);
  }
  const out = new Uint8Array(got);
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

/**
 * The newest Mirror release's APK, its bytes checked against the release's .sha256. Throws NoRelease before the
 * first release, and an Error that says what went wrong otherwise, including when the route stays silent for
 * `stallMs`.
 */
export async function releaseApk(get: typeof fetch, onState: (s: ReleaseApk) => void, stallMs = RELEASE_STALL_MS): Promise<{ bytes: Uint8Array; ready: Extract<ReleaseApk, { state: 'ready' }> }> {
  const abort = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const poke = () => { clearTimeout(timer); timer = setTimeout(() => abort.abort(), stallMs); };
  const timed = (async (url: string) => { poke(); const res = await get(url, { signal: abort.signal }); poke(); return res; }) as typeof fetch;
  try {
    return await fetchApk(timed, onState, poke);
  } catch (e) {
    if (abort.signal.aborted) throw new Error(`Kiln's release route stopped answering for ${stallMs / 1000} s`);
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchApk(get: typeof fetch, onState: (s: ReleaseApk) => void, poke: () => void) {
  onState({ state: 'looking' });
  const r = await loadRelease(MIRROR_LINE, get);
  if (r.state === 'error') throw new Error(`Kiln couldn't read the releases: ${r.why}`);
  const apk = download(MIRROR_LINE, r, MIRROR_ABI);
  if (r.state !== 'out' || !apk) throw new NoRelease();
  const { tag } = r;
  const { version, name } = apk;

  const sum = await get(`${apk.href}.sha256`);
  if (!sum.ok) throw new Error(`Kiln couldn't read ${name}.sha256 from ${tag} (its release route answered ${sum.status})`);
  const want = (await sum.text()).trim().split(/\s+/)[0]?.toLowerCase() ?? '';
  if (!/^[0-9a-f]{64}$/.test(want)) throw new Error(`${name}.sha256 in ${tag} isn't a SHA-256`);

  onState({ state: 'downloading', tag, version, name, got: 0 });
  const res = await get(apk.href);
  if (!res.ok || !res.body) throw new Error(`Kiln couldn't download ${name} from ${tag} (its release route answered ${res.status})`);
  const bytes = await body(res, (got, size) => onState({ state: 'downloading', tag, version, name, got, size }), poke);
  const sha256 = hex(await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>));
  if (sha256 !== want) throw new Error(`${name} isn't the file ${tag} publishes: its SHA-256 is ${sha256}, the release says ${want}. Kiln dropped it`);
  return { bytes, ready: { state: 'ready' as const, tag, version, name, size: bytes.length, sha256 } };
}
