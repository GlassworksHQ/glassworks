/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import android.content.Context

/** Setup's answers to the device's own steps from #403 on, in a file of their own (`setup`). Nothing secret. */
class SetupPrefs(context: Context) {
    private val sp = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    /** This device was answered: Continue or Not now, or the device was set up before the step (ThisDeviceStep). */
    var thisDeviceAnswered: Boolean
        get() = sp.getBoolean("this_device_answered", false)
        set(v) = sp.edit().putBoolean("this_device_answered", v).apply()

    /** This device had its once-per-install check (GlassworksRuntime.deviceSetupSteps). */
    var thisDeviceMigrated: Boolean
        get() = sp.getBoolean("this_device_migrated", false)
        set(v) = sp.edit().putBoolean("this_device_migrated", v).apply()

    /** A factory reset (#33): forgets every answer, and keeps only that the once-per-install check ran. False when not written. */
    fun reset(): Boolean = sp.edit().clear().putBoolean("this_device_migrated", thisDeviceMigrated).commit()
}
