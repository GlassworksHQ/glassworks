/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.setup

import android.Manifest
import dev.glassworks.platform.PlatformApi
import dev.glassworks.setup.HotspotPermission.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen says why before Android's dialog, asks again after a "no", and points to Settings once Android won't ask. */
class HotspotPermissionTest {
    private val nearby = Manifest.permission.NEARBY_WIFI_DEVICES
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION

    private fun step(level: Int, granted: Boolean = false, askedBefore: Boolean = false, androidWouldExplain: Boolean = false) =
        HotspotPermission.step(PlatformApi(level), { granted }, askedBefore) { androidWouldExplain }

    @Test
    fun `Android 13 and newer - the nearby permission, with the reason shown before Android's dialog`() {
        val s = step(33)
        assertTrue(s is Step.Explain)
        s as Step.Explain
        assertEquals(nearby, s.permission)
        assertTrue(s.why, "Nearby devices" in s.why && "doesn't use it to find where you are" in s.why)
    }

    @Test
    fun `Android 8 to 12 - location, with its reason`() {
        val s = step(29) as Step.Explain
        assertEquals(fine, s.permission)
        assertTrue("doesn't use your location" in s.why)
    }

    @Test
    fun `after one no, the reason shows again and the button asks again`() {
        assertTrue(step(34, askedBefore = true, androidWouldExplain = true) is Step.Explain)
    }

    @Test
    fun `once Android won't ask again, the screen says where to allow it`() {
        val s = step(34, askedBefore = true, androidWouldExplain = false)
        assertTrue(s is Step.Blocked)
        assertTrue((s as Step.Blocked).why.contains("Android's settings"))
    }

    @Test
    fun `granted, or Android 6 and 7, nothing to ask`() {
        assertEquals(Step.Ready, step(33, granted = true))
        assertEquals(Step.Ready, step(29, granted = true))
        for (level in 23..25) assertEquals(Step.Ready, step(level))
    }
}
