/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import dev.glassworks.engine.vitro.cards.HaRipple
import dev.glassworks.platform.Platform
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Whether a card's content can draw into its rounded corners ([contentReachesCorners]), so the card needs
 * `clipToOutline` to keep it inside them ([dev.glassworks.engine.ScrollTuning.Knob.CLIP]).
 *
 * Without hardware layers (most of a long view's cards, once the layer budget is spent), a card that clips to its
 * outline has every draw inside it clipped to an anti-aliased rounded rect on each frame. Most cards keep their content
 * inside their padding, far from the corners, and need no clip: a tile's ripple is rounded like the card, an entities
 * card's rows sit 16 px in. A picture, a camera, a graph footer along the bottom edge, a card_mod `padding: 0`, a
 * child of unknown drawing at a corner keep the clip.
 *
 * The test is geometric and conservative. A view whose bounds stay inside the card's rounded rect is fine, whatever it
 * draws (a view's drawing is clipped to its bounds). A view that reaches a corner is fine only if it's a plain
 * container that draws nothing of its own there (no background or foreground outside the card's rounding, no `onDraw`
 * or `dispatchDraw` of its own, no transform or shadow) and all its children pass. Anything else keeps the clip.
 */
internal object OutlineClip {
    /** How far inside the card's arc content must stay, px: the clip's anti-aliased edge is about a pixel wide. */
    private const val MARGIN = 1f

    /** Arc samples per corner when a rounded shape is tested (the arc bulges at most ~0.1 px between two at 18 px). */
    private const val ARC_STEPS = 8

    private val drawsOwnCache = HashMap<Class<*>, Boolean>()

    // ---- the log: how many cards clip, and why, a few seconds after the decisions last changed. Main thread.

    private val decisions = java.util.WeakHashMap<CardSurface, String>()
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var logPosted = false
    private const val NO_CLIP = "-"
    private val logTask = Runnable {
        logPosted = false
        val all = decisions.entries.map { it.key.cardType to it.value }
        val clipped = all.filter { it.second != NO_CLIP }
        val reasons = clipped.groupingBy { (type, why) -> "$type: $why" }.eachCount().entries
            .sortedByDescending { it.value }.joinToString("; ") { "${it.key} x${it.value}" }
        android.util.Log.i(
            dev.glassworks.engine.ScrollTuning.TAG,
            "outline clip: ${clipped.size} of ${all.size} rounded cards clip" + if (reasons.isEmpty()) "" else " ($reasons)",
        )
    }

    /** Notes [card]'s decision ([reason], null: no clip) for the log, which follows once they settle. */
    fun record(card: CardSurface, reason: String?) {
        val value = reason ?: NO_CLIP
        if (decisions.put(card, value) == value) return
        dev.glassworks.engine.ScrollTuning.mark("Vitro outline clip " + card.cardType + " " + value)
        if (logPosted) main.removeCallbacks(logTask)
        logPosted = true
        main.postDelayed(logTask, LOG_AFTER_MS)
    }

    private const val LOG_AFTER_MS = 3_000L

    /**
     * Why [card] needs its outline clip (for the log), or null when nothing it draws reaches beyond its rounded corners.
     * Its outline must be its background's, a rounded GradientDrawable whose radius can be read (Android 7 and later);
     * anything else keeps the clip.
     */
    fun needsClip(card: CardSurface): String? {
        if (card.outlineProvider !== ViewOutlineProvider.BACKGROUND) return "outline provider"
        val bg = card.background ?: return "no background"
        if (bg !is GradientDrawable || !Platform.hasGradientDrawableGetters) return "background " + bg.javaClass.simpleName
        if (bg.shape != GradientDrawable.RECTANGLE || bg.cornerRadii != null) return "background shape"
        return contentReachesCorners(card, bg.cornerRadius)
    }

    /**
     * Why [card]'s content needs the clip (the first view found at a corner, for the log), or null when nothing it
     * draws reaches beyond its rounded outline of [radius] px.
     */
    fun contentReachesCorners(card: CardSurface, radius: Float): String? {
        val w = card.width.toFloat()
        val h = card.height.toFloat()
        if (w <= 0f || h <= 0f) return "unlaid"
        // GradientDrawable rounds no more than half the shorter side, its outline too.
        val box = Box(w, h, min(radius, min(w, h) / 2f))
        if (overridesDrawing(card.javaClass, CardSurface::class.java)) return "card draws"
        card.foreground?.let { fg -> if (!drawableInside(fg, 0f, 0f, w, h, box)) return "card foreground" }
        for (i in 0 until card.childCount) {
            val reason = viewInside(card.getChildAt(i), 0f, 0f, box)
            if (reason != null) return reason
        }
        return null
    }

    /** The card's rounded rect: [w] x [h] with corners of [r], less [MARGIN] on the arcs. */
    private class Box(val w: Float, val h: Float, val r: Float) {
        /** A hard corner (a rect's) stays [MARGIN] inside the arc; a rounded edge, anti-aliased like the clip, may meet it. */
        private val hardArc = (r - MARGIN).coerceAtLeast(0f)
        private val softArc = r + EPS

        /** The point is inside the rounded rect: within [hardArc] of the corner's center ([soft]: [softArc]). */
        fun contains(x: Float, y: Float, soft: Boolean = false): Boolean {
            if (x < -EPS || y < -EPS || x > w + EPS || y > h + EPS) return false
            if (r <= 0f) return true
            val dx = when {
                x < r -> r - x
                x > w - r -> x - (w - r)
                else -> return true
            }
            val dy = when {
                y < r -> r - y
                y > h - r -> y - (h - r)
                else -> return true
            }
            val arc = if (soft) softArc else hardArc
            return dx * dx + dy * dy <= arc * arc
        }

        /** A rect inside the bounds that doesn't reach into a corner's square: no arc to test. */
        fun clearOfCorners(l: Float, t: Float, rr: Float, b: Float): Boolean =
            (l >= r || t >= r) && (rr <= w - r || t >= r) && (l >= r || b <= h - r) && (rr <= w - r || b <= h - r)

        /** The rect (l, t, r, b) with corners of [rc] (clamped as GradientDrawable clamps it) is inside. */
        fun containsRoundRect(l: Float, t: Float, rr: Float, b: Float, rc: Float): Boolean {
            if (l < -EPS || t < -EPS || rr > w + EPS || b > h + EPS) return false
            if (clearOfCorners(l, t, rr, b)) return true
            val k = min(rc, min(rr - l, b - t) / 2f).coerceAtLeast(0f)
            if (k <= 0f) return contains(l, t) && contains(rr, t) && contains(l, b) && contains(rr, b)
            // Each corner's arc, from its center toward the corner it rounds.
            val corners = arrayOf(
                floatArrayOf(l + k, t + k, -1f, -1f), floatArrayOf(rr - k, t + k, 1f, -1f),
                floatArrayOf(l + k, b - k, -1f, 1f), floatArrayOf(rr - k, b - k, 1f, 1f),
            )
            for (c in corners) {
                for (s in 0..ARC_STEPS) {
                    val a = (Math.PI / 2) * s / ARC_STEPS
                    if (!contains(c[0] + c[2] * k * cos(a).toFloat(), c[1] + c[3] * k * sin(a).toFloat(), soft = true)) return false
                }
            }
            return true
        }
    }

    /** Null when [v] (at [ox], [oy] in the card) draws nothing outside the card's rounded rect; else why. */
    private fun viewInside(v: View, ox: Float, oy: Float, box: Box): String? {
        // Gone takes a layout to come back, which draws the card again. Invisible doesn't: it counts as shown.
        if (v.visibility == View.GONE) return null
        val l = ox + v.left
        val t = oy + v.top
        val r = ox + v.right
        val b = oy + v.bottom
        // A view's own drawing is clipped to its bounds: inside the rounded rect, it's fine whatever it draws.
        // Unless it's moved, scaled or casts a shadow, which reach beyond its bounds.
        if (!plainTransform(v)) return "transform " + v.javaClass.simpleName
        if (v !is ViewGroup || v.clipChildren) {
            if (box.containsRoundRect(l, t, r, b, 0f)) return null
        }
        // A view that keeps all it draws inside its own rounded outline: inside if that is.
        roundOutline(v)?.let { rc -> if (box.containsRoundRect(l, t, r, b, rc)) return null }
        if (v.javaClass == View::class.java) {
            // A spacer: only its background and foreground draw.
            return if (drawablesInside(v, l, t, r, b, box)) null else "spacer " + v.javaClass.simpleName
        }
        if (v !is ViewGroup) return "leaf " + v.javaClass.simpleName
        if (!plainContainer(v)) return "container " + v.javaClass.simpleName
        if (!drawablesInside(v, l, t, r, b, box)) return "drawable " + v.javaClass.simpleName
        val cx = l - v.scrollX
        val cy = t - v.scrollY
        for (i in 0 until v.childCount) {
            val reason = viewInside(v.getChildAt(i), cx, cy, box)
            if (reason != null) return reason
        }
        return null
    }

    private fun plainTransform(v: View): Boolean =
        v.translationX == 0f && v.translationY == 0f && v.scaleX == 1f && v.scaleY == 1f && v.rotation == 0f &&
            v.rotationX == 0f && v.rotationY == 0f && v.elevation == 0f && v.translationZ == 0f

    /**
     * The radius of the rounded rect [v] clips itself to (clipToOutline on its background's outline, a rounded
     * GradientDrawable), or null when it doesn't.
     */
    private fun roundOutline(v: View): Float? {
        if (!v.clipToOutline || v.outlineProvider !== ViewOutlineProvider.BACKGROUND) return null
        return gradientRadius(v.background ?: return null, v.width.toFloat(), v.height.toFloat())
    }

    /** A plain container: its own drawing is only its background and foreground, its children the rest. */
    private fun plainContainer(v: ViewGroup): Boolean {
        if (!v.willNotDraw()) return false
        // The framework part must be a plain layout (not a scroller with edge effects, not a list), the app's part
        // must not draw anything itself.
        var c: Class<*> = v.javaClass
        while (!c.name.startsWith("android.")) c = c.superclass ?: return false
        if (c != FrameLayout::class.java && c != LinearLayout::class.java && c != RelativeLayout::class.java &&
            c != ViewGroup::class.java
        ) {
            return false
        }
        if (v is LinearLayout && v.dividerDrawable != null) return false
        return !overridesDrawing(v.javaClass, c)
    }

    private fun drawablesInside(v: View, l: Float, t: Float, r: Float, b: Float, box: Box): Boolean {
        v.background?.let { if (!drawableInside(it, l, t, r, b, box)) return false }
        v.foreground?.let { if (!drawableInside(it, l, t, r, b, box)) return false }
        return true
    }

    /** [d], drawn over the view's bounds (l, t, r, b), stays inside the card's rounded rect. */
    private fun drawableInside(d: Drawable, l: Float, t: Float, r: Float, b: Float, box: Box): Boolean {
        val rc = when (d) {
            is ColorDrawable -> if (d.alpha == 0) return true else 0f
            // The ripple is masked to a rounded rect of its radius.
            is HaRipple -> d.cornerRadiusPx
            is GradientDrawable -> gradientRadius(d, r - l, b - t) ?: 0f
            // A ripple without a mask may draw on an ancestor (projection), outside the view.
            is RippleDrawable -> return false
            // Others draw inside their bounds: as the whole rect.
            else -> 0f
        }
        return box.containsRoundRect(l, t, r, b, rc)
    }

    /** A GradientDrawable's uniform corner radius over a [w] x [h] rect (an oval as a pill), or null when unknown. */
    private fun gradientRadius(d: Drawable, w: Float, h: Float): Float? {
        if (d !is GradientDrawable || !Platform.hasGradientDrawableGetters) return null
        return when (d.shape) {
            GradientDrawable.OVAL -> min(w, h) / 2f
            GradientDrawable.RECTANGLE -> if (d.cornerRadii == null) d.cornerRadius else null
            else -> null
        }
    }

    /** [cls] or a class between it and [stop] declares its own draw (onDraw, dispatchDraw, draw, onDrawForeground). */
    private fun overridesDrawing(cls: Class<*>, stop: Class<*>): Boolean = drawsOwnCache.getOrPut(cls) {
        var c: Class<*>? = cls
        var draws = false
        while (c != null && c != stop && !c.name.startsWith("android.")) {
            if (c.declaredMethods.any { m ->
                    m.name in DRAW_METHODS && m.parameterTypes.size == 1 && m.parameterTypes[0] == Canvas::class.java
                }
            ) {
                draws = true
                break
            }
            c = c.superclass
        }
        draws
    }

    private val DRAW_METHODS = setOf("onDraw", "dispatchDraw", "draw", "onDrawForeground")

    private const val EPS = 0.01f
}
