// The S1 as the tests use it: its declaration and simulated device from its installer package, as the frames registry
// loads them (packages/frames), and its route.
import { kilnFrame, kilnSim } from '@glassworks/aqara-s1-plus-installer';
import type { DemoFrame } from '@glassworks/kiln-core';

export const frame = kilnFrame;
export const demoFrame: DemoFrame = { frame, sim: kilnSim };
export const BASE = '/aqara-s1-plus';
