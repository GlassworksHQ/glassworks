/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.clarity

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Clarity's frame statistics and timing rules, as `tools/perf/page/stats.js` defines them for the web harness
 * (docs/perf.md). [VitroClarity] measures Vitro with these, so a Vitro run's page lines mean what a web run's do.
 * `ClarityStatsTest` holds them to `tools/perf/test/stats-cases.json`, the cases stats.js and perflib.py pass.
 */
internal object ClarityStats {
    const val BUDGET_MS = 1000.0 / 60
    private const val FROZEN_MS = 700.0

    /** A page line longer than this many bytes of UTF-8 goes out in numbered parts (stats.js LINE_MAX). */
    const val LINE_MAX = 900

    /** JavaScript's Math.round: halves go up, toward +∞. */
    private fun jsRound(x: Double): Double = floor(x + 0.5)

    fun round1(x: Double): Double = jsRound(x * 10) / 10
    fun round2(x: Double): Double = jsRound(x * 100) / 100

    fun nearestRank(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val i = max(0, ceil(p * sorted.size).toInt() - 1)
        return sorted[min(i, sorted.size - 1)]
    }

    fun intervals(timestamps: List<Double>): List<Double> = (1 until timestamps.size).map { timestamps[it] - timestamps[it - 1] }

    /**
     * stats.js frameStats: intervals snapped to whole refreshes before the percentile, so a frame that made its
     * refresh isn't over budget by timer jitter.
     */
    fun frameStats(intervalsMs: List<Double>, vsyncMs: Double): JSONObject {
        val period = if (vsyncMs > 0) vsyncMs else BUDGET_MS
        val xs = intervalsMs.filter { it.isFinite() && it > 0 }
        val n = xs.size
        if (n == 0) return JSONObject().put("n", 0)
        val snapped = xs.map { max(1.0, jsRound(it / period)) * period }
        val raw = xs.sorted()
        val q = snapped.sorted()
        val over = snapped.count { it > BUDGET_MS + 0.5 }
        val frozen = xs.count { it > FROZEN_MS }
        return JSONObject()
            .put("n", n)
            .put("median_ms", round2(nearestRank(raw, 0.5)!!))
            .put("p95_ms", round2(nearestRank(q, 0.95)!!))
            .put("p95_raw_ms", round2(nearestRank(raw, 0.95)!!))
            .put("max_ms", round2(raw[n - 1]))
            .put("fps", round2(1000.0 * n / xs.sum()))
            .put("jank_pct", round2(100.0 * over / n))
            .put("frozen_pct", round2(100.0 * frozen / n))
    }

    /** The frames of a window: 100 refreshes (SurfaceFlinger keeps 127, and is read after the window). */
    fun measureMs(vsyncMs: Double): Double = floor(100 * (if (vsyncMs > 0) vsyncMs else BUDGET_MS))

    /** Refreshes a run of frame intervals missed: an interval of k refreshes missed k - 1. */
    fun missedRefreshes(intervalsMs: List<Double>, vsyncMs: Double): Int {
        val period = if (vsyncMs > 0) vsyncMs else BUDGET_MS
        return intervalsMs.sumOf { max(0, jsRound(it / period).toInt() - 1) }
    }

    /** One look at the first screen (stats.js usableStep): [head] and [onScreen] cards, each drawn or not. */
    class Look(
        val launch: Boolean,
        val viewIndex: Int?,
        val wantIndex: Int?,
        val head: List<Boolean>,
        val onScreen: List<Pair<Boolean, Double>>,
    )

    data class Usable(val last: String = "", val stable: Int = 0, val done: Boolean = false)

    /**
     * stats.js usableStep: done when, two frames in a row, the loading status is gone, the view shown is the one
     * asked for, every head card and every card on the screen is drawn, and the layout hasn't moved.
     */
    fun usableStep(f: Look, prev: Usable, headCards: Int): Usable {
        val no = Usable()
        if (f.launch || f.viewIndex != f.wantIndex) return no
        if (f.head.size < headCards || !f.head.take(headCards).all { it }) return no
        if (f.onScreen.isEmpty() || !f.onScreen.all { it.first }) return no
        val key = "${f.onScreen.size}:" + f.onScreen.joinToString(",") { jsRound(it.second).toLong().toString() }
        val stable = if (key == prev.last) prev.stable + 1 else 0
        return Usable(key, stable, stable >= 1)
    }

    /**
     * stats.js freeStep: the main thread is free once every task posted back to back ran within 50 ms (RAIL's input
     * budget) for [windowMs]. Returns when the free stretch began (null: not now) and whether it is long enough.
     */
    fun freeStep(posted: Double, ran: Double, start: Double?, windowMs: Double): Pair<Double?, Boolean> {
        if (ran - posted > 50) return null to false
        val s = start ?: ran
        return s to (ran - s >= windowMs)
    }

