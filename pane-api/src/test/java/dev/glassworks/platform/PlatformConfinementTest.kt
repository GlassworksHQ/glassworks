/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when `SDK_INT` is read outside its one place. In the app that is `dev/glassworks/platform/`.
 * Each library (a module in a `libs` folder) may read it only in its own `Compat.kt`, because
 * libraries do not depend on Glassworks. The scan matches the text, so an alias import or a comment
 * trips it too. It covers every module, wherever it sits ([RepoFiles]).
 *
 * A source scan, not a Lint rule: it needs no extra module, it runs in `./gradlew test` where CI
 * already runs, and its failure names the file and line.
 */
class PlatformConfinementTest {
    private fun violations(sources: List<File>, allowed: (File) -> Boolean): List<String> =
        sources.filterNot(allowed).flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if ("SDK_INT" in line) "${RepoFiles.rel(f)}:${i + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `the app reads SDK_INT only in dev glassworks platform`() {
        val found = violations(RepoFiles.code(atLeast = 100, inModules = RepoFiles.appModules)) { RepoFiles.inPackage(it, "dev/glassworks/platform") }
        assertTrue("SDK_INT outside dev.glassworks.platform; add a query to Platform:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `each library reads SDK_INT only in its own Compat file`() {
        assertTrue("scan found no libraries", RepoFiles.libraries.size >= 4)
        val found = violations(RepoFiles.code(atLeast = 20, inModules = RepoFiles.libraries)) { it.name == "Compat.kt" }
        assertTrue("SDK_INT outside a library's Compat.kt:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the scan sees the places that are allowed to read it`() {
        assertTrue(RepoFiles.source("dev/glassworks/platform/Platform.kt").readText().contains("SDK_INT"))
        assertTrue(RepoFiles.source("dev/glassworks/improv/Compat.kt").readText().contains("SDK_INT"))
    }
}
