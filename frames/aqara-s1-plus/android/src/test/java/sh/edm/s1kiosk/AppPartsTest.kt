package sh.edm.s1kiosk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.frame.Capability
import dev.glassworks.manage.ManagePage
import dev.glassworks.pane.CantRun
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.esphome.DeviceHost
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.voice.VoicePrefs

/** The runtime runs the panes its app gives it ([AppParts]), and asks the app for its activity and pane files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppPartsTest {
    private val app = RuntimeEnvironment.getApplication()

    private object NoEngine : Engine {
        override val started: EngineSettings? = null
        override fun open(activity: Activity, host: PageHost, background: Int): Page = error("no page in this test")
        override fun isContentProcess(processName: String) = false
    }

    private class Definition(override val id: String, override val requires: Set<Capability> = emptySet()) : PaneDefinition

    private inner class Parts : AppParts {
        var asked = 0
        var frameIds: List<String> = emptyList()
        val file: SharedPreferences = app.getSharedPreferences("parts-test", Context.MODE_PRIVATE)

        override fun paneDefinitions(context: Context, runtime: GlassworksRuntime, frameDefinitions: List<PaneDefinition>): List<PaneDefinition> {
            asked++
            frameIds = frameDefinitions.map { it.id }
            return listOf(Definition("first")) + frameDefinitions + Definition("needs-a-camera", setOf(Capability.CAMERA))
        }
        override fun devicePanes(runtime: GlassworksRuntime, host: DeviceHost): List<Pane> = emptyList()
        override fun overlays(runtime: GlassworksRuntime, host: OverlayHost): List<PaneOverlay> = emptyList()
        override val mainActivity = Activity::class.java
        override fun paneFile(context: Context, namespace: String) = file
        override fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
            error("no Manage page in this test")
    }

    private val parts = Parts()
    private val runtime = GlassworksRuntime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false), NoEngine, parts, releases = sh.edm.s1kiosk.update.ReleaseLine.S1_KIOSK)

    @Test fun theRuntimeRunsTheAppsPanesInItsOrderAndMarksTheOnesTheFrameCantRun() {
        val defs = runtime.paneDefinitions(app)
        assertEquals("the frame's own, as the app placed them", listOf("first", "wall-base", "time-zone", "needs-a-camera"), defs.map { it.id })
        assertEquals("the app got the frame's definitions", listOf("wall-base", "time-zone"), parts.frameIds)
        assertTrue("the S1 has no camera", defs.last() is CantRun)
        assertTrue(defs.dropLast(1).none { it is CantRun })
        assertSame("made once", defs, runtime.paneDefinitions(app))
        assertEquals(1, parts.asked)
    }

    @Test fun theS1AppsRuntimeRunsTheS1Panes() {
        val s1 = KioskApp.runtime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false))
        assertTrue("the S1 app's overlays and hooks are the S1 panes'", s1.app is S1Panes)
        // adb, Kiln and Clarity drive S1 Kiosk with MainActivity's extras from any caller (#23 keeps that).
        assertTrue(s1.app.trustsExtras(android.content.Intent().setClassName(app, "sh.edm.s1kiosk.MainActivity").putExtra("url", "http://192.0.2.1/")))
    }

    @Test fun theS1AppsVoiceStateStartsFromTheAssistantsSwitch() {
        val s1 = KioskApp.runtime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false))
        val panes = s1.app as S1Panes
        for ((on, state) in listOf(true to "idle", false to "off")) {
            PanePrefs(app).paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, on).commit()
            panes.serviceStarted(app, s1)
            assertEquals(state, s1.voiceState)
            assertEquals("the overlay starts from it", state, panes.voiceUi.state)
        }
    }

    @Test fun theAppDecidesItsActivityAndWherePaneSettingsLive() {
        assertEquals(Activity::class.java, runtime.mainActivity)
        assertSame(parts.file, runtime.paneFile(app, "anything"))
    }
}
