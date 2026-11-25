/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.theme

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A CSS `background` value as the browser paints it: gradient layers over a color, first layer on top. Parses
 * `linear-gradient()` (angle or `to <side>`) and `radial-gradient()` (`circle` / `ellipse`, a length or two, the
 * `closest-` / `farthest-` keywords and `at <position>`), with color stops at `%` or `px`. [geometry] works out each
 * layer's shader geometry for a box, in the same units as the box (lengths are CSS px times [scale]).
 */
class CssBackground(val layers: List<Layer>, val color: Int?) {
    sealed class Layer {
        abstract val stops: List<Stop>
    }

    /** One color stop; [pos] is a fraction of the gradient ray, or null to spread evenly, [px] a CSS px offset. */
    data class Stop(val color: Int, val pos: Float?, val px: Float?)

    /**
     * [corner]: a `to top right` style direction, as (vertical, horizontal) signs (-1 top/left, 1 bottom/right). Its
     * angle depends on the box (the line through the two other corners is perpendicular to the gradient), so it is
     * resolved in [geometry]; [angleDeg] is then its angle for a square box.
     */
    data class Linear(val angleDeg: Float, override val stops: List<Stop>, val corner: Pair<Int, Int>? = null) : Layer()

    /** [sizeX] / [sizeY]: explicit radii (`px`, or `%` of the box when [sizeXPct]); null uses [extent]. */
    data class Radial(
        val circle: Boolean,
        val extent: String,
        val sizeX: Float?,
        val sizeY: Float?,
        val sizeXPct: Boolean,
        val sizeYPct: Boolean,
        val posX: Length,
        val posY: Length,
        override val stops: List<Stop>,
    ) : Layer()

    data class Length(val value: Float, val pct: Boolean)

    /** Where a layer's gradient sits in a [w] x [h] box: the ray (linear: start and end; radial: center and radii). */
    data class Geometry(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val rx: Float, val ry: Float, val rayLength: Float,
        val colors: IntArray, val positions: FloatArray,
    )

    fun geometry(layer: Layer, w: Float, h: Float, scale: Float): Geometry? = when (layer) {
        is Linear -> {
            val a = layer.corner?.let { (v, hz) -> kotlin.math.atan2((hz * h).toDouble(), (-v * w).toDouble()) }
                ?: Math.toRadians(layer.angleDeg.toDouble())
            val len = (abs(w * sin(a)) + abs(h * cos(a))).toFloat()
            val dx = (sin(a) * len / 2).toFloat()
            val dy = (-cos(a) * len / 2).toFloat()
            val (c, p) = resolveStops(layer.stops, len, scale) ?: return@geometry null
            Geometry(w / 2 - dx, h / 2 - dy, w / 2 + dx, h / 2 + dy, 0f, 0f, len, c, p)
        }
        is Radial -> {
            val cx = if (layer.posX.pct) w * layer.posX.value / 100f else layer.posX.value * scale
            val cy = if (layer.posY.pct) h * layer.posY.value / 100f else layer.posY.value * scale
            val sideX = listOf(abs(cx), abs(w - cx))
            val sideY = listOf(abs(cy), abs(h - cy))
            var rx: Float
            var ry: Float
            if (layer.sizeX != null) {
                rx = if (layer.sizeXPct) w * layer.sizeX / 100f else layer.sizeX * scale
                ry = layer.sizeY?.let { if (layer.sizeYPct) h * it / 100f else it * scale } ?: rx
            } else {
                val corners = listOf(hypot(cx, cy), hypot(w - cx, cy), hypot(cx, h - cy), hypot(w - cx, h - cy))
                when (layer.extent) {
                    "closest-side" -> { rx = if (layer.circle) min(sideX.min(), sideY.min()) else sideX.min(); ry = if (layer.circle) rx else sideY.min() }
                    "farthest-side" -> { rx = if (layer.circle) max(sideX.max(), sideY.max()) else sideX.max(); ry = if (layer.circle) rx else sideY.max() }
                    "closest-corner" -> if (layer.circle) { rx = corners.min(); ry = rx } else {
                        rx = sideX.min() * SQRT2; ry = sideY.min() * SQRT2
                    }
                    else -> if (layer.circle) { rx = corners.max(); ry = rx } else {
                        rx = sideX.max() * SQRT2; ry = sideY.max() * SQRT2
                    }
                }
            }
            rx = rx.coerceAtLeast(0.5f)
            ry = ry.coerceAtLeast(0.5f)
            val (c, p) = resolveStops(layer.stops, rx, scale) ?: return@geometry null
            Geometry(cx, cy, cx, cy, rx, ry, rx, c, p)
        }
    }

