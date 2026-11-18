/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.testing

import java.io.File

/**
 * Finds files for tests that read the source tree (confinement scans, the pane catalog, goldens, the
 * bundled font), from the repo root and across every Gradle module, wherever the module sits (#21).
 *
 * A module is a folder with `src/main` under one of [SEARCH]: `core`, `pane-api`, `engines/<x>`, `panes/<x>`,
 * `libs/<x>`, `frames/<id>/android` and `frames/<id>/panes/<x>`. A test finds the same files from whichever module
 * runs it, so moving code between modules changes no test.
 *
 * Nothing here skips. A lookup that finds nothing, or fewer files than the caller expects, fails: a
 * scan that silently sees nothing would let a confinement test pass with nothing checked.
 *
 * Compiled into each module's tests from this one folder (`testing/kotlin`, a test source folder of every
 * module but the libraries: glassworks.android in build-logic). `packages/frame-card/scripts/panes.mjs`
 * searches the same [SEARCH].
 */
object RepoFiles : RepoTree({
    generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "THIRD-PARTY-NOTICES.md").isFile && File(it, "frames").isDirectory }
        ?: throw AssertionError("no repo root (THIRD-PARTY-NOTICES.md and frames/) at or above ${File("").absolutePath}")
})

/** [RepoFiles] over any tree: the repo, or a made-up layout in a test. */
open class RepoTree(findRoot: () -> File) {
    /** Where modules are looked for, relative to the repo root. `*` is one folder level. */
    val SEARCH = listOf("libs", "core", "pane-api", "engines", "panes", "frames/*/android", "frames/*/panes")

    /** Folders never walked into: build output, caches, and a module's own sources. */
    private val skip = setOf("build", ".gradle", ".kotlin", "node_modules", "src", ".git")

    /** The repo root (for [RepoFiles]: the first folder at or above the working directory with THIRD-PARTY-NOTICES.md and frames/). */
    val root: File by lazy(findRoot)

    /** Every module folder, sorted by path. Fails if there is none. */
    val modules: List<File> by lazy {
        val found = SEARCH.flatMap(::expand).flatMap { top ->
            top.walkTopDown().onEnter { it == top || it.name !in skip }.filter { File(it, "src/main").isDirectory }.toList()
        }.distinct().sortedBy { rel(it) }
        if (found.isEmpty()) throw AssertionError("no module (a folder with src/main) under ${SEARCH.joinToString()} in $root")
        found
    }

    /** The libraries: modules in a `libs` folder. They don't depend on Glassworks. */
    val libraries: List<File> get() = modules.filter { m -> "libs" in rel(m).split('/').dropLast(1) }

    /** Every other module: the app, the core, the pane API, engines, panes and frames. */
    val appModules: List<File> get() = modules - libraries.toSet()

    /** The S1 app's module: the one whose build file sets `applicationId = "sh.edm.s1kiosk"`. */
    val s1App: File by lazy {
        val apps = modules.filter { File(it, "build.gradle.kts").let { b -> b.isFile && "applicationId = \"sh.edm.s1kiosk\"" in b.readText() } }
        apps.singleOrNull() ?: throw AssertionError("expected one module with applicationId sh.edm.s1kiosk, found ${apps.map(::rel)}")
    }

    /**
     * Every file under `src/<sourceSet>` of [inModules] that [pick] keeps, sorted by path. Fails when
     * there are fewer than [atLeast]: pass a floor well under today's count, so a scan that stops seeing
     * its files fails instead of passing.
     */
    fun sources(
        atLeast: Int,
        sourceSet: String = "main",
        inModules: List<File> = modules,
        pick: (File) -> Boolean = { true },
    ): List<File> {
        val found = inModules.flatMap { m ->
            File(m, "src/$sourceSet").walkTopDown().filter { it.isFile && pick(it) }.toList()
        }.sortedBy { rel(it) }
        if (found.size < atLeast) {
            throw AssertionError("found ${found.size} files under src/$sourceSet, expected at least $atLeast, in ${inModules.map(::rel)}")
        }
        return found
    }

    /** Kotlin and Java sources under `src/main` of [inModules], at least [atLeast] of them. */
    fun code(atLeast: Int, inModules: List<File> = modules): List<File> =
        sources(atLeast, inModules = inModules) { it.extension == "kt" || it.extension == "java" }

    /**
     * [f]'s path inside its source root: `dev/glassworks/platform/Platform.kt` for
     * `<module>/src/main/java/dev/glassworks/platform/Platform.kt` (or `kotlin/`), and the path below
     * `src/<set>` for other files (`res/font/inter.ttf`).
     */
    fun packagePath(f: File): String {
        val parts = rel(f).split('/')
        val src = parts.indexOf("src")
        require(src >= 0 && src + 2 < parts.size) { "${rel(f)} is not in a module's src/<set>" }
        val inSet = parts.drop(src + 2)
        return (if (inSet.first() == "java" || inSet.first() == "kotlin") inSet.drop(1) else inSet).joinToString("/")
    }

    /** Is [f] in package folder [pkg] (`dev/glassworks/platform`) or below it, in any module? */
    fun inPackage(f: File, pkg: String): Boolean = packagePath(f).startsWith(pkg.trimEnd('/') + "/")

    /** Every existing `src/<sourceSet>/{java,kotlin}/<pkg>` folder across the modules (a package can span modules). */
    fun packageDirs(pkg: String, sourceSet: String = "main"): List<File> =
        modules.flatMap { m -> listOf("java", "kotlin").map { File(m, "src/$sourceSet/$it/$pkg") } }.filter { it.isDirectory }

    /** The one source file at package path [path] (`dev/glassworks/device/PanelDevice.kt`), in whichever module holds it. */
    fun source(path: String, sourceSet: String = "main"): File =
        one(path, modules.flatMap { m -> listOf("java", "kotlin").map { File(m, "src/$sourceSet/$it/$path") } })

    /** The one module file at [path] below its module folder (`src/main/res/font/inter.ttf`, `src/test/resources/x.golden.txt`). */
    fun inModule(path: String): File = one(path, modules.map { File(it, path) })

    /** A file at [path] from the repo root (`CHANGELOG.md`, `frames/aqara-s1-plus/tools/bracket/base_emulator.py`). Fails if it isn't there. */
    fun file(path: String): File = File(root, path).also { if (!it.exists()) throw AssertionError("$path is not in the repo at $root") }

    /** [f]'s path from the repo root, with `/`, for messages. */
    fun rel(f: File): String = f.absoluteFile.relativeTo(root).invariantSeparatorsPath

    private fun one(what: String, candidates: List<File>): File {
        val hits = candidates.filter { it.exists() }
        return hits.singleOrNull() ?: throw AssertionError(
            if (hits.isEmpty()) "$what is in no module (${modules.map(::rel)})" else "$what is in more than one module: ${hits.map(::rel)}",
        )
    }

    /** The folders [pattern] names, `*` matching one folder level. */
    private fun expand(pattern: String): List<File> =
        pattern.split('/').fold(listOf(root)) { dirs, part ->
            if (part == "*") dirs.flatMap { d -> d.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name } }
            else dirs.map { File(it, part) }.filter { it.isDirectory }
        }
}
