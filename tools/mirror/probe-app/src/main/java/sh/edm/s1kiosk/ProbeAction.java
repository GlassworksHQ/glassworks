// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package sh.edm.s1kiosk;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The actions, started over adb only (the manifest makes callers hold WRITE_SECURE_SETTINGS):
 * am start -n sh.edm.s1kiosk/.ProbeAction --es action update|settings|reboot [--es apk <path>].
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
                default: Log.i(Probe.TAG, Probe.result(action, false, "unknown action: use update, settings or reboot"));
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

    private void reboot() {
        Log.i(Probe.TAG, "STEP reboot calling PowerManager.reboot(null)");
        ((PowerManager) getSystemService(POWER_SERVICE)).reboot(null);
        Log.i(Probe.TAG, Probe.result("reboot", false, "PowerManager.reboot returned"));
    }
}
