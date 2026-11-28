/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What custom cards drawn in islands answered to `getCardSize()`, by their config, for the session. HA's masonry view
 * weighs each card by it (`computeCardSize`: the card's own answer, 1 when it has none or takes over 500 ms), and so
 * decides which column a card goes in and how many columns stay. Vitro can't ask a card before its island runs, so a
 * custom card weighs 1 until its island says (tablet Vitro run 3: twelve island cards of size 2 filled 4 columns in
 * HA, 3 in Vitro). [dev.glassworks.engine.vitro.model.HaCardSize] reads it.
 */
object IslandCardSizes {
    private val sizes = ConcurrentHashMap<String, Double>()

    /** What the card with config [raw] answered, or null when no island has said. */
    fun size(raw: JSONObject): Double? = if (sizes.isEmpty()) null else sizes[raw.toString()]

    /** Records [size] for the card with config [raw]. True when it's new or differs from what was known. */
    fun record(raw: JSONObject, size: Double): Boolean = sizes.put(raw.toString(), size) != size

    /** Forgets every size (another Home Assistant, tests). */
    fun clear() = sizes.clear()
}
