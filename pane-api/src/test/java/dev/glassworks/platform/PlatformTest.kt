/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The first API level each query is true on. These numbers are written out here on purpose: they
 * are the boundaries the app had before the checks moved, so a changed boundary in [PlatformApi]
 * fails this table.
 */
class PlatformTest {
    private val queries: List<Triple<String, Int, (PlatformApi) -> Boolean>> = listOf(
        Triple("hasDefaultNetworkCallback", 24) { it.hasDefaultNetworkCallback },
        Triple("hasLocaleList", 24) { it.hasLocaleList },
        Triple("hasSetProgressAction", 24) { it.hasSetProgressAction },
        Triple("hasProcessStartTime", 24) { it.hasProcessStartTime },
        Triple("hasLogcatEpoch", 24) { it.hasLogcatEpoch },
        Triple("hasIsrgRootX1", 25) { it.hasIsrgRootX1 },
        Triple("hasActivityManagerGetService", 26) { it.hasActivityManagerGetService },
        Triple("hasNotificationChannels", 26) { it.hasNotificationChannels },
        Triple("hasForegroundServiceStart", 26) { it.hasForegroundServiceStart },
        Triple("hasPerAppUnknownSources", 26) { it.hasPerAppUnknownSources },
        Triple("hasDisplayCutoutMode", 28) { it.hasDisplayCutoutMode },
        Triple("hasSigningInfo", 28) { it.hasSigningInfo },
        Triple("hasLongVersionCode", 28) { it.hasLongVersionCode },
        Triple("hasForegroundServiceTypes", 29) { it.hasForegroundServiceTypes },
        Triple("hasSettingsPanel", 29) { it.hasSettingsPanel },
        Triple("hasFontBuilder", 29) { it.hasFontBuilder },
        Triple("hasTypefaceBuilder", 26) { it.hasTypefaceBuilder },
        Triple("hasFocusableAuto", 26) { it.hasFocusableAuto },
        Triple("hasDescendantInvalidated", 26) { it.hasDescendantInvalidated },
        Triple("hasAccessibilityPaneTitle", 28) { it.hasAccessibilityPaneTitle },
        Triple("hasWindowInsetsController", 30) { it.hasWindowInsetsController },
        Triple("hasStateDescription", 30) { it.hasStateDescription },
        Triple("hasSystemCameraIndicator", 31) { it.hasSystemCameraIndicator },
        Triple("canInstallWithoutUserAction", 31) { it.canInstallWithoutUserAction },
        Triple("hasApplicationExitInfo", 30) { it.hasApplicationExitInfo },
        Triple("hasBootCount", 24) { it.hasBootCount },
        Triple("foregroundServiceTypesNeedTheirPermission", 34) { it.foregroundServiceTypesNeedTheirPermission },
        Triple("hasSoftApConfiguration", 30) { it.hasSoftApConfiguration },
        Triple("hasDockPowerSource", 33) { it.hasDockPowerSource },
    )

    @Test
    fun `the target a silent self-update needs, per Android`() {
        val docs = mapOf(31 to 29, 32 to 29, 33 to 30, 34 to 31, 35 to 33, 36 to 34, 37 to 35)
        for (level in 21..30) assertEquals("API $level", null, PlatformApi(level).silentInstallMinTarget)
        docs.forEach { (level, target) -> assertEquals("API $level", target, PlatformApi(level).silentInstallMinTarget) }
    }

    @Test
    fun `the setup hotspot - hidden API below Android 8, then local-only, and the permission each needs`() {
        for (level in 21..40) {
            assertEquals("kind at API $level", if (level >= 26) HotspotKind.LOCAL_ONLY else HotspotKind.HIDDEN_AP, PlatformApi(level).hotspotKind)
            val permission = when {
                level >= 33 -> android.Manifest.permission.NEARBY_WIFI_DEVICES
                level >= 26 -> android.Manifest.permission.ACCESS_FINE_LOCATION
                else -> null
            }
            assertEquals("permission at API $level", permission, PlatformApi(level).hotspotPermission)
            assertEquals("location on at API $level", level in 26..32, PlatformApi(level).hotspotNeedsLocationOn)
            assertEquals("an app turns Wi-Fi on at API $level", level < 29, PlatformApi(level).appCanEnableWifi)
        }
    }

    @Test
    fun `each query flips at its boundary and nowhere else`() {
        for ((name, firstLevel, query) in queries) {
            for (level in 21..40) {
                assertEquals("$name at API $level", level >= firstLevel, query(PlatformApi(level)))
            }
        }
    }

    @Test
    fun `a PendingIntent is mutable from Android 12 only`() {
        assertEquals(0, PlatformApi(30).mutablePendingIntentFlag)
        assertEquals(PendingIntent.FLAG_MUTABLE, PlatformApi(31).mutablePendingIntentFlag)
        assertEquals(PendingIntent.FLAG_MUTABLE, PlatformApi(33).mutablePendingIntentFlag)
    }

    @Test
    fun `the apiLevel is the one given`() {
        assertEquals(29, PlatformApi(29).apiLevel)
    }
}

/** [Platform] reads the real `Build.VERSION`, and the Intent shim returns the extra. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlatformOnDeviceTest {
    @Test
    fun `Platform answers for the running Android`() {
        assertEquals(Build.VERSION.SDK_INT, Platform.apiLevel)
        assertEquals(34, Platform.apiLevel)
        assertEquals(PendingIntent.FLAG_MUTABLE, Platform.mutablePendingIntentFlag)
        assertEquals(Build.SUPPORTED_ABIS.toList(), Platform.supportedAbis)
    }

    @Test
    fun `intentExtra returns the nested Intent`() {
        val inner = Intent("test.INNER")
        val outer = Intent("test.OUTER").putExtra(Intent.EXTRA_INTENT, inner)
        assertEquals("test.INNER", Platform.intentExtra(outer, Intent.EXTRA_INTENT)?.action)
        assertEquals(null, Platform.intentExtra(Intent("test.EMPTY"), Intent.EXTRA_INTENT))
    }
}

/**
 * The fallbacks on a real Android 6 framework (Robolectric's API 23 jar), where the newer methods don't exist: a
 * call that reached one would throw NoSuchMethodError here (#198, the legacy build).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class PlatformApi23Test {
    @Test
    fun `the default-network callback falls back to an internet request on Android 6`() {
        assertEquals(23, Platform.apiLevel)
        val app = RuntimeEnvironment.getApplication()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {}
        Platform.registerDefaultNetworkCallback(cm, cb)
        assertTrue(shadowOf(cm).networkCallbacks.contains(cb))
        cm.unregisterNetworkCallback(cb)
    }

    /** #198: Android 6 has no Process.getStartElapsedRealtime; the start comes from /proc/self/stat (ticks since boot). */
    @Test
    fun `the process start comes from proc self stat on Android 6`() {
        val stat = "4242 (sh.edm.s1kiosk (x)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 4200 20 21"
        assertEquals("4200 ticks at 100 Hz", 42_000L, Platform.processStartElapsedMs({ stat }, { 100L }))
        assertEquals("unknown, not a guess", null, Platform.processStartElapsedMs({ null }, { 100L }))
        assertEquals(null, Platform.processStartElapsedMs({ "4242 (short) S 1 2" }, { 100L }))
        assertEquals("no tick rate", null, Platform.processStartElapsedMs({ stat }, { 0L }))
    }
}
