package dev.glassworks.assist

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.glassworks.setup.BackdropMath
import dev.glassworks.theme.Themes
import kotlin.math.roundToInt

/**
 * The voice overlay's scrim in a theme (#691): the theme's ground, lit by its light a at the top left and light b at
 * the bottom right, where the onboarding backdrop ([dev.glassworks.setup.Backdrop]) has sky and violet. The same
 * centres, radii and falloff, as dithered gradients (the backdrop's own fallback); a light ground gets stronger glows
 * to show on it. Glassworks dark keeps the onboarding backdrop itself.
 */
class ThemedBackdrop(private val palette: Themes.Palette) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var alpha = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat(); val h = b.height().toFloat()
        paint.shader = null
        paint.color = palette.ground
        paint.alpha = alpha
        canvas.drawRect(b, paint)
        paint.color = Color.BLACK
        paint.alpha = alpha
        val stops = BackdropMath.FALLBACK_STOPS
        val strength = if (palette.darkGround) 1f else 4.5f
        for ((g, colour) in BackdropMath.GLOWS.zip(listOf(palette.a, palette.b))) {
            val peak = (g.alpha * strength).coerceAtMost(0.6f)
            val colors = IntArray(stops.size) { i ->
                Color.argb((peak * BackdropMath.falloff(stops[i]) * 255f).roundToInt(), Color.red(colour), Color.green(colour), Color.blue(colour))
            }
            paint.shader = RadialGradient(b.left + g.cx * w, b.top + g.cy * h, g.radius * w, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
