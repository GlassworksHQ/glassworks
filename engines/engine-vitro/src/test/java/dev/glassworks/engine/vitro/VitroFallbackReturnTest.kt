/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PagePort
import dev.glassworks.engine.vitro.data.HaDashboardClient
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The way back from a page Vitro doesn't draw (`/energy`, `/history`, `/config/...`), which the fallback engine shows:
 * Back, Reload dashboard and Home Assistant's own moves to a dashboard return to the native view, and nothing of either
 * side shows over the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VitroFallbackReturnTest {
    private val base = FakeHaServer.BASE
    private val living = "$base/wall-test/living"
    private val wall = "$base/wall-test/wall"
    private val settings = EngineSettings(secureHosts = listOf("192.0.2.10"), zoom = 1f, remoteDebugging = false, dashboard = living)

    private lateinit var activity: Activity
    private val host = RecordingHost()
    private val server = FakeHaServer()
    private val engines = ArrayList<VitroEngine>()
    private val opened = ArrayList<Page>()

    @Before fun setUp() { activity = Robolectric.buildActivity(Activity::class.java).setup().get() }

    @After fun tearDown() {
        engines.forEach { runCatching { it.islandServer.stop() } }
        opened.forEach { page ->
            runCatching {
                val f = page.javaClass.getDeclaredField("client"); f.isAccessible = true
                (f.get(page) as? HaDashboardClient)?.close()
            }
        }
        server.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun awaitMain(message: String, cond: () -> Boolean) = awaitCondition(message, tick = { idle() }, cond = cond)

    private fun engine(fb: RecordingEngine, cfg: EngineSettings = settings) = VitroEngine(
        fallback = fb, settings = { cfg }, enabled = { true },
        tokenProvider = { _, done -> done(FakeHaServer.TOKEN) }, socketFactory = server::connect,
    ).also { engines += it }

    private fun page(e: VitroEngine): Page = e.open(activity, host, Color.BLACK).also {
        opened += it
        activity.setContentView(it.view)
        idle()
    }

    private fun readyPage(fb: RecordingEngine, url: String = living): Page = page(engine(fb)).also {
        it.load(url)
        awaitMain("ready") { host.messages("ready").isNotEmpty() }
    }

    private fun vitroView(page: Page): VitroView = (page.view as ViewGroup).getChildAt(0) as VitroView

    /** The fallback page: the one opened with the page's background (islands open transparent). */
    private fun RecordingEngine.fallbackOpen() = opens.single { it.background == Color.BLACK }
    private fun RecordingEngine.fallbackPage() = pages[opens.indexOf(fallbackOpen())]
    private fun RecordingEngine.islandPages() = pages.filterIndexed { i, _ -> opens[i].background == Color.TRANSPARENT }

    private fun assertNative(page: Page, fp: RecordingPage) {
        assertEquals("the native view is in front", View.VISIBLE, vitroView(page).visibility)
        assertEquals("the fallback page is gone", View.GONE, fp.view.visibility)
    }

    private fun assertFallback(page: Page, fp: RecordingPage) {
        assertEquals("the native view is gone", View.GONE, vitroView(page).visibility)
        assertEquals("the fallback page is in front", View.VISIBLE, fp.view.visibility)
    }

    // ---- Back ----

    @Test fun backGoesThroughTheFallbackPagesOwnHistoryThenToTheNativeViewItCameFrom() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load(wall)
        idle()
        page.load("$base/energy")
        idle()
        val fp = fb.fallbackPage()
        assertFallback(page, fp)

        // Home Assistant's page moved on within itself (/energy → /config/energy): Back goes back there first.
        fp.canGoBack = true
        page.goBack()
        assertEquals(1, fp.backs)
        assertFallback(page, fp)

        // With nothing of its own left, Back returns to the view the page came from.
        fp.canGoBack = false
        page.goBack()
        idle()
        assertEquals("not the fallback's own back", 1, fp.backs)
        assertNative(page, fp)
        assertEquals(1, vitroView(page).currentViewIndex)
        assertEquals(wall, host.locations.last())

        // And Back again goes on through Vitro's history.
        page.goBack()
        idle()
        assertEquals(0, vitroView(page).currentViewIndex)
    }

    @Test fun backFromAFallbackPageThePanelStartedOnGoesToTheDashboard() {
        val fb = RecordingEngine()
        val page = page(engine(fb))
        page.load("$base/history")
        idle()
        val fp = fb.fallbackPage()
        assertTrue("nothing native yet", server.connectUrls.isEmpty())

        page.goBack()
        awaitMain("the dashboard the panel is set to") { host.messages("ready").isNotEmpty() }
        assertNative(page, fp)
        assertEquals(living, host.locations.last())

        // The page it started on isn't a step back: Back again stays on the dashboard.
        assertFalse(page.canGoBack)
        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals(listOf("$base/history"), fp.loads.toList())
    }

    @Test fun eachPageVitroSendsTheFallbackStartsWithNoHistory() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        assertEquals("a new page is empty already", 0, fp.restarts)

        // Back to the native view: Home Assistant's frontend doesn't keep running behind it.
        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals(1, fp.restarts)

        // The next visit uses that empty page as it is.
        page.load("$base/history")
        assertFallback(page, fp)
        assertEquals(1, fp.restarts)
        // A second page Vitro sends while one shows starts over too: Vitro's history has the step back.
        page.load("$base/logbook")
        assertEquals(2, fp.restarts)
        assertEquals(listOf("$base/energy", "$base/history", "$base/logbook"), fp.loads.toList())
        assertEquals("one fallback page for all of them", 1, fb.opens.count { it.background == Color.BLACK })

        page.goBack()
        idle()
        assertEquals("back to /history, in the fallback", "$base/history", fp.loads.last())
        assertFallback(page, fp)
        page.goBack()
        idle()
        assertNative(page, fp)
    }

    @Test fun aMoreInfoOpenBeforeTheFallbackClosesAndDoesNotTakeTheFirstBack() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        val vv = vitroView(page)
        vv.openMoreInfo("light.lounge_lamp")
        idle()
        page.load("$base/history")
        val fp = fb.fallbackPage()
        assertFalse("closed, not waiting to show over the dashboard again", vv.closeMoreInfo())

        page.goBack()
        idle()
        assertNative(page, fp)
    }

    // ---- Reload dashboard ----

    @Test fun reloadDashboardOnAFallbackPageReturnsToTheNativeView() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load(wall)
        idle()
        page.load("$base/energy")
        val fp = fb.fallbackPage()

        page.reload()
        idle()
        assertEquals("the HA page isn't reloaded", 0, fp.reloads)
        assertNative(page, fp)
        assertEquals("the view the page last showed", 1, vitroView(page).currentViewIndex)
        assertEquals(wall, host.locations.last())

        // The page it left isn't in the history, nor the dashboard twice: Back goes to the view before.
        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals(0, vitroView(page).currentViewIndex)
        assertEquals(listOf("$base/energy"), fp.loads.toList())
    }

    @Test fun reloadDashboardFromAPageThePanelStartedOnGoesToTheDashboardItIsSetTo() {
        val fb = RecordingEngine()
        val page = page(engine(fb))
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        page.reload()
        awaitMain("native dashboard") { host.messages("ready").isNotEmpty() }
        assertNative(page, fp)
        assertEquals(0, fp.reloads)
    }

    @Test fun aDashboardHomeAssistantHasNoConfigForIsNoReturnTarget() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/missing")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        val fp = fb.fallbackPage()
        assertFallback(page, fp)

        // Reload goes to the dashboard last on screen, not to the one HA's frontend draws.
        page.reload()
        idle()
        assertNative(page, fp)
        assertEquals(living, host.locations.last())
        assertEquals(listOf("$base/missing"), fp.loads.toList())
    }

    @Test fun startedOnADashboardWithNoConfigBackGoesToTheDashboardThePanelIsSetTo() {
        val fb = RecordingEngine()
        val page = page(engine(fb))
        page.load("$base/missing")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        val fp = fb.fallbackPage()
        page.goBack()
        awaitMain("native") { host.messages("ready").isNotEmpty() }
        assertNative(page, fp)
        assertEquals(living, host.locations.last())
    }

    @Test fun setToADashboardWithNoConfigReloadReloadsHomeAssistantsPage() {
        val fb = RecordingEngine()
        val page = page(engine(fb, settings.copy(dashboard = "$base/missing")))
        page.load("$base/missing")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        val fp = fb.fallbackPage()
        page.reload()
        idle()
        assertEquals("nothing native to go to", 1, fp.reloads)
        assertFallback(page, fp)
    }

    @Test fun aDashboardGivenAConfigLaterIsAReturnTargetAgain() {
        val fb = RecordingEngine()
        val page = page(engine(fb, settings.copy(dashboard = "$base/missing")))
        page.load("$base/missing")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        // Someone takes control of it in HA: the next load draws it natively.
        server.missingDashboards = emptySet()
        page.load("$base/missing")
        awaitMain("native") { host.messages("ready").isNotEmpty() }
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        assertFallback(page, fp)

        page.reload()
        idle()
        assertEquals("back to it, not a reload of HA's page", 0, fp.reloads)
        assertNative(page, fp)
    }

    @Test fun withNoDashboardToGoToReloadReloadsTheFallbackPage() {
        val fb = RecordingEngine()
        val page = page(engine(fb, settings.copy(dashboard = null)))
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        page.reload()
        assertEquals(1, fp.reloads)
        assertFallback(page, fp)
    }

    @Test fun reloadOnTheNativeViewStillFetchesTheDashboardAgain() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        page.goBack()
        idle()
        page.reload()
        awaitMain("refetch") { server.commands("lovelace/config").size == 2 }
        assertEquals(0, fb.fallbackPage().reloads)
    }

    // ---- Home Assistant's frontend moving to a dashboard ----

    @Test fun aSidebarTapToADashboardVitroDrawsHandsThePageBack() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fpHost = fb.fallbackOpen().host
        assertNotSame("the fallback reports through Vitro", host, fpHost)
        val fp = fb.fallbackPage()

        // Its own pages are the host's to know about.
        fpHost.onLocationChange("$base/energy")
        fpHost.onLocationChange("$base/config/energy")
        assertEquals("$base/config/energy", host.locations.last())
        assertFallback(page, fp)

        // HA's sidebar (pushState) to the dashboard's other view, with the app's external_auth flag on it.
        val readies = host.messages("ready").size
        fpHost.onLocationChange("$wall?external_auth=1")
        idle()
        assertNative(page, fp)
        assertEquals(1, vitroView(page).currentViewIndex)
        assertTrue("ready again", host.messages("ready").size > readies)
        assertEquals("no fallback page for it", listOf("$base/energy"), fp.loads.toList())

        // Back from there returns to the HA page it came from.
        page.goBack()
        idle()
        assertFallback(page, fp)
        assertEquals("$base/energy", fp.loads.last())
    }

    @Test fun pagesVitroDoesNotDrawStayOnTheFallback() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fpHost = fb.fallbackOpen().host
        val fp = fb.fallbackPage()
        listOf(
            "https://example.com/lovelace/0", // another origin
            "$living?edit=1", // HA's edit mode
            "$base/?auth_callback=1&code=abc", // a sign-in coming back
            "about:blank",
        ).forEach { fpHost.onLocationChange(it); idle() }
        assertFallback(page, fp)
        assertEquals(1, fp.loads.size)
    }

    @Test fun aNewerLocationCancelsAQueuedHandBack() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fpHost = fb.fallbackOpen().host
        val fp = fb.fallbackPage()
        // HA's frontend passes through the dashboard on its way to edit mode, before the hand-back runs.
        fpHost.onLocationChange(wall)
        fpHost.onLocationChange("$wall?edit=1")
        idle()
        assertFallback(page, fp)
        assertEquals("$wall?edit=1", host.locations.last())
    }

    @Test fun aDashboardHomeAssistantsFrontendDrawsIsNotHandedBackInALoop() {
        val fb = RecordingEngine()
        val page = page(engine(fb))
        page.load("$base/missing")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        val fp = fb.fallbackPage()
        // Its frontend reports the dashboard it loaded: no saved config, so it stays HA's.
        fb.fallbackOpen().host.onLocationChange("$base/missing")
        idle()
        assertFallback(page, fp)
        assertEquals(listOf("$base/missing"), fp.loads.toList())
        assertEquals("$base/missing", host.locations.last())

        // Another dashboard from its sidebar is Vitro's.
        fb.fallbackOpen().host.onLocationChange(living)
        awaitMain("native") { host.messages("ready").isNotEmpty() }
        assertNative(page, fp)
    }

    // ---- No half-native states ----

    @Test fun aFallbackPageBehindTheNativeViewReportsNothing() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fpHost = fb.fallbackOpen().host
        // In front, its page reports as any page does.
        fpHost.onPageStart("$base/energy")
        assertEquals("$base/energy", host.pageStarts.last())
        page.goBack()
        idle()

        val starts = host.pageStarts.size
        val stops = host.pageStops.size
        val paints = host.firstPaints.get()
        val locations = host.locations.size
        val messages = host.portMessages.size
        fpHost.onPageStart("$base/energy")
        fpHost.onPageStop(false)
        fpHost.onFirstPaint()
        fpHost.onLocationChange("$base/energy")
        fpHost.onLocationChange(wall) // behind the native view: no hand-back either
        val port = object : PagePort {
            override val url: String? = "$base/energy"
            override val topLevel = true
            override fun post(message: JSONObject) {}
        }
        fpHost.onPortMessage(port, JSONObject().put("type", "ready"))
        idle()
        assertEquals("no loading screen", starts, host.pageStarts.size)
        assertEquals("no offline screen", stops, host.pageStops.size)
        assertEquals(paints, host.firstPaints.get())
        assertEquals(locations, host.locations.size)
        assertEquals(messages, host.portMessages.size)
        assertEquals(0, vitroView(page).currentViewIndex)

        // Loads (the panel's sign-in) and ports still go through.
        host.redirectTo = { "about:blank" }
        assertEquals("about:blank", fpHost.redirect("$base/?code=abc"))
        fpHost.onConnect(port)
        assertTrue(port in host.ports)
    }

    @Test fun islandsStayHiddenBehindTheFallbackEvenWhenRebuiltAndComeBackAfter() {
        val fb = RecordingEngine(surfaces = true)
        val page = readyPage(fb, wall)
        awaitMain("an island") { fb.islandPages().isNotEmpty() }
        fun surfaceOf(p: RecordingPage) = (p.view as ViewGroup).getChildAt(0) as SurfaceView
        val first = surfaceOf(fb.islandPages().first())

        page.load("$base/energy")
        val fp = fb.fallbackPage()
        assertEquals(View.INVISIBLE, first.visibility)

        // A theme change rebuilds the view behind the fallback: its new island opens hidden.
        val islands = fb.islandPages().size
        vitroView(page).setUserTheme("night", true)
        awaitMain("a rebuilt island") { fb.islandPages().size > islands }
        val rebuilt = surfaceOf(fb.islandPages().last())
        assertEquals("not drawn over HA's page", View.INVISIBLE, rebuilt.visibility)
        assertEquals(View.VISIBLE, fp.view.visibility)

        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals(View.VISIBLE, rebuilt.visibility)
    }

    @Test fun pageCanGoBackSaysWhetherBackHasSomewhereToGo() {
        val fb = RecordingEngine()
        val page = page(engine(fb, settings.copy(dashboard = null)))
        assertFalse(page.canGoBack)
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        assertFalse("no history and no dashboard", page.canGoBack)
        fp.canGoBack = true
        assertTrue(page.canGoBack)
    }
}
