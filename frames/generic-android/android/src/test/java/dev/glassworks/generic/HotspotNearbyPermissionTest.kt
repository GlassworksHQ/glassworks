/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.glassworks
import dev.glassworks.platform.Hotspot
import dev.glassworks.setup.HotspotStep
import dev.glassworks.setup.SetupHotspot
import dev.glassworks.setup.SetupOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #197 on Android 13 and newer: the real MainActivity under GenericApp, offline on the setup screen's Wi-Fi step, with
 * "Nearby devices" not granted. Asking for the setup Wi-Fi shows the reason first, with Allow; only Allow opens
 * Android's dialog, for that permission alone; a grant starts the hotspot, and once Android won't ask again the
 * screen says where to allow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = GenericApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class HotspotNearbyPermissionTest {
    private val app = RuntimeEnvironment.getApplication()
    private val nearby = Manifest.permission.NEARBY_WIFI_DEVICES

    /** The device's hotspot: counts the starts, never answers. */
    private var starts = 0
    private val hotspot = object : Hotspot {
        override fun start(onEvent: (Hotspot.Event) -> Unit) { starts++ }
        override fun stop() = Unit
    }

    @Before fun offlineWithNoGrant() {
        shadowOf(app.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        shadowOf(app).denyPermissions(nearby)
        // What KioskService makes on the panel (the service doesn't run here), with its 3-minute wait.
        val main = Handler(Looper.getMainLooper())
        app.glassworks.setupHotspot = SetupHotspot(
            hotspot = hotspot,
            connected = { false },
            clock = SystemClock::elapsedRealtime,
            schedule = { delay, run -> val r = Runnable(run); main.postDelayed(r, delay); { main.removeCallbacks(r) } },
        ).also { it.start() }
    }

    @After fun noHotspot() { app.glassworks.setupHotspot = null }

    private fun offline(block: (GeckoPinHarness) -> Unit) =
        GeckoPinHarness(prefs = { it.urlSetting = "http://homeassistant.local:8123" }).use { h ->
            h.progress.onPageStop(h.session, false)
            h.advance(31_000) // past the loading screen's grace: the setup screen's Wi-Fi step
            assertTrue("the setup screen is up", h.setup().isShowing)
            block(h)
        }

    private fun GeckoPinHarness.setup() = rootChildren().filterIsInstance<SetupOverlay>().single()

    private fun GeckoPinHarness.shown(): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v is TextView && v.text.isNotEmpty()) out += v
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(setup())
        return out
    }

    private fun GeckoPinHarness.texts() = shown().map { it.text.toString() }

    private fun GeckoPinHarness.tap(label: String) {
        val b = shown().firstOrNull { it.text.toString() == label && it.hasOnClickListeners() }
        assertTrue("a button '$label' in ${texts()}", b != null)
        b!!.performClick()
        advance(2_500)
    }

    private fun GeckoPinHarness.asked(): List<String>? = shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList()

    /** Android's dialog answers the request the activity made, with its own code and permissions. */
    private fun GeckoPinHarness.answer(result: Int) {
        val asked = shadowOf(activity).lastRequestedPermission
        assertTrue("Android's dialog is open", asked != null)
        permissionDialogAnswered()
        activity.onRequestPermissionsResult(asked!!.requestCode, asked.requestedPermissions, IntArray(asked.requestedPermissions.size) { result })
        advance(2_500)
    }

    private fun GeckoPinHarness.reason() = texts().any { "“Nearby devices” permission" in it && "doesn't use it to find where you are" in it }

    @Test fun theReasonShowsFirstAndAllowAsksForNearbyDevicesAlone() = offline { h ->
        h.tap(HotspotStep.Action.OPEN.label)
        assertTrue("the reason, before Android's dialog: ${h.texts()}", h.reason())
        assertTrue(h.asked().orEmpty().none { it == nearby })
        assertEquals("no hotspot without the permission", 0, starts)

        h.tap(HotspotStep.Action.ALLOW.label)
        assertEquals(listOf(nearby), h.asked())
    }

    @Test fun aGrantStartsTheHotspot() = offline { h ->
        h.tap(HotspotStep.Action.OPEN.label)
        h.tap(HotspotStep.Action.ALLOW.label)
        shadowOf(app).grantPermissions(nearby)
        h.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(1, starts)
    }

    @Test fun onceAndroidWontAskAgainTheScreenSaysWhereToAllowIt() = offline { h ->
        h.tap(HotspotStep.Action.OPEN.label)
        h.tap(HotspotStep.Action.ALLOW.label)
        h.activity.packageManager.let { shadowOf(it).setShouldShowRequestPermissionRationale(nearby, false) }
        h.answer(PackageManager.PERMISSION_DENIED)
        val texts = h.texts()
        assertTrue(texts.toString(), texts.any { "Android won't ask again" in it && "“Nearby devices”" in it && "under Apps" in it })
        assertNull("no Allow left: ${texts}", h.shown().firstOrNull { it.text.toString() == HotspotStep.Action.ALLOW.label && it.hasOnClickListeners() })
        assertEquals(0, starts)
    }
}
