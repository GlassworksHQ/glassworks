/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.clarity.ClarityStats
import dev.glassworks.engine.vitro.clarity.ClarityStats.Dialog
import dev.glassworks.engine.vitro.clarity.ClarityStats.Look
import dev.glassworks.engine.vitro.clarity.ClarityStats.Usable
import dev.glassworks.testing.RepoFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vitro's Clarity harness grades by stats.js's rules (VitroClarity, docs/perf.md "Vitro"): the same cases as the web
 * harness's (tools/perf/test/stats-cases.json, and page.test.mjs's for usable, free, blocking and the dialog), so a
 * Vitro run's page lines mean what a web run's do.
 */
class ClarityStatsTest {
    @Test
    fun frameStatsMatchesTheSharedCases() {
        val cases = JSONArray(RepoFiles.file("tools/perf/test/stats-cases.json").readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val iv = c.getJSONArray("intervals").let { a -> (0 until a.length()).map { a.getDouble(it) } }
            val vsync = if (c.isNull("vsync_ms")) 0.0 else c.getDouble("vsync_ms")
            val got = ClarityStats.frameStats(iv, vsync)
            val want = c.getJSONObject("expect")
            assertEquals(c.getString("name"), want.keys().asSequence().toSet(), got.keys().asSequence().toSet())
            for (k in want.keys()) assertEquals("${c.getString("name")}: $k", want.getDouble(k), got.getDouble(k), 1e-9)
        }
    }

    @Test
    fun aShortLineGoesWholeAndALongOneInPartsThatJoin() {
        assertEquals(listOf("GWPERF {\"t\":\"done\"}"), ClarityStats.consoleLines("GWPERF", "n:1", "{\"t\":\"done\"}"))
        val names = JSONArray((0 until 200).map { "Küche $it 🌡 Wohnzimmer" })
        val text = JSONObject().put("names", names).toString()
        val lines = ClarityStats.consoleLines("GWPERF", "k:1", text)
        assertTrue(lines.size >= 2)
        val parts = lines.mapIndexed { k, l ->
            val m = Regex("^GWPERF\\+ k:1 (\\d+)/(\\d+) (.*)$", RegexOption.DOT_MATCHES_ALL).find(l)!!
            assertEquals(k, m.groupValues[1].toInt())
            assertEquals(lines.size, m.groupValues[2].toInt())
            assertTrue(l.toByteArray().size <= 1023)
            m.groupValues[3]
        }
        assertFalse(parts.any { it.contains('�') })
        assertEquals(text, parts.joinToString(""))
    }

    private val ok = Look(false, 2, 2, listOf(true, true), listOf(true to 0.0, true to 120.0))

    private fun usableAfter(vararg frames: Look): Boolean {
        var st = Usable()
        for (f in frames) {
            st = ClarityStats.usableStep(f, st, 2)
            if (st.done) return true
        }
        return false
    }

    @Test
    fun usableNeedsTwoFramesWithEverythingDrawnAndNothingMoving() {
        assertFalse(usableAfter(ok))
        assertTrue(usableAfter(ok, ok))
        val launch = Look(true, 2, 2, ok.head, ok.onScreen)
        assertFalse(usableAfter(launch, launch, launch))
        val other = Look(false, 1, 2, ok.head, ok.onScreen)
        assertFalse(usableAfter(other, other, other))
        val missing = Look(false, 2, 2, listOf(true), ok.onScreen)
        assertFalse(usableAfter(missing, missing, missing))
        val blank = Look(false, 2, 2, ok.head, listOf(true to 0.0, false to 120.0))
        assertFalse(usableAfter(blank, blank, blank))
        val none = Look(false, 2, 2, ok.head, emptyList())
        assertFalse(usableAfter(none, none, none))
        val moved = Look(false, 2, 2, ok.head, listOf(true to 0.0, true to 160.0))
        assertFalse(usableAfter(ok, moved, ok, moved))
    }

    @Test
    fun freeFromTheStretchThatLastsTheWindow() {
        val probes = mutableListOf(0.0 to 80.0, 80.0 to 85.0, 85.0 to 300.0, 300.0 to 305.0)
        var t = 305.0
        while (t < 900) {
            probes += t to t + 40
            t += 40
        }
        var start: Double? = null
        var done = false
        val seen = ArrayList<Double?>()
        for ((p, r) in probes) {
            val (s, d) = ClarityStats.freeStep(p, r, start, 500.0)
            start = s
            done = d
            seen += s
            if (d) break
        }
        assertEquals(listOf(null, 85.0, null, 305.0, 305.0), seen.take(5))
        assertTrue(done)
        assertEquals(305.0, start!!, 0.0)
        assertFalse(ClarityStats.freeStep(0.0, 51.0, 0.0, 1.0).second)
    }

