/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.engine.vitro.data.HaText
import dev.glassworks.engine.vitro.theme.ResolvedTheme
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.ui.Inter

/**
 * A toast as HA's `showToast` asks for one (`ShowToastParams`): [message], an optional [action] button (its label and
 * what it does), how long it stays ([durationMs]: HA's default and least is 4 s; 0 closes the toast with the same [id],
 * or the newest one without an id), an [id] that replaces a toast already showing instead of adding one, and a close
 * button when [dismissable]. [onDismiss] runs when the close button closes it.
 */
class HaToast(
    val message: String,
    val action: Action? = null,
    val durationMs: Long? = null,
    val id: String? = null,
    val dismissable: Boolean = false,
    val announce: String? = null,
    val onDismiss: (() -> Unit)? = null,
) {
    class Action(val text: String, val run: () -> Unit)
}

/**
 * HA's `notification-manager` and `ha-toast` (frontend 20261028.5): toasts stacked at the bottom of the window, 16 px
 * up and centered, 8 px apart, the newest last. Each is a dark bar (`--ha-color-neutral-10`, white text, 4 px corners,
 * `--wa-shadow-l`) at least 48 px tall with 12 px by 16 px of padding, between 350 and 650 px wide (the window's width
 * and square on a window at most 450 wide or 500 tall), with its plain action button and close button after the
 * message. It fades and rises 8 px in over 150 ms, stays [HaToast.durationMs], and goes the same way. TalkBack reads it
 * as HA's `role="status"` live region is read: politely, as it appears.
 *
 * The host is a full-window layer that takes no touches but its toasts'. Main thread only.
 */
