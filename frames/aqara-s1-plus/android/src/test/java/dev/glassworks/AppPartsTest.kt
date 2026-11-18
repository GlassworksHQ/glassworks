package dev.glassworks

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.SignInServer
import dev.glassworks.device.DeviceHost
import sh.edm.s1kiosk.frame.AqaraS1Plus
import dev.glassworks.assist.VoicePrefs
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.S1Panes

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
        override val components = AppComponents(FakeMain::class.java, KioskService::class.java, AdminReceiver::class.java, dev.glassworks.update.UpdateInstallReceiver::class.java)
        override fun paneFile(context: Context, namespace: String) = file
        override fun managePage(context: Context, runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, prefs: KioskPrefs): ManagePage =
            error("no Manage page in this test")
    }

    private val parts = Parts()
    private val runtime = GlassworksRuntime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false), NoEngine, parts, releases = dev.glassworks.update.ReleaseLine.AQARA_S1_PLUS)

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
        // adb, Kiln, Clarity and the root helper drive the S1 app with MainActivity's extras; the launcher's alias can't (#374).
        assertTrue(s1.app.trustsExtras(android.content.Intent().setClassName(app, "sh.edm.s1kiosk.MainActivity").putExtra("url", "http://192.0.2.1/")))
        assertFalse(s1.app.trustsExtras(android.content.Intent().setClassName(app, "sh.edm.s1kiosk.Launcher").putExtra("url", "http://192.0.2.1/")))
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
        assertEquals(FakeMain::class.java, runtime.components.mainActivity)
        assertSame(parts.file, runtime.paneFile(app, "anything"))
    }
}

/** An app's own subclass of the core's activity, as the S1's `sh.edm.s1kiosk.MainActivity` is. */
private class FakeMain : MainActivity()
