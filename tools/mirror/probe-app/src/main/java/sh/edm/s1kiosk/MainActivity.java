// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package sh.edm.s1kiosk;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The home screen: what Android made of this app, on screen and in logcat. It reads no extras. */
public class MainActivity extends Activity {
    static final String[] PERMISSIONS = {
        "android.permission.INSTALL_PACKAGES",
        "android.permission.REBOOT",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.MANAGE_USB",
    };

    private TextView text;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        text.setLineSpacing(0, 1.3f);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(text);
        setContentView(scroll);
        // creates /sdcard/Android/data/sh.edm.s1kiosk/files, the fallback place for the update APK
        getExternalFilesDir(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StringBuilder screen = new StringBuilder("Glassworks probe\n\n");
        for (String line : facts(this)) {
            Log.i(Probe.TAG, line);
            screen.append(line.substring("FACT ".length())).append('\n');
        }
        text.setText(screen);
    }

    static List<String> facts(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        ApplicationInfo ai = c.getApplicationInfo();
        try {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), 0);
            out.add(Probe.fact("version", pi.versionName + " (code " + pi.versionCode + ")"));
        } catch (PackageManager.NameNotFoundException e) {
            out.add(Probe.fact("version", "unknown"));
        }
        out.add(Probe.fact("system", yesNo((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)));
        out.add(Probe.fact("updated-system-app", yesNo((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)));
        out.add(Probe.fact("privileged", Probe.privileged(hiddenInt(ai, "privateFlags"), hiddenInt(null, "PRIVATE_FLAG_PRIVILEGED"))));
        out.add(Probe.fact("code-path", ai.sourceDir));
        for (String p : PERMISSIONS) {
            boolean granted = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            String level;
            try {
                level = Probe.protection(pm.getPermissionInfo(p, 0).protectionLevel);
            } catch (PackageManager.NameNotFoundException e) {
                level = "undefined";
            }
            out.add(Probe.fact("perm." + p.substring("android.permission.".length()), (granted ? "granted" : "denied") + " " + level));
        }
        return out;
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    /** An ApplicationInfo int field that isn't public API; on == null, a static one. Null if it isn't there. */
    private static Integer hiddenInt(ApplicationInfo on, String name) {
        try {
            return ApplicationInfo.class.getField(name).getInt(on);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
