/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.os.SystemClock

/**
 * Whether a dashboard view is scrolling now: a drag or a fling moved it within the last [QUIET_MS]
 * ([dev.glassworks.engine.ScrollTuning.Knob.ANIM_SNAP]). A fling moves the view on every frame, so the window stays
 * open until it stops. Main thread.
 */
internal object ScrollActivity {
    /** A frame or two at 60 Hz, and the gap between a drag's last move and its fling's first. */
    private const val QUIET_MS = 100L

    private var lastMovedAt = Long.MIN_VALUE / 2

    /** The view moved (its scroll listener). */
    fun moved() {
        lastMovedAt = SystemClock.uptimeMillis()
    }

    fun scrolling(): Boolean = SystemClock.uptimeMillis() - lastMovedAt < QUIET_MS
}
