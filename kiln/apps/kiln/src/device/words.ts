// A frame's optional words (installer.json `device.words`) that only some frames give: how long a step or a whole
// install usually takes, an intro line. Without one, the screens say less, never something wrong.
import type { DeviceFrame } from '@glassworks/kiln-core';

/** A frame word that only some frames give. */
export const word = (d: Pick<DeviceFrame, 'words'>, k: string): string | undefined => (d.words as unknown as Record<string, string | undefined>)[k];

/** How long a recipe's whole install usually takes: the frame's `time.total.<recipe id>` word, where it gives one. */
export const totalTime = (d: Pick<DeviceFrame, 'words'>, recipe: string): string | undefined => word(d, `time.total.${recipe}`);
