// The Mirror for Kiln (#621): its real install as Kiln's device flows run it. What it is called, its board, release
// line, timings, assets, the browser storage it keeps and every word its screens say are its design data
// (../../design/installer.json `device`); the steps only code can say are this package's own (recipe.ts over adb,
// flash.ts and image.ts over fastboot). Kiln reads it through the frames registry (packages/frames). The simulated
// Mirror for ?demo=… and tests is separate: sim.ts.
import { deviceWords, type DeviceFrame, type RecoveryData } from '@glassworks/kiln-core';
import design from '../../design/frame.json';
import installer from '../../design/installer.json';
import recovery from '../../design/recovery.json';
import { GLASSWORKS } from './app';
import { MIRROR_IMAGES } from './choices';
import { IMAGE_PROXY } from './download';
import { GLASS_PANES_USERDATA, MIRROR_FASTBOOT, USERDATA_BYTES, checkFastboot, firstBoot, makeOnlyHome } from './flash';
import { checkMirrorApk } from './image';
import { mirrorInPlace } from './inplace';
import { workerImages } from './kiln/images';
import { checkMirror, installMirror, undoMirror } from './recipe';
import { describeMirrorUpdates, readMirrorUpdates } from './updates';
import { wifiOn } from './wifi';

const d = installer.device;

export const kilnFrame: DeviceFrame = {
  kind: 'device',
  id: design.id,
  name: design.name,
  short: d.short,
  the: d.the,
  app: GLASSWORKS,
  connect: { ...d.connect, pick: d.listed.as },
  listed: d.listed.as,
  drivers: { win: d.drivers.win, linux: d.drivers.linux, guide: d.drivers.guide },
  // the image's copy in /system/priv-app holds INSTALL_PACKAGES, or Android asks before each update (updates.ts)
  updates: async (p, signal) => { const u = await readMirrorUpdates(p, GLASSWORKS, signal); return { state: u.state, log: describeMirrorUpdates(u) }; },
  board: d.board,
  apk: { line: d.apk.line, abi: d.apk.abi },
  words: deviceWords(d.words),
  appRows: d.appRows,
  silent: d.silent as DeviceFrame['silent'],
  recovery: recovery as unknown as RecoveryData,
  // the Mirror's Android is Glass Panes (frame.json `credits`)
  credits: { image: ['glass-panes-image'] },
  appFlow: {
    checkApk: async (apk) => { await checkMirrorApk(apk); },
    check: (p) => checkMirror(p),
    install: async (p, o) => { const { adminHomes, ...r } = await installMirror(p, o); return { ...r, heldHomes: adminHomes }; },
    undo: (p, o) => undoMirror(p, o),
  },
  imageFlow: {
    recipes: MIRROR_IMAGES,
    fastbootFilter: MIRROR_FASTBOOT,
    checkFastboot,
    communityUserdata: { file: GLASS_PANES_USERDATA.name, bytes: USERDATA_BYTES },
    firstBoot: (p, o) => firstBoot(p, o),
    makeOnlyHome: (p, onStep, sleep) => makeOnlyHome(p, onStep, sleep),
    wifiOn: (p, sleep) => wifiOn(p, sleep),
    images: workerImages,
    proxy: IMAGE_PROXY.base,
    assets: { bootAnimation: d.assets.bootAnimation, settings: d.assets.settings, framework: d.assets.framework },
    // Stock-in-place (choices.ts GLASSWORKS_IN_PLACE): the stage, the bundle's check, the build, the app's install
    inPlace: mirrorInPlace(d.words),
    storage: { flashedFlag: d.storage.flashedFlag, inPlaceDb: d.storage.inPlaceDb },
  },
  timings: { authMs: d.timings.authMs, firstStartAuthMs: d.timings.firstStartAuthMs, readMs: d.timings.readMs },
};
