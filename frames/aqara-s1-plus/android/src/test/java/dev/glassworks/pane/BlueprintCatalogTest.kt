/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import dev.glassworks.testing.RepoFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.File

/**
 * The panes' Home Assistant blueprints: each pane.json's `blueprints` lists files in its module's
 * `blueprints/` folder, each with a one-line summary. Every listed file exists and is an automation
 * blueprint (a `blueprint:` block with a name, a description, `domain: automation` and named inputs),
 * every YAML in a `blueprints/` folder is listed, and no two share a file name: Home Assistant saves an
 * import from GitHub as `GlassworksHQ/<file>`, without the folder.
 *
 * The app reads the list from its asset [Blueprint.ASSET], which this test checks against the files.
 * After changing a pane.json or a blueprint's name, write it again:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*BlueprintCatalogTest*' -Ps1RegenGolden=true
 *
 * The YAML here is parsed for its shape only. Home Assistant's own check is in docs/research/pane-blueprints.md.
 */
class BlueprintCatalogTest {

    private val repo = RepoFiles.root

    private class Listed(val pane: String, val module: File, val paneJson: File, val file: String, val summary: String) {
        val yaml = File(module, file)
    }

    private val listed by lazy {
        RepoFiles.sources(atLeast = 8) { it.name == "pane.json" }.flatMap { f ->
            val d = JSONObject(f.readText())
            val module = File(repo, RepoFiles.rel(f).substringBefore("/src/main/"))
            val a = d.optJSONArray("blueprints") ?: return@flatMap emptyList()
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                assertEquals("${RepoFiles.rel(f)}: blueprints[$i] has only file and summary", setOf("file", "summary"), o.keys().asSequence().toSet())
                Listed(d.getString("id"), module, f, o.getString("file"), o.getString("summary"))
            }
        }
    }

    /** Home Assistant's `!input name` as the string `!input name`, so the file parses as plain YAML. */
    private val yaml = Yaml(object : SafeConstructor(LoaderOptions()) {
        init {
            yamlConstructors[Tag("!input")] = object : AbstractConstruct() {
                override fun construct(node: Node) = "!input " + (node as ScalarNode).value
            }
        }
    })

    @Suppress("UNCHECKED_CAST")
    private fun blueprintBlock(f: File): Map<String, Any?> {
        val doc = yaml.load<Any?>(f.readText()) as? Map<String, Any?> ?: throw AssertionError("${RepoFiles.rel(f)}: not a YAML mapping")
        return doc["blueprint"] as? Map<String, Any?> ?: throw AssertionError("${RepoFiles.rel(f)}: no blueprint: block")
    }

    private fun nameOf(l: Listed) = blueprintBlock(l.yaml)["name"] as String

    /** What [Blueprint.ASSET] must hold: every listed blueprint in pane.json order, by pane.json path. */
    private fun asset(): String = listed.joinToString(",\n", "[\n", "\n]\n") { l ->
        val path = RepoFiles.rel(l.yaml)
        "  {\"pane\": ${JSONObject.quote(l.pane)}, \"path\": ${JSONObject.quote(path)}, \"name\": ${JSONObject.quote(nameOf(l))}, \"summary\": ${JSONObject.quote(l.summary)}}"
    }

    @Test
    fun theScanFindsTheKnownBlueprints() {
        // If a refactor hides them from the scan, this fails before the checks below pass on nothing.
        assertTrue(listed.map { it.file }.toString(), listed.map { it.yaml.name }.containsAll(listOf("doorbell-panels.yaml", "ask-panels.yaml")))
        assertTrue(listed.map { it.pane }.toSet().toString(), listed.map { it.pane }.toSet().containsAll(setOf("notifications", "camera")))
    }

    @Test
    fun everyListedFileExistsInItsPanesBlueprintsFolderAndIsAnAutomationBlueprint() {
        for (l in listed) {
            val where = "${RepoFiles.rel(l.paneJson)}: ${l.file}"
            assertTrue("$where: must be blueprints/<name>.yaml", Regex("^blueprints/[a-z0-9-]+\\.yaml$").matches(l.file))
            assertTrue("$where: no such file ${RepoFiles.rel(l.yaml)}", l.yaml.isFile)
            assertTrue("$where: summary is one line", l.summary.isNotBlank() && "\n" !in l.summary)
            val b = blueprintBlock(l.yaml)
            for (k in listOf("name", "description")) assertTrue("$where: blueprint.$k", (b[k] as? String)?.isNotBlank() == true)
            assertEquals("$where: blueprint.domain", "automation", b["domain"])
            @Suppress("UNCHECKED_CAST")
            val inputs = b["input"] as? Map<String, Map<String, Any?>> ?: throw AssertionError("$where: blueprint.input")
            assertTrue("$where: no inputs", inputs.isNotEmpty())
            for ((k, v) in inputs) {
                assertTrue("$where: input $k has a name", (v["name"] as? String)?.isNotBlank() == true)
                assertTrue("$where: input $k has a description", (v["description"] as? String)?.isNotBlank() == true)
                assertTrue("$where: input $k has a selector", v["selector"] is Map<*, *>)
            }
            // Every input is used, and nothing uses an input that isn't declared.
            val used = Regex("""!input ([a-z0-9_]+)""").findAll(l.yaml.readText()).map { it.groupValues[1] }.toSet()
            assertEquals("$where: inputs declared vs used", inputs.keys, used)
        }
    }

    @Test
    fun everyBlueprintFileIsListedAndItsFileNameIsUniqueInTheRepo() {
        val files = RepoFiles.modules.flatMap { m -> File(m, "blueprints").listFiles().orEmpty().filter { it.isFile }.toList() }
        assertTrue("found no blueprints/ files: $files", files.size >= listed.size)
        val unlisted = files.map(RepoFiles::rel) - listed.map { RepoFiles.rel(it.yaml) }.toSet()
        assertTrue("files in a blueprints/ folder that no pane.json lists: $unlisted", unlisted.isEmpty())
        val twice = files.groupBy { it.name }.filterValues { it.size > 1 }.mapValues { (_, v) -> v.map(RepoFiles::rel) }
        assertTrue("blueprint file names used twice (Home Assistant drops the folder): $twice", twice.isEmpty())
        val outside = repo.walkTopDown().onEnter { it.name !in setOf(".git", "node_modules", "build", ".gradle") }
            .filter { it.isFile && it.extension == "yaml" && "blueprint:" in it.readText() && it.parentFile.name != "blueprints" }
            .map(RepoFiles::rel).toList()
        assertTrue("blueprints outside a pane's blueprints/ folder: $outside", outside.isEmpty())
    }

    @Test
    fun theAppsAssetMatchesTheFiles() {
        val want = asset()
        val file = File(RepoFiles.s1App, "src/main/assets/${Blueprint.ASSET}")
        if (System.getProperty("s1.regenGolden") == "true") {
            file.writeText(want)
            return
        }
        assertEquals("${RepoFiles.rel(file)} is stale: write it again (see the class KDoc)", want, file.readText())
        assertEquals(listed.size, Blueprint.parse(file.readText()).size)
    }
}
