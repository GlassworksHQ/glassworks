/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro.compat

import dev.glassworks.engine.vitro.cards.ButtonCardCss
import dev.glassworks.engine.vitro.cards.ButtonCardJs
import dev.glassworks.engine.vitro.cards.ButtonCardTemplates
import dev.glassworks.engine.vitro.cards.CssGridSpec
import org.json.JSONArray
import org.json.JSONObject


/**
 * Which `custom:button-card` configs Vitro draws natively. Its JavaScript templates (`[[[ … ]]]`) go to an island on
 * purpose, as do cards inside `custom_fields`, HTML in its texts and anything in [VitroSupport]'s button-card lists it
 * doesn't draw. The config reaching here has its `button_card_templates` merged already
 * ([ButtonCardTemplates.expandDashboard]): a `template:` left in it is one the dashboard doesn't define.
 */
internal object ButtonCardCompat {
    fun check(raw: JSONObject, reasons: MutableSet<String>, notes: MutableSet<String>) {
        raw.opt("template")?.takeIf { it != JSONObject.NULL && it != "" && it != false }?.let {
            reasons += "button-card `template:` names a template that isn't in the dashboard's `button_card_templates`"
        }
        val js = ButtonCardJs.paths(raw)
        if (js.isNotEmpty()) {
            // Only the top-level option holding each one, and only a name button-card reads: never a value or a key
            // the dashboard made up.
            val options = js.map { optionName(it) }.distinct()
            reasons += "button-card JavaScript templates (`[[[ ]]]`) in " + options.take(3).joinToString(", ") { "`$it`" } +
                (if (options.size > 3) " and ${options.size - 3} more" else "")
        }
        ButtonCardJs.forcedVariables(raw).takeIf { it.isNotEmpty() }?.let { v ->
            reasons += "button-card `variables` with `force_eval` (${v.size})"
        }
        for ((key, what) in VitroSupport.BUTTON_CARD_UNSUPPORTED_OPTIONS) {
            if (!raw.has(key)) continue
            val v = raw.opt(key)
            val on = when {
                key.endsWith("_action") -> actionDoesSomething(v)
                else -> truthy(v)
            }
            if (on) reasons += "button-card `$key:` ($what) isn't drawn natively"
        }
        for ((key, why) in VitroSupport.BUTTON_CARD_IGNORED_OPTIONS) {
            if (key == "tooltip" && truthy(raw.opt(key))) notes += "button-card `tooltip:` is ignored: $why"
        }
        checkTimer(raw, reasons)
        checkLayout(raw, reasons)
        checkTexts(raw, null, reasons)
        checkActions(raw, reasons)
        checkLock(raw, reasons)
        checkCustomFields(raw, "custom_fields", reasons)
        checkStyles(raw.opt("styles"), "styles", reasons, notes)
        val states = raw.opt("state")
        if (states != null && states != JSONObject.NULL) {
            if (states !is JSONArray) {
                reasons += "button-card `state:` isn't a list"
            } else {
                for (i in 0 until states.length()) {
                    val s = states.optJSONObject(i) ?: continue
                    val op = s.optString("operator", "")
                    if (op == "template") continue // JavaScript, reported above
                    if (op.isNotEmpty() && op !in STATE_OPERATORS) reasons += "button-card `state[$i].operator` isn't one it knows"
                    checkTexts(s, "state[$i].", reasons)
                    checkCustomFields(s, "state[$i].custom_fields", reasons)
                    checkStyles(s.opt("styles"), "state[$i].styles", reasons, notes)
                    if (s.has("entity_picture")) checkPicture(s.opt("entity_picture"), "state[$i].entity_picture", reasons)
                }
            }
        }
    }

    /**
     * The button-card option a JavaScript path starts with (`styles.card[0].color` is `styles`), or "an option" for a
     * key button-card doesn't read: diagnostics name options, never the dashboard's own keys or values.
     */
    private fun optionName(path: String): String {
        val top = path.substringBefore('.').substringBefore('[')
        return if (top in KNOWN_OPTIONS) top else "an option"
    }