    /** stats.js frameBlocking: from [start] to [end], each frame's time past 50 ms, added up (TBT on frames). */
    fun frameBlocking(times: List<Double>, start: Double?, end: Double?): JSONObject? {
        if (start == null || end == null || end < start) return null
        val pts = listOf(start) + times.filter { it > start && it < end } + listOf(end)
        var blocking = 0.0
        var n = 0
        var longest = 0.0
        for (i in 1 until pts.size) {
            val gap = pts[i] - pts[i - 1]
            longest = max(longest, gap)
            if (gap > 50) {
                blocking += gap - 50
                n++
            }
        }
        return JSONObject().put("blocking_ms", round2(blocking)).put("long_frames", n).put("longest_ms", round2(longest))
    }

    data class Dialog(
        val opened: Double? = null,
        val shown: Double? = null,
        val graph: Double? = null,
        val settled: Double? = null,
        val done: Boolean = false,
    )

    /**
     * stats.js dialogStep: each mark is the first frame in which it held; settled is the first frame in which the
     * dialog is shown and, if it has a graph ([graph] not null), the graph drawn.
     */
    fun dialogStep(opened: Boolean, shown: Boolean, graph: Boolean?, ts: Double, prev: Dialog): Dialog {
        var st = prev
        if (opened && st.opened == null) st = st.copy(opened = ts)
        if (shown && st.shown == null) st = st.copy(shown = ts)
        if (graph == true && st.graph == null) st = st.copy(graph = ts)
        if (st.shown != null && (graph == null || st.graph != null)) st = st.copy(settled = ts, done = true)
        return st
    }

    /** stats.js dialogTimes: one dialog's timing points, ms after [t0]. */
    fun dialogTimes(
        t0: Double,
        frames: List<Double>,
        st: Dialog,
        c0: Double?,
        closed: Double?,
        closeFrames: List<Double>,
        vsyncMs: Double,
    ): JSONObject {
        fun after(t: Double?, from: Double): Any = if (t == null) JSONObject.NULL else round1(max(0.0, t - from))
        val end = st.shown ?: frames.lastOrNull()
        val animStamps = if (st.opened == null || end == null) emptyList() else frames.filter { it >= st.opened && it <= end }
        val anim = intervals(animStamps)
        val openStamps = listOf(t0) + frames.filter { it > t0 && (st.settled == null || it <= st.settled) }
        val close = if (c0 == null) null else intervals(listOf(c0) + closeFrames.filter { it > c0 && (closed == null || it <= closed) })
        return JSONObject()
            .put("opened", st.opened != null)
            .put("open_ms", after(st.opened, t0))
            .put("shown_ms", after(st.shown, t0))
            .put("graph_ms", after(st.graph, t0))
            // Graded: unrounded, so rounding can't carry it under a bar.
            .put("settled_ms", st.settled?.let { max(0.0, it - t0) } ?: JSONObject.NULL)
            .put("anim", frameStats(anim, vsyncMs))
            .put("anim_intervals", JSONArray(anim.map { round1(it) }))
            .put("dropped", missedRefreshes(anim, vsyncMs))
            .put("longest_frame_ms", if (openStamps.size > 1) round1(intervals(openStamps).max()) else JSONObject.NULL)
            .put("close_ms", if (c0 == null) JSONObject.NULL else after(closed, c0))
            .put("close_longest_ms", if (close != null && close.isNotEmpty()) round1(close.max()) else JSONObject.NULL)
    }

    /**
     * stats.js consoleLines: a line over [LINE_MAX] bytes of UTF-8 goes out in numbered parts, `TAG+ key k/n chunk`,
     * split on whole code points, never next to whitespace (logcat may trim it). perflib.PageLines joins them.
     */
    fun consoleLines(tag: String, key: String, text: String): List<String> {
        fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size
        if (utf8(text) <= LINE_MAX) return listOf("$tag $text")
        val chars = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            chars += text.substring(i, i + n)
            i += n
        }
        val sizes = chars.map(::utf8)
        val ws = { s: String -> s.isNotEmpty() && Character.isWhitespace(s.codePointAt(0)) }
        val parts = ArrayList<String>()
        i = 0
        while (i < chars.size) {
            var j = i
            var bytes = 0
            while (j < chars.size && bytes + sizes[j] <= LINE_MAX) bytes += sizes[j++]
            while (j < chars.size && j > i + 1 && (ws(chars[j - 1]) || ws(chars[j]))) j--
            parts += chars.subList(i, j).joinToString("")
            i = j
        }
        return parts.mapIndexed { k, p -> "$tag+ $key $k/${parts.size} $p" }
    }

    /** Event Timing's duration rounding (8 ms granularity), and its 16 ms floor: shorter taps aren't reported. */
    fun eventTimingDuration(ms: Double): Double = max(16.0, (ms / 8).roundToLong() * 8.0)
}
