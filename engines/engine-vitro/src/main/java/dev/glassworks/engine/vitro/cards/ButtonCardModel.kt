/* SPDX-License-Identifier: MIT
 *
 * Ported from button-card: custom-cards/button-card, tag v7.0.1. Copyright (c) 2018, Alexandre Garcia.
 * The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: rewritten in Kotlin for Vitro's native views; only the config side (templates, `[[[ ]]]` detection, `state:` matching), no JavaScript. */

package dev.glassworks.engine.vitro.cards

import dev.glassworks.engine.vitro.data.EntityState
import dev.glassworks.engine.vitro.model.VisibilityCondition
import org.json.JSONArray
import org.json.JSONObject

// custom:button-card (custom-cards/button-card v7.0.1, MIT), the parts of its config that don't draw anything:
// dashboard templates, its `[[[ ]]]` JavaScript values, its defaults and its `state:` matching. Ported from
// src/button-card.ts and src/helpers.ts at that release; the notice is in THIRD-PARTY-NOTICES.md.

/** `custom:button-card`'s type, and the dashboard key holding its templates. */
object ButtonCardTemplates {
    const val TYPE = "custom:button-card"
    const val TEMPLATES_KEY = "button_card_templates"

    /** How deep templates may name templates before Vitro stops, as button-card would overflow its stack. */
    private const val MAX_DEPTH = 32

    /**
     * The dashboard [raw] with every button-card's `template:` merged in from its `button_card_templates`, the way
     * button-card's `setConfig` merges them (`_configFromLLTemplates`). Done once, when the dashboard is parsed, so the
     * native card and an island both get the merged config: an island has no dashboard to read the templates from.
     * A card whose template is missing (or loops) keeps its `template:`; [dev.glassworks.engine.vitro.compat.CardCompat]
     * sends it to an island, where button-card shows the same error it shows in HA. [raw] itself is never changed.
     */
    fun expandDashboard(raw: JSONObject): JSONObject {
        val templates = raw.optJSONObject(TEMPLATES_KEY) ?: return raw
        if (templates.length() == 0) return raw
        val views = raw.optJSONArray("views") ?: return raw
        if (!mentionsTemplate(views)) return raw
        val copy = JSONObject(raw.toString())
        copy.put("views", expandIn(copy.getJSONArray("views"), templates) as JSONArray)
        return copy
    }

    /** Cheap pre-check: a dashboard whose views never say `"template"` has nothing to merge. */
    private fun mentionsTemplate(views: JSONArray): Boolean = "\"template\"" in views.toString()

    /** Walks any JSON value, replacing each button-card config that names templates with its merged config. */
    private fun expandIn(value: Any?, templates: JSONObject): Any? = when (value) {
        is JSONObject -> {
            val merged = if (value.optString("type") == TYPE && value.has("template")) expand(value, templates) ?: value else value
            for (k in merged.keys().asSequence().toList()) merged.put(k, expandIn(merged.opt(k), templates))
            merged
        }
        is JSONArray -> {
            for (i in 0 until value.length()) value.put(i, expandIn(value.opt(i), templates))
            value
        }
        else -> value
    }

    /**
     * [card] merged with the templates it names (`template: name` or a list), or null when one is missing or they
     * loop. Templates merge in order, each one first merged with its own templates; the card goes last. Lists
     * concatenate and objects merge deeply ([mergeDeep]); `state:` entries with the same `id` merge ([mergeStatesById]).
     */
    fun expand(card: JSONObject, templates: JSONObject): JSONObject? {
        val extras = ArrayList<Any>()
        val result = expandLevel(card, templates, 0, extras) ?: return null
        // button-card takes each level's `extra_styles` out as it merges (`_extraStyles`) and puts them all in the
        // card's <style>, in merge order. One merged config carries them as one sheet, unless one is JavaScript,
        // which has to stay on its own: then the island gets the last one only (a known loss, and it's rare).
        when {
            extras.isEmpty() -> result.remove("extra_styles")
            extras.size == 1 -> result.put("extra_styles", extras[0])
            extras.none { it is String && ButtonCardJs.isJs(it) } -> result.put("extra_styles", extras.joinToString("\n") { jsString(it).trim() })
            else -> result.put("extra_styles", extras.last())
        }
        return result
    }

