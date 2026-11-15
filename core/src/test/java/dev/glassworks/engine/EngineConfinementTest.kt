/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fails when app code outside the engine uses GeckoView. Only `dev/glassworks/engine/gecko/` may
 * name `org.mozilla.gecko…`; everything else talks to [Engine]. The scan matches the text, so a
 * fully qualified name or a comment trips it too, as `PlatformConfinementTest` does for `SDK_INT`.
 * It covers every module but the libraries, wherever they sit ([RepoFiles]).
 */
class EngineConfinementTest {
    private val gecko = "dev/glassworks/engine/gecko"

    @Test
    fun `only the Gecko engine package uses GeckoView`() {
        val sources = RepoFiles.code(atLeast = 100, inModules = RepoFiles.appModules)
        val found = sources.filterNot { RepoFiles.inPackage(it, gecko) }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if ("org.mozilla.gecko" in line) "${RepoFiles.rel(f)}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("GeckoView outside dev.glassworks.engine.gecko; go through Engine:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the scan sees the engine that is allowed to use it`() {
        val engine = RepoFiles.source("$gecko/GeckoEngine.kt")
        assertTrue(engine.readText().contains("import org.mozilla.geckoview.GeckoRuntime"))
    }
}
