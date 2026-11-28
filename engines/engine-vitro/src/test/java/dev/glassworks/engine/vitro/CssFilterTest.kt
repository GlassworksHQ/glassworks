/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.CssFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CSS filter functions as color matrices (hui-image's grey filter for an off entity, and `state_filter`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CssFilterTest {
    private fun apply(css: String, r: Float, g: Float, b: Float): FloatArray {
        val m = CssFilter.matrix(css)!!.array
        fun row(i: Int) = (m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]).coerceIn(0f, 255f)
        return floatArrayOf(row(0), row(1), row(2))
    }

    @Test
    fun grayscaleBrightnessAndChains() {
        // HA's #18bcf2 under grayscale(100%) is the grey HA draws (measured ~157).
        val grey = apply("grayscale(100%)", 24f, 188f, 242f)
        assertEquals(grey[0], grey[1], 0.5f)
        assertEquals(grey[1], grey[2], 0.5f)
        assertEquals(157f, grey[0], 3f)
        assertEquals(50f, apply("brightness(50%)", 100f, 0f, 0f)[0], 0.5f)
        // Functions compose left to right.
        assertEquals(apply("grayscale(1) brightness(0.5)", 24f, 188f, 242f)[0], grey[0] / 2f, 1f)
        // Order matters where the functions don't commute: invert then halve is 77.5, halve then invert 205.
        assertEquals(77.5f, apply("invert(100%) brightness(50%)", 100f, 0f, 0f)[0], 1f)
        assertEquals(205f, apply("brightness(50%) invert(100%)", 100f, 0f, 0f)[0], 1f)
        assertNull(CssFilter.matrix("none"))
        assertNull(CssFilter.matrix("blur(2px)"))
    }
}
