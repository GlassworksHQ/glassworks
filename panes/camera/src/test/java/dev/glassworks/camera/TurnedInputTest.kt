/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.opengl.Matrix
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TurnedInputTest {
    /** Where output point ([x], [y]) reads the source, both 0 to 1 from the bottom left. */
    private fun source(turn: Int, x: Float, y: Float): Pair<Float, Float> {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, textureTurn(turn), 0, floatArrayOf(x, y, 0f, 1f), 0)
        return Math.round(out[0] * 100) / 100f to Math.round(out[1] * 100) / 100f
    }

    @Test fun noTurnReadsTheSameSpot() {
        assertEquals(0.25f to 0.75f, source(0, 0.25f, 0.75f))
    }

    @Test fun aClockwiseQuarterTurnPutsTheSourcesBottomLeftAtTheTopLeft() {
        // Turned clockwise, the picture's left edge becomes its top: the output's top left shows the source's bottom left.
        assertEquals(0f to 0f, source(90, 0f, 1f))
        assertEquals("the output's top right shows the source's top left", 0f to 1f, source(90, 1f, 1f))
        assertEquals("the output's bottom left shows the source's bottom right", 1f to 0f, source(90, 0f, 0f))
    }

    @Test fun halfATurnReadsTheOppositeCorner() {
        assertEquals(1f to 1f, source(180, 0f, 0f))
    }

    @Test fun theMirrorsTurnIsAQuarterTurnTheOtherWay() {
        // 270 clockwise: the output's top left shows the source's top right.
        assertEquals(1f to 1f, source(270, 0f, 1f))
    }

    /** GL's usual SurfaceTexture matrix: the buffer's top row first, inside a crop window [inset] in from each edge. */
    private fun usual(inset: Float = 0f): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, inset, 1f - inset, 0f)
        Matrix.scaleM(m, 0, 1f - 2 * inset, -(1f - 2 * inset), 1f)
        return m
    }

    private fun times(a: FloatArray, b: FloatArray) = FloatArray(16).also { Matrix.multiplyMM(it, 0, a, 0, b, 0) }

    private fun assertSame(expected: FloatArray, actual: FloatArray, why: String = "") =
        assertEquals(why, expected.map { Math.round(it * 1000) / 1000f }, actual.map { Math.round(it * 1000) / 1000f })

    @Test fun theUsualTextureMatrixStaysAsItIs() {
        assertSame(usual(), rawTexture(usual()))
        assertSame(usual(0.01f), rawTexture(usual(0.01f)), "a crop window stays")
    }

    @Test fun aTurnTheCameraPutsInTheTextureIsLeftOut() {
        // The Pixel Tablet's front camera: Camera2 hands the texture a quarter turn for its sensor at 90°.
        for (turn in listOf(90, 180, 270)) assertSame(usual(0.01f), rawTexture(times(usual(0.01f), textureTurn(turn))))
    }

    @Test fun aFrontCamerasFlipIsLeftOutToo() {
        val flip = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, 1f, 0f, 0f); Matrix.scaleM(it, 0, -1f, 1f, 1f) }
        assertSame(usual(), rawTexture(times(usual(), flip)))
        assertSame(usual(), rawTexture(times(times(usual(), textureTurn(90)), flip)))
    }

    @Test fun aQuarterTurnSwapsTheSize() {
        assertEquals(720 to 1280, turnedSize(1280, 720, 270))
        assertEquals(720 to 1280, turnedSize(1280, 720, 90))
        assertEquals(1280 to 720, turnedSize(1280, 720, 180))
        assertEquals(1280 to 720, turnedSize(1280, 720, 0))
    }

    @Test fun anEncoderListedLandscapeTakesItsSizeTurned() {
        val mirror = { w: Int, h: Int -> w <= 1920 && h <= 1088 }
        assertEquals("720x1280 fits as 1280x720", true, takesEitherWay(720, 1280, mirror))
        assertEquals(true, takesEitherWay(1280, 720, mirror))
        assertEquals("too big either way", false, takesEitherWay(1088, 2400, mirror))
    }
}
