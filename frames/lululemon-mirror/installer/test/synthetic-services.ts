// A synthetic stock services.jar for tests: a real DEX 035 built with javac + d8, shaped like stock AOSP 6.0.1's
// UsbDebuggingManager and DevicePolicyManagerService, and stock 1.24.0's MirrorApService. Needs an Android SDK (d8); callers skip without one (HAS_D8).
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';

// ---------------------------------------------------------------------------
// Android SDK tool discovery (optional; the DEX tests skip without it)
// ---------------------------------------------------------------------------

function findTool(name: string): string | undefined {
  for (const sdk of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, join(homedir(), 'Library/Android/sdk')]) {
    if (!sdk || !existsSync(join(sdk, 'build-tools'))) continue;
    const found = execFileSync('find', [join(sdk, 'build-tools'), '-name', name], { encoding: 'utf8' })
      .split(/\r?\n/).filter(Boolean).sort((a, b) => a.localeCompare(b, 'en', { numeric: true }));
    if (found.length) return found.at(-1);
  }
  return undefined;
}

export const D8 = findTool('d8');
export const DEXDUMP = findTool('dexdump');
export const HAS_D8 = Boolean(D8);

export const START_CONFIRMATION_LOG_STRING =
  'unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component ';

/** Which `MirrorApService` the synthetic jar carries (see buildSyntheticStockServicesJar). */
export type MirrorApVariant = 'stock' | 'none' | 'unexpected';
/** Which `UsbService` it carries: AOSP 6.0.1's shape, none, or an `allowUsbDebugging` that checks two permissions. */
export type UsbServiceVariant = 'stock' | 'none' | 'unexpected';

/**
 * Builds a real DEX 035 + ZIP `services.jar` with `UsbDebuggingManager` (so the startConfirmation
 * patch runs) AND `DevicePolicyManagerService` shaped like stock AOSP 6.0.1 (so both Device Owner
 * fixes run): `<init>` sets `mHasFeature` from `PackageManager.hasSystemFeature(device_admin)`, and
 * `setDeviceOwner` calls `IBackupManager.setBackupServiceActive(0, false)`. With `mirrorAp: 'stock'`
 * (the default) it also has a `MirrorApService` shaped like stock 1.24.0's: `create(Context)`, and a
 * package-private `systemReady()` that registers the `ACTION_START_HOST_APD` receiver and starts the
 * `mapd` connector thread (so the stock-hotspot patch runs); `'none'` leaves the class out, and
 * `'unexpected'` gives `systemReady()` a body that registers no `ACTION_START_HOST_APD` receiver.
 * Android framework types are provided as local stubs with the exact AOSP descriptors. Each variant is
 * built once per run (d8 is slow); callers get their own copy.
 */
export function buildSyntheticStockServicesJar(opts: { mirrorAp?: MirrorApVariant; usbService?: UsbServiceVariant } = {}): Uint8Array {
  const mirrorAp = opts.mirrorAp ?? 'stock';
  const usbService = opts.usbService ?? 'stock';
  const id = `${mirrorAp}/${usbService}`;
  const cached = SYNTHETIC_JARS.get(id);
  if (cached) return cached.slice();
  const jar = buildSyntheticJarUncached(mirrorAp, usbService);
  SYNTHETIC_JARS.set(id, jar);
  return jar.slice();
}

const SYNTHETIC_JARS = new Map<string, Uint8Array>();

