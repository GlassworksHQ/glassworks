/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The legacy build (#198) is the `legacy` build type of this app (frames/generic-android/android/build.gradle.kts):
 * the same sources and manifest (so GenericAppTest's permission and home-screen checks hold for it), its own id, the
 * legacy engine, and minSdk 23. Its unit tests are the release build's; this checks what only the build file says,
 * which only a legacy build would otherwise find out.
 */
class LegacyBuildTest {
    private val buildFile = RepoFiles.file("frames/generic-android/android/build.gradle.kts").readText()
    private val settings = RepoFiles.file("settings.gradle.kts").readText()
    private val dirs: Map<String, File> = Regex("""project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)""")
        .findAll(settings).associate { it.groupValues[1] to RepoFiles.file(it.groupValues[2]) }
    private val dependency = Regex("""\bproject\("(:[^"]+)"\)""")
    private val minSdkLine = Regex("""minSdk = (\d+)""")
    private val defaultMinSdk = minSdkLine.find(RepoFiles.file("build-logic/src/main/kotlin/glassworks.android.gradle.kts").readText())
        ?.groupValues?.get(1)?.toInt() ?: throw AssertionError("no minSdk in glassworks.android")

    private fun build(path: String) = File(dirs[path] ?: throw AssertionError("$path isn't in settings.gradle.kts"), "build.gradle.kts").readText()

    /** The dependencies lines of this build file that the legacy build gets: all but the debug and release ones. */
    private fun legacyDependencies(): Set<String> = buildFile.substringAfter("\ndependencies {").lines()
        .filter { !it.trim().startsWith("debugImplementation(") && !it.trim().startsWith("releaseImplementation(") }
        .flatMap { line -> dependency.findAll(line).map { it.groupValues[1] } }.toSet()

    @Test fun itsIdIsTheOneTheAppPicksItsReleaseLineBy() {
        val id = Regex("""applicationId = "([^"]+)"""").find(buildFile)?.groupValues?.get(1)
        val legacy = buildFile.substringAfter("create(\"legacy\")").substringBefore("}")
        val suffix = Regex("""applicationIdSuffix = "([^"]+)"""").find(legacy)?.groupValues?.get(1)
        assertEquals(GenericApp.LEGACY_APPLICATION_ID, "$id$suffix")
    }

    @Test fun eachBuildHasOneEngine() {
        assertTrue(""""legacyImplementation"(project(":engine-gecko-legacy"))""" in buildFile)
        assertEquals("GeckoView 159 only in debug and release", setOf(":engine-gecko-legacy"), legacyDependencies().filter { it.startsWith(":engine-gecko") }.toSet())
        assertTrue("""debugImplementation(project(":engine-gecko"))""" in buildFile)
        assertTrue("""releaseImplementation(project(":engine-gecko"))""" in buildFile)
    }

    /** It runs modules that declare minSdk 26 from API 23: each must be named, or the legacy build's manifest merge fails. */
    @Test fun everyModuleAboveApi23IsNamedInOverrideLibrary() {
        val manifest = RepoFiles.file("frames/generic-android/android/src/legacy/AndroidManifest.xml").readText()
        val named = Regex("""tools:overrideLibrary="([^"]+)"""").find(manifest)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.toSet()
            ?: throw AssertionError("no tools:overrideLibrary in the legacy manifest")
        val seen = linkedSetOf<String>()
        val todo = ArrayDeque(legacyDependencies().toList())
        while (todo.isNotEmpty()) {
            val p = todo.removeFirst()
            if (seen.add(p)) todo += dependency.findAll(build(p)).map { it.groupValues[1] }.filter { it != p }
        }
        assertTrue("found only $seen", seen.size >= 8)
        val missing = seen.mapNotNull { p ->
            val b = build(p)
            if ("id(\"com.android." !in b) return@mapNotNull null // esphome-api: a JVM library
            val minSdk = minSdkLine.find(b)?.groupValues?.get(1)?.toInt() ?: defaultMinSdk
            val namespace = Regex("""namespace = "([^"]+)"""").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            if (minSdk > 23 && namespace !in named) "$p ($namespace, minSdk $minSdk)" else null
        }
        assertEquals("add these to tools:overrideLibrary in src/legacy/AndroidManifest.xml", emptyList<String>(), missing)
    }
}
