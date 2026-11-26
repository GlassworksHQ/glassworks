/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.parity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How two renders of the same region compare, at one sample per CSS px. */
data class PixelDiff(
    /** Share of compared pixels that differ visibly (pixelmatch's YIQ distance over its 0.1 threshold, [ParityDiff.MAX_DELTA]). */
    val strict: Double,
    /** The same, but a pixel that matches any neighbour within one CSS px counts as a match: what the eye calls "the same". */
    val tolerant: Double,
    /** Mean luma difference, 0..1. */
    val meanLuma: Double,
    /** One pixel per CSS px: the reference in grey, differences in red (tolerant) and orange (strict only), masks in blue. */
    val image: Bitmap,
    /**
     * The worst small region: the color distance (pixelmatch's YIQ, square-rooted to 0..1) between HA's and Vitro's mean
     * color over a [ParityDiff.REGION] CSS px square, where Vitro's square may sit up to [ParityDiff.REGION_SHIFT] px
     * away. A share of the whole crop can't see a small element in the wrong color (a 24 px icon green where HA's is
     * white is 1% of a chips card); a mean over the square ignores antialiasing and sub-pixel text, but not a color.
     */
    val region: Double = 0.0,
    /** Where [region] is, in CSS px of the crop; null when nothing was compared. */
    val regionAt: Rect? = null,
)

/**
 * The perceptual part of the comparison. Both images are first box-filtered down to one sample per CSS px, which
 * irons out subpixel text rendering and antialiasing differences between Chromium and Android's Skia; then each
 * sample is compared with pixelmatch's YIQ colour distance (Kotlyar & Mazurkiewicz, the metric pixelmatch uses).
 */
object ParityDiff {
    /** pixelmatch's default threshold, 0.1. */
    const val THRESHOLD = 0.1

    /** [yiq] is a squared distance, so pixelmatch compares it with the threshold squared. */
    const val MAX_DELTA = THRESHOLD * THRESHOLD
    private const val MAX_YIQ = 35215.0

    /** The side of a region [PixelDiff.region] compares, CSS px: about one icon. */
    const val REGION = 16

    /** How far Vitro's region may sit from HA's and still count as the same, CSS px. */
    const val REGION_SHIFT = 2

    /** Regions are taken every this many CSS px. */
    private const val REGION_STEP = 4

