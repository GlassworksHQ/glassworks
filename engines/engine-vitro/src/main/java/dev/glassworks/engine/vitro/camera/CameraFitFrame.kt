/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.camera

import android.content.Context
import android.graphics.Rect
import android.os.Trace
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import dev.glassworks.engine.vitro.ui.VideoRedraw
import dev.glassworks.platform.Platform

/**
 * Holds one stream's view and fits it in its own bounds as CSS `object-fit` fits HA's `<video>` or `<img>`:
 * `contain` (letterboxed, the video's default), `cover` (cropped) or `fill` (stretched, the image's default). Until
 * the picture's size is known ([setVideoSize]) the view fills the frame. The frame clips what `cover` puts outside.
 *
 * Its player draws into a TextureView, so the video fades, clips and filters with its card as HA's `<video>` does;
 * the view's one overlay camera ([CameraOverlay]) into a SurfaceView, whose hole in the window the frame's clip shapes.
 */
class CameraFitFrame(context: Context, fit: String) : ViewGroup(context) {
    var fit: String = fit
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            coverMayHaveChanged()
        }

    private var videoW = 0
    private var videoH = 0

    /**
     * Its picture crops itself to its view for `cover` ([CameraStreamCrop]: a SurfaceView's): the view then gets the
     * frame's size, not a larger one the frame clips. Main thread.
     */
    var childCrops: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            coverMayHaveChanged()
        }

    /**
     * The picture fills the whole frame, no letterbox: `cover`, `fill`, a video whose shape is the frame's (within
     * [SNAP_PX]), or one whose size isn't known yet (it fills the frame then). False before the frame has a size.
     * What's behind the frame (the card's still) shows only where this is false.
     */
    val coversFrame: Boolean
        get() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return false
            // `cover` makes the picture larger than the frame on one axis (the frame clips it): that fills it too.
            val (cw, ch) = fitted(w, h)
            return cw >= w && ch >= h
        }

    /** Called when [coversFrame] changes: a new video size, fit or frame size. Main thread. */
    var onCoverChanged: (() -> Unit)? = null

    private var coveredLast = false

    private fun coverMayHaveChanged() {
        val now = coversFrame
        if (now == coveredLast) return
        coveredLast = now
        onCoverChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        coverMayHaveChanged()
    }

    init {
        clipChildren = true
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width == videoW && height == videoH) return
        videoW = width
        videoH = height
        requestLayout()
        coverMayHaveChanged()
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

    /**
     * Android 8 and later: a change inside passes up to the window. A video frame's (its TextureView's, on every frame)
     * is a `vitro camera invalidate` trace section, the card guards' part of it included.
     */
    override fun onDescendantInvalidated(child: View, target: View) {
        if (target !is TextureView) return super.onDescendantInvalidated(child, target)
        Trace.beginSection(TRACE_INVALIDATE)
        try {
            super.onDescendantInvalidated(child, target)
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Android 6 and 7: its child changed, and when that's a video's TextureView (a new frame), the card guards above
     * hear it as one ([VideoRedraw]) and don't record themselves again for it. Android 8 and later tell them which
     * view changed instead.
     */
    @Deprecated("ViewGroup's, deprecated in API 26, where onDescendantInvalidated takes over")
    override fun invalidateChildInParent(location: IntArray, dirty: Rect): ViewParent? {
        if (!Platform.hasDescendantInvalidated && childCount == 1 && getChildAt(0) is TextureView) VideoRedraw.from(this)
        @Suppress("DEPRECATION")
        return super.invalidateChildInParent(location, dirty)
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
        if (videoW <= 0 || videoH <= 0 || w <= 0 || h <= 0 || fit == FILL || (fit == COVER && childCrops)) return w to h
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

        private const val TRACE_INVALIDATE = "vitro camera invalidate"

        /** How close, in pixels, a fitted axis may come to the frame before it takes the frame's size ([fitted]). */
        internal const val SNAP_PX = 2

        /** A card's `fit_mode` (`cover`, `contain`, `fill`), else what CSS gives a [kind]'s element by default. */
        fun fitFor(fitMode: String?, kind: CameraStreamKind): String = when (fitMode) {
            CONTAIN, COVER, FILL -> fitMode
            else -> if (kind == CameraStreamKind.MJPEG) FILL else CONTAIN
        }
    }
}
