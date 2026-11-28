/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import android.app.Activity
import android.app.Application
import dev.glassworks.engine.vitro.cards.WebIslandCardView
import dev.glassworks.engine.vitro.data.EntityStore
import dev.glassworks.engine.vitro.model.LovelaceParser
import dev.glassworks.engine.vitro.model.LovelaceResource
import dev.glassworks.engine.vitro.theme.ThemeCatalog
import dev.glassworks.engine.vitro.theme.ThemeResolver
import dev.glassworks.engine.vitro.ui.VitroCardContext
import dev.glassworks.testing.RepoFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The island page Vitro would serve for one card, written to a file for tools/vitro-parity/island-probe.mjs, which
 * runs it in Chromium against the fake HA (#885). Runs only when asked:
 *
 *     ./gradlew :engine-vitro:testDebugUnitTest --tests '*IslandPageDumpTest*' -PvitroIslandCase=<case.json>
 *
 * <case.json> holds the `card`, the Lovelace `resources` the page loads (URLs of module scripts), a `fixture` and,
 * optionally, `storage`: what HA's web UI keeps in `localStorage` on the panel, which the island starts from. The
 * page goes to engines/engine-vitro/build/vitro-island/<case>.html, with the corpus's home.json config. Several cases
 * at once: their paths separated by commas. Writes nothing else; skipped (not failed) without the property, so CI's
 * runs pass over it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IslandPageDumpTest {
    @Test
    fun writesTheIslandPageOfACase() {
        val paths = System.getProperty("vitro.islandCase")
        assumeTrue("only with -PvitroIslandCase=<case.json> (tools/vitro-parity/island-probe.mjs)", !paths.isNullOrBlank())
        val files = paths!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::File)
        // Each page is named after its case file: two cases of one name would write (and probe) one page.
        val clash = files.groupBy { it.nameWithoutExtension }.filterValues { it.size > 1 }
        require(clash.isEmpty()) { "cases with the same file name write the same page: ${clash.values.flatten().joinToString()}" }
        files.forEach(::writePage)
    }

    private fun writePage(caseFile: File) {
        val case = JSONObject(caseFile.readText())
        val home = JSONObject(File(RepoFiles.root, "engines/engine-vitro/src/test/parity/home.json").readText())
        // HA's config as get_config gives it, with the components the fixture's entities come from.
        val domains = case.optJSONObject("fixture")?.optJSONObject("states")?.keys()?.asSequence()?.map { it.substringBefore('.') }?.toSortedSet().orEmpty()
        val config = home.getJSONObject("config").put("components", JSONArray(domains.toList()))
            // HA's config always has its version (Mushroom 5.3 reads it on every render).
            .put("version", "2026.11.0")
        val resources = case.optJSONArray("resources")?.let { a -> (0 until a.length()).map { LovelaceResource(a.getString(it), "module") } }.orEmpty()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ctx = VitroCardContext(
            context = activity, haBase = "http://127.0.0.1", store = EntityStore(), theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = resources, accessToken = { "island-probe" }, haConfig = { config }, callService = { _, _, _, _ -> },
            navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
            // HA's localStorage on the panel, when the case gives one (#881): the island takes it in before its card.
            pageStorage = { case.optJSONObject("storage") },
        )
        // The view the card is in, when the case says (`"view_type": "sections"`): what the island hands a card that
        // reads it from HA's page (WebIslandCardView.islandConfig).
        ctx.viewType = case.optString("view_type").ifEmpty { null }
        val out = File(RepoFiles.root, "engines/engine-vitro/build/vitro-island/${caseFile.nameWithoutExtension}.html")
        out.parentFile.mkdirs()
        out.writeText(WebIslandCardView.buildIslandHtml(ctx, LovelaceParser.parseCard(case.getJSONObject("card"))))
        println("island page: ${out.absolutePath}")
    }
}
