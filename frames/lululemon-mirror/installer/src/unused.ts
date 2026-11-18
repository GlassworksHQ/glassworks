// The image's apps a Mirror never uses, which Kiln leaves out of system (patchSystem). On the unit they
// cost RAM: the Qualcomm and telephony services restart after every lowmemorykiller kill, around 25
// times in 30 minutes each, while the Mirror sat at "critical" (frames/lululemon-mirror/research/mirror-memory.md). The list is what
// the root hid on the unit on 2026-11-18 (pm hide), which took free RAM from about 60 MB to 205 MB
// (one sample). Folders read from the pinned image 2 with tools/mirror/list-apps.ts (2026-11-18).
// The way back is Kiln's restore: the plain Glass Panes images bring every app back.

/** An app Kiln leaves out: its package, and its folder in the pinned image. */
export interface UnusedApp { package: string; dir: string }

export const UNUSED_APPS: readonly UnusedApp[] = [
  // Telephony front ends: a Mirror has no SIM. The phone stack itself stays (KEPT_APPS).
  { package: 'com.android.smspush', dir: '/app/WAPPushManager' },
  { package: 'com.android.mms', dir: '/priv-app/Mms' },
  { package: 'com.android.mms.service', dir: '/priv-app/MmsService' },
  { package: 'com.android.dialer', dir: '/priv-app/Dialer' },
  { package: 'com.android.stk', dir: '/app/Stk' },
  { package: 'com.android.cellbroadcastreceiver', dir: '/priv-app/CellBroadcastReceiver' },
  { package: 'com.android.calllogbackup', dir: '/priv-app/CallLogBackup' },
  { package: 'com.android.contacts', dir: '/priv-app/Contacts' },
  // Qualcomm diagnostics, modem settings, RCS and tethering.
  { package: 'com.qti.diagservices', dir: '/app/QTIDiagServices' },
  { package: 'com.qualcomm.qualcommsettings', dir: '/app/QualcommSettings' },
  { package: 'com.qualcomm.qti.rcsbootstraputil', dir: '/app/RCSBootstraputil' },
  { package: 'com.qualcomm.qti.tetherservice', dir: '/priv-app/QtiTetherService' },
  { package: 'com.android.qlogging', dir: '/app/BTLogKit' },
  // Apps and demos with nothing to show on a Mirror.
  { package: 'com.android.email', dir: '/app/Email' },
  { package: 'com.android.exchange', dir: '/app/Exchange2' },
  { package: 'com.android.fmradio', dir: '/priv-app/FMRadio' },
  { package: 'com.caf.fmradio', dir: '/app/FM2' },
  { package: 'com.android.music', dir: '/app/Music' },
  { package: 'com.android.gallery3d', dir: '/app/Gallery2' },
  { package: 'com.android.soundrecorder', dir: '/priv-app/SoundRecorder' },
  { package: 'com.android.quicksearchbox', dir: '/app/QuickSearchBox' },
  { package: 'com.android.calendar', dir: '/app/Calendar' },
  { package: 'com.android.deskclock', dir: '/app/DeskClock' },
  { package: 'com.android.calculator2', dir: '/app/ExactCalculator' },
  { package: 'com.android.galaxy4', dir: '/app/Galaxy4' },
  { package: 'com.android.noisefield', dir: '/app/NoiseField' },
  { package: 'com.android.phasebeam', dir: '/app/PhaseBeam' },
  { package: 'com.android.wallpaper.holospiral', dir: '/app/HoloSpiralWallpaper' },
  { package: 'com.android.protips', dir: '/app/Protips' },
  { package: 'org.codeaurora.snapcam', dir: '/app/SnapdragonCamera' },
  { package: 'com.cyanogenmod.filemanager', dir: '/app/CMFileManager' },
  { package: 'com.qualcomm.wfd.client', dir: '/app/WfdClient' },
  // Bluetooth and ANT test tools. Bluetooth itself stays.
  { package: 'org.codeaurora.bluetooth.btlogsave', dir: '/app/BTLogSave' },
  { package: 'org.codeaurora.bluetooth.bttestapp', dir: '/app/BTTestApp' },
  { package: 'org.codeaurora.bluetooth.hiddtestapp', dir: '/app/HiddTestApp' },
  { package: 'com.dsi.ant.server', dir: '/app/AntHalService' },
];

/**
 * Apps that stay, though they also restart under memory pressure or look unused: removing them could
 * break the phone stack, typing, the display or the app itself. UNUSED_APPS must never name one.
 */
export const KEPT_APPS: readonly string[] = [
  'com.android.phone', // TeleService: persistent; the telephony stack runs in it
  'com.android.providers.telephony',
  'com.android.server.telecom',
  'com.android.providers.contacts', // other system apps read contacts through it
  'com.android.inputmethod.latin', // the only keyboard, for a USB keyboard or the setup's text fields
  // The display's post-processing front ends (content-adaptive backlight, sunlight visibility, colour).
  // They restart often too, but taking them out could change brightness or colour: kept until measured.
  'com.qualcomm.display', 'com.qualcomm.svi', 'com.qualcomm.cabl', 'com.qti.service.colorservice',
  'com.android.webview',
  'com.android.bluetooth', 'org.codeaurora.bluetooth',
  'com.android.settings', 'com.android.providers.settings',
  'com.android.systemui',
  'com.quicinc.cne.CNEService', // Wi-Fi and mobile connectivity engine: persistent
];
