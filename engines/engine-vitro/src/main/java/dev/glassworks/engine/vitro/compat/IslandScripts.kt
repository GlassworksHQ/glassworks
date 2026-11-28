/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.model.LovelaceResourceList
import java.util.Locale

/**
 * The scripts an island page loads: the file names of the dashboard's Lovelace resources it runs as scripts (every
 * type but `css`, as `WebIslandCardView.buildIslandHtml` loads them). An island can only draw a custom card whose
 * script is among them, and only applies card_mod when card-mod is.
 */
class IslandScripts private constructor(
    private val files: Set<String>?,
    /**
     * Bubble Card modules may restyle every Bubble card on this Home Assistant ([LovelaceResourceList.bubbleModules]),
     * so a Bubble card Vitro would draw goes to the island, which runs Bubble's own script and its modules.
     */
    val bubbleModules: Boolean = false,
) {
    /** card-mod is among the scripts, so the island applies a card's card_mod styles. */
    val cardMod: Boolean get() = files == null || CARD_MOD in files

    /**
     * True when a script known to define [type] is among them ([VitroSupport.CARD_SCRIPTS]). False for a type with
     * no known script: without one, Vitro can't tell the island could draw it.
     */
    fun provides(type: String): Boolean {
        if (files == null) return true
        return VitroSupport.CARD_SCRIPTS[type]?.any { it in files } ?: false
    }

    /**
     * For a custom card with no known script: whether a script plausibly named after it is there (`acme-card` in
     * `acme-card.js`, `acme.js`, `acme-card-bundle.js`). Only used for a note.
     */
    fun mentions(type: String): Boolean {
        if (files == null || provides(type)) return true
        val tag = type.removePrefix("custom:").lowercase(Locale.ROOT)
        val stem = tag.removeSuffix("-card")
        return files.any { f -> tag in f || f.removeSuffix(".js").startsWith(stem) }
    }

    override fun equals(other: Any?): Boolean = other is IslandScripts && other.files == files && other.bubbleModules == bubbleModules
    override fun hashCode(): Int = files.hashCode() * 31 + bubbleModules.hashCode()

    companion object {
        private const val CARD_MOD = "card-mod.js"

        /** Every script there: what the offline checker assumes, since a config export has no resource list. */
        val ANY = IslandScripts(null)

        /**
         * The scripts among [resources]. Every card a view builds asks with the dashboard's one resource list, which
         * never changes once parsed: the last answer is kept for that list.
         */
        fun of(resources: List<LovelaceResource>): IslandScripts {
            last?.let { (list, scripts) -> if (list === resources) return scripts }
            return IslandScripts(
                resources.filter { it.type != "css" }.map { fileName(it.url) }.toSet(),
                bubbleModules = (resources as? LovelaceResourceList)?.bubbleModules == true,
            ).also { last = resources to it }
        }

        @Volatile private var last: Pair<List<LovelaceResource>, IslandScripts>? = null

        /** The file name of a resource URL, without its query or fragment (`/hacsfiles/x/mushroom.js?hacstag=1`). */
        fun fileName(url: String): String =
            url.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase(Locale.ROOT)
    }
}
