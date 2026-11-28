/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import org.json.JSONArray
import org.json.JSONObject

/**
 * The strict compat rule: a card Vitro draws natively claims a config only when it draws every option and value in it
 * the way Home Assistant (or the custom card) does. Anything else falls back: a custom card to its island
 * ([CardCompat]), a built-in card, or a custom card that holds built-in ones, to HA's frontend ([WebFallback]).
 *
 * What each card supports is data, in `native-options.json` next to this class, so the engine, the site's checker
 * (`site/src/lib/vitroCompat.ts`) and the audit script read one declaration, and this object is the only code that
 * reads it. A card type with no declaration (button-card, which [ButtonCardCompat] checks) isn't checked here.
 *
 * The file holds `defs` (shared specs), `common` (keys every card accepts), `cards` (a spec per card type), and `view`,
 * `section` and `badge`. A spec is one of:
 * - `"any"`: every value is drawn as HA draws it.
 * - `"text"`: a string, number or boolean, never an object or a list.
 * - `"@name"`: the spec `defs.name`.
 * - `{"values": [...]}`: one of these JSON scalars.
 * - `{"pattern": "regex"}`: a string the expression finds a match in.
 * - `{"range": [min, max]}`: a finite number in the range (a null bound is open), or text that is one in plain decimal
 *   notation.
 * - `{"entity": ["light", ...]}`: an entity id of one of these domains (`"*"`: any).
 * - `{"keys": {"key": spec}, "required": ["key"]}`: an object; a key the spec doesn't list isn't drawn, and neither is
 *   an object without a `required` key. A null value counts as absent.
 * - `{"list": spec}`: a list whose items each pass `spec` (a single value that isn't a list is checked as one item).
 * - `{"anyOf": [spec, ...]}`: passes when one of them does.
 * - `{"by": "field", "default": "value", "cases": {"value": spec}}`: an object whose `field` (or `default`, when it
 *   has none) picks the case that checks it; a value with no case isn't drawn.
 * - `{"gap": "why"}`: never drawn exactly, so it always falls back.
 *
 * Any spec object may carry `"why"`, a short reason the checker quotes, and `"note"`, the longer evidence for whoever
 * edits the file, which nothing shows. Reasons go to logcat and the HUD, so they name keys and enum-like values only:
 * never a path, a URL, a template or an entity id (an entity is named by its domain).
 */
object NativeOptions {
    private const val RESOURCE = "/dev/glassworks/engine/vitro/compat/native-options.json"

    /** The declaration, read once. */
    internal val schema: JSONObject by lazy {
        val text = NativeOptions::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("$RESOURCE is missing from the engine")
        JSONObject(text)
    }

    private val defs: JSONObject get() = schema.getJSONObject("defs")
    private val common: JSONObject get() = schema.getJSONObject("common")

    /** The card types with a declaration. */
    val declaredTypes: Set<String> get() = schema.getJSONObject("cards").keys().asSequence().toSet()

    /**
     * Why [raw], a `type` card's config, isn't drawn exactly natively, in the config's order: empty when every option
     * and value in it is one the native card draws. Empty for a type with no declaration.
     */
    fun cardReasons(type: String, raw: JSONObject): List<String> {
        val spec = schema.getJSONObject("cards").opt(type) ?: return emptyList()
        return Walk("`$type`").apply { check(raw, spec, "", common) }.reasons.toList()
    }

    /**
     * Why a view's own config ([raw]: its keys, sections and badges; its cards are checked one by one) isn't drawn
     * exactly natively.
     */
    fun viewReasons(raw: JSONObject): List<String> =
        Walk("the view").apply { check(raw, schema.get("view"), "", null) }.reasons.toList()

    /** One check: the reasons it found, worded for [subject] (the card's type, or the view). */
    private class Walk(val subject: String) {
        val reasons = LinkedHashSet<String>()

        private fun fail(path: String, detail: String? = null, why: String? = null) {
            val where = if (path.isEmpty()) "" else " `$path`"
            reasons += "$subject$where" + (detail?.let { " ($it)" } ?: "") + " isn't drawn natively" + (why?.let { ": $it" } ?: "")
        }

        private fun resolve(spec: Any?): Any? {
            var s = spec
            var hops = 0
            while (s is String && s.startsWith("@")) {
                s = defs.opt(s.substring(1)) ?: error("native-options.json: no def ${s.substring(1)}")
                if (++hops > 16) error("native-options.json: a def refers to itself")
            }
            return s
        }

        /** Checks [value] (never null or JSON null: the caller skips those) against [spec] at [path]. */
        fun check(value: Any, rawSpec: Any?, path: String, extra: JSONObject?) {
            when (val spec = resolve(rawSpec)) {
                "any" -> Unit
                "text" -> if (value is JSONObject || value is JSONArray) fail(path, "an object or a list")
                is JSONObject -> checkObject(value, spec, path, extra)
                else -> error("native-options.json: unknown spec $spec at $path")
            }
        }

