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
import org.junit.Assert.assertEquals
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
    fun aSurfacePlayerIsHiddenWhileItsCardFadesAndBackAtFullOpacity() {
        val card = FrameLayout(activity)
        val surface = SurfaceView(activity)
        val frame = frameIn(card, surface)
        card.viewTreeObserver.dispatchOnPreDraw()
        assertTrue("opaque: the video shows", frame.surfaceShown)
        assertEquals(View.VISIBLE, surface.visibility)
        card.alpha = 0.4f
        card.viewTreeObserver.dispatchOnPreDraw()
        // Invisible, its surface goes: the system can't composite it over the fading card, the still fades instead.
        assertFalse(frame.surfaceShown)
        assertEquals(View.INVISIBLE, surface.visibility)
        card.alpha = 1f
        card.viewTreeObserver.dispatchOnPreDraw()
        assertTrue("faded in: the video is back", frame.surfaceShown)
        assertEquals(View.VISIBLE, surface.visibility)
    }

    @Test
    fun aPlayerTheChoiceHidesKeepsItsSurfaceSoItCanLoad() {
        // The stream choice hides a loading player with the frame's alpha 0: it keeps playing into its surface.
        val card = FrameLayout(activity)
        val surface = SurfaceView(activity)
        val frame = frameIn(card, surface)
        frame.alpha = 0f
        card.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(View.VISIBLE, surface.visibility)
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
