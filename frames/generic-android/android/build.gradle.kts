// Glassworks for generic Android (#23): the core and the panes that need no special hardware, as an app any
// Android 8+ device installs (frames/generic-android). Its own applicationId and signing key: it never installs
// over S1 Kiosk (sh.edm.s1kiosk), and the two can sit side by side on one device.
plugins {
    id("com.android.application")
    id("glassworks.android")
}

// Release version: -PgenericVersion=1.2.3 or GENERIC_VERSION=1.2.3. versionCode = major*10000 + minor*100 + patch,
// the rule the app compares releases with (update/AppVersion.kt), as for S1 Kiosk.
val genericVersion: String? = ((findProperty("genericVersion") as String?) ?: System.getenv("GENERIC_VERSION"))
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
val genericVersionCode: Int? = genericVersion?.let { v ->
    val m = Regex("""^(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)$""").matchEntire(v)
        ?: throw GradleException("GENERIC_VERSION '$v' must be MAJOR.MINOR.PATCH with minor and patch below 100")
    val (major, minor, patch) = m.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

// A test build's commit (-Ps1TestBuild or S1_TEST_BUILD, as S1 Kiosk's), shown next to the version.
val testBuild: String = ((findProperty("s1TestBuild") as String?) ?: System.getenv("S1_TEST_BUILD")).orEmpty().trim()
    .also { if (!Regex("^[0-9a-f]{0,40}$").matches(it)) throw GradleException("S1_TEST_BUILD '$it' must be a commit hash") }

// The ABI this APK is built for (-Ps1Abi or S1_ABI; the root build.gradle.kts picks it for every module). This
// frame runs arm64-v8a and armeabi-v7a (frames/generic-android/design/frame.json): build each with -Ps1Abi.
val s1Abi: String by rootProject.extra

// Release signing, from the environment only, with this app's own key (frames/generic-android/README.md,
// "Signing"). Never S1 Kiosk's key. Nothing set: an unsigned release APK. Partly set, or
// GENERIC_REQUIRE_RELEASE_SIGNING=true without a key: the release build fails.
val signingEnv = listOf("GENERIC_KEYSTORE_FILE", "GENERIC_KEYSTORE_PASSWORD", "GENERIC_KEY_ALIAS", "GENERIC_KEY_PASSWORD")
    .associateWith { System.getenv(it)?.takeIf(String::isNotEmpty) }
val haveReleaseKey = signingEnv.values.all { it != null }

android {
    namespace = "dev.glassworks.generic_android"
    defaultConfig {
        // Never sh.edm.s1kiosk: this is another app, with its own data and key.
        applicationId = "dev.glassworks.generic_android"
        // minSdk is glassworks.android's 26 (Android 8.0). API 23 to 25 is the separate legacy build (#198).
        // 35: Android 17 updates an app without a tap only if the new APK targets 35+ (#277).
        targetSdk = 35
        versionCode = genericVersionCode ?: 1
        versionName = genericVersion ?: "0.1.0"
        buildConfigField("String", "TEST_BUILD", "\"$testBuild\"")
        ndk { abiFilters += listOf(s1Abi) }
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(signingEnv.getValue("GENERIC_KEYSTORE_FILE")!!)
                storePassword = signingEnv.getValue("GENERIC_KEYSTORE_PASSWORD")
                keyAlias = signingEnv.getValue("GENERIC_KEY_ALIAS")
                keyPassword = signingEnv.getValue("GENERIC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (haveReleaseKey) signingConfigs.getByName("release") else null
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    // The GeckoView shadows and harness that run the real MainActivity (testing/gecko, shared with S1 Kiosk's tests).
    sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/gecko"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("testing/gecko"))
}

dependencies {
    // The core, the Gecko engine, and the panes that need no special hardware: Assist (with its timers) and
    // Sendspin. Nothing S1: no radio, wall base, time zone or root side.
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    implementation(project(":engine-gecko"))
    implementation(project(":pane-assist"))
    implementation(project(":pane-timers"))
    implementation(project(":pane-sendspin"))
    implementation(project(":esphome-api-android"))
    implementation(project(":improv"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}

// The licences screen and the notices check are S1 Kiosk's (frames/aqara-s1-plus/android/build.gradle.kts),
// copied as they are. Both apps ship the same THIRD-PARTY-NOTICES.md.
// Open source licences screen (hidden settings → "Open source licences"): the repo's
// THIRD-PARTY-NOTICES.md, LICENSE and LICENSES/*.txt are copied into the APK as assets/licenses/ at
// build time, so the app always shows the committed texts. Local files only: no network, no plugin.
abstract class LicenceAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val notices: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val projectLicence: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val texts: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val fs: FileSystemOperations

    @TaskAction fun copy() {
        fs.sync {
            into(outputDir.dir("licenses"))
            from(notices)
            from(projectLicence)
            from(texts) { include("*.txt"); into("texts") }
        }
    }
}
val repoRoot = rootProject.layout.projectDirectory
val thirdPartyNotices = repoRoot.file("THIRD-PARTY-NOTICES.md")
val licenceAssets = tasks.register<LicenceAssets>("licenceAssets") {
    notices.set(thirdPartyNotices)
    projectLicence.set(repoRoot.file("LICENSE"))
    texts.set(repoRoot.dir("LICENSES"))
    outputDir.set(layout.buildDirectory.dir("generated/licence-assets"))
}
androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(licenceAssets, LicenceAssets::outputDir) }
}

// Every library in the release APK must be listed, with its version, in THIRD-PARTY-NOTICES.md
// (`group:name:version`). Runs before release builds, so a dependency bump can't ship without its notice.
// Reads only the resolved dependency graph Gradle already has for the build.
val checkThirdPartyNotices = tasks.register("checkThirdPartyNotices") {
    val modules = configurations.named("releaseRuntimeClasspath").flatMap { c ->
        c.incoming.resolutionResult.rootComponent.map { root ->
            val seen = sortedSetOf<String>()
            val todo = ArrayDeque(listOf(root))
            val visited = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
            while (todo.isNotEmpty()) {
                val comp = todo.removeFirst()
                if (!visited.add(comp)) continue
                (comp.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                    ?.let { seen += "${it.group}:${it.module}:${it.version}" }
                comp.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                    .forEach { todo += it.selected }
            }
            seen.toList()
        }
    }
    val noticesFile = thirdPartyNotices
    inputs.file(noticesFile)
    inputs.property("modules", modules)
    doLast {
        val text = noticesFile.asFile.readText()
        val missing = modules.get().filter { "`$it`" !in text }
        if (missing.isNotEmpty()) throw GradleException(
            "THIRD-PARTY-NOTICES.md does not list these APK libraries (add them, with licence and source):\n  " +
                missing.joinToString("\n  "))
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkThirdPartyNotices) }


val checkReleaseSigning by tasks.registering {
    val partial = signingEnv.filterValues { it == null }.keys.takeIf { it.size in 1..3 }
    val required = System.getenv("GENERIC_REQUIRE_RELEASE_SIGNING") == "true"
    val keyFile = signingEnv["GENERIC_KEYSTORE_FILE"]
    val keyFileMissing = keyFile != null && !file(keyFile).isFile
    doLast {
        if (partial != null) throw GradleException("release signing: missing ${partial.joinToString()} (see docs/releasing.md)")
        if (keyFileMissing) throw GradleException("release signing: GENERIC_KEYSTORE_FILE does not exist")
        if (required && keyFile == null) throw GradleException("release signing required (GENERIC_REQUIRE_RELEASE_SIGNING) but no GENERIC_KEYSTORE_* set")
        if (keyFile == null) logger.warn("release APK is UNSIGNED: set GENERIC_KEYSTORE_FILE, GENERIC_KEYSTORE_PASSWORD, GENERIC_KEY_ALIAS, GENERIC_KEY_PASSWORD to sign it")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }
