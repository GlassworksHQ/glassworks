plugins {
    id("com.android.application") version "9.3.3" apply false
    id("com.android.library") version "9.3.3" apply false
    // AGP 9 has built-in Kotlin (it bundles KGP 2.2.x). GeckoView 159 is built
    // against kotlin-stdlib 2.4.x, so pin a newer KGP on the classpath; AGP's
    // built-in Kotlin support then uses it.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // The pure-JVM libraries under libs/ (esphome-api).
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}

// The ABI this APK is built for: -Ps1Abi=arm64-v8a or S1_ABI. Unset: the S1's. One APK per ABI, because
// each per-ABI GeckoView AAR carries its own classes.jar and omni.ja, so two cannot share a classpath
// (docs/releasing.md "ABIs"). Every ABI's APK has the same versionCode. Each frame declares the ABIs its
// device runs (`abis` in frames/<id>/design/frame.json), and the release builds them all (#98).
val frameAbis: Map<String, List<String>> = layout.projectDirectory.dir("frames").asFile.listFiles().orEmpty()
    .map { File(it, "design/frame.json") }.filter { it.isFile }.sortedBy { it.parentFile.parentFile.name }
    .associate { f ->
        val abis = ((groovy.json.JsonSlurper().parse(f) as? Map<*, *>)?.get("abis") as? List<*>)
            ?.takeIf { it.isNotEmpty() && it.all { a -> a is String } }
            ?: throw GradleException("$f: abis must be a non-empty list of ABI names, e.g. [\"armeabi-v7a\"]")
        f.parentFile.parentFile.name to abis.map { it as String }
    }
val releaseAbis = frameAbis.values.flatten().distinct()
    .ifEmpty { throw GradleException("no frame declares abis (frames/*/design/frame.json)") }
val s1Abi: String = ((findProperty("s1Abi") as String?) ?: System.getenv("S1_ABI"))
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: frameAbis["aqara-s1-plus"]?.first() ?: throw GradleException("frames/aqara-s1-plus/design/frame.json is missing")
if (s1Abi !in releaseAbis) throw GradleException("s1Abi '$s1Abi' must be one of ${releaseAbis.joinToString()}")
extra["s1Abi"] = s1Abi

// The test settings of every Glassworks module (not the libraries in libs/, which test on their own):
// -Ps1MockupDir writes the *Mockups PNGs (a module without mockups then runs none, not fails), and
// -Ps1RegenGolden=true rewrites the pin tests' goldens. The repo files the tests read are inputs (#21).
val libraries = setOf(":esphome-api", ":esphome-api-android", ":improv", ":bluetooth-proxy")
subprojects {
    if (path in libraries) return@subprojects
    tasks.withType<Test>().configureEach {
        (project.findProperty("s1MockupDir") as String?)?.let {
            systemProperty("s1.mockupDir", it)
            filter.isFailOnNoMatchingTests = false
        }
        (project.findProperty("s1RegenGolden") as String?)?.let { systemProperty("s1.regenGolden", it) }
        // ApkAbiListsTest reads the workflows, every frame's frame.json and frame-abis.sh; WakeWordsTest the
        // host fetch script. Declared as inputs, so a change re-runs the tests instead of taking them UP-TO-DATE
        // or FROM-CACHE. Build files and the web extension are under readsRepoFiles' roots.
        inputs.dir(rootProject.file(".github/workflows")).withPropertyName("workflows")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(fileTree(rootProject.file("frames")) { include("*/design/frame.json") }, rootProject.file("tools/release/frame-abis.sh"))
            .withPropertyName("frameAbis").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(rootProject.file("tools/voice/fetch-models.sh")).withPropertyName("fetchModels")
            .withPathSensitivity(PathSensitivity.NONE)
        // The pane catalog (#231), the confinement scans, goldens, the font and the repo files the tests read.
        readsRepoFiles(rootProject.projectDir)
    }
}

/**
 * What tests read from the repo beyond their own module (RepoFiles, testing/kotlin): every module's src/main
 * and build file under RepoFiles' search roots, and the repo files that tests open. Declared as inputs, so a
 * change to any of them re-runs the tests instead of taking them UP-TO-DATE or FROM-CACHE: a pane.json or
 * icon.svg, which no compile task takes in (#231), and, after #21's split, another module's sources that a
 * confinement scan in this module reads. Relative paths keep the build cache relocatable. Every module's
 * test task calls it once there are several (#21).
 */
fun Test.readsRepoFiles(repo: File) {
    val roots = listOf("app", "libs", "core", "pane-api", "engines", "panes", "frames") // RepoFiles.SEARCH, by top folder
    inputs.files(roots.map { dir ->
        fileTree(File(repo, dir)) {
            include("**/src/main/**", "**/build.gradle.kts")
            exclude("**/build/**", "**/node_modules/**", "**/.gradle/**")
        }
    }).withPropertyName("repoModules").withPathSensitivity(PathSensitivity.RELATIVE)
    val files = listOf(
        "CHANGELOG.md", "README.md", "THIRD-PARTY-NOTICES.md", "kiln/packages/brand/src/tokens.css",
        "kiln/packages/setup-channel/test/s1-kiosk-transcript.json", "branding/glass-tile/bootanimation.zip",
        "tools/bracket/base_emulator.py", "build.gradle.kts",
    )
    inputs.files(files.map { File(repo, it) }, fileTree(File(repo, "LICENSES")), fileTree(File(repo, "docs")) { include("**/*.md") },
        fileTree(File(repo, "frames")) { include("*/README.md") })
        .withPropertyName("repoFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
