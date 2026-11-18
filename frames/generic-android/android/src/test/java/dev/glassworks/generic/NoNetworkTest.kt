/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.content.Context
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import dev.glassworks.frame.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowInputDevice
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.setup.OfflineText
import dev.glassworks.setup.SetupFlow
import dev.glassworks.setup.SetupOverlay

/**
 * #448: the Mirror (no touchscreen, Android 6) with Home Assistant set by Kiln and no network saved. The real
 * MainActivity under GenericApp, the dashboard's load failing for want of a network. Without touch the setup
 * screen's Wi-Fi step comes up at once, and no screen offers a button. With touch nothing changes. A Wi-Fi
 * state the app can't read is never "Wi-Fi is off".
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
class NoNetworkTest {
    private val app = RuntimeEnvironment.getApplication()
    private val wifi = shadowOf(app.getSystemService(Context.WIFI_SERVICE) as WifiManager)

    /** Kiln's first boot: Home Assistant's address set; Wi-Fi on, no network saved, nothing connected. */
    private fun offline(input: Input, wifiReadable: Boolean = true, block: (GeckoPinHarness) -> Unit) {
        // Keys only (the Mirror's buttons): no touchscreen.
        if (input == Input.NONE) shadowOf(app.getSystemService(InputManager::class.java)).addInputDevice(ShadowInputDevice.makeInputDeviceNamed("gpio-keys"))
        wifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)
        wifi.setAccessWifiStatePermission(wifiReadable) // unreadable: every read throws a SecurityException
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        GeckoPinHarness(prefs = { it.urlSetting = "http://homeassistant.local:8123" }).use { h ->
            assertEquals("the frame's input", input, h.setup().input)
            h.progress.onPageStop(h.session, false)
            h.advance(2_500) // a setup tick
            block(h)
        }
    }

    private fun GeckoPinHarness.setup() = rootChildren().filterIsInstance<SetupOverlay>().single()

    @Test fun withoutTouchTheWifiStepComesUpAtOnceAndNothingHasAButton() = offline(Input.NONE) { h ->
        assertTrue("the setup screen is up", h.setup().isShowing)
        assertEquals(SetupFlow.WIFI, h.setup().model?.step)
        assertFalse(h.loadingTexts().toString(), OfflineText.SETUP_ACTION in h.loadingTexts())
    }

    @Test fun withTouchTheLoadingScreenKeepsItsButton() = offline(Input.TOUCH) { h ->
        assertFalse("the 30 s grace, as before", h.setup().isShowing)
        val texts = h.loadingTexts()
        assertTrue(texts.toString(), OfflineText.SETUP_ACTION in texts)
        assertTrue(texts.toString(), texts.any { it.startsWith(OfflineText.NO_NETWORK_TITLE) })
    }

    @Test fun aWifiStateTheAppCantReadIsNotWifiOff() {
        offline(Input.TOUCH, wifiReadable = false) { h ->
            val texts = h.loadingTexts()
            assertFalse(texts.toString(), texts.any { it.startsWith(OfflineText.WIFI_OFF_TITLE) })
            assertTrue(texts.toString(), texts.any { it.startsWith(OfflineText.NO_NETWORK_TITLE) })
            assertTrue(h.logs("Glassworks").toString(), h.logs("Glassworks").any { it.startsWith("Offline: Wi-Fi java.lang.SecurityException") })
        }
    }
}
