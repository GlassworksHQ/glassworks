package dev.glassworks.setup

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import dev.glassworks.s1.KioskApp
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows

/**
 * #621's Mirror run: after the app's own restart in setup, Sign in says it connects until the page answers. Android
 * recreating the activity in those 20 s (Kiln's `set_locale`) keeps the screen up and the check going. A process
 * Android killed and restored later isn't the app's own restart: its saved state starts no check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class SetupRestartRecreateTest {
    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun GeckoPinHarness.setupTexts(): List<String> = setupTexts(activity)

    private fun setupTexts(a: android.app.Activity): List<String> {
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val s = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<SetupOverlay>().single()
        return if (!s.isShowing) emptyList() else views(s).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
    }

    /** Robolectric's active network, with the internet: Wi-Fi is done. */
    private fun online() {
        val cm = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test fun theSignInCheckCarriesOnWhenTheActivityIsRecreated() = GeckoPinHarness(prefs = {
        online()
        it.urlSetting = "http://192.0.2.10:8123/lovelace/0"
        it.markSetupUpAtRestart(true)
    }).use { h ->
        h.advance(2_000)
        assertTrue("after the restart: ${h.setupTexts()}", SetupOverlay.SIGNIN_CHECKING in h.setupTexts())
        h.controller.recreate()
        h.advance(2_000)
        assertTrue("recreated: ${h.setupTexts()}", SetupOverlay.SIGNIN_CHECKING in h.setupTexts())
    }

    @Test fun aRestoredProcessStartsNoCheck() = GeckoPinHarness(prefs = {
        online()
        it.urlSetting = "http://192.0.2.10:8123/lovelace/0"
        it.markSetupUpAtRestart(true)
    }).use { h ->
        h.advance(2_000)
        assertTrue("after the restart: ${h.setupTexts()}", SetupOverlay.SIGNIN_CHECKING in h.setupTexts())
        val saved = Bundle()
        h.controller.pause().saveInstanceState(saved)
        saved.putInt("dev.glassworks.pid", android.os.Process.myPid() + 1)
        val restored = Robolectric.buildActivity(h.activity.javaClass).setup(saved)
        try {
            h.advance(2_000)
            assertFalse("restored in another process: ${setupTexts(restored.get())}", SetupOverlay.SIGNIN_CHECKING in setupTexts(restored.get()))
        } finally {
            restored.pause().stop().destroy()
            h.controller.resume()
        }
    }
}