        private fun checkObject(value: Any, spec: JSONObject, path: String, extra: JSONObject?) {
            val why = spec.optString("why", "").ifEmpty { null }
            when {
                spec.has("gap") -> fail(path, why = spec.getString("gap"))
                spec.has("values") -> {
                    val allowed = spec.getJSONArray("values")
                    if ((0 until allowed.length()).none { same(allowed.opt(it), value) }) fail(join(path, shown(value)), why = why)
                }
                spec.has("pattern") -> {
                    if (value !is String || !Regex(spec.getString("pattern")).containsMatchIn(value)) fail(path, why = why)
                }
                spec.has("range") -> {
                    val r = spec.getJSONArray("range")
                    val n = (value as? Number)?.toDouble() ?: (value as? String)?.takeIf { NUMBER.matches(it) }?.trim()?.toDoubleOrNull()
                    val ok = n != null && n.isFinite() && (r.isNull(0) || n >= r.getDouble(0)) && (r.isNull(1) || n <= r.getDouble(1))
                    // Never the value itself: a number that isn't one may be any text of the dashboard's.
                    if (!ok) fail(path, "not a number in range", why)
                }
                spec.has("entity") -> {
                    val domains = spec.getJSONArray("entity")
                    val domain = (value as? String)?.substringBefore('.', "")?.trim().orEmpty()
                    val ok = domain.isNotEmpty() && (0 until domains.length()).any { domains.optString(it) == "*" || domains.optString(it) == domain }
                    if (!ok) fail(path, if (domain.isNotEmpty() && SAFE.matches(domain)) "a `$domain` entity" else "this entity", why)
                }
                spec.has("keys") -> {
                    val obj = value as? JSONObject ?: return fail(path, "not an object", why)
                    val keys = spec.getJSONObject("keys")
                    spec.optJSONArray("required")?.let { req ->
                        for (i in 0 until req.length()) {
                            val k = req.getString(i)
                            if (!obj.has(k) || obj.isNull(k)) fail(if (path.isEmpty()) k else "$path.$k", "missing", why)
                        }
                    }
                    for (k in obj.keys()) {
                        if (obj.isNull(k)) continue
                        val sub = keys.opt(k) ?: extra?.opt(k)
                        val at = if (path.isEmpty()) k else "$path.$k"
                        if (sub == null) fail(if (SAFE.matches(k)) at else join(path, "an option"), why = why)
                        else check(obj.get(k), sub, at, null)
                    }
                }
                spec.has("list") -> {
                    val item = spec.get("list")
                    if (value is JSONArray) {
                        for (i in 0 until value.length()) if (!value.isNull(i)) check(value.get(i), item, "$path[$i]", null)
                    } else {
                        check(value, item, path, null)
                    }
                }
                spec.has("anyOf") -> {
                    val options = spec.getJSONArray("anyOf")
                    val tries = (0 until options.length()).map { i -> Walk(subject).also { it.check(value, options.get(i), path, extra) } }
                    if (tries.any { it.reasons.isEmpty() }) return
                    // The option meant for this shape of value says best what's wrong with it.
                    val best = (0 until options.length()).firstOrNull { fits(value, options.get(it)) } ?: 0
                    reasons += tries[best].reasons
                }
                spec.has("by") -> {
                    val obj = value as? JSONObject ?: return fail(path, "not an object", why)
                    val field = spec.getString("by")
                    val picked = obj.opt(field).takeUnless { it == null || it == JSONObject.NULL }?.toString()
                        ?: spec.optString("default", "")
                    val case = spec.getJSONObject("cases").opt(picked)
                    if (case == null) fail(join(if (path.isEmpty()) field else "$path.$field", shown(picked)), why = why)
                    else check(obj, case, path, extra)
                }
                else -> error("native-options.json: unknown spec $spec at $path")
            }
        }

        /** Whether [spec] is meant for a value shaped like [value] (an object, a list, or a scalar). */
        private fun fits(value: Any, spec: Any?): Boolean = when (val s = resolve(spec)) {
            "any" -> true
            "text" -> value !is JSONObject && value !is JSONArray
            is JSONObject -> when {
                s.has("keys") || s.has("by") -> value is JSONObject
                s.has("list") -> value is JSONArray
                s.has("anyOf") -> s.getJSONArray("anyOf").let { a -> (0 until a.length()).any { fits(value, a.get(it)) } }
                else -> value !is JSONObject && value !is JSONArray
            }
            else -> false
        }
    }

    /** A number written as text, as all three checkers read one (no hex, `NaN`, `Infinity` or `_`). */
    private val NUMBER = Regex("""^\s*[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?\s*$""")

    /** Values that can be named in a reason: a key, a type, an enum value. Never a path, a URL or an entity id. */
    private val SAFE = Regex("""^[A-Za-z0-9_:+\-]{1,48}$""")

    private fun shown(value: Any?): String? = when (value) {
        is Boolean -> value.toString()
        is Number -> value.toString().takeIf { it.length <= 24 }
        is String -> value.takeIf { SAFE.matches(it) }
        else -> null
    }

    private fun join(path: String, value: String?): String = when {
        value == null -> path
        path.isEmpty() -> value
        else -> "$path: $value"
    }

    /** JSON equality for the scalars a `values` list holds: numbers by value, so `1` and `1.0` are one. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}
