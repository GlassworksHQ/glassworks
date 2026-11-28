/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor

/** One entry of a `history/stream` answer, minimal and compressed (HA's `EntityHistoryState`): state, `lu` in seconds. */
class HistoryState(val s: String, var lu: Double, val a: JSONObject? = null) {
    /** `lc` in seconds, when it differs from [lu]; NaN when absent. */
    var lc: Double = Double.NaN
}

/**
 * The data side of Home Assistant's graphs, ported from its frontend (home-assistant/frontend 20261028.4, the release
 * HA 2026.11.0 ships) so Vitro asks for the same data and draws the same points:
 * `src/data/history.ts` (the `history/stream` request and `HistoryStream`), `src/components/chart/down-sample.ts`
 * (`downSampleLineData`), `src/panels/lovelace/common/graph/coordinates.ts` (`calcPoints`) and `get-path.ts`.
 */
object HaGraph {
    const val HOUR_MS = 3_600_000.0

    /** Domains whose history needs attributes (`NEED_ATTRIBUTE_DOMAINS`, history.ts). */
    private val NEED_ATTRIBUTE_DOMAINS = setOf("climate", "humidifier", "input_datetime", "water_heater", "person", "device_tracker")

    /** `Date.prototype.toISOString`: UTC with milliseconds. */
    fun isoString(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(ms))

    /** `entityIdHistoryNeedsAttributes`: no state yet, or a domain whose history graph reads attributes. */
    fun needsAttributes(entityId: String, hasState: Boolean): Boolean =
        !hasState || entityId.substringBefore('.') in NEED_ATTRIBUTE_DOMAINS

    /**
     * `subscribeHistoryStatesTimeWindow`'s message: the stream of [entityIds] from [hoursToShow] hours before [nowMs].
     * [hasState] says whether the frontend holds a state for an entity (it asks for attributes when it doesn't).
     */
    fun historyStreamMessage(entityIds: List<String>, hoursToShow: Double, nowMs: Long, hasState: (String) -> Boolean): JSONObject =
        JSONObject()
            .put("type", "history/stream")
            .put("entity_ids", JSONArray(entityIds))
            // new Date(new Date().getTime() - 60 * 60 * hoursToShow * 1000): a Date keeps whole milliseconds.
            .put("start_time", isoString(jsDateMs(nowMs - 60.0 * 60.0 * hoursToShow * 1000.0)))
            .put("minimal_response", true)
            .put("significant_changes_only", true)
            .put("no_attributes", !entityIds.any { needsAttributes(it, hasState(it)) })

    /** `new Date(x).getTime()`: a time value is truncated toward zero to whole milliseconds. */
    fun jsDateMs(x: Double): Long = x.toLong()

    /** JavaScript's `Number(string)`: NaN for anything that isn't a whole numeric literal (`""` is 0). */
    fun jsNumber(s: String): Double {
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        when (t) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (isDecimal(t)) return t.toDouble()
        val radix = when {
            t.startsWith("0x") || t.startsWith("0X") -> 16
            t.startsWith("0o") || t.startsWith("0O") -> 8
            t.startsWith("0b") || t.startsWith("0B") -> 2
            else -> return Double.NaN
        }
        return t.substring(2).toBigIntegerOrNull(radix)?.toDouble() ?: Double.NaN
    }

    /**
     * Whether [t] is a decimal literal, `[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?` with ASCII digits, read without a regex:
     * the footer asks it of every point of a day's history. (Android's ICU regex takes any Unicode digit for `\d`, which
     * [String.toDouble] then refuses.)
     */
    internal fun isDecimal(t: String): Boolean {
        val n = t.length
        var i = 0
        if (i < n && (t[i] == '+' || t[i] == '-')) i++
        val intStart = i
        while (i < n && t[i] in '0'..'9') i++
        val intDigits = i - intStart
        var fracDigits = 0
        if (i < n && t[i] == '.') {
            i++
            val fracStart = i
            while (i < n && t[i] in '0'..'9') i++
            fracDigits = i - fracStart
        }
        if (intDigits == 0 && fracDigits == 0) return false
        if (i < n && (t[i] == 'e' || t[i] == 'E')) {
            i++
            if (i < n && (t[i] == '+' || t[i] == '-')) i++
            val expStart = i
            while (i < n && t[i] in '0'..'9') i++
            if (i == expStart) return false
        }
        return i == n
    }