    /** Box-filters [b] to [w] x [h] samples (premultiplied over [bg], so transparent edges compare as the page). */
    fun downsample(b: Bitmap, w: Int, h: Int, bg: Int = Color.BLACK): IntArray {
        val src = IntArray(b.width * b.height)
        b.getPixels(src, 0, b.width, 0, 0, b.width, b.height)
        val out = IntArray(w * h)
        val sx = b.width.toDouble() / w
        val sy = b.height.toDouble() / h
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = (x * sx).toInt(); val x1 = max(x0 + 1, min(b.width, ((x + 1) * sx).roundToInt()))
            val y0 = (y * sy).toInt(); val y1 = max(y0 + 1, min(b.height, ((y + 1) * sy).roundToInt()))
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val p = src[yy * b.width + xx]
                val a = Color.alpha(p) / 255.0
                r += (Color.red(p) * a + Color.red(bg) * (1 - a)).toLong()
                g += (Color.green(p) * a + Color.green(bg) * (1 - a)).toLong()
                bl += (Color.blue(p) * a + Color.blue(bg) * (1 - a)).toLong()
                n++
            }
            out[y * w + x] = Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
        return out
    }

    /** pixelmatch's colour distance between two opaque pixels, squared and normalized to 0..1. */
    fun yiq(a: Int, b: Int): Double {
        val r1 = Color.red(a).toDouble(); val g1 = Color.green(a).toDouble(); val b1 = Color.blue(a).toDouble()
        val r2 = Color.red(b).toDouble(); val g2 = Color.green(b).toDouble(); val b2 = Color.blue(b).toDouble()
        val y = (r1 - r2) * 0.29889531 + (g1 - g2) * 0.58662247 + (b1 - b2) * 0.11448223
        val i = (r1 - r2) * 0.59597799 - (g1 - g2) * 0.27417610 - (b1 - b2) * 0.32180189
        val q = (r1 - r2) * 0.21147017 - (g1 - g2) * 0.52261711 + (b1 - b2) * 0.31114694
        return (0.5053 * y * y + 0.299 * i * i + 0.1957 * q * q) / MAX_YIQ
    }

    private fun luma(p: Int) = (Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114) / 255.0

    /**
     * Compares [ref] and [vitro] (same px size) at [w] x [h] samples. [masked] (sample coordinates) are left out.
     */
    fun compare(ref: Bitmap, vitro: Bitmap, w: Int, h: Int, masked: List<Rect> = emptyList()): PixelDiff {
        val a = downsample(ref, w, h)
        val b = downsample(vitro, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        var strict = 0; var tolerant = 0; var n = 0
        var lumaSum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val l = luma(a[i])
            val grey = (40 + l * 90).toInt()
            if (masked.any { it.contains(x, y) }) {
                px[i] = Color.rgb(grey / 2, grey / 2, 160)
                continue
            }
            n++
            lumaSum += abs(l - luma(b[i]))
            val d = yiq(a[i], b[i])
            if (d <= MAX_DELTA) {
                px[i] = Color.rgb(grey, grey, grey)
                continue
            }
            strict++
            // A shift, not a difference: HA's pixel is somewhere near in Vitro's render, and Vitro's near in HA's.
            var nearA = false
            var nearB = false
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                // A masked neighbor isn't compared either: it can't excuse an unmasked pixel.
                if (xx < 0 || yy < 0 || xx >= w || yy >= h || masked.any { it.contains(xx, yy) }) continue
                val j = yy * w + xx
                if (!nearA && yiq(a[i], b[j]) <= MAX_DELTA) nearA = true
                if (!nearB && yiq(b[i], a[j]) <= MAX_DELTA) nearB = true
            }
            val near = nearA && nearB
            if (near) {
                px[i] = Color.rgb(230, 150, 40)
            } else {
                tolerant++
                px[i] = Color.rgb(235, 40, 40)
            }
        }
        val (region, at) = worstRegion(a, b, w, h, masked)
        at?.let { r ->
            // The worst region, outlined in magenta.
            for (x in r.left until r.right) for (y in intArrayOf(r.top, r.bottom - 1)) if (y in 0 until h && x in 0 until w) px[y * w + x] = Color.MAGENTA
            for (y in r.top until r.bottom) for (x in intArrayOf(r.left, r.right - 1)) if (y in 0 until h && x in 0 until w) px[y * w + x] = Color.MAGENTA
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        val total = max(1, n).toDouble()
        return PixelDiff(strict / total, tolerant / total, lumaSum / total, out, region, at)
    }

    /** Per-channel summed-area table of [p] ([w] x [h]), (w+1) x (h+1). */
    private fun sat(p: IntArray, w: Int, h: Int, channel: (Int) -> Int): LongArray {
        val t = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += channel(p[y * w + x])
                t[(y + 1) * (w + 1) + x + 1] = t[y * (w + 1) + x + 1] + row
            }
        }
        return t
    }

    /**
     * [PixelDiff.region]: the largest distance between HA's mean color over a [REGION] square and Vitro's over the
     * nearest-matching square within [REGION_SHIFT], with where it is. Squares touching a mask are left out.
     */
    fun worstRegion(a: IntArray, b: IntArray, w: Int, h: Int, masked: List<Rect> = emptyList()): Pair<Double, Rect?> {
        if (w < REGION || h < REGION) return 0.0 to null
        val sa = listOf(sat(a, w, h, Color::red), sat(a, w, h, Color::green), sat(a, w, h, Color::blue))
        val sb = listOf(sat(b, w, h, Color::red), sat(b, w, h, Color::green), sat(b, w, h, Color::blue))
        val area = (REGION * REGION).toLong()
        fun channel(s: LongArray, x: Int, y: Int): Int =
            ((s[(y + REGION) * (w + 1) + x + REGION] - s[y * (w + 1) + x + REGION] - s[(y + REGION) * (w + 1) + x] + s[y * (w + 1) + x]) / area).toInt()
        // No list per call: this runs some 700,000 times for one S1 render.
        fun mean(t: List<LongArray>, x: Int, y: Int): Int = Color.rgb(channel(t[0], x, y), channel(t[1], x, y), channel(t[2], x, y))
        var worst = 0.0
        var at: Rect? = null
        val xs = (0..w - REGION step REGION_STEP).toMutableList().apply { if (last() != w - REGION) add(w - REGION) }
        val ys = (0..h - REGION step REGION_STEP).toMutableList().apply { if (last() != h - REGION) add(h - REGION) }
        for (y in ys) for (x in xs) {
            val r = Rect(x, y, x + REGION, y + REGION)
            if (masked.any { Rect.intersects(it, r) }) continue
            val ma = mean(sa, x, y)
            var best = Double.MAX_VALUE
            for (dy in -REGION_SHIFT..REGION_SHIFT) for (dx in -REGION_SHIFT..REGION_SHIFT) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx > w - REGION || yy > h - REGION) continue
                // Vitro's square may not reach into a mask either: masked pixels never excuse an unmasked difference.
                if (masked.isNotEmpty() && masked.any { Rect.intersects(it, Rect(xx, yy, xx + REGION, yy + REGION)) }) continue
                best = min(best, yiq(ma, mean(sb, xx, yy)))
                if (best == 0.0) break
            }
            if (best == Double.MAX_VALUE) continue
            val d = Math.sqrt(best)
            if (d > worst) { worst = d; at = r }
        }
        return worst to at
    }

    /** HA | Vitro | diff, side by side under one caption line, at the references' scale (the diff is scaled up). */
    fun sideBySide(ref: Bitmap, vitro: Bitmap, diff: Bitmap, caption: String, density: Float): Bitmap {
        val gap = (8 * density).roundToInt()
        val head = (22 * density).roundToInt()
        val w = ref.width * 3 + gap * 4
        val h = ref.height + head + gap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(24, 24, 27))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(228, 228, 231); textSize = 12 * density; typeface = Typeface.DEFAULT_BOLD }
        val labels = listOf("Home Assistant", "Vitro", "Diff")
        listOf(ref, vitro, diff).forEachIndexed { i, b ->
            val x = gap + i * (ref.width + gap)
            c.drawText(labels[i] + if (i == 2) "  $caption" else "", x.toFloat(), head - 6 * density, p)
            c.drawBitmap(b, null, Rect(x, head, x + ref.width, head + ref.height), Paint().apply { isFilterBitmap = false })
        }
        return out
    }

    /** [b]'s region at px ([x], [y]) of [w] x [h], with the part outside [b] filled with [fill]. */
    fun crop(b: Bitmap, x: Int, y: Int, w: Int, h: Int, fill: Int = Color.MAGENTA): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(fill)
        val x0 = max(0, x); val y0 = max(0, y)
        val x1 = min(b.width, x + w); val y1 = min(b.height, y + h)
        if (x1 > x0 && y1 > y0) c.drawBitmap(b, Rect(x0, y0, x1, y1), Rect(x0 - x, y0 - y, x1 - x, y1 - y), null)
        return out
    }
}
