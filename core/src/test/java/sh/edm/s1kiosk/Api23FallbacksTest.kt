package sh.edm.s1kiosk

import android.app.Application
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.glassworks.platform.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.setup.ConnectivityWifiStatus
import sh.edm.s1kiosk.ui.GlassSlider
import java.util.Locale

/**
 * The core on a real Android 6 framework (Robolectric's API 23 jar), for the legacy build (#198): each of these
 * called an API 24+ method, which throws NoSuchMethodError or NoSuchFieldError here. The first was the app's crash
 * at start on an API 23 emulator (docs/research/legacy-engine.md, step 9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class Api23FallbacksTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun `the setup screen hears the network on Android 6`() {
        assertEquals(23, Platform.apiLevel)
        val status = ConnectivityWifiStatus(app)
        status.observe { }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
        status.observe(null)
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
    }

    @Test fun `the language reads and sets through Configuration_locale on Android 6`() {
        val config = Configuration()
        SystemLocale.setLocale(config, Locale.forLanguageTag("es-ES"))
        assertEquals(Locale.forLanguageTag("es-ES"), SystemLocale.current(config))
    }

    @Test fun `a slider describes itself without the API 24 set-progress action`() {
        val slider = GlassSlider(app, 0f, 1f, 0.1f).apply { value = 0.5f }
        val info = AccessibilityNodeInfo.obtain()
        slider.onInitializeAccessibilityNodeInfo(info)
        assertFalse(info.actionList.isEmpty())
    }
}
