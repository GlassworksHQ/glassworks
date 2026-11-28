/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.HaFormat
import dev.glassworks.engine.vitro.data.HaLocale
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroFonts
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * What Home Assistant's charts get from ECharts 6.1.0 (the version frontend 20261028.4 bundles), ported for the
 * history and statistics graphs: the nice value scale (`util/number.ts` `nice`, `coord/axisNiceTicks.ts`), the
 * calendar-aligned time ticks (`scale/Time.ts`), label overlap hiding (`label/labelLayoutHelper.ts`), step lines
 * (`chart/line/helper.ts`) and the smoothed polyline (`chart/line/poly.ts`). Plus HA's own axis rules
 * (`components/chart/y-axis-fraction-digits.ts`, `axis-label.ts`).
 */
object ECharts {
    const val SECOND = 1000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    /** `nice(val, round)`: 1, 2, 3, 5 or 10 times a power of ten. */
    fun nice(v: Double, roundMode: Boolean): Double {
        if (v == 0.0 || !v.isFinite()) return 1.0
        val exponent = floor(log10(v))
        val exp10 = 10.0.pow(exponent)
        val f = v / exp10
        val nf = if (roundMode) {
            when { f < 1.5 -> 1.0; f < 2.5 -> 2.0; f < 4 -> 3.0; f < 7 -> 5.0; else -> 10.0 }
        } else {
            when { f < 1 -> 1.0; f < 2 -> 2.0; f < 3 -> 3.0; f < 5 -> 5.0; else -> 10.0 }
        }
        val out = nf * exp10
        return if (exponent >= -20) roundTo(out, if (exponent < 0) (-exponent).toInt() else 0) else out
    }