    private val KNOWN_OPTIONS = setOf(
        "entity", "name", "icon", "color", "color_type", "size", "aspect_ratio", "layout", "label", "state_display",
        "entity_picture", "units", "numeric_precision", "styles", "state", "custom_fields", "tap_action", "hold_action",
        "double_tap_action", "press_action", "release_action", "icon_tap_action", "icon_hold_action",
        "icon_double_tap_action", "icon_press_action", "icon_release_action", "lock", "hidden", "rotate", "spinner",
        "tooltip", "confirmation", "protect", "update_timer", "extra_styles", "show_name", "show_state", "show_icon",
        "show_label", "show_units", "show_entity_picture", "show_last_changed", "show_live_stream", "show_ripple",
        "card_size", "section_mode", "group_expand", "triggers_update", "variables", "template",
    )

    private val STATE_OPERATORS = setOf("==", "<=", "<", ">=", ">", "!=", "regex", "template", "default")

    /**
     * A timer's state: button-card counts it down every second (`_buildStateString`: `secondsToDuration` of the time
     * remaining, with "(Paused)" after it while paused), which the native card doesn't draw. Only when the card shows
     * its state and no `state_display` of its own replaces it.
     */
    private fun checkTimer(raw: JSONObject, reasons: MutableSet<String>) {
        val entity = raw.opt("entity") as? String ?: return
        if (entity.substringBefore('.') != "timer" || !truthy(raw.opt("show_state"))) return
        if ((raw.opt("state_display") as? String)?.isNotEmpty() == true) return
        reasons += "button-card `show_state:` on a timer (its countdown) isn't drawn natively"
    }

    private fun checkLayout(raw: JSONObject, reasons: MutableSet<String>) {
        raw.opt("layout")?.takeIf { it != JSONObject.NULL }?.let { l ->
            if (l !is String || l !in VitroSupport.BUTTON_CARD_LAYOUTS) reasons += "button-card `layout:` isn't one Vitro draws natively"
        }
        raw.opt("aspect_ratio")?.takeIf { it != JSONObject.NULL && it != "" }?.let { a ->
            if (aspectRatio(a) == null) reasons += "button-card `aspect_ratio:` isn't one Vitro reads"
        }
        raw.opt("size")?.takeIf { it != JSONObject.NULL }?.let { s ->
            if (s !is String || ButtonCardCss.length(s, auto = true) == null) reasons += "button-card `size:` isn't a length Vitro reads"
        }
        if (raw.has("entity_picture")) checkPicture(raw.opt("entity_picture"), "entity_picture", reasons)
    }

    /** `aspect_ratio`, as `calc(100% / (<it>))` reads it: a number, or `a/b`. */
    fun aspectRatio(v: Any?): Float? {
        if (v is Number) return v.toFloat().takeIf { it > 0f }
        val s = (v as? String)?.trim() ?: return null
        s.toFloatOrNull()?.let { return it.takeIf { r -> r > 0f } }
        val parts = s.split('/')
        if (parts.size != 2) return null
        val a = parts[0].trim().toFloatOrNull() ?: return null
        val b = parts[1].trim().toFloatOrNull() ?: return null
        return (a / b).takeIf { b > 0f && it > 0f && it.isFinite() }
    }

    private fun checkPicture(v: Any?, where: String, reasons: MutableSet<String>) {
        val s = v as? String ?: return
        if (s.startsWith("media-source://")) reasons += "button-card `$where` from the media browser isn't drawn natively"
    }

    /** Names, labels and states show as HTML in button-card (`unsafeHTML`): text only is drawn natively. */
    private fun checkTexts(o: JSONObject, prefix: String?, reasons: MutableSet<String>) {
        for (k in listOf("name", "label", "state_display")) {
            val v = o.opt(k) as? String ?: continue
            if (ButtonCardJs.isJs(v)) continue
            if (isHtml(v)) reasons += "button-card `${prefix.orEmpty()}$k` holds HTML"
        }
    }

    private val TAG = Regex("""<[a-zA-Z/!]""")
    private val ENTITY_REF = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")

    /** HTML the browser would parse: a tag, or a character reference Vitro doesn't decode. */
    fun isHtml(s: String): Boolean = TAG.containsMatchIn(s) ||
        ENTITY_REF.findAll(s).any { decodeEntity(it.value) == null }

