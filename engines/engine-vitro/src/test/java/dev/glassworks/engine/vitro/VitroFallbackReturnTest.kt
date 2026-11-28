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
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    /** Its clients never go to the network for HA's frontend files ([NoHaFrontendFetch]). */
    @get:org.junit.Rule val noFrontendFetch = NoHaFrontendFetch()

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

    // ---- Paths the app is asked to open ----

    /**
     * The Mirror (Android 6, legacy engine): `--es open /history` reached the fallback as the bare path, and its lockdown
     * refused it as "a URL without a network origin". A path goes on Home Assistant's origin before the fallback gets
     * it, its query and fragment kept, on the first load (the configured dashboard's origin) and on a later one.
     */
    @Test fun aPathTheAppIsAskedToOpenReachesTheFallbackAsAUrlOnHomeAssistant() {
        val fb = RecordingEngine(lockedDown = true)
        val page = page(engine(fb))
        page.load("/history?entity_id=light.kitchen#top")
        idle()
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        assertEquals(listOf("$base/history?entity_id=light.kitchen#top"), fp.loads.toList())

        page.goBack()
        awaitMain("the dashboard") { host.messages("ready").isNotEmpty() }
        page.load("/logbook")
        idle()
        page.load("config/dashboard")
        idle()
        assertEquals(listOf("$base/history?entity_id=light.kitchen#top", "$base/logbook", "$base/config/dashboard"), fp.loads.toList())
        assertTrue("every load has Home Assistant's origin", fp.loads.all { it.startsWith("$base/") })

        // A dashboard path draws natively, on Home Assistant's origin.
        page.load("/wall-test/wall")
        idle()
        assertNative(page, fp)
        assertEquals("$base/wall-test/wall", host.locations.last())
    }

    @Test fun aPathOnANonDefaultPortOrADefaultOneGoesOnTheOriginAsThePanelWritesIt() {
        for ((dashboard, expected) in listOf(
            "http://192.0.2.10:80/lovelace/0" to "http://192.0.2.10/history",
            "https://192.0.2.10:443/lovelace/0" to "https://192.0.2.10/history",
            "https://192.0.2.10:8443/lovelace/0" to "https://192.0.2.10:8443/history",
        )) {
            val fb = RecordingEngine(lockedDown = true)
            val page = page(engine(fb, settings.copy(dashboard = dashboard)))
            page.load("/history")
            idle()
            assertEquals(dashboard, listOf(expected), fb.fallbackPage().loads.toList())
        }
    }

    @Test fun aPathWithNoHomeAssistantToGoOnLoadsNothing() {
        val fb = RecordingEngine(lockedDown = true)
        val page = page(engine(fb, settings.copy(dashboard = null)))
        page.load("/history")
        idle()
        assertTrue("no fallback page for it", fb.opens.none { it.background == Color.BLACK })
    }

    /** A card's `navigate` to a page Vitro doesn't draw: the fallback gets it on HA's origin, query and fragment kept. */
    @Test fun aCardsNavigateToANonDashboardPathReachesTheFallbackAsAUrl() {
        val fb = RecordingEngine(lockedDown = true)
        val page = readyPage(fb)
        val ctx = vitroView(page).contextFactory!!(ThemeResolver.resolve(ThemeCatalog(), true))
        ctx.navigate("/history?hours_to_show=24#graph")
        idle()
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        assertEquals(listOf("$base/history?hours_to_show=24#graph"), fp.loads.toList())
        // A scheme-relative path names a host: it goes nowhere.
        ctx.navigate("//192.0.2.30/history")
        idle()
        assertEquals(1, fp.loads.size)
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

    // ---- Home Assistant's own More Info dialog ----

    @Test fun aDialogTheSheetDoesntDrawOpensInHomeAssistantsFrontendAndClosingItComesBack() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        val vv = vitroView(page)
        // A timer's dialog (its controls and presets) is HA's own: its frontend shows the view with it open.
        vv.openMoreInfo("timer.tea")
        idle()
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        assertEquals(listOf("$living?more-info-entity-id=timer.tea"), fp.loads.toList())
        assertEquals("no sheet of Vitro's", null, vv.openSheet)

        // HA's page reports the dialog's URL: no hand-back while it's open.
        val fpHost = fb.fallbackOpen().host
        fpHost.onLocationChange("$living?more-info-entity-id=timer.tea")
        idle()
        assertFallback(page, fp)

        // The dialog closes (HA drops the parameter): the native view again, with no step back to the dialog.
        fpHost.onLocationChange(living)
        idle()
        assertNative(page, fp)
        assertEquals(living, host.locations.last())
        assertFalse("Back doesn't open the dialog again", page.canGoBack)
    }

    @Test fun backFromHomeAssistantsDialogReturnsToTheViewItOpenedOver() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load(wall)
        idle()
        vitroView(page).openMoreInfo("weather.home")
        idle()
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals(1, vitroView(page).currentViewIndex)
        assertEquals(wall, host.locations.last())
    }

    @Test fun aDisposedPageClosesItsSheet() {
        // The activity goes: the sheet is dismissed, and a script-fields lookup still out answers into nothing
        // (closeMoreInfo moves its request on; EntityRowControlsTest covers that half).
        val fb = RecordingEngine()
        val page = readyPage(fb)
        val vv = vitroView(page)
        vv.openMoreInfo("sensor.outdoor_temperature")
        idle()
        assertNotNull(vv.openSheet)
        page.javaClass.getDeclaredMethod("dispose").apply { isAccessible = true }.invoke(page)
        idle()
        assertEquals("no sheet left on a disposed page", null, vv.openSheet)
    }

    @Test fun aDialogTheSheetDrawsStaysNative() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        val vv = vitroView(page)
        vv.openMoreInfo("sensor.outdoor_temperature")
        idle()
        assertNotNull(vv.openSheet)
        assertTrue("no fallback page", fb.opens.none { it.background == Color.BLACK })
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
            "$living?more-info-entity-id=script.greet", // HA's More Info dialog over a view (a script's fields)
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

    // ---- Admin-only dashboards (#887) ----

    private val wallUser = org.json.JSONObject("""{"id": "wall-user", "name": "Wall Panel", "is_owner": false, "is_admin": false}""")

    /**
     * A user who isn't an administrator opening an admin-only dashboard (a link, a navigate action, Manage's URL): HA's
     * frontend has no panel for it and routes it to the user's default panel. Vitro draws nothing of it and hands the URL
     * to HA's frontend, which does just that; the default panel is Vitro's again when it's a dashboard Vitro draws.
     */
    @Test fun anAdminOnlyDashboardGoesToHomeAssistantsFrontendForAUserWhoIsntAnAdministrator() {
        server.currentUser = wallUser
        val fb = RecordingEngine()
        val page = readyPage(fb)
        val before = host.messages("ready").size
        page.load("$base/admin-only/secret")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        assertEquals(listOf("$base/admin-only/secret"), fp.loads.toList())
        // Nothing of it was drawn: the native view still holds the dashboard before it.
        assertEquals(listOf("Living", "Wall"), vitroView(page).dashboard!!.views.map { it.title })
        assertEquals(before, host.messages("ready").size)

        // HA's frontend went to the user's default panel, a dashboard Vitro draws: the page is native again.
        fb.fallbackOpen().host.onLocationChange(living)
        awaitMain("native") { host.messages("ready").size > before }
        assertNative(page, fp)

        // Reload and Back never return to the refused dashboard.
        page.load("$base/energy")
        idle()
        page.reload()
        idle()
        assertNative(page, fp)
        assertEquals(living, host.locations.last())
    }

    /** HA makes the dashboard on screen admin only (`frontend/update_panel`): it goes, on the same connection. */
    @Test fun aDashboardMadeAdminOnlyWhileOnScreenGoesToHomeAssistantsFrontend() {
        server.currentUser = wallUser
        val fb = RecordingEngine()
        val page = readyPage(fb)
        awaitMain("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        server.panelRequireAdmin["wall-test"] = true
        server.pushPanelsUpdated()
        awaitMain("fallback") { fb.opens.any { it.background == Color.BLACK } }
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        assertEquals(listOf(living), fp.loads.toList())
        assertEquals("no reconnect", 1, server.connectUrls.size)
    }

    /** Revoked while HA's frontend showed another page: Back to the dashboard from Vitro's cache is refused too. */
    @Test fun aDashboardRevokedWhileTheFallbackShowedIsRefusedOnTheWayBack() {
        server.currentUser = wallUser
        val fb = RecordingEngine()
        val page = readyPage(fb)
        awaitMain("panels_updated subscribed") { server.eventSubscriptionId("panels_updated") != null }
        page.load("$base/energy")
        idle()
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        server.panelRequireAdmin["wall-test"] = true
        server.pushPanelsUpdated()
        awaitMain("panels asked again") { server.commands("get_panels").size == 2 }
        // Back: the native view in Vitro's cache, then refused, and HA's frontend takes the URL.
        page.goBack()
        awaitMain("handed to HA's frontend") { fp.loads.contains(living) }
        assertFallback(page, fp)
        assertEquals(listOf("$base/energy", living), fp.loads.toList())
    }

    @Test fun startedOnAnAdminOnlyDashboardAUserWhoIsntAnAdministratorGetsHomeAssistantsPage() {
        server.currentUser = wallUser
        val fb = RecordingEngine()
        val page = page(engine(fb, settings.copy(dashboard = "$base/admin-only")))
        page.load("$base/admin-only")
        awaitMain("fallback") { fb.opens.isNotEmpty() }
        val fp = fb.fallbackPage()
        assertFallback(page, fp)
        assertTrue("nothing native drawn", vitroView(page).dashboard == null)
        assertTrue("no ready from Vitro", host.messages("ready").isEmpty())
    }

    @Test fun anAdministratorGetsTheAdminOnlyDashboardNatively() {
        val fb = RecordingEngine()
        val page = page(engine(fb))
        page.load("$base/admin-only")
        awaitMain("native") { host.messages("ready").isNotEmpty() }
        assertTrue("never the fallback", fb.opens.none { it.background == Color.BLACK })
        assertNotNull(vitroView(page).dashboard)
    }

    /** The panel's Dashboard select lists what HA's frontend lists for the user, as the web engine reports it. */
    @Test fun theDashboardListLeavesOutAdminOnlyDashboardsForAUserWhoIsntAnAdministrator() {
        server.currentUser = wallUser
        val page = readyPage(RecordingEngine())
        awaitMain("dashboards") { host.messages("dashboards").isNotEmpty() }
        val list = org.json.JSONArray(host.messages("dashboards").single().getString("json"))
        assertEquals(listOf("wall-test"), (0 until list.length()).map { list.getJSONObject(it).getString("url_path") })
        assertNotNull(page)
    }

    // ---- No half-native states ----

    @Test fun aContentProcessLostBehindTheNativeViewRestartsOnlyTheHiddenPage() {
        // A5: the native dashboard doesn't run in Gecko. A content process lost while the fallback page hides behind it
        // restarts that page alone, not the activity's page (which would close the native connection and draw again).
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        val fpHost = fb.fallbackOpen().host
        page.goBack()
        idle()
        assertNative(page, fp)
        val restarts = fp.restarts
        val connections = server.connectUrls.size
        fpHost.onContentGone(killed = true)
        idle()
        assertTrue("the activity isn't told: ${host.contentGone}", host.contentGone.isEmpty())
        assertEquals("the hidden page starts over", restarts + 1, fp.restarts)
        assertNative(page, fp)
        Thread.sleep(200)
        idle()
        assertEquals("the native dashboard keeps its connection", connections, server.connectUrls.size)

        // In front, it's the page the user sees: the activity is told, as for any page.
        page.load("$base/energy")
        idle()
        assertFallback(page, fp)
        fpHost.onContentGone(killed = false)
        assertEquals(listOf(false), host.contentGone.toList())
    }

    @Test fun aDashboardWhoseConfigFailsToReadIsVitrosAgainOnReload() {
        // A12: an error other than config_not_found is asked again; once the retries are spent HA's frontend shows its
        // error, but only for now: Reload dashboard (or the next connection) gives it back to Vitro.
        server.configError = "error"
        val fb = RecordingEngine()
        val e = engine(fb).also { it.configRetryMs = longArrayOf(20, 20) }
        val page = page(e)
        page.load(living)
        awaitMain("fallback") { fb.opens.isNotEmpty() && fb.fallbackPage().view.visibility == View.VISIBLE }
        val fp = fb.fallbackPage()
        assertEquals("the first ask and two retries", 3, server.commands("lovelace/config").size)
        // HA's frontend says where it is: no hand-back to a dashboard whose config just failed.
        fb.fallbackOpen().host.onLocationChange(living)
        idle()
        assertFallback(page, fp)

        server.configError = null
        page.reload()
        awaitMain("native") { host.messages("ready").isNotEmpty() }
        assertNative(page, fp)
        assertEquals("not a reload of HA's page", 0, fp.reloads)
    }

    @Test fun aPageSentToTheFallbackRightAfterAHiddenLossStartsOverFirst() {
        // The posted restart hasn't run yet when a page goes to the fallback: it restarts before it loads.
        val fb = RecordingEngine()
        val page = readyPage(fb)
        page.load("$base/energy")
        val fp = fb.fallbackPage()
        val fpHost = fb.fallbackOpen().host
        page.goBack()
        idle()
        val restarts = fp.restarts
        val loads = fp.loads.size
        fpHost.onContentGone(killed = true)
        page.load("$base/history") // before the main looper runs the posted restart
        assertEquals("restarted before the load", restarts + 1, fp.restarts)
        assertEquals(loads + 1, fp.loads.size)
        idle()
        assertEquals("and not again once it shows", restarts + 1, fp.restarts)
        assertTrue(host.contentGone.isEmpty())
    }

    @Test fun aDashboardWhoseConfigFailedIsVitrosAgainOnTheNextConnection() {
        server.configError = "error"
        val fb = RecordingEngine()
        val e = engine(fb).also { it.configRetryMs = longArrayOf(20, 20) }
        val page = page(e)
        page.load(living)
        awaitMain("fallback") { fb.opens.isNotEmpty() && fb.fallbackPage().view.visibility == View.VISIBLE }
        val fp = fb.fallbackPage()
        server.configError = null
        server.dropConnection()
        awaitMain("native again") { host.messages("ready").isNotEmpty() }
        assertNative(page, fp)
    }

    @Test fun aFailedDashboardIsntRetriedOverWhereTheUserWentInHomeAssistantsPage() {
        server.configError = "error"
        val fb = RecordingEngine()
        val e = engine(fb).also { it.configRetryMs = longArrayOf(20, 20) }
        val page = page(e)
        page.load(living)
        awaitMain("fallback") { fb.opens.isNotEmpty() && fb.fallbackPage().view.visibility == View.VISIBLE }
        val fp = fb.fallbackPage()
        // In HA's page, the user goes on to the energy dashboard.
        fb.fallbackOpen().host.onLocationChange("$base/energy")
        server.configError = null
        val connections = server.connectUrls.size
        server.dropConnection()
        awaitMain("reconnected") { server.connectUrls.size > connections && server.received.count { it.optString("type") == "auth" } > connections }
        repeat(10) { Thread.sleep(20); idle() }
        assertFallback(page, fp)
        assertTrue("no ready: the energy page stays", host.messages("ready").isEmpty())
    }

    @Test fun aFailedDashboardOpenInHomeAssistantsEditorIsntReloadedOnReconnect() {
        server.configError = "error"
        val fb = RecordingEngine()
        val e = engine(fb).also { it.configRetryMs = longArrayOf(20, 20) }
        val page = page(e)
        page.load(living)
        awaitMain("fallback") { fb.opens.isNotEmpty() && fb.fallbackPage().view.visibility == View.VISIBLE }
        val fp = fb.fallbackPage()
        // In HA's page, the user opens the failed dashboard's editor: a reload would drop their unsaved edits.
        fb.fallbackOpen().host.onLocationChange("$living?edit=1")
        val loads = fp.loads.size
        server.configError = null
        val connections = server.connectUrls.size
        server.dropConnection()
        awaitMain("reconnected") { server.connectUrls.size > connections && server.received.count { it.optString("type") == "auth" } > connections }
        repeat(10) { Thread.sleep(20); idle() }
        assertFallback(page, fp)
        assertEquals("the editor's page isn't loaded again", loads, fp.loads.size)
        assertTrue("no ready: the editor stays", host.messages("ready").isEmpty())
    }

    @Test fun backFromADialogThenASidebarTapToItsViewKeepsTheWayBack() {
        val fb = RecordingEngine()
        val page = readyPage(fb)
        vitroView(page).openMoreInfo("timer.tea")
        idle()
        val fp = fb.fallbackPage()
        page.goBack()
        idle()
        assertNative(page, fp)
        // Later: another HA page, then HA's sidebar back to the view the dialog once opened over.
        page.load("$base/energy")
        idle()
        val fpHost = fb.fallbackOpen().host
        fpHost.onLocationChange("$base/energy")
        fpHost.onLocationChange(living)
        idle()
        assertNative(page, fp)
        assertTrue("Back goes to the energy page, not lost as a closed dialog's", page.canGoBack)
        page.goBack()
        idle()
        assertFallback(page, fp)
        assertEquals("$base/energy", fp.loads.last())
    }

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
        val island = fb.islandPages().single()
        val first = surfaceOf(island)

        page.load("$base/energy")
        val fp = fb.fallbackPage()
        assertEquals(View.INVISIBLE, first.visibility)

        // A theme change rebuilds the view behind the fallback: the island keeps its page, and it stays hidden.
        vitroView(page).setUserTheme("night", true)
        idle()
        assertEquals("the same page", listOf(island), fb.islandPages())
        assertEquals("not drawn over HA's page", View.INVISIBLE, first.visibility)
        assertEquals(View.VISIBLE, fp.view.visibility)

        // The island's content process dies behind the fallback: its card opens a new page, hidden too.
        fb.opens[fb.pages.indexOf(island)].host.onContentGone(killed = true)
        awaitMain("a rebuilt island") { fb.islandPages().size > 1 }
        val rebuilt = surfaceOf(fb.islandPages().last())
        assertEquals("not drawn over HA's page", View.INVISIBLE, rebuilt.visibility)
        assertEquals(View.VISIBLE, fp.view.visibility)

        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals(View.VISIBLE, rebuilt.visibility)
    }

    @Test fun anIslandKeptThroughARebuildBehindTheFallbackComesBackWithTheDashboard() {
        val fb = RecordingEngine(surfaces = true)
        val page = readyPage(fb, wall)
        awaitMain("an island") { fb.islandPages().isNotEmpty() }
        val island = fb.islandPages().single()
        val surface = (island.view as ViewGroup).getChildAt(0) as SurfaceView

        page.load("$base/energy")
        val fp = fb.fallbackPage()
        vitroView(page).setUserTheme("night", true)
        idle()
        assertEquals(View.INVISIBLE, surface.visibility)
        assertFalse("and it doesn't run", island.active)

        page.goBack()
        idle()
        assertNative(page, fp)
        assertEquals("its surface shows with the dashboard", View.VISIBLE, surface.visibility)
        assertTrue(island.active)
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
