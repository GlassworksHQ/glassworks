// What Kiln's screens say and show for the Aqara S1 Plus. The hardware steps are the recipe's
// (@glassworks/aqara-s1-plus-installer); this is the words, the drawing data and the links.
import { credits } from '@glassworks/aqara-s1-plus-installer';
import type { AskKind } from '@glassworks/kiln-core';
import recovery from '../../../../../frames/aqara-s1-plus/design/recovery.json';
import photo from '../../../../../docs/img/fel-button.jpg';
import type { RecoveryData } from '../recovery/player';

const DOCS = 'https://github.com/GlassworksHQ/glassworks/blob/main/frames/aqara-s1-plus/docs';

export interface AskCopy { title: string; body: string; pick: string }

export const s1PlusUi = {
  id: 'aqara-s1-plus',
  name: 'Aqara S1 Plus',
  short: 'S1 Plus',
  recovery: recovery as RecoveryData,
  photo,
  photoAlt: 'The S1 Plus board with the back cover off. The recovery button is circled: right of the shield\'s notch, below the round sensor window.',
  /** The name the browser's USB list shows at each "your turn". */
  asks: {
    fastboot: { title: 'One click to continue.', body: 'The panel restarted into the installer. Nothing has been written yet.', pick: 'USB download gadget' },
    adb: { title: 'One last click.', body: 'The panel is starting its new software. Leave its screen alone meanwhile.', pick: 'QUAD-CORE R818 EVB2' },
    connect: { title: 'Connect to your panel.', body: 'It should be running S1 Kiosk and plugged in to this computer. Nothing is reflashed.', pick: 'QUAD-CORE R818 EVB2' },
  } satisfies Record<AskKind, AskCopy>,
  /** The device in recovery mode, as the browser lists it. */
  felPick: 'USB Device (1f3a:efe8)',
  drivers: {
    win: 'Windows needs a driver once: in Zadig, give USB Device (1f3a:efe8) the WinUSB driver.',
    linux: 'Linux needs a udev rule once for 1f3a:efe8 and 1f3a:1010.',
  },
  help: {
    recovery: `${DOCS}/install.md#troubleshooting`,
    undo: `${DOCS}/install.md#undo`,
    undoCli: 'frames/aqara-s1-plus/tools/install/fel-install.sh --undo ~/s1-backup',
    drivers: `${DOCS}/install.md#1--check-this-browser`,
    learn: `${DOCS}/install.md`,
  },
  app: 'S1 Kiosk',
  /** The outside work each install phase uses (the recipe's, ids from frame.json `credits`). */
  credits,
  /** Undo's two depths (frames/aqara-s1-plus/docs/install.md#undo). */
  undo: {
    factory: { title: 'As it came from Aqara', detail: 'The original software, then a factory reset on the panel. Everything is removed.' },
    keep: { title: 'Keep its settings', detail: 'Aqara\'s apps come back first, over USB with the panel running. Wi-Fi and the rest stay.', button: 'Give back Aqara\u2019s apps' },
  },
};

export type FrameUi = typeof s1PlusUi;

export { radioPlan, THREAD_FROM_HA, type PanelApp } from '../setup/radioPlan';