    @Test
    fun blockingCountsEachFramePast50Ms() {
        val b = ClarityStats.frameBlocking(listOf(1016.0, 1032.0, 1332.0, 1348.0, 1468.0), 1000.0, 1480.0)!!
        assertEquals(320.0, b.getDouble("blocking_ms"), 0.0)
        assertEquals(2, b.getInt("long_frames"))
        assertEquals(300.0, b.getDouble("longest_ms"), 0.0)
        assertEquals(350.0, ClarityStats.frameBlocking(emptyList(), 1000.0, 1400.0)!!.getDouble("blocking_ms"), 0.0)
        assertNull(ClarityStats.frameBlocking(listOf(1.0), null, 2.0))
        assertNull(ClarityStats.frameBlocking(listOf(1016.0), 1000.0, 999.0))
        assertEquals(0, ClarityStats.frameBlocking(emptyList(), 1000.0, 1050.0)!!.getInt("long_frames"))
        assertEquals(0.5, ClarityStats.frameBlocking(emptyList(), 1000.0, 1050.5)!!.getDouble("blocking_ms"), 0.0)
    }

    /** page.test.mjs fakeDialog: opened before frame [openedAt], shown before [shownAt], the graph from [graphAt]. */
    private fun fakeDialog(openedAt: Int, shownAt: Int, graphAt: Int?, slow: Map<Int, Double> = emptyMap()): Pair<Dialog, List<Double>> {
        var st = Dialog()
        var t = 1000.0
        val stamps = ArrayList<Double>()
        var k = 1
        while (k <= 60 && !st.done) {
            t += slow[k] ?: (1000.0 / 60)
            stamps += t
            val graph = if (graphAt == null) null else (st.graph != null || k >= graphAt)
            st = ClarityStats.dialogStep(k >= openedAt, k >= shownAt, graph, t, st)
            k++
        }
        return st to stamps
    }

    @Test
    fun aDialogSettlesWhenShownWithItsGraph() {
        var (st, stamps) = fakeDialog(1, 19, null)
        assertEquals(listOf(stamps[0], stamps[18], null, stamps[18]), listOf(st.opened, st.shown, st.graph, st.settled))
        fakeDialog(1, 19, 40).let { (s, t) -> assertEquals(t[39], s.settled!!, 0.0) }
        fakeDialog(1, 19, Int.MAX_VALUE).let { (s, _) -> assertNull(s.settled) }
        st = ClarityStats.dialogStep(true, false, false, 9.0, ClarityStats.dialogStep(true, false, false, 5.0, Dialog()))
        assertEquals(5.0, st.opened!!, 0.0)
    }

    @Test
    fun dialogTimesAsTheWebHarnessGivesThem() {
        val (st, stamps) = fakeDialog(4, 22, 6, mapOf(10 to 50.0))
        val d = ClarityStats.dialogTimes(1000.0, stamps, st, 2990.0, 3300.0, listOf(3000.0, 3016.7, 3033.3, 3300.0), 1000.0 / 60)
        assertTrue(d.getBoolean("opened"))
        assertEquals(ClarityStats.round1(stamps[3] - 1000), d.getDouble("open_ms"), 0.0)
        assertEquals(stamps[21] - 1000, d.getDouble("settled_ms"), 0.0)
        assertEquals(18, d.getJSONObject("anim").getInt("n"))
        assertEquals(2, d.getInt("dropped"))
        assertEquals(50.0, d.getDouble("longest_frame_ms"), 0.0)
        assertEquals(310.0, d.getDouble("close_ms"), 0.0)
        assertEquals(266.7, d.getDouble("close_longest_ms"), 0.0)
        val never = ClarityStats.dialogTimes(0.0, listOf(16.7, 33.3), Dialog(), null, null, emptyList(), 1000.0 / 60)
        assertFalse(never.getBoolean("opened"))
        assertTrue(never.isNull("settled_ms") && never.isNull("close_ms"))
        assertEquals(0, never.getJSONObject("anim").getInt("n"))
        val late = ClarityStats.dialogTimes(0.0, listOf(2000.04), Dialog(2000.04, 2000.04, null, 2000.04, true), null, null,
            emptyList(), 1000.0 / 60)
        assertEquals(2000.04, late.getDouble("settled_ms"), 0.0)
    }

