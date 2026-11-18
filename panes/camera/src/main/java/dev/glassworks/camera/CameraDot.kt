/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.platform.Platform
import dev.glassworks.platform.PlatformApi
import dev.glassworks.ui.Glass

/**
 * The camera's privacy indicator (#117; maintainer, 2026-11-14): a small green dot in the top-right
 * corner while images leave the panel (a still to Home Assistant, the stream to a viewer). Only where
 * Android has no indicator of its own ([PlatformApi.hasSystemCameraIndicator]); from Android 12 its own
 * dot says it. It sits in the corner's margin over no content, above every overlay, and takes no
 * touches. Main thread.
 */
class CameraDot(context: Context, private val platform: PlatformApi = Platform) : View(context) {

    private val g = Glass(context)

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Glass.GREEN)
            setStroke(g.px(1.5f).coerceAtLeast(1), Color.argb(200, 14, 15, 19)) // seen on a light dashboard too
        }
        contentDescription = "The camera is sending images"
        isClickable = false
        isFocusable = false
        elevation = g.px(30f).toFloat() // above the settings menu (24 dp) and the Thread join screen (26 dp)
        visibility = GONE
    }

    /** Shown while [leaving], where Android has no camera indicator of its own; hidden otherwise. */
    fun show(leaving: Boolean) {
        visibility = if (leaving && !platform.hasSystemCameraIndicator) VISIBLE else GONE
    }

    /** Where it sits in the activity's root: the top-right corner, in its margin. */
    fun layoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(g.px(SIZE_DP), g.px(SIZE_DP), Gravity.TOP or Gravity.END).apply {
            topMargin = g.px(MARGIN_DP); marginEnd = g.px(MARGIN_DP)
        }

    companion object {
        const val SIZE_DP = 8f
        const val MARGIN_DP = 4f
    }
}
