/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.theme

/**
 * The themes of the panel's own screens over the dashboard (#691): voice, timers, notifications and setup. The
 * dashboard keeps Home Assistant's theme. Each theme is two lights and a ground, in a dark and a light variant:
 * light [Palette.a] comes from the top left and light [Palette.b] from the bottom right, as the brand's glass is lit.
 *
 * Status colours stay status in every theme (green done, amber a timer's last seconds, rose errors), and no named theme
 * uses Ember (Kiln's colour). A theme is only its palettes, so a [custom] one (colours someone picked, #691's "Custom
 * themes") works the same as a named one. Text on a palette takes its colour from contrast ([Palette.ink],
 * [Palette.readable]), so it stays readable on any ground. The picker's choice is one id, `<theme>/<variant>`: [Pick].
 * Pure.
 */
object Themes {
    /** Two lights and a ground, as 0xAARRGGBB (opaque). */
    class Palette(val a: Int, val b: Int, val ground: Int) {
        /** Whether the ground is dark: the light text goes on it. From the ground itself, so a custom palette is right too. */
        val darkGround: Boolean get() = luminance(ground) < 0.18

        /**
         * The text colour on the ground: Glassworks' near-white or near-black ink, whichever stands out more, while it
         * reaches 4.5:1 (WCAG's text contrast); on a mid ground where neither does, pure white or black, whichever does.
         */
        val ink: Int get() {
            val branded = if (contrast(INK_LIGHT, ground) >= contrast(INK_DARK, ground)) INK_LIGHT else INK_DARK
            if (contrast(branded, ground) >= 4.5) return branded
            return if (contrast(WHITE, ground) >= contrast(BLACK, ground)) WHITE else BLACK
        }

        /**
         * [colour] as text or a thin line on the ground: itself when it stands out at least [min] to 1 (WCAG's 3:1 for large
         * text and lines), else mixed towards [ink] until it does. A light a pale theme gave stays visible on its ground.
         */
        fun readable(colour: Int, min: Double = 3.0): Int {
            if (contrast(colour, ground) >= min) return colour
            for (k in 1..20) { val c = mix(colour, ink, k / 20f); if (contrast(c, ground) >= min) return c }
            return ink
        }

        override fun equals(other: Any?) = other is Palette && other.a == a && other.b == b && other.ground == ground
        override fun hashCode() = (a * 31 + b) * 31 + ground
    }

    class Theme(val id: String, val name: String, val dark: Palette, val light: Palette)

    /**
     * Home Assistant's theme (#691): the dashboard's own colors as the kiosk extension reads them (its primary and
     * accent colors as the two lights, its background as the ground). One pick, [HA_PICK]: light or dark is whatever
     * Home Assistant is, so the ground decides.
     */
    const val HA = "ha"
    const val HA_PICK = "ha/auto"

    /** Home Assistant's theme from the palette its dashboard shows now. */
    fun ha(p: Palette) = Theme(HA, "Home Assistant", p, p)

    /** The custom theme's id: its palettes are the ones someone picked ([custom], [encode]). */
    const val CUSTOM = "custom"

    /** A custom theme from the palettes someone picked for its dark and its light variant. */
    fun custom(dark: Palette, light: Palette) = Theme(CUSTOM, "Custom", dark, light)

    /** The custom theme before any color is picked: Glassworks' colors, to change from. */
    val CUSTOM_START: Theme get() = custom(GLASSWORKS.dark, GLASSWORKS.light)

    /**
     * Colors to offer for a custom theme, `#rrggbb` each: every theme's lights for a light ([ground] false), every
     * theme's grounds for a ground. Light before dark variant, theme by theme, without repeats.
     */
    fun suggestions(ground: Boolean): List<String> = ALL.flatMap { t ->
        listOf(t.light, t.dark).flatMap { p -> if (ground) listOf(p.ground) else listOf(p.a, p.b) }
    }.map(::hex).distinct()

    val GLASSWORKS = Theme("glassworks", "Glassworks",
        dark = Palette(0xff38bdf8.toInt(), 0xffa78bfa.toInt(), 0xff0a0b0f.toInt()),
        light = Palette(0xff0ea5e9.toInt(), 0xff8b5cf6.toInt(), 0xffeceef2.toInt()))
    val AURORA = Theme("aurora", "Aurora",
        dark = Palette(0xff5eead4.toInt(), 0xffc4b5fd.toInt(), 0xff06100f.toInt()),
        light = Palette(0xff14b8a6.toInt(), 0xffa855f7.toInt(), 0xffe7f0ee.toInt()))
    val DUSK = Theme("dusk", "Dusk",
        dark = Palette(0xff818cf8.toInt(), 0xfff0abfc.toInt(), 0xff0c0a14.toInt()),
        light = Palette(0xff6366f1.toInt(), 0xffd946ef.toInt(), 0xffeeecf5.toInt()))
    val DUNE = Theme("dune", "Dune",
        dark = Palette(0xfff1d6ae.toInt(), 0xffc39a78.toInt(), 0xff110e0b.toInt()),
        light = Palette(0xffb88a5c.toInt(), 0xff8c5f45.toInt(), 0xfff3efe8.toInt()))
    val GRAPHITE = Theme("graphite", "Graphite",
        dark = Palette(0xfff4f4f5.toInt(), 0xff8b8b94.toInt(), 0xff0b0b0c.toInt()),
        light = Palette(0xff52525b.toInt(), 0xffa1a1aa.toInt(), 0xffededee.toInt()))

