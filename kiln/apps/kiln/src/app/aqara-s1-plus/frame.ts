// The S1 for Kiln's shared wizard: its words and drawings from frames/aqara-s1-plus/design, and its real install's code
// from its installer package (kiln.ts). Its simulated device (sim.ts) goes only to the demo path, beside the frame.
// The wizard reads every frame through wizard/frame.ts.
import { credits, kiln, kilnSim } from '@glassworks/aqara-s1-plus-installer';
import design from '../../../../../../frames/aqara-s1-plus/design/installer.json';
import recovery from '../../../../../../frames/aqara-s1-plus/design/recovery.json';
import photo from '../../../../../../docs/img/fel-button.jpg';
import type { RecoveryData } from '../../recovery/player';
import { frameWords, type DemoFrame, type KilnFrame } from '../../wizard/frame';

export const frame: KilnFrame = {
  id: 'aqara-s1-plus',
  ...frameWords(design.words, kiln.updateStates),
  recovery: recovery as RecoveryData,
  photo,
  credits,
  ...kiln,
};

/** ?demo=… and tests only: the S1 with its simulated device. */
export const demoFrame: DemoFrame = { frame, sim: kilnSim };

/** The route the wizard runs under: installer.json's. */
export const BASE = design.route;
