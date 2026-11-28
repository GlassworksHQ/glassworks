/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A card's or row's `name` as HA's frontend draws it (`hass.formatEntityName`, `common/entity/compute_entity_name`).
 * HA's name picker writes `name` as a string, one part (`{type: "device"}`, `{type: "text", text: "…"}`) or a list of
 * parts (`[{type: "area"}, {type: "entity"}]`). The parts are the entity's `floor`, `area`, `parent_device`, `device`
 * and own name (`entity`), read from the registries ([HaDisplay]), and literal `text`. They are joined with a space.
 */
object HaEntityName {
    /**
     * [name] (a card's raw `name` value) for [entityId], whose state's friendly name is [friendlyName]: a string as it
     * is; a part or a list of parts as HA joins them (it may be empty, as in HA); null when there is no `name`, so the
     * caller draws its default name. A number or boolean (`name: 1984` in YAML) reads as its text.
     */
    fun format(name: Any?, entityId: String?, friendlyName: String?): String? = when (name) {
        null, JSONObject.NULL -> null
        is String -> name
        is JSONObject -> formatParts(listOf(name), entityId, friendlyName)
        is JSONArray -> formatParts((0 until name.length()).map { name.opt(it) }, entityId, friendlyName)
        else -> name.toString()
    }

    /** Whether [name] is HA's name-picker form (a part or a list of parts), not plain text. */
    fun isStructured(name: Any?): Boolean = name is JSONObject || name is JSONArray

    private fun formatParts(items: List<Any?>, entityId: String?, friendlyName: String?): String {
        // Text parts alone are joined without looking anything up (HA's `formatTextOnly`).
        val texts = items.map { (it as? JSONObject)?.takeIf { p -> p.optString("type") == "text" } }
        if (texts.all { it != null }) return texts.joinToString(" ") { textOf(it!!) }
        val parts = parts(entityId, friendlyName)
        // An entity with no name of its own is named by its device, unless the name asks for the device itself.
        val entityIsDevice = parts.entity == null && items.none { (it as? JSONObject)?.optString("type") == "device" }
        return items.mapNotNull { item ->
            val p = item as? JSONObject ?: return@mapNotNull null
            when (p.optString("type")) {
                "text" -> textOf(p)
                "entity" -> if (entityIsDevice) parts.device else parts.entity
                "device" -> parts.device
                "parent_device" -> parts.parentDevice
                "area" -> parts.area
                "floor" -> parts.floor
                else -> null
            }
        }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun textOf(p: JSONObject): String = if (p.isNull("text")) "" else p.optString("text", "")

    private data class Parts(val entity: String?, val device: String?, val parentDevice: String?, val area: String?, val floor: String?)

    /**
     * HA's `getEntityEntryNameParts` without following context: the entity's own name, its device and that device's
     * parent, its area (its own, else its device's, else the parent device's) and that area's floor. An entity with no
     * registry entry has its friendly name alone.
     */
    private fun parts(entityId: String?, friendlyName: String?): Parts {
        val fallback = friendlyName ?: entityId?.substringAfter('.', entityId)?.replace('_', ' ')
        val entry = entityId?.let { HaDisplay.registry[it] } ?: return Parts(fallback?.takeIf { it.isNotEmpty() }, null, null, null, null)
        val deviceId = entry.deviceId
        val parentId = deviceId?.let { HaDisplay.deviceParents[it] }
        val areaId = entry.areaId
            ?: deviceId?.let { HaDisplay.deviceAreas[it] }
            ?: parentId?.let { HaDisplay.deviceAreas[it] }
        val area = areaId?.let { HaDisplay.areas[it] }
        return Parts(
            entity = entry.name?.takeIf { it.isNotEmpty() },
            device = deviceId?.let { HaDisplay.devices[it] }?.trim()?.takeIf { it.isNotEmpty() },
            parentDevice = parentId?.let { HaDisplay.devices[it] }?.trim()?.takeIf { it.isNotEmpty() },
            area = area?.name?.trim()?.takeIf { it.isNotEmpty() },
            floor = area?.floorId?.let { HaDisplay.floors[it] }?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
