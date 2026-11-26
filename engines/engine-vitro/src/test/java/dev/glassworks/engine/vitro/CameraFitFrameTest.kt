/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.camera.CameraFitFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A camera's frame and a fading card: a player on a SurfaceView (composited under the window, so it can't fade) is
 * held back while anything above it is partly transparent, and the card's still fades in its place; a TextureView
 * player fades with the card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraFitFrameTest {
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun frameIn(card: FrameLayout, player: View): CameraFitFrame {
        val frame = CameraFitFrame(activity, CameraFitFrame.CONTAIN)
        frame.addView(player)
        card.addView(frame)
        activity.setContentView(card)
        return frame
    }

    @Test
    fun aSurfacePlayerIsHeldBackWhileItsCardFadesAndBackAtFullOpacity() {
        val card = FrameLayout(activity)
        val frame = frameIn(card, SurfaceView(activity))
        card.viewTreeObserver.dispatchOnPreDraw()
        assertTrue("opaque: the video shows", frame.surfaceShown)
        card.alpha = 0.4f
        card.viewTreeObserver.dispatchOnPreDraw()
        assertFalse("fading: the still fades in its place", frame.surfaceShown)
        card.alpha = 1f
        card.viewTreeObserver.dispatchOnPreDraw()
        assertTrue("faded in: the video is back", frame.surfaceShown)
    }

    @Test
    fun aTexturePlayerFadesWithItsCard() {
        val card = FrameLayout(activity)
        val frame = frameIn(card, TextureView(activity))
        card.alpha = 0.4f
        card.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(frame.surfaceShown)
    }
}
