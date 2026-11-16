/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.Manifest
import android.app.Application
import dev.glassworks.frame.GenericAndroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The legacy build as a privileged system app on Android 6 (#198): the Mirror's /system/priv-app copy holds
 * INSTALL_PACKAGES, so its updates install without a tap (frames/lululemon-mirror/research/privileged-app.md),
 * and the Device page says so. Without it, Android 6 installs without a tap only for a device owner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class InstallRightsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun aPrivilegedAppInstallsWithoutATap() {
        shadowOf(app).grantPermissions(Manifest.permission.INSTALL_PACKAGES)
        assertTrue(InstallRights.installsPackages(app))
        assertEquals("Available: the app is a system app with the install permission", GenericAndroid.updates(app))
    }

    @Test fun anInstalledAppAsksForATapOnAndroid6() {
        assertFalse(InstallRights.installsPackages(app))
        assertEquals("Not on this Android: it needs Android 12 or newer, or the app as device owner", GenericAndroid.updates(app))
    }
}
