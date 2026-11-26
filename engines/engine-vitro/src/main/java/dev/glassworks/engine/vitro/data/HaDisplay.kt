/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * What HA's frontend knows about showing an entity, read from HA itself: the entity registry's display entries
 * (`config/entity_registry/list_for_display`: platform, translation key, display precision, custom icon), the
 * icon translations (`frontend/get_icons`) and the state translations (`frontend/get_translations`, in the user's language).
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
        /** `np`, HA's `next_name_part`: "device" when the device's name comes before the entity's own. */
        val nextNamePart: String? = null,
    )

    /** One area of `config/area_registry/list`: what HA's area card draws. */
    data class Area(
        val name: String,
        val icon: String?,
        val picture: String?,
        val temperatureEntityId: String?,
        val humidityEntityId: String?,
        val floorId: String? = null,
    )

    /** Device id to its name (`name_by_user`, else `name`), from `config/device_registry/list`. */
    @Volatile var devices: Map<String, String> = emptyMap()
        private set

    /** Device id to its area id, from `config/device_registry/list`. */
    @Volatile var deviceAreas: Map<String, String> = emptyMap()
        private set

    /** Device id to its parent device's id (`parent_device_id`), from `config/device_registry/list`. */
    @Volatile var deviceParents: Map<String, String> = emptyMap()
        private set

    /** Area id to the area, from `config/area_registry/list`. */
    @Volatile var areas: Map<String, Area> = emptyMap()
        private set

    /** Floor id to its name, from `config/floor_registry/list`. */
    @Volatile var floors: Map<String, String> = emptyMap()
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

    /** The Home Assistant (its origin) whose data this holds, set by [connectionTo]. */
    @Volatile private var origin: String? = null

    /**
     * A connection to Home Assistant at [origin] begins. Another Home Assistant's data goes at once ([reset]). The same
     * one's stays, so cards keep their names, precision and translated states while the new connection's entity
     * snapshot draws them, and the connection's answers replace it as they arrive: only what was loaded is forgotten,
     * so every domain's and integration's icons and words are asked for again.
     */
    @Synchronized
    fun connectionTo(origin: String?) {
        if (origin == null || origin != this.origin) {
            reset()
            this.origin = origin
            return
        }
        translatedDomains.clear(); translatedPlatforms.clear(); iconDomains.clear(); iconPlatforms.clear()
    }

    /** Forgets everything (a new Home Assistant, or tests). */
    @Synchronized
    fun reset() {
        origin = null
        registry = emptyMap()
        devices = emptyMap()
        deviceAreas = emptyMap()
        deviceParents = emptyMap()
        areas = emptyMap()
        floors = emptyMap()
        componentIcons.clear(); platformIcons.clear(); stateLabels.clear()
        labelRevision++
        translatedDomains.clear(); translatedPlatforms.clear(); iconDomains.clear(); iconPlatforms.clear()
    }

    /**
     * The user's language changed: the next [missingFor] asks for every domain's and integration's translations
     * again. The labels in hand stay until the new ones replace them (HA's backend fills a missing key with English,
     * so every label is replaced).
     */
    fun forgetTranslations() {
        translatedDomains.clear(); translatedPlatforms.clear()
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
                nextNamePart = if (e.isNull("np")) null else e.optString("np").ifEmpty { null },
            )
        }
        registry = out
    }

    /** Reads `config/device_registry/list`'s result, keeping each device's display name. */
    fun applyDevices(result: org.json.JSONArray) {
        val out = HashMap<String, String>(result.length() * 2)
        val inArea = HashMap<String, String>()
        val parents = HashMap<String, String>()
        for (i in 0 until result.length()) {
            val d = result.optJSONObject(i) ?: continue
            if (!d.isNull("area_id")) d.optString("area_id").takeIf { it.isNotEmpty() }?.let { inArea[d.optString("id")] = it }
            if (d.has("parent_device_id") && !d.isNull("parent_device_id")) {
                d.optString("parent_device_id").takeIf { it.isNotEmpty() }?.let { parents[d.optString("id")] = it }
            }
            val name = d.optString("name_by_user").takeIf { it.isNotEmpty() && !d.isNull("name_by_user") }
                ?: d.optString("name").takeIf { it.isNotEmpty() && !d.isNull("name") } ?: continue
            out[d.optString("id")] = name
        }
        devices = out
        deviceAreas = inArea
        deviceParents = parents
    }

    /** Reads `config/area_registry/list`'s result. */
    fun applyAreas(result: org.json.JSONArray) {
        val out = HashMap<String, Area>(result.length() * 2)
        fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
        for (i in 0 until result.length()) {
            val a = result.optJSONObject(i) ?: continue
            val id = a.str("area_id") ?: continue
            out[id] = Area(
                a.str("name") ?: id, a.str("icon"), a.str("picture"), a.str("temperature_entity_id"), a.str("humidity_entity_id"),
                a.str("floor_id"),
            )
        }
        areas = out
    }

    /** Reads `config/floor_registry/list`'s result, keeping each floor's name. */
    fun applyFloors(result: org.json.JSONArray) {
        val out = HashMap<String, String>(result.length() * 2)
        for (i in 0 until result.length()) {
            val f = result.optJSONObject(i) ?: continue
            val id = f.optString("floor_id").ifEmpty { continue }
            if (f.isNull("name")) continue
            f.optString("name").takeIf { it.isNotEmpty() }?.let { out[id] = it }
        }
        floors = out
    }

    /** [areaId]'s name, or null before the area registry is in (or for an unknown area). */
    fun areaName(areaId: String): String? = areas[areaId]?.name

    /** [areaId]'s icon, or null. */
    fun areaIcon(areaId: String): String? = areas[areaId]?.icon

    /** [deviceId]'s name (`name_by_user`, else `name`), or null. */
    fun deviceName(deviceId: String): String? = devices[deviceId]

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

    /** An entity's name in parts, as HA's `computeEntityNameParts` gives them: null where it has none. */
    data class NameParts(val area: String?, val device: String?, val entity: String?)

    /**
     * HA's `computeEntityEntryNameParts` (#885): the entity's area (its own, else its device's), its device's name and
     * its own name (null for a device's main entity). The device takes part when the entity has no name of its own or
     * its `next_name_part` is "device", as in HA; otherwise the name stops at the entity. Without a registry entry,
     * [friendlyName] alone.
     */
    fun nameParts(entityId: String, friendlyName: String): NameParts {
        val entry = registry[entityId] ?: return NameParts(null, null, friendlyName)
        val ownName = entry.name?.trim()?.takeIf { it.isNotEmpty() }
        val device = entry.deviceId?.let { devices[it] }?.takeIf { ownName == null || entry.nextNamePart == "device" }
        val own = ownName ?: if (device == null) friendlyName else null
        return NameParts(areaOf(entityId)?.let { areas[it]?.name }, device, own)
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

    /**
     * `frontend/get_translations` for `entity_component` or `entity`: only the `.state.` labels and the attributes'
     * names (`.state_attributes.<name>.name`) are kept.
     */
    fun applyTranslations(result: JSONObject, component: Boolean, requested: Collection<String>) {
        val res = result.optJSONObject("resources") ?: JSONObject()
        val keys = res.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (".state." in k || (".state_attributes." in k && k.endsWith(".name"))) {
                res.optString(k).takeIf { it.isNotEmpty() }?.let { if (stateLabels.put(k, it) != it) labelRevision++ }
            }
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
        stateIcon(s)?.let { return it }
        val comp = componentIcons[domain] ?: return null
        val byClass = s.deviceClass?.let { comp.optJSONObject(it) } ?: comp.optJSONObject("_") ?: return null
        return fromTranslations(byClass, s)
    }

    /**
     * HA's `stateIcon` (`common/entity/state_icon.ts`), between the integration's icons and the domain's: an update
     * installing, a device tracker by how it's tracked, the sun, a date-only or time-only input_datetime.
     */
    internal fun stateIcon(s: EntityState): String? {
        val a = s.attributes
        return when (s.domain) {
            "update" -> when {
                a["in_progress"].let { it != null && it != false && it != 0 && it != "" } -> "mdi:package-down"
                s.state == "on" -> "mdi:package-up"
                else -> "mdi:package"
            }
            "device_tracker" -> when (a["source_type"]) {
                "router" -> if (s.state == "home") "mdi:lan-connect" else "mdi:lan-disconnect"
                "bluetooth", "bluetooth_le" -> if (s.state == "home") "mdi:bluetooth-connect" else "mdi:bluetooth"
                else -> if (s.state == "not_home") "mdi:account-arrow-right" else "mdi:account"
            }
            "sun" -> if (s.state == "above_horizon") "mdi:white-balance-sunny" else "mdi:weather-night"
            "input_datetime" -> when {
                a["has_date"].let { it == null || it == false } -> "mdi:clock"
                a["has_time"].let { it == null || it == false } -> "mdi:calendar"
                else -> null
            }
            else -> null
        }
    }

    /**
     * HA's `attributeIcon` (`data/icons.ts`): the icon of [attribute]'s [value] (a climate's `hvac_action`), from the
     * integration's icons for the entity's translation key, else the domain's for its device class or its default.
     * Null until HA's icons are loaded, or when none is set.
     */
    fun attributeIcon(s: EntityState, attribute: String, value: String): String? {
        val entry = registry[s.entityId]
        val domain = s.domain
        if (entry?.translationKey != null && entry.platform != null) {
            platformIcons[entry.platform]?.optJSONObject(domain)?.optJSONObject(entry.translationKey)
                ?.optJSONObject("state_attributes")?.optJSONObject(attribute)
                ?.let { fromAttributeTranslations(it, value) }?.let { return it }
        }
        val comp = componentIcons[domain] ?: return null
        val t = s.deviceClass?.let { comp.optJSONObject(it)?.optJSONObject("state_attributes")?.optJSONObject(attribute) }
            ?: comp.optJSONObject("_")?.optJSONObject("state_attributes")?.optJSONObject(attribute)
            ?: return null
        return fromAttributeTranslations(t, value)
    }

    /** HA's `getIconFromTranslations` for an attribute value: its `state` match, else `default`. */
    private fun fromAttributeTranslations(t: JSONObject, value: String): String? =
        t.optJSONObject("state")?.optString(value)?.takeIf { it.isNotEmpty() } ?: t.optString("default").takeIf { it.isNotEmpty() }

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

    /**
     * HA's `formatEntityAttributeValue` lookup for a string attribute ([attribute] `hvac_action` = `heating`): the
     * integration's label, the device class's, the domain's. Null when none is loaded.
     */
    fun attributeLabel(s: EntityState, attribute: String, value: String): String? {
        val entry = registry[s.entityId]
        val domain = s.domain
        if (entry?.translationKey != null && entry.platform != null) {
            stateLabels["component.${entry.platform}.entity.$domain.${entry.translationKey}.state_attributes.$attribute.state.$value"]?.let { return it }
        }
        s.deviceClass?.let { dc -> stateLabels["component.$domain.entity_component.$dc.state_attributes.$attribute.state.$value"]?.let { return it } }
        return stateLabels["component.$domain.entity_component._.state_attributes.$attribute.state.$value"]
    }

    /** The domain's own word for [state] (`component.<domain>.entity_component._.state.<state>`), when loaded. */
    /**
     * HA's `formatEntityAttributeName` ([attribute] `fan_speed` = "Fan speed"): the integration's name for it, the
     * device class's, the domain's, else the attribute's own words, capitalized, as `computeAttributeNameDisplay`.
     */
    fun attributeName(s: EntityState, attribute: String): String {
        val entry = registry[s.entityId]
        val domain = s.domain
        if (entry?.translationKey != null && entry.platform != null) {
            stateLabels["component.${entry.platform}.entity.$domain.${entry.translationKey}.state_attributes.$attribute.name"]?.let { return it }
        }
        s.deviceClass?.let { dc -> stateLabels["component.$domain.entity_component.$dc.state_attributes.$attribute.name"]?.let { return it } }
        stateLabels["component.$domain.entity_component._.state_attributes.$attribute.name"]?.let { return it }
        return attribute.replace('_', ' ').replace(Regex("""\bid\b"""), "ID").replace(Regex("""\bip\b"""), "IP")
            .replace(Regex("""\bmac\b"""), "MAC").replace(Regex("""\bgps\b"""), "GPS")
            .replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
    }

    /**
     * Every state label read so far (`component.<domain>.entity_component.<class>.state.<state>`, and an
     * integration's `component.<platform>.entity.…`), keyed as HA's `localize` reads them: for web islands.
     */
    fun stateLabels(): Map<String, String> = HashMap(stateLabels)

    /** Counts changes to the state labels (one added or replaced, or all forgotten): [stateLabels] differ after one. */
    @Volatile var labelRevision: Long = 0
        private set

    fun domainStateLabel(domain: String, state: String): String? = stateLabels["component.$domain.entity_component._.state.$state"]

    fun displayPrecision(entityId: String): Int? = registry[entityId]?.displayPrecision

    /**
     * HA's `formatNumber` for a state string with `getNumberFormatOptions`: the display precision when the registry
     * has one, no decimals when the step and the value are whole, else the decimals the string carries; in the
     * user's number format ([HaFormat]).
     */
    fun formatState(raw: String, displayPrecision: Int?, step: Double?): String =
        HaFormat.formatNumber(raw, HaLocale.current, HaFormat.numberOptions(raw, step, displayPrecision))

    /** HA's `formatNumber` for a number value: up to 2 decimals (or [maxDigits]), trailing zeros dropped. */
    fun formatValue(v: Double, maxDigits: Int = 2): String =
        HaFormat.formatNumber(v, HaLocale.current, HaFormat.NumberOptions(maximumFractionDigits = maxDigits))

    /** HA's `formatNumber` with fixed decimals ([digits] minimum and maximum), as cards pass `display_precision`. */
    fun formatFixed(v: Double, digits: Int): String =
        HaFormat.formatNumber(v, HaLocale.current, HaFormat.NumberOptions(digits, digits))
}