    companion object {
        private val WHITESPACE = Regex("""\s+""")
        private val SQRT2 = sqrt(2f)
        private val FN = Regex("""^(repeating-)?(linear|radial)-gradient\((.*)\)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val WS = Regex("""\s+""")
        private val LEN = Regex("""^(-?[\d.]+)(px|%)?$""")

        /** Parses a `background` value; null when it holds no gradient (a plain color or an image). */
        fun parse(spec: String?): CssBackground? {
            // Theme YAML can fold a long value over several lines: whitespace runs are one space, as in CSS.
            val s = spec?.replace(WS, " ")?.trim().orEmpty()
            if ("gradient(" !in s.lowercase(Locale.ROOT)) return null
            val parts = splitTopLevel(s, ',')
            val layers = ArrayList<Layer>()
            var color: Int? = null
            for ((i, raw) in parts.withIndex()) {
                var p = raw.trim()
                var m = FN.find(p)
                if (m == null && i == parts.lastIndex) {
                    // The final layer may carry the background color, alone (`..., #0a0b0f`) or next to its image
                    // (`linear-gradient(transparent, #fff) #123456`).
                    val toks = splitTopLevel(p, ' ').map { it.trim() }
                    val image = toks.firstOrNull { FN.matches(it) }
                    toks.filter { it !== image }.firstNotNullOfOrNull { ThemeResolver.parseColor(it) }?.let { color = it }
                    if (image != null) {
                        p = image
                        m = FN.find(p)
                    }
                }
                if (m == null) continue
                if (m.groupValues[1].isNotEmpty()) return null
                val layer = if (m.groupValues[2].equals("linear", true)) linear(m.groupValues[3]) else radial(m.groupValues[3])
                layer?.let(layers::add)
            }
            return if (layers.isEmpty()) null else CssBackground(layers, color)
        }

        private fun linear(body: String): Linear? {
            val args = splitTopLevel(body, ',').map { it.trim() }
            if (args.isEmpty()) return null
            val first = args[0].lowercase(Locale.ROOT)
            val angle = when {
                first.endsWith("deg") -> first.removeSuffix("deg").trim().toFloatOrNull()
                first.endsWith("turn") -> first.removeSuffix("turn").trim().toFloatOrNull()?.times(360f)
                first.endsWith("rad") -> first.removeSuffix("rad").trim().toFloatOrNull()?.let { Math.toDegrees(it.toDouble()).toFloat() }
                first.startsWith("to ") -> sideAngle(first.removePrefix("to ").trim())
                else -> null
            }
            val stops = stops(if (angle != null) args.drop(1) else args) ?: return null
            return Linear(angle ?: 180f, stops, if (first.startsWith("to ")) corner(first.removePrefix("to ").trim()) else null)
        }

        /** A corner direction (`top right`) as (vertical, horizontal) signs; null for a side (`top`) or nothing. */
        private fun corner(side: String): Pair<Int, Int>? {
            val words = side.split(WHITESPACE).toSet()
            val v = when { "top" in words -> -1; "bottom" in words -> 1; else -> 0 }
            val hz = when { "left" in words -> -1; "right" in words -> 1; else -> 0 }
            return if (v != 0 && hz != 0) v to hz else null
        }

        private fun sideAngle(side: String): Float? {
            val words = side.split(WHITESPACE).toSet()
            val v = when { "top" in words -> -1; "bottom" in words -> 1; else -> 0 }
            val hz = when { "left" in words -> -1; "right" in words -> 1; else -> 0 }
            return when {
                v == 0 && hz == 0 -> null
                v == -1 && hz == 0 -> 0f
                v == 0 && hz == 1 -> 90f
                v == 1 && hz == 0 -> 180f
                v == 0 && hz == -1 -> 270f
                v == -1 && hz == 1 -> 45f
                v == 1 && hz == 1 -> 135f
                v == 1 && hz == -1 -> 225f
                else -> 315f
            }
        }

        private fun radial(body: String): Radial? {
            val args = splitTopLevel(body, ',').map { it.trim() }
            if (args.isEmpty()) return null
            val head = args[0].lowercase(Locale.ROOT)
            // The first argument is a color stop when it starts with a color, functional ones (`rgba(0, 0, 0, .5) 10%`)
            // included, so split it outside parentheses.
            val firstToken = splitTopLevel(head, ' ').firstOrNull()?.trim().orEmpty()
            val isShape = ThemeResolver.parseColor(head) == null && ThemeResolver.parseColor(firstToken) == null
            var circle = false
            var extent = "farthest-corner"
            var sx: Float? = null
            var sy: Float? = null
            var sxPct = false
            var syPct = false
            var px = Length(50f, true)
            var py = Length(50f, true)
            if (isShape) {
                val shapePart = head.substringBefore(" at ").let { if (head.startsWith("at ")) "" else it }.trim()
                val posPart = if (head.startsWith("at ")) head.removePrefix("at ") else head.substringAfter(" at ", "")
                val sizes = ArrayList<Pair<Float, Boolean>>()
                for (tok in shapePart.split(WHITESPACE).filter { it.isNotEmpty() }) {
                    when (tok) {
                        "circle" -> circle = true
                        "ellipse" -> circle = false
                        "closest-side", "closest-corner", "farthest-side", "farthest-corner" -> extent = tok
                        else -> LEN.find(tok)?.let { m ->
                            sizes += (m.groupValues[1].toFloatOrNull() ?: return null) to (m.groupValues[2] == "%")
                        } ?: return null
                    }
                }
                if (sizes.isNotEmpty()) {
                    sx = sizes[0].first; sxPct = sizes[0].second
                    if (sizes.size > 1) { sy = sizes[1].first; syPct = sizes[1].second } else circle = true
                }
                if (posPart.isNotBlank()) position(posPart.trim())?.let { (x, y) -> px = x; py = y } ?: return null
            }
            val stops = stops(if (isShape) args.drop(1) else args) ?: return null
            return Radial(circle, extent, sx, sy, sxPct, syPct, px, py, stops)
        }

        /** `at` positions: one or two of `left|center|right|top|bottom|<length>`. */
        private fun position(s: String): Pair<Length, Length>? {
            val toks = s.split(WHITESPACE)
            fun kw(t: String): Length? = when (t) {
                "left", "top" -> Length(0f, true)
                "center" -> Length(50f, true)
                "right", "bottom" -> Length(100f, true)
                else -> LEN.matchEntire(t)?.let { m -> m.groupValues[1].toFloatOrNull()?.let { Length(it, m.groupValues[2] == "%") } }
            }
            // The edge-offset forms (`right 20px bottom 30px`, valid CSS) aren't placed yet: the gradient is rejected,
            // not misplaced.
            if (toks.size > 2) return null
            if (toks.size == 1) {
                val t = toks[0]
                return when (t) {
                    "top", "bottom" -> Length(50f, true) to kw(t)!!
                    else -> (kw(t) ?: return null) to Length(50f, true)
                }
            }
            var a = toks[0]
            var b = toks[1]
            if (a == "top" || a == "bottom" || b == "left" || b == "right") { val t = a; a = b; b = t }
            return (kw(a) ?: return null) to (kw(b) ?: return null)
        }

        private fun stops(args: List<String>): List<Stop>? {
            val out = ArrayList<Stop>()
            // Transition hints (`#000 0%, 20%, #fff`): where between two stops their mix sits, by index into [out].
            val hints = ArrayList<Pair<Int, Stop>>()
            for (a in args) {
                val toks = splitTopLevel(a.trim(), ' ').map { it.trim() }.filter { it.isNotEmpty() }
                if (toks.isEmpty()) continue
                val color = ThemeResolver.parseColor(toks[0]) ?: run {
                    // A bare position is a transition hint: it needs a stop on each side and no other token.
                    val m = LEN.matchEntire(toks[0]) ?: return null
                    if (toks.size != 1 || out.isEmpty()) return null
                    val v = m.groupValues[1].toFloatOrNull() ?: return null
                    hints += out.size to (if (m.groupValues[2] == "px") Stop(0, null, v) else Stop(0, v / 100f, null))
                    null
                } ?: continue
                // At most two positions, each a length or percentage: anything else (`#fff bogus`) makes the browser
                // drop the declaration, so an earlier valid background stays.
                if (toks.size > 3) return null
                val positions = toks.drop(1).map { t ->
                    LEN.matchEntire(t)?.let { m -> m.groupValues[1].toFloatOrNull()?.let { v -> if (m.groupValues[2] == "px") Stop(color, null, v) else Stop(color, v / 100f, null) } }
                        ?: return null
                }
                if (positions.isEmpty()) out += Stop(color, null, null) else out += positions
            }
            // A hint after the last stop (or two in a row) is invalid: the browser drops the declaration.
            if (hints.any { (at, _) -> at >= out.size } || hints.map { it.first }.toSet().size != hints.size) return null
            // Each hint becomes a stop of the two colors' mix at its position: the midpoint lands where the hint puts
            // it, the curve on each side approximated by a straight run.
            for ((at, h) in hints.asReversed()) out.add(at, h.copy(color = mix(out[at - 1].color, out[at].color)))
            // A gradient needs two stops; with fewer the browser drops the whole declaration.
            return if (out.size >= 2) out else null
        }

        /** The color halfway from [a] to [b], premultiplied as the browser interpolates (alpha weighs the channels). */
        private fun mix(a: Int, b: Int): Int {
            val aa = a ushr 24
            val ba = b ushr 24
            val alpha = (aa + ba) / 2
            if (alpha == 0) return ((((a shr 16 and 0xFF) + (b shr 16 and 0xFF)) / 2) shl 16) or
                ((((a shr 8 and 0xFF) + (b shr 8 and 0xFF)) / 2) shl 8) or (((a and 0xFF) + (b and 0xFF)) / 2)
            fun ch(shift: Int) = (((a shr shift and 0xFF) * aa + (b shr shift and 0xFF) * ba) / (aa + ba)).coerceIn(0, 255)
            return (alpha shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /**
         * Stop positions as fractions of [rayLength] (CSS px times [scale] for `px` stops): missing ones spread
         * evenly, each at least the one before. A fully transparent stop takes its neighbors' color at alpha 0, as
         * the browser's premultiplied interpolation shows it (no dark fringe).
         */
        internal fun resolveStops(stops: List<Stop>, rayLength: Float, scale: Float): Pair<IntArray, FloatArray>? {
            if (stops.isEmpty() || rayLength <= 0f) return null
            val pos = FloatArray(stops.size) { i -> stops[i].pos ?: stops[i].px?.let { it * scale / rayLength } ?: Float.NaN }
            if (pos[0].isNaN()) pos[0] = 0f
            if (pos[pos.lastIndex].isNaN()) pos[pos.lastIndex] = 1f
            // CSS: an explicit position under an earlier one is raised to it first; the missing ones then spread
            // evenly between their corrected neighbors.
            var floor = Float.NEGATIVE_INFINITY
            for (k in pos.indices) if (!pos[k].isNaN()) { if (pos[k] < floor) pos[k] = floor; floor = pos[k] }
            var i = 1
            while (i < pos.size) {
                if (pos[i].isNaN()) {
                    var j = i
                    while (pos[j].isNaN()) j++
                    val a = pos[i - 1]
                    val b = pos[j]
                    for (k in i until j) pos[k] = a + (b - a) * (k - i + 1) / (j - i + 1)
                    i = j
                }
                i++
            }
            for (k in 1 until pos.size) if (pos[k] < pos[k - 1]) pos[k] = pos[k - 1]
            val colors = ArrayList<Int>()
            val positions = ArrayList<Float>()
            for ((k, s) in stops.withIndex()) {
                val c = s.color
                if (c ushr 24 == 0) {
                    val prev = stops.getOrNull(k - 1)?.color?.takeIf { it ushr 24 != 0 }
                    val next = stops.getOrNull(k + 1)?.color?.takeIf { it ushr 24 != 0 }
                    val left = (prev ?: next ?: c) and 0xFFFFFF
                    val right = (next ?: prev ?: c) and 0xFFFFFF
                    colors += left; positions += pos[k]
                    if (right != left) { colors += right; positions += pos[k] }
                } else {
                    colors += c; positions += pos[k]
                }
            }
            return colors.toIntArray() to positions.toFloatArray()
        }

        /** Splits [s] at [sep] outside parentheses. */
        fun splitTopLevel(s: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            val cur = StringBuilder()
            for (ch in s) {
                when {
                    ch == '(' -> { depth++; cur.append(ch) }
                    ch == ')' -> { depth--; cur.append(ch) }
                    ch == sep && depth == 0 -> { out += cur.toString(); cur.setLength(0) }
                    else -> cur.append(ch)
                }
            }
            out += cur.toString()
            return if (sep == ' ') out.filter { it.isNotBlank() } else out
        }
    }
}
