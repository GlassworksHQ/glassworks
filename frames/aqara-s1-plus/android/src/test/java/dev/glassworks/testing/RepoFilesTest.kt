/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.testing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * [RepoFiles] finds files across the modules wherever they sit (#21), and fails rather than finding
 * nothing. The layouts are made up here, beside a check on the real repo.
 */
class RepoFilesTest {
    private val tmp = createTempDirectory("repo-files").toFile()

    @After fun cleanUp() {
        tmp.deleteRecursively()
    }

    private fun touch(path: String, text: String = "") = File(tmp, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun tree(vararg files: String): RepoTree {
        touch("THIRD-PARTY-NOTICES.md")
        File(tmp, "frames").mkdirs()
        files.forEach { touch(it) }
        return RepoTree { tmp }
    }

    private fun RepoTree.names(modules: List<File>) = modules.map(::rel)

    @Test fun theRepoHasTheAppAndItsLibraries() {
        val r = RepoFiles
        assertTrue(r.names(r.modules).toString(), r.names(r.modules).containsAll(listOf("frames/aqara-s1-plus/android", "libs/esphome-api", "libs/improv")))
        assertEquals(r.names(r.modules) - r.names(r.libraries).toSet(), r.names(r.appModules))
        assertTrue(r.names(r.libraries).toString(), r.libraries.size >= 4 && r.libraries.all { "/libs/" in "/${r.rel(it)}" })
        assertTrue(r.rel(r.s1App), File(r.s1App, "src/main/AndroidManifest.xml").isFile)
        assertEquals("dev/glassworks/platform/Platform.kt", r.packagePath(r.source("dev/glassworks/platform/Platform.kt")))
    }

    /** The files, spread over modules in several places. */
    @Test fun findsFilesAcrossTheModules() {
        val r = tree(
            "pane-api/src/main/java/dev/glassworks/platform/Platform.kt", "pane-api/src/main/java/dev/glassworks/pane/Pane.kt",
            "core/src/main/java/dev/glassworks/pane/PaneHost.kt", "core/src/main/res/font/inter.ttf",
            "engines/engine-gecko/src/main/java/dev/glassworks/engine/gecko/GeckoEngine.kt",
            "panes/assist/src/main/java/dev/glassworks/assist/pane.json",
            "frames/aqara-s1-plus/android/src/main/java/sh/edm/s1kiosk/MainActivity.kt",
            "frames/aqara-s1-plus/android/src/test/resources/prefs.golden.txt",
            "frames/aqara-s1-plus/panes/wall-base/src/main/java/sh/edm/s1kiosk/base/WallBasePane.kt",
            "frames/aqara-s1-plus/installer/src/index.ts", "frames/aqara-s1-plus/design/frame.json",
            "libs/improv/src/main/java/dev/glassworks/improv/Compat.kt", "libs/esphome-api/src/main/kotlin/dev/glassworks/esphome/Api.kt",
            "core/build/tmp/src/main/java/Stale.kt", "kiln/node_modules/x/src/main/java/Y.kt",
        )
        touch("frames/aqara-s1-plus/android/build.gradle.kts", "applicationId = \"sh.edm.s1kiosk\"")
        assertEquals(
            listOf("core", "engines/engine-gecko", "frames/aqara-s1-plus/android", "frames/aqara-s1-plus/panes/wall-base",
                "libs/esphome-api", "libs/improv", "pane-api", "panes/assist"),
            r.names(r.modules),
        )
        assertEquals(listOf("libs/esphome-api", "libs/improv"), r.names(r.libraries))
        assertEquals("frames/aqara-s1-plus/android", r.rel(r.s1App))
        // every app module's code, none of the libraries' and none of the build output
        assertEquals(6, r.code(atLeast = 6, inModules = r.appModules).size)
        assertEquals("pane-api/src/main/java/dev/glassworks/platform/Platform.kt", r.rel(r.source("dev/glassworks/platform/Platform.kt")))
        assertEquals("libs/esphome-api/src/main/kotlin/dev/glassworks/esphome/Api.kt", r.rel(r.source("dev/glassworks/esphome/Api.kt")))
        assertEquals(listOf("core", "pane-api"), r.packageDirs("dev/glassworks/pane").map { r.rel(it).substringBefore("/src/") })
        assertEquals(1, r.sources(atLeast = 1) { it.name == "pane.json" }.size)
        assertEquals("core/src/main/res/font/inter.ttf", r.rel(r.inModule("src/main/res/font/inter.ttf")))
        assertEquals("frames/aqara-s1-plus/android/src/test/resources/prefs.golden.txt", r.rel(r.inModule("src/test/resources/prefs.golden.txt")))
        val platform = r.source("dev/glassworks/platform/Platform.kt")
        assertTrue(r.inPackage(platform, "dev/glassworks/platform") && !r.inPackage(platform, "dev/glassworks/plat"))
    }

    /** The point of the guard: a scan that sees nothing fails instead of passing with nothing checked. */
    @Test fun aScanThatFindsTooLittleFails() {
        val r = tree("core/src/main/java/dev/glassworks/A.kt", "core/src/main/res/x.xml", "pane-api/src/main/res/x.xml")
        assertEquals(1, r.code(atLeast = 1).size)
        assertThrows(AssertionError::class.java) { r.code(atLeast = 2) }
        assertThrows(AssertionError::class.java) { r.code(atLeast = 1, inModules = r.libraries) }
        assertThrows(AssertionError::class.java) { r.source("dev/glassworks/B.kt") }
        assertThrows(AssertionError::class.java) { r.inModule("src/main/res/x.xml") } // in two modules
        assertThrows(AssertionError::class.java) { r.s1App }
        assertThrows(AssertionError::class.java) { r.file("CHANGELOG.md") }
        File(tmp, "core").deleteRecursively()
        File(tmp, "pane-api").deleteRecursively()
        assertThrows(AssertionError::class.java) { RepoTree { tmp }.modules }
    }
}
