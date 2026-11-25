// A wizard frame's downloads: the blobs staged with Kiln (kiln-core Assets, manifest.json), and the app from the newest
// release of the line its installer.json `apk` names, read when someone installs (Kiln's /api/release/<line>/latest and
// Refract, release/apk.ts, as the device pages do). A release then reaches Kiln with no deploy (issue 848). A file
// staged under the app's name (a local `npm run stage`) still wins, for testing a build before its release.
import type { Assets, KilnFrame } from '@glassworks/kiln-core';
import { NoRelease, releaseApk, type ListedRelease, type ReleaseFetch } from '../release/apk';

export interface FrameAssets {
  asset(name: string, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>;
  hasAsset(name: string): Promise<boolean>;
  /** The app, fetched and checked before the install writes anything (kiln-core FlowDeps `fetchAhead`); none without `apk`. */
  fetchAhead?: () => Promise<Uint8Array>;
}

/** Where this tab keeps the release the install took its app from (sessionStorage). */
export const pinKey = (line: string) => `kiln:apk:${line}`;

type Store = Pick<Storage, 'getItem' | 'setItem'>;
const tabStore = (): Store | undefined => { try { return typeof sessionStorage === 'undefined' ? undefined : sessionStorage; } catch { return undefined; } };

/**
 * `get`: fetch, for the release list and Refract; `o`: how a release download waits (tests make it short); `store`: the
 * tab's sessionStorage. The install looks up the newest release (`fetchAhead`, before it writes) and keeps which one in
 * the tab, so a set-up after a reload installs that same file, checked against the same digest, without a new look-up:
 * a release published since, or a release list that's down, can't change or stop what goes on the panel.
 */
export function frameAssets(frame: Pick<KilnFrame, 'apk' | 'app'>, assets: Assets<string>, get: typeof fetch = fetch.bind(globalThis), o: ReleaseFetch = {}, store: Store | undefined = tabStore()): FrameAssets {
  const app = frame.apk;
  let fromRelease: Promise<Uint8Array> | undefined;

  const pinned = (): ListedRelease | undefined => {
    try {
      const r = JSON.parse(store?.getItem(pinKey(app!.line)) ?? 'null') as ListedRelease | null;
      return r?.state === 'out' && typeof r.tag === 'string' && Array.isArray(r.assets) ? r : undefined;
    } catch { return undefined; }
  };

  /** `fresh`: the newest release now (the install); else the one this tab's install took, if any (a set-up after a reload). */
  const release = (fresh: boolean, onProgress?: (done: number, total: number) => void) => {
    if (!fromRelease) {
      const from = fresh ? undefined : pinned();
      const p = releaseApk({ line: app!.line, ...(from ? { from } : {}) }, get, (s) => {
        if (s.state === 'downloading' && s.size) onProgress?.(s.got, s.size);
      }, { ...o, requireDigest: true }).then(
        (r) => {
          try { store?.setItem(pinKey(app!.line), JSON.stringify(r.release)); } catch { /* the tab keeps no pin: a reload looks it up again */ }
          return r.bytes;
        },
        (e: unknown) => { throw e instanceof NoRelease ? new Error(`There's no ${frame.app} release to install yet`) : e; },
      );
      // a failed download isn't kept: Try again starts it over
      p.catch(() => { if (fromRelease === p) fromRelease = undefined; });
      fromRelease = p;
    }
    return fromRelease;
  };

  /** The frame's app, with no file staged under its name: it comes from the release. */
  const fromLine = async (name: string) => app?.asset === name && !(await assets.has(name));
  const asset: FrameAssets['asset'] = async (name, onProgress) =>
    (await fromLine(name)) ? release(false, onProgress) : assets.get(name, onProgress);
  return {
    asset,
    async hasAsset(name) {
      return app?.asset === name || assets.has(name);
    },
    ...(app ? { fetchAhead: async () => ((await fromLine(app.asset)) ? release(true) : assets.get(app.asset)) } : {}),
  };
}
