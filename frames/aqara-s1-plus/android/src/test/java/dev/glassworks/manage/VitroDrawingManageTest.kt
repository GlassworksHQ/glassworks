/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import android.app.Activity
import android.app.Application
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.engine.CardInBrowser
import dev.glassworks.engine.Drawing
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.ViewDrawing
import dev.glassworks.pane.SettingsItem
import dev.glassworks.platform.AppBuild
import dev.glassworks.s1.S1Panes
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.s1.manage.S1Manage
import dev.glassworks.settings.CoreSettings
import dev.glassworks.settings.S1Settings
import dev.glassworks.settings.VitroDrawingRows
import dev.glassworks.update.ReleaseLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How Vitro draws the dashboard, in Labs (maintainer, 2026-11-28): on the Manage page and in the panel's menu alike,
 * live, from the engine ([Engine.drawing]); nothing of it while Vitro is off. The real settings model and Manage page
 * over an engine that reports what a test sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VitroDrawingManageTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)

    /** An engine that draws nothing, and says what [drawing] holds. */
    private class Reporting(@Volatile var drawing: Drawing?) : Engine {
        override val started: EngineSettings? = null
        override val name = if (drawing == null) "GeckoView 159.0" else "Vitro 0.0.1 (experimental)"
        override fun open(activity: Activity, host: PageHost, background: Int): Page = error("no page in this test")
        override fun isContentProcess(processName: String) = false
        override fun drawing(): Drawing? = drawing
    }

    private val media = CardInBrowser("home › section 2 › card 3 (custom:mushroom-media-player-card)", "`custom:mushroom-media-player-card` isn't drawn natively")
    private val home = ViewDrawing("Home", islands = listOf(media))
    private val energy = ViewDrawing("Energy", frontend = "the built-in `energy-date-selection` card")

    private fun runtime(engine: Engine) =
        GlassworksRuntime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false), engine, S1Panes(), releases = ReleaseLine.AQARA_S1_PLUS)

    private fun browser(runtime: GlassworksRuntime): ManageFixture.Browser {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val m = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)
        return ManageFixture().apply { statusFn = { m.status() } }.Browser().also { it.signInWithHa() }
    }

    private fun labs(runtime: GlassworksRuntime) =
        S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = false, fullScreen = {}, context = app)
            .sections.single { it.id == CoreSettings.LABS }

    @Test fun vitroOffShowsNothingOfItAndTheLabsPageStaysStill() {
        val runtime = runtime(Reporting(null))
        assertFalse(VitroDrawingRows.shownIn(labs(runtime).items))
        val page = browser(runtime).get("/${CoreSettings.LABS}").text()
        assertTrue(page, page.contains(CoreSettings.VITRO_TITLE))
        assertFalse(page.contains(VitroDrawingRows.SHOWN_TITLE))
        assertFalse("nothing to follow", page.contains("http-equiv=\"refresh\""))
    }

    @Test fun theManagePageShowsTheViewOnScreenItsIslandsAndEachViewAndFollowsThem() {
        val engine = Reporting(Drawing(home) { listOf(home, energy) })
        val runtime = runtime(engine)
        val browser = browser(runtime)
        val page = browser.get("/${CoreSettings.LABS}").text()
        for (s in listOf(VitroDrawingRows.SHOWN_TITLE, "<b>Home</b>", "with 1 card in a browser view of its own.", "<b>home › section 2 › card 3 (custom:mushroom-media-player-card)</b>",
            "isn't drawn natively", VitroDrawingRows.VIEWS_TITLE, "<span>Energy</span><em>Home Assistant's frontend</em>", "<span>Home</span><em>1 browser view</em>")) {
            assertTrue("$s in\n$page", page.contains(s))
        }
        assertTrue("it reloads itself to follow the view on screen", page.contains("""<meta http-equiv="refresh" content="3;url=/labs">"""))
        assertTrue(page, page.contains("This page reloads every 3 seconds to follow the dashboard."))
        assertTrue(page.contains("""<a href="/labs?live=off">Pause</a>"""))

        // The view on screen changes: the next load says so.
        engine.drawing = Drawing(energy) { listOf(home, energy) }
        val next = browser.get("/${CoreSettings.LABS}").text()
        assertTrue(next, next.contains("<b>Energy</b>") && next.contains("because Vitro doesn't draw the built-in `energy-date-selection` card."))
        assertFalse("no islands listed for a view HA's frontend draws", next.contains(media.where))

        // The Overview's Engine tile says how, and opens Labs.
        val overview = browser.get("/").text()
        assertTrue(overview, overview.contains("HA's frontend"))
        assertTrue(overview, overview.contains("href=\"/labs\""))
    }

    @Test fun thePanelsMenuShowsTheSameRows() {
        val runtime = runtime(Reporting(Drawing(home) { listOf(home, energy) }))
        val items = labs(runtime).items
        val group = items.filterIsInstance<SettingsItem.Group>().single { it.title == VitroDrawingRows.SHOWN_TITLE }
        assertEquals(listOf("Home", media.where), group.items.map { (it as SettingsItem.Info).title() })
        assertEquals(listOf("Home", "Energy"), items.filterIsInstance<SettingsItem.States>().single().rows().map { it.name })
    }

    @Test fun theDiagnosticsFileListsThem() {
        val runtime = runtime(Reporting(Drawing(home) { listOf(home, energy) }))
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val text = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs).diagnostics()
        assertTrue(text, text.contains("${media.where}: ${media.why}"))
        assertTrue(text, text.contains("Energy: Home Assistant's frontend"))
    }
}
