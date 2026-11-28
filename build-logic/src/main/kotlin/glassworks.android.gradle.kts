// What every Android module builds with (#282). Each one applies this beside com.android.library or
// com.android.application, and its own build file keeps only its namespace, its dependencies and what differs
// (minSdk 23 for the libraries, the apps' testing/gecko). Its android {} block runs after this, so it wins.
import com.android.build.api.dsl.CommonExtension

// The libraries in libs/ test on their own: no RepoFiles, no repo inputs (RepoFiles.libraries: a module in libs/).
val library = projectDir.parentFile == rootProject.file("libs")

for (agp in listOf("com.android.library", "com.android.application")) {
    pluginManager.withPlugin(agp) {
        extensions.getByType(CommonExtension::class.java).apply {
            // GeckoView 159 requires compileSdk 37.2. It only affects compilation: the apps still run on API 26 up.
            compileSdk {
                version = release(37) { minorApiLevel = 2 }
            }
            // Android 8.0. The libraries and the legacy build set 23.
            defaultConfig.minSdk = 26
            compileOptions.sourceCompatibility = JavaVersion.VERSION_17
            compileOptions.targetCompatibility = JavaVersion.VERSION_17
            // RepoFiles, the tests' way to find files across every module from the repo root (#21), for a module
            // with tests of its own (the legacy test APK has none).
            if (!library && file("src/test").isDirectory) {
                sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/kotlin"))
            }
        }
    }
}

// Interface default methods without Kotlin's compatibility bridges, in every module that isn't published to Maven
// (the core, the pane API, the engines, the panes, the apps; docs/research/art-start-path.md). Kotlin's default mode
// also gives each interface with a default body a static `access$<name>$jd` bridge that invoke-supers the interface
// itself. Android 10's verifier soft-fails that ("Cannot invoke-super on self as interface"), so the interface, and
// every class that implements it, was verified again at run time on every start: 136 classes on the S1, MainActivity
// and VitroPage among them. StartPathVerifyTest fails on a `$jd` bridge in the APK.
// A library published to Maven builds in DISABLE mode instead (DefaultImpls, no bridges either), which its users
// compiled against: set in the root build.gradle.kts, and here too. The mode is read when the task runs, after every
// plugin is applied, so it doesn't depend on when the publishing plugin comes in.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmDefault.set(provider {
        if (pluginManager.hasPlugin("com.vanniktech.maven.publish")) org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.DISABLE
        else org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    })
}

// Robolectric fetches its Android jars (android-all-instrumented) itself while the tests run, from Maven Central by
// default, and Central's 429s (too many requests) failed whole test classes in CI. This sends it to Google's mirror
// of Central, the repository settings.gradle.kts asks first. Robolectric 4.17 takes one repository only, so there's
// no fallback to Central here; the jars it already has in ~/.m2 (CI's robolectric cache) need no fetch at all.
// docs/ci.md, Caches.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
}

if (!library) {
    tasks.withType<Test>().configureEach {
        // -Ps1MockupDir writes the *Mockups PNGs (a module without mockups then runs none, not fails), and
        // -Ps1RegenGolden=true rewrites the pin tests' goldens.
        (project.findProperty("s1MockupDir") as String?)?.let {
            systemProperty("s1.mockupDir", it)
            filter.isFailOnNoMatchingTests = false
        }
        (project.findProperty("s1RegenGolden") as String?)?.let { systemProperty("s1.regenGolden", it) }
        readsRepoFiles(rootProject.projectDir)
    }
}

/**
 * What tests read from the repo beyond their own module (RepoFiles, testing/kotlin), declared as inputs so a change
 * to any of them re-runs the tests instead of taking them UP-TO-DATE or FROM-CACHE: every module's src/main and
 * build file under RepoFiles' search roots (a pane.json or icon.svg, which no compile task takes in, #231, and
 * another module's sources that a confinement scan reads), and the repo files tests open. Relative paths keep the
 * build cache relocatable.
 */
fun Test.readsRepoFiles(repo: File) {
    // ApkAbiListsTest reads the workflows, every frame's frame.json and frame-abis.sh; WakeWordsTest the host
    // fetch script.
    inputs.dir(File(repo, ".github/workflows")).withPropertyName("workflows")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(File(repo, "frames")) { include("*/design/frame.json") }, File(repo, "tools/release/frame-abis.sh"))
        .withPropertyName("frameAbis").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(File(repo, "tools/voice/fetch-models.sh")).withPropertyName("fetchModels")
        .withPathSensitivity(PathSensitivity.NONE)
    // CaptivePortalTest: the app's portal ports are the S1 radio helper's (#197).
    inputs.file(File(repo, "frames/aqara-s1-plus/tools/radio/s1kiosk_radio.sh")).withPropertyName("radioHelper")
        .withPathSensitivity(PathSensitivity.NONE)
    val roots = listOf("libs", "core", "pane-api", "engines", "panes", "frames") // RepoFiles.SEARCH, by top folder
    inputs.files(roots.map { dir ->
        fileTree(File(repo, dir)) {
            // The panes' blueprints (BlueprintCatalogTest); the generic app's legacy manifest (LegacyBuildTest).
            include("**/src/main/**", "**/src/legacy/**", "**/build.gradle.kts", "**/blueprints/*.yaml")
            exclude("**/build/**", "**/node_modules/**", "**/.gradle/**")
        }
    }).withPropertyName("repoModules").withPathSensitivity(PathSensitivity.RELATIVE)
    // OldPackageGuardTest (#22): a new file under any sh/edm folder re-runs it.
    inputs.files(fileTree(repo) {
        include("**/sh/edm/**")
        exclude("**/build/**", "**/node_modules/**", "**/.gradle/**", "**/.git/**", "**/.next/**", "dumps/**")
    }).withPropertyName("oldPackage").withPathSensitivity(PathSensitivity.RELATIVE)
    // DeviceNamesTest reads a frame app's own tests and mockups, and the shots they save (#502): a new Mirror shot,
    // or a shot drawn from another frame, re-runs it instead of taking it UP-TO-DATE.
    inputs.files(fileTree(File(repo, "frames")) { include("*/android/src/test/**/*.kt") })
        .withPropertyName("frameAppTests").withPathSensitivity(PathSensitivity.RELATIVE)
    val files = listOf(
        "CHANGELOG.md", "README.md", "THIRD-PARTY-NOTICES.md", "kiln/packages/brand/src/tokens.css",
        "kiln/packages/setup-channel/test/aqara-s1-plus-transcript.json", "branding/glass-tile/bootanimation.zip",
        "frames/aqara-s1-plus/tools/bracket/base_emulator.py", "build.gradle.kts",
        "build-logic/src/main/kotlin/glassworks.android.gradle.kts", // LegacyTestAppManifestTest: the default minSdk
        "frames/generic-android/installer/src/devices.ts", // GenericAndroidKnownTest: Kiln's models with a frame
    )
    inputs.files(files.map { File(repo, it) }, fileTree(File(repo, "LICENSES")), fileTree(File(repo, "docs")) { include("**/*.md") },
        fileTree(File(repo, "frames")) { include("*/README.md") })
        .withPropertyName("repoFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