class HaToastHost(context: Context, private val theme: () -> ResolvedTheme) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val stack = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private var anonymous = 0

    /** One toast on screen: its key (`identified-<id>` or `anonymous-<n>`), view, params and timer. */
    private inner class Shown(val key: String, var params: HaToast, val view: ToastView) {
        var closing = false
        val timeout = Runnable { hide(this, dismissed = false) }
    }

    private val shown = ArrayList<Shown>()

    init {
        clipChildren = false
        // A layer over the dashboard and any sheet: it has no touch of its own (the toasts take theirs).
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(stack, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = px(16f)
        })
    }

    /** The toasts showing, oldest first: their messages (tests, Clarity). */
    val messages: List<String> get() = shown.filter { !it.closing }.map { it.params.message }

    /** `showDialog`: shows [toast], replaces the one with its [HaToast.id], or (duration 0) closes one. */
    fun show(toast: HaToast) {
        if (toast.durationMs == 0L) {
            val target = if (toast.id != null) shown.firstOrNull { it.key == "identified-${toast.id}" }
            else shown.lastOrNull { it.params.id == null }
            target?.let { hide(it, dismissed = false) }
            return
        }
        val key = toast.id?.let { "identified-$it" } ?: "anonymous-${++anonymous}"
        val existing = shown.firstOrNull { it.key == key && !it.closing }
        // Re-entered above whatever opened since (a sheet), as HA's stack re-enters the top layer.
        bringToFront()
        if (existing != null) {
            existing.params = toast
            existing.view.bind(toast)
            arm(existing)
            existing.view.announce(toast)
            return
        }
        val view = ToastView(context, toast)
        val entry = Shown(key, toast, view)
        shown += entry
        view.onAction = { run ->
            hide(entry, dismissed = false)
            run()
        }
        view.onClose = { hide(entry, dismissed = true) }
        stack.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (stack.childCount > 0) topMargin = px(8f)
        })
        view.alpha = 0f
        view.translationY = 8f * dp
        view.animate().alpha(1f).translationY(0f).setDuration(ANIMATION_MS).setInterpolator(EASE).withEndAction {
            if (!entry.closing) arm(entry)
        }.start()
        view.announce(toast)
    }

    /** Closes every toast now (the view goes away). */
    fun clear() {
        shown.forEach { main.removeCallbacks(it.timeout); it.view.animate().cancel() }
        shown.clear()
        stack.removeAllViews()
    }

    private fun hide(entry: Shown, dismissed: Boolean) {
        if (entry.closing) return
        entry.closing = true
        main.removeCallbacks(entry.timeout)
        if (dismissed) entry.params.onDismiss?.invoke()
        entry.view.animate().cancel()
        entry.view.animate().alpha(0f).translationY(8f * dp).setDuration(ANIMATION_MS).setInterpolator(EASE).withEndAction {
            shown.remove(entry)
            stack.removeView(entry.view)
            // The next toast up is the first now: no gap over it.
            (stack.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != 0) { lp.topMargin = 0; stack.getChildAt(0).layoutParams = lp }
            }
        }.start()
    }

    /** HA's normalized duration: unset or up to 4 s is 4 s; a negative one stays until it's closed. */
    private fun duration(t: HaToast): Long? {
        val d = t.durationMs ?: return MIN_DURATION_MS
        return when {
            d < 0 -> null
            d <= MIN_DURATION_MS -> MIN_DURATION_MS
            else -> d
        }
    }

    private fun arm(entry: Shown) {
        main.removeCallbacks(entry.timeout)
        duration(entry.params)?.let { main.postDelayed(entry.timeout, it) }
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }

    private fun px(css: Float): Int = Math.round(css * dp)

    /** One `ha-toast`. */
    private inner class ToastView(context: Context, toast: HaToast) : LinearLayout(context) {
        var onAction: ((() -> Unit) -> Unit)? = null
        var onClose: (() -> Unit)? = null
        private val messageView = TextView(context)
        private val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48f)
            setPadding(px(16f), px(12f), px(16f), px(12f))
            isClickable = true // its own taps stay on it
            elevation = 8f * dp
            val t = theme()
            messageView.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f * t.fontScale)
                setTextColor(onNeutralLoud(t))
                typeface = VitroFonts.typeface(context, Inter.REGULAR, 14f)
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                ellipsize = null
                // role="status", aria-live="polite": TalkBack reads it when it changes.
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(messageView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actions, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) })
            bind(toast)
        }

        fun bind(toast: HaToast) {
            val t = theme()
            val narrow = narrowWindow()
            background = GradientDrawable().apply {
                setColor(ThemeResolver.parseColor(t.vars["ha-color-neutral-10"]) ?: NEUTRAL_10)
                cornerRadius = if (narrow) 0f else 4f * dp
            }
            messageView.text = toast.message
            actions.removeAllViews()
            toast.action?.let { a ->
                // ha-button appearance="plain" size="s": 32 px tall, 12 px sides, 14 px medium in the brand's color.
                val button = TextView(context).apply {
                    text = a.text
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f * t.fontScale)
                    setTextColor(ThemeResolver.parseColor(t.vars["ha-color-on-primary-normal"]) ?: if (t.dark) PRIMARY_60 else PRIMARY_40)
                    typeface = VitroFonts.typeface(context, Inter.MEDIUM, 14f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(px(12f), 0, px(12f), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onAction?.invoke(a.run) }
                }
                dev.glassworks.engine.vitro.cards.applyHaRipple(button, 999f, true)
                VitroAccess.role(button, A11yRole.BUTTON, a.text)
                actions.addView(button, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32f)))
            }
            if (toast.dismissable) {
                val close = FrameLayout(context).apply {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClose?.invoke() }
                    addView(MdiIconView(context).apply { setIcon("mdi:close", onNeutralLoud(t)) },
                        FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
                }
                dev.glassworks.engine.vitro.cards.applyHaRipple(close, 999f, true)
                VitroAccess.role(close, A11yRole.BUTTON, HaText.ui("ui.common.close"))
                actions.addView(close, LayoutParams(px(48f), px(48f)).apply { if (actions.childCount > 0) marginStart = px(8f) })
            }
            actions.visibility = if (actions.childCount > 0) VISIBLE else GONE
        }

        /** HA's live region speaks the message (or its `announceMessage`) when the toast shows. */
        fun announce(toast: HaToast) {
            announceForAccessibility(toast.announce ?: toast.message)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // `width: max-content` between `min(350px, 100vw - 16px)` and `min(650px, 100vw)`; the window's width on a
            // narrow one.
            val windowPx = (this@HaToastHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
            val maxW = if (narrowWindow()) windowPx else minOf(px(650f), windowPx)
            val minW = if (narrowWindow()) windowPx else minOf(px(350f), windowPx - px(16f))
            super.onMeasure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (measuredWidth < minW) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(minW, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }
    }

    /** `(max-width: 450px), (max-height: 500px)`. */
    private fun narrowWindow(): Boolean {
        val c = resources.configuration
        return c.screenWidthDp <= 450 || c.screenHeightDp <= 500
    }

    private fun onNeutralLoud(t: ResolvedTheme): Int = ThemeResolver.parseColor(t.vars["ha-color-on-neutral-loud"]) ?: WHITE

    companion object {
        const val MIN_DURATION_MS = 4_000L
        const val ANIMATION_MS = 150L
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        private const val NEUTRAL_10 = 0xFF202020.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val PRIMARY_40 = 0xFF009AC7.toInt()
        private const val PRIMARY_60 = 0xFF37C8FD.toInt()
    }
}
