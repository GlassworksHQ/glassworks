export {
  PREVIOUS_HOME, checkMirror, installMirror, parseDfFreeMb, parsePreferredHome, preferredHome, undoMirror,
  type AppVersion, type Check, type HomeWait, type Installed, type MirrorOptions, type MirrorPhase, type MirrorReport, type Removed,
} from './recipe';
export { APP_DIR, GLASS_PANES, LABELS, buildUserdata, patchSystem, setProp, type SystemPatch } from './image';

/** The outside work each step stands on (kiln-core StepCredits; ids in frame.json `credits`): the Mirror's Android is Glass Panes. */
export const credits = { image: ['glass-panes-image'] };
