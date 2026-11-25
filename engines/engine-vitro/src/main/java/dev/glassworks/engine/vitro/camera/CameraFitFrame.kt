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

    /** The child's size in a [w] × [h] frame. */
    internal fun fitted(w: Int, h: Int): Pair<Int, Int> {
        if (videoW <= 0 || videoH <= 0 || w <= 0 || h <= 0 || fit == FILL) return w to h
        val scaleW = w.toFloat() / videoW
        val scaleH = h.toFloat() / videoH
        val scale = if (fit == COVER) maxOf(scaleW, scaleH) else minOf(scaleW, scaleH)
        return Math.round(videoW * scale).coerceAtLeast(1) to Math.round(videoH * scale).coerceAtLeast(1)
    }

    companion object {
        const val CONTAIN = "contain"
        const val COVER = "cover"
        const val FILL = "fill"

        /** A card's `fit_mode` (`cover`, `contain`, `fill`), else what CSS gives a [kind]'s element by default. */
        fun fitFor(fitMode: String?, kind: CameraStreamKind): String = when (fitMode) {
            CONTAIN, COVER, FILL -> fitMode
            else -> if (kind == CameraStreamKind.MJPEG) FILL else CONTAIN
        }
    }
}
