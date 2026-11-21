// Glassworks' APK from its release, for a page that installs it: the newest release of the line the page names
// (drafts, prereleases and other lines' tags never count), its APK for one ABI, and that APK's .sha256, through Kiln's
// release route, as Kiln's other release APKs come. Refract can't serve it: its allowlist pins each file's size and
// hash before the file exists, and a Glassworks release isn't known until its tag is cut. Never releases/latest: that
// is another line's.
import { ships, type Support } from '@glassworks/frame-card';
import { download, loadRelease, type Abi, type Line, type Release } from '../device/onDevice/model';

/** Where a page's APK comes from: the newest release, or a file the tester picks. */
export type ApkFrom = 'release' | 'local';

/**
 * A frame's page is everyone's, its APK from the release, once Kiln's picker lists the frame: frame.json's `status`
 * alpha or later and its `kiln` route (components/Picker.tsx reads the same two fields).
 */
export const installable = (frame: { status: string }) => ships(frame.status as Support) && !!(frame as { kiln?: string }).kiln;

/**
 * Whether a page is on and where its APK comes from: `?apk=release` or `?apk=local` (the test links), the release for
 * everyone once the page is `open`, or a demo where demos run.
 */
export function apkMode(search: string, demosOn: boolean, demos: readonly string[], open: boolean): { on: boolean; apk?: ApkFrom; demo?: string } {
  const q = new URLSearchParams(search);
  const demo = q.get('demo');
  if (demosOn && demo && demos.includes(demo)) return { on: true, demo };
  const apk = q.get('apk');
  if (apk === 'release' || apk === 'local') return { on: true, apk };
  return open ? { on: true, apk: 'release' } : { on: false };
}

/** Which APK a page installs: its release line, and the ABI it downloads. */
export interface ReleaseLine { line: Line; abi: Abi }

/** A release as the route listed it: its tag, version and files. */
export type ListedRelease = Extract<Release, { state: 'out' }>;

/**
 * The ABI of the release APK for a device: the first of its ABIs (`ro.product.cpu.abilist`, preferred first) that the
 * frame ships (frame.json `abis`). A 64-bit SoC with a 32-bit userspace lists only `armeabi-v7a,armeabi` (a Galaxy
 * Tab A 10.1 on Samsung's Android 8.1, issue 700), so it gets that build. Undefined when the frame ships none of them.
 */
export function apkAbi(deviceAbis: readonly string[], ships: readonly string[]): Abi | undefined {
  return deviceAbis.find((a) => ships.includes(a)) as Abi | undefined;
}

/** Where the release's APK is, from waiting for the first look to checked against its published SHA-256. */
export type ReleaseApk =
  | { state: 'waiting' }
  | { state: 'looking' }
  | { state: 'downloading'; tag: string; version: string; name: string; got: number; size?: number }
  | { state: 'ready'; tag: string; version: string; name: string; size: number; sha256: string }
  | { state: 'none' }
  | { state: 'error'; why: string };

/** No published release of the line has an APK for the ABI. */
export class NoRelease extends Error {}

/**
 * How long the release route may stay silent, waiting for an answer or for the APK's next bytes, before Kiln gives
 * up with Try again. Without it a request that never answers left the row on "Looking…" (first real run, 2026-11-19, PR 607).
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
 * The newest release's APK for `of`, its bytes checked against the release's .sha256. Throws NoRelease before the
 * first release (or when the release has no APK for the ABI), and an Error that says what went wrong otherwise,
 * including when the route stays silent for `stallMs`. `of.from`: that release, as an earlier call listed it, not the
 * newest one now. Returns the release it took the APK from.
 */
export async function releaseApk(of: ReleaseLine & { from?: ListedRelease }, get: typeof fetch, onState: (s: ReleaseApk) => void, stallMs = RELEASE_STALL_MS): Promise<{ bytes: Uint8Array; ready: Extract<ReleaseApk, { state: 'ready' }>; release: ListedRelease }> {
  const abort = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const poke = () => { clearTimeout(timer); timer = setTimeout(() => abort.abort(), stallMs); };
  const timed = (async (url: string) => { poke(); const res = await get(url, { signal: abort.signal }); poke(); return res; }) as typeof fetch;
  try {
    return await fetchApk(of, timed, onState, poke);
  } catch (e) {
    if (abort.signal.aborted) throw new Error(`Kiln's release route stopped answering for ${stallMs / 1000} s`);
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchApk(of: ReleaseLine & { from?: ListedRelease }, get: typeof fetch, onState: (s: ReleaseApk) => void, poke: () => void) {
  onState({ state: 'looking' });
  const r: Release = of.from ?? await loadRelease(of.line, get);
  if (r.state === 'error') throw new Error(`Kiln couldn't read the releases: ${r.why}`);
  const apk = download(of.line, r, of.abi);
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
  return { bytes, ready: { state: 'ready' as const, tag, version, name, size: bytes.length, sha256 }, release: r };
}
