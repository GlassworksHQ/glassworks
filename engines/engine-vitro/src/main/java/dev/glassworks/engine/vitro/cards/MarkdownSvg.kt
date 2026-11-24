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
import kotlin.math.min

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
        for (shape in shapes) drawShape(canvas, shape, context)
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, shape: Shape, context: Context) {
        val a = shape.attrs
        canvas.save()
        shape.matrix?.let { canvas.concat(it) }
        val opacity = (a["opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f)
        val fill = a["fill"] ?: "black"
        val stroke = a["stroke"] ?: "none"
        if (shape.text != null) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = color(fill, opacity * (a["fill-opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f)) ?: 0
            val size = a["font-size"]?.let { MdCascade.length(it, 16f, 0f) ?: it.toFloatOrNull() } ?: 16f
            paint.textSize = size
            val weight = when (val wt = a["font-weight"]?.lowercase(Locale.ROOT)) {
                null, "normal" -> 400
                "bold" -> 700
                else -> wt.toIntOrNull() ?: 400
            }
            paint.typeface = MdFonts.typeface(context, weight, false, false, size)
            paint.textAlign = when (a["text-anchor"]) {
                "middle" -> Paint.Align.CENTER
                "end" -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            canvas.drawText(shape.text, num(a["x"]), num(a["y"]), paint)
            canvas.restore()
            return
        }
        val path = shape.path ?: run { canvas.restore(); return }
        color(fill, opacity * (a["fill-opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f))?.let { c ->
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = c
            canvas.drawPath(path, paint)
        }
        color(stroke, opacity * (a["stroke-opacity"]?.let { MarkdownInline.parseOpacity(it) } ?: 1f))?.let { c ->
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = c
            paint.strokeWidth = a["stroke-width"]?.let { num(it) } ?: 1f
            paint.strokeCap = when (a["stroke-linecap"]) {
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
            paint.strokeJoin = when (a["stroke-linejoin"]) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
            val dashes = a["stroke-dasharray"]?.split(Regex("""[\s,]+"""))?.mapNotNull { it.toFloatOrNull() }
                ?.filter { it >= 0f }
            if (!dashes.isNullOrEmpty() && dashes.any { it > 0f }) {
                val scale = a["pathLength"]?.toFloatOrNull()?.takeIf { it > 0f }?.let { PathMeasure(path, false).length / it } ?: 1f
                val list = (if (dashes.size % 2 == 1) dashes + dashes else dashes).map { it * scale }
                if (list[0] <= 0f) {
                    // A zero-length dash still draws its round or square cap.
                    if (paint.strokeCap != Paint.Cap.BUTT) {
                        val pm = PathMeasure(path, false)
                        val pos = FloatArray(2)
                        pm.getPosTan(0f, pos, null)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(pos[0], pos[1], paint.strokeWidth / 2f, paint)
                    }
                } else {
                    paint.pathEffect = DashPathEffect(list.map { it.coerceAtLeast(0.001f) }.toFloatArray(), 0f)
                    canvas.drawPath(path, paint)
                }
            } else {
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
    }

    private fun color(v: String, opacity: Float): Int? {
        val t = v.trim()
        if (t.isEmpty() || t == "none" || t == "transparent") return null
        val c = MarkdownInline.parseCssColor(if (t == "currentColor") "black" else t) ?: return null
        return MarkdownInline.scaleAlpha(c, opacity)
    }

    companion object {
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
            var pos = 0
            var openText: Pair<Map<String, String>, Matrix?>? = null
            val textBuf = StringBuilder()
            while (true) {
                val m = ELEMENT.find(xml, pos) ?: break
                if (openText != null) textBuf.append(xml, pos, m.range.first)
                pos = m.range.last + 1
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2].substringAfter(':').lowercase(Locale.ROOT)
                val selfClose = m.groupValues[4] == "/"
                if (closing) {
                    when (name) {
                        "g", "svg" -> if (inherited.isNotEmpty()) inherited.removeAt(inherited.size - 1)
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
                            own["viewBox"]?.split(Regex("""[\s,]+"""))?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }?.let {
                                viewBox = RectF(it[0], it[1], it[0] + it[2], it[1] + it[3])
                            }
                            own["preserveAspectRatio"]?.let { preserve = it.trim() }
                            iw = own["width"]?.let { MdCascade.length(it, 16f, 0f) }
                            ih = own["height"]?.let { MdCascade.length(it, 16f, 0f) }
                        }
                        if (!selfClose) inherited += own to matrix
                    }
                    "g" -> if (!selfClose) inherited += own to matrix
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
                        val pts = own["points"]?.split(Regex("""[\s,]+"""))?.mapNotNull { it.toFloatOrNull() }.orEmpty()
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

        private val INHERITABLE = setOf(
            "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "fill-opacity",
            "stroke-opacity", "font-size", "font-weight", "font-family", "text-anchor",
        )

        private fun num(v: String?): Float = v?.trim()?.removeSuffix("px")?.toFloatOrNull() ?: 0f

        private val TRANSFORM = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")

        private fun transform(v: String): Matrix? {
            val m = Matrix()
            var any = false
            for (t in TRANSFORM.findAll(v)) {
                val a = t.groupValues[2].split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
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

        /** SVG path data to an Android [Path]. Arcs (A) are drawn as straight lines to their end point. */
        fun pathData(d: String): Path {
            val p = Path()
            val tokens = PATH_TOKEN.findAll(d).map { it.value }.toList()
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
                        repeat(5) { n() }
                        x = ox + n(); y = oy + n(); p.lineTo(x, y)
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
    }
}
