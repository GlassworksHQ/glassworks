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
    data class Entry(
        val platform: String?,
        val translationKey: String?,
        val displayPrecision: Int?,
        val icon: String?,
        val deviceId: String? = null,
        /** The entity's own name (`en`): null for a device's main entity, which takes the device's name. */
        val name: String? = null,
        /** The entity's own area (`ai`); null when it follows its device's. */
        val areaId: String? = null,
        /** True for a config or diagnostic entity (`ec`), which area summaries leave out. */
        val categorized: Boolean = false,
        /** True for an entity hidden in HA (`hb`). */
        val hidden: Boolean = false,
    )

    /** One area of `config/area_registry/list`: what HA's area card draws. */
    data class Area(
        val name: String,
        val icon: String?,
        val picture: String?,
        val temperatureEntityId: String?,
        val humidityEntityId: String?,
    )

    /** Device id to its name (`name_by_user`, else `name`), from `config/device_registry/list`. */
    @Volatile var devices: Map<String, String> = emptyMap()
        private set

    /** Device id to its area id, from `config/device_registry/list`. */
    @Volatile var deviceAreas: Map<String, String> = emptyMap()
        private set

    /** Area id to the area, from `config/area_registry/list`. */
    @Volatile var areas: Map<String, Area> = emptyMap()
        private set

    /** The registry in HA's order (an area card's sensors and camera follow it). */
    @Volatile var registry: Map<String, Entry> = emptyMap()
        private set
    private val componentIcons = ConcurrentHashMap<String, JSONObject>()
    private val platformIcons = ConcurrentHashMap<String, JSONObject>()
    private val stateLabels = ConcurrentHashMap<String, String>()
    // Sets over a ConcurrentHashMap through Collections, which API 23 has (the map's own key-set view is API 24).
    private val translatedDomains = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val translatedPlatforms = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val iconDomains = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val iconPlatforms = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Forgets everything (a new connection, or tests). */
    fun reset() {
        registry = emptyMap()
        devices = emptyMap()
        deviceAreas = emptyMap()
        areas = emptyMap()
        componentIcons.clear(); platformIcons.clear(); stateLabels.clear()
        translatedDomains.clear(); translatedPlatforms.clear(); iconDomains.clear(); iconPlatforms.clear()
    }

    /** Reads `list_for_display`'s result (`entities: [{ei, pl, tk, dp, ic}, ...]`). */
    fun applyRegistry(result: JSONObject) {
        val arr = result.optJSONArray("entities") ?: return
        val out = LinkedHashMap<String, Entry>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("ei")
            if (id.isEmpty()) continue
            out[id] = Entry(
                platform = e.optString("pl").ifEmpty { null },
                translationKey = e.optString("tk").ifEmpty { null },
                displayPrecision = if (e.has("dp") && !e.isNull("dp")) e.optInt("dp") else null,
                icon = e.optString("ic").ifEmpty { null },
                deviceId = e.optString("di").ifEmpty { null },
                name = if (e.has("en") && !e.isNull("en")) e.optString("en").ifEmpty { null } else null,
                areaId = if (e.isNull("ai")) null else e.optString("ai").ifEmpty { null },
                categorized = e.has("ec") && !e.isNull("ec"),
                hidden = e.optBoolean("hb", false),
            )
        }
        registry = out
    }

    /** Reads `config/device_registry/list`'s result, keeping each device's display name. */
    fun applyDevices(result: org.json.JSONArray) {
        val out = HashMap<String, String>(result.length() * 2)
        val inArea = HashMap<String, String>()
        for (i in 0 until result.length()) {
            val d = result.optJSONObject(i) ?: continue
            if (!d.isNull("area_id")) d.optString("area_id").takeIf { it.isNotEmpty() }?.let { inArea[d.optString("id")] = it }
            val name = d.optString("name_by_user").takeIf { it.isNotEmpty() && !d.isNull("name_by_user") }
                ?: d.optString("name").takeIf { it.isNotEmpty() && !d.isNull("name") } ?: continue
            out[d.optString("id")] = name
        }
        devices = out
        deviceAreas = inArea
    }

    /** Reads `config/area_registry/list`'s result. */
    fun applyAreas(result: org.json.JSONArray) {
        val out = HashMap<String, Area>(result.length() * 2)
        fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
        for (i in 0 until result.length()) {
            val a = result.optJSONObject(i) ?: continue
            val id = a.str("area_id") ?: continue
            out[id] = Area(a.str("name") ?: id, a.str("icon"), a.str("picture"), a.str("temperature_entity_id"), a.str("humidity_entity_id"))
        }
        areas = out
    }

    /** The area [entityId] is in: its own, else its device's (HA's `getEntityContext`). */
    fun areaOf(entityId: String): String? {
        val e = registry[entityId] ?: return null
        return e.areaId ?: e.deviceId?.let { deviceAreas[it] }
    }

    /**
     * The entities of [areaId] an area card counts, in HA's registry order: not hidden, no entity category
     * (`generateEntityFilter` with `entity_category: "none"`).
     */
    fun areaEntityIds(areaId: String): List<String> =
        registry.entries.filter { (id, e) -> !e.hidden && !e.categorized && areaOf(id) == areaId }.map { it.key }

    /**
     * The name HA's cards give an entity on a device (`hass.formatEntityName`'s default): the device's name, then the
     * entity's own name as it is ("Sunset Lamp Sunset"; "Fridge" + "Fridge temperature" stays "Fridge Fridge
     * temperature", as HA's page draws it), or the device's name alone for its main entity. Null when the entity has
     * no device (or the registries haven't arrived): callers use the friendly name. [separator] joins the two (HA's
     * history charts use " ▸ ").
     */
    fun deviceEntityName(entityId: String, separator: String = " "): String? {
        val entry = registry[entityId] ?: return null
        val device = entry.deviceId?.let { devices[it] } ?: return null
        val own = entry.name?.trim()
        return if (own.isNullOrEmpty() || own.equals(device, ignoreCase = true)) device else "$device$separator$own"
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

    /** The domain's own icon for [s]'s state and device class, skipping the entity's and its integration's. */
    fun domainIcon(s: EntityState): String? {
        val comp = componentIcons[s.domain] ?: return null
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
