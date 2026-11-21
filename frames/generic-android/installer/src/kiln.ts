// Glassworks for generic Android for Kiln (#621): the engine's USB-debugging flow (kiln-core UsbFrame) with this
// frame's words and release line from its design data (../../design/installer.json `device`) and its code here: the
// brands and their steps to turn on USB debugging (devices.ts), and the recipe that reads, installs and undoes (recipe.ts).
import type { SilentWords, UsbFrame, UsbSilentUpdates } from '@glassworks/kiln-core';
import installer from '../../design/installer.json';
import { APPS, androidName } from './app';
import { identifyBrowser } from './browser';
import { BRANDS, identifyPicked, identifyUsb, type BrandId } from './devices';
import { SILENT_LINES, installGeneric, planInstall, undoGeneric } from './recipe';

const d = installer.device;
const { why: _, ...grants } = d.grants;

/**
 * Done's Silent updates block (issue 422, the maintainer, 2026-11-18): in place of the grants list's row, what Kiln did
 * and what it means, with a word in the grants list's colours. Null where this build doesn't ask.
 */
function silent(u: UsbSilentUpdates | undefined, release: string): SilentWords | null {
  if (!u) return { word: 'Not confirmed', tone: 'n', line: `Glassworks' Device page on ${d.the} says whether updates install by themselves.` };
  switch (u.state) {
    case 'allowed':
      return { word: 'On', tone: 'y', line: `Updates from Home Assistant install with nobody at ${d.the}. Kiln allowed “Install unknown apps” for Glassworks only. Undo takes it back.` };
    case 'old-android':
      return { word: 'A tap each', tone: 'n', line: `Android ${release} asks for a tap on each update. Updates still come from Home Assistant; someone taps Install on ${d.the}. Only a device owner skips the tap.` };
    case 'declined':
      return { word: 'A tap each', tone: 'n', line: `Each update waits for a tap on ${d.the}, as asked. Updates still come from Home Assistant.` };
    case 'refused':
      if (u.line === SILENT_LINES.off) return { word: `This ${d.short} said no`, tone: 'w', line: `This ${d.short}'s software keeps “Install unknown apps” off. Updates still come from Home Assistant, with a tap on ${d.the}.` };
      if (u.line === SILENT_LINES.allowed) return { word: 'Still on', tone: 'w', line: `Android kept “Install unknown apps” allowed from an earlier install, so updates still install with nobody at ${d.the}.` };
      return { word: 'Not confirmed', tone: 'n', line: `Kiln couldn't read “Install unknown apps” back. Glassworks' Device page on ${d.the} says which it is.` };
    case 'not-asked':
      return null;
  }
}

export const kilnFrame: UsbFrame = {
  kind: 'usb',
  id: 'generic-android',
  name: d.name,
  short: d.short,
  the: d.the,
  app: d.app,
  apk: { line: d.apk.line, abi: d.apk.abi },
  apps: APPS,
  needs: { release: d.needs.release, local: d.needs.local },
  off: d.off,
  // the brands drawn in the mockup; the rest get Android's general steps, as Other does
  brands: d.picks.map((id) => ({ id, name: BRANDS.find((b) => b.id === id)!.name })),
  grants,
  connect: d.connect,
  screenLock: d.screenLock,
  silent,
  androidName,
  onDevice: { route: installer.onDevice.route, identify: identifyBrowser, lines: installer.onDevice.lines, needs: installer.onDevice.needs },
  identifyUsb,
  // a brand this frame doesn't list gets Android's general steps, as "Other" does
  identifyPicked: (brand) => identifyPicked(brand as BrandId | undefined),
  plan: planInstall,
  install: installGeneric,
  undo: undoGeneric,
};
