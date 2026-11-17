package sh.edm.s1kiosk.manage

import android.app.Application
import android.widget.TextView
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.text
import dev.glassworks.pane.HA_NOT_ADDED
import dev.glassworks.pane.HA_NOT_CONNECTED
import dev.glassworks.pane.HaLink
import dev.glassworks.pane.IN_HOME_ASSISTANT
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.UNKNOWN
import dev.glassworks.pane.shown
import dev.glassworks.pane.withHaLink
import dev.glassworks.platform.AppBuild
import dev.glassworks.web.PageShell.esc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.PaneFixtures
import sh.edm.s1kiosk.settings.S1Settings
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Mockup

/**
 * #261: a pane page's "In Home Assistant" list on a panel Home Assistant never added says so once, with no
 * row reading "Unknown"; on one it added but isn't connected to now, says so above the last known states,
 * dimmed; connected, it is as before. The panel and the Manage page, from the same model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HaLinkSurfacesTest {
    private val app = RuntimeEnvironment.getApplication()
    private var link = HaLink.CONNECTED
    private val page = SettingsSection("camera", "Camera", listOf(
        SettingsItem.Note { "The camera's page" },
        SettingsItem.States(IN_HOME_ASSISTANT) { listOf(SettingsItem.StateRow("Camera still", UNKNOWN), SettingsItem.StateRow("Camera stream", "Idle")) },
    ), master = SettingsItem.Switch("Camera", "", { true }) { _, d -> d(null) })
    private val sections = withHaLink(listOf(page)) { link }
    private val fixtures = PaneFixtures(app)

    @Before fun interFromSources() = Mockup.interFromSources()
    @After fun shutdown() = fixtures.shutdown()

    private fun panel(): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(KioskSettingsOverlay.Model(sections, "0.4.3", setOf("camera")))
        o.previewSection("camera")
        Mockup.idle()
        return o
    }

    private fun shown(o: KioskSettingsOverlay, text: String) = runCatching { Mockup.label(o, text).isShown }.getOrDefault(false)
    private fun colour(o: KioskSettingsOverlay, text: String) = (Mockup.label(o, text) as TextView).currentTextColor

    private fun manage(): String {
        val f = ManageFixture()
        f.statusFn = { f.status.copy(panes = listOf(paneRow("camera", sections.single())), sections = sections) }
        return f.Browser().also { it.signInWithHa() }.get("/camera").text()
    }

    @Test
    fun neverAddedSaysSoOnceWithNoRows() {
        link = HaLink.NOT_ADDED
        val o = panel()
        assertTrue(shown(o, HA_NOT_ADDED))
        assertFalse("no row", shown(o, "Camera still") || shown(o, UNKNOWN))
        val html = manage()
        assertTrue(html, html.contains(esc(HA_NOT_ADDED)))
        assertFalse("no row", html.contains("Camera still") || html.contains(UNKNOWN))
    }

    @Test
    fun notConnectedSaysSoAndDimsTheLastKnownStates() {
        link = HaLink.NOT_CONNECTED
        val o = panel()
        assertTrue(shown(o, HA_NOT_CONNECTED))
        assertTrue(shown(o, "Idle"))
        assertEquals("the name is dimmed", Glass.MU, colour(o, "Camera stream"))
        assertEquals("the value is dimmed", Glass.FA, colour(o, "Idle"))
        val html = manage()
        assertTrue(html.contains(esc(HA_NOT_CONNECTED)))
        assertTrue("every row is quiet", html.contains("""<div class="sr quiet"><span>Camera stream</span><em>Idle</em></div>"""))
        assertFalse(html.contains("""<div class="sr">"""))
    }

    @Test
    fun connectedIsAsBefore() {
        val o = panel()
        assertFalse(shown(o, HA_NOT_ADDED) || shown(o, HA_NOT_CONNECTED))
        assertEquals(Glass.TX, colour(o, "Camera stream"))
        val html = manage()
        assertFalse(html.contains(esc(HA_NOT_ADDED)) || html.contains(esc(HA_NOT_CONNECTED)))
        assertTrue(html.contains("""<div class="sr"><span>Camera stream</span><em>Idle</em></div>"""))
    }

    @Test
    fun theOpenPageFollowsHomeAssistant() {
        link = HaLink.NOT_ADDED
        val o = panel()
        link = HaLink.CONNECTED
        o.refresh(); Mockup.idle()
        assertFalse(shown(o, HA_NOT_ADDED))
        assertEquals(Glass.TX, colour(o, "Camera stream"))
        link = HaLink.NOT_CONNECTED
        o.refresh(); Mockup.idle()
        assertTrue(shown(o, HA_NOT_CONNECTED))
        assertEquals(Glass.MU, colour(o, "Camera stream"))
    }

    /** The app's own model, for the panel and the Manage page: the link from whether Home Assistant ever added the panel. */
    @Test
    fun theAppsModelReadsWhetherHomeAssistantAddedThePanel() {
        val runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("1.0.0", 10000, debug = false))
        runtime.panes = fixtures.all()
        val prefs = KioskPrefs(app)
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        fun notes(sections: List<SettingsSection>) = sections.flatMap { s -> s.items.filterIsInstance<SettingsItem.States>().filter { it.title == IN_HOME_ASSISTANT }.map { it.shown().note } }
        fun both() = listOf(notes(S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app).sections), notes(manage.status().sections))
        prefs.haSeen = false
        val never = both()
        assertTrue("some page has the list: $never", never[0].isNotEmpty())
        assertEquals(listOf(never[0], never[0]), never)
        assertEquals(setOf(HA_NOT_ADDED), never[0].toSet())
        // Added before; no device runs here, so it isn't connected now.
        prefs.haSeen = true
        assertEquals(setOf(HA_NOT_CONNECTED), both().flatten().toSet())
    }
}
