/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.ECharts
import dev.glassworks.engine.vitro.cards.GraphFooterView
import dev.glassworks.engine.vitro.cards.StatisticsGraphCardView
import dev.glassworks.engine.vitro.cards.historyPath
import dev.glassworks.engine.vitro.cards.HaChartFormat
import dev.glassworks.engine.vitro.cards.HaGraph
import dev.glassworks.engine.vitro.cards.HaHistory
import dev.glassworks.engine.vitro.cards.HaTimelineChart
import dev.glassworks.engine.vitro.cards.HaYBounds
import dev.glassworks.engine.vitro.cards.HistoryState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** The graph data and chart rules ported from HA's frontend and ECharts, checked against values worked out by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HaGraphTest {
    private val tz = TimeZone.getDefault()

    @Before
    fun utc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null) // as the device's broadcast says it
    }

    @After
    fun restore() {
        TimeZone.setDefault(tz)
        dev.glassworks.engine.vitro.data.HaLocale.deviceZoneChanged(null)
    }

    @Test
    fun historyStreamMessageIsTheFrontendsOwn() {
        val now = 1_795_543_680_000L // 2026-11-24T18:08:00Z
        val msg = HaGraph.historyStreamMessage(listOf("sensor.a"), 24.0, now) { true }
        assertEquals("history/stream", msg.getString("type"))
        assertEquals("2026-11-23T18:08:00.000Z", msg.getString("start_time"))
        assertEquals(true, msg.getBoolean("significant_changes_only"))
        assertEquals(true, msg.getBoolean("no_attributes"))
        // A climate entity, or one the frontend holds no state for, needs attributes.
        assertEquals(false, HaGraph.historyStreamMessage(listOf("climate.ac"), 24.0, now) { true }.getBoolean("no_attributes"))
        assertEquals(false, HaGraph.historyStreamMessage(listOf("sensor.a"), 24.0, now) { false }.getBoolean("no_attributes"))
    }

    @Test
    fun jsNumberParsesLikeJavaScript() {
        assertEquals(1017.0, HaGraph.jsNumber("1017.0"), 0.0)
        assertEquals(0.0, HaGraph.jsNumber(" "), 0.0)
        assertEquals(16.0, HaGraph.jsNumber("0x10"), 0.0)
        assertTrue(HaGraph.jsNumber("unavailable").isNaN())
        assertTrue(HaGraph.jsNumber("1.0f").isNaN())
        // parseFloat keeps the leading number.
        assertEquals(12.5, HaHistory.parseFloatOrNull("12.5 kWh")!!, 0.0)
        assertNull(HaHistory.parseFloatOrNull("unknown"))
    }

    @Test
    fun decimalScanTakesTheStringsTheRegexDid() {
        // The pattern jsNumber matched before it scanned by hand (java.util.regex: \d is ASCII only).
        val regex = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
        // Every string of up to 6 of these: two digits stand for all ten, plus the signs, point, exponents, a letter,
        // a space and a non-ASCII digit (Arabic-Indic three).
        val alphabet = "05+-.eEx \u0663"
        var checked = 0
        fun walk(prefix: String) {
            assertEquals("\"$prefix\"", regex.matches(prefix), HaGraph.isDecimal(prefix))
            checked++
            if (prefix.length < 6) for (c in alphabet) walk(prefix + c)
        }
        walk("")
        assertTrue(checked > 1_000_000)
        for (t in listOf("1017.0", "-.5", "+5.", "1e5", "1E-5", "3.25e+10", "007", ".", "e5", "1e", "1e+", "--1", "1.2.3", "Infinity", "0x10", "\u0661\u0662")) {
            assertEquals(t, regex.matches(t), HaGraph.isDecimal(t))
        }
        // And jsNumber reads them the same as before: the literal's value, NaN for the rest.
        assertEquals(-0.5, HaGraph.jsNumber("-.5"), 0.0)
        assertEquals(5.0, HaGraph.jsNumber(" +5. "), 0.0)
        assertTrue(HaGraph.jsNumber("\u0661\u0662").isNaN())
    }

    @Test
    fun streamMergesLaterMessagesAndKeepsTheStartState() {
        val s = HaGraph.Stream(1.0)
        val first = JSONObject().put("states", JSONObject().put("sensor.a", JSONArray()
            .put(JSONObject().put("s", "1").put("lu", 1000.0))
            .put(JSONObject().put("s", "2").put("lu", 2000.0))))
        assertEquals(2, s.process(first, 4_000_000L)["sensor.a"]!!.size)
        // An hour after 2000 s: the state from 1000 s ages out, the one at 2000 s becomes the start state at the cutoff.
        val later = JSONObject().put("states", JSONObject().put("sensor.a", JSONArray().put(JSONObject().put("s", "3").put("lu", 5000.0))))
        val merged = s.process(later, 5_700_000L)["sensor.a"]!!
        assertEquals(listOf("2", "3"), merged.map { it.s })
        assertEquals(2100.0, merged[0].lu, 1e-9)
    }

    @Test
    fun downSampleMeansFramesOnTheClock() {
        assertEquals(3_600_000.0, HaGraph.snapFrameSize(3_600_000.0), 0.0)
        assertEquals(4 * 3_600_000.0, HaGraph.snapFrameSize(5 * 3_600_000.0), 0.0)
        val hour = 3_600_000.0
        val data = (0 until 48).map { doubleArrayOf(it * hour / 2, it.toDouble()) }
        val out = HaGraph.downSample(data, 24.0, 0.0, 24 * hour, true)
        assertEquals(24, out.size)
        assertEquals(0.5, out[0][1], 1e-9) // the mean of 0 and 1
        assertEquals(hour / 4, out[0][0], 1e-9)
        // Under the limit, nothing changes.
        assertEquals(data.take(10), HaGraph.downSample(data.take(10), 24.0, null, null, true))
    }

    @Test
    fun footerPointsSpanTheCardWithTenPercentMargins() {
        val pts = HaGraph.calcPoints(listOf(doubleArrayOf(0.0, 10.0), doubleArrayOf(50.0, 20.0)), 100.0, 20.0, 0.0, 100.0, null, null)
        // y range 10..20 grows by 10 % each side (9..21), then the last value holds to the right edge.
        assertEquals(3, pts.size)
        assertEquals(20.0 - (10.0 - 9.0) / 12.0 * 20.0, pts[0][1], 1e-9)
        assertEquals(50.0, pts[1][0], 1e-9)
        assertEquals(100.0, pts[2][0], 1e-9)
        assertEquals(pts[1][1], pts[2][1], 0.0)
    }

    @Test
    fun valueAxisIsNiced() {
        assertEquals(0.5, ECharts.nice(0.624, true), 0.0)
        assertEquals(2.0, ECharts.nice(1.73, true), 0.0)
        assertEquals(1, ECharts.precision(0.5))
        // 1015..1018 widened by 2 % each side, five intervals: 0.5 steps from 1014.5 to 1018.5.
        val scale = ECharts.valueScale(1015.0, 1018.0, null, null, 0.02)
        assertEquals(1014.5, scale.min, 1e-9)
        assertEquals(1018.5, scale.max, 1e-9)
        assertEquals(9, scale.ticks.size)
        val b = HaYBounds.of(1015.0, 1018.0, "hPa", false, null, null)
        assertNull(b.fixMin)
        assertEquals(1, b.fractionDigits)
        assertEquals("1,018.5", HaChartFormat.number(1018.5, 1, 1))
        // Ties round away from zero, as Intl does.
        assertEquals("3", HaChartFormat.number(2.5, 0, 0))
        assertEquals("-3", HaChartFormat.number(-2.5, 0, 0))
        assertEquals("100", HaChartFormat.number(100.0, 0, 0))
        // A percentage close to 100 is pinned there; a positive series close to 0 is pinned at 0.
        assertEquals(100.0, HaYBounds.of(50.0, 99.5, "%", false, null, null).fixMax!!, 0.0)
        assertEquals(0.0, HaYBounds.of(0.5, 40.0, "W", false, null, null).fixMin!!, 0.0)
    }

    @Test
    fun timeTicksFollowTheClock() {
        // 2026-11-23 18:08 to 2026-11-24 18:08 UTC: every 4 hours from midnight, midnight the most important.
        val start = 1_795_457_280_000L
        val end = start + 24 * 3_600_000L
        val ticks = ECharts.timeTicks(start, end)
        val nice = ticks.filter { !it.notNice }
        assertEquals(6, nice.size)
        assertTrue(ticks.first().notNice && ticks.last().notNice)
        val midnight = nice.single { it.value % 86_400_000L == 0L }
        assertTrue(nice.all { it === midnight || it.level < midnight.level })
        assertEquals("Nov 24" to true, HaChartFormat.timeLabel(midnight.value, 1440.0))
        assertEquals("8:00\u202fPM" to false, HaChartFormat.timeLabel(nice.first().value, 1440.0))
        // A week, a day at least: one tick a day, labelled with the date past seven days.
        val week = ECharts.timeTicks(start, start + 7 * 86_400_000L + 3_600_000L, minInterval = 86_400_000L).filter { !it.notNice }
        assertEquals(7, week.size)
        assertEquals("Nov 30" to false, HaChartFormat.timeLabel(week.last().value, 7.05 * 1440))
    }

    @Test
    fun statisticsFillOnlyBeforeTheFirstState() {
        val stats = JSONObject().put("sensor.a", JSONArray()
            .put(JSONObject().put("start", 0).put("end", 3_600_000).put("mean", 5.0))
            .put(JSONObject().put("start", 3_600_000).put("end", 7_200_000).put("mean", 6.0)))
        val lts = HaHistory.statisticsToHistory({ null }, stats, listOf("sensor.a"))
        val history = HaHistory.compute({ null }, mapOf("sensor.a" to listOf(HistoryState("7", 5000.0))), listOf("sensor.a"), null, forceNumeric = true)
        val merged = HaHistory.merge(history, lts)
        val e = merged.line.single().data.single()
        assertEquals(listOf("5"), e.statistics!!.map { it.state })
        assertEquals(listOf("7"), e.states.map { it.state })
    }

    @Test
    fun timelineFramesKeepTheStateCoveringMost() {
        val segs = listOf(
            HaTimelineChart.Segment("on", "On", 0.0, 100.0),
            HaTimelineChart.Segment("off", "Off", 100.0, 130.0),
            HaTimelineChart.Segment("on", "On", 130.0, 400.0),
        )
        // 200 ms frames: the first frame is mostly "on", so the short "off" disappears into it.
        val out = HaTimelineChart.downSample(segs, 200.0)
        assertEquals(listOf("on"), out.map { it.state })
        assertEquals(400.0, out.single().end, 0.0)
    }

    @Test
    fun footerPurgeKeepsTheStartState() {
        val states = listOf(HistoryState("1", 100.0), HistoryState("2", 200.0), HistoryState("3", 400.0))
        assertEquals(listOf("2" to 300.0, "3" to 400.0), GraphFooterView.purge(states, 300.0).map { it.s to it.lu })
        // Nothing older than the window: the list stays as it is.
        assertEquals(states, GraphFooterView.purge(states, 50.0))
    }

    @Test
    fun headerLinksToTheHistoryPanelFromTheWindowsStart() {
        assertEquals(
            "/history?entity_id=sensor.a%2Csensor.b&back=1&start_date=2026-11-23T18%3A08%3A00.000Z",
            historyPath(listOf("sensor.a", "sensor.b"), 1_795_457_280_000L),
        )
    }

    @Test
    fun recorderRowTimesAreEpochOrIso() {
        assertEquals(1_700_000_000_000.0, StatisticsGraphCardView.rowTime(1_700_000_000_000L)!!, 0.0)
        assertEquals(1_700_000_000_000.0, StatisticsGraphCardView.rowTime("2023-11-14T22:13:20+00:00")!!, 1.0)
        assertNull(StatisticsGraphCardView.rowTime("soon"))
        assertNull(StatisticsGraphCardView.rowTime(null))
    }

    @Test
    fun tinyIntervalsStopWhenTicksStopMoving() {
        val scale = ECharts.valueScale(1e-23, 2e-23, null, null, 0.02)
        assertTrue(scale.ticks.size < 100)
        assertEquals(scale.ticks, scale.ticks.distinct())
    }

    @Test
    fun aChartDescribesItsText() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val chart = dev.glassworks.engine.vitro.cards.ChartView(activity, 1f)
        chart.heightCss = 60f
        chart.setScene { _ ->
            dev.glassworks.engine.vitro.cards.ChartScene().apply {
                text(dev.glassworks.engine.vitro.cards.ChartScene.Text("Occupancy", 0f, 10f, android.graphics.Paint.Align.RIGHT, 0, false))
                text(dev.glassworks.engine.vitro.cards.ChartScene.Text("12:00 PM", 50f, 40f, android.graphics.Paint.Align.CENTER, 0, false))
            }
        }
        chart.measure(android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        chart.layout(0, 0, 300, chart.measuredHeight)
        assertEquals("Occupancy, 12:00 PM", chart.contentDescription.toString())
    }

    @Test
    fun entityAndSensorCardsKeepTheirMinimumSizeInASection() {
        fun size(json: String) = dev.glassworks.engine.vitro.ui.HaGridSize.of(dev.glassworks.engine.vitro.model.LovelaceParser.parseCard(JSONObject(json)))
        // HA clamps grid_options to the card's min_columns / min_rows (6 x 2 for the entity and sensor cards).
        assertEquals(dev.glassworks.engine.vitro.ui.HaGridSize(6, 2), size("""{"type": "sensor", "entity": "sensor.a", "grid_options": {"columns": 3, "rows": 1}}"""))
        assertEquals(dev.glassworks.engine.vitro.ui.HaGridSize(9, 3), size("""{"type": "entity", "entity": "sensor.a", "grid_options": {"columns": 9, "rows": 3}}"""))
        // Full width and auto rows stay as they are.
        assertEquals(dev.glassworks.engine.vitro.ui.HaGridSize(null, null), size("""{"type": "history-graph", "entities": ["sensor.a"], "grid_options": {"columns": "full", "rows": "auto"}}"""))
    }

    @Test
    fun onlyStateColoredDomainsHaveAStateColor() {
        val sensor = dev.glassworks.engine.vitro.data.EntityState("sensor.pressure", "1017", mapOf("unit_of_measurement" to "hPa"))
        val battery = dev.glassworks.engine.vitro.data.EntityState("sensor.battery", "40", mapOf("device_class" to "battery"))
        val light = dev.glassworks.engine.vitro.data.EntityState("light.a", "on", emptyMap())
        assertEquals(false, HaTimelineChart.isStateColored(sensor))
        assertEquals(true, HaTimelineChart.isStateColored(battery))
        assertEquals(true, HaTimelineChart.isStateColored(light))
    }

    @Test
    fun unitlessBackfillJoinsItsEntitysLine() {
        // The statistics came before the entity's state (no unit then); the history knows it as °C.
        val stats = JSONObject().put("sensor.a", JSONArray().put(JSONObject().put("start", 0).put("end", 3_600_000).put("mean", 5.0)))
        val lts = HaHistory.statisticsToHistory({ null }, stats, listOf("sensor.a"))
        val st = dev.glassworks.engine.vitro.data.EntityState("sensor.a", "7", mapOf("unit_of_measurement" to "°C"))
        val history = HaHistory.compute({ if (it == "sensor.a") st else null }, mapOf("sensor.a" to listOf(HistoryState("7", 5000.0))), listOf("sensor.a"), null)
        val merged = HaHistory.merge(history, lts)
        assertEquals(listOf("°C"), merged.line.map { it.unit })
        assertEquals(listOf("5"), merged.line.single().data.single().statistics!!.map { it.state })
    }

    @Test
    fun legacyLayoutOptionsCarryTheirBoundsLikeHa() {
        fun size(json: String) = dev.glassworks.engine.vitro.ui.HaGridSize.of(dev.glassworks.engine.vitro.model.LovelaceParser.parseCard(JSONObject(json)))
        // migrateLayoutToGridOptions: columns and their bounds x 3, rows as written; they override the type's minimums.
        assertEquals(dev.glassworks.engine.vitro.ui.HaGridSize(3, 2), size("""{"type": "sensor", "entity": "sensor.a", "layout_options": {"grid_columns": 1, "grid_min_columns": 1}}"""))
        assertEquals(dev.glassworks.engine.vitro.ui.HaGridSize(6, 2), size("""{"type": "sensor", "entity": "sensor.a", "layout_options": {"grid_columns": 1}}"""))
        // grid_options wins when both are there.
        assertEquals(dev.glassworks.engine.vitro.ui.HaGridSize(9, 2), size("""{"type": "sensor", "entity": "sensor.a", "grid_options": {"columns": 9}, "layout_options": {"grid_columns": 1, "grid_min_columns": 1}}"""))
    }

    @Test
    fun fitYDataWidensConfiguredBoundsToTheData() {
        fun spec(fit: Boolean) = dev.glassworks.engine.vitro.cards.LineChartSpec(
            listOf(dev.glassworks.engine.vitro.cards.ChartLine(doubleArrayOf(0.0, 1000.0), doubleArrayOf(20.0, 25.5), 0)),
            0L, 1000L, "W", 233f, true, minYAxis = 0.0, maxYAxis = 10.0, fitYData = fit,
        )
        // Fixed: the configured 0..10 stands, and the data is clipped.
        val fixed = dev.glassworks.engine.vitro.cards.HaLineChart.yAxis(spec(false)).first
        assertEquals(0.0, fixed.min, 0.0)
        assertEquals(10.0, fixed.max, 0.0)
        // fit_y_data: the top grows to ceil(25.5) = 26, the bottom keeps 0.
        val fit = dev.glassworks.engine.vitro.cards.HaLineChart.yAxis(spec(true)).first
        assertEquals(0.0, fit.min, 0.0)
        assertEquals(26.0, fit.max, 0.0)
    }

    @Test
    fun aFontContextKeepsTheClockAndTheSubscriptions() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val subscribe: (JSONObject, (JSONObject) -> Unit, (JSONObject?) -> Unit) -> () -> Unit = { _, _, _ -> {} }
        val ctx = dev.glassworks.engine.vitro.ui.VitroCardContext(
            context = activity, haBase = "http://192.0.2.1", store = dev.glassworks.engine.vitro.data.EntityStore(),
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} }, subscribeWs = subscribe, now = { 42L },
        )
        // A card with its own font-family gets a derived context: its graphs still read the same clock and stream.
        val derived = ctx.withFontFamily("Roboto")
        assertEquals(42L, derived.now())
        assertTrue(derived.subscribeWs === subscribe)
    }

    @Test
    fun aFooterWithHistoryButNoNumbersSaysSo() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var onEvent: ((JSONObject) -> Unit)? = null
        val nowMs = 1_800_000_000_000L
        val ctx = dev.glassworks.engine.vitro.ui.VitroCardContext(
            context = activity, haBase = "http://192.0.2.1", store = dev.glassworks.engine.vitro.data.EntityStore(),
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            subscribeWs = { _, ev, _ -> onEvent = ev; {} }, now = { nowMs },
        )
        val footer = GraphFooterView(ctx, JSONObject().put("type", "graph").put("entity", "sensor.power"))
        val root = android.widget.FrameLayout(activity)
        activity.setContentView(root)
        root.addView(footer, android.widget.FrameLayout.LayoutParams(500, 100))
        // The recorder answered: only `unavailable`, nothing to plot. Not loading, not an error: HA's info line.
        onEvent!!(JSONObject().put("states", JSONObject().put("sensor.power", org.json.JSONArray()
            .put(JSONObject().put("s", "unavailable").put("lu", nowMs / 1000.0 - 600)))))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        footer.measure(android.view.View.MeasureSpec.makeMeasureSpec(500, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY))
        footer.layout(0, 0, 500, 100)
        // The stream is merged and the path built on the graph thread.
        drainGraphWork()
        footer.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(500, 100, android.graphics.Bitmap.Config.ARGB_8888)))
        assertEquals(GraphFooterView.NO_HISTORY, footer.contentDescription)
    }

    @Test
    fun aMiniGraphBackOnScreenReadsItsHistoryAtOnce() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var reads = 0
        val ctx = dev.glassworks.engine.vitro.ui.VitroCardContext(
            context = activity, haBase = "http://192.0.2.1", store = dev.glassworks.engine.vitro.data.EntityStore(),
            theme = dev.glassworks.engine.vitro.theme.ThemeResolver.resolve(dev.glassworks.engine.vitro.theme.ThemeCatalog(), true),
            resources = emptyList(), accessToken = { null }, callService = { _, _, _, _ -> }, navigate = {}, openMoreInfo = {},
            fetchHistory = { _, _, _ -> reads++ }, subscribeTemplate = { _, _ -> {} },
        )
        val card = dev.glassworks.engine.vitro.cards.SensorGraphCardView(ctx, dev.glassworks.engine.vitro.model.LovelaceParser.parseCard(
            JSONObject("""{"type": "custom:mini-graph-card", "entities": ["sensor.power"]}"""),
        ))
        val root = android.widget.FrameLayout(activity)
        activity.setContentView(root)
        root.addView(card)
        val first = reads
        assertTrue("the constructor read it", first >= 1)
        root.removeView(card)
        root.addView(card)
        assertEquals("read again on return, not five minutes later", first + 1, reads)
    }

    @Test
    fun aPinnedTopKeepsItsTicksInside() {
        // Pinned just under 0.3: no 0.3 tick past it; the end itself is the last tick.
        val under = ECharts.valueScale(0.0, 0.29999999999, 0.0, 0.29999999999, 0.0, splitNumber = 3)
        assertTrue(under.ticks.toString(), under.ticks.all { it <= 0.29999999999 })
        assertEquals(0.29999999999, under.ticks.last(), 0.0)
        // Pinned at exactly 0.3: the 0.3 tick stays.
        val exact = ECharts.valueScale(0.0, 0.3, 0.0, 0.3, 0.0, splitNumber = 3)
        assertEquals(0.3, exact.ticks.last(), 1e-12)
        assertEquals(1, exact.ticks.count { kotlin.math.abs(it - 0.3) < 1e-12 })
    }

    /** Runs the main looper until every graph job has handed its result back. */
    private fun drainGraphWork() {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (dev.glassworks.engine.vitro.cards.GraphWork.pending.get() == 0) break
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(0, dev.glassworks.engine.vitro.cards.GraphWork.pending.get())
    }
}
