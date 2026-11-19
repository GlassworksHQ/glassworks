// Each frame's declaration for Kiln's engine (its installer package's `kilnFrame`), and beside it, for ?demo=… and tests
// only, its simulated device (`kilnSim`). Loaded on demand: a frame's code reaches the browser only when its wizard opens.
import type { DemoFrame } from '@glassworks/kiln-core';

/** By frame id; catalog.ts's ENGINE lists the same ids. */
export const KILN: Record<string, () => Promise<DemoFrame>> = {
  'aqara-s1-plus': () => import('@glassworks/aqara-s1-plus-installer').then((m) => ({ frame: m.kilnFrame, sim: m.kilnSim })),
};
