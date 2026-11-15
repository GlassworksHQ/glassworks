plugins {
    id("com.android.application") version "9.3.3" apply false
    id("com.android.library") version "9.3.3" apply false
    // AGP 9 has built-in Kotlin (it bundles KGP 2.2.x). GeckoView 159 is built
    // against kotlin-stdlib 2.4.x, so pin a newer KGP on the classpath; AGP's
    // built-in Kotlin support then uses it.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // The pure-JVM libraries under libs/ (esphome-api).
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    // Maven Central publishing of the libraries (#153), and their KDoc jars. Both Apache-2.0, build time only.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
    // The libraries' public API check (apiCheck, apiDump). Apache-2.0, build time only; its tasks are wired below.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2" apply false
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

// The libraries published to Maven Central as dev.glassworks:<module name> (#153, docs/releasing.md
// "Libraries"). A library opts in by applying com.vanniktech.maven.publish and org.jetbrains.dokka, and
// keeps its own `version` and `description` in its gradle.properties. Only the release workflow has the
// signing key, so publishToMavenLocal runs unsigned without it.
subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        val repo = "https://github.com/GlassworksHQ/glassworks"
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates("dev.glassworks", project.name, project.version.toString())
            publishToMavenCentral(automaticRelease = false)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set(project.name)
                description.set(provider { project.description ?: throw GradleException("${project.path}: set description in gradle.properties") })
                url.set("$repo/tree/main/libs/${project.name}")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("GlassworksHQ")
                        name.set("Glassworks")
                        url.set("https://github.com/GlassworksHQ")
                    }
                }
                scm {
                    url.set(repo)
                    connection.set("scm:git:$repo.git")
                    developerConnection.set("scm:git:ssh://git@github.com/GlassworksHQ/glassworks.git")
                }
            }
        }
        // Kotlin 2.1 metadata and kotlin-stdlib 2.1, so apps on Kotlin 2.0 or newer can compile against them. A
        // compiler reads metadata up to one minor version ahead, and AGP 9's built-in Kotlin is 2.2: built at the
        // repo's 2.4, a fresh minSdk 23 app fails (tools/release/lib-consumer-check.sh).
        // Strict explicit API mode: every public declaration says so, with KDoc. A release can't be changed,
        // so nothing becomes public API by default.
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
            coreLibrariesVersion = "2.1.21"
            explicitApi()
        }
        // The public API, dumped in libs/<name>/api/<name>.api: `apiCheck` (part of `check`, and run by pr.yml's
        // libs-publish) fails when it changes and the dump doesn't; `apiDump` rewrites the dump. Neither KGP's
        // abiValidation nor BCV's own plugin sees an Android library on AGP 9's built-in Kotlin (KT-78025: no
        // classes, an empty dump), so BCV's tasks are wired here, on the classes of the compilation that ships.
        // What BCV's worker runs on, as its own plugin sets it up: ASM (BSD-3-Clause) and kotlin-metadata-jvm
        // (Apache-2.0), build time only.
        val abiRuntime = configurations.create("abiValidationRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies {
            abiRuntime("org.ow2.asm:asm:9.6")
            abiRuntime("org.ow2.asm:asm-tree:9.6")
            abiRuntime("org.jetbrains.kotlin:kotlin-metadata-jvm:${org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)}")
        }
        val apiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
            val kotlin = project.extensions.getByName("kotlin") as org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension<*>
            val shipped = if (project.pluginManager.hasPlugin("com.android.library")) "release" else "main"
            // The compile task's output, not output.classesDirs: that is empty on AGP 9's built-in Kotlin. The
            // libraries are all Kotlin.
            inputClassesDirs.from(kotlin.target.compilations.named(shipped).flatMap { it.compileTaskProvider }
                .flatMap { (it as org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool).destinationDirectory })
            outputApiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
            runtimeClasspath.from(abiRuntime)
        }
        val apiCheck = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
            group = "verification"
            description = "Fails if the public API differs from api/${project.name}.api (update it with apiDump)."
            projectApiFile.set(layout.projectDirectory.file("api/${project.name}.api"))
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
        }
        tasks.register<Copy>("apiDump") {
            description = "Writes the public API to api/${project.name}.api."
            from(apiBuild.flatMap { it.outputApiFile })
            into(layout.projectDirectory.dir("api"))
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(apiCheck) }
        // The README's Kotlin examples are compiled, and run, by the library's ReadmeExample* tests.
        val readmeSnippets = tasks.register<ReadmeSnippets>("readmeSnippets") {
            readme.set(layout.projectDirectory.file("README.md"))
            examples.from(fileTree("src/test") { include("**/ReadmeExample*.kt") })
        }
        tasks.matching { it.name == "test" }.configureEach { dependsOn(readmeSnippets) }
        // Every public declaration has KDoc: the KDoc jar is the libraries' reference, and an undocumented
        // declaration fails it (publishToMavenLocal builds it, so pr.yml's libs-publish runs this).
        pluginManager.withPlugin("org.jetbrains.dokka") {
            extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
                dokkaSourceSets.configureEach { reportUndocumented.set(true) }
                dokkaPublications.configureEach { failOnWarning.set(true) }
            }
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
            compilerOptions {
                languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
                apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
            }
        }
        // The licence in the jar, and in the AAR's classes.jar, as META-INF/LICENSE. Apps drop that path
        // when they package (AGP's default excludes), so it never reaches an APK.
        val licence = tasks.register<LicenceResources>("licenceResources") {
            licence.set(rootProject.file("LICENSE"))
            outputDir.set(layout.buildDirectory.dir("generated/licence"))
        }
        pluginManager.withPlugin("java-library") {
            extensions.getByType<SourceSetContainer>()["main"].resources.srcDir(licence)
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                // AGP writes the library's own compileSdk (37.2) here by default, which stops any app below
                // it. The libraries need nothing above API 23 to compile against (lib-consumer-check.sh).
                defaultConfig.aarMetadata.minCompileSdk = 23
                // A default exclude, in libraries too. The androidTest APK gets two (this library's and
                // esphome-api's): the same text, so keep one.
                packaging.resources.excludes.remove("/META-INF/LICENSE")
                packaging.resources.pickFirsts.add("/META-INF/LICENSE")
            }
            extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
                onVariants { it.sources.resources?.addGeneratedSourceDirectory(licence, LicenceResources::outputDir) }
            }
        }
    }
}