    private fun expandLevel(card: JSONObject, templates: JSONObject, depth: Int, extras: MutableList<Any>): JSONObject? {
        if (depth > MAX_DEPTH) return null
        val tpl = card.opt("template")
        val result: JSONObject
        // `if (!tpl)`: no template, an empty name, `false` or `null` merge nothing.
        if (tpl == null || !truthy(tpl)) {
            result = JSONObject(card.toString())
        } else {
            val names = when (tpl) {
                is JSONArray -> (0 until tpl.length()).map { tpl.opt(it) }
                else -> listOf(tpl)
            }
            var merged = JSONObject()
            var mergedStates: JSONArray? = null
            for (n in names) {
                val name = n as? String ?: return null
                val t = templates.optJSONObject(name) ?: return null
                val res = expandLevel(t, templates, depth + 1, extras) ?: return null
                merged = mergeDeep(merged, res)
                mergedStates = mergeStatesById(mergedStates, res.optJSONArray("state"))
            }
            merged = mergeDeep(merged, JSONObject(card.toString()))
            merged.put("state", mergeStatesById(mergedStates, card.optJSONArray("state")))
            result = merged
        }
        // The merged config names no template any more: button-card's `template` is only read by this merge.
        result.remove("template")
        result.opt("extra_styles")?.takeIf { it != JSONObject.NULL && it != "" }?.let { extras += it }
        result.remove("extra_styles")
        return result
    }

    /**
     * button-card's `mergeDeep(prev, obj)`: [obj]'s keys over [prev]'s, two lists concatenated (`concat(...list)`,
     * which also flattens a list inside the list one level), two objects merged in turn. Returns a new object;
     * values are shared, as in JavaScript.
     */
    fun mergeDeep(prev: JSONObject, obj: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in prev.keys()) out.put(k, prev.opt(k))
        for (k in obj.keys()) {
            val p = out.opt(k)
            val o = obj.opt(k)
            out.put(k, when {
                p is JSONArray && o is JSONArray -> JSONArray().apply {
                    for (i in 0 until p.length()) put(p.opt(i))
                    for (i in 0 until o.length()) {
                        val e = o.opt(i)
                        if (e is JSONArray) for (j in 0 until e.length()) put(e.opt(j)) else put(e)
                    }
                }
                p is JSONObject && o is JSONObject -> mergeDeep(p, o)
                else -> o
            })
        }
        return out
    }

    /**
     * button-card's `mergeStatesById(into, from)`: each entry of [into] merged with the entries of [from] that have
     * the same truthy `id`, then the entries of [from] that match none of [into].
     */
    fun mergeStatesById(into: JSONArray?, from: JSONArray?): JSONArray {
        val out = JSONArray()
        fun idOf(o: Any?): Any? = (o as? JSONObject)?.opt("id")?.takeIf { truthy(it) }
        if (into != null) {
            for (i in 0 until into.length()) {
                var local = into.opt(i)
                if (from != null) {
                    for (j in 0 until from.length()) {
                        val f = from.opt(j)
                        val fid = idOf(f)
                        val iid = idOf(into.opt(i))
                        if (fid != null && iid != null && looseEquals(fid, iid) && local is JSONObject && f is JSONObject) {
                            local = mergeDeep(local, f)
                        }
                    }
                }
                out.put(local)
            }
        }
        if (from != null) {
            for (j in 0 until from.length()) {
                val f = from.opt(j)
                val fid = idOf(f)
                val matched = into != null && fid != null && (0 until into.length()).any { i ->
                    val iid = idOf(into.opt(i))
                    iid != null && looseEquals(iid, fid)
                }
                if (!matched) out.put(f)
            }
        }
        return out
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0 && !v.toDouble().isNaN()
        is String -> v.isNotEmpty()
        else -> true
    }
}

/** button-card's `[[[ … ]]]` values: JavaScript it runs with `states`, `entity`, `hass` and `variables`. */
object ButtonCardJs {
    private val TEMPLATE = Regex("""^(\[{3,})(.*?)(]{3,})$""", RegexOption.DOT_MATCHES_ALL)

    /** True when [s] is a JavaScript template: `[[[` and `]]]` exactly, around anything (`_getTemplateOrValue`). */
    fun isJs(s: String): Boolean {
        val m = TEMPLATE.matchEntire(s.trim()) ?: return false
        return m.groupValues[1].length == 3 && m.groupValues[3].length == 3
    }

    /**
     * A string as button-card shows it: `[[[[x]]]]` (as many brackets on each side, more than three) is an escape for
     * the text `[[[x]]]`; anything else is itself. Not for JavaScript templates ([isJs]), which a native card never draws.
     */
    fun literal(s: String): String {
        val trimmed = s.trim()
        val m = TEMPLATE.matchEntire(trimmed) ?: return s
        val open = m.groupValues[1].length
        return if (open > 3 && open == m.groupValues[3].length) trimmed.substring(1, trimmed.length - 1) else s
    }

    /** [v] (a config value) as button-card reads it: a string is [literal], anything else as it is. */
    fun value(v: Any?): Any? = if (v is String) literal(v) else v

