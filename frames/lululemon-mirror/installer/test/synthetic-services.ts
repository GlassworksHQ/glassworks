// A synthetic stock services.jar for tests: a real DEX 035 built with javac + d8, shaped like stock AOSP 6.0.1's
// UsbDebuggingManager and DevicePolicyManagerService. Needs an Android SDK (d8); callers skip without one (HAS_D8).
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

/**
 * Builds a real DEX 035 + ZIP `services.jar` with `UsbDebuggingManager` (so the startConfirmation
 * patch runs) AND `DevicePolicyManagerService` shaped like stock AOSP 6.0.1 (so both Device Owner
 * fixes run): `<init>` sets `mHasFeature` from `PackageManager.hasSystemFeature(device_admin)`, and
 * `setDeviceOwner` calls `IBackupManager.setBackupServiceActive(0, false)`. Android framework types
 * are provided as local stubs with the exact AOSP descriptors.
 */
export function buildSyntheticStockServicesJar(): Uint8Array {
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
public class Context { public PackageManager getPackageManager() { return null; } }
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
    const sources = [
      'android/content/pm/PackageManager.java', 'android/content/Context.java',
      'android/app/backup/IBackupManager.java', 'com/android/server/usb/UsbDebuggingManager.java',
      'com/android/server/devicepolicy/DevicePolicyManagerService.java',
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