/**
 * Fails when a ```kotlin block of a library's README.md isn't in its ReadmeExample* test sources, line for line
 * (indentation and blank lines aside), so the README can't drift from code that compiles. Gradle blocks (they
 * declare a dev.glassworks dependency) are skipped. Where a test must differ, it says what the README shows
 * instead: `0 /* README: ApiServer.DEFAULT_PORT */` reads as `ApiServer.DEFAULT_PORT`.
 */
abstract class ReadmeSnippets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val readme: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val examples: ConfigurableFileCollection

    @TaskAction
    fun check() {
        fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val source = lines(examples.files.sorted().joinToString("\n") { it.readText() }
            .replace(Regex("""\S+ /\* README: (.+?) \*/"""), "$1"))
        val blocks = Regex("```kotlin\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(readme.get().asFile.readText())
            .map { it.groupValues[1] }.filter { "implementation(\"dev.glassworks:" !in it }.toList()
        if (blocks.isEmpty()) throw GradleException("${readme.get().asFile}: no Kotlin examples")
        val missing = blocks.filter { block -> lines(block).let { want -> source.windowed(want.size).none { it == want } } }
        if (missing.isNotEmpty()) throw GradleException(
            "${readme.get().asFile}: ${missing.size} Kotlin example(s) aren't in ${examples.files.map { it.name }} " +
                "line for line. Copy each into the test so it compiles:\n\n" + missing.joinToString("\n---\n"),
        )
    }
}

abstract class LicenceResources : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val licence: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        licence.get().asFile.copyTo(outputDir.file("META-INF/LICENSE").get().asFile, overwrite = true)
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
            include("**/src/main/**", "**/build.gradle.kts", "**/blueprints/*.yaml") // the panes' blueprints (BlueprintCatalogTest)
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
