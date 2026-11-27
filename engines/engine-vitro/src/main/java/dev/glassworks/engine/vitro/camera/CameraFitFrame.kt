/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Holds one stream's view and fits it in its own bounds as CSS `object-fit` fits HA's `<video>` or `<img>`:
 * `contain` (letterboxed, the video's default), `cover` (cropped) or `fill` (stretched, the image's default). Until
 * the picture's size is known ([setVideoSize]) the view fills the frame. The frame clips what `cover` puts outside.
 *
 * A player on a SurfaceView ([surfaceShown]) is composited under the window, so it can't fade with its card: while
 * anything above the frame is partly transparent, or the frame itself is (a card's visibility fading it in or out,
 * a view switching), the SurfaceView is made invisible, so its surface goes and the card's still shows, fading with
 * the card as HA fades the whole card, video included. Fully opaque again, the surface comes back and its player
 * draws into it. The frame's own alpha 0 is the stream choice hiding a player that loads: that one keeps its surface.
 */
class CameraFitFrame(context: Context, fit: String) : ViewGroup(context) {
    var fit: String = fit
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var videoW = 0
    private var videoH = 0

    init {
        clipChildren = true
    }

    /** False while a SurfaceView player is held back because something above it is partly transparent. Main thread. */
    var surfaceShown = true
        private set

    /** Each frame drawn: whether the surface shows. A change cancels this draw, so no frame shows the video mid-fade. */
    private val checkFade = android.view.ViewTreeObserver.OnPreDrawListener { !updateSurfaceShown() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(checkFade)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(checkFade)
        super.onDetachedFromWindow()
    }

    /** Shows or hides a SurfaceView player as [fading] says. True when that changed something. */
    internal fun updateSurfaceShown(): Boolean {
        val want = (0 until childCount).none { getChildAt(it) is android.view.SurfaceView } || !fading()
        var changed = want != surfaceShown
        surfaceShown = want
        val visibility = if (want) View.VISIBLE else View.INVISIBLE
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is android.view.SurfaceView && c.visibility != visibility) {
                c.visibility = visibility
                changed = true
            }
        }
        return changed
    }

    /** The frame partly transparent, or anything above it below full opacity. */
    private fun fading(): Boolean {
        if (alpha > 0f && alpha < 1f) return true
        var v = parent as? View
        while (v != null) {
            if (v.alpha < 1f) return true
            v = v.parent as? View
        }
        return false
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width == videoW && height == videoH) return
        videoW = width
        videoH = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (cw, ch) = fitted(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            val left = (w - c.measuredWidth) / 2
            val top = (h - c.measuredHeight) / 2
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }

    /**
     * The child's size in a [w] × [h] frame. An axis that comes out within [SNAP_PX] of the frame takes the frame's
     * size: a card sized to the video's shape rounds its height to whole pixels, so `contain` would leave a gap of a
     * pixel or so, all of it on the right or bottom edge (the child is centred with integer offsets) where the card's
     * background shows as a dark line. The browser lays HA's `<video>` out in fractions of a pixel and shows no gap;
     * the stretch this costs is under 0.2% of a 720p picture.
     */
    internal fun fitted(w: Int, h: Int): Pair<Int, Int> {
        if (videoW <= 0 || videoH <= 0 || w <= 0 || h <= 0 || fit == FILL) return w to h
        val scaleW = w.toFloat() / videoW
        val scaleH = h.toFloat() / videoH
        val scale = if (fit == COVER) maxOf(scaleW, scaleH) else minOf(scaleW, scaleH)
        val cw = Math.round(videoW * scale).coerceAtLeast(1)
        val ch = Math.round(videoH * scale).coerceAtLeast(1)
        return (if (Math.abs(cw - w) <= SNAP_PX) w else cw) to (if (Math.abs(ch - h) <= SNAP_PX) h else ch)
    }

    companion object {
        const val CONTAIN = "contain"
        const val COVER = "cover"
        const val FILL = "fill"

        /** How close, in pixels, a fitted axis may come to the frame before it takes the frame's size ([fitted]). */
        internal const val SNAP_PX = 2

        /** A card's `fit_mode` (`cover`, `contain`, `fill`), else what CSS gives a [kind]'s element by default. */
        fun fitFor(fitMode: String?, kind: CameraStreamKind): String = when (fitMode) {
            CONTAIN, COVER, FILL -> fitMode
            else -> if (kind == CameraStreamKind.MJPEG) FILL else CONTAIN
        }
    }
}
