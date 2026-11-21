// Glassworks for generic Android for Kiln (#621): the engine's USB-debugging flow (kiln-core UsbFrame) with this
// frame's words and release line from its design data (../../design/installer.json `device`) and its code here: the
// brands and their steps to turn on USB debugging (devices.ts), and the recipe that reads, installs and undoes (recipe.ts).
import type { UsbFrame } from '@glassworks/kiln-core';
import installer from '../../design/installer.json';
import { APPS } from './app';
import { identifyPicked, identifyUsb, type BrandId } from './devices';
import { installGeneric, planInstall, undoGeneric } from './recipe';

const d = installer.device;

export const kilnFrame: UsbFrame = {
  kind: 'usb',
  id: 'generic-android',
  name: d.name,
  short: d.short,
  the: d.the,
  app: d.app,
  apk: { line: d.apk.line, abi: d.apk.abi },
  apps: APPS,
  identifyUsb,
  // a brand this frame doesn't list gets Android's general steps, as "Other" does
  identifyPicked: (brand) => identifyPicked(brand as BrandId | undefined),
  plan: planInstall,
  install: installGeneric,
  undo: undoGeneric,
};
