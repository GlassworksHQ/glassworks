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
 * <case.json> holds the `card`, the Lovelace `resources` the page loads (URLs of module scripts) and a `fixture`. The
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
        for (path in paths!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }) writePage(File(path))
    }

    private fun writePage(caseFile: File) {
        val case = JSONObject(caseFile.readText())
        val home = JSONObject(File(RepoFiles.root, "engines/engine-vitro/src/test/parity/home.json").readText())
        // HA's config as get_config gives it, with the components the fixture's entities come from.
        val domains = case.optJSONObject("fixture")?.optJSONObject("states")?.keys()?.asSequence()?.map { it.substringBefore('.') }?.toSortedSet().orEmpty()
        val config = home.getJSONObject("config").put("components", JSONArray(domains.toList()))
        val resources = case.optJSONArray("resources")?.let { a -> (0 until a.length()).map { LovelaceResource(a.getString(it), "module") } }.orEmpty()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ctx = VitroCardContext(
            context = activity, haBase = "http://127.0.0.1", store = EntityStore(), theme = ThemeResolver.resolve(ThemeCatalog(), true),
            resources = resources, accessToken = { "island-probe" }, haConfig = { config }, callService = { _, _, _, _ -> },
            navigate = {}, openMoreInfo = {}, fetchHistory = { _, _, _ -> }, subscribeTemplate = { _, _ -> {} },
        )
        val out = File(RepoFiles.root, "engines/engine-vitro/build/vitro-island/${caseFile.nameWithoutExtension}.html")
        out.parentFile.mkdirs()
        out.writeText(WebIslandCardView.buildIslandHtml(ctx, LovelaceParser.parseCard(case.getJSONObject("card"))))
        println("island page: ${out.absolutePath}")
    }
}
