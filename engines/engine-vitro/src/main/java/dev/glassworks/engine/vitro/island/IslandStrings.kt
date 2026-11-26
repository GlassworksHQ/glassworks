/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.island

import dev.glassworks.engine.vitro.data.HaDisplay
import dev.glassworks.engine.vitro.data.HaText
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The strings a web island's `hass.localize` reads, as HA's frontend has them: its UI strings in the user's language
 * ([HaText]) and the state labels Vitro has read from HA's backend ([HaDisplay]). The island server hands them out
 * at an address named after their content ([path]), so the browser keeps one copy for every island page and a new
 * set gets a new address. A key that isn't there is a miss, and `localize` returns "" for it, as HA's does.
 */
object IslandStrings {
    /** One set: the versions it was made from, its content hash, and its JSON. */
    class Snapshot internal constructor(
        internal val textVersion: Long,
        internal val labelCount: Int,
        val hash: String,
        val body: ByteArray,
    )

    @Volatile private var last: Snapshot? = null

    /** The strings now; made again only when HA's strings or the labels changed. Any thread (not the main one: it hashes). */
    fun current(): Snapshot {
        val version = HaText.version
        val labels = HaDisplay.stateLabelCount
        last?.let { if (it.textVersion == version && it.labelCount == labels) return it }
        synchronized(this) {
            last?.let { if (it.textVersion == version && it.labelCount == labels) return it }
            val all = JSONObject()
            for ((k, v) in HaText.installed()) all.put(k, v)
            // HA's UI strings win a key both have (there are none: the backend's are all `component.*`).
            for ((k, v) in HaDisplay.stateLabels()) if (!all.has(k)) all.put(k, v)
            val body = all.toString().toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(body).take(12).joinToString("") { "%02x".format(it) }
            return Snapshot(version, labels, hash, body).also { last = it }
        }
    }

    /** The address of the strings now, on the island server: `/__vitro/strings/<hash>.json`. */
    fun path(): String = "$PREFIX${current().hash}.json"

    internal const val PREFIX = "/__vitro/strings/"
}
