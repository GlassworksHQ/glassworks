export {
  HIDDEN_HOMES, PREVIOUS_HOME, checkMirror, installMirror, parseDfFreeMb, parsePreferredHome, preferredHome, undoMirror,
  type AppVersion, type Check, type HomeWait, type Installed, type MirrorOptions, type MirrorPhase, type MirrorReport, type Removed,
} from './recipe';
export { API_KEY_HANDOVER_PROP, DEVICE_MAC_PROP, FRAMEWORK_OVERLAY, GLASS_PANES, IMAGE_KIOSK, LABELS, SETTINGS_OVERLAY, apkLibs, buildUserdata, checkMirrorApk, macFromSerial, patchSystem, setProp, wpaPsk, wpaSupplicantConf, type SystemPatch } from './image';
export {
  GLASS_PANES_USERDATA, MIRROR_FASTBOOT, MIRROR_ROTATION, displayRotation, setPortrait, focusedActivity, makeOnlyHome, OTHER_HOMES, SYSTEM_BYTES, USERDATA_BYTES, WrongImage, buildImages, checkDownload, checkFastboot, firstBoot, flashImages, loadSystem,
  type FastbootLike, type FastbootReport, type FirstBoot, type HomeCheck, type Loaded, type PrepareStep,
} from './flash';

/** The outside work each step stands on (kiln-core StepCredits; ids in frame.json `credits`): the Mirror's Android is Glass Panes. */
export const credits = { image: ['glass-panes-image'] };
export { IMAGE_PROXY, MAX_RETRY_AFTER_MS, ProxyError, resumableBody, retryAfterMs, type ResumeOptions } from './download';
export { ADB_KEY_LINE, GLASS_PANES_KEY_FINGERPRINT, IMAGE_OWNER_FINGERPRINT, SERVICES_JAR, adbFingerprint, adbKeyLines, md5, replaceDexString, trustAdbKey, trustFingerprint } from './services';
export { KEPT_APPS, UNUSED_APPS, type UnusedApp } from './unused';
export { APP_DIRS, GLASSWORKS, MIRROR_PROBE, PRIVILEGED, type MirrorApp } from './app';
export { describeMirrorUpdates, isDeviceOwner, parseMirrorUpdates, readMirrorUpdates, type MirrorUpdates } from './updates';
export { GLASSWORKS_IMAGE, GLASSWORKS_IN_PLACE, GLASS_PANES_IMAGE, MIRROR_ACCESS, MIRROR_IMAGES } from './choices';
export { ADB_KEYS, WIFI_CONF, WIFI_TEMPLATE, saveWifi, setAdbKeys, setImageKey } from './image';
export { systemWithKey, userdataWithKey, userdataWithWifi, wifiOn, wifiTemplate, within } from './wifi';
export {
  MAX_RAW_CHUNK_BLOCKS, SPARSE_BLOCK_SIZE, START_CONFIRMATION_LOG_STRING, STOCK_BLOAT_PACKAGES, STOCK_BOOTANIMATION_BYTES,
  STOCK_BUNDLE_MAGIC, STOCK_SERVICES_JAR_PATH, STOCK_SYSTEM_PARTITION_BYTES, buildSurgicalSparseImage, decodeStockBundle,
  encodeStockBundle, hideStockBloat, padZipToExactLength, patchStockServicesJar, unhideStockBloat,
  type DecodedStockBundle, type DeviceOwnerPatch, type FiemapExtent, type PatchStockServicesJarOptions,
  type PatchedServicesJar, type StockApPatch, type StockBloatChange, type StockBundleMeta,
} from './stock';
export {
  BundleRefused, ORIGINAL_JAR, RESTORE_SPARSE, STOCK_PROBE_BLE, STOCK_PROBE_LAN, bundleExtents, checkNotOdexed, installInPlace, mirrorInPlace, restoredFiles,
} from './inplace';
export {
  DEFAULT_PROBE_APP_PACKAGE, DEFAULT_PROBE_APP_UID, STAGE_SECRET_BYTES, STOCK_PROBE_ASSETS, STOCK_PROBE_LABELS, buildPackagesXml, buildStageUserdata,
  buildStockProbeUserdata, validateStockAdbKey, type ProbeAppPayload, type StockProbeUserdataOptions,
} from './stage';
