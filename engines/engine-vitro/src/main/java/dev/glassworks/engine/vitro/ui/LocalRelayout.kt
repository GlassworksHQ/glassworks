/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.view.View

/**
 * Keeps a live update's layout inside [host]. A text view sized to its text asks for a layout up to the window each time
 * its text changes, so a sensor row's value or a clock's seconds would measure the whole dashboard again every time.
 * Inside [run], such a request from [host]'s own content stops at [host]. When [run] ends, [host] is measured again
 * with the specs its parent last gave it. If it keeps its size, it's laid out where it is, and nothing around it
 * moves. If its size changed, the request goes on up as usual and the next pass lays everything out.
 *
 * Only what's inside [host] may change in [run]. A change to [host]'s own layout params or visibility is its parent's
 * to lay out, so it must happen outside. The host calls [onMeasure] from its own `onMeasure` and asks [take] from
 * its `requestLayout` (calling `super.requestLayout()` when [take] says no).
 */
internal class LocalRelayout(private val host: View) {
    private var widthSpec = 0
    private var heightSpec = 0
    private var measured = false
    private var depth = 0
    private var taken = false

    /** The specs [host]'s parent measured it with, last time. */
    fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        widthSpec = widthMeasureSpec
        heightSpec = heightMeasureSpec
        measured = true
    }

    /**
     * [host]'s `requestLayout`: true when the request is kept here (inside [run], with [host] on screen and laid out,
     * and no layout of the window under way or already asked for above it).
     */
    fun take(): Boolean {
        if (depth == 0 || !measured) return false
        if (!host.isAttachedToWindow || !host.isLaidOut || host.isInLayout) return false
        // A pass is already coming for the parent: it measures the host too.
        if (host.parent?.isLayoutRequested != false) return false
        taken = true
        // Flags the host (measured again below) without telling its parent.
        host.forceLayout()
        return true
    }

    /** Runs [block], which changes only what's inside [host], and lays [host] out again on its own if it asked to. */
    fun run(block: () -> Unit) {
        depth++
        try {
            block()
        } finally {
            depth--
        }
        if (depth == 0 && taken) {
            taken = false
            relayout()
        }
    }

    private fun relayout() {
        if (!host.isLayoutRequested || !host.isAttachedToWindow) return
        // Asked for since by some other way (the parent is flagged): the window's pass does it.
        if (host.parent?.isLayoutRequested != false) return
        val w = host.width
        val h = host.height
        dev.glassworks.engine.ScrollTuning.mark("Vitro local relayout")
        host.measure(widthSpec, heightSpec)
        if (host.measuredWidth == w && host.measuredHeight == h) {
            host.layout(host.left, host.top, host.right, host.bottom)
        } else {
            // Outside run: the request goes up to the window.
            host.requestLayout()
        }
    }
}
