'use client';
// A frame's route and its step routes: the frame's recipe on real WebUSB, or with ?demo=… a simulated device.
import { loadOrCreateAdbKey } from '@glassworks/adb';
import { FelDevice } from '@glassworks/fel';
import { Assets, BackupStore, webUsbDevices } from '@glassworks/kiln-core';
import { usePathname, useRouter } from 'next/navigation';
import { useCallback, useEffect, useMemo, useSyncExternalStore } from 'react';
import { frameAssets } from './assets';
import { DEMOS, demoSession, enterDemo, type Demo } from './demo';
import type { DemoFrame, KilnFrame } from '@glassworks/kiln-core';
import { registry, type Entry } from './registry';
import { Session } from './session';
import { useRouteSync } from './useRouteSync';
import { Wizard } from './Wizard';

/**
 * Demos run in `next dev`, and in a build made with NEXT_PUBLIC_KILN_DEMO=1 set at build time (Next
 * inlines it into the bundle). A build without it has none, whatever is set later.
 */
const DEMO_ON = process.env.NODE_ENV === 'development' || process.env.NEXT_PUBLIC_KILN_DEMO === '1';

function realSession(frame: KilnFrame): Session {
  // the staged blobs, and the app from its newest release (installer.json `apk`)
  const assets = frameAssets(frame, new Assets<string>('/assets/'));
  return new Session({
    recipe: frame.recipe,
    frame,
    devices: webUsbDevices,
    requestFel: () => FelDevice.request(),
    asset: (n, p) => assets.asset(n, p),
    hasAsset: (n) => assets.hasAsset(n),
    fetchAhead: assets.fetchAhead,
    backups: new BackupStore(frame.recipe.backup.db),
    loadKey: loadOrCreateAdbKey,
  });
}

/** `demoFrame`: the frame with its simulated device, used only for ?demo=… */
function create(frame: KilnFrame, demoFrame: DemoFrame | undefined, base: string, withDemo: boolean): Entry {
  const d = withDemo ? new URLSearchParams(location.search).get('demo') : null;
  const demo = DEMO_ON && demoFrame && DEMOS.includes(d as Demo) ? (d as Demo) : undefined;
  return { base, name: frame.name, session: demo && demoFrame ? demoSession(demoFrame, demo) : realSession(frame), demo };
}

/**
 * The frame's session lives in the registry, not in this component: the routes below its base share
 * it, and it outlives a visit to the picker, so a running install keeps running and the picker can
 * lead back to it. It is created once (StrictMode's double setup reuses it) and replaced only when
 * someone enters a route after the old session began a flow (routes.ts, `fresh`).
 */
export function FrameWizard({ frame, demo, base }: { frame: KilnFrame; demo?: DemoFrame; base: string }) {
  const current = useCallback((): Entry => {
    const e = registry.get();
    // a snapshot runs during render: the registry tells its other listeners once this render is over
    return e?.base === base && !e.standard ? e : registry.set(create(frame, demo, base, true), { quiet: true });
  }, [frame, demo, base]);
  const entry = useSyncExternalStore(registry.subscribe, current, current);
  const router = useRouter();
  const path = usePathname();
  const nav = useMemo(() => ({
    replace: (url: string) => router.replace(url, { scroll: false }),
    push: (url: string) => router.push(url, { scroll: false }),
  }), [router]);
  const renew = useCallback(() => registry.set(create(frame, demo, base, false)), [frame, demo, base]);
  useRouteSync(entry, path, nav, renew);
  useEffect(() => {
    if (entry.demo && demo && !entry.demoEntered) { entry.demoEntered = true; void enterDemo(entry.session as Session, demo, entry.demo as Demo); }
  }, [entry, demo]);
  return <Wizard session={entry.session as Session} frame={frame} base={base} />;
}
