/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.compat.NativeOptions
import dev.glassworks.engine.vitro.compat.VitroSupport
import dev.glassworks.engine.vitro.compat.WebFallback
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.parity.ParityCase
import dev.glassworks.engine.vitro.parity.ParityFiles
import dev.glassworks.engine.vitro.parity.ParityRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strict compat rule (#799 item 0g): a native card claims a config only when it draws every option and value in
 * it as HA does ([NativeOptions], `native-options.json`). Anything else goes to an island (a custom card) or HA's
 * frontend (a built-in card, or a custom card holding built-in ones, [WebFallback]). The cases in
 * `native-options-cases.json` are the auditors' findings and plain configs that must stay native; the site's checker
 * reads the same file (vitroCompat.test.ts), so the two can't route a config differently.
 */
class NativeOptionsTest {
    private val cases = JSONObject(
        javaClass.getResourceAsStream("/dev/glassworks/engine/vitro/compat/native-options-cases.json")!!.use { it.readBytes().toString(Charsets.UTF_8) },
    )

    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }

    /** The route Vitro takes for a view, and its first reason: HA's frontend, an island (every script a resource), or native. */
    private fun route(viewRaw: JSONObject): Pair<String, String?> {
        val view = LovelaceParser.parseView(0, viewRaw)
        WebFallback.viewReason(view)?.let { return "frontend" to it }
        fun island(c: CardConfig): CardConfig? =
            if (c.inIsland(IslandScripts.ANY)) c else CardRouter.producedCards(c).firstNotNullOfOrNull { island(it.value) }
        val found = CardRouter.viewRoots(view).firstNotNullOfOrNull { island(it.card) }
        return if (found != null) "island" to found.compat.reasons.first() else "native" to null
    }

    private fun checkCase(what: String, viewRaw: JSONObject, case: JSONObject) {
        val (route, reason) = route(viewRaw)
        assertEquals("$what: $reason", case.getString("route"), route)
        if (case.has("reason")) assertEquals(what, case.getString("reason"), reason)
    }

    @Test
    fun eachFindingFallsBackAndEachPlainConfigStaysNative() {
        for (c in objects(cases.getJSONArray("cards"))) {
            checkCase(c.getString("what"), JSONObject().put("cards", JSONArray().put(c.getJSONObject("card"))), c)
        }
        for (c in objects(cases.getJSONArray("views"))) checkCase(c.getString("what"), c.getJSONObject("view"), c)
    }

    /** No regression in native coverage: every committed parity case routes where it says, `native` unless it says not. */
    @Test
    fun everyParityCorpusCaseRoutesWhereItSays() {
        val wrong = ParityCase.loadAll(ParityFiles.committed).mapNotNull { case ->
            val (route, why) = ParityRoute.of(case, ParityFiles.committed) ?: return@mapNotNull null
            if (route == case.route) null else "${case.id}: routes `$route` (${why}), the case says `${case.route}`"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun everyCardVitroDrawsIsDeclaredAndOnlyThose() {
        // button-card has its own, finer checker (ButtonCardCompat).
        val expected = VitroSupport.CORE_CARD_TYPES + VitroSupport.HACS_CARD_TYPES - "custom:button-card"
        assertEquals(expected.sorted(), NativeOptions.declaredTypes.sorted())
    }

    /** Every spec in the file is one the checker reads, and every `@def` resolves: a typo can't pass every config. */
    @Test
    fun theDeclarationIsWellFormed() {
        val schema = NativeOptions.schema
        val defs = schema.getJSONObject("defs")
        val forms = setOf("values", "pattern", "range", "entity", "keys", "list", "anyOf", "by", "gap")
        fun walk(spec: Any?, at: String) {
            when (spec) {
                "any", "text" -> Unit
                is String -> {
                    assertTrue("$at: `$spec`", spec.startsWith("@"))
                    assertTrue("$at: no def `$spec`", defs.has(spec.substring(1)))
                }
                is JSONObject -> {
                    val form = spec.keys().asSequence().filter { it in forms }.toList()
                    assertEquals("$at: one form per spec, not $form", 1, form.size)
                    val extra = spec.keys().asSequence().filter { it !in forms && it != "why" && it != "note" && !(form.single() == "by" && it in setOf("default", "cases")) && !(form.single() == "keys" && it == "required") }.toList()
                    assertTrue("$at: unknown fields $extra", extra.isEmpty())
                    when (form.single()) {
                        "keys" -> spec.getJSONObject("keys").let { k ->
                            k.keys().forEach { walk(k.get(it), "$at.$it") }
                            spec.optJSONArray("required")?.let { r -> (0 until r.length()).forEach { i -> assertTrue("$at: required `${r.get(i)}` isn't a key", k.has(r.getString(i))) } }
                        }
                        "list" -> walk(spec.get("list"), "$at[]")
                        "anyOf" -> spec.getJSONArray("anyOf").let { a -> (0 until a.length()).forEach { walk(a.get(it), "$at|$it") } }
                        "by" -> spec.getJSONObject("cases").let { c -> c.keys().forEach { walk(c.get(it), "$at<$it>") } }
                        "pattern" -> Regex(spec.getString("pattern"))
                        "range" -> assertEquals("$at: [min, max]", 2, spec.getJSONArray("range").length())
                        "values", "entity" -> assertTrue("$at: a list", spec.get(form.single()) is JSONArray)
                        "gap" -> assertTrue("$at: why", spec.getString("gap").isNotBlank())
                    }
                }
                else -> throw AssertionError("$at: not a spec: $spec")
            }
        }
        defs.keys().forEach { walk(defs.get(it), "defs.$it") }
        schema.getJSONObject("common").let { c -> c.keys().forEach { walk(c.get(it), "common.$it") } }
        schema.getJSONObject("cards").let { c -> c.keys().forEach { walk(c.get(it), it) } }
        walk(schema.get("view"), "view")
    }

    /** Reasons go to logcat and the HUD: an entity id, a path, a URL or a template never shows in one. */
    @Test
    fun reasonsNameKeysAndKindsNeverTheDashboardsText() {
        val card = JSONObject(
            """{"type": "tile", "entity": "light.secret_room", "color": "red",
                "tap_action": {"action": "navigate", "navigation_path": "relative/secret-room"},
                "features": [{"type": "https://example.test/x?token=abc"}], "secret_key{{ x }}": 1}""",
        )
        val reasons = NativeOptions.cardReasons("tile", card)
        assertTrue(reasons.toString(), reasons.size >= 3)
        for (r in reasons) for (secret in listOf("secret", "example.test", "token", "{{")) assertFalse(r, secret in r)
        val rows = NativeOptions.cardReasons("entities", JSONObject("""{"type": "entities", "entities": ["timer.secret_door"]}"""))
        assertTrue(rows.toString(), rows.single().contains("`timer` entity") && "secret" !in rows.single())
    }

    @Test
    fun aRangeTakesFiniteNumbersOnlyAndARequiredKeyMustBeThere() {
        assertTrue(NativeOptions.cardReasons("logbook", JSONObject("""{"type": "logbook", "hours_to_show": "NaN"}""")).isNotEmpty())
        assertTrue(NativeOptions.cardReasons("logbook", JSONObject("""{"type": "logbook", "hours_to_show": 24}""")).isEmpty())
        // stack-in-card keeps a child's rounded border only with its background: an omitted background is `false`.
        fun keep(k: String) = NativeOptions.cardReasons("custom:stack-in-card", JSONObject("""{"type": "custom:stack-in-card", "cards": [], "keep": $k}"""))
        assertEquals(keep("""{"border_radius": true}""").isEmpty(), keep("""{"background": false, "border_radius": true}""").isEmpty())
        assertTrue(keep("""{"background": true, "border_radius": true}""").isEmpty())
        // An auto-entities entry without `entity` (a divider row) is one Vitro drops.
        val entry = NativeOptions.cardReasons("custom:auto-entities", JSONObject("""{"type": "custom:auto-entities", "entities": [{"type": "divider"}]}"""))
        assertTrue(entry.toString(), entry.any { "entities[0].entity` (missing)" in it })
    }

    @Test
    fun aNullValueIsAnAbsentOption() {
        assertTrue(NativeOptions.cardReasons("tile", JSONObject("""{"type": "tile", "entity": "light.a", "color": null}""")).isEmpty())
        assertNull(WebFallback.cardReason(LovelaceParser.parseCard(JSONObject("""{"type": "tile", "entity": "light.a", "double_tap_action": null}"""))))
    }
}
