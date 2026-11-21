// Each frame's declaration for Kiln's engine (its installer package's `kilnFrame`), and beside it, for ?demo=… and tests
// only, its simulated device (`kilnSim`). Loaded on demand: a frame's code reaches the browser only when its page opens.
import type { KilnEntry } from '@glassworks/kiln-core';

/** By frame id; catalog.ts's ENGINE lists the same ids. */
export const KILN: Record<string, () => Promise<KilnEntry>> = {
  'aqara-s1-plus': () => import('@glassworks/aqara-s1-plus-installer').then((m) => ({ kind: 'wizard', frame: m.kilnFrame, sim: m.kilnSim })),
  'lululemon-mirror': () => Promise.all([import('@glassworks/lululemon-mirror-installer/kiln'), import('@glassworks/lululemon-mirror-installer/sim')])
    .then(([k, s]) => ({ kind: 'device', frame: k.kilnFrame, sim: s.kilnSim })),
  'generic-android': () => Promise.all([import('@glassworks/generic-android-installer/kiln'), import('@glassworks/generic-android-installer/sim')])
    .then(([k, s]) => ({ kind: 'usb', frame: k.kilnFrame, sim: s.kilnSim })),
};
