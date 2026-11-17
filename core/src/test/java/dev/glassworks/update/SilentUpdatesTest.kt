/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.Manifest
import android.app.Application
import dev.glassworks.update.SilentUpdates.Facts
import dev.glassworks.update.SilentUpdates.Root
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
 * Whether updates install with nobody at the screen (#422), route by route, in the order the app takes them, and
 * read from Robolectric's Android 6 (the Mirror's legacy build) and 14.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class SilentUpdatesTest {
    private val android14 = Facts(Root.NONE, systemApp = false, deviceOwner = false, android = "14", minTarget = 31, target = 35, unknownApps = true)
    private val android10 = android14.copy(android = "10", minTarget = null, unknownApps = false)

    @Test fun eachRouteAndWhatStopsIt() {
        val cases = listOf(
            "the S1's boot image service" to (android10.copy(root = Root.PRESENT) to SilentUpdates.RootService),
            "a privileged system app" to (android10.copy(systemApp = true) to SilentUpdates.SystemApp),
            "a device owner" to (android10.copy(deviceOwner = true) to SilentUpdates.DeviceOwner),
            "Android 12+ updating itself" to (android14 to SilentUpdates.SelfUpdate),
            "an S1 boot image from before the service" to (android10.copy(root = Root.MISSING) to SilentUpdates.NoRootService),
            "Android 6 to 11" to (android10 to SilentUpdates.OldAndroid("10")),
            "Install unknown apps off" to (android14.copy(unknownApps = false) to SilentUpdates.UnknownAppsOff),
            "a target too old for this Android" to (android14.copy(android = "17", minTarget = 35, target = 34) to SilentUpdates.TargetTooLow("17", 35, 34)),
        )
        cases.forEach { (what, case) -> assertEquals(what, case.second, SilentUpdates.of(case.first)) }
    }

    @Test fun theRouteTheAppTakesComesFirst() {
        assertEquals("the root service installs before PackageInstaller is asked", SilentUpdates.RootService,
            SilentUpdates.of(android14.copy(root = Root.PRESENT, unknownApps = false)))
        assertEquals("a system app needs no unknown-apps permission", SilentUpdates.SystemApp,
            SilentUpdates.of(android14.copy(systemApp = true, unknownApps = false, target = 20)))
        assertEquals("an old boot image on an owned device still installs silently", SilentUpdates.DeviceOwner,
            SilentUpdates.of(android10.copy(root = Root.MISSING, deviceOwner = true)))
        assertEquals("allowing unknown apps wouldn't help an old target", SilentUpdates.TargetTooLow("17", 35, 30),
            SilentUpdates.of(android14.copy(android = "17", minTarget = 35, target = 30, unknownApps = false)))
    }

    @Test fun theWordsForHomeAssistantAndTheSettingsRow() {
        assertEquals("Yes: the app is the device owner", SilentUpdates.DeviceOwner.state)
        assertEquals("Yes: the app is the device owner", SilentUpdates.DeviceOwner.detail)
        val old = SilentUpdates.OldAndroid("9")
        assertEquals("No: Android 9 asks for a tap on each update", old.state)
        assertEquals("No: Android 9 asks for a tap on each update. ${SilentUpdates.DEVICE_OWNER_FIX}", old.detail)
        assertTrue("Home Assistant keeps a state up to 255 characters",
            listOf(SilentUpdates.NoRootService, SilentUpdates.UnknownAppsOff, SilentUpdates.TargetTooLow("17", 35, 34)).all { it.state.length <= 255 })
    }

    @Test fun aPrivilegedAppInstallsWithoutATapOnAndroid6() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.INSTALL_PACKAGES)
        assertEquals(SilentUpdates.SystemApp, SilentUpdates.read(app, Root.NONE))
    }

    @Test fun anInstalledAppAsksForATapOnAndroid6() {
        val s = SilentUpdates.read(RuntimeEnvironment.getApplication(), Root.NONE)
        assertEquals(SilentUpdates.OldAndroid("6.0.1"), s)
        assertFalse(s.silent)
    }

    @Test @Config(sdk = [34])
    fun android14ReadsInstallUnknownAppsAndTheAppsTarget() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.targetSdkVersion = 35
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertEquals(SilentUpdates.UnknownAppsOff, SilentUpdates.read(app, Root.NONE))
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(SilentUpdates.SelfUpdate, SilentUpdates.read(app, Root.NONE))
        app.applicationInfo.targetSdkVersion = 30
        assertEquals(SilentUpdates.TargetTooLow("14", 31, 30), SilentUpdates.read(app, Root.NONE))
    }
}
