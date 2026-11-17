/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout

/**
 * The page's side of a notification's half-screen view (#230): its still picture, and its place on screen.
 *
 * What a capture costs is logged only when asked, so the panel pays nothing for it otherwise:
 * `adb shell setprop log.tag.S1Kiosk.ViewCapture DEBUG`. One line per capture: the time from the request
 * to the picture, its size, and this process's PSS before and after ([Debug.getPss]; the engine's own
 * content processes are not in it).
 */
object PageCapture {
    const val TAG = "S1Kiosk.ViewCapture"

    fun capture(page: Page?, done: (Bitmap?) -> Unit) {
        if (page == null) return done(null)
        val measure = Log.isLoggable(TAG, Log.DEBUG)
        val pssBefore = if (measure) Debug.getPss() else 0L
        val asked = SystemClock.elapsedRealtime()
        page.capture { b ->
            if (measure) {
                val ms = SystemClock.elapsedRealtime() - asked
                val picture = b?.let { "${it.width}x${it.height}, ${it.byteCount / 1024} KB" } ?: "no picture"
                Log.d(TAG, "capture: $ms ms, $picture, PSS $pssBefore -> ${Debug.getPss()} KB")
            }
            done(b)
        }
    }

    /** Puts the page's [view] in [area] (px, in its parent FrameLayout), or back to full screen with null. */
    fun place(view: View, area: Rect?) {
        view.layoutParams = if (area == null) FrameLayout.LayoutParams(-1, -1)
        else FrameLayout.LayoutParams(area.width(), area.height()).apply { leftMargin = area.left; topMargin = area.top }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "page: ${area?.toShortString() ?: "full screen"}")
    }
}
