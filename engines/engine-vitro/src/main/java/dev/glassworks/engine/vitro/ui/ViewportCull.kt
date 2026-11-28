/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.glassworks.engine.vitro.cards.IframeCardView
import dev.glassworks.engine.vitro.cards.PictureCardView
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import java.util.Collections
import java.util.WeakHashMap

/**
 * A masonry view's columns draw only the cards in a band around the viewport ([ScrollPath.cull]).
 *
 * On the S1 the render thread's `prepareTree` walks every render node the window's display lists reach, each frame:
 * on Clarity's heavy view (about 2,000 views, most of them far off screen) that was 6.6 ms of a scroll frame, and
 * 1.4 ms with culling (an S1 trace, 2026-11-28). A card the column doesn't draw ([CardColumn.drawChild]) isn't in the
 * column's display list, so the render thread never walks it, and frees what it held for it: the card records itself
 * again as it comes back, on the main thread (0.5 to 0.8 ms a card on the S1), which has time to spare.
 *
 * Nothing else changes. The card stays VISIBLE, attached and laid out: the column's height, every position, the
 * accessibility tree, `isShown` (which cards read to pause and refetch), Clarity's probes and the cameras' on-screen
 * logic are as before. Its live changes wait while it's out of the band ([CardSurface.setCulled]) and catch up before
 * it's drawn again. A card with a camera, an island or a frame of its own (a SurfaceView or a TextureView, whose
 * picture follows its view's drawing) is always drawn.
 *
 * The band is the viewport and [MARGIN_PERCENT] of its height above and below. It's checked as the view scrolls and
 * after each layout; a column records itself again only when a card crosses the band's edge, so a view at rest costs
 * nothing.
 */
internal class ViewportCull(private val scroll: ScrollView) {
    /**
     * A masonry view's scroller, holding its culling: no registry elsewhere, so a scroller dropped with its view (a
     * rebuild, another dashboard) takes its culling and card tree with it.
     */
    internal class Scroller(context: android.content.Context) : ScrollView(context) {
        var cull: ViewportCull? = null
    }

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
        val margin = h * MARGIN_PERCENT / 100
        val top = scroll.scrollY - margin
        val bottom = scroll.scrollY + h + margin
        android.os.Trace.beginSection("Vitro cull")
        try {
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
                    android.os.Trace.beginSection("Vitro cull column")
                    col.invalidate()
                    android.os.Trace.endSection()
                }
            }
        } finally {
            android.os.Trace.endSection()
        }
    }

    /**
     * [card] (or a card inside it) replaced what it holds, as an auto-entities card does when its matches change: what
     * it holds now may have to be drawn always (a camera, an island). A column's card left out is drawn again then;
     * one still left out has its new cards' live changes wait, as the old ones did.
     */
    fun contentChanged(card: View) {
        var child: View = card
        var parent = card.parent
        while (parent is View && parent !is CardColumn) {
            child = parent
            parent = parent.parent
        }
        val col = parent as? CardColumn ?: return
        if (col.cull !== this) return
        keep.remove(child)
        if (child !in skipped) return
        if (cullable(child)) {
            forEachCard(child) { it.setCulled(true) }
        } else {
            skipped.remove(child)
            forEachCard(child) { it.setCulled(false) }
            col.invalidate()
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

    companion object {
        /**
         * [card] replaced its content ([contentChanged]): tells the culling of the masonry view it's in, if any.
         * Main thread.
         */
        fun contentChanged(card: View) {
            var p = card.parent
            while (p is View) {
                if (p is CardColumn) {
                    p.cull?.contentChanged(card)
                    return
                }
                p = p.parent
            }
        }

        /** How deep under the scroller columns are looked for (a masonry view's are 2 or 3 down). */
        private const val MAX_DEPTH = 6

        /**
         * The band above and below the viewport, percent of its height: cards come back early enough to have caught
         * up and recorded before they show, on the S1's fastest flings.
         */
        private const val MARGIN_PERCENT = 50
    }
}
