/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View

/**
 * Whether [guard] (a [CardSurface] or a [ContainedCard]) is the card guard nearest to [target], the view that
 * changed: no other guard lies between them, [target] included.
 *
 * With hardware drawing, Android rebuilds only the display list of a view that changed, straight from its parent's
 * list, without running any parent's draw or `drawChild`, where the guards catch a throw. So the nearest guard asks to
 * record itself again ([View.invalidate]): its draw then runs the changed view's inside the guard's catch. The
 * guard's other views keep their lists, so the cost is the guard's own small list (its background and one entry per
 * child). A guard further up leaves it to the nearer one, so a change in a card inside a stack-in-card re-records that
 * card only, never the stack round it. The nearest guard records itself again even while it has failed and waits
 * for its error card: its draw then skips the failed card's views, so they never draw outside a catch.
 */
internal fun isNearestGuard(guard: View, target: View): Boolean {
    var v: View? = target
    while (v != null && v !== guard) {
        if (v is CardSurface || v is ContainedCard) return false
        v = v.parent as? View
    }
    return v === guard
}

/**
 * Whether a change at [target] can redraw without its card guard recording itself again: a [TextureView], which a
 * camera's video draws into ([dev.glassworks.engine.vitro.camera.CameraFitFrame]), invalidates itself on every frame
 * (15 or more times a second, per camera), and recording the card each time cost the S1 a re-recorded card per frame
 * and per camera on the UI thread. A TextureView's [View.draw] and `onDraw` are final in the framework: its redraw
 * draws its texture layer and runs no card's code. The only app code it reaches is its `SurfaceTextureListener`,
 * which the camera players keep away from card code (Media3's does nothing per frame; libs/ha-webrtc's posts its
 * frame report), and `onSurfaceTextureAvailable`, which comes in the view's first draw, after it was added to its
 * parent, so inside the card's own recording.
 */
internal fun redrawsWithoutCardCode(target: View): Boolean = target is TextureView

/**
 * Android 6 and 7, where hardware drawing reports a change through `invalidateChildInParent`, which doesn't say what
 * changed: a [dev.glassworks.engine.vitro.camera.CameraFitFrame] holding a video's [TextureView] marks the change as
 * the video's ([from]) before it passes it up, so the card guards above it can tell ([isVideoFrame]) and leave it, as
 * [redrawsWithoutCardCode] leaves it on Android 8 and later. The mark lasts for the rest of the main-thread message
 * the change came in (a frame's is the TextureView's own `onFrameAvailable`, which does nothing else): it is cleared
 * first thing after it, so a later change elsewhere never passes as a video frame. Main thread.
 */
internal object VideoRedraw {
    private var from: View? = null
    private var clearPosted = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val clear = Runnable {
        clearPosted = false
        from = null
    }

    /** [frame], whose one child is a video's TextureView, passes that view's change up now. */
    fun from(frame: View) {
        from = frame
        if (!clearPosted) {
            clearPosted = true
            main.postAtFrontOfQueue(clear)
        }
    }

    /** The change [guard] hears of now is a video frame inside it. */
    fun isVideoFrame(guard: View): Boolean {
        var v: View? = from ?: return false
        while (v != null) {
            if (v === guard) return true
            v = v.parent as? View
        }
        return false
    }

    internal fun resetForTest() {
        from = null
    }
}
