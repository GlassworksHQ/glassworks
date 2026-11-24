/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.graphics.Typeface
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.ui.Inter

/**
 * The text face the theme asks for, as a browser picks it: the first family in `--ha-font-family-body` (else
 * `--primary-font-family`, else HA's own `Roboto, Noto, sans-serif`) that Vitro can draw. Inter is bundled; Roboto,
 * Noto and the generic families are the system's sans-serif (Roboto on Android). Families Vitro doesn't have
 * ("Google Sans") are skipped, as a browser skips a font it can't load. Set from the view's theme before its cards
 * are built; one dashboard per process, so one face at a time. Main thread.
 */
object VitroFonts {
    enum class Face { INTER, SYSTEM }

    /** HA's default `--ha-font-family-body`. */
    const val HA_DEFAULT = "Roboto, Noto, sans-serif"

    private val SYSTEM_FAMILIES = setOf(
        "roboto", "noto", "noto sans", "sans-serif", "system-ui", "-apple-system", "blinkmacsystemfont",
        "arial", "helvetica", "helvetica neue", "segoe ui", "ui-sans-serif",
    )
    private val INTER_FAMILIES = setOf("inter", "inter var", "inter variable", "inter display")

    var face: Face = faceFor(HA_DEFAULT)

    /** The face a CSS `font-family` list draws in. */
    fun faceFor(fontFamily: String?): Face {
        for (raw in (fontFamily?.takeIf { it.isNotBlank() } ?: HA_DEFAULT).split(',')) {
            val name = raw.trim().trim('"', '\'').trim().lowercase()
            if (name in INTER_FAMILIES) return Face.INTER
            if (name in SYSTEM_FAMILIES) return Face.SYSTEM
        }
        return Face.SYSTEM
    }

    /** The face [theme]'s body text draws in. */
    fun faceFor(theme: ResolvedTheme): Face =
        faceFor(theme.vars["ha-font-family-body"]?.takeIf { "var(" !in it } ?: theme.vars["primary-font-family"])

    /** Text of CSS [weight] at [sizeSp] in the current [face]. */
    fun typeface(context: Context, weight: Int, sizeSp: Float): Typeface =
        if (face == Face.INTER) Inter.typeface(context, weight, sizeSp) else system(weight)

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
