/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import android.content.Intent
import android.os.Looper
import dev.glassworks.engine.vitro.data.HaLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The device's zone, kept by [HaLocale.deviceTimeZone], follows the system's ACTION_TIMEZONE_CHANGED. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HaLocaleZoneWatchTest {
    @After fun tearDown() = HaLocale.deviceZoneChanged(null)

    @Test
    fun theKeptZoneFollowsTheDevicesBroadcast() {
        val app = RuntimeEnvironment.getApplication()
        HaLocale.watchDeviceZone(app)
        HaLocale.deviceTimeZone()
        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, "Europe/Madrid"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Europe/Madrid", HaLocale.deviceTimeZone().id)
    }
}
