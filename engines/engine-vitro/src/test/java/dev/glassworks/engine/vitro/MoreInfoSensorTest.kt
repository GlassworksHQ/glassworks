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
    fun withoutTheRecorderASensorWithAStateClassFollowsItsStateHistoryAsHasDialogDoes() {
        // HA's _getStateHistory asks for statistics only when the recorder is loaded: Clarity's fake HA lists history but
        // not the recorder, so HA's dialog never asks it for statistics metadata, and neither does Vitro's sheet.
        val store = sensor(mapOf("state_class" to "measurement"))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            haConfig = { JSONObject().put("components", JSONArray(listOf("sensor", "history"))) },
            callWs = { msg, done -> calls += msg; answers += msg to done }, subscribeWs = { msg, _, _ -> subscriptions += msg; {} }, now = { now },
        )
        val sheet = MoreInfoSheet(ctx) {}
        activity.setContentView(sheet)
        sheet.show(SENSOR)
        idle()
        assertTrue("no statistics asked for: ${calls.map { it.optString("type") }}", calls.isEmpty())
        assertEquals(listOf("history/stream"), subscriptions.map { it.getString("type") })
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
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray().put(JSONObject().put("ei", SENSOR).put("di", "d1").put("en", "Illuminance").put("np", "device"))))
        HaDisplay.applyDevices(JSONArray().put(JSONObject().put("id", "d1").put("name", "Study Desk Sensor").put("area_id", "study")))
        HaDisplay.applyAreas(JSONArray().put(JSONObject().put("area_id", "study").put("name", "Study")))
        val shown = texts(open(sensor(emptyMap())))
        assertTrue(shown.toString(), "Study ▸ Study Desk Sensor" in shown)
        // The header's main line and the state row both name the entity by its own name.
        assertEquals(shown.toString(), 2, shown.count { it == "Illuminance" })
        assertFalse(shown.toString(), shown.any { it.contains("Study Desk Sensor Illuminance") })
    }

    @Test
    fun aNameThatDoesntReachItsDeviceLeavesTheDeviceOut() {
        // HA's computeEntityEntryNameParts: a named entity without next_name_part "device" is named by its area and itself.
        HaDisplay.applyRegistry(JSONObject().put("entities", JSONArray().put(JSONObject().put("ei", SENSOR).put("di", "d1").put("en", "Illuminance"))))
        HaDisplay.applyDevices(JSONArray().put(JSONObject().put("id", "d1").put("name", "Study Desk Sensor").put("area_id", "study")))
        HaDisplay.applyAreas(JSONArray().put(JSONObject().put("area_id", "study").put("name", "Study")))
        assertEquals(HaDisplay.NameParts("Study", null, "Illuminance"), HaDisplay.nameParts(SENSOR, "x"))
        val shown = texts(open(sensor(emptyMap())))
        assertTrue(shown.toString(), "Study" in shown)
        assertFalse(shown.toString(), shown.any { "Study Desk Sensor" in it })
    }

    @Test
    fun noLastChangeShowsNoAge() {
        val store = EntityStore().apply { putLocal(EntityState(SENSOR, "792", mapOf("unit_of_measurement" to "lx"))) }
        val shown = texts(open(store))
        assertFalse(shown.toString(), shown.any { it.endsWith(" ago") })
    }

    @Test
    fun withoutHasHistoryIntegrationThereIsNoHistorySection() {
        val store = sensor(mapOf("state_class" to "measurement"))
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            haConfig = { JSONObject().put("components", JSONArray(listOf("sensor", "recorder"))) },
            callWs = { msg, done -> calls += msg; answers += msg to done }, subscribeWs = { msg, _, _ -> subscriptions += msg; {} }, now = { now },
        )
        val sheet = MoreInfoSheet(ctx) {}
        activity.setContentView(sheet)
        sheet.show(SENSOR)
        idle()
        assertFalse(texts(sheet).toString(), "History" in texts(sheet))
        assertTrue("nothing asked for", calls.isEmpty() && subscriptions.isEmpty())
    }

    @Test
    fun aFirstStatisticsAttemptLeftUnansweredFallsBackToTheStateHistory() {
        open(sensor(mapOf("state_class" to "measurement")))
        assertTrue(subscriptions.isEmpty())
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.FIRST_ANSWER_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(listOf("history/stream"), subscriptions.map { it.getString("type") })
        // A late answer to that attempt changes nothing.
        answers.forEach { (msg, done) -> done(true, if (msg.getString("type") == "recorder/get_statistics_metadata") JSONArray().put(JSONObject().put("statistic_id", SENSOR)) else JSONObject(), null) }
        idle()
        assertEquals(1, subscriptions.size)
    }

    @Test
    fun aStateHistoryRefusedWhileOfflineStartsAgainOnReconnect() {
        // The socket is down when the sheet opens: the client refuses the subscription at once.
        var refuse = true
        val store = sensor(emptyMap())
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { msg, _, onError -> subscriptions += msg; if (refuse) onError(null); {} }, now = { now },
        )
        val sheet = MoreInfoSheet(ctx) {}
        activity.setContentView(sheet)
        sheet.show(SENSOR)
        idle()
        assertEquals(1, subscriptions.size)
        refuse = false
        sheet.reconnected()
        idle()
        assertEquals("the history subscribes again", 2, subscriptions.size)
    }

    @Test
    fun aFailedRenewalKeepsItsErrorOverTheMinuteTick() {
        // History arrives, the connection comes back, and the client's renewal is refused: the old chart doesn't return.
        val events = ArrayList<(JSONObject) -> Unit>()
        val errors = ArrayList<(JSONObject?) -> Unit>()
        val store = sensor(emptyMap())
        val ctx = VitroCardContext(
            context = activity, haBase = FakeHaServer.BASE, store = store, theme = ThemeResolver.resolve(ThemeCatalog(), true), resources = emptyList(),
            accessToken = { FakeHaServer.TOKEN }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { msg, onEvent, onError -> subscriptions += msg; events += onEvent; errors += onError; {} }, now = { now },
        )
        val sheet = MoreInfoSheet(ctx) {}
        activity.setContentView(sheet)
        sheet.show(SENSOR)
        idle()
        val t = (now / 1000.0) - 3600
        val rows = JSONArray().put(JSONObject().put("s", "700").put("lu", t)).put(JSONObject().put("s", "792").put("lu", t + 1800))
        events[0](JSONObject().put("states", JSONObject().put(SENSOR, rows)))
        idle()
        sheet.reconnected()
        idle()
        // The client renews a held subscription itself (HaDashboardClient), and reports a refused renewal on it.
        assertEquals("the sheet leaves a live subscription to the client", 1, subscriptions.size)
        errors[0](JSONObject().put("code", "unknown_error").put("message", "refused"))
        idle()
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(texts(sheet).toString(), texts(sheet).any { it.endsWith(": refused") })
    }

    @Test
    fun aSlowAnswerToAnEarlierRefreshDoesntReplaceANewerChart() {
        // D40: statistics are fetched again every minute, all in one generation. The newest refresh's answer is drawn; a
        // late answer to an earlier one is dropped instead of putting older data over it.
        val sheet = open(sensor(mapOf("state_class" to "measurement")))
        val history = all(sheet).filterIsInstance<MoreInfoHistoryView>().single()
        val shown = MoreInfoHistoryView::class.java.getDeclaredField("statistics").apply { isAccessible = true }
        fun answer(at: Int, mean: Double) {
            val start = (now - 3_600_000L).toDouble()
            val row = JSONObject().put("start", start).put("end", start + 300_000).put("mean", mean).put("min", mean).put("max", mean).put("state", mean)
            answers.subList(at, at + 2).forEach { (msg, done) ->
                if (msg.getString("type") == "recorder/get_statistics_metadata") {
                    done(true, JSONArray().put(JSONObject().put("statistic_id", SENSOR).put("statistics_unit_of_measurement", "lx")), null)
                } else {
                    done(true, JSONObject().put(SENSOR, JSONArray().put(row)), null)
                }
            }
            idle()
        }
        fun mean() = (shown.get(history) as JSONObject).getJSONArray(SENSOR).getJSONObject(0).getDouble("mean")
        answer(0, 1.0)
        assertEquals(1.0, mean(), 0.0)
        // Two refreshes go out, a minute apart, before either is answered.
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(6, answers.size)
        answer(4, 3.0) // the newest
        answer(2, 2.0) // the one before it, late
        assertEquals("the newest refresh's data stays", 3.0, mean(), 0.0)
        // Every answer slower than a minute: a refresh goes out before the last one is answered, and the late answer
        // still draws, as no newer one has.
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(10, answers.size)
        answer(6, 4.0)
        assertEquals("a slow answer still draws when nothing newer has", 4.0, mean(), 0.0)
        answer(8, 5.0)
        assertEquals(5.0, mean(), 0.0)
        // A newer refresh that fails draws nothing, so it doesn't block a slower, earlier one that succeeds.
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(MoreInfoHistoryView.MINUTE_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(14, answers.size)
        answers.subList(12, 14).forEach { (_, done) -> done(false, null, null) }
        idle()
        answer(10, 6.0)
        assertEquals("an earlier refresh's data draws when the newer one failed", 6.0, mean(), 0.0)
    }

    @Test
    fun aReconnectLeavesALiveStateHistoryStreamToTheClient() {
        // The client sends a held subscription again on the new connection (HaDashboardClientTest): the sheet asking
        // for another one would only double it.
        val sheet = open(sensor(emptyMap()))
        assertEquals(1, subscriptions.size)
        sheet.reconnected()
        idle()
        assertEquals(1, subscriptions.size)
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
