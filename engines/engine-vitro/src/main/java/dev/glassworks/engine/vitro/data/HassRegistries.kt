/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Home Assistant's registries in the shape its frontend hands every card: `hass.entities`, `hass.devices`,
 * `hass.areas` and `hass.floors`, each an object keyed by id. Built from the same commands HA's frontend reads
 * (`connection-mixin.ts`): the entity registry's display list, and the device, area and floor lists, each refetched
 * on its `*_registry_updated` event. Web islands get them ([HaDashboardClient.watchRegistries]).
 */
object HassRegistries {
    /** One registry: the command that lists it, the event that says it changed, and the key it has in `hass`. */
    enum class Kind(val command: String, val event: String, val key: String) {
        ENTITIES("config/entity_registry/list_for_display", "entity_registry_updated", "entities"),
        DEVICES("config/device_registry/list", "device_registry_updated", "devices"),
        AREAS("config/area_registry/list", "area_registry_updated", "areas"),
        FLOORS("config/floor_registry/list", "floor_registry_updated", "floors"),
    }

    /**
     * `hass.entities` from `list_for_display`'s compressed answer (`{entities: [{ei, di, ai, lb, ec, en, ic, pl, tk,
     * hb, dp, hn, np}], entity_categories: {"0": "config", …}}`), as HA's frontend expands it: a field HA left out stays
     * out (undefined to a card), and `labels` is always a list.
     */
    fun entities(result: JSONObject?): JSONObject {
        val out = JSONObject()
        val arr = result?.optJSONArray("entities") ?: return out
        val categories = result.optJSONObject("entity_categories")
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = e.optString("ei").ifEmpty { continue }
            val entry = JSONObject().put("entity_id", id)
            fun copy(from: String, to: String) {
                if (e.has(from) && !e.isNull(from)) entry.put(to, e.get(from))
            }
            copy("di", "device_id")
            copy("ai", "area_id")
            entry.put("labels", e.optJSONArray("lb") ?: JSONArray())
            copy("tk", "translation_key")
            copy("pl", "platform")
            if (e.has("ec") && !e.isNull("ec")) {
                categories?.opt(e.get("ec").toString())?.takeIf { it is String }?.let { entry.put("entity_category", it) }
            }
            copy("hn", "has_entity_name")
            copy("en", "name")
            copy("ic", "icon")
            copy("hb", "hidden")
            copy("dp", "display_precision")
            // Whether the device's name comes before the entity's own (HA's name composition, formatEntityName).
            copy("np", "next_name_part")
            out.put(id, entry)
        }
        return out
    }

    /** A registry list (devices, areas, floors) keyed by each entry's [idKey], entries as HA sent them. */
    fun keyed(result: JSONArray?, idKey: String): JSONObject {
        val out = JSONObject()
        if (result == null) return out
        for (i in 0 until result.length()) {
            val entry = result.optJSONObject(i) ?: continue
            val id = entry.optString(idKey).ifEmpty { continue }
            out.put(id, entry)
        }
        return out
    }

    /**
     * The four registries from each command's `result` ([results], by [Kind]; a missing or failed one is empty, as an
     * older Home Assistant without floors answers): `{entities, devices, areas, floors}`.
     */
    fun build(results: Map<Kind, Any?>): JSONObject = JSONObject()
        .put(Kind.ENTITIES.key, entities(results[Kind.ENTITIES] as? JSONObject))
        .put(Kind.DEVICES.key, keyed(results[Kind.DEVICES] as? JSONArray, "id"))
        .put(Kind.AREAS.key, keyed(results[Kind.AREAS] as? JSONArray, "area_id"))
        .put(Kind.FLOORS.key, keyed(results[Kind.FLOORS] as? JSONArray, "floor_id"))
}
