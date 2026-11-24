/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What HA's frontend knows about showing an entity, read from HA itself: the entity registry's display entries
 * (`config/entity_registry/list_for_display`: platform, translation key, display precision, custom icon), the
 * icon translations (`frontend/get_icons`) and the state translations (`frontend/get_translations`, English).
 * [icon] and [stateLabel] follow HA's `entityIcon` and `computeStateDisplay`; until the data arrives they return
 * null and callers keep their own defaults. One HA per process, so this is a process-wide holder; the client
 * fills it off the main thread and the store re-renders once it lands.
 */
object HaDisplay {
    /** One `list_for_display` entry. */
    data class Entry(val platform: String?, val translationKey: String?, val displayPrecision: Int?, val icon: String?)

    @Volatile var registry: Map<String, Entry> = emptyMap()
        private set
    private val componentIcons = ConcurrentHashMap<String, JSONObject>()
    private val platformIcons = ConcurrentHashMap<String, JSONObject>()
    private val stateLabels = ConcurrentHashMap<String, String>()
    // newSetFromMap, not ConcurrentHashMap.newKeySet: KeySetView is API 24, and the Mirror runs Android 6.
    private val translatedDomains: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val translatedPlatforms: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val iconDomains: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val iconPlatforms: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Forgets everything (a new connection, or tests). */
    fun reset() {
        registry = emptyMap()
        componentIcons.clear(); platformIcons.clear(); stateLabels.clear()
        translatedDomains.clear(); translatedPlatforms.clear(); iconDomains.clear(); iconPlatforms.clear()
    }

    /** Reads `list_for_display`'s result (`entities: [{ei, pl, tk, dp, ic}, ...]`). */
    fun applyRegistry(result: JSONObject) {
        val arr = result.optJSONArray("entities") ?: return
        val out = HashMap<String, Entry>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("ei")
            if (id.isEmpty()) continue
            out[id] = Entry(
                platform = e.optString("pl").ifEmpty { null },
                translationKey = e.optString("tk").ifEmpty { null },
                displayPrecision = if (e.has("dp") && !e.isNull("dp")) e.optInt("dp") else null,
                icon = e.optString("ic").ifEmpty { null },
            )
        }
        registry = out
    }

    /** The domains and platforms among [entityIds] whose icons and translations aren't loaded yet. */
    fun missingFor(entityIds: Collection<String>): Pair<Set<String>, Set<String>> {
        val domains = HashSet<String>()
        val platforms = HashSet<String>()
        for (id in entityIds) {
            val d = id.substringBefore('.', "")
            if (d.isNotEmpty() && (d !in translatedDomains || d !in iconDomains)) domains += d
            registry[id]?.takeIf { it.translationKey != null }?.platform?.let { p ->
                if (p !in translatedPlatforms || p !in iconPlatforms) platforms += p
            }
        }
        return domains to platforms
    }

    /** `frontend/get_icons` for `entity_component` ([domains]) or `entity` ([platforms]). */
    fun applyIcons(result: JSONObject, component: Boolean, requested: Collection<String>) {
        val res = result.optJSONObject("resources") ?: JSONObject()
        val keys = res.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = res.optJSONObject(k) ?: continue
            if (component) componentIcons[k] = v else platformIcons[k] = v
        }
        if (component) iconDomains += requested else iconPlatforms += requested
    }

    /** `frontend/get_translations` for `entity_component` or `entity`: only the `.state.` labels are kept. */
    fun applyTranslations(result: JSONObject, component: Boolean, requested: Collection<String>) {
        val res = result.optJSONObject("resources") ?: JSONObject()
        val keys = res.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (".state." in k) res.optString(k).takeIf { it.isNotEmpty() }?.let { stateLabels[k] = it }
        }
        if (component) translatedDomains += requested else translatedPlatforms += requested
    }

    /** HA's `entityIcon` after the card's and the state's own icon: the registry's, the integration's, the domain's. */
    fun icon(s: EntityState): String? {
        val entry = registry[s.entityId]
        entry?.icon?.let { return it }
        val domain = s.domain
        if (entry?.translationKey != null && entry.platform != null) {
            platformIcons[entry.platform]?.optJSONObject(domain)?.optJSONObject(entry.translationKey)
                ?.let { fromTranslations(it, s) }?.let { return it }
        }
        val comp = componentIcons[domain] ?: return null
        val byClass = s.deviceClass?.let { comp.optJSONObject(it) } ?: comp.optJSONObject("_") ?: return null
        return fromTranslations(byClass, s)
    }

    /** `state` match, then the `range` step at or under a numeric state, then `default`. */
    private fun fromTranslations(t: JSONObject, s: EntityState): String? {
        t.optJSONObject("state")?.optString(s.state)?.takeIf { it.isNotEmpty() }?.let { return it }
        val range = t.optJSONObject("range")
        val n = s.state.toDoubleOrNull()
        if (range != null && n != null) {
            var best: Double? = null
            var icon: String? = null
            val keys = range.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = k.toDoubleOrNull() ?: continue
                if (v <= n && (best == null || v > best)) { best = v; icon = range.optString(k) }
            }
            icon?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return t.optString("default").takeIf { it.isNotEmpty() }
    }

    /**
     * HA's translated state ([computeStateDisplay]'s lookup): the integration's label for the entity's translation
     * key, then the domain's label for its device class, then the domain's own. Null when HA hasn't sent the
     * translations yet; [state] itself when they're loaded and have no label, as HA shows it.
     */
    fun stateLabel(s: EntityState): String? {
        val entry = registry[s.entityId]
        val domain = s.domain
        if (entry?.translationKey != null && entry.platform != null) {
            stateLabels["component.${entry.platform}.entity.$domain.${entry.translationKey}.state.${s.state}"]?.let { return it }
        }
        s.deviceClass?.let { dc -> stateLabels["component.$domain.entity_component.$dc.state.${s.state}"]?.let { return it } }
        stateLabels["component.$domain.entity_component._.state.${s.state}"]?.let { return it }
        return if (domain in translatedDomains) s.state else null
    }

    fun displayPrecision(entityId: String): Int? = registry[entityId]?.displayPrecision

    private val US = DecimalFormatSymbols(Locale.US)

    /**
     * HA's `formatNumber` for a state string with `getNumberFormatOptions`: the display precision when the registry
     * has one, no decimals when the step and the value are whole, else the decimals the string carries. English
     * grouping, rounding half away from zero like `Intl`.
     */
    fun formatState(raw: String, displayPrecision: Int?, step: Double?): String {
        val bd = raw.trim().toBigDecimalOrNull() ?: return raw
        val digits = when {
            displayPrecision != null -> displayPrecision.coerceIn(0, 10)
            step != null && step % 1.0 == 0.0 && bd.stripTrailingZeros().scale() <= 0 -> 0
            else -> raw.substringAfter('.', "").takeIf { '.' in raw }?.length ?: 0
        }
        return fixed(bd, digits, digits)
    }

    /** HA's `formatNumber` for a number value: up to 2 decimals (or [maxDigits]), trailing zeros dropped. */
    fun formatValue(v: Double, maxDigits: Int = 2): String = fixed(BigDecimal.valueOf(v), 0, maxDigits)

    private fun fixed(bd: BigDecimal, minDigits: Int, maxDigits: Int): String {
        val f = DecimalFormat("#,##0", US).apply {
            minimumFractionDigits = minDigits
            maximumFractionDigits = maxDigits
            roundingMode = RoundingMode.HALF_UP
        }
        return f.format(bd)
    }
}
