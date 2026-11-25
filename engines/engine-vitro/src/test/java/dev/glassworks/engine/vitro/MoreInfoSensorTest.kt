/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.engine.vitro.cards.MoreInfoHistoryView
import dev.glassworks.engine.vitro.cards.MoreInfoSheet
import dev.glassworks.engine.vitro.cards.dialogSurface
import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.engine.vitro.ui.VitroView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.TimeZone

/**
 * A sensor's More Info as HA's dialog draws it (#885): statistics for a sensor with a state class, the state history
 * otherwise, "Show more" to /history, the title on two lines, and the user's theme, never the view's. The parity
 * harness compares the drawn sheet with HA's (`more-info-sensor-*`); these pin the behavior behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreInfoSensorTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val calls = ArrayList<JSONObject>()
    private val subscriptions = ArrayList<JSONObject>()
    private val answers = ArrayList<Pair<JSONObject, (Boolean, Any?, JSONObject?) -> Unit>>()
    private val navigated = ArrayList<String>()

    /** 2026-11-12 10:08 in Chicago, as the parity harness's fixed instant. */
    private val now = 1_794_499_680_000L

    @After fun tearDown() = HaDisplay.reset()

    private fun context(store: EntityStore, theme: ResolvedTheme = ThemeResolver.resolve(ThemeCatalog(), true)) = VitroCardContext(
        context = activity, haBase = FakeHaServer.BASE, store = store, theme = theme, resources = emptyList(),
        accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = { navigated += it }, openMoreInfo = {},
        fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        callWs = { msg, done -> calls += msg; answers += msg to done },
        subscribeWs = { msg, _, _ -> subscriptions += msg; {} },
        now = { now },
    )

    private fun sensor(attributes: Map<String, Any?>) = EntityStore().apply {
        putLocal(EntityState(SENSOR, "792", attributes + mapOf("unit_of_measurement" to "lx", "friendly_name" to "Study Desk Sensor Illuminance")))
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.map { it.text.toString() }

    private fun open(store: EntityStore): MoreInfoSheet {
        val sheet = MoreInfoSheet(context(store)) {}
        activity.setContentView(sheet)
        sheet.show(SENSOR)
        idle()
        return sheet
    }

    @Test
    fun aSensorWithAStateClassAsksForFiveMinuteStatisticsAsHasDialogDoes() {
        open(sensor(mapOf("state_class" to "measurement")))
        assertEquals(listOf("recorder/get_statistics_metadata", "recorder/statistics_during_period"), calls.map { it.getString("type") })
        val stats = calls[1]
        // fetchStatistics(callWS, subHours(new Date(), 24), undefined, [id], "5minute", undefined, statTypes): no end, no units.
        assertEquals("2026-11-11T16:08:00.000Z", stats.getString("start_time"))
        assertEquals("5minute", stats.getString("period"))
        assertEquals(JSONArray(listOf("state", "min", "mean", "max")).toString(), stats.getJSONArray("types").toString())
        assertFalse(stats.has("end_time"))
        assertFalse(stats.has("units"))
        assertTrue("no history stream while statistics may come", subscriptions.isEmpty())
    }

    @Test
    fun noStatisticsMetadataFallsBackToTheStateHistory() {
        open(sensor(mapOf("state_class" to "measurement")))
        answers[0].second(true, JSONArray(), null)
        answers[1].second(true, JSONObject(), null)
        idle()
        assertEquals(listOf("history/stream"), subscriptions.map { it.getString("type") })
    }

    @Test
    fun aSensorWithoutAStateClassFollowsItsStateHistory() {
        val sheet = open(sensor(emptyMap()))
        assertTrue("no statistics asked for: ${calls.map { it.optString("type") }}", calls.isEmpty())
        val stream = subscriptions.single()
        assertEquals("history/stream", stream.getString("type"))
        assertEquals("2026-11-11T16:08:00.000Z", stream.getString("start_time"))
        assertTrue("History, then Show more", texts(sheet).containsAll(listOf("History", "Show more")))
        assertFalse("the aggregate note is for statistics only", "5-minute aggregated" in texts(sheet))
    }

    @Test
    fun showMoreOpensTheHistoryPageFromTheStartOfYesterdayAndClosesTheSheet() {
        assertEquals(
            "/history?entity_id=sensor.study_desk_illuminance&start_date=2026-11-11T06%3A00%3A00.000Z&back=1",
            MoreInfoHistoryView.showMoreHref(SENSOR, now, TimeZone.getTimeZone("America/Chicago")),
        )
        val sheet = open(sensor(emptyMap()))
        all(sheet).filterIsInstance<TextView>().single { it.text.toString() == "Show more" }.performClick()
        idle()
        assertTrue(sheet.isDismissed)
        assertEquals(listOf(MoreInfoHistoryView.showMoreHref(SENSOR, now)), navigated)
    }

    @Test
    fun theTitleNamesTheAreaAndDeviceOverTheEntitysOwnName() {
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray().put(JSONObject().put("ei", SENSOR).put("di", "d1").put("en", "Illuminance"))))
        HaDisplay.applyDevices(JSONArray().put(JSONObject().put("id", "d1").put("name", "Study Desk Sensor").put("area_id", "study")))
        HaDisplay.applyAreas(JSONArray().put(JSONObject().put("area_id", "study").put("name", "Study")))
        val shown = texts(open(sensor(emptyMap())))
        assertTrue(shown.toString(), "Study ▸ Study Desk Sensor" in shown)
        // The header's main line and the state row both name the entity by its own name.
        assertEquals(shown.toString(), 2, shown.count { it == "Illuminance" })
        assertFalse(shown.toString(), shown.any { it.contains("Study Desk Sensor Illuminance") })
    }

    @Test
    fun theSheetTakesTheUsersThemeNotTheViews() {
        // A view theme with a see-through card background (the tablet's): HA's dialog sits outside the view.
        val catalog = ThemeCatalog.parse(JSONObject().put("themes", JSONObject().put("Glass", JSONObject()
            .put("card-background-color", "rgba(255, 255, 255, 0.05)").put("ha-card-background", "rgba(255, 255, 255, 0.05)"))))
        val store = sensor(emptyMap())
        val themes = ArrayList<ResolvedTheme>()
        val view = VitroView(activity, store, Color.BLACK)
        view.contextFactory = { theme -> themes += theme; context(store, theme) }
        view.setThemes(catalog)
        view.setUserTheme(null, true)
        view.setDashboard(LovelaceParser.parseDashboard(JSONObject("""{"views": [{"path": "p", "theme": "Glass", "cards": [{"type": "tile", "entity": "$SENSOR"}]}]}""")), "p")
        activity.setContentView(view)
        idle()
        themes.clear()
        view.openMoreInfo(SENSOR)
        idle()
        val surface = dialogSurface(themes.first())
        assertEquals("opaque", 0xFF, Color.alpha(surface))
        assertEquals(dialogSurface(ThemeResolver.resolve(catalog, true)), surface)
        assertNotEquals(dialogSurface(ThemeResolver.resolve(catalog, true, "Glass")), surface)
    }

    private companion object {
        const val SENSOR = "sensor.study_desk_illuminance"
    }
}
