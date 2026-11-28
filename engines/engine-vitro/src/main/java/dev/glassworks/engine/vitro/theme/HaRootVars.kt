/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.theme

import org.json.JSONObject

/**
 * Home Assistant's own CSS variables, as its frontend puts them on the page's `:root` with its default theme in light
 * and dark mode (`ha-root-vars.json`, written by tools/vitro-parity/ha-root-vars.mjs from the pinned frontend): HA's
 * color tokens, `--rgb-disabled-color`, `--ha-space-*`, `--ha-font-*` and the rest. Every card in HA's page inherits
 * them. An island page has no HA frontend, so it starts its `:root` from them ([ResolvedTheme.toCssRootBlock]), and a
 * theme value that names one resolves to HA's value ([ThemeResolver.resolve]).
 */
object HaRootVars {
    private const val RESOURCE = "/dev/glassworks/engine/vitro/theme/ha-root-vars.json"

    private val table: Pair<Map<String, String>, Map<String, String>> by lazy {
        val json = HaRootVars::class.java.getResourceAsStream(RESOURCE)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        fun mode(name: String): Map<String, String> {
            val o = json?.optJSONObject(name) ?: return emptyMap()
            val out = LinkedHashMap<String, String>(o.length())
            for (k in o.keys()) out[k] = o.optString(k)
            return out
        }
        mode("light") to mode("dark")
    }

    /** HA's variables in [dark] mode or light, name (no `--`) to value; empty when the table is missing. */
    fun of(dark: Boolean): Map<String, String> = if (dark) table.second else table.first

    /** The frontend build the table was read from. */
    val frontend: String by lazy {
        HaRootVars::class.java.getResourceAsStream(RESOURCE)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)).optString("frontend") }.orEmpty()
    }
}