    @Test
    fun theStoreTellsTheHarnessEachChangeBeforeAndAfterTheCards() {
        val store = dev.glassworks.engine.vitro.data.EntityStore()
        val seen = ArrayList<String>()
        store.subscribe("sensor.clarity_1") { seen += "card" }
        store.contextTap = object : dev.glassworks.engine.vitro.data.EntityStore.ContextTap {
            override fun read(entityId: String, context: String?) { seen += "read $entityId $context" }
            override fun delivered(entityId: String, context: String?) { seen += "delivered $entityId $context" }
        }
        store.applyCompressedEvent(JSONObject("""{"a":{"sensor.clarity_1":{"s":"1.0"}}}"""))
        seen.clear()
        store.applyCompressedEvent(JSONObject("""{"c":{"sensor.clarity_1":{"+":{"s":"1.2","c":"01JC1ARITYS000020000070003"}}}}"""))
        // Read before the cards hear of it, delivered after: a change's tick can't come before its card has it.
        assertEquals(listOf("read sensor.clarity_1 01JC1ARITYS000020000070003", "card",
            "delivered sensor.clarity_1 01JC1ARITYS000020000070003"), seen)
        store.contextTap = null
        seen.clear()
        store.applyCompressedEvent(JSONObject("""{"c":{"sensor.clarity_1":{"+":{"s":"1.3"}}}}"""))
        assertEquals(listOf("card"), seen)
    }

    @Test
    fun aTapCountsOnlyWithItsDownAndItsUp() {
        // CodeRabbit on #835: a down whose frame came before the up arrived isn't a finished tap.
        val taps = dev.glassworks.engine.vitro.clarity.Taps()
        taps.start()
        val fast = taps.down(1000.0)!!
        taps.downDone(fast, 1020.0)
        taps.upDone(taps.up(1100.0)!!, 1130.0)
        // The slow one: down, its frame, then counting stops before its up arrives.
        val slow = taps.down(2000.0)!!
        taps.downDone(slow, 2010.0)
        taps.stop()
        assertNull("a down after counting stopped is no tap", taps.down(2050.0))
        assertTrue("down only: unfinished", taps.unfinished)
        assertEquals("only the finished tap has a time", listOf(32.0), taps.times())
        // Its up comes late (after counting stopped), then its frame: now it counts, with the up's time.
        val up = taps.up(2600.0)
        assertSame(slow, up)
        assertTrue("its up's frame hasn't come", taps.unfinished)
        taps.upDone(up!!, 2900.0)
        assertFalse(taps.unfinished)
        assertEquals(2, taps.count)
        assertEquals(listOf(32.0, 304.0), taps.times())
    }

    @Test
    fun aCancelledTapIsNeverEndedByAnotherGesturesUp() {
        // CodeRabbit on #835: Android's ACTION_CANCEL ends the gesture; a later gesture's up isn't that tap's.
        val taps = dev.glassworks.engine.vitro.clarity.Taps()
        taps.start()
        val cancelled = taps.down(1000.0)!!
        taps.downDone(cancelled, 1016.0)
        taps.cancel()
        taps.stop()
        assertNull("the next gesture's down isn't counted", taps.down(1500.0))
        assertNull("and its up ends no tap", taps.up(1600.0))
        assertTrue("the cancelled tap stays unfinished", taps.unfinished)
        assertEquals(emptyList<Double>(), taps.times())
        // A gesture that began before counting stopped, and wasn't cancelled, still takes its late up.
        taps.start()
        val late = taps.down(2000.0)!!
        taps.downDone(late, 2016.0)
        taps.stop()
        taps.upDone(taps.up(2400.0)!!, 2420.0)
        assertEquals(listOf(24.0), taps.times())
    }

    @Test
    fun aBlockingSpanWithDroppedFramesIsNotMeasured() {
        // CodeRabbit on #835: frames dropped to bound the list inside the span would read as one long gap.
        val frames = listOf(1016.0, 1032.0, 1048.0)
        assertEquals(0.0, ClarityStats.blocking(frames, null, 1000.0, 1050.0)!!.getDouble("blocking_ms"), 0.0)
        assertEquals("dropped before the span: measured", 0.0,
            ClarityStats.blocking(frames, 990.0, 1000.0, 1050.0)!!.getDouble("blocking_ms"), 0.0)
        assertNull("dropped inside it: not measured", ClarityStats.blocking(listOf(1048.0), 1032.0, 1000.0, 1050.0))
    }

    @Test
    fun tapsRoundAsEventTimingDoes() {
        assertEquals(16.0, ClarityStats.eventTimingDuration(3.0), 0.0)
        assertEquals(48.0, ClarityStats.eventTimingDuration(45.0), 0.0)
        assertEquals(208.0, ClarityStats.eventTimingDuration(205.0), 0.0)
    }

