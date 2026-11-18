package sh.edm.s1kiosk

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import dev.glassworks.engine.gecko.GeckoPinHarness
import dev.glassworks.engine.gecko.GeckoShadows
import dev.glassworks.assist.VoicePrefs
import dev.glassworks.glassworksRuntime

/** The shell calls the app's hooks ([AppParts.serviceStarted], [AppParts.activityStarted]) as it starts. */
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
class ShellHooksTest {
    private val app = RuntimeEnvironment.getApplication()
    private var service: ServiceController<KioskService>? = null

    @After fun tearDown() { service?.destroy() }

    @Test fun theServiceStartsTheVoiceStateFromTheAssistantsSwitch() {
        PanePrefs(app).paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, true).commit()
        service = Robolectric.buildService(KioskService::class.java).create()
        val runtime = app.glassworksRuntime
        assertEquals("idle", runtime.voiceState)
        assertEquals("the voice overlay starts from it", "idle", (runtime.app as S1Panes).voiceUi.state)
    }

    @Test fun theActivityRunsTheBootSplashSyncOnStart() {
        // The app's MainActivity is the only HOME, so a sync turns the splash on.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(app.packageManager).addResolveInfoForIntent(home, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = app.packageName; name = MainActivity::class.java.name }
        })
        GeckoPinHarness().use { splashTurnsOn() }
    }

    private fun splashTurnsOn() {
        val splash = ComponentName(app, BootSplashActivity::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        while (app.packageManager.getComponentEnabledSetting(splash) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
            System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("the splash is on: the app is the only HOME", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, app.packageManager.getComponentEnabledSetting(splash))
    }
}
