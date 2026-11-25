/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.engine.vitro.VitroFailures
import dev.glassworks.engine.vitro.cards.IslandCard

/**
 * The guard round a card that isn't a [CardSurface] (a heading, Mushroom's chips and title, a stack, layout-card,
 * auto-entities, swipe-card), wherever it's placed: a throw in its measure, layout, draw or touch handling is caught
 * here, logged once ([VitroFailures]), and the card becomes HA's error card ([errorCard]) on the next main-thread
 * message, as a [CardSurface] does. Until the swap the card is left out of measure, layout, draw and touch, so the
 * cards round it carry on. The view builds every card through it ([VitroView]'s `buildCard`); a [CardSurface] guards
 * itself and is never wrapped.
 *
 * It's transparent otherwise: it passes its parent's measure specs straight to the card and takes the card's size, so
 * the card measures as it would in the parent's own slot. A card that hides itself (auto-entities with nothing to show)
 * hides the guard too, so it takes no space and no gap; the guard's own visibility (a card's conditions) still holds.
 */
class ContainedCard(
    private val ctx: VitroCardContext,
    /** The card's type, for the log (`heading`, `custom:mushroom-chips-card`). */
    private val cardType: String?,
    /** The card itself, until it fails. */
    card: View,
    /** HA's error card for this card, made when it fails. */
    private val errorCard: () -> View,
) : FrameLayout(ctx.context) {
    /** What the card threw, once it has; null while it works. */
    var failure: Throwable? = null
        private set

    /** The error card is in the card's place. */
    private var swapped = false

    /** The visibility asked of the guard (its conditions); it shows only while the card itself isn't GONE as well. */
    private var requestedVisibility = VISIBLE
    private var contentGone = false

    /** Constructed: [requestLayout] runs from View's constructor too, before the fields above are set. */
    private var ready = false

    init {
        // CSS `overflow: visible`, as the grid: what the card paints past its box is clipped where it was before.
        clipChildren = false
        clipToPadding = false
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentGone = card.visibility == GONE
        applyVisibility()
        ready = true
    }

    /** The card shown now: the card itself, or the error card once it failed. */
    val content: View? get() = if (childCount > 0) getChildAt(0) else null

    /** A throw at [where]: the card's ([fail]), or, past the swap, the error card's own (logged; never the app's). */
    private fun caught(where: String, error: Exception) {
        if (swapped) VitroFailures.report(where, ErrorCardView.TYPE, error) else fail(where, error)
    }

    private fun fail(where: String, error: Exception) {
        if (failure != null) return
        failure = error
        VitroFailures.report(where, cardType, error)
        ctx.main.post { if (!swapped) runCatching { showErrorCard() } }
    }

    /** Puts HA's error card in place of the card. The card leaves the window, and its islands close with it. */
    internal fun showErrorCard() {
        swapped = true
        val old = content
        removeAllViews()
        old?.let { releaseIslands(it) }
        val error = try {
            errorCard()
        } catch (e: Exception) {
            VitroFailures.report("build", ErrorCardView.TYPE, e)
            View(context)
        }
        addView(error, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentGone = false
        applyVisibility()
        requestLayout()
        invalidate()
    }

    private fun releaseIslands(v: View) {
        if (v is IslandCard) {
            runCatching { v.release() }
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) releaseIslands(v.getChildAt(i))
        }
    }

    /** The card is failing and the error card isn't in yet: nothing of the card's runs. */
    private val waiting: Boolean get() = failure != null && !swapped

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = content
        if (!waiting && child != null) {
            try {
                child.measure(widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(child.measuredWidthAndState, child.measuredHeightAndState)
                return
            } catch (e: Exception) {
                caught("measure", e)
            }
        }
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(ctx.px(CardSurface.ERROR_CARD_MIN_DP), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = content ?: return
        if (waiting) return
        try {
            child.layout(0, 0, right - left, bottom - top)
        } catch (e: Exception) {
            caught("layout", e)
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (waiting) return false
        val saved = canvas.saveCount
        return try {
            super.drawChild(canvas, child, drawingTime)
        } catch (e: Exception) {
            canvas.restoreToCount(saved)
            caught("draw", e)
            false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (waiting) return true
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: Exception) {
            caught("touch", e)
            true
        }
    }

    /** The card's baseline, so a stack that aligns its cards' baselines places this one as it did unguarded. */
    override fun getBaseline(): Int {
        val child = content ?: return -1
        if (waiting) return -1
        val b = try {
            child.baseline
        } catch (e: Exception) {
            caught("measure", e)
            -1
        }
        return if (b < 0) -1 else child.top + b
    }

    override fun setVisibility(visibility: Int) {
        requestedVisibility = visibility
        applyVisibility()
    }

    /**
     * A card that goes GONE or comes back asks for a layout ([View.setVisibility]), which reaches the guard: it follows
     * the card there.
     */
    override fun requestLayout() {
        if (ready) {
            val gone = content?.visibility == GONE
            if (gone != contentGone) {
                contentGone = gone
                applyVisibility()
            }
        }
        super.requestLayout()
    }

    /**
     * Never "already asked" while the guard is hidden for its card: a GONE view keeps its pending layout, and the card
     * coming back would then never reach [requestLayout].
     */
    override fun isLayoutRequested(): Boolean = super.isLayoutRequested() && !contentGone

    private fun applyVisibility() {
        super.setVisibility(if (contentGone) GONE else requestedVisibility)
    }
}
