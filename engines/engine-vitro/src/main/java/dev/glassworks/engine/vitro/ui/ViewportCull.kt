/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.glassworks.engine.ScrollTuning
import dev.glassworks.engine.vitro.cards.IframeCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import java.util.Collections
import java.util.WeakHashMap

/**
 * [ScrollTuning.Knob.CULL]: a masonry view's columns draw only the cards in a band around the viewport.
 *
 * On the S1 the render thread's `prepareTree` walks every render node the window's display lists reach, each frame:
 * on Clarity's heavy view, about 4,900 views, most of them far off screen, cost 6 to 7 ms of a scroll frame. A card the
 * column doesn't draw ([CardColumn.drawChild]) isn't in the column's display list, so the render thread never walks
 * it (and frees what it held for it: the card records itself again as it comes back, on the main thread, which has
 * time to spare).
 *
 * Nothing else changes. The card stays VISIBLE, attached and laid out: the column's height, every position, the
 * accessibility tree, `isShown` (which cards read to pause and refetch), Clarity's probes and the cameras' on-screen
 * logic are as before. Its live changes wait while it's out of the band ([CardSurface.setCulled]) and catch up before
 * it's drawn again. A card with a camera, an island or a frame of its own (a SurfaceView or a TextureView, whose
 * picture follows its view's drawing) is always drawn.
 *
 * The band is the viewport and [ScrollTuning.cullMarginPercent] of its height above and below. It's checked as the
 * view scrolls and after each layout; a column records itself again only when a card crosses the band's edge.
 */
internal class ViewportCull(private val scroll: ScrollView) {
    /** The cards (a column's children) left out now, by identity. */
    private val skipped: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** Cards always drawn (a camera, an island, a surface), as found the first time. */
    private val keep = WeakHashMap<View, Boolean>()

    private var columns: List<CardColumn>? = null

    private val updateTask = Runnable { update() }
    private var updatePosted = false

    /** Whether the column leaves [child] out of its drawing. */
    fun skips(child: View): Boolean = child in skipped

    /** The view scrolled: the band moved. */
    fun onScrolled() = update()

    /** Something under the view was laid out: the columns and the cards' places may have changed. */
    fun onLayout() {
        columns = null
        if (updatePosted) return
        updatePosted = true
        scroll.post(updateTask)
    }

    fun update() {
        updatePosted = false
        val content = scroll.getChildAt(0) ?: return
        val h = scroll.height
        if (h <= 0 || !scroll.isAttachedToWindow) return
        val margin = h * ScrollTuning.cullMarginPercent / 100
        val top = scroll.scrollY - margin
        val bottom = scroll.scrollY + h + margin
        ScrollTuning.section({ "Vitro cull" }) {
            for (col in columns(content)) {
                val y = offsetIn(col, content) ?: continue
                var changed = false
                for (i in 0 until col.childCount) {
                    val c = col.getChildAt(i)
                    val inBand = c.visibility == View.GONE || (y + c.bottom > top && y + c.top < bottom)
                    if (inBand) {
                        if (skipped.remove(c)) {
                            // Its live changes first, so it's drawn as it is now.
                            forEachCard(c) { it.setCulled(false) }
                            changed = true
                        }
                    } else if (c !in skipped && cullable(c)) {
                        skipped += c
                        forEachCard(c) { it.setCulled(true) }
                        changed = true
                    }
                }
                if (changed) {
                    ScrollTuning.mark("Vitro cull column")
                    col.invalidate()
                }
            }
        }
    }

    /** Draws every card again (the knob's effect undone, for tests). */
    fun release() {
        for (c in skipped.toList()) forEachCard(c) { it.setCulled(false) }
        skipped.clear()
        columns?.forEach { it.invalidate() }
    }

    /** The cards drawn now and left out now, counted by views under them (tests and the log). */
    fun counts(): Pair<Int, Int> {
        val content = scroll.getChildAt(0) ?: return 0 to 0
        var drawn = 0
        var left = 0
        for (col in columns(content)) {
            for (i in 0 until col.childCount) {
                val c = col.getChildAt(i)
                if (c.visibility == View.GONE) continue
                if (c in skipped) left += countViews(c) else drawn += countViews(c)
            }
        }
        return drawn to left
    }

    private fun columns(content: View): List<CardColumn> = columns ?: ArrayList<CardColumn>().also { found ->
        fun walk(v: View, depth: Int) {
            if (v is CardColumn) {
                found += v
                if (v.cull !== this) {
                    v.cull = this
                    // A card that grows, a chunk of the masonry that lands: the places in the column change.
                    v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onLayout() }
                }
                return
            }
            if (v is ViewGroup && depth < MAX_DEPTH) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(content, 0)
        columns = found
    }

    /** [v]'s top in [content]'s coordinates, or null when it isn't under it. */
    private fun offsetIn(v: View, content: View): Int? {
        var y = 0
        var p: View? = v
        while (p != null && p !== content) {
            y += p.top - (if (p !== v) p.scrollY else 0)
            p = p.parent as? View
        }
        return if (p === content) y else null
    }

    private fun cullable(c: View): Boolean = !(keep.getOrPut(c) { mustDraw(c) })

    private fun mustDraw(v: View): Boolean {
        if (v is SurfaceView || v is TextureView || v is PictureCardView || v is WebIslandCardView || v is IframeCardView) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (mustDraw(v.getChildAt(i))) return true
        return false
    }

    private fun forEachCard(v: View, action: (CardSurface) -> Unit) {
        if (v is CardSurface) action(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) forEachCard(v.getChildAt(i), action)
    }

    private fun countViews(v: View): Int = 1 + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).sumOf { countViews(g.getChildAt(it)) } } ?: 0)

    private companion object {
        /** How deep under the scroller columns are looked for (a masonry view's are 2 or 3 down). */
        const val MAX_DEPTH = 6
    }
}
