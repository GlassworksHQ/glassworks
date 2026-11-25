/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.compat.CompatWarmup
import dev.glassworks.engine.vitro.compat.IslandScripts
import dev.glassworks.engine.vitro.model.CardConfig
import dev.glassworks.engine.vitro.model.CardRouter
import dev.glassworks.engine.vitro.model.DashboardConfig
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** [CompatWarmup]: the cards it routes in the background get the answer the build would compute itself. */
class CompatWarmupTest {
    /** Every committed parity case's card, and cards that take the island and container paths. */
    private val dashboard: String by lazy {
        val cards = JSONArray()
        File("src/test/parity/corpus").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.forEach { f ->
            JSONObject(f.readText()).optJSONObject("card")?.let { cards.put(it) }
        }
        listOf(
            """{"type": "custom:button-card", "entity": "light.lamp"}""",
            """{"type": "custom:mushroom-template-card", "primary": "A", "card_mod": {"style": "ha-card { animation: spin 1s; }"}}""",
            """{"type": "custom:mushroom-template-card", "primary": "B", "card_mod": {"style": {"mushroom-shape-icon$": ".shape { --x: 1; }"}}}""",
            """{"type": "custom:stack-in-card", "cards": [{"type": "custom:mushroom-chips-card", "chips": []}, {"type": "tile", "entity": "light.lamp"}]}""",
            """{"type": "conditional", "conditions": [], "card": {"type": "custom:mini-media-player", "entity": "media_player.x"}}""",
            """{"type": "custom:auto-entities", "card": {"type": "entities"}, "filter": {"include": [{"domain": "light"}]}}""",
        ).forEach { cards.put(JSONObject(it)) }
        JSONObject().put("views", JSONArray()
            .put(JSONObject().put("path", "home").put("type", "sections").put("sections", JSONArray().put(JSONObject().put("type", "grid").put("cards", cards))))
            .put(JSONObject().put("path", "other").put("cards", JSONArray(cards.toString()))),
        ).toString()
    }

    private fun parse(): DashboardConfig = LovelaceParser.parseDashboard(JSONObject(dashboard))

    /** Every card of [config], containers' cards included, in a fixed order. */
    private fun cards(config: DashboardConfig): List<CardConfig> {
        val out = ArrayList<CardConfig>()
        fun walk(c: CardConfig) { out += c; c.childCards.forEach(::walk) }
        config.views.forEach { v -> CardRouter.viewRoots(v).forEach { walk(it.card) } }
        return out
    }

    private val scriptSets = listOf(
        IslandScripts.ANY,
        IslandScripts.of(emptyList()),
        IslandScripts.of(listOf(LovelaceResource("/hacsfiles/lovelace-mushroom/mushroom.js", "module"))),
        IslandScripts.of(listOf(LovelaceResource("/hacsfiles/lovelace-card-mod/card-mod.js", "module"), LovelaceResource("/hacsfiles/button-card/button-card.js", "module"))),
    )

    @Test
    fun aWarmedCardRoutesExactlyAsTheBuildWould() {
        val warmed = parse()
        CompatWarmup.start(warmed, "other").get(30, TimeUnit.SECONDS)
        val warm = cards(warmed)
        val fresh = cards(parse())
        assertEquals(fresh.size, warm.size)
        assertTrue("the dashboard holds cards of every kind: ${warm.size}", warm.size > 70)
        var islands = 0
        for ((w, f) in warm.zip(fresh)) {
            assertEquals(f.type, w.type)
            for (s in scriptSets) assertEquals("${w.type} with $s", f.inIsland(s), w.inIsland(s))
            if (w.inIsland()) islands++
            assertEquals(w.type, f.compat, w.compat)
        }
        assertTrue("some cards go to an island, so both paths were compared: $islands", islands > 0)
    }

    @Test
    fun theBuildReadsTheVerdictTheWarmUpComputed() {
        val config = parse()
        val running = CompatWarmup.start(config, null)
        // The UI thread routes while the warm-up runs: one verdict per card, whichever thread got there first.
        val seen = cards(config).map { it.compat }
        running.get(30, TimeUnit.SECONDS)
        cards(config).zip(seen).forEach { (c, v) -> assertSame(c.type, v, c.compat) }
    }

    @Test
    fun theWarmUpComputesAVerdictBeforeAnythingReadsIt() {
        val config = parse()
        // A card the warm-up must check (it isn't native-only): its verdict is there once the future is done.
        val island = cards(config).first { it.type == "custom:button-card" }
        assertFalse(island.compatComputed)
        CompatWarmup.start(config, null).get(30, TimeUnit.SECONDS)
        assertTrue("computed by the warm-up, not by this test", island.compatComputed)
        val seen = island.compat
        assertSame(seen, island.compat)
    }

    @Test
    fun theWarmUpStartsWithTheViewTheRendererOpens() {
        // The first view has a path, the second none: no path asked for opens the first, and it is warmed first.
        val config = LovelaceParser.parseDashboard(JSONObject("""{"views": [
            {"path": "home", "cards": [{"type": "custom:button-card", "entity": "light.a"}]},
            {"cards": [{"type": "custom:button-card", "entity": "light.b"}]}]}"""))
        var checked = 0
        CompatWarmup.warm(config, null) { checked++ >= 1 }
        val (home, other) = config.views.map { CardRouter.viewRoots(it).single().card }
        assertTrue(home.compatComputed)
        assertFalse(other.compatComputed)
    }

    @Test
    fun aNewDashboardStopsTheOldWarmUp() {
        var checked = 0
        CompatWarmup.warm(parse(), null) { checked++ >= 3 }
        assertEquals("stops between cards once cancelled", 4, checked)
    }
}
