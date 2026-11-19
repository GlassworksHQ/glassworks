// The Mirror as the tests use it: its declaration and simulated device from its installer package, as the frames
// registry loads them (packages/frames), its route, and its timings.
import type { DeviceEntry } from '@glassworks/kiln-core';
import { kilnFrame } from '@glassworks/lululemon-mirror-installer/kiln';
import { kilnSim } from '@glassworks/lululemon-mirror-installer/sim';

export const mirrorFrame = kilnFrame;
export const mirrorEntry: DeviceEntry = { kind: 'device', frame: kilnFrame, sim: kilnSim };
export const MIRROR_BASE = '/lululemon-mirror';
export const { authMs: AUTH_MS, firstStartAuthMs: FIRST_START_AUTH_MS, readMs: READ_MS } = kilnFrame.timings;
