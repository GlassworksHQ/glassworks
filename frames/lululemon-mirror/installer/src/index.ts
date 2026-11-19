export {
  PREVIOUS_HOME, checkMirror, installMirror, parseDfFreeMb, parsePreferredHome, preferredHome, undoMirror,
  type AppVersion, type Check, type HomeWait, type Installed, type MirrorOptions, type MirrorPhase, type MirrorReport, type Removed,
} from './recipe';
export { API_KEY_HANDOVER_PROP, DEVICE_MAC_PROP, GLASS_PANES, LABELS, SETTINGS_OVERLAY, apkLibs, buildUserdata, checkMirrorApk, macFromSerial, patchSystem, setProp, wpaPsk, wpaSupplicantConf, type SystemPatch } from './image';
export {
  GLASS_PANES_USERDATA, MIRROR_FASTBOOT, MIRROR_ROTATION, displayRotation, setPortrait, focusedActivity, makeOnlyHome, OTHER_HOMES, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, buildImages, checkDownload, checkFastboot, firstBoot, flashImages, loadSystem,
  type FastbootLike, type FastbootReport, type FirstBoot, type HomeCheck, type Loaded, type PrepareStep,
} from './flash';

/** The outside work each step stands on (kiln-core StepCredits; ids in frame.json `credits`): the Mirror's Android is Glass Panes. */
export const credits = { image: ['glass-panes-image'] };
export { IMAGE_PROXY, MAX_RETRY_AFTER_MS, ProxyError, resumableBody, retryAfterMs, type ResumeOptions } from './download';
export { ADB_KEY_LINE, GLASS_PANES_KEY_FINGERPRINT, SERVICES_JAR, adbFingerprint, adbKeyLines, md5, replaceDexString, trustAdbKey, trustFingerprint } from './services';
export { KEPT_APPS, UNUSED_APPS, type UnusedApp } from './unused';
export { APP_DIRS, GLASSWORKS, MIRROR_PROBE, PRIVILEGED, type MirrorApp } from './app';
export { describeMirrorUpdates, parseMirrorUpdates, readMirrorUpdates, type MirrorUpdates } from './updates';
export { GLASSWORKS_IMAGE, GLASS_PANES_IMAGE, MIRROR_ACCESS, MIRROR_IMAGES } from './choices';
