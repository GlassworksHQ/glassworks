/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.ui

import android.os.Handler
import android.view.View
import android.widget.TextView
import dev.glassworks.engine.vitro.data.EntityState
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * A state shown where HA's card draws a timestamp with `hui-timestamp-display` (frontend 20261028.5): HA's
 * `state-display` (the tile, view badges, heading entities), the sensor and simple entity rows and the glance card.
 * The display writes a timestamp state relative to now ("In 3 hours", "5 minutes ago") and redraws it each second, so
 * a text shown here does too, while its view is on screen. It is Vitro's one ticker for text that changes on its own:
 * the tile's and heading badges' `state-display` (a running timer too) and an entities row's relative secondary line
 * go through [show] with their own relative form.
 */
internal object TimestampText {
    /**
     * The ticker of each text that shows a timestamp now. A ticker holds its text, so the map holds tickers weakly: a
     * strong value would keep its weak key alive. The text's attach listener holds the ticker while it ticks.
     */
    private val tickers = WeakHashMap<View, WeakReference<Ticker>>()

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
    ) = show(view, s, otherwise, now, current, main, if (anyDomain) ANY_DOMAIN else ROWS)

    /**
     * [show] with the card's own [relative] form: the text it draws for a state at a time, or null when it draws
     * [otherwise] instead. Use the same [relative] (instance) for a view each time, so its ticker carries on.
     */
    fun show(
        view: TextView,
        s: EntityState?,
        otherwise: String,
        now: () -> Long,
        current: (String) -> EntityState?,
        main: Handler,
        relative: (EntityState, Long) -> String?,
    ) = show(view, { view.text }, { view.text = it }, s, otherwise, now, current, main, relative)

    /**
     * [show] for a text a view draws itself ([view] holds one such text, read by [get] and set by [set]): the entities
     * card's row, which draws its state without a TextView. [view] keys its ticker, and its attachment runs it.
     */
    fun show(
        view: View,
        get: () -> CharSequence?,
        set: (CharSequence) -> Unit,
        s: EntityState?,
        otherwise: String,
        now: () -> Long,
        current: (String) -> EntityState?,
        main: Handler,
        relative: (EntityState, Long) -> String?,
    ) {
        val ts = s?.let { relative(it, now()) }
        val text = ts ?: otherwise
        if (get()?.toString() != text) set(text)
        val old = tickers[view]?.get()
        if (ts == null || s == null) {
            old?.stop()
            tickers.remove(view)
            return
        }
        if (old != null && old.entityId == s.entityId && old.relative === relative) return
        old?.stop()
        tickers[view] = WeakReference(Ticker(view, get, set, s.entityId, relative, now, current, main).also { it.start() })
    }

    /** A row's own state text ([show] with a getter and setter): as `hui-timestamp-display` draws a timestamp, else [otherwise]. */
    fun showRowState(
        view: View,
        get: () -> CharSequence?,
        set: (CharSequence) -> Unit,
        s: EntityState?,
        otherwise: String,
        now: () -> Long,
        current: (String) -> EntityState?,
        main: Handler,
    ) = show(view, get, set, s, otherwise, now, current, main, ROWS)

    private val ROWS: (EntityState, Long) -> String? = { s, now -> s.timestampDisplay(now) }
    private val ANY_DOMAIN: (EntityState, Long) -> String? = { s, now -> s.timestampDisplay(now, anyDomain = true) }

    /**
     * Mushroom 5.3.0's `state` info (`computeInfoDisplay`): `ha-relative-time` for a `timestamp` sensor and a button,
     * input button or scene that is available and known ("In 3 hours", "2 minutes ago"), else the card's own line.
     */
    val MUSHROOM: (EntityState, Long) -> String? = { s, now ->
        if ((s.deviceClass == "timestamp" || s.domain in MUSHROOM_DOMAINS) && !s.isUnavailable) {
            dev.glassworks.engine.vitro.data.jsDateMs(s.state)?.let {
                dev.glassworks.engine.vitro.data.HaFormat.capitalize(dev.glassworks.engine.vitro.data.HaFormat.relativeTime(it, now))
            }
        } else {
            null
        }
    }
    private val MUSHROOM_DOMAINS = setOf("button", "input_button", "scene")

    /** Whether [view] keeps a relative time current (for tests). */
    fun isTicking(view: View): Boolean = tickers[view]?.get() != null

    private class Ticker(
        val view: View,
        val get: () -> CharSequence?,
        val set: (CharSequence) -> Unit,
        val entityId: String,
        val relative: (EntityState, Long) -> String?,
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
            // A text in a hidden cached view (or a hidden row) isn't set: a set would lay the shown view out again.
            // It counts on from the next second it's shown.
            if (view.isShown) current(entityId)?.let { relative(it, now()) }?.let { if (get()?.toString() != it) set(it) }
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

/** [TimestampText.show] with a card's own relative form ([TimestampText.MUSHROOM]). */
internal fun VitroCardContext.showRelativeOr(view: TextView, s: EntityState?, otherwise: String, relative: (EntityState, Long) -> String?) =
    TimestampText.show(view, s, otherwise, now, { store[it] }, main, relative)
