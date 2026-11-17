package sh.edm.s1kiosk.manage

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.text
import dev.glassworks.pane.CantRun
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.paneState
import dev.glassworks.platform.AppBuild
import dev.glassworks.web.PageShell.esc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.PanePrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.settings.S1Settings

/**
 * Maintainer, 2026-11-15: "the manage page still feels not feature parity with the panel like I can't turn
 * off panes from the manage page; if the pane can't run it should just be hidden". On the S1 and on a
 * Pixel Tablet (the generic Android frame): the Manage page lists the panes the panel's rail lists, in its
 * order, folds the same ones under Off, and turns each one on and off that the rail can. A pane the frame
 * lacks the hardware for is on neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanesOnBothSurfacesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val panePrefs = PanePrefs(app)
    private val tokens = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    })

    private class Frame(val name: String, val runtime: GlassworksRuntime, val rail: List<String>, val hidden: List<String>)

    private fun frames() = listOf(
        Frame("S1", KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false)),
            listOf("assist", "sendspin", "notifications", "radio", "bluetooth-proxy", "wall-base", "time-zone", "diagnostics"), listOf("camera")),
        Frame("Pixel Tablet", KioskApp.runtime(GenericAndroid(app), build = AppBuild("1.0.0", 10000, debug = false)),
            listOf("assist", "sendspin", "notifications", "bluetooth-proxy", "camera", "diagnostics"), listOf("radio")),
    )

    @Before fun aPixelTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        val pm = shadowOf(app.packageManager)
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            pm.setSystemFeature(f, true)
        }
    }

    /** The panel's menu model, as MainActivity makes it, and the Manage page on the same runtime, signed in with Home Assistant. */
    private class Surfaces(val panel: sh.edm.s1kiosk.settings.KioskSettingsOverlay.Model, val manage: S1Manage, val browser: ManageFixture.Browser, val fixture: ManageFixture)

    private fun surfaces(runtime: GlassworksRuntime): Surfaces {
        val panel = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
        val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        val f = ManageFixture().apply { statusFn = { manage.status() } }
        return Surfaces(panel, manage, f.Browser().also { it.signInWithHa() }, f)
    }

    @Test fun manageListsTheRailsPanesInItsOrderAndHidesWhatTheFrameCantRun() {
        for (frame in frames()) {
            val defs = frame.runtime.paneDefinitions(app)
            assertEquals(frame.name, frame.hidden, defs.filterIsInstance<CantRun>().map { it.id })
            val s = surfaces(frame.runtime)
            val rail = s.panel.sections.filter { it.id in s.panel.panes }.map { it.id }
            assertEquals("${frame.name}: the panel's rail", frame.rail, rail)
            val status = s.manage.status()
            assertEquals("${frame.name}: Manage's panes are the rail's", rail, status.panes.map { it.id })
            val overview = s.browser.get("/").text()
            for (id in frame.hidden) {
                assertFalse("${frame.name}: no $id section on the panel", s.panel.sections.any { it.id == id })
                assertFalse("${frame.name}: no $id section on Manage", status.sections.any { it.id == id })
                assertEquals("${frame.name}: no $id page on Manage", 404, s.browser.get("/$id").status)
                assertFalse("${frame.name}: no $id in Manage's nav or Overview", overview.contains("href=\"/$id\""))
                assertFalse("${frame.name}: no setup card for $id", frame.runtime.setupPanes(app).any { it.steps.any { st -> st.id == id } })
            }
            assertTrue(overview.contains(dev.glassworks.manage.panesSummary(status.panes)))
            // Hidden everywhere else, the diagnostics file still says why each one is missing.
            val lacks = mapOf("camera" to "camera", "radio" to "silabs-radio")
            for (id in frame.hidden) {
                assertTrue("${frame.name}: diagnostics name $id", s.manage.diagnostics().lines().contains("  $id: HIDDEN, the frame lacks ${lacks[id]}"))
            }
        }
    }

    @Test fun manageFoldsTheSamePanesUnderOffAsTheRail() {
        for (frame in frames()) {
            val sw = frame.runtime.paneDefinitions(app).single { it.id == "diagnostics" }.switch!!
            sw.on = false
            try {
                val s = surfaces(frame.runtime)
                val offOnRail = s.panel.sections.filter { it.id in s.panel.panes && paneState(it).kind == PaneState.Kind.OFF }.map { it.id }
                assertTrue("${frame.name}: diagnostics folds on the rail", "diagnostics" in offOnRail)
                assertEquals(offOnRail, s.manage.status().panes.filter { it.state.kind == PaneState.Kind.OFF }.map { it.id })
                val fold = s.browser.get("/").text().substringAfter("<details class=\"off\"").substringBefore("</details>")
                assertTrue("${frame.name}: Off · ${offOnRail.size}", fold.contains("<summary>Off · ${offOnRail.size}</summary>"))
                for (id in offOnRail) assertTrue("${frame.name}: $id under Off", fold.contains("href=\"/$id\""))
            } finally {
                sw.on = sw.default
            }
        }
    }

    /**
     * Every pane the rail shows with its on/off (the panel draws it while the pane can run: bindMaster) has it
     * on its Manage page and turns off and on there; the radio asks first, as the panel does.
     */
    @Test fun everyPaneSwitchOnTheRailTurnsOnAndOffFromManage() {
        for (frame in frames()) {
            val s = surfaces(frame.runtime)
            val b = s.browser
            val switched = s.panel.sections.filter { it.id in s.panel.panes && it.master != null && it.unavailable() == null }
            assertEquals("${frame.name}: every pane on the rail has its on/off", frame.rail, switched.map { it.id })
            for (sec in switched) {
                val m = sec.master!!
                val page = b.get("/${sec.id}").text()
                val was = m.value()
                assertTrue("${frame.name}: ${sec.id}'s on/off, not locked",
                    page.contains("role=\"switch\" aria-checked=\"$was\" aria-label=\"${esc(sec.heading)}\"><i></i>"))
                val to = if (was) "off" else "on"
                val form = mapOf("csrf" to b.csrf(), "section" to sec.id, "key" to SettingsChange.MASTER, "value" to to)
                val r = b.post("/settings", form)
                val ask = m.confirm(!was)
                if (ask != null) {
                    // The radio's off: the panel's question, and nothing changes until it's answered. Not confirmed here.
                    assertEquals(200, r.status)
                    assertTrue(r.text().contains(esc(ask.title)) && r.text().contains(">${esc(ask.action)}</button>"))
                    assertEquals(was, m.value())
                    continue
                }
                assertEquals("${frame.name}: ${sec.id} $to", 303, r.status)
                assertEquals("${frame.name}: ${sec.id} $to", !was, m.value())
                assertEquals(303, b.post("/settings", form + ("value" to (if (was) "on" else "off"))).status)
                assertEquals("${frame.name}: ${sec.id} back", was, m.value())
            }
        }
    }

    /**
     * Maintainer, 2026-11-15: Manage has the panel's settings, with the sign-in as the only extra layer. A code
     * sign-in turns a pane off and on; the radio asks first, and nothing changes until the question is answered.
     */
    @Test fun aCodeSignInSwitchesPanesAsThePanelDoes() {
        for (frame in frames()) {
            val s = surfaces(frame.runtime)
            val code = s.fixture.Browser()
            code.post("/manage/code", mapOf("next" to "/"))
            code.post("/manage/code", mapOf("next" to "/", "code" to s.fixture.shown.last()!!.code))
            assertFalse(frame.name, code.get("/diagnostics").text().contains("aria-label=\"Diagnostics\" disabled"))
            val sw = frame.runtime.paneDefinitions(app).single { it.id == "diagnostics" }.switch!!
            assertTrue(sw.on)
            fun post(section: String, value: String) =
                code.post("/settings", mapOf("csrf" to code.csrf(), "section" to section, "key" to SettingsChange.MASTER, "value" to value))
            val off = post("diagnostics", "off")
            assertEquals(frame.name, 303, off.status)
            assertEquals(200, code.get(off.headers["Location"]!!).status)
            assertFalse("${frame.name}: diagnostics off", sw.on)
            assertEquals(303, post("diagnostics", "on").status)
            assertTrue("${frame.name}: diagnostics on again", sw.on)
            val radio = s.panel.sections.firstOrNull { it.id == "radio" }?.master ?: continue
            panePrefs.radio.mode = "thread_router"
            try {
                assertTrue("${frame.name}: the radio is on", radio.value())
                val ask = post("radio", "off")
                assertEquals(200, ask.status)
                assertTrue(ask.text().contains(esc("Turn the radio off?")))
                assertEquals("${frame.name}: not confirmed, the radio stays", "thread_router", panePrefs.radio.mode)
                assertTrue(radio.value())
            } finally {
                panePrefs.radio.mode = null
            }
        }
    }

}
