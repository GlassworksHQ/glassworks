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
    /** Each kept name and the core class it hands to Android. */
    private val shims = mapOf(
        "AdminReceiver" to "dev.glassworks.AdminReceiver",
        "BootReceiver" to "dev.glassworks.BootReceiver",
        "MainActivity" to "dev.glassworks.MainActivity",
        "improv/ImprovActivity" to "dev.glassworks.provisioning.ImprovActivity",
        "update/UpdateInstallReceiver" to "dev.glassworks.update.UpdateInstallReceiver",
    ).mapKeys { (k, _) -> "frames/aqara-s1-plus/android/src/main/java/sh/edm/s1kiosk/$k.kt" }
    private val skip = setOf("build", ".gradle", ".kotlin", "node_modules", ".git", ".next", "dumps")
    private val files by lazy { RepoFiles.root.walkTopDown().onEnter { it.name !in skip }.filter { it.isFile }.toList() }

    @Test fun onlyTheS1AppsComponentNamesAreUnderTheOldPackage() {
        val found = files.map(RepoFiles::rel).filter { "/sh/edm/" in "/$it" }
        assertEquals("files under sh/edm besides the S1 app's subclasses; move them with tools/migrate/rename-22.py",
            shims.keys.sorted(), found.sorted())
        val declared = files.filter { it.extension in setOf("kt", "java") }
            .filter { f -> f.useLines { ls -> ls.any { it.startsWith("package sh.edm") } } }.map(RepoFiles::rel)
        assertEquals("sources that declare an sh.edm package", shims.keys.sorted(), declared.sorted())
    }

    @Test fun eachS1SubclassIsOneLineOverItsCoreClass() {
        for ((path, core) in shims) {
            val code = File(RepoFiles.root, path).readLines().filter { it.isNotBlank() && !it.startsWith("/**") && !it.startsWith("package ") }
            val name = path.substringAfterLast('/').removeSuffix(".kt")
            assertEquals("$path holds only its subclass line", listOf("class $name : $core()"), code)
        }
    }
}
