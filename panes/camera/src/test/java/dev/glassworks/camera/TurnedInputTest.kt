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
