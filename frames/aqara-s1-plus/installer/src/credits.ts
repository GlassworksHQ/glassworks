// The outside work the S1's install steps rely on, by kiln-core's phase: ids from
// frames/aqara-s1-plus/design/frame.json `credits` (frames/README.md#credits).
import type { Phase, StepCredits } from '@glassworks/kiln-core';

export const credits = {
  boot: ['sunxi-tools'],
  patch: ['magisk'],
} as const satisfies StepCredits<Phase>;
