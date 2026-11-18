/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Notification
import dev.glassworks.generic_android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskService

/**
 * The service's notification shows the mark's white silhouette, not the launcher icon. The launcher icon was its
 * small icon until the Mirror's first start (#198, 2026-11-17): an adaptive icon, which Android 6 can't load, so the
 * app crashed. Robolectric can't run this app at API 23 (its minSdk is 26); tools/legacy/apk-icons.py checks the
 * legacy APK's icons for API 23 in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = GenericApp::class)
class NotificationIconTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun theServicesNotificationShowsTheMark() {
        val service = Robolectric.buildService(KioskService::class.java).get()
        val build = dev.glassworks.KioskService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
        val n = build.invoke(service) as Notification
        assertEquals(R.drawable.ic_notification, n.smallIcon.resId)
        assertNotNull(n.smallIcon.loadDrawable(app))
    }
}
