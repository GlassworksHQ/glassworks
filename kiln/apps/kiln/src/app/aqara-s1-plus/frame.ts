// The S1 for Kiln's shared wizard: its words and drawings from frames/aqara-s1-plus/design, and its code from its
// installer package. Nothing else here; the wizard reads every frame through wizard/frame.ts.
import { credits, kiln } from '@glassworks/aqara-s1-plus-installer';
import design from '../../../../../../frames/aqara-s1-plus/design/installer.json';
import recovery from '../../../../../../frames/aqara-s1-plus/design/recovery.json';
import photo from '../../../../../../docs/img/fel-button.jpg';
import type { RecoveryData } from '../../recovery/player';
import { frameWords, type KilnFrame } from '../../wizard/frame';

export const frame: KilnFrame = {
  id: 'aqara-s1-plus',
  ...frameWords(design.words, kiln.updateStates),
  recovery: recovery as RecoveryData,
  photo,
  credits,
  ...kiln,
};

/** The route the wizard runs under: installer.json's. */
export const BASE = design.route;
