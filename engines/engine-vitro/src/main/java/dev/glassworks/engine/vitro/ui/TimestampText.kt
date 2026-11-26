/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.os.Handler
import android.view.View
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import java.util.WeakHashMap

/**
 * A state shown where HA's card draws a timestamp with `hui-timestamp-display` (frontend 20261028.5): HA's
 * `state-display` (the tile, view badges, heading entities), the sensor and simple entity rows and the glance card.
 * The display writes a timestamp state relative to now ("In 3 hours", "5 minutes ago") and redraws it each second, so
 * a text shown here does too, while its view is on screen.
 */
internal object TimestampText {
    /** The ticker of each text that shows a timestamp now. */
    private val tickers = WeakHashMap<TextView, Ticker>()

    /**
     * Shows [s]'s state in [view]: as `hui-timestamp-display` draws it when it's a timestamp
     * ([EntityState.timestampDisplay]), kept current each second, else [otherwise]. [anyDomain] is `state-display`'s
     * test, which takes a timestamp `device_class` on an entity of any domain. [current] reads the entity's latest state.
     */
    fun show(
        view: TextView,
        s: EntityState?,
        otherwise: String,
        now: () -> Long,
        current: (String) -> EntityState?,
        main: Handler,
        anyDomain: Boolean = false,
    ) {
        val ts = s?.timestampDisplay(now(), anyDomain = anyDomain)
        val text = ts ?: otherwise
        if (view.text?.toString() != text) view.text = text
        val old = tickers[view]
        if (ts == null || s == null) {
            old?.stop()
            tickers.remove(view)
            return
        }
        if (old != null && old.entityId == s.entityId && old.anyDomain == anyDomain) return
        old?.stop()
        tickers[view] = Ticker(view, s.entityId, anyDomain, now, current, main).also { it.start() }
    }

    /** Whether [view] keeps a relative time current (for tests). */
    fun isTicking(view: TextView): Boolean = tickers[view] != null

    private class Ticker(
        val view: TextView,
        val entityId: String,
        val anyDomain: Boolean,
        val now: () -> Long,
        val current: (String) -> EntityState?,
        val main: Handler,
    ) : Runnable, View.OnAttachStateChangeListener {
        fun start() {
            view.addOnAttachStateChangeListener(this)
            if (view.isAttachedToWindow) main.postDelayed(this, 1_000L)
        }

        fun stop() {
            main.removeCallbacks(this)
            view.removeOnAttachStateChangeListener(this)
        }

        override fun run() {
            if (!view.isAttachedToWindow) return
            current(entityId)?.timestampDisplay(now(), anyDomain = anyDomain)?.let { if (view.text?.toString() != it) view.text = it }
            main.postDelayed(this, 1_000L)
        }

        override fun onViewAttachedToWindow(v: View) {
            main.removeCallbacks(this)
            main.postDelayed(this, 1_000L)
        }

        override fun onViewDetachedFromWindow(v: View) {
            main.removeCallbacks(this)
        }
    }
}

/** [TimestampText.show] for a card: the card's clock, store and main thread. */
internal fun VitroCardContext.showTimestampOr(view: TextView, s: EntityState?, otherwise: String, anyDomain: Boolean = false) =
    TimestampText.show(view, s, otherwise, now, { store[it] }, main, anyDomain)
