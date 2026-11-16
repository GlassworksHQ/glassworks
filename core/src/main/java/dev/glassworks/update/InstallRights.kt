/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** What lets the app's own updates install with nobody at the screen. */
object InstallRights {
    /**
     * The app holds `INSTALL_PACKAGES`: a privileged system app. Kiln puts the legacy build in the lululemon
     * Mirror's `/system/priv-app`, and Android 6 grants it then (#198,
     * frames/lululemon-mirror/research/privileged-app.md). Its PackageInstaller sessions then install with no
     * confirmation, on any Android, and an update signed with the same key keeps the permission. Without it, the
     * session waits for a tap (STATUS_PENDING_USER_ACTION), as before.
     */
    fun installsPackages(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
}
