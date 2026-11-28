/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Application
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.testing.RepoFiles
import org.json.JSONObject
import dev.glassworks.engine.vitro.island.IslandStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * An island's `hass` against Home Assistant's own (#799 D3–D5): the bridge module of a real island page runs under node
 * (`src/test/js/island-hass.test.mjs`) and its `hass` is checked member by member against HA's `HomeAssistant` and
 * `Connection` (`src/test/ha-frontend/hass-types.ts`, vendored), then for what the members do: HA's strings, backend
 * translations, subscriptions that follow the connection, services, and the hand-off of a card that throws on a member
 * the island lacks. Node: [IslandNode].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandHassContractTest {

    private fun page(
        config: JSONObject = JSONObject().put("version", "2026.11.0").put("components", org.json.JSONArray())
            .put("unit_system", JSONObject().put("temperature", "°C").put("length", "km")),
    ): String {
        val ctx = VitroCardContext(
            context = RuntimeEnvironment.getApplication(),
            haBase = "http://homeassistant.local:8123",
            store = EntityStore(),
            theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = emptyList(),
            accessToken = { "made-up-token" },
            haConfig = { config },
            callService = { _, _, _, _ -> },
            navigate = {},
            openMoreInfo = {},
            fetchHistory = { _, _, _ -> },
            subscribeTemplate = { _, _ -> {} },
            pageUrl = { "http://homeassistant.local:8123/dashboard-wall/living" },
        )
        return WebIslandCardView.buildIslandHtml(ctx, LovelaceParser.parseCard(JSONObject("""{"type": "custom:acme-island-card"}""")))
    }

    /** The page's bridge module: the last module script, the one that makes `hass`. */
    private fun bridgeModule(html: String): String {
        val start = html.indexOf("<div id=\"mount\"></div>\n<script type=\"module\">")
        assertTrue("the page has its bridge module", start >= 0)
        val body = html.indexOf('>', html.indexOf("<script type=\"module\">", start)) + 1
        return html.substring(body, html.indexOf("</script>", body))
    }

    @Test
    fun theIslandHassIsHomeAssistantsShape() {
        val node = IslandNode.require()
        // Kept in the build folder: `ISLAND_MODULE=… HASS_TYPES=… node --test src/test/js/island-hass.test.mjs` runs it again.
        val module = File(RepoFiles.root, "engines/engine-vitro/build/vitro-island/island-module.js")
        module.parentFile.mkdirs()
        // The same page made before the panel had HA's config: it waits for the bridge's.
        val noConfig = File(module.parentFile, "island-module-no-config.js")
        run {
            module.writeText(bridgeModule(page()))
            noConfig.writeText(bridgeModule(page(JSONObject())))
            val harness = File(RepoFiles.root, "engines/engine-vitro/src/test/js/island-hass.test.mjs")
            val types = File(RepoFiles.root, "engines/engine-vitro/src/test/ha-frontend/hass-types.ts")
            val (code, out) = IslandNode.run(
                node, listOf("--test", harness.path),
                // TZ: the dates the page formats in its time zone are the same on every machine.
                env = mapOf("ISLAND_MODULE" to module.path, "ISLAND_MODULE_NO_CONFIG" to noConfig.path, "HASS_TYPES" to types.path, "TZ" to "UTC"),
                timeoutSeconds = 120,
            )
            assertEquals(out, 0, code)
        }
    }

    @Test
    fun aRightToLeftLanguageLaysTheIslandOutThatWay() {
        assertEquals(JSONObject("""{"nativeName":"Deutsch","isRTL":false}""").toString(), WebIslandCardView.languageMeta("de").toString())
        assertTrue(WebIslandCardView.languageMeta("ar").getBoolean("isRTL"))
        assertTrue(WebIslandCardView.languageMeta("he").getBoolean("isRTL"))
        assertTrue(WebIslandCardView.languageMeta("fa").getBoolean("isRTL"))
        assertEquals("Español", WebIslandCardView.languageMeta("es").getString("nativeName"))
        assertTrue("the page says so", page().contains("""<html lang="en" dir="ltr">"""))
    }

    @Test
    fun aPageGetsTheStringsItAskedForOrNone() {
        try {
            dev.glassworks.engine.vitro.data.HaText.install("en", mapOf("ui.common.save" to "Save"))
            val english = IslandStrings.current()
            assertTrue(String(english.body).contains("\"Save\""))
            dev.glassworks.engine.vitro.data.HaText.install("de", mapOf("ui.common.save" to "Speichern"))
            val german = IslandStrings.current()
            assertNotEquals(english.hash, german.hash)
            assertEquals("a page made before the change still gets its own", english.hash, IslandStrings.byHash(english.hash)?.hash)
            assertEquals(german.hash, IslandStrings.byHash(german.hash)?.hash)
            assertNull("never another set's strings", IslandStrings.byHash("0123456789abcdef01234567"))
            assertEquals("${IslandStrings.PREFIX}${german.hash}.json", IslandStrings.path())
            // Four sets (en, de, fr, es), English made again, then one more: English, made again, counts as the newest
            // and stays; without that, the fifth would push it out as the oldest.
            val made = HashMap<String, String>()
            for (lang in listOf("fr", "es")) {
                dev.glassworks.engine.vitro.data.HaText.install(lang, mapOf("ui.common.save" to lang))
                made[lang] = IslandStrings.current().hash
            }
            dev.glassworks.engine.vitro.data.HaText.install("en", mapOf("ui.common.save" to "Save"))
            assertEquals(english.hash, IslandStrings.current().hash)
            dev.glassworks.engine.vitro.data.HaText.install("nl", mapOf("ui.common.save" to "Opslaan"))
            IslandStrings.current()
            assertEquals("made again counts as new", english.hash, IslandStrings.byHash(english.hash)?.hash)
            assertNull("German, the oldest, went", IslandStrings.byHash(german.hash))
            // A lookup makes no set, so it pushes nothing out: with Portuguese installed, a lookup that made its set
            // would evict French, the oldest kept (fr, es, en, nl).
            dev.glassworks.engine.vitro.data.HaText.install("pt", mapOf("ui.common.save" to "Guardar"))
            assertEquals(english.hash, IslandStrings.byHash(english.hash)?.hash)
            assertEquals("the oldest kept set survives a lookup", made["fr"], IslandStrings.byHash(made.getValue("fr"))?.hash)
        } finally {
            dev.glassworks.engine.vitro.data.HaText.reset()
        }
    }

    @Test
    fun panelUrlIsTheDashboardsPath() {
        assertEquals("dashboard-wall", WebIslandCardView.panelUrlOf("http://homeassistant.local:8123/dashboard-wall/living"))
        assertEquals("lovelace", WebIslandCardView.panelUrlOf(null))
        assertEquals("lovelace", WebIslandCardView.panelUrlOf("http://homeassistant.local:8123/"))
    }
}