    /** Pictures every [every] ms from [from] through [to]. */
    private fun steady(from: Double, to: Double, every: Double) = generateSequence(from) { it + every }.takeWhile { it <= to }.toList()

    @Test
    fun aFreezeIsLibwebrtcsGapAtLeastThreeMeansOrTheMeanPlus150() {
        // 15 fps, gaps of 66 ms. The new gap joins the mean first (16 gaps, rounded down): 215 ms makes it 75, so a freeze
        // would be at least 225 ms; 230 makes it 76, and 230 is at least max(228, 226).
        val before = steady(0.0, 990.0, 66.0)
        val under = ClarityStats.freezeGaps(before + steady(990.0 + 215, 3000.0, 66.0), 0.0, 3000.0)
        assertEquals(0, under.freezes)
        assertEquals(0.0, under.stallTotalMs, 0.0)
        assertEquals(215.0, under.maxGapMs, 0.0)
        val over = ClarityStats.freezeGaps(before + steady(990.0 + 230, 3000.0, 66.0), 0.0, 3000.0)
        assertEquals(1, over.freezes)
        assertEquals(230.0, over.stallTotalMs, 0.0)
        assertEquals(listOf(990.0 to 1220.0), over.stallsAt)
        // At 5 fps (200 ms, so 3 × the mean rules): 740 ms isn't a freeze (the mean 249, 747), 760 is (250, 750).
        val slow = steady(0.0, 2000.0, 200.0)
        assertEquals(0, ClarityStats.freezeGaps(slow + steady(2740.0, 4000.0, 200.0), 0.0, 4000.0).freezes)
        assertEquals(1, ClarityStats.freezeGaps(slow + steady(2760.0, 4000.0, 200.0), 0.0, 4000.0).freezes)
    }

    @Test
    fun noFreezeIsTakenBeforeFiveGapsAndItsMeanIsTheLast30() {
        // The 4th gap is long, but the rule needs 5 gaps: no freeze.
        assertEquals(0, ClarityStats.freezeGaps(listOf(0.0, 66.0, 132.0, 198.0, 900.0, 966.0), 0.0, 966.0).freezes)
        // 30 gaps of 500 ms, then 30 of 66: the mean is the last 30's, so a 300 ms gap after them is a freeze.
        val t = steady(0.0, 15_000.0, 500.0) + steady(15_066.0, 15_000.0 + 30 * 66, 66.0)
        val g = ClarityStats.freezeGaps(t + (t.last() + 300), 0.0, t.last() + 300)
        assertEquals(1, g.freezes)
    }

    @Test
    fun theWindowsEdgesCountAsTheWebEnginesSamplesDo() {
        // Only the part of a freeze inside the window counts.
        val t = steady(0.0, 2000.0, 66.0) + steady(3000.0, 6000.0, 66.0)
        val g = ClarityStats.freezeGaps(t, 2500.0, 6000.0)
        assertEquals(1, g.freezes)
        assertEquals(3000.0 - 2500.0, g.stallTotalMs, 0.0)
        // Nothing drawn after 4 s of a 6 s window: a freeze still going on, to the window's end.
        val end = ClarityStats.freezeGaps(steady(0.0, 4000.0, 66.0), 1000.0, 6000.0)
        assertEquals(1, end.freezes)
        assertEquals(6000.0 - 3960.0, end.stallTotalMs, 0.0)
        assertEquals(end.stallTotalMs, end.maxGapMs, 0.0)
        // Nothing drawn before the window: from its start to the first picture is a stall past 200 ms, as an interval
        // with nothing decoded; under it, the sampling's.
        assertEquals(300.0, ClarityStats.freezeGaps(steady(1300.0, 3000.0, 66.0), 1000.0, 3000.0).stallTotalMs, 0.0)
        assertEquals(0.0, ClarityStats.freezeGaps(steady(1100.0, 3000.0, 66.0), 1000.0, 3000.0).stallTotalMs, 0.0)
        // No picture at all: the whole window.
        val none = ClarityStats.freezeGaps(emptyList(), 1000.0, 3000.0)
        assertEquals(2000.0, none.stallTotalMs, 0.0)
        assertEquals(2000.0, none.maxGapMs, 0.0)
    }

    @Test
    fun hlsGapsAreTheWebEnginesVideoGaps() {
        val g = ClarityStats.videoGaps(listOf(100.0, 500.0, 1100.0, 1300.0, 5000.0), 0.0, 2000.0)
        assertEquals(700.0, g.maxGapMs, 0.0)
        assertEquals(600.0 + 700.0, g.stallTotalMs, 0.0)
        assertEquals(listOf(1300.0 to 2000.0, 500.0 to 1100.0), g.stallsAt)
        assertNull(g.freezes)
    }
}
