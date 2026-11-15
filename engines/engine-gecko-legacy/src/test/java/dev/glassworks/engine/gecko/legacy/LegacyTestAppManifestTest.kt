/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The legacy test APK (engines/engine-gecko-legacy/test-app, #198) runs modules that declare minSdk 26 on API 23,
 * so its manifest names each in `tools:overrideLibrary`. A new one that isn't named fails the APK's manifest merge,
 * which only a legacy test build runs; this fails `./gradlew test` instead (it happened with :sendspin, 2026-11-15).
 */
class LegacyTestAppManifestTest {
    private val settings = RepoFiles.file("settings.gradle.kts").readText()
    private val dirs: Map<String, File> = Regex("""project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)""")
        .findAll(settings).associate { it.groupValues[1] to RepoFiles.file(it.groupValues[2]) }
    private val dependency = Regex("""\bproject\("(:[^"]+)"\)""")

    private fun build(path: String) = File(dirs[path] ?: throw AssertionError("$path isn't in settings.gradle.kts"), "build.gradle.kts").readText()

    /** Every project the test app depends on, directly or through another project. */
    private fun projects(): Set<String> {
        val seen = linkedSetOf<String>()
        val todo = ArrayDeque(dependency.findAll(build(":kiosk-legacy")).map { it.groupValues[1] }.toList())
        while (todo.isNotEmpty()) {
            val p = todo.removeFirst()
            if (seen.add(p)) todo += dependency.findAll(build(p)).map { it.groupValues[1] }.filter { it != p }
        }
        return seen
    }

    @Test fun `every module above API 23 is named in overrideLibrary`() {
        val manifest = RepoFiles.file("engines/engine-gecko-legacy/test-app/src/debug/AndroidManifest.xml").readText()
        val named = Regex("""tools:overrideLibrary="([^"]+)"""").find(manifest)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.toSet()
            ?: throw AssertionError("no tools:overrideLibrary in the test app's manifest")
        val deps = projects()
        assertTrue("found only $deps", deps.size >= 10)
        val missing = deps.mapNotNull { p ->
            val b = build(p)
            val minSdk = Regex("""minSdk = (\d+)""").find(b)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            val namespace = Regex("""namespace = "([^"]+)"""").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            if (minSdk > 23 && namespace !in named) "$p ($namespace, minSdk $minSdk)" else null
        }
        assertEquals("add these to tools:overrideLibrary in test-app/src/debug/AndroidManifest.xml", emptyList<String>(), missing)
    }
}
