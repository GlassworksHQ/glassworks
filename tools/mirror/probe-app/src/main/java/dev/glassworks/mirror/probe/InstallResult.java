// SPDX-License-Identifier: MIT
// Copyright (c) 2026 aguilaair
package dev.glassworks.mirror.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * PackageInstaller's answer to the update. After a successful update this runs in the new version:
 * Android stops the old process to replace it, then delivers the result to the new one.
 */
public class InstallResult extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Log.i(Probe.TAG, Probe.installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // the Mirror has no touchscreen to confirm on: drop the session instead of asking
            int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (id != -1) context.getPackageManager().getPackageInstaller().abandonSession(id);
        }
    }
}
