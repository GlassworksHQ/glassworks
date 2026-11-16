export {
  PREVIOUS_HOME, checkMirror, installMirror, parseDfFreeMb, parsePreferredHome, preferredHome, undoMirror,
  type AppVersion, type Check, type HomeWait, type Installed, type MirrorOptions, type MirrorPhase, type MirrorReport, type Removed,
} from './recipe';
export { APP_DIR, GLASS_PANES, LABELS, buildUserdata, patchSystem, setProp, type SystemPatch } from './image';
export {
  GLASS_PANES_USERDATA, MIRROR_FASTBOOT, focusedActivity, makeOnlyHome, OTHER_HOMES, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, buildImages, checkDownload, checkFastboot, firstBoot, flashImages, loadSystem,
  type FastbootLike, type FastbootReport, type FirstBoot, type HomeCheck, type Loaded, type PrepareStep,
} from './flash';

/** The outside work each step stands on (kiln-core StepCredits; ids in frame.json `credits`): the Mirror's Android is Glass Panes. */
export const credits = { image: ['glass-panes-image'] };
export { IMAGE_PROXY, ProxyError, resumableBody, type ResumeOptions } from './download';
