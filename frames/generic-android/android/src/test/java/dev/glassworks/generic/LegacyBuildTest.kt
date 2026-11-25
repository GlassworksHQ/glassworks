/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.generic

import android.app.Activity
import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.vitro.VitroEngine
import dev.glassworks.platform.AppBuild
import dev.glassworks.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The legacy build (#198) is the `legacy` build type of this app (frames/generic-android/android/build.gradle.kts):
 * the same sources and manifest (so GenericAppTest's permission and home-screen checks hold for it), its own id, the
 * legacy engine, and minSdk 23. Its unit tests are the release build's; this checks what only the build file says,
 * which only a legacy build would otherwise find out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
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

    /** A browser engine that never opens: only [VitroEngine.islandOrigin] is used. */
    private class NoEngine : Engine {
        override val started: EngineSettings? = null
        override fun open(activity: Activity, host: PageHost, background: Int): Page = throw UnsupportedOperationException()
        override fun isContentProcess(processName: String) = false
    }

    /**
     * #198: the legacy GeckoView holds pages to the dashboard's origin. Under Vitro it is told the loopback island
     * server's too, as its one other origin, and keeps Home Assistant's address: HA's own pages load from HA.
     */
    @Test fun theLegacyEngineIsToldTheLoopbackOriginOnlyUnderVitro() {
        val app = RuntimeEnvironment.getApplication()
        val build = AppBuild("1.0.0", 10000, debug = false, product = GenericApp.PRODUCT)
        KioskPrefs(app).urlSetting = "http://192.0.2.10:8123/lovelace/0"
        val real = GenericApp.engineSettings(app, build, GenericApp.LEGACY_APPLICATION_ID).dashboard
        assertTrue(real.toString(), real?.startsWith("http://192.0.2.10:8123") == true)
        val vitro = VitroEngine(fallback = NoEngine(), settings = { EngineSettings(emptyList(), 1f, false) })
        try {
            val legacy = GenericApp.geckoSettings(app, build, GenericApp.LEGACY_APPLICATION_ID) { vitro }
            assertEquals("Home Assistant stays the dashboard", real, legacy.dashboard)
            assertTrue(legacy.localOrigin.toString(), legacy.localOrigin?.startsWith("http://127.0.0.1:") == true)
            assertEquals(vitro.islandOrigin(), legacy.localOrigin)
            assertFalse("the rest is as engineSettings has it", legacy.remoteDebugging)

            assertEquals("the generic app's GeckoView keeps Home Assistant's address", real,
                GenericApp.geckoSettings(app, build, "dev.glassworks.generic_android") { throw AssertionError("no Vitro needed") }.dashboard)

            assertEquals("no Vitro: the legacy engine keeps the real address", real,
                GenericApp.geckoSettings(app, build, GenericApp.LEGACY_APPLICATION_ID) { null }.dashboard)
            assertNull("no Vitro: no loopback origin", GenericApp.geckoSettings(app, build, GenericApp.LEGACY_APPLICATION_ID) { null }.localOrigin)
            assertNull("the generic app's GeckoView isn't held to origins",
                GenericApp.geckoSettings(app, build, "dev.glassworks.generic_android") { vitro }.localOrigin)
        } finally {
            vitro.islandServer.stop()
        }
    }
}
