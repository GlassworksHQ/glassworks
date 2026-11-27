/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.graphics.Rect
import android.view.View
import android.widget.ScrollView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Which of a dashboard view's cameras plays into a SurfaceView, the one video the panel's composer can overlay: the
 * largest one on screen when the view first lays its cameras out. Every other camera of the view plays into a
 * TextureView. More Info's camera always asks for one ([dev.glassworks.engine.vitro.cards.LiveCameraFeed]'s
 * `foreground`).
 *
 * A TextureView's frame makes the window draw again and is composited by the GPU with it; a SurfaceView's frame
 * goes to the composer as a layer of its own and redraws nothing. The S1's composer (Allwinner A133, its screen
 * rotated) overlays one video layer: with a SurfaceView for every camera, the others and the window fell back to the
 * GPU at video rate, so only one gets one here.
 *
 * Chosen once per view (its [ScrollView]), not as it scrolls: a camera that changed kind would close its player and
 * connect again, and its surface would go and come back. The choice is forgotten when the chosen camera stops (its
 * card went), so the next camera to open in that view chooses again. Not chosen:
 * - a camera off screen, or in a card that draws through a layer (HA's `state_filter`, a fade): a SurfaceView's
 *   picture shows through a hole in the window, and a layer or a partly transparent parent closes the hole.
 *
 * Main thread only.
 */
object CameraOverlay {
    /** Off: every camera plays into a TextureView, as before (tests). */
    @JvmStatic
    var enabled: Boolean = true

    private val chosen = WeakHashMap<View, WeakReference<Any>>()
    private val rect = Rect()

    /**
     * Whether [owner], whose picture goes in [host], gets the view's SurfaceView: true or false, or null while [host]
     * isn't laid out in a window yet (ask again after its layout). [candidates] are the view's other live cameras
     * (owner and host each, [owner] among them), asked only when the view has no choice yet.
     */
    fun choose(owner: Any, host: View, candidates: () -> List<Pair<Any, View>>): Boolean? {
        if (!enabled) return false
        if (!host.isAttachedToWindow || host.width <= 0 || host.height <= 0) return null
        val root = rootOf(host)
        chosen[root]?.let { ref ->
            val held = ref.get()
            if (held != null) return held === owner
        }
        var best: Any? = null
        var bestArea = 0L
        for ((o, v) in candidates()) {
            if (!v.isAttachedToWindow || rootOf(v) !== root) continue
            val area = visibleArea(v, root)
            if (area > bestArea) {
                best = o
                bestArea = area
            }
        }
        // Nothing on screen yet: no choice is kept, the next camera to open asks again.
        val pick = best ?: return false
        chosen[root] = WeakReference(pick)
        return pick === owner
    }

    /** [owner] stopped: a view it was chosen for chooses again. */
    fun forget(owner: Any) {
        chosen.entries.removeAll { it.value.get().let { o -> o == null || o === owner } }
    }

    /** The dashboard view [v] is in: its nearest [ScrollView], else its window's root. */
    internal fun rootOf(v: View): View {
        var p = v.parent
        while (p is View) {
            if (p is ScrollView) return p
            p = p.parent
        }
        return v.rootView
    }

    /**
     * [v]'s area on screen in pixels, or 0 when none of it is, or when it draws through a layer or a partly
     * transparent parent up to [root] (a SurfaceView's hole wouldn't open there).
     */
    internal fun visibleArea(v: View, root: View): Long {
        var p: View? = v
        while (p != null) {
            if (p.layerType != View.LAYER_TYPE_NONE || p.alpha < 1f || p.visibility != View.VISIBLE) return 0
            if (p === root) break
            p = p.parent as? View
        }
        if (!v.getGlobalVisibleRect(rect)) return 0
        return rect.width().toLong() * rect.height()
    }

    /** Tests: no view has a choice, and the overlay is on. */
    internal fun reset() {
        chosen.clear()
        enabled = true
    }
}
