/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes every card of a freshly parsed dashboard on a background thread, the view about to open first, so the UI
 * thread building it finds each answer ready. It computes the same lazy values the build reads ([CardConfig.nativeOnly],
 * [CardConfig.compat]): a card is never checked twice, and its answer can't differ ([CardCompat.check] is pure, and
 * its caches are synchronized). A card the build reaches first is checked there, and this thread waits for it.
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
        val first = views.indexOfFirst { it.path == firstViewPath }.takeIf { it >= 0 }
            ?: firstViewPath?.toIntOrNull()?.takeIf { it in views.indices } ?: 0
        for (i in listOf(first) + views.indices.filter { it != first }) {
            val view = views.getOrNull(i) ?: continue
            for (root in CardRouter.viewRoots(view)) {
                if (cancelled()) return
                warm(root.card)
            }
        }
    }

    /** [card] and the cards inside it, as the build routes them ([CardConfig.inIsland]). */
    private fun warm(card: CardConfig) {
        if (!card.nativeOnly) card.compat
        card.childCards.forEach(::warm)
    }
}