function buildSyntheticJarUncached(mirrorAp: MirrorApVariant, usbService: UsbServiceVariant): Uint8Array {
  if (!D8) throw new Error('buildSyntheticStockServicesJar requires d8 (guard call sites with it.skipIf(!HAS_D8))');
  const dir = mkdtempSync(join(tmpdir(), 'gw-stock-dex-'));
  try {
    const write = (rel: string, src: string): void => {
      const p = join(dir, 'src', rel);
      execFileSync('mkdir', ['-p', p.slice(0, p.lastIndexOf('/'))]);
      writeFileSync(p, src);
    };
    write('android/content/pm/PackageManager.java', `package android.content.pm;
public class PackageManager { public boolean hasSystemFeature(String name) { return false; } }
`);
    write('android/content/Context.java', `package android.content;
import android.content.pm.PackageManager;
public class Context {
  public PackageManager getPackageManager() { return null; }
  public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
  public void enforceCallingOrSelfPermission(String permission, String message) {}
}
`);
    write('android/content/Intent.java', `package android.content;
public class Intent {}
`);
    write('android/content/BroadcastReceiver.java', `package android.content;
public class BroadcastReceiver {}
`);
    write('android/content/IntentFilter.java', `package android.content;
public class IntentFilter { public IntentFilter(String action) {} }
`);
    write('android/app/backup/IBackupManager.java', `package android.app.backup;
public interface IBackupManager { void setBackupServiceActive(int userHandle, boolean makeActive); }
`);
    write('com/android/server/usb/UsbDebuggingManager.java', `package com.android.server.usb;
public class UsbDebuggingManager {
  public boolean allowed;
  public String allowedKey;
  public boolean denied;
  public String lastLog;
  public void allowUsbDebugging(boolean alwaysAllow, String publicKey) { this.allowed = alwaysAllow; this.allowedKey = publicKey; }
  public void denyUsbDebugging() { this.denied = true; }
  private void startConfirmation(String key, String fingerprints) {
    String msg = "${START_CONFIRMATION_LOG_STRING}";
    String a = key.substring(0, 1);
    String b = fingerprints.substring(0, 1);
    if (a.equals(b)) { this.lastLog = msg + key + fingerprints + " padding padding padding padding"; }
    else { this.lastLog = msg + fingerprints + key + " extra extra extra extra extra"; }
    this.lastLog = this.lastLog + " more bytecode so insns_size exceeds 30 units " + a + b;
  }
}
`);
    write('com/android/server/devicepolicy/DevicePolicyManagerService.java', `package com.android.server.devicepolicy;
import android.content.Context;
import android.app.backup.IBackupManager;
public class DevicePolicyManagerService {
  public boolean mHasFeature;
  IBackupManager mBackup;
  String owner;
  public DevicePolicyManagerService(Context context) {
    this.mHasFeature = context.getPackageManager().hasSystemFeature("android.software.device_admin");
  }
  public boolean setDeviceOwner(String packageName, String ownerName) {
    mBackup.setBackupServiceActive(0, false);
    this.owner = packageName;
    return true;
  }
}
`);
    // AOSP 6.0.1's UsbService: every call checks MANAGE_USB first (stock 1.24.0's, read with jadx 2026-11-25). The
    // settings class stands in for the rest of system_server, which checks WRITE_SECURE_SETTINGS elsewhere.
    if (usbService !== 'none') {
      const allowBody = usbService === 'stock'
        ? 'mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);'
        : 'mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);\n    mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS", null);';
      write('com/android/server/usb/UsbService.java', `package com.android.server.usb;
import android.content.Context;
public class UsbService {
  private final Context mContext;
  private final UsbDebuggingManager mDeviceManager;
  public UsbService(Context context, UsbDebuggingManager manager) { mContext = context; mDeviceManager = manager; }
  public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
    ${allowBody}
    mDeviceManager.allowUsbDebugging(alwaysAllow, publicKey);
  }
  public void denyUsbDebugging() {
    mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USB", null);
    mDeviceManager.denyUsbDebugging();
  }
}
`);
    }
    // AOSP 6.0.1's AlarmManagerService: its binder is the class's second anonymous class, and setTimeZone checks
    // SET_TIME_ZONE first and setTime SET_TIME (stock 1.24.0's AlarmManagerService$2, read with jadx 2026-11-25).
    write('com/android/server/AlarmManagerService.java', `package com.android.server;
import android.content.Context;
public class AlarmManagerService {
  private final Context mContext;
  private String mZone;
  private long mNow;
  private final Runnable mTick = new Runnable() { public void run() {} };
  final Object mService = new Object() {
    public boolean setTime(long millis) {
      mContext.enforceCallingOrSelfPermission("android.permission.SET_TIME", "setTime");
      mNow = millis;
      return true;
    }
    public void setTimeZone(String tz) {
      mContext.enforceCallingOrSelfPermission("android.permission.SET_TIME_ZONE", "setTimeZone");
      setTimeZoneImpl(tz);
    }
  };
  public AlarmManagerService(Context context) { mContext = context; }
  void setTimeZoneImpl(String tz) { mZone = tz; }
}
`);
    write('com/android/server/SecureSettings.java', `package com.android.server;
import android.content.Context;
public class SecureSettings {
  public static void check(Context context) { context.enforceCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS", null); }
}
`);
    if (mirrorAp !== 'none') {
      const ready = mirrorAp === 'stock'
        ? `mContext.registerReceiver(mWifiStateBroadcastReceiver, new IntentFilter("android.net.wifi.WIFI_STATE_CHANGED"));
    mContext.registerReceiver(mStartHostApBroadcastReceiver, new IntentFilter(ACTION_START_HOST_APD));
    mThread.start();`
        : 'mThread.start();';
      write('com/android/server/MirrorApService.java', `package com.android.server;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
public class MirrorApService {
  public static final String ACTION_START_HOST_APD = "com.mirror.framework.ACTION_START_HOST_APD";
  private final Context mContext;
  private final Thread mThread;
  private final BroadcastReceiver mStartHostApBroadcastReceiver = new BroadcastReceiver();
  private final BroadcastReceiver mWifiStateBroadcastReceiver = new BroadcastReceiver();
  private MirrorApService(Context context) { mContext = context; mThread = new Thread("MapdConnector"); }
  static MirrorApService create(Context context) { return new MirrorApService(context); }
  void systemReady() {
    ${ready}
  }
}
`);
    }
    const sources = [
      'android/content/pm/PackageManager.java', 'android/content/Context.java', 'android/content/Intent.java',
      'android/content/BroadcastReceiver.java', 'android/content/IntentFilter.java',
      'android/app/backup/IBackupManager.java', 'com/android/server/usb/UsbDebuggingManager.java',
      'com/android/server/devicepolicy/DevicePolicyManagerService.java',
      ...(mirrorAp !== 'none' ? ['com/android/server/MirrorApService.java'] : []),
      ...(usbService !== 'none' ? ['com/android/server/usb/UsbService.java'] : []),
      'com/android/server/SecureSettings.java', 'com/android/server/AlarmManagerService.java',
    ].map((r) => join(dir, 'src', r));
    const clsDir = join(dir, 'classes');
    execFileSync('mkdir', ['-p', clsDir]);
    execFileSync('javac', ['-source', '1.8', '-target', '1.8', '-d', clsDir, ...sources]);
    const classFiles = execFileSync('find', [clsDir, '-name', '*.class'], { encoding: 'utf8' }).split(/\r?\n/).filter(Boolean);
    const dexDir = join(dir, 'dex');
    execFileSync('mkdir', ['-p', dexDir]);
    execFileSync(D8, ['--min-api', '23', '--output', dexDir, ...classFiles]);
    const jarOut = join(dir, 'services.jar');
    execFileSync('python3', ['-I', '-c', `
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\\r\\n\\r\\n')
    z.write(sys.argv[2], 'classes.dex')
    z.comment = b' ' * 512
`, jarOut, join(dexDir, 'classes.dex')]);
    return new Uint8Array(readFileSync(jarOut));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** dexdump -d output for one class, from its `Class descriptor :` header to the next class or end. */
