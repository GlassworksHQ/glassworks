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
// The legacy engine's test APK (engines/engine-gecko-legacy, #198): -PlegacyAbi or LEGACY_ABI. Unset: the lululemon
// Mirror's first ABI (arm64-v8a: a 64-bit zygote on its MSM8916, frames/lululemon-mirror/research/mirror-abis.md),
// else the S1's. Lazy: only the two legacy modules read it, so no other build depends on the Mirror's frame.
extra["legacyAbi"] = lazy {
    val abi = (providers.gradleProperty("legacyAbi").orNull ?: System.getenv("LEGACY_ABI"))?.trim()?.takeIf { it.isNotEmpty() }
        ?: frameAbis["lululemon-mirror"]?.first() ?: s1Abi
    if (abi !in releaseAbis) throw GradleException("legacyAbi '$abi' must be one of ${releaseAbis.joinToString()}")
    abi
}

// The libraries published to Maven Central as dev.glassworks:<module name> (#153, docs/releasing.md
// "Libraries"). A library opts in by applying com.vanniktech.maven.publish and org.jetbrains.dokka, and
// keeps its own `version` and `description` in its gradle.properties. Only the release workflow has the
// signing key, so publishToMavenLocal runs unsigned without it.
subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        val repo = "https://github.com/GlassworksHQ/glassworks"
        // Code ported from elsewhere keeps its licence: `extraLicenses` in the library's gradle.properties,
        // `<SPDX id>|<upstream copyright line>` entries split by `;` (libs/voice: MicroFrontend.kt). Each is
        // listed in the POM and shipped as META-INF/dev.glassworks/<name>/LICENSE-<ID>, beside libs/<name>/NOTICE
        // (a folder of the library's own, so two libraries' files never share a path in an app).
        val extraLicenses = (findProperty("extraLicenses") as String?).orEmpty().split(';').filter { it.isNotBlank() }.map { entry ->
            val (id, copyright) = entry.split('|', limit = 2).map { it.trim() }.also {
                if (it.size != 2 || it[1].isEmpty()) throw GradleException("$path: extraLicenses entry '$entry' is not '<SPDX id>|<copyright line>'")
            }
            val url = licenceUrls[id] ?: throw GradleException("$path: extraLicenses names $id, which licenceUrls doesn't know")
            Triple(id, copyright, url)
        }
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
                    for ((id, _, licenceUrl) in extraLicenses) license {
                        name.set(id)
                        url.set(licenceUrl)
                        distribution.set("repo")
                        comments.set("Ported code in this library; see META-INF/dev.glassworks/${project.name}/NOTICE")
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
        // Interfaces as Kotlin 2.1 compiles them, whatever languageVersion says: default bodies in DefaultImpls, no
        // interface default methods and no `$jd` bridges. Apps on Kotlin 2.1 and older that implement a library's
        // interface call those DefaultImpls, so a library never drops them (no @JvmDefaultWithoutCompatibility, no
        // other mode). The app's own modules build without them (build-logic/glassworks.android).
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE)
        }
        // The licence in the jar, and in the AAR's classes.jar, as META-INF/LICENSE (which apps drop when they
        // package: one of AGP's default excludes), with any extraLicenses and the NOTICE in the library's own
        // folder, META-INF/dev.glassworks/<name>/. An app that depends on two libraries with the same extra
        // licence (voice's and ha-webrtc's BSD-3-Clause) gets both files, at two paths, instead of a merge error.
        val licence = tasks.register<LicenceResources>("licenceResources") {
            licence.set(rootProject.file("LICENSE"))
            artifact.set(project.name)
            extras.set(extraLicenses.map { (id, copyright, _) -> "$id|$copyright" })
            licenceTexts.set(rootProject.layout.projectDirectory.dir("LICENSES"))
            layout.projectDirectory.file("NOTICE").asFile.takeIf { it.isFile }?.let { notice.set(it) }
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

// An app built from these modules (the S1 app) gets its licence texts from its own licence pages (assets/
// licenses, THIRD-PARTY-NOTICES), not from the libraries' META-INF: drop their extraLicenses files and NOTICEs as
// AGP drops META-INF/LICENSE by default.
subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            packaging.resources.excludes += "/META-INF/dev.glassworks/**"
            // As before: any other dependency's META-INF/LICENSE-* stays out of the APK too.
            packaging.resources.excludes += "/META-INF/LICENSE-*"
        }
    }
}

// `./gradlew test` (CI's app job) runs build-logic's tests too: an included build's tasks aren't part of it otherwise.
tasks.register("test") { dependsOn(gradle.includedBuild("build-logic").task(":test")) }

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
        val blocks = Regex("```kotlin\r?\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(readme.get().asFile.readText())
            .map { it.groupValues[1] }.filter { "implementation(\"dev.glassworks:" !in it }.toList()
        if (blocks.isEmpty()) throw GradleException("${readme.get().asFile}: no Kotlin examples")
        val missing = blocks.filter { block -> lines(block).let { want -> source.windowed(want.size).none { it == want } } }
        if (missing.isNotEmpty()) throw GradleException(
            "${readme.get().asFile}: ${missing.size} Kotlin example(s) aren't in ${examples.files.map { it.name }} " +
                "line for line. Copy each into the test so it compiles:\n\n" + missing.joinToString("\n---\n"),
        )
    }
}

/** The licence URLs a library's POM may name in `extraLicenses`, by SPDX id. */
val licenceUrls = mapOf(
    "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
    "BSD-3-Clause" to "https://opensource.org/license/bsd-3-clause",
)

/**
 * META-INF/LICENSE (the repo's MIT licence), and in the library's own folder, META-INF/dev.glassworks/[artifact]/:
 * for each of [extras] (`<SPDX id>|<copyright line>`) LICENSE-<ID> from LICENSES/<id>.txt under that copyright line
 * (in place of a template's `Copyright (c) <year> <owner>.` line), and NOTICE from [notice].
 */
abstract class LicenceResources : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val licence: RegularFileProperty
    @get:Input abstract val artifact: Property<String>
    @get:Input abstract val extras: ListProperty<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val licenceTexts: DirectoryProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val notice: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val metaInf = outputDir.dir("META-INF").get().asFile
        metaInf.deleteRecursively()
        licence.get().asFile.copyTo(File(metaInf, "LICENSE"))
        val own = File(metaInf, "dev.glassworks/${artifact.get()}").apply { mkdirs() }
        for (entry in extras.get()) {
            val (id, copyright) = entry.split('|', limit = 2)
            val text = licenceTexts.file("$id.txt").get().asFile.readText()
            val template = Regex("""^Copyright \(c\) <year> <owner>\.\s*\n""")
            val body = if (template.containsMatchIn(text)) text.replaceFirst(template, "$copyright\n") else "$copyright\n\n$text"
            File(own, "LICENSE-${id.uppercase()}").writeText(body)
        }
        if (notice.isPresent) notice.get().asFile.copyTo(File(own, "NOTICE"))
    }
}