    /**
     * The paths under [value] that hold a JavaScript template, `name` or `styles.card[0].color` style, in order.
     * `variables` is left out: button-card only evaluates a variable when JavaScript elsewhere reads it, or when
     * the variable says `force_eval`, which [forcedVariables] reports.
     */
    fun paths(value: Any?, path: String = "", out: MutableList<String> = ArrayList()): List<String> {
        when (value) {
            is String -> if (isJs(value)) out += path.ifEmpty { "(value)" }
            is JSONObject -> for (k in value.keys()) {
                if (path.isEmpty() && k == "variables") continue
                paths(value.opt(k), if (path.isEmpty()) k else "$path.$k", out)
            }
            is JSONArray -> for (i in 0 until value.length()) paths(value.opt(i), "$path[$i]", out)
        }
        return out
    }

    /** The `variables` that set `force_eval`: button-card evaluates them on every render, JavaScript or not. */
    fun forcedVariables(config: JSONObject): List<String> {
        val vars = config.optJSONObject("variables") ?: return emptyList()
        return vars.keys().asSequence().filter { k -> vars.optJSONObject(k)?.optBoolean("force_eval") == true }.toList()
    }
}

/**
 * button-card's `state:` list, matched as `_getMatchingConfigState` matches it: the first entry whose `operator`
 * (or `==` without one) holds against the entity's state, else the `default` entry. JavaScript's comparisons are
 * kept (`"10" < 9` compares numbers, two strings compare as text), so a list matches exactly what it matches in HA.
 * `template` operators are JavaScript, which a native card never meets ([dev.glassworks.engine.vitro.compat.CardCompat]).
 */
object ButtonCardStates {
    fun match(states: JSONArray?, state: EntityState?): JSONObject? {
        if (states == null || states.length() == 0) return null
        if (state == null) return null
        var def: JSONObject? = null
        for (i in 0 until states.length()) {
            val elt = states.optJSONObject(i) ?: continue
            val value = ButtonCardJs.value(elt.opt("value"))
            val op = elt.opt("operator")
            val hit = if (op != null && op != JSONObject.NULL && op != "") {
                when (op) {
                    "==" -> looseEquals(state.state, value)
                    "<=" -> compare(state.state, value) { it <= 0 }
                    "<" -> compare(state.state, value) { it < 0 }
                    ">=" -> compare(state.state, value) { it >= 0 }
                    ">" -> compare(state.state, value) { it > 0 }
                    "!=" -> !looseEquals(state.state, value)
                    "regex" -> regexMatch(state.state, value)
                    "default" -> { def = elt; false }
                    else -> false
                }
            } else {
                looseEquals(value, state.state)
            }
            if (hit) return elt
        }
        return def
    }

    /** `str.match(value)`: [value] becomes a regular expression (`new RegExp(value)`); one Java can't read never matches. */
    private fun regexMatch(s: String, value: Any?): Boolean {
        val pattern = jsString(value)
        return runCatching { Regex(pattern).containsMatchIn(s) }.getOrDefault(false)
    }

    /**
     * JavaScript's `a OP b` for a state string [a] and a config value [b]: two strings compare by UTF-16 code unit,
     * anything else as numbers (NaN never holds).
     */
    private fun compare(a: String, b: Any?, ok: (Int) -> Boolean): Boolean {
        val pb = primitive(b)
        if (pb is String) return ok(a.compareTo(pb).coerceIn(-1, 1))
        val x = VisibilityCondition.jsToNumber(a) ?: return false
        val y = VisibilityCondition.jsToNumber(pb) ?: return false
        return ok(x.compareTo(y))
    }
}

/** JavaScript's `ToPrimitive` for a JSON value: a list joins with commas, an object is `[object Object]`. */
private fun primitive(v: Any?): Any? = when (v) {
    is JSONArray -> (0 until v.length()).joinToString(",") { i -> v.opt(i).let { e -> if (e == null || e == JSONObject.NULL) "" else jsString(e) } }
    is JSONObject -> "[object Object]"
    JSONObject.NULL -> null
    else -> v
}

/** JavaScript's `String(v)` for a JSON value. */
internal fun jsString(v: Any?): String = when (val p = primitive(v)) {
    null -> "null"
    is Double -> VisibilityCondition.jsNumber(p)
    is Float -> VisibilityCondition.jsNumber(p.toDouble())
    is Number -> p.toString()
    else -> p.toString()
}

/** JavaScript's loose `a == b` for two JSON values (a string, a number, a boolean, null, a list or an object). */
internal fun looseEquals(a: Any?, b: Any?): Boolean {
    val x = if (a == JSONObject.NULL) null else a
    val y = if (b == JSONObject.NULL) null else b
    if (x == null || y == null) return x == null && y == null
    // Two objects are equal only when they are the same object.
    if ((x is JSONObject || x is JSONArray) && (y is JSONObject || y is JSONArray)) return x === y
    val px = primitive(x)
    val py = primitive(y)
    if (px is String && py is String) return px == py
    if (px is Boolean && py is Boolean) return px == py
    val nx = VisibilityCondition.jsToNumber(px) ?: return false
    val ny = VisibilityCondition.jsToNumber(py) ?: return false
    return nx == ny
}
