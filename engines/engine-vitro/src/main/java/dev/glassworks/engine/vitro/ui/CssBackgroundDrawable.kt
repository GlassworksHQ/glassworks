/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.glassworks.engine.vitro.theme.CssBackground

/**
 * Paints a [CssBackground]: the color, then each gradient layer from the last to the first, as the browser stacks
 * them. Shaders are built once per size, so drawing is a few rect fills. [scale] turns CSS px into pixels;
 * [cornerRadiusPx] clips to a rounded card.
 */
class CssBackgroundDrawable(
    private val bg: CssBackground,
    private val scale: Float,
    private val cornerRadiusPx: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var shaders: List<Shader> = emptyList()
    private val rect = RectF()
    private val clip = Path()

    /** [setAlpha]'s value, applied over each fill's own alpha when drawing (the fills set the paint's color). */
    private var drawableAlpha = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        rect.set(bounds)
        clip.reset()
        if (cornerRadiusPx > 0f) clip.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        if (w <= 0f || h <= 0f) { shaders = emptyList(); return }
        shaders = bg.layers.asReversed().mapNotNull { layer ->
            val g = bg.geometry(layer, w, h, scale) ?: return@mapNotNull null
            val shader: Shader = if (layer is CssBackground.Linear) {
                LinearGradient(g.x0, g.y0, g.x1, g.y1, g.colors, g.positions, Shader.TileMode.CLAMP)
            } else {
                // An ellipse is a circle of radius rx squeezed to ry.
                RadialGradient(0f, 0f, g.rx, g.colors, g.positions, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(1f, g.ry / g.rx); postTranslate(g.x0, g.y0) })
                }
            }
            if (bounds.left != 0 || bounds.top != 0) {
                val m = Matrix()
                shader.getLocalMatrix(m)
                m.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                shader.setLocalMatrix(m)
            }
            shader
        }
    }

    override fun draw(canvas: Canvas) {
        val save = if (cornerRadiusPx > 0f) canvas.save().also { canvas.clipPath(clip) } else -1
        bg.color?.let { c ->
            paint.shader = null
            paint.color = c
            paint.alpha = (c ushr 24) * drawableAlpha / 255
            canvas.drawRect(rect, paint)
        }
        paint.color = 0xFF000000.toInt()
        paint.alpha = drawableAlpha
        for (s in shaders) {
            paint.shader = s
            canvas.drawRect(rect, paint)
        }
        paint.shader = null
        if (save >= 0) canvas.restoreToCount(save)
    }

    /** A rounded outline, so a card clipping to it keeps its corners. */
    override fun getOutline(outline: android.graphics.Outline) {
        outline.setRoundRect(bounds, cornerRadiusPx)
    }

    override fun setAlpha(alpha: Int) {
        val a = alpha.coerceIn(0, 255)
        if (a == drawableAlpha) return
        drawableAlpha = a
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
