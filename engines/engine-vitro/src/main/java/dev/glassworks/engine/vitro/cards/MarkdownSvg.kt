/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small SVG subset, for the `data:image/svg+xml,…` images dashboards build with Jinja and show through a
 * Markdown `<img>`: `svg` (viewBox, preserveAspectRatio), `g`, `path` (M L H V Q T C S Z, absolute and relative),
 * `circle`, `ellipse`, `rect` (rx), `line`, `polyline`, `polygon` and `text`; fill, stroke, stroke-width,
 * stroke-linecap / linejoin, stroke-dasharray with pathLength, opacity, and translate / rotate / scale / matrix
 * transforms. Parsed once, then drawn as vectors at any size.
 */
class MdSvg private constructor(
    val viewBox: RectF,
    private val preserve: String,
    private val shapes: List<Shape>,
    /** The SVG's own `width` / `height`, when it gives them. */
    val intrinsicWidth: Float?,
    val intrinsicHeight: Float?,
) {
    private class Shape(
        val path: Path?,
        val attrs: Map<String, String>,
        val matrix: Matrix?,
        val text: String? = null,
        /**
         * A group's opacity, as a marker rather than a shape: the shapes up to the matching [endsLayer] marker are
         * drawn into one layer, composited once at this alpha (children's own alphas don't stack where they overlap).
         */
        val layerAlpha: Float? = null,
        val endsLayer: Boolean = false,
    ) {
        /** Its paint, resolved from [attrs] on the first draw and reused by every draw after it. */
        var style: ShapeStyle? = null
    }

    /** A shape's attributes as paint settings: colors, stroke, dashes and text, all parsed once. */
    private class ShapeStyle(
        val fill: Int?,
        val stroke: Int?,
        val strokeWidth: Float,
        val cap: Paint.Cap,
        val join: Paint.Join,
        /** The stroke's dashes (null when solid). */
        val dash: DashPathEffect?,
        /** A dash pattern that starts with a zero-length dash: only its cap at the path's start is drawn. */
        val capDot: FloatArray?,
        /** That zero-length dash with a butt cap: nothing is stroked. */
        val strokeHidden: Boolean,
        val strokeFirst: Boolean,
        val textSize: Float,
        val textWeight: Int,
        val textAlign: Paint.Align,
        val x: Float,
        val y: Float,
        /** The shape's `opacity` when it has both a fill and a stroke: applied once to the two drawn together. */
        val groupOpacity: Float = 1f,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The width / height ratio, from the viewBox. */
    val ratio: Float get() = if (viewBox.height() > 0f) viewBox.width() / viewBox.height() else 2f

    /** Draws the image into [w] x [h] px at the canvas origin. */
    fun draw(canvas: Canvas, w: Float, h: Float, context: Context) {
        if (viewBox.width() <= 0f || viewBox.height() <= 0f) return
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        val sx = w / viewBox.width()
        val sy = h / viewBox.height()
        if (preserve.startsWith("none")) {
            canvas.scale(sx, sy)
        } else {
            val s = if (preserve.contains("slice")) maxOf(sx, sy) else min(sx, sy)
            // The alignment `preserveAspectRatio` asks for (xMin/xMid/xMax, YMin/YMid/YMax); mid by default.
            val fx = when { "xMin" in preserve -> 0f; "xMax" in preserve -> 1f; else -> 0.5f }
            val fy = when { "YMin" in preserve -> 0f; "YMax" in preserve -> 1f; else -> 0.5f }
            canvas.translate((w - viewBox.width() * s) * fx, (h - viewBox.height() * s) * fy)
            canvas.scale(s, s)
        }
        canvas.translate(-viewBox.left, -viewBox.top)
        val base = canvas.saveCount
        val layers = ArrayList<Int>()
        for (shape in shapes) {
            when {
                shape.layerAlpha != null -> layers += canvas.saveLayerAlpha(null, (shape.layerAlpha * 255f).roundToInt().coerceIn(0, 255))
                shape.endsLayer -> if (layers.isNotEmpty()) canvas.restoreToCount(layers.removeAt(layers.lastIndex))
                else -> drawShape(canvas, shape, context)
            }
        }
        canvas.restoreToCount(base)
        canvas.restore()
    }

    private fun styleOf(shape: Shape): ShapeStyle {
        shape.style?.let { return it }
        val a = shape.attrs
        val opacity = (a["opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f)
        // `opacity` is the element's, over its fill and stroke drawn together: with both, it's one layer (no darker
        // band where the stroke overlaps the fill); with one, it folds into that paint.
        val fillOpacity = a["fill-opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f
        val strokeOpacity = a["stroke-opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f
        val both = color(a["fill"] ?: "black", 1f) != null && color(a["stroke"] ?: "none", 1f) != null && shape.text == null
        val each = if (both) 1f else opacity
        val fill = color(a["fill"] ?: "black", each * fillOpacity)
        val stroke = color(a["stroke"] ?: "none", each * strokeOpacity)
        val strokeWidth = a["stroke-width"]?.let { num(it) } ?: 1f
        val cap = when (a["stroke-linecap"]) {
            "round" -> Paint.Cap.ROUND
            "square" -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        var dash: DashPathEffect? = null
        var capDot: FloatArray? = null
        var strokeHidden = false
        val path = shape.path
        val dashes = a["stroke-dasharray"]?.split(SEPARATORS)?.mapNotNull { it.toFloatOrNull() }?.filter { it >= 0f }
        if (path != null && stroke != null && !dashes.isNullOrEmpty() && dashes.any { it > 0f }) {
            val scale = a["pathLength"]?.toFloatOrNull()?.takeIf { it > 0f }?.let { PathMeasure(path, false).length / it } ?: 1f
            val list = (if (dashes.size % 2 == 1) dashes + dashes else dashes).map { it * scale }
            val anyOn = list.indices.any { it % 2 == 0 && list[it] > 0f }
            if (list[0] <= 0f && !anyOn) {
                // Only zero-length dashes: a round or square cap still draws its dot; a butt cap draws nothing.
                if (cap != Paint.Cap.BUTT) capDot = FloatArray(2).also { PathMeasure(path, false).getPosTan(0f, it, null) }
                else strokeHidden = true
            } else if (list[0] <= 0f && cap != Paint.Cap.BUTT) {
                // A zero-length first dash with a cap: its dot, and the pattern's later dashes.
                capDot = FloatArray(2).also { PathMeasure(path, false).getPosTan(0f, it, null) }
                dash = DashPathEffect(list.map { it.coerceAtLeast(0.001f) }.toFloatArray(), 0f)
            } else {
                // (A zero-length butt dash comes out as a sliver too thin to see; the later ones paint.)
                dash = DashPathEffect(list.map { it.coerceAtLeast(0.001f) }.toFloatArray(), 0f)
            }
        }
        val size = a["font-size"]?.let { MdCascade.length(it, 16f, 0f) ?: it.toFloatOrNull() } ?: 16f
        val weight = when (val wt = a["font-weight"]?.lowercase(Locale.ROOT)) {
            null, "normal" -> 400
            "bold" -> 700
            else -> wt.toIntOrNull() ?: 400
        }
        return ShapeStyle(
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            cap = cap,
            join = when (a["stroke-linejoin"]) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            },
            dash = dash,
            capDot = capDot,
            strokeHidden = strokeHidden,
            strokeFirst = a["paint-order"]?.trim()?.startsWith("stroke") == true,
            textSize = size,
            textWeight = weight,
            textAlign = when (a["text-anchor"]) {
                "middle" -> Paint.Align.CENTER
                "end" -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            },
            x = num(a["x"]),
            y = num(a["y"]),
            groupOpacity = if (both) opacity else 1f,
        ).also { shape.style = it }
    }

    private fun drawShape(canvas: Canvas, shape: Shape, context: Context) {
        val st = styleOf(shape)
        // Restored to this count at the end: the transform and an opacity layer both go, so no sibling inherits either.
        val saved = canvas.save()
        shape.matrix?.let { canvas.concat(it) }
        if (shape.text != null) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = st.fill ?: 0
            paint.textSize = st.textSize
            paint.typeface = MdFonts.typeface(context, st.textWeight, false, null, st.textSize)
            paint.textAlign = st.textAlign
            canvas.drawText(shape.text, st.x, st.y, paint)
            canvas.restore()
            return
        }
        val path = shape.path
        if (path == null) {
            canvas.restore()
            return
        }
        if (st.groupOpacity < 1f) canvas.saveLayerAlpha(null, (st.groupOpacity * 255f).roundToInt().coerceIn(0, 255))
        // `paint-order: stroke` paints the stroke under the fill.
        if (st.strokeFirst) {
            drawStroke(canvas, path, st)
            drawFill(canvas, path, st)
        } else {
            drawFill(canvas, path, st)
            drawStroke(canvas, path, st)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawFill(canvas: Canvas, path: Path, st: ShapeStyle) {
        val c = st.fill ?: return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = c
        canvas.drawPath(path, paint)
    }

    private fun drawStroke(canvas: Canvas, path: Path, st: ShapeStyle) {
        val c = st.stroke ?: return
        if (st.strokeHidden) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = c
        paint.strokeWidth = st.strokeWidth
        paint.strokeCap = st.cap
        paint.strokeJoin = st.join
        val dot = st.capDot
        when {
            dot != null -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(dot[0], dot[1], st.strokeWidth / 2f, paint)
                // The pattern's later dashes, when it has any.
                st.dash?.let { d ->
                    paint.style = Paint.Style.STROKE
                    paint.pathEffect = d
                    canvas.drawPath(path, paint)
                }
            }
            st.dash != null -> {
                paint.pathEffect = st.dash
                canvas.drawPath(path, paint)
            }
            else -> canvas.drawPath(path, paint)
        }
    }

    private fun color(v: String, opacity: Float): Int? {
        val t = v.trim()
        if (t.isEmpty() || t == "none" || t == "transparent") return null
        val c = MarkdownInline.parseCssColor(if (t == "currentColor") "black" else t) ?: return null
        return MarkdownInline.scaleAlpha(c, opacity)
    }

    companion object {
        private val SEPARATORS = Regex("""[\s,]+""")
        private val ELEMENT = Regex("""<(/?)([a-zA-Z][\w:-]*)((?:\s+[^\s=/>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(/?)>""")
        private val ATTR = Regex("""([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        /** Reads a `data:image/svg+xml` URI (percent-encoded, plain or base64), or null when it isn't one. */
        fun fromDataUri(uri: String): MdSvg? {
            val head = uri.substringBefore(',', "")
            if (!head.startsWith("data:image/svg+xml", ignoreCase = true)) return null
            val body = uri.substringAfter(',', "")
            val xml = if (head.endsWith(";base64", ignoreCase = true)) {
                runCatching { String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)) }.getOrNull() ?: return null
            } else percentDecode(body)
            return runCatching { parse(xml) }.getOrNull()
        }

        /** Percent-decoding without `+` as space, as the browser reads a data URI. */
        fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val bytes = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (h != null) { bytes.write(h); i += 3; continue }
                }
                // By code point: a surrogate pair (an emoji) encodes as one character, not two halves.
                val cp = s.codePointAt(i)
                val enc = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                bytes.write(enc, 0, enc.size)
                i += Character.charCount(cp)
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        fun parse(xml: String): MdSvg? {
            val shapes = ArrayList<Shape>()
            var viewBox: RectF? = null
            var preserve = "xMidYMid meet"
            var iw: Float? = null
            var ih: Float? = null
            // Inherited presentation attributes and transforms of open <g>/<svg> elements.
            val inherited = ArrayList<Pair<Map<String, String>, Matrix?>>()
            // Per open <g>/<svg>, alongside [inherited]: whether it opened an opacity layer that its close ends.
            val layered = ArrayList<Boolean>()
            var pos = 0
            var openText: Pair<Map<String, String>, Matrix?>? = null
            val textBuf = StringBuilder()
            var unrendered = 0
            while (true) {
                val m = ELEMENT.find(xml, pos) ?: break
                if (openText != null) textBuf.append(xml, pos, m.range.first)
                pos = m.range.last + 1
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2].substringAfter(':').lowercase(Locale.ROOT)
                val selfClose = m.groupValues[4] == "/"
                // Inside <defs> (and the other never-drawn containers) nothing is drawn directly: only their own
                // nesting is followed, to know where they end.
                if (unrendered > 0) {
                    if (name in UNRENDERED) { if (closing) unrendered-- else if (!selfClose) unrendered++ }
                    continue
                }
                if (!closing && !selfClose && name in UNRENDERED) { unrendered++; continue }
                if (closing) {
                    when (name) {
                        "g", "svg" -> {
                            if (inherited.isNotEmpty()) inherited.removeAt(inherited.size - 1)
                            if (layered.isNotEmpty() && layered.removeAt(layered.size - 1)) shapes += Shape(null, emptyMap(), null, endsLayer = true)
                        }
                        "text", "tspan" -> openText?.let { (a, mx) ->
                            if (name == "text") {
                                shapes += Shape(null, a, mx, MdHtml.decodeEntities(textBuf.toString().trim()))
                                openText = null
                                textBuf.setLength(0)
                            }
                        }
                    }
                    continue
                }
                val own = LinkedHashMap<String, String>()
                ATTR.findAll(m.groupValues[3]).forEach { own[it.groupValues[1]] = it.groupValues[2].ifEmpty { it.groupValues[3] } }
                // `style="fill:…;stroke:…"` counts like attributes.
                own["style"]?.split(';')?.forEach { d ->
                    val k = d.substringBefore(':').trim()
                    val v = d.substringAfter(':', "").trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) own[k] = v
                }
                val attrs = LinkedHashMap<String, String>()
                inherited.forEach { (a, _) -> a.forEach { (k, v) -> if (k in INHERITABLE) attrs[k] = v } }
                attrs.putAll(own)
                val parentMatrix = inherited.lastOrNull()?.second
                val ownMatrix = own["transform"]?.let(::transform)
                val matrix = when {
                    parentMatrix == null -> ownMatrix
                    ownMatrix == null -> parentMatrix
                    else -> Matrix(parentMatrix).apply { preConcat(ownMatrix) }
                }
                when (name) {
                    "svg" -> {
                        if (viewBox == null) {
                            own["viewBox"]?.split(SEPARATORS)?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }?.let {
                                viewBox = RectF(it[0], it[1], it[0] + it[2], it[1] + it[3])
                            }
                            own["preserveAspectRatio"]?.let { preserve = it.trim() }
                            // SVG's `width="64"` is in px: a bare number counts, as it does in the browser.
                            iw = own["width"]?.let { it.trim().toFloatOrNull() ?: MdCascade.length(it, 16f, 0f) }
                            ih = own["height"]?.let { it.trim().toFloatOrNull() ?: MdCascade.length(it, 16f, 0f) }
                        }
                        if (!selfClose) {
                            inherited += own to matrix
                            // The root's (or a nested svg's) opacity applies to everything in it at once, as a group's.
                            val alpha = own["opacity"]?.let { MarkdownInline.parseOpacity(it) }?.coerceIn(0f, 1f)
                            val layer = alpha != null && alpha < 1f
                            if (layer) shapes += Shape(null, emptyMap(), null, layerAlpha = alpha)
                            layered += layer
                        }
                    }
                    "g" -> if (!selfClose) {
                        inherited += own to matrix
                        // A group's opacity applies to the group drawn as a whole.
                        val alpha = own["opacity"]?.let { MarkdownInline.parseOpacity(it) }?.coerceIn(0f, 1f)
                        val layer = alpha != null && alpha < 1f
                        if (layer) shapes += Shape(null, emptyMap(), null, layerAlpha = alpha)
                        layered += layer
                    }
                    "text" -> if (!selfClose) { openText = attrs to matrix; textBuf.setLength(0) }
                    "path" -> own["d"]?.let { shapes += Shape(pathData(it), attrs, matrix) }
                    "circle" -> {
                        val r = num(own["r"])
                        if (r > 0f) {
                            val cx = num(own["cx"])
                            val cy = num(own["cy"])
                            // Starts at 3 o'clock and runs clockwise, like SVG (it matters for dashes).
                            shapes += Shape(Path().apply { addArc(RectF(cx - r, cy - r, cx + r, cy + r), 0f, 359.999f) }, attrs, matrix)
                        }
                    }
                    "ellipse" -> {
                        val rx = num(own["rx"])
                        val ry = num(own["ry"])
                        val cx = num(own["cx"])
                        val cy = num(own["cy"])
                        if (rx > 0f && ry > 0f) shapes += Shape(Path().apply { addArc(RectF(cx - rx, cy - ry, cx + rx, cy + ry), 0f, 359.999f) }, attrs, matrix)
                    }
                    "rect" -> {
                        val x = num(own["x"])
                        val y = num(own["y"])
                        val w = num(own["width"])
                        val h = num(own["height"])
                        if (w > 0f && h > 0f) {
                            var rx = own["rx"]?.let { num(it) }
                            var ry = own["ry"]?.let { num(it) }
                            if (rx == null) rx = ry ?: 0f
                            if (ry == null) ry = rx
                            val p = Path()
                            val rect = RectF(x, y, x + w, y + h)
                            if (rx > 0f || ry > 0f) p.addRoundRect(rect, min(rx, w / 2f), min(ry, h / 2f), Path.Direction.CW)
                            else p.addRect(rect, Path.Direction.CW)
                            shapes += Shape(p, attrs, matrix)
                        }
                    }
                    "line" -> shapes += Shape(Path().apply {
                        moveTo(num(own["x1"]), num(own["y1"]))
                        lineTo(num(own["x2"]), num(own["y2"]))
                    }, attrs + ("fill" to "none"), matrix)
                    "polyline", "polygon" -> {
                        val pts = own["points"]?.split(SEPARATORS)?.mapNotNull { it.toFloatOrNull() }.orEmpty()
                        if (pts.size >= 4) {
                            val p = Path()
                            p.moveTo(pts[0], pts[1])
                            var k = 2
                            while (k + 1 < pts.size) { p.lineTo(pts[k], pts[k + 1]); k += 2 }
                            if (name == "polygon") p.close()
                            shapes += Shape(p, attrs, matrix)
                        }
                    }
                }
            }
            val vb = viewBox ?: RectF(0f, 0f, iw ?: 300f, ih ?: 150f)
            return MdSvg(vb, preserve, shapes, iw, ih)
        }

        /** Containers whose content is never drawn where it stands (definitions, references, effects). */
        private val UNRENDERED = setOf("defs", "symbol", "clippath", "mask", "pattern", "marker", "lineargradient", "radialgradient", "filter")

        private val INHERITABLE = setOf(
            "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "fill-opacity",
            "stroke-opacity", "font-size", "font-weight", "font-family", "text-anchor", "paint-order",
        )

        private fun num(v: String?): Float = v?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f

        private val TRANSFORM = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")

        private fun transform(v: String): Matrix? {
            val m = Matrix()
            var any = false
            for (t in TRANSFORM.findAll(v)) {
                val a = t.groupValues[2].split(SEPARATORS).mapNotNull { it.toFloatOrNull() }
                val step = Matrix()
                when (t.groupValues[1]) {
                    "translate" -> step.setTranslate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                    "scale" -> step.setScale(a.getOrElse(0) { 1f }, a.getOrElse(1) { a.getOrElse(0) { 1f } })
                    "rotate" -> if (a.size >= 3) step.setRotate(a[0], a[1], a[2]) else step.setRotate(a.getOrElse(0) { 0f })
                    "skewX" -> step.setSkew(Math.tan(Math.toRadians(a.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                    "skewY" -> step.setSkew(0f, Math.tan(Math.toRadians(a.getOrElse(0) { 0f }.toDouble())).toFloat())
                    "matrix" -> if (a.size == 6) step.setValues(floatArrayOf(a[0], a[2], a[4], a[1], a[3], a[5], 0f, 0f, 1f))
                }
                m.preConcat(step)
                any = true
            }
            return if (any) m else null
        }

        private val PATH_TOKEN = Regex("""[MmLlHhVvQqTtCcSsZzAa]|[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")

        /** SVG path data to an Android [Path], elliptical arcs (A) included. */
        fun pathData(d: String): Path {
            val p = Path()
            val tokens = PATH_TOKEN.findAll(d).map { it.value }.toMutableList()
            var i = 0
            var cmd = 'M'
            var x = 0f
            var y = 0f
            var sx = 0f
            var sy = 0f
            var lcx = 0f
            var lcy = 0f
            var lastQ = false
            var lastC = false
            fun hasNum() = i < tokens.size && tokens[i][0].let { it.isDigit() || it == '-' || it == '+' || it == '.' }
            fun n(): Float = tokens[i++].toFloat()
            while (i < tokens.size) {
                if (!hasNum()) { cmd = tokens[i++][0] }
                val rel = cmd.isLowerCase()
                val ox = if (rel) x else 0f
                val oy = if (rel) y else 0f
                var q = false
                var c = false
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        if (!hasNum()) break
                        x = ox + n(); y = oy + n(); p.moveTo(x, y); sx = x; sy = y
                        cmd = if (rel) 'l' else 'L'
                    }
                    'L' -> { if (!hasNum()) break; x = ox + n(); y = oy + n(); p.lineTo(x, y) }
                    'H' -> { if (!hasNum()) break; x = (if (rel) x else 0f) + n(); p.lineTo(x, y) }
                    'V' -> { if (!hasNum()) break; y = (if (rel) y else 0f) + n(); p.lineTo(x, y) }
                    'Q' -> {
                        if (!hasNum()) break
                        lcx = ox + n(); lcy = oy + n(); x = ox + n(); y = oy + n()
                        p.quadTo(lcx, lcy, x, y); q = true
                    }
                    'T' -> {
                        if (!hasNum()) break
                        lcx = if (lastQ) 2 * x - lcx else x; lcy = if (lastQ) 2 * y - lcy else y
                        x = ox + n(); y = oy + n(); p.quadTo(lcx, lcy, x, y); q = true
                    }
                    'C' -> {
                        if (!hasNum()) break
                        val x1 = ox + n(); val y1 = oy + n()
                        lcx = ox + n(); lcy = oy + n(); x = ox + n(); y = oy + n()
                        p.cubicTo(x1, y1, lcx, lcy, x, y); c = true
                    }
                    'S' -> {
                        if (!hasNum()) break
                        val x1 = if (lastC) 2 * x - lcx else x; val y1 = if (lastC) 2 * y - lcy else y
                        lcx = ox + n(); lcy = oy + n(); x = ox + n(); y = oy + n()
                        p.cubicTo(x1, y1, lcx, lcy, x, y); c = true
                    }
                    'A' -> {
                        if (!hasNum()) break
                        val rx = n(); val ry = n(); val rot = n()
                        // The flags are single digits that may run together ("a5 5 0 011 1").
                        val large = flag(tokens, i).also { i = it.second }.first
                        val sweep = flag(tokens, i).also { i = it.second }.first
                        val ex = ox + n(); val ey = oy + n()
                        arcTo(p, x, y, rx, ry, rot, large, sweep, ex, ey)
                        x = ex; y = ey
                    }
                    // Z takes no operands: a number after it is an error, and the path ends there (it looped forever).
                    'Z' -> { p.close(); x = sx; y = sy; if (hasNum()) break; if (i < tokens.size) cmd = tokens[i++][0] else break }
                    else -> break
                }
                lastQ = q
                lastC = c
            }
            return p
        }

        /**
         * Reads one arc flag at [i]: a flag is one `0` or `1`, so a token like `011.5` is the flags 0 and 1 and then
         * the coordinate 1.5.
         */
        private fun flag(tokens: MutableList<String>, i: Int): Pair<Boolean, Int> {
            val t = tokens[i]
            if (t.length > 1 && (t[0] == '0' || t[0] == '1') && (t[1].isDigit() || t[1] == '.')) {
                tokens[i] = t.substring(1) // "011" is the flag 0, then "11"
                return (t[0] == '1') to i
            }
            return (t.toFloat() != 0f) to i + 1
        }

        /**
         * SVG's endpoint arc (implementation notes F.6): radii scaled up when too small, the center found from the
         * flags, then the sweep drawn as cubic Béziers of at most 90 degrees each.
         */
        internal fun arcTo(p: Path, x0: Float, y0: Float, rxIn: Float, ryIn: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
            if (x0 == x && y0 == y) return
            var rx = abs(rxIn).toDouble()
            var ry = abs(ryIn).toDouble()
            if (rx == 0.0 || ry == 0.0) { p.lineTo(x, y); return }
            val phi = Math.toRadians(rotDeg.toDouble())
            val cosP = cos(phi); val sinP = sin(phi)
            val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
            val x1 = cosP * dx + sinP * dy
            val y1 = -sinP * dx + cosP * dy
            val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
            if (lambda > 1) { val sq = sqrt(lambda); rx *= sq; ry *= sq }
            val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
            val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
            var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
            if (large == sweep) coef = -coef
            val cx1 = coef * rx * y1 / ry
            val cy1 = -coef * ry * x1 / rx
            val cx = cosP * cx1 - sinP * cy1 + (x0 + x) / 2.0
            val cy = sinP * cx1 + cosP * cy1 + (y0 + y) / 2.0
            fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
                val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
                return a
            }
            val theta1 = angle(1.0, 0.0, (x1 - cx1) / rx, (y1 - cy1) / ry)
            var dTheta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
            if (!sweep && dTheta > 0) dTheta -= 2 * PI else if (sweep && dTheta < 0) dTheta += 2 * PI
            val segments = ceil(abs(dTheta) / (PI / 2)).toInt().coerceAtLeast(1)
            val delta = dTheta / segments
            val t = 4.0 / 3.0 * tan(delta / 4)
            var a1 = theta1
            for (k in 0 until segments) {
                val a2 = a1 + delta
                val c1 = cos(a1); val s1 = sin(a1); val c2 = cos(a2); val s2 = sin(a2)
                // Unit-circle control points, then scaled, rotated and moved to the center.
                fun px(ux: Double, uy: Double) = (cx + rx * ux * cosP - ry * uy * sinP).toFloat()
                fun py(ux: Double, uy: Double) = (cy + rx * ux * sinP + ry * uy * cosP).toFloat()
                p.cubicTo(
                    px(c1 - t * s1, s1 + t * c1), py(c1 - t * s1, s1 + t * c1),
                    px(c2 + t * s2, s2 - t * c2), py(c2 + t * s2, s2 - t * c2),
                    px(c2, s2), py(c2, s2),
                )
                a1 = a2
            }
        }
    }
}
