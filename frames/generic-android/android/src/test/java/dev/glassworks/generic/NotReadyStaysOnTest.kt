/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import dev.glassworks.KioskPrefs
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.setup.SetupOverlay

/**
 * #449: the Mirror (no presence sensor) slept 20 s after boot at night, with the connecting screen up. The real
 * MainActivity under GenericApp, with a 20 s idle timeout (what HA's night mode sets). Until setup is done and the
 * dashboard is up, nothing turns the screen off. Then the timeout works as before.
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
class NotReadyStaysOnTest {
    private val dash = "http://192.0.2.10:8123/"

    // A picked time puts the screen mode to presence, as the Screen page's row does: "always" is the default (#522).
    private fun idle20s(set: (KioskPrefs) -> Unit = {}) = GeckoPinHarness(prefs = { it.idleOffSec = 20; it.screenMode = "presence"; set(it) })

    private fun GeckoPinHarness.sleeps() = logs("Glassworks.Screen").filter { it.startsWith("sleep (idle") }
    private fun GeckoPinHarness.setup() = rootChildren().filterIsInstance<SetupOverlay>().single()

    @Test fun theSetupScreenStaysOn() = idle20s().use { h ->
        h.advance(5_000)
        assertTrue("a fresh panel shows setup", h.setup().isShowing)
        h.advance(120_000)
        assertEquals(emptyList<String>(), h.sleeps())
        assertTrue(h.logs("Glassworks.Screen").toString(), "stay on (not ready)" in h.logs("Glassworks.Screen"))
    }

    /** Online over Wi-Fi: an active network with internet. */
    private fun online() {
        val app = RuntimeEnvironment.getApplication()
        val cm = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        cm.clearAllNetworks()
        val n = ShadowNetwork.newInstance(1)
        val ni = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED)
        cm.addNetwork(n, ni)
        cm.setActiveNetworkInfo(ni)
        cm.setNetworkCapabilities(n, ShadowNetworkCapabilities.newInstance().also { c ->
            shadowOf(c).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(c).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        })
    }

    /** No network at all. */
    private fun offline() {
        val cm = shadowOf(RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java))
        cm.clearAllNetworks()
        cm.setActiveNetworkInfo(null)
    }

    /** #484: a set-up panel that loses Wi-Fi at night shows setup's Wi-Fi step, and still sleeps on its idle timeout. */
    @Test fun aSetUpPanelThatLosesWifiSleepsOnItsTimeout() {
        offline()
        idle20s { it.urlSetting = dash; it.haSeen = true }.use { h ->
            h.advance(35_000)
            assertTrue("offline past the grace: setup shows", h.setup().isShowing)
            assertFalse("Home Assistant added it before", h.setup().needsHomeAssistant)
            h.advance(30_000)
            assertEquals(h.logs("Glassworks.Screen").toString(), listOf("sleep (idle 20s) admin=false"), h.sleeps())
        }
    }

    @Test fun theConnectingScreenStaysOnUntilTheDashboardIsUpThenTheTimeoutCounts() {
        online()
        idle20s { it.urlSetting = dash; it.haSeen = true }.use { h ->
            h.progress.onPageStart(h.session, dash)
            h.advance(120_000)
            assertFalse("set up: no setup screen", h.setup().isShowing)
            assertTrue("still connecting", h.loading.isShowing)
            assertEquals(emptyList<String>(), h.sleeps())

            h.progress.onPageStop(h.session, true)
            h.send(h.connect(dash), org.json.JSONObject("""{"type":"ready","why":"frontend"}"""), replies = 0)
            h.advance(2_000)
            assertFalse(h.loading.isShowing)
            h.advance(30_000)
            assertEquals("the 20 s idle timeout, as before", listOf("sleep (idle 20s) admin=false"), h.sleeps())
        }
    }
}
