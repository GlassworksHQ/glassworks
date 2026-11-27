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
 * Its player draws into a TextureView, so the video fades, clips and filters with its card as HA's `<video>` does.
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
