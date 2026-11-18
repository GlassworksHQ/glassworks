/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `sh.edm.s1kiosk` is the S1 app's id. Its code is in `dev.glassworks.s1` (#527). The only files under an `sh/edm` folder
 * are the S1 app's one-line subclasses at the component names Android, the field and the root side hold. A branch from
 * before the move that adds a file to an old package fails here: run `tools/migrate/rename-22.py` on it.
 */
class OldPackageGuardTest {
    private val shims = listOf("AdminReceiver", "BootReceiver", "MainActivity", "improv/ImprovActivity", "update/UpdateInstallReceiver")
        .map { "frames/aqara-s1-plus/android/src/main/java/sh/edm/s1kiosk/$it.kt" }
    private val skip = setOf("build", ".gradle", ".kotlin", "node_modules", ".git", ".next", "dumps")

    @Test fun onlyTheS1AppsComponentNamesAreUnderTheOldPackage() {
        val found = RepoFiles.root.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && "/sh/edm/" in "/" + RepoFiles.rel(it) }.map(RepoFiles::rel).toList()
        assertEquals("files under sh/edm besides the S1 app's subclasses; move them with tools/migrate/rename-22.py",
            shims.sorted(), found.sorted())
    }

    @Test fun theS1AppsSubclassesAreOneLine() {
        for (path in shims) {
            val code = File(RepoFiles.root, path).readLines().filter { it.isNotBlank() && !it.startsWith("/**") && !it.startsWith("package ") }
            assertEquals("$path holds only its subclass line", 1, code.size)
            assertTrue(path, Regex("""^class \w+ : dev\.glassworks\.[\w.]+\(\)$""").matches(code.single()))
        }
    }
}
