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
 * `sh.edm.s1kiosk` is the S1 frame's package (#22). Outside the S1 frame and the Mirror probe, which poses as the
 * S1 app on purpose, the only files under an `sh/edm` folder are the generic app's subclasses at the component
 * names its installed apps know. A branch from before the rename that adds a file to an old package fails here:
 * run `tools/migrate/rename-22.py` on it.
 */
class OldPackageGuardTest {
    private val allowedTrees = listOf("frames/aqara-s1-plus/", "tools/mirror/probe-app/")
    private val genericShims = listOf("AdminReceiver", "BootReceiver", "CameraAdbActivity", "KioskService", "MainActivity",
        "improv/ImprovActivity", "update/UpdateInstallReceiver")
        .map { "frames/generic-android/android/src/main/java/sh/edm/s1kiosk/$it.kt" }
    private val skip = setOf("build", ".gradle", ".kotlin", "node_modules", ".git", ".next", "dumps")

    @Test fun nothingNewUnderTheOldPackage() {
        val found = RepoFiles.root.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && "/sh/edm/" in "/" + RepoFiles.rel(it) }.map(RepoFiles::rel).toList()
        assertTrue("the scan sees the S1 frame's own sh.edm files", found.count { it.startsWith("frames/aqara-s1-plus/") } >= 20)
        val stray = found.filter { f -> allowedTrees.none { f.startsWith(it) } && f !in genericShims }.sorted()
        assertEquals("files under sh/edm outside the S1 frame; move them with tools/migrate/rename-22.py", emptyList<String>(), stray)
    }

    @Test fun theGenericAppsShimsAreOneLineSubclasses() {
        for (path in genericShims) {
            val code = File(RepoFiles.root, path).readLines().filter { it.isNotBlank() && !it.startsWith("/**") && !it.startsWith("package ") }
            assertEquals("$path holds only its subclass line", 1, code.size)
            assertTrue(path, Regex("""^class \w+ : dev\.glassworks\.[\w.]+\(\)$""").matches(code.single()))
        }
    }
}
