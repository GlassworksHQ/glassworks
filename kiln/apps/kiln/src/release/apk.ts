// Glassworks' APK from its release, for a page that installs it: the newest release of the line the page names
// (drafts, prereleases and other lines' tags never count), its APK for one ABI, and that APK's .sha256, through Refract
// (refract.glassworks.dev), which admits this repo's release lines (kiln/workers/refract/releases.json). The APK
// resumes where a connection dropped and waits out Refract's rate limit (kiln-core downloadWhole), and nothing is used
// before its SHA-256 matches the .sha256 the release publishes. Never releases/latest: that is another line's.
import { ProxyError, downloadWhole } from '@glassworks/kiln-core';
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
 * How long Refract may stay silent, waiting for an answer or for the APK's next bytes, before Kiln counts the connection
 * as dropped and resumes it. Without it a request that never answers left the row on "Looking…" (first real run,
 * 2026-11-19, PR 607).
 */
export const RELEASE_STALL_MS = 30_000;

/** How a release download waits: the stall time above, and how often a dropped connection resumes before Try again. */
export interface ReleaseFetch {
  stallMs?: number;
  /** Resumes after a dropped or silent connection; default kiln-core's (5). */
  retries?: number;
  sleep?: (ms: number) => Promise<void>;
}

export const releasePage = (tag: string) => `https://github.com/GlassworksHQ/glassworks/releases/tag/${encodeURIComponent(tag)}`;

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');

/** A whole-percent step (or each MiB without a size): one screen update, not one per USB-sized chunk. */
const step = (got: number, before: number, size?: number) =>
  size ? Math.floor((got * 100) / size) !== Math.floor((before * 100) / size) : got >> 20 !== before >> 20;

/**
 * The line's newest release from Kiln's list, or an error once it has been silent for `stallMs` (no answer, or no
 * body): without that deadline a list that never answers left the row on "Looking…" (first real run, 2026-11-19).
 */
async function listed(line: Line, get: typeof fetch, stallMs: number): Promise<Release> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), stallMs);
  try {
    const r = await loadRelease(line, ((u: RequestInfo | URL, init?: RequestInit) => get(u, { ...init, signal: abort.signal })) as typeof fetch);
    return abort.signal.aborted ? { state: 'error', why: `Kiln's release list sent nothing for ${stallMs / 1000} s` } : r;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The newest release's APK for `of`, its bytes checked against the release's .sha256. Throws NoRelease before the
 * first release (or when the release has no APK for the ABI), and an Error that says what went wrong otherwise,
 * including when Refract stays silent through every resume. `of.from`: that release, as an earlier call listed it, not
 * the newest one now. Returns the release it took the APK from.
 */
export async function releaseApk(of: ReleaseLine & { from?: ListedRelease }, get: typeof fetch, onState: (s: ReleaseApk) => void, o: ReleaseFetch = {}): Promise<{ bytes: Uint8Array; ready: Extract<ReleaseApk, { state: 'ready' }>; release: ListedRelease }> {
  const dl = { fetch: get, stallMs: o.stallMs ?? RELEASE_STALL_MS, ...(o.retries !== undefined ? { retries: o.retries } : {}), ...(o.sleep ? { sleep: o.sleep } : {}) };
  onState({ state: 'looking' });
  const r: Release = of.from ?? await listed(of.line, get, dl.stallMs);
  if (r.state === 'error') throw new Error(`Kiln couldn't read the releases: ${r.why}`);
  const apk = download(r, of.abi);
  if (r.state !== 'out' || !apk) throw new NoRelease();
  const { tag } = r;
  const { version, name } = apk;

  let sum: Uint8Array;
  try {
    sum = await downloadWhole(`${apk.href}.sha256`, dl);
  } catch (e) {
    throw new Error(`Kiln couldn't read ${name}.sha256 from ${tag}: ${e instanceof ProxyError ? e.message : String(e)}`);
  }
  const want = new TextDecoder().decode(sum).trim().split(/\s+/)[0]?.toLowerCase() ?? '';
  if (!/^[0-9a-f]{64}$/.test(want)) throw new Error(`${name}.sha256 in ${tag} isn't a SHA-256`);

  onState({ state: 'downloading', tag, version, name, got: 0 });
  let before = 0;
  let bytes: Uint8Array;
  try {
    bytes = await downloadWhole(apk.href, {
      ...dl,
      onProgress: (got, size) => { if (step(got, before, size)) onState({ state: 'downloading', tag, version, name, got, ...(size ? { size } : {}) }); before = got; },
    });
  } catch (e) {
    throw new Error(`Kiln couldn't download ${name} from ${tag}: ${e instanceof ProxyError ? e.message : String(e)}`);
  }
  const sha256 = hex(await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>));
  if (sha256 !== want) throw new Error(`${name} isn't the file ${tag} publishes: its SHA-256 is ${sha256}, the release says ${want}. Kiln dropped it`);
  return { bytes, ready: { state: 'ready' as const, tag, version, name, size: bytes.length, sha256 }, release: r };
}