    /** `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;`, `&nbsp;` and numeric references, as the browser decodes them. */
    fun decodeEntity(ref: String): String? {
        val body = ref.removePrefix("&").removeSuffix(";")
        return when {
            body.startsWith("#x", ignoreCase = true) -> body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "deg" to "°")[body]
        }
    }

    fun decodeText(s: String): String = ENTITY_REF.replace(s) { m -> decodeEntity(m.value) ?: m.value }

    private fun checkCustomFields(o: JSONObject, where: String, reasons: MutableSet<String>) {
        val fields = o.optJSONObject("custom_fields") ?: return
        for (k in fields.keys()) {
            when (val v = fields.opt(k)) {
                is JSONObject -> if (v.has("card")) reasons += "button-card `$where` holds a card" else reasons += "button-card `$where` holds something other than text"
                is String -> if (!ButtonCardJs.isJs(v) && isHtml(v)) reasons += "button-card `$where` holds HTML"
                is JSONArray -> reasons += "button-card `$where` holds something other than text"
            }
            if (!CssGridSpec.IDENT.matches(k)) reasons += "button-card `$where` has a field name that isn't a grid area name"
        }
    }

    private fun checkActions(raw: JSONObject, reasons: MutableSet<String>) {
        for (key in listOf("tap_action", "hold_action", "double_tap_action")) {
            val a = raw.opt(key) as? JSONObject ?: continue
            val type = a.optString("action", "")
            if (type.isEmpty() || type == "none") continue
            if (type !in VitroSupport.BUTTON_CARD_ACTIONS) reasons += "button-card `$key` has an action type Vitro doesn't run natively"
            for ((f, what) in VitroSupport.BUTTON_CARD_UNSUPPORTED_ACTION_FIELDS) {
                if (a.has(f) && truthy(a.opt(f))) reasons += "button-card `$key.$f` ($what) isn't run natively"
            }
            if ((a.opt("confirmation") as? JSONObject)?.has("exemptions") == true) {
                reasons += "button-card `$key.confirmation.exemptions` isn't run natively"
            }
        }
        if ((raw.opt("confirmation") as? JSONObject)?.has("exemptions") == true) {
            reasons += "button-card `confirmation.exemptions` isn't run natively"
        }
    }

    private fun checkLock(raw: JSONObject, reasons: MutableSet<String>) {
        val lock = raw.optJSONObject("lock") ?: return
        val unlock = lock.optString("unlock", "tap")
        if (unlock !in setOf("tap", "hold", "double_tap")) reasons += "button-card `lock.unlock` isn't a gesture Vitro runs natively"
    }

    /** Every style part and declaration: one Vitro doesn't draw is a reason, with where it is. */
    private fun checkStyles(styles: Any?, where: String, reasons: MutableSet<String>, notes: MutableSet<String>) {
        if (styles == null || styles == JSONObject.NULL) return
        if (styles !is JSONObject) {
            reasons += "button-card `$where` isn't a map of style lists"
            return
        }
        for (part in styles.keys()) {
            if (part !in VitroSupport.BUTTON_CARD_STYLE_PARTS) continue // button-card never reads it
            if (part == "tooltip" || part == "spinner") continue // only with a tooltip or a spinner, which are handled above
            if (part == "custom_fields") {
                val fields = styles.optJSONObject(part) ?: continue
                // A field's name is the dashboard's own text: never in a reason.
                for (f in fields.keys()) checkList(fields.opt(f), "custom_field", "$where.custom_fields", reasons, notes)
                continue
            }
            checkList(styles.opt(part), part, "$where.$part", reasons, notes)
        }
    }

    private fun checkList(list: Any?, part: String, where: String, reasons: MutableSet<String>, notes: MutableSet<String>) {
        val decls = ButtonCardCss.declarations(list)
        if (decls == null) {
            reasons += "button-card `$where` isn't a list of styles"
            return
        }
        for (d in decls) {
            if (ButtonCardJs.isJs(d.value)) continue
            val problem = ButtonCardCss.problem(part, d) ?: run {
                if (d.name.startsWith("--") && part != "card") notes += "button-card `$where` sets a custom property: known gap, " +
                    (VitroSupport.KNOWN_GAP_PROPERTIES["card variable"] ?: "")
                null
            } ?: continue
            reasons += "button-card `$where` sets $problem"
        }
    }

    private fun actionDoesSomething(v: Any?): Boolean {
        val a = v as? JSONObject ?: return v is String && v.isNotEmpty()
        val t = a.opt("action")
        return t != null && t != JSONObject.NULL && t != "none" && t != ""
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }
}