    /** One entity's states of a stream message. */
    fun parseStates(arr: JSONArray?): MutableList<HistoryState> {
        if (arr == null) return ArrayList()
        val out = ArrayList<HistoryState>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.opt("s")
            out += HistoryState(if (s == null || s == JSONObject.NULL) "null" else s.toString(), o.optDouble("lu", o.optDouble("lc", 0.0)), o.optJSONObject("a"))
                .also { it.lc = o.optDouble("lc", Double.NaN) }
        }
        return out
    }

    /** HA's `HistoryStream`: merges the messages of one `history/stream` subscription, dropping what ages out. */
    class Stream(private val hoursToShow: Double?) {
        var combined: LinkedHashMap<String, MutableList<HistoryState>> = LinkedHashMap()
            private set

        /**
         * The stream starts over: its subscription was sent again on a new connection, with a fresh window, and the
         * next message is that window whole (`first` of a held subscription). What came before goes.
         */
        fun restart() {
            combined = LinkedHashMap()
        }

        /** [message] is one event (`{states, start_time, end_time}`); [nowMs] the frontend's clock when it arrives. */
        fun process(message: JSONObject, nowMs: Long): Map<String, List<HistoryState>> {
            val statesObj = message.optJSONObject("states") ?: JSONObject()
            val stream = LinkedHashMap<String, MutableList<HistoryState>>()
            for (k in statesObj.keys()) stream[k] = parseStates(statesObj.optJSONArray(k))
            if (combined.isEmpty()) {
                combined = stream
                return combined
            }
            if (stream.isEmpty()) return combined
            val purgeBefore = hoursToShow?.takeIf { it != 0.0 }?.let { (nowMs - 60 * 60 * it * 1000) / 1000.0 }
            val next = LinkedHashMap<String, MutableList<HistoryState>>()
            fun processEntity(id: String) {
                val inCombined = id in combined
                val fresh = stream[id]
                if (inCombined && fresh != null) {
                    val old = combined.getValue(id)
                    val merged = ArrayList(old).apply { addAll(fresh) }
                    if (fresh.isNotEmpty() && old.isNotEmpty() && fresh[0].lu < old.last().lu) merged.sortBy { it.lu }
                    next[id] = merged
                } else if (inCombined) {
                    next[id] = combined.getValue(id)
                } else {
                    next[id] = fresh ?: ArrayList()
                    return
                }
                if (purgeBefore == null) return
                val kept = ArrayList<HistoryState>()
                var lastExpired: HistoryState? = null
                for (st in next.getValue(id)) if (st.lu < purgeBefore) lastExpired = st else kept += st
                if (lastExpired == null) return
                next[id] = kept
                if (kept.isNotEmpty() && kept[0].lu == purgeBefore) return
                lastExpired.lu = purgeBefore
                lastExpired.lc = Double.NaN
                kept.add(0, lastExpired)
            }
            for (id in combined.keys.toList()) processEntity(id)
            for (id in stream.keys) if (id !in combined) processEntity(id)
            combined = next
            return combined
        }
    }

    // --- down-sample.ts --------------------------------------------------------------------------------------------

    private const val SECOND = 1000.0
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private val FRAME_SIZES: DoubleArray = (
        listOf(1, 2, 3, 5, 10, 20, 30, 50, 100, 200, 300, 500).map { it.toDouble() } +
            listOf(1, 2, 3, 5, 10, 15, 20, 30).map { it * SECOND } +
            listOf(1, 2, 3, 5, 10, 15, 20, 30).map { it * MINUTE } +
            listOf(1, 2, 3, 4, 6, 8, 12).map { it * HOUR } +
            listOf(DAY)
        ).toDoubleArray()

    /** `snapFrameSize`: the largest frame size that divides the clock and isn't above [step]. */
    fun snapFrameSize(step: Double): Double {
        if (step >= DAY) return floor(step / DAY) * DAY
        var snapped = FRAME_SIZES[0]
        for (size in FRAME_SIZES) {
            if (size > step) break
            snapped = size
        }
        return snapped
    }

    /**
     * `downSampleLineData` for `[x, y]` points (y NaN for a gap marker, as the chart data's `null`): at most
     * [maxDetails] frames on absolute time, the mean of each ([useMean]) or its min and max in order.
     */
    fun downSample(data: List<DoubleArray>, maxDetails: Double, minX: Double?, maxX: Double?, useMean: Boolean): List<DoubleArray> {
        if (data.size <= maxDetails) return data
        if (data.isEmpty()) return data
        val min = minX ?: data[0][0]
        val max = maxX ?: data[data.size - 1][0]
        val rawStep = ceil((max - min) / floor(maxDetails))
        if (!rawStep.isFinite() || rawStep <= 0) return data
        val step = snapFrameSize(rawStep)
        if (useMean) {
            val frames = LinkedHashMap<Double, DoubleArray>() // sumX, sumY, count
            for (p in data) {
                val x = p[0]
                val y = p[1]
                if (x.isNaN() || y.isNaN()) continue
                val idx = floor(x / step)
                val f = frames[idx]
                if (f == null) frames[idx] = doubleArrayOf(x, y, 1.0) else { f[0] += x; f[1] += y; f[2] += 1.0 }
            }
            return frames.values.map { doubleArrayOf(it[0] / it[2], it[1] / it[2]) }
        }
        class Frame(var hasValue: Boolean, var minP: DoubleArray, var maxP: DoubleArray, var gap: DoubleArray?)
        val frames = LinkedHashMap<Double, Frame>()
        for (p in data) {
            val x = p[0]
            if (x.isNaN()) continue
            val y = p[1]
            val idx = floor(x / step)
            if (y.isNaN()) {
                val f = frames[idx]
                if (f != null) f.gap = p else frames[idx] = Frame(false, p, p, p)
                continue
            }
            val f = frames[idx]
            when {
                f == null -> frames[idx] = Frame(true, p, p, null)
                f.hasValue -> {
                    if (y < f.minP[1]) f.minP = p
                    if (y > f.maxP[1]) f.maxP = p
                }
                else -> { f.hasValue = true; f.minP = p; f.maxP = p }
            }
        }
        val out = ArrayList<DoubleArray>()
        for (f in frames.values) {
            if (f.hasValue) {
                if (f.minP[0] > f.maxP[0]) out += f.maxP
                out += f.minP
                if (f.minP[0] < f.maxP[0]) out += f.maxP
            }
            val gap = f.gap
            if (gap != null) {
                val lastValueX = if (f.hasValue) maxOf(f.minP[0], f.maxP[0]) else gap[0]
                if (gap[0] >= lastValueX) out += gap
            }
        }
        return out
    }

    // --- coordinates.ts / get-path.ts ------------------------------------------------------------------------------

    /** `calcPoints`: [history] (x ms, y) scaled into [width] x [height], y down, plus the last value held to the right edge. */
    fun calcPoints(history: List<DoubleArray>, width: Double, height: Double, minXLimit: Double?, maxXLimit: Double?, minYLimit: Double?, maxYLimit: Double?): List<DoubleArray> {
        if (history.isEmpty()) return emptyList()
        var minY = minYLimit ?: history[0][1]
        var maxY = maxYLimit ?: history[0][1]
        val minX = minXLimit ?: history[0][0]
        val maxX = maxXLimit ?: history[history.size - 1][0]
        for (p in history) {
            if (p[1] < minY) minY = p[1] else if (p[1] > maxY) maxY = p[1]
        }
        val rangeY = (maxY - minY).takeIf { it != 0.0 && !it.isNaN() } ?: (minY * 0.1)
        maxY += rangeY * 0.1
        minY -= rangeY * 0.1
        if (maxY < 0) {
            maxY = minOf(0.0, maxY)
        } else if (minY < 0) {
            // some values are negative: only the fill's origin moves (the footer doesn't pass it on)
        } else {
            minY = maxOf(0.0, minY)
        }
        val yDenom = (maxY - minY).takeIf { it != 0.0 && !it.isNaN() } ?: 1.0
        val xDenom = (maxX - minX).takeIf { it != 0.0 && !it.isNaN() } ?: 1.0
        val points = ArrayList<DoubleArray>(history.size + 1)
        for (p in history) points += doubleArrayOf((p[0] - minX) / xDenom * width, height - (p[1] - minY) / yDenom * height)
        points += doubleArrayOf(width, points.last()[1])
        return points
    }

    /** `coordinatesMinimalResponseCompressedState`: an entity's states to footer coordinates. */
    fun footerCoordinates(states: List<HistoryState>, width: Double, height: Double, maxDetails: Double, minX: Double?, maxX: Double?, minY: Double?, maxY: Double?, useMean: Boolean): List<DoubleArray> {
        if (states.isEmpty()) return emptyList()
        val mapped = states.map { doubleArrayOf(it.lu * 1000, jsNumber(it.s)) }.filter { !it[1].isNaN() }
        val sampled = downSample(mapped, maxDetails, minX, maxX, useMean)
        return calcPoints(sampled, width, height, minX, maxX, minY, maxY)
    }

    /**
     * `getPath`: a smooth line through [coords], quadratic curves through the midpoints, scaled by [scale] (CSS px to
     * device px). Returns null for no points.
     */
    fun smoothPath(coords: List<DoubleArray>, scale: Float, path: Path = Path()): Path? {
        if (coords.isEmpty()) return null
        path.reset()
        fun x(p: DoubleArray) = (p[0] * scale).toFloat()
        fun y(p: DoubleArray) = (p[1] * scale).toFloat()
        var last = coords[0]
        path.moveTo(x(last), y(last))
        path.lineTo(x(last), y(last))
        for (next in coords) {
            val mx = ((last[0] - next[0]) / 2 + next[0])
            val my = ((last[1] - next[1]) / 2 + next[1])
            // " Zx,Zy Q nx,ny": the Q's control point is the previous point, its end the midpoint (see getPath's tokens).
            path.quadTo(x(last), y(last), (mx * scale).toFloat(), (my * scale).toFloat())
            last = next
        }
        path.quadTo(x(last), y(last), x(last), y(last))
        return path
    }
}
