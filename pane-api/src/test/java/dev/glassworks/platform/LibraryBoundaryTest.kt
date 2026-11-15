/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A library (a module in a `libs` folder) runs in any Android app, so it depends only on other libraries
 * and never on the Pane API, the core or an app (#152). Its build file may name only library modules, and
 * its code (comments aside) never names the app's packages. Without the first, an import of
 * `dev.glassworks.pane` would compile; the second catches a fully qualified name or a class name in a string.
 */
class LibraryBoundaryTest {
    /** Each Gradle path in settings.gradle.kts to its module folder. */
    private val modules: Map<String, File> by lazy {
        Regex("""project\("(:[\w-]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")
            .findAll(RepoFiles.file("settings.gradle.kts").readText())
            .associate { it.groupValues[1] to File(RepoFiles.root, it.groupValues[2]).canonicalFile }
    }

    @Test fun `a library's build file names only library modules`() {
        val libraries = RepoFiles.libraries.map { it.canonicalFile }.toSet()
        assertTrue("scan found no libraries", libraries.size >= 5)
        val bad = libraries.flatMap { lib ->
            Regex("""project\("(:[\w-]+)"\)""").findAll(File(lib, "build.gradle.kts").readText()).map { it.groupValues[1] }
                .filter { path -> modules[path].let { it == null || it !in libraries } }
                .map { "${RepoFiles.rel(lib)}/build.gradle.kts depends on $it" }
                .toList()
        }
        assertEquals("libraries depend only on libraries", emptyList<String>(), bad)
    }

    @Test fun `a library's sources never name the app's packages`() {
        val qualified = Regex("""\bsh\.edm\.[a-z0-9_.]*[a-z0-9_]|\bdev\.glassworks\.(pane|platform|frame|secrets)\b""")
        val bad = RepoFiles.code(atLeast = 40, inModules = RepoFiles.libraries).flatMap { f ->
            f.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//").trim()
                !code.startsWith("*") && !code.startsWith("/*") && qualified.containsMatchIn(code)
            }.map { (i, line) -> "${RepoFiles.rel(f)}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("app packages in a library", emptyList<String>(), bad)
    }

    @Test fun `the scan knows every library module`() {
        val libraries = RepoFiles.libraries.map { it.canonicalFile }.toSet()
        assertTrue("settings.gradle.kts includes each library", libraries.all { it in modules.values })
        assertTrue(modules[":sendspin"] in libraries)
        assertTrue(modules[":voice"] in libraries)
    }
}