    /** In the picker's order. Glassworks first: it is the default. */
    val ALL = listOf(GLASSWORKS, AURORA, DUSK, DUNE, GRAPHITE)

    /** One theme in one variant: what the overlays draw with. */
    class Pick(val theme: Theme, val dark: Boolean) {
        val id: String get() = id(theme, dark)
        val palette: Palette get() = if (dark) theme.dark else theme.light
        /** In words: "Glassworks, dark"; Home Assistant's is just "Home Assistant" (it decides light or dark). */
        val name: String get() = if (theme.id == HA) theme.name else "${theme.name}, ${if (dark) "dark" else "light"}"
    }

    /** Glassworks, dark: today's look. */
    val DEFAULT = Pick(GLASSWORKS, dark = true)

    fun id(theme: Theme, dark: Boolean): String = if (theme.id == HA) HA_PICK else theme.id + if (dark) "/dark" else "/light"

    /**
     * The pick an id names, or [DEFAULT] for none or one this build doesn't know. `custom/<variant>` names [custom]'s
     * palettes, the default too while none are saved; [HA_PICK] names [ha]'s, in the variant its ground is, and is the
     * default too while the dashboard hasn't shown its colors.
     */
    fun pick(id: String?, custom: Theme? = null, ha: Theme? = null): Pick {
        val parts = id?.split('/')?.takeIf { it.size == 2 } ?: return DEFAULT
        if (parts[0] == HA) return ha?.takeIf { parts[1] == "auto" }?.let { Pick(it, it.dark.darkGround) } ?: DEFAULT
        val theme = (ALL + listOfNotNull(custom)).firstOrNull { it.id == parts[0] } ?: return DEFAULT
        return when (parts[1]) {
            "dark" -> Pick(theme, true)
            "light" -> Pick(theme, false)
            else -> DEFAULT
        }
    }

    /** A custom theme's palettes as text, for the settings file: `dark;light`, each `a,b,ground` as `#rrggbb`. */
    fun encode(t: Theme): String = listOf(t.dark, t.light).joinToString(";") { p -> listOf(p.a, p.b, p.ground).joinToString(",") { hex(it) } }

    /** A custom theme from [encode]'s text, or null when it isn't one (none saved, or written by something else). */
    fun decode(text: String?): Theme? {
        val halves = text?.split(';')?.takeIf { it.size == 2 } ?: return null
        val ps = halves.map { h -> h.split(',').takeIf { it.size == 3 }?.map { colour(it) ?: return null } ?: return null }
        return custom(Palette(ps[0][0], ps[0][1], ps[0][2]), Palette(ps[1][0], ps[1][1], ps[1][2]))
    }

    /** `#rrggbb` as an opaque colour, or null. */
    fun colour(text: String): Int? = Regex("#[0-9a-fA-F]{6}").matchEntire(text.trim())?.let { (0xff000000 or it.value.substring(1).toLong(16)).toInt() }

    fun hex(c: Int): String = "#%06x".format(c and 0xffffff)

    /** Glassworks' inks: the screen's text on night, and on day ([dev.glassworks.brand.Tokens]'s fg). */
    const val INK_LIGHT = 0xfff4f4f5.toInt()
    const val INK_DARK = 0xff0c0e13.toInt()
    private const val WHITE = 0xffffffff.toInt()
    private const val BLACK = 0xff000000.toInt()

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xff) + 0.7152 * ch((c shr 8) and 0xff) + 0.0722 * ch(c and 0xff)
    }

    /** WCAG contrast ratio of two colours, 1 to 21. */
    fun contrast(x: Int, y: Int): Double {
        val l1 = luminance(x); val l2 = luminance(y)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** [c] mixed [k] of the way to [d], opaque. */
    fun mix(c: Int, d: Int, k: Float): Int {
        fun ch(shift: Int) = (((c shr shift) and 0xff) + (((d shr shift) and 0xff) - ((c shr shift) and 0xff)) * k).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Every pick as (id, name), theme by theme, light before dark, then Home Assistant's one when its colors are known
     * ([ha]), then [custom]'s when there is one: the picker's options.
     */
    fun options(custom: Theme? = null, ha: Theme? = null): List<Pair<String, String>> =
        ALL.flatMap { t -> listOf(false, true).map { dark -> Pick(t, dark).let { it.id to it.name } } } +
            listOfNotNull(ha?.let { HA_PICK to it.name }) +
            listOfNotNull(custom).flatMap { t -> listOf(false, true).map { dark -> Pick(t, dark).let { it.id to it.name } } }

    /** One palette as text, `a,b,ground` as `#rrggbb` (Home Assistant's, in the settings file). */
    fun encode(p: Palette): String = listOf(p.a, p.b, p.ground).joinToString(",") { hex(it) }

    /** A palette from [encode]'s text, or null when it isn't one. */
    fun decodePalette(text: String?): Palette? {
        val c = text?.split(',')?.takeIf { it.size == 3 }?.map { colour(it) ?: return null } ?: return null
        return Palette(c[0], c[1], c[2])
    }
}
