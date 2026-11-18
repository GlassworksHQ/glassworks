package dev.glassworks.device

import dev.glassworks.frame.Frame
import dev.glassworks.testing.RepoFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device shell is meant to become device-neutral, and the core already is. Source scans, like
 * PlatformConfinementTest:
 *  - `PanelDevice` reaches the frame only through `dev.glassworks.frame.Frame`: every `runtime.frame`
 *    read is a member of [Frame], or the one field typed as it. It imports nothing from the S1 frame.
 *  - Nothing under `dev/glassworks` imports app code. The libraries (modules in a `libs` folder) are
 *    allowed: they are plain libraries with no Glassworks dependency.
 * Both scans cover every module, wherever it sits ([RepoFiles]).
 */
class PanelDeviceFrameTest {

    @Test fun panelDeviceSeesOnlyTheFrameInterface() {
        val frameMembers = Frame::class.java.methods.map { m ->
            m.name.removePrefix("get").replaceFirstChar { it.lowercase() }
        }.toSet()
        val lines = RepoFiles.source("dev/glassworks/device/PanelDevice.kt").readLines()
        val bad = lines.withIndex().flatMap { (i, line) ->
            val reads = Regex("""runtime\.frame(?:\.(\w+))?""").findAll(line).filterNot { m ->
                val member = m.groupValues[1]
                if (member.isEmpty()) ": Frame = runtime.frame" in line else member in frameMembers
            }.map { "${i + 1}: ${line.trim()}" }.toList()
            val s1 = if ("dev.glassworks.s1.frame" in line || "AqaraS1Plus" in line) listOf("${i + 1}: ${line.trim()}") else emptyList()
            reads + s1
        }.distinct()
        assertTrue("scan found the frame field", lines.any { "private val frame: Frame = runtime.frame" in it })
        assertEquals("PanelDevice reaches past Frame:\n${bad.joinToString("\n")}", emptyList<String>(), bad)
    }

    @Test fun theCoreImportsNoAppCode() {
        val libPackages = RepoFiles.sources(atLeast = 20, inModules = RepoFiles.libraries) { it.extension == "kt" }
            .mapNotNull { f -> f.useLines { ls -> ls.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() } }
            .toSet()
        // An APK module is an app, not the core: the S1 app (dev.glassworks.s1, #527) and the generic app (#23).
        val apks = RepoFiles.appModules.filter { m -> File(m, "build.gradle.kts").let { it.isFile && "id(\"com.android.application\")" in it.readText() } }
        assertTrue("found the APK modules", apks.size >= 2)
        val appPackages = RepoFiles.sources(atLeast = 20, inModules = apks) { it.extension == "kt" }
            .mapNotNull { f -> f.useLines { ls -> ls.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() } }
            .toSet() - libPackages
        assertTrue("found the S1 app's package", "dev.glassworks.s1" in appPackages)
        // A frame's own panes (frames/<id>/panes) are that frame's code, not the core.
        val core = RepoFiles.appModules - apks.toSet() - RepoFiles.appModules.filter { RepoFiles.rel(it).startsWith("frames/") }.toSet()
        val sources = RepoFiles.sources(atLeast = 30, inModules = core) { it.extension == "kt" && RepoFiles.inPackage(it, "dev/glassworks") }
        // Imports, and fully qualified names anywhere else (`dev.glassworks.s1.X(...)` needs no import), outside strings
        // (the root helper's `/data/data/sh.edm.s1kiosk` path is the app's id, not its code). A name's package is the
        // part before its first capitalised segment.
        val qualified = Regex("""\b(?:sh\.edm|dev\.glassworks)(?:\.[A-Za-z0-9_]+)+""")
        fun pkgOf(name: String) = name.split('.').takeWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")
        fun isApp(name: String) = name.startsWith("sh.edm.") && libPackages.none { name == it || name.startsWith("$it.") } || pkgOf(name) in appPackages
        val string = Regex(""""(?:\\.|[^"\\])*"""")
        val bad = sources.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//").trim().replace(string, "\"\"")
                if (code.startsWith("*") || code.startsWith("/*")) return@filter false // KDoc and comments
                val imp = code.takeIf { it.startsWith("import ") }?.removePrefix("import ")?.substringBefore(" as ")
                if (imp != null) isApp(imp)
                else qualified.findAll(code).any { isApp(it.value) }
            }.map { (i, line) -> "${RepoFiles.rel(f)}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("dev.glassworks imports app code:\n${bad.joinToString("\n")}", emptyList<String>(), bad)
    }
}
