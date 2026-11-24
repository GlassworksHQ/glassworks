/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import dev.glassworks.engine.vitro.fonts.CssFontFaces
import dev.glassworks.engine.vitro.fonts.WebFonts
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.platform.Platform
import dev.glassworks.ui.Inter
import java.util.Locale

/**
 * The face text draws in, picked as a browser picks it from a CSS `font-family` list: the first family that's there.
 *
 * - A family a Lovelace `type: css` resource declares with `@font-face` is a web font ([WebFonts]). While it loads,
 *   the text draws in the next family of the list, and the view draws again once it arrives (`font-display: swap`).
 * - Inter is bundled. Roboto, Noto and the generic sans-serif families are the system's sans-serif (Roboto on
 *   Android), `serif` and `monospace` the system's serif and monospace.
 * - Any other family ("Google Sans") is skipped, as a browser skips a font it can't load.
 *
 * The list is the view theme's body font ([family], from `--ha-font-family-body`, else `--primary-font-family`, else
 * HA's own `Roboto, Noto, sans-serif`), unless the card sets its own `font-family` with card-mod: then the card's
 * views are built on a [VitroFontContext] that carries it, and every text inside the card (cards stacked in it too)
 * inherits it, as text inherits `font-family` from `ha-card` in the browser. One dashboard per process, so one body
 * font at a time. Main thread.
 */
object VitroFonts {
    enum class Face { INTER, SYSTEM, SERIF, MONOSPACE }

    /** HA's default `--ha-font-family-body`. */
    const val HA_DEFAULT = "Roboto, Noto, sans-serif"

    private val SYSTEM_FAMILIES = setOf(
        "roboto", "noto", "noto sans", "sans-serif", "system-ui", "-apple-system", "blinkmacsystemfont",
        "arial", "helvetica", "helvetica neue", "segoe ui", "ui-sans-serif",
    )
    private val INTER_FAMILIES = setOf("inter", "inter var", "inter variable", "inter display")
    private val SERIF_FAMILIES = setOf("serif", "ui-serif", "noto serif", "droid serif")
    private val MONO_FAMILIES = setOf("monospace", "ui-monospace", "droid sans mono", "courier", "courier new")

    /** The view theme's body font list. Set from the view's theme before its cards are built. */
    var family: String = HA_DEFAULT

    /** The built-in face for one family name, or null when Vitro has none by that name. */
    fun builtIn(name: String): Face? {
        val n = name.trim().trim('"', '\'').trim().lowercase(Locale.ROOT)
        return when {
            n in INTER_FAMILIES -> Face.INTER
            n in SYSTEM_FAMILIES -> Face.SYSTEM
            n in SERIF_FAMILIES -> Face.SERIF
            n in MONO_FAMILIES || "mono" in n -> Face.MONOSPACE
            else -> null
        }
    }

    /** Whether [name] is one of the names Vitro draws itself; a guess by name ("Roboto Mono") may still be a web font. */
    private fun named(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n in INTER_FAMILIES || n in SYSTEM_FAMILIES || n in SERIF_FAMILIES || n in MONO_FAMILIES
    }

    /** The built-in face a CSS `font-family` list draws in, web fonts aside: the first family Vitro has, else sans-serif. */
    fun faceFor(fontFamily: String?): Face {
        for (name in CssFontFaces.families(fontFamily?.takeIf { it.isNotBlank() } ?: HA_DEFAULT)) builtIn(name)?.let { return it }
        return Face.SYSTEM
    }

    /** [theme]'s body font list. */
    fun familyFor(theme: ResolvedTheme): String =
        (theme.vars["ha-font-family-body"]?.takeIf { "var(" !in it && it.isNotBlank() }
            ?: theme.vars["primary-font-family"]?.takeIf { "var(" !in it && it.isNotBlank() })
            ?: HA_DEFAULT

    /** The built-in face [theme]'s body text draws in, web fonts aside. */
    fun faceFor(theme: ResolvedTheme): Face = faceFor(familyFor(theme))

    /** The font list in force for views built on [context]: a card's own (card-mod), else the theme's body font. */
    fun familyOf(context: Context): String {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is VitroFontContext) return c.fontFamily
            c = c.baseContext
        }
        return family
    }

    /**
     * Text of CSS [weight] at [sizeSp], from [fontFamily] (a CSS list), else the list in force for [context]: the
     * first family that's there, a web font that has arrived included.
     */
    fun typeface(context: Context, weight: Int, sizeSp: Float, italic: Boolean = false, fontFamily: String? = null): Typeface {
        val list = fontFamily?.takeIf { it.isNotBlank() } ?: familyOf(context)
        for (name in CssFontFaces.families(list)) {
            val known = builtIn(name)
            when (val web = WebFonts.lookup(name, weight, italic, mayFetch = !named(name))) {
                is WebFonts.Lookup.Ready -> return web.typeface
                WebFonts.Lookup.Loading -> continue
                WebFonts.Lookup.Absent -> if (known != null) return builtIn(context, known, weight, sizeSp, italic)
            }
        }
        return builtIn(context, Face.SYSTEM, weight, sizeSp, italic)
    }

    private val builtInCache = HashMap<String, Typeface>()

    private fun builtIn(context: Context, face: Face, weight: Int, sizeSp: Float, italic: Boolean): Typeface {
        val w = weight.coerceIn(100, 900)
        val inter = face == Face.INTER && Platform.hasFontBuilder
        if (!italic) {
            return when (face) {
                Face.INTER -> if (inter) Inter.typeface(context, w, sizeSp) else system(w)
                Face.SYSTEM -> system(w)
                Face.SERIF, Face.MONOSPACE -> generic(face, w, false)
            }
        }
        return when (face) {
            // Inter has no italic: slanted, at the weight it's drawn at.
            Face.INTER -> if (inter) {
                builtInCache.getOrPut("inter-i|$w|${Inter.opticalSize(sizeSp)}") { Typeface.create(Inter.typeface(context, w, sizeSp), Typeface.ITALIC) }
            } else {
                systemItalic(w)
            }
            Face.SYSTEM -> systemItalic(w)
            Face.SERIF, Face.MONOSPACE -> generic(face, w, true)
        }
    }

    private fun systemItalic(w: Int): Typeface =
        builtInCache.getOrPut("sys-i|$w") { Typeface.create(system(w), if (w >= 600) Typeface.BOLD_ITALIC else Typeface.ITALIC) }

    private fun generic(face: Face, w: Int, italic: Boolean): Typeface {
        val bold = w >= 600
        return builtInCache.getOrPut("$face|$bold|$italic") {
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(if (face == Face.SERIF) Typeface.SERIF else Typeface.MONOSPACE, style)
        }
    }

    private val systemCache = HashMap<Int, Typeface>()

    /** Roboto's light / regular / medium / bold for a CSS weight (family names every API level has). */
    fun system(weight: Int): Typeface {
        val bucket = when {
            weight <= 350 -> 300
            weight < 450 -> 400
            weight < 550 -> 500
            else -> 700
        }
        return systemCache.getOrPut(bucket) {
            when (bucket) {
                300 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                400 -> Typeface.create("sans-serif", Typeface.NORMAL)
                500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.BOLD)
            }
        }
    }
}

/**
 * The context a card with its own card-mod `font-family` builds its views on: every text inside it draws in
 * [fontFamily] ([VitroFonts.familyOf]). Anything else goes to the wrapped context.
 */
class VitroFontContext(base: Context, val fontFamily: String) : ContextWrapper(base)