    /** `getPrecision`: how many decimals [v] needs. */
    fun precision(v: Double): Int {
        if (v.isNaN()) return 0
        if (v > 1e-14) {
            var e = 1.0
            for (i in 0 until 15) {
                if (round(v * e) / e == v) return i
                e *= 10
            }
        }
        return BigDecimal(v.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
    }

    /** `round(x, precision)`: `+x.toFixed(precision)`. */
    fun roundTo(x: Double, p: Int): Double =
        if (!x.isFinite()) x else BigDecimal(x).setScale(p.coerceIn(0, 20), RoundingMode.HALF_UP).toDouble()

    /** A value axis: its extent and the values of its ticks. */
    class ValueScale(val min: Double, val max: Double, val ticks: List<Double>)

    /**
     * An interval scale over data [dataMin]..[dataMax] widened by [boundaryGap] of the span on each side unless
     * [fixMin] / [fixMax] pin an end (`scaleRawExtentInfo.ts`), then niced to [splitNumber] intervals.
     */
    fun valueScale(dataMin: Double, dataMax: Double, fixMin: Double?, fixMax: Double?, boundaryGap: Double, splitNumber: Int = 5): ValueScale {
        val span = (dataMax - dataMin).takeIf { it != 0.0 } ?: abs(dataMin)
        var lo = fixMin ?: (dataMin - boundaryGap * span)
        var hi = fixMax ?: (dataMax + boundaryGap * span)
        if (lo > hi) { val t = lo; lo = hi; hi = t }
        if (lo == hi) {
            // intervalScaleEnsureValidExtent
            if (lo != 0.0) {
                val e = abs(lo)
                if (fixMax == null) { hi += e / 2; lo -= e / 2 } else lo -= e / 2
            } else hi = 1.0
        }
        val interval = nice((hi - lo) / splitNumber, true)
        val p = precision(interval)
        if (fixMin == null) lo = roundTo(floor(lo / interval) * interval, p)
        if (fixMax == null) hi = roundTo(ceil(hi / interval) * interval, p)
        val ticks = ArrayList<Double>()
        var t = roundTo(ceil(lo / interval) * interval, p)
        if (t > lo) ticks += lo
        // A pinned top is the end itself: no tolerance past it (a rounded tick above 0.29999… isn't drawn, 0.3 is).
        val last = if (fixMax != null) hi else hi + interval * 1e-9
        while (t <= last && ticks.size < 100) {
            ticks += t
            val next = roundTo(t + interval, p)
            // IntervalScale stops when rounding no longer moves the tick (an interval below what 20 places hold).
            if (next <= t) break
            t = next
        }
        if (ticks.last() < hi) ticks += hi
        return ValueScale(lo, hi, ticks)
    }

    /** One time-axis tick: its value, level (higher wins overlaps) and whether it's an extent end (no label). */
    class TimeTick(val value: Long, val level: Int, val notNice: Boolean)

    private fun hourInterval(a: Double): Int { val h = a / HOUR; return if (h > 12) 12 else if (h > 6) 6 else if (h > 3.5) 4 else if (h > 2) 2 else 1 }
    private fun dateInterval(a: Double): Int { val d = a / DAY; return if (d > 16) 16 else if (d > 7.5) 7 else if (d > 3.5) 4 else if (d > 1.5) 2 else 1 }
    private fun monthInterval(a: Double): Int { val m = a / (DAY * 31); return if (m > 6) 6 else if (m > 3) 3 else if (m > 2) 2 else 1 }
    private fun minSecInterval(a: Double, unit: Long): Int { val v = a / unit; return if (v > 30) 30 else if (v > 20) 20 else if (v > 15) 15 else if (v > 10) 10 else if (v > 5) 5 else if (v > 2) 2 else 1 }

    /**
     * The ticks of a time axis over [min]..[max] in the default time zone (`calcNiceForTimeScale` and
     * `createIntervalTicks`): year, month, day and hour boundaries down to the unit the approximate interval
     * (span / [splitNumber], at least [minInterval]) falls in, stepped by ECharts' interval for that unit inside each
     * boundary of the level above, plus the extent's ends (not nice, never labelled).
     */
    fun timeTicks(min: Long, max: Long, splitNumber: Int = 6, minInterval: Long? = null): List<TimeTick> {
        if (max <= min) return emptyList()
        var approx = (max - min).toDouble() / splitNumber
        if (minInterval != null && approx < minInterval) approx = minInterval.toDouble()
        // The finest level ECharts goes down to (scaleIntervals' unit just below the approximate interval).
        val bottom = when {
            approx < MINUTE -> 5
            approx < HOUR -> 4
            approx < DAY -> 3
            approx < DAY * 28 -> 2
            approx < DAY * 365 -> 1
            else -> 0
        }
        val target = (max - min) / approx
        // The zone the labels are written in (HaFormat): ticks fall on its hours and days.
        val cal = Calendar.getInstance(HaLocale.timeZone())
        // Level boundaries, coarse to fine: year (0), month (1), day (2), hour (3), minute (4), second (5).
        var upper: List<Long> = emptyList()
        val levels = ArrayList<List<Long>>()
        var count = 0
        for (level in 0..bottom) {
            val starts = if (upper.isEmpty()) listOf(firstOfUnit(min, level, cal), max) else upper + max
            val out = ArrayList<Long>()
            for (i in 0 until starts.size - 1) {
                val from = starts[i]
                val to = starts[i + 1]
                if (from == to || to < min || from > max) continue
                cal.timeInMillis = from
                var t = from
                var guard = 0
                while (t < to && t <= max && guard++ < 3000) {
                    out += t
                    when (level) {
                        0 -> cal.add(Calendar.YEAR, max(1, Math.round(approx / DAY / 365).toInt()))
                        1 -> cal.add(Calendar.MONTH, monthInterval(approx))
                        2 -> cal.add(Calendar.DAY_OF_MONTH, dateInterval(approx))
                        3 -> cal.add(Calendar.HOUR_OF_DAY, hourInterval(approx))
                        4 -> cal.add(Calendar.MINUTE, minSecInterval(approx, MINUTE))
                        else -> cal.add(Calendar.SECOND, minSecInterval(approx, SECOND))
                    }
                    t = cal.timeInMillis
                }
                out += t
            }
            val merged = (upper + out).distinct().sorted()
            val inExtent = merged.count { it in min..max }
            val previous = count
            count = inExtent
            if (count > target * 1.5 && previous > target / 1.5) break
            levels += merged
            upper = merged
            if (count > target) break
        }
        val all = levels.lastOrNull().orEmpty()
        val maxLevel = levels.size - 1
        val ticks = ArrayList<TimeTick>()
        for (v in all) {
            if (v < min || v > max) continue
            // The coarsest level a value already belonged to gives it its priority.
            val lv = levels.indexOfFirst { v in it }
            ticks += TimeTick(v, maxLevel - lv, false)
        }
        if (ticks.isEmpty() || ticks.first().value > min) ticks.add(0, TimeTick(min, 0, true))
        if (ticks.last().value < max) ticks += TimeTick(max, 0, true)
        return ticks
    }

    private fun firstOfUnit(t: Long, level: Int, cal: Calendar): Long {
        cal.timeInMillis = t
        if (level <= 0) cal.set(Calendar.MONTH, 0)
        if (level <= 1) cal.set(Calendar.DAY_OF_MONTH, 1)
        if (level <= 2) cal.set(Calendar.HOUR_OF_DAY, 0)
        if (level <= 3) cal.set(Calendar.MINUTE, 0)
        if (level <= 4) cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** `turnPointsIntoStep(…, 'end')`: each point holds until the next one's x, where the line steps. */
    fun stepEnd(xs: DoubleArray, ys: DoubleArray): Pair<DoubleArray, DoubleArray> {
        if (xs.size < 2) return xs to ys
        val ox = ArrayList<Double>(xs.size * 2)
        val oy = ArrayList<Double>(xs.size * 2)
        for (i in 0 until xs.size - 1) {
            ox += xs[i]; oy += ys[i]
            ox += xs[i + 1]; oy += ys[i]
        }
        ox += xs.last(); oy += ys.last()
        return ox.toDoubleArray() to oy.toDoubleArray()
    }

    /**
     * `drawSegment` of `poly.ts` for each run of non-null points, into [path]: straight ([smooth] 0) or smoothed
     * with control points clamped to each segment's box. Points with a NaN coordinate break the line.
     */
    fun polyline(xs: DoubleArray, ys: DoubleArray, smooth: Double, path: Path, scale: Float, reverse: Boolean = false, continuePath: Boolean = false) {
        val n = xs.size
        val idx = if (reverse) (n - 1 downTo 0).toList() else (0 until n).toList()
        var k = 0
        var first = true
        while (k < idx.size) {
            while (k < idx.size && (xs[idx[k]].isNaN() || ys[idx[k]].isNaN())) k++
            if (k >= idx.size) break
            var end = k
            while (end < idx.size && !xs[idx[end]].isNaN() && !ys[idx[end]].isNaN()) end++
            segment(xs, ys, idx.subList(k, end), smooth, path, scale, !(first && continuePath))
            first = false
            k = end
        }
    }

    private fun segment(xs: DoubleArray, ys: DoubleArray, idx: List<Int>, smooth: Double, path: Path, s: Float, move: Boolean) {
        var prevX = 0.0
        var prevY = 0.0
        var cpx0 = 0.0
        var cpy0 = 0.0
        var i = 0
        while (i < idx.size) {
            var x = xs[idx[i]]
            var y = ys[idx[i]]
            if (i == 0) {
                if (move) path.moveTo((x * s).toFloat(), (y * s).toFloat()) else path.lineTo((x * s).toFloat(), (y * s).toFloat())
                cpx0 = x; cpy0 = y
            } else {
                val dx = x - prevX
                val dy = y - prevY
                if (dx * dx + dy * dy < 0.5) { i++; continue }
                if (smooth > 0) {
                    var nextI = i + 1
                    var nextX = if (nextI < idx.size) xs[idx[nextI]] else Double.NaN
                    var nextY = if (nextI < idx.size) ys[idx[nextI]] else Double.NaN
                    while (nextX == x && nextY == y && nextI < idx.size) {
                        i++; nextI++
                        nextX = if (nextI < idx.size) xs[idx[nextI]] else Double.NaN
                        nextY = if (nextI < idx.size) ys[idx[nextI]] else Double.NaN
                        x = xs[idx[i]]; y = ys[idx[i]]
                    }
                    val cpx1: Double
                    val cpy1: Double
                    var nextCpx0 = x
                    var nextCpy0 = y
                    if (nextI >= idx.size || nextX.isNaN() || nextY.isNaN()) {
                        cpx1 = x; cpy1 = y
                    } else {
                        var vx = nextX - prevX
                        var vy = nextY - prevY
                        val dx0 = x - prevX; val dx1 = nextX - x
                        val dy0 = y - prevY; val dy1 = nextY - y
                        val lenPrev = sqrt(dx0 * dx0 + dy0 * dy0)
                        val lenNext = sqrt(dx1 * dx1 + dy1 * dy1)
                        val ratio = lenNext / (lenNext + lenPrev)
                        nextCpx0 = x + vx * smooth * ratio
                        nextCpy0 = y + vy * smooth * ratio
                        nextCpx0 = min(nextCpx0, max(nextX, x)); nextCpy0 = min(nextCpy0, max(nextY, y))
                        nextCpx0 = max(nextCpx0, min(nextX, x)); nextCpy0 = max(nextCpy0, min(nextY, y))
                        vx = nextCpx0 - x; vy = nextCpy0 - y
                        var c1x = x - vx * lenPrev / lenNext
                        var c1y = y - vy * lenPrev / lenNext
                        c1x = min(c1x, max(prevX, x)); c1y = min(c1y, max(prevY, y))
                        c1x = max(c1x, min(prevX, x)); c1y = max(c1y, min(prevY, y))
                        vx = x - c1x; vy = y - c1y
                        nextCpx0 = x + vx * lenNext / lenPrev
                        nextCpy0 = y + vy * lenNext / lenPrev
                        cpx1 = c1x; cpy1 = c1y
                    }
                    path.cubicTo((cpx0 * s).toFloat(), (cpy0 * s).toFloat(), (cpx1 * s).toFloat(), (cpy1 * s).toFloat(), (x * s).toFloat(), (y * s).toFloat())
                    cpx0 = nextCpx0; cpy0 = nextCpy0
                } else {
                    path.lineTo((x * s).toFloat(), (y * s).toFloat())
                }
            }
            prevX = x; prevY = y
            i++
        }
    }
}

/** HA's formatters for chart labels (`formatNumber`, `formatTime`, `formatDateVeryShort`, …), in the user's locale. */
object HaChartFormat {
    /**
     * `formatNumber(value, locale, {minimumFractionDigits, maximumFractionDigits})`, grouped like `Intl`, which rounds
     * ties away from zero (`halfExpand`) on the number's shortest decimal form: 2.5 at no decimals is "3".
     */
    fun number(v: Double, minFraction: Int, maxFraction: Int): String =
        HaFormat.formatNumber(if (v == 0.0) 0.0 else v, HaLocale.current, HaFormat.NumberOptions(minFraction, maxFraction))

    fun time(t: Long): String = HaFormat.formatTime(t)
    fun timeWithSeconds(t: Long): String = HaFormat.formatTimeWithSeconds(t)
    fun dateVeryShort(t: Long): String = HaFormat.formatDateVeryShort(t)
    fun weekdayShort(t: Long): String = HaFormat.formatDateWeekdayShort(t)
    fun month(t: Long): String = HaFormat.formatDateMonth(t)
    fun monthYear(t: Long): String = HaFormat.formatDateMonthYear(t)

    /** `formatTimeLabel`: the label of a time tick, and whether it's bold (`{bold|…}`), for an axis [minutes] wide. */
    fun timeLabel(t: Long, minutes: Double): Pair<String, Boolean> {
        val days = minutes / 60 / 24
        val cal = Calendar.getInstance(HaLocale.timeZone()).apply { timeInMillis = t }
        return when {
            days > 88 -> if (cal.get(Calendar.MONTH) == 0) monthYear(t) to true else month(t) to false
            days > 35 -> dateVeryShort(t) to (cal.get(Calendar.DAY_OF_MONTH) == 1)
            days > 7 -> dateVeryShort(t) to (cal.get(Calendar.DAY_OF_MONTH) == 1)
            days > 2 -> weekdayShort(t) to false
            minutes != 0.0 && minutes < 5 -> timeWithSeconds(t) to false
            cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0 && cal.get(Calendar.SECOND) == 0 -> dateVeryShort(t) to true
            else -> time(t) to false
        }
    }

    /** `computeYAxisFractionDigits`: the decimals of the nice interval for a range split in five. */
    fun yFractionDigits(min: Double, max: Double, includeZero: Boolean = false): Int {
        val lo = if (includeZero) min(min, 0.0) else min
        val hi = if (includeZero) max(max, 0.0) else max
        val range = hi - lo
        if (!range.isFinite() || range <= 0) return 1
        val magnitude = max(abs(lo), abs(hi))
        if (range <= magnitude * 1e-10) return 1
        return ECharts.precision(ECharts.nice(range / 5, true))
    }
}

/**
 * HA's y-axis bounds (`createYAxisPrecisionBounds`): the ends ECharts must keep (zero for a positive series that comes
 * close to it, 100 for a percentage, a flat series' nice extent) and the label decimals.
 */
class HaYBounds(val fixMin: Double?, val fixMax: Double?, val fractionDigits: Int) {
    companion object {
        fun of(dataMin: Double, dataMax: Double, unit: String?, includeZero: Boolean, cfgMin: Double?, cfgMax: Double?): HaYBounds {
            val naturalMax = if (unit == "%") 100.0 else null
            var min = cfgMin
            var max = cfgMax
            var gap = 0.0
            if (!includeZero && dataMin.isFinite() && dataMax.isFinite()) {
                if (dataMin == dataMax) {
                    var lo = dataMin
                    var hi = dataMax
                    if (lo != 0.0) {
                        val e = abs(lo)
                        if (cfgMax == null) { hi += e / 2; lo -= e / 2 } else lo -= e / 2
                    } else hi = 1.0
                    val interval = ECharts.nice((hi - lo) / 5, true)
                    val p = ECharts.precision(interval)
                    val fMin = ECharts.roundTo(floor(lo / interval) * interval, p)
                    val fMax = ECharts.roundTo(ceil(hi / interval) * interval, p)
                    min = cfgMin ?: fMin
                    max = cfgMax ?: if (naturalMax != null && dataMax <= naturalMax) min(fMax, naturalMax) else fMax
                } else {
                    gap = (dataMax - dataMin) * 0.02
                    val floorV = if (dataMin >= 0) 0.0 else null
                    val ceiling = if (naturalMax != null && dataMax <= naturalMax) naturalMax else if (dataMax <= 0) 0.0 else null
                    min = cfgMin ?: if (floorV != null && dataMin - floorV < gap) floorV else null
                    max = cfgMax ?: if (ceiling != null && ceiling - dataMax < gap) ceiling else null
                }
            }
            val digits = HaChartFormat.yFractionDigits(min ?: (dataMin - gap), max ?: (dataMax + gap), includeZero)
            return HaYBounds(min, max, digits)
        }
    }
}

/** The theme's graph colors (`getGraphColorByIndex`): `--graph-color-N`, else HA's `--color-N` palette. */
object HaGraphColors {
    private val PALETTE = intArrayOf(
        0x4269d0, 0xf4bd4a, 0xff725c, 0x6cc5b0, 0xa463f2, 0xff8ab7, 0x9c6b4e, 0x97bbf5, 0x01ab63, 0x094bad,
        0xc99000, 0xd84f3e, 0x49a28f, 0x048732, 0xd96895, 0x8043ce, 0x7599d1, 0x7a4c31, 0x6989f4, 0xffd444,
        0xff957c, 0x8fe9d3, 0x62cc71, 0xffadda, 0xc884ff, 0xbadeff, 0xbf8b6d, 0x927acc, 0x97ee3f, 0xbf3947,
        0x9f5b00, 0xf48758, 0x8caed6, 0xf2b94f, 0xeff26e, 0xe43872, 0xd9b100, 0x9d7a00, 0x698cff, 0x00d27e,
        0xd06800, 0x009f82, 0xc49200, 0xcbe8ff, 0xfecddf, 0xc27eb6, 0x8cd2ce, 0xc4b8d9, 0xf883b0, 0xa49100,
        0xf48800, 0x27d0df, 0xa04a9b, 0x4269d0,
    )

    fun byIndex(theme: ResolvedTheme, index: Int): Int {
        ThemeResolver.parseColor(theme.vars["graph-color-${index + 1}"])?.let { return it }
        val i = index % PALETTE.size
        return ThemeResolver.parseColor(theme.vars["color-${i + 1}"]) ?: (0xFF000000.toInt() or PALETTE[i])
    }

    /** HA's `luminosity` (relative luminance, WCAG). */
    fun luminosity(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }
}

/** What a chart draws, in CSS px, built when its data or width changes; [ChartView] only replays it. */
class ChartScene {
    class Stroke(val path: Path, val color: Int, val width: Float, val clip: RectF?)
    class Fill(val path: Path, val color: Int, val clip: RectF?)
    class Rect(val rect: RectF, val color: Int)
    class Text(val text: String, val x: Float, val cy: Float, val align: Paint.Align, val color: Int, val bold: Boolean, val size: Float = 12f, val maxWidth: Float = 0f)

    val strokes = ArrayList<Stroke>()
    val fills = ArrayList<Fill>()
    val rects = ArrayList<Rect>()
    val texts = ArrayList<Text>()
    /** Draw order: fills, rects and strokes interleaved as added. */
    val order = ArrayList<Any>()

    fun stroke(s: Stroke) { strokes += s; order += s }
    fun fill(f: Fill) { fills += f; order += f }
    fun rect(r: Rect) { rects += r; order += r }
    fun text(t: Text) { texts += t; order += t }
}

/**
 * A chart Home Assistant draws with ECharts, drawn natively: [build] makes its [ChartScene] for a width (CSS px)
 * when the data or the size changes, and drawing only replays the paths. [heightCss] is the chart's own height.
 */
class ChartView(context: Context, private val density: Float) : View(context) {
    private var hCss = 200f

    /** The chart's height (CSS px). The scene is built again when it changes, like when the width does. */
    var heightCss: Float
        get() = hCss
        set(v) { if (hCss != v) { hCss = v; requestLayout() } }

    /**
     * Sets [heightCss] from a parent's measure pass (a graph card in a fixed `grid_options.rows` slot, which shrinks
     * the chart so the chart and its legend fill the slot): the parent measures this view next, so no layout request.
     */
    fun sizeInMeasure(heightCss: Float) { hCss = heightCss }

    private var build: ((Float, Float) -> ChartScene)? = null
    private var scene: ChartScene? = null
    private var builtWidth = -1
    private var builtHeight = Float.NaN
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setScene(builder: (widthCss: Float) -> ChartScene) = setSizedScene { w, _ -> builder(w) }

    /** A scene drawn for the chart's width and height (CSS px). */
    fun setSizedScene(builder: (widthCss: Float, heightCss: Float) -> ChartScene) {
        build = builder
        builtWidth = -1
        ensureScene()
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        ensureScene()
    }

    /**
     * Builds the scene for the current width, once per data or size change, and describes its text (axis names and
     * labels, timeline rows and state labels) as the view's content description: what HA's chart shows as text, for
     * TalkBack and the parity harness, since none of it is a TextView.
     */
    private fun ensureScene() {
        val b = build ?: return
        if (width <= 0 || (builtWidth == width && builtHeight == hCss)) return
        val sc = b(width / density, hCss)
        scene = sc
        builtWidth = width
        builtHeight = hCss
        contentDescription = sc.texts.joinToString(", ") { it.text }.takeIf { it.isNotEmpty() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Math.round(hCss * density))
    }

    override fun onDraw(canvas: Canvas) {
        ensureScene()
        val s = scene ?: return
        canvas.save()
        // HA's chart is a canvas composited where its box falls, a fraction of a pixel off the grid at 1.5x.
        val shift = cardSurface()?.contentSubpixel ?: 0f
        if (shift != 0f) canvas.translate(shift, shift)
        canvas.scale(density, density)
        for (item in s.order) when (item) {
            is ChartScene.Fill -> {
                paint.style = Paint.Style.FILL
                paint.color = item.color
                clipped(canvas, item.clip) { canvas.drawPath(item.path, paint) }
            }
            is ChartScene.Rect -> {
                paint.style = Paint.Style.FILL
                paint.color = item.color
                canvas.drawRect(item.rect, paint)
            }
            is ChartScene.Stroke -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = item.width
                paint.strokeJoin = Paint.Join.BEVEL
                paint.strokeCap = Paint.Cap.BUTT
                paint.color = item.color
                clipped(canvas, item.clip) { canvas.drawPath(item.path, paint) }
            }
            is ChartScene.Text -> drawText(canvas, item)
        }
        canvas.restore()
    }

    private inline fun clipped(canvas: Canvas, clip: RectF?, draw: () -> Unit) {
        if (clip == null) { draw(); return }
        canvas.save()
        canvas.clipRect(clip)
        draw()
        canvas.restore()
    }

    private fun cardSurface(): dev.glassworks.engine.vitro.ui.CardSurface? {
        var p = parent
        while (p != null && p !is dev.glassworks.engine.vitro.ui.CardSurface) p = p.parent
        return p as? dev.glassworks.engine.vitro.ui.CardSurface
    }

    private fun drawText(canvas: Canvas, t: ChartScene.Text) {
        textPaint.typeface = VitroFonts.system(if (t.bold) 700 else 400)
        textPaint.textSize = t.size
        textPaint.color = t.color
        textPaint.textAlign = t.align
        var text = t.text
        if (t.maxWidth > 0 && textPaint.measureText(text) > t.maxWidth) text = truncate(text, t.maxWidth)
        val fm = textPaint.fontMetrics
        canvas.drawText(text, t.x, t.cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    /** ECharts' `overflow: 'truncate'`: cut to fit with an ellipsis. */
    private fun truncate(text: String, maxWidth: Float): String {
        var end = text.length
        while (end > 0 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "" else text.substring(0, end) + "…"
    }

    companion object {
        private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** HA's `measureTextWidth(text, size)`: the width of [text] in its chart font, rounded up (CSS px). */
        fun measure(text: String, size: Float = 12f, bold: Boolean = false): Float =
            ceil(width(text, size, bold).toDouble()).toFloat()

        /** The width of [text] in its chart font as ECharts lays it out, unrounded (CSS px). */
        fun width(text: String, size: Float = 12f, bold: Boolean = false): Float {
            measurePaint.typeface = VitroFonts.system(if (bold) 700 else 400)
            measurePaint.textSize = size
            return measurePaint.measureText(text)
        }
    }
}
