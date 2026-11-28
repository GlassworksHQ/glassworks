/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.cards

import android.graphics.ColorMatrix
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * CSS `filter` functions as one [ColorMatrix], composed left to right like the browser: `grayscale`, `saturate`,
 * `sepia`, `brightness`, `contrast`, `invert`, `opacity` and `hue-rotate` (the Filter Effects spec's matrices).
 * Anything else (blur, drop-shadow, url()) is skipped. Null when nothing applies.
 */
object CssFilter {
    private val FN = Regex("""([a-z-]+)\(\s*([^)]*)\)""")

    fun matrix(css: String?): ColorMatrix? {
        val src = css?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "none" } ?: return null
        var out: ColorMatrix? = null
        for (m in FN.findAll(src)) {
            val f = m.groupValues[1]
            val arg = m.groupValues[2].trim()
            val amount = amount(arg, if (f == "hue-rotate") 0f else 1f)
            val step = when (f) {
                "grayscale" -> ColorMatrix().apply { setSaturation(1f - amount.coerceIn(0f, 1f)) }
                "saturate" -> ColorMatrix().apply { setSaturation(amount.coerceAtLeast(0f)) }
                "brightness" -> ColorMatrix().apply { setScale(amount, amount, amount, 1f) }
                "contrast" -> {
                    val t = (1f - amount) * 127.5f
                    ColorMatrix(floatArrayOf(amount, 0f, 0f, 0f, t, 0f, amount, 0f, 0f, t, 0f, 0f, amount, 0f, t, 0f, 0f, 0f, 1f, 0f))
                }
                "invert" -> {
                    val a = amount.coerceIn(0f, 1f)
                    val k = 1f - 2f * a
                    ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, 255f * a, 0f, k, 0f, 0f, 255f * a, 0f, 0f, k, 0f, 255f * a, 0f, 0f, 0f, 1f, 0f))
                }
                "opacity" -> ColorMatrix().apply { setScale(1f, 1f, 1f, amount.coerceIn(0f, 1f)) }
                "sepia" -> {
                    val a = 1f - amount.coerceIn(0f, 1f)
                    ColorMatrix(floatArrayOf(
                        0.393f + 0.607f * a, 0.769f - 0.769f * a, 0.189f - 0.189f * a, 0f, 0f,
                        0.349f - 0.349f * a, 0.686f + 0.314f * a, 0.168f - 0.168f * a, 0f, 0f,
                        0.272f - 0.272f * a, 0.534f - 0.534f * a, 0.131f + 0.869f * a, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ))
                }
                "hue-rotate" -> hueRotate(angle(arg))
                else -> null
            } ?: continue
            out = (out ?: ColorMatrix()).also { it.postConcat(step) }
        }
        return out
    }

    /** `50%` → 0.5, `0.5` → 0.5; [default] when empty. */
    private fun amount(arg: String, default: Float): Float {
        if (arg.isEmpty()) return default
        return if (arg.endsWith("%")) (arg.removeSuffix("%").toFloatOrNull() ?: 100f) / 100f else arg.toFloatOrNull() ?: default
    }

    private fun angle(arg: String): Float = when {
        arg.endsWith("deg") -> arg.removeSuffix("deg").toFloatOrNull() ?: 0f
        arg.endsWith("turn") -> (arg.removeSuffix("turn").toFloatOrNull() ?: 0f) * 360f
        arg.endsWith("rad") -> Math.toDegrees((arg.removeSuffix("rad").toDoubleOrNull() ?: 0.0)).toFloat()
        else -> arg.toFloatOrNull() ?: 0f
    }

    private fun hueRotate(deg: Float): ColorMatrix {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return ColorMatrix(floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
}
