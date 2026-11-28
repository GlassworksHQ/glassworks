/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.ViewConfig
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes every card of a freshly parsed dashboard on a background thread, the view about to open first, so the UI
 * thread building it finds each answer ready. It computes the same lazy values the build reads ([CardConfig.nativeOnly],
 * [CardConfig.compat], and each view's [ViewConfig.webFallbackReason]): a card is never checked twice, and its answer
 * can't differ ([CardCompat.check] and [WebFallback.viewReason] read the config alone, and the caches are synchronized).
 * A card or view the build reaches first is checked there, and this thread waits for it.
 */
object CompatWarmup {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vitro-compat").apply { isDaemon = true } }
    private val generation = AtomicInteger()

    /** Starts routing [config]'s cards, [firstViewPath]'s first; a later call stops this one between cards. */
    fun start(config: DashboardConfig, firstViewPath: String?): Future<*> {
        val gen = generation.incrementAndGet()
        return executor.submit { warm(config, firstViewPath) { generation.get() != gen } }
    }

    /** What [start] runs: every card's routing, the view [firstViewPath] opens on first (as the view resolves it). */
    internal fun warm(config: DashboardConfig, firstViewPath: String?, cancelled: () -> Boolean = { false }) {
        val views = config.views
        // The renderer's own choice: a null or blank path is the first view, never one without a path.
        val first = config.viewIndex(firstViewPath)
        for (i in listOf(first) + views.indices.filter { it != first }) {
            val view = views.getOrNull(i) ?: continue
            for (root in CardRouter.viewRoots(view)) {
                if (!warm(root.card, cancelled)) return
            }
            // The view's own verdict ([ViewConfig.webFallbackReason]): config alone, read on every open of it.
            if (cancelled()) return
            view.webFallbackReason
        }
    }

    /**
     * [card] and the cards inside it, as the build routes them ([CardConfig.inIsland]), checking [cancelled] before
     * each one: a new dashboard doesn't wait behind the rest of a large stack. False once cancelled.
     */
    private fun warm(card: CardConfig, cancelled: () -> Boolean): Boolean {
        if (cancelled()) return false
        if (!card.nativeOnly) card.compat
        for (child in card.childCards) if (!warm(child, cancelled)) return false
        return true
    }
}
