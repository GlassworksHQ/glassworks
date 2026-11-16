// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package sh.edm.s1kiosk;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;

/**
 * The actions, started over adb only (the manifest makes callers hold WRITE_SECURE_SETTINGS):
 * am start -n sh.edm.s1kiosk/.ProbeAction --es action update|settings|reboot|allow-adb-key [--es apk <path>]
 * [--es key <adb public key line>].
 * Each one logs a single RESULT line, the update's from InstallResult once Android answers.
 */
public class ProbeAction extends Activity {
    /** A Global setting every image has (SettingsProvider loads it on first boot); written back unchanged. */
    static final String SETTING = Settings.Global.AUTO_TIME;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = String.valueOf(getIntent().getStringExtra("action"));
        try {
            switch (action) {
                case "update": update(getIntent().getStringExtra("apk")); break;
                case "settings": settings(); break;
                case "reboot": reboot(); break;
                case "allow-adb-key": allowAdbKey(getIntent().getStringExtra("key")); break;
                default: Log.i(Probe.TAG, Probe.result(action, false, "unknown action: use update, settings, reboot or allow-adb-key"));
            }
        } catch (Exception e) {
            Log.i(Probe.TAG, Probe.result(action, false, e.toString()));
        }
        finish();
    }

    private void update(String path) throws IOException {
        if (path == null) {
            Log.i(Probe.TAG, Probe.result("update", false, "no --es apk <path>"));
            return;
        }
        File apk = new File(path);
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("probe.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent done = new Intent(this, InstallResult.class);
            PendingIntent receiver = PendingIntent.getBroadcast(this, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            Log.i(Probe.TAG, "STEP update committing session " + id + " (" + apk.length() + " bytes from " + path + ")");
            session.commit(receiver.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    private void settings() {
        ContentResolver cr = getContentResolver();
        String value = Settings.Global.getString(cr, SETTING);
        if (value == null) {
            Log.i(Probe.TAG, Probe.result("settings", false, "global " + SETTING + " is unset, nothing written"));
            return;
        }
        try {
            boolean ok = Settings.Global.putString(cr, SETTING, value);
            Log.i(Probe.TAG, Probe.result("settings", ok, "global " + SETTING + "=" + value + " written back" + (ok ? "" : ": putString returned false")));
        } catch (SecurityException e) {
            Log.i(Probe.TAG, Probe.result("settings", false, "SecurityException: " + e.getMessage()));
        }
    }

    /**
     * IUsbManager.allowUsbDebugging(boolean alwaysAllow, String publicKey) (Android 6.0.1_r81:
     * IUsbManager.aidl:103; UsbService.java:329 enforces MANAGE_USB), by reflection, alwaysAllow = true.
     * The call is asynchronous and returns nothing: UsbDebuggingManager (:236) writes the key to
     * /data/misc/adb/adb_keys only when its fingerprint matches the computer adb is asking about.
     * So OK here means "Android took the call", and adb_keys says what it did.
     * There is deliberately no clearUsbDebuggingKeys: it would remove the root's own key, and the
     * Mirror has no touchscreen to allow it again.
     */
    private void allowAdbKey(String key) throws ReflectiveOperationException {
        String problem = Probe.adbKeyProblem(key);
        if (problem != null) {
            Log.i(Probe.TAG, Probe.result("allow-adb-key", false, problem));
            return;
        }
        String fp = Probe.keyFingerprint(key);
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "usb");
            Object usb = Class.forName("android.hardware.usb.IUsbManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
            usb.getClass().getMethod("allowUsbDebugging", boolean.class, String.class).invoke(usb, true, key.trim());
            Log.i(Probe.TAG, Probe.adbKeySent(fp));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Log.i(Probe.TAG, Probe.adbKeyFailed(fp, cause));
        }
    }

    private void reboot() {
        Log.i(Probe.TAG, "STEP reboot calling PowerManager.reboot(null)");
        ((PowerManager) getSystemService(POWER_SERVICE)).reboot(null);
        Log.i(Probe.TAG, Probe.result("reboot", false, "PowerManager.reboot returned"));
    }
}
