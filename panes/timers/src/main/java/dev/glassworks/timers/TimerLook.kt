package dev.glassworks.timers

import android.graphics.Color
import dev.glassworks.theme.Themes

/**
 * The timers' colors in a theme (#691's approved mockups): the theme's two lights run down the ring (a to b), the
 * glass is dark on a dark ground and near white on a light one, and text takes the ground's ink. Amber stays the
 * last 10 seconds' color in every theme: it is a status color, as green (done) and rose (errors) are. On a light
 * ground the amber that text uses is darkened until it reads ([Themes.Palette.readable]).
 */
class TimerLook(val palette: Themes.Palette) {
    val light = !palette.darkGround

    /** The ring's two lights. */
    val a = palette.a
    val b = palette.b

    /** Text: the ground's ink, then muted and faint mixes of it toward the ground. */
    val ink = palette.ink
    val mu = Themes.mix(ink, palette.ground, 0.35f)
    val fa = Themes.mix(ink, palette.ground, 0.58f)

    /** The glass the orbs, the chip and the peek are made of (opaque: no blur on the S1). */
    val glass = if (light) Color.rgb(251, 251, 252) else Color.rgb(15, 16, 21)

    /** A 1 dp rim on the glass. */
    val edge = if (light) Color.argb(26, 16, 20, 32) else Color.argb(36, 255, 255, 255)

    /** The ring's track under the lights. */
    val track = if (light) Color.argb(20, 16, 20, 32) else Color.argb(26, 255, 255, 255)

    /** The top highlight on the chip's glass. */
    val sheen = if (light) Color.argb(150, 255, 255, 255) else Color.argb(15, 255, 255, 255)

    /** The name tag's and the drag tag's pill: the ground, nearly opaque. */
    val tag = withAlpha(palette.ground, if (light) 0.92f else 0.82f)

    /** The dark round under each orb that keeps it apart from a busy page: the ground. */
    val halo = palette.ground and 0xffffff

    /** The last 10 seconds: amber on the ring, and amber text that reads on the ground. */
    val hotRing = AMBER
    val hotText = palette.readable(AMBER, 4.5)

    /** A light that reads on the ground, for small strokes (the chip's arrow, the peek's arc). */
    val accent = palette.readable(palette.a, 3.0)

    /** The guide's veil over the page while the group is dragged. */
    val veil = withAlpha(palette.ground, if (light) 0.55f else 0.42f)

    companion object {
        const val AMBER = 0xfffbbf24.toInt()

        /** Glassworks, dark: the default. */
        val DEFAULT = TimerLook(Themes.DEFAULT.palette)

        fun withAlpha(c: Int, a: Float) = (c and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
