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
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(tz)

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
        assertEquals("8:00 PM" to false, HaChartFormat.timeLabel(nice.first().value, 1440.0))
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
}
