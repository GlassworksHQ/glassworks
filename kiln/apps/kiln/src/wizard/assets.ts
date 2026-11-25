// A wizard frame's downloads: the blobs staged with Kiln (kiln-core Assets, manifest.json), and the app from the newest
// release of the line its installer.json `apk` names, read when someone installs (Kiln's /api/release/<line>/latest and
// Refract, release/apk.ts, as the device pages do). A release then reaches Kiln with no deploy (issue 848). A file
// staged under the app's name (a local `npm run stage`) still wins, for testing a build before its release.
import type { Assets, KilnFrame } from '@glassworks/kiln-core';
import { NoRelease, releaseApk, type ReleaseFetch } from '../release/apk';

export interface FrameAssets {
  asset(name: string, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>;
  hasAsset(name: string): Promise<boolean>;
  /** The app, fetched and checked before the install writes anything (kiln-core FlowDeps `fetchAhead`); none without `apk`. */
  fetchAhead?: () => Promise<Uint8Array>;
}

/** `get`: fetch, for the release list and Refract; `o`: how a release download waits (tests make it short). */
export function frameAssets(frame: Pick<KilnFrame, 'apk' | 'app'>, assets: Assets<string>, get: typeof fetch = fetch.bind(globalThis), o: ReleaseFetch = {}): FrameAssets {
  const app = frame.apk;
  let fromRelease: Promise<Uint8Array> | undefined;

  const release = (onProgress?: (done: number, total: number) => void) => {
    if (!fromRelease) {
      const p = releaseApk({ line: app!.line }, get, (s) => {
        if (s.state === 'downloading' && s.size) onProgress?.(s.got, s.size);
      }, o).then(
        (r) => r.bytes,
        (e: unknown) => { throw e instanceof NoRelease ? new Error(`There's no ${frame.app} release to install yet`) : e; },
      );
      // a failed download isn't kept: Try again starts it over
      p.catch(() => { if (fromRelease === p) fromRelease = undefined; });
      fromRelease = p;
    }
    return fromRelease;
  };

  const asset: FrameAssets['asset'] = async (name, onProgress) => {
    if (app?.asset === name && !(await assets.has(name))) return release(onProgress);
    return assets.get(name, onProgress);
  };
  return {
    asset,
    async hasAsset(name) {
      return app?.asset === name || assets.has(name);
    },
    ...(app ? { fetchAhead: () => asset(app.asset) } : {}),
  };
}
