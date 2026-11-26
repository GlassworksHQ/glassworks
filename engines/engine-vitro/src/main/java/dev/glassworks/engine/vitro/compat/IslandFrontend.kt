/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.LovelaceResource
import java.security.MessageDigest

/**
 * The custom cards an island found drawing with elements of Home Assistant's frontend that an island page lacks
 * (its `needsFrontend` report, `frontend-watch.js`), for this process's session. A view holding one goes to HA's
 * frontend ([WebFallback.viewReason] with [reason]), as a view with a built-in card Vitro doesn't draw does.
 *
 * Kept per card type and per version of the dashboard's scripts (every Lovelace resource's URL: HACS's `?hacstag=`
 * changes with each release), so an update of a card is looked at again, and a decision never flaps between builds
 * of the same script: once handed off, a type stays handed off for the session, and a view never goes back to Vitro
 * to find the same thing again. Not persisted: one island draw per type and session finds it again, and a remembered
 * decision would outlive the frontend version and stand-ins it was made with.
 */
object IslandFrontend {
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** True when no card was handed off this session: the views' own reasons are the whole answer. */
    val isEmpty: Boolean get() = learned.isEmpty()

    /** Records that [type], with these [resources], draws with HA elements [tags]. True when it's new. */
    fun record(resources: List<LovelaceResource>, type: String, tags: List<String>): Boolean =
        recordReason(resources, type, describe(type, tags))

    /**
     * Records that [type], with these [resources], needs HA's frontend for [reason] (as the log and the fallback show
     * it): a card that threw on something an island page doesn't have and drew nothing. True when it's new.
     */
    fun recordReason(resources: List<LovelaceResource>, type: String, reason: String): Boolean =
        learned.putIfAbsent(key(resources, type), reason) == null

    /** Why a [type] card with these [resources] needs HA's frontend, or null when no island found it. */
    fun reason(resources: List<LovelaceResource>, type: String): String? =
        if (learned.isEmpty()) null else learned[key(resources, type)]

    /** Forgets every decision (tests). */
    internal fun clear() = learned.clear()

    /** The reason as the log and the fallback show it. */
    internal fun describe(type: String, tags: List<String>): String {
        val names = tags.joinToString(", ") { "`$it`" }
        return "a `$type` card (it draws with Home Assistant's $names, which only its frontend has)"
    }

    /** One card type under one version of the dashboard's scripts. */
    internal fun key(resources: List<LovelaceResource>, type: String): String = "${version(resources)} $type"

    /** The dashboard's one resource list, kept with its version: every card of a view asks with that same list. */
    @Volatile private var last: Pair<List<LovelaceResource>, String>? = null

    private fun version(resources: List<LovelaceResource>): String {
        last?.let { (list, v) -> if (list === resources) return v }
        val md = MessageDigest.getInstance("SHA-256")
        resources.map { it.url }.sorted().forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().take(12).joinToString("") { "%02x".format(it) }.also { last = resources to it }
    }
}
