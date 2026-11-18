/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.KeyguardManager
import android.content.Context

/**
 * Whether the device has a PIN, pattern or password (#522). With one, Android locks the device when the panel turns its
 * screen off, and someone has to unlock it by hand. False when Android can't say.
 */
object ScreenLock {
    fun secure(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }.getOrDefault(false)
}
