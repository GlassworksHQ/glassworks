/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sh.edm.s1kiosk` is the S1 frame's package (#22). Nothing outside the S1 frame has a file under an `sh/edm` folder:
 * the generic app names the core's components directly and the Mirror probe has its own package (#527). A branch from
 * before the rename that adds a file to an old package fails here: run `tools/migrate/rename-22.py` on it.
 */
class OldPackageGuardTest {
    private val skip = setOf("build", ".gradle", ".kotlin", "node_modules", ".git", ".next", "dumps")

    @Test fun nothingNewUnderTheOldPackage() {
        val found = RepoFiles.root.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && "/sh/edm/" in "/" + RepoFiles.rel(it) }.map(RepoFiles::rel).toList()
        assertTrue("the scan sees the S1 frame's own sh.edm files", found.count { it.startsWith("frames/aqara-s1-plus/") } >= 20)
        val stray = found.filterNot { it.startsWith("frames/aqara-s1-plus/") }.sorted()
        assertEquals("files under sh/edm outside the S1 frame; move them with tools/migrate/rename-22.py", emptyList<String>(), stray)
    }
}
