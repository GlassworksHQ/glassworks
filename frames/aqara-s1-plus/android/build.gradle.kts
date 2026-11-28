plugins {
    id("com.android.application")
    id("glassworks.android")
}

// Release version: -Ps1Version=1.2.3 or S1_VERSION=1.2.3 (the release workflow sets it from the tag).
// versionCode = major*10000 + minor*100 + patch, the same rule the app uses to compare releases
// (update/AppVersion.kt). Unset: the development defaults below, so debug builds are unchanged.
val s1Version: String? = ((findProperty("s1Version") as String?) ?: System.getenv("S1_VERSION"))
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
val s1VersionCode: Int? = s1Version?.let { v ->
    val m = Regex("""^(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)$""").matchEntire(v)
        ?: throw GradleException("S1_VERSION '$v' must be MAJOR.MINOR.PATCH with minor and patch below 100")
    val (major, minor, patch) = m.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

// A test build's commit: -Ps1TestBuild=28c157d or S1_TEST_BUILD (.github/workflows/test-build.yml sets it).
// Shown next to the version (AppBuild.shown); releases leave it empty. It never changes versionName or versionCode.
val s1TestBuild: String = ((findProperty("s1TestBuild") as String?) ?: System.getenv("S1_TEST_BUILD")).orEmpty().trim()
    .also { if (!Regex("^[0-9a-f]{0,40}$").matches(it)) throw GradleException("S1_TEST_BUILD '$it' must be a commit hash") }

// The ABI this APK is built for (-Ps1Abi or S1_ABI; the root build.gradle.kts picks it for every module).
val s1Abi: String by rootProject.extra

// Release signing comes only from the environment (CI secrets or a local shell; docs/releasing.md).
// Nothing set: the release APK is unsigned (never signed with the debug key). Partly set, or
// S1_REQUIRE_RELEASE_SIGNING=true without a key: the release build fails (checkReleaseSigning).
val signingEnv = listOf("S1_KEYSTORE_FILE", "S1_KEYSTORE_PASSWORD", "S1_KEY_ALIAS", "S1_KEY_PASSWORD")
    .associateWith { System.getenv(it)?.takeIf(String::isNotEmpty) }
val haveReleaseKey = signingEnv.values.all { it != null }

android {
    namespace = "dev.glassworks.s1"
    defaultConfig {
        applicationId = "sh.edm.s1kiosk"
        // Opts into newer Android rules on newer devices only; minSdk decides who can install it.
        // 35: Android 17 updates an app without a tap only if the new APK targets 35+ (#277,
        // docs/releasing.md "Raising targetSdk").
        targetSdk = 35
        versionCode = s1VersionCode ?: 1
        versionName = s1Version ?: "0.1.0"
        buildConfigField("String", "TEST_BUILD", "\"$s1TestBuild\"")

        // The Panel Hub S1 Plus runs 32-bit Android 10 (A133 with an armeabi-v7a userspace): the default.
        ndk { abiFilters += listOf(s1Abi) }

        // No hard-coded dashboard: the default URL setting is "auto" (Home Assistant is discovered,
        // see docs/onboarding.md). A URL saved in the prefs is kept.
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(signingEnv.getValue("S1_KEYSTORE_FILE")!!)
                storePassword = signingEnv.getValue("S1_KEYSTORE_PASSWORD")
                keyAlias = signingEnv.getValue("S1_KEY_ALIAS")
                keyPassword = signingEnv.getValue("S1_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No keystore is committed: signed only with the S1_* environment above, else unsigned.
            signingConfig = if (haveReleaseKey) signingConfigs.getByName("release") else null
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // The signed radio bundle (frames/aqara-s1-plus/docs/radio.md "Radio bundles"): S1_RADIO_BUNDLE_ASSETS is a directory
    // holding radio-bundle/ (frames/aqara-s1-plus/tools/radio/make-bundle.py --out). The release workflow sets it; other
    // builds ship no bundle and the app leaves the panel's root side as it is.
    System.getenv("S1_RADIO_BUNDLE_ASSETS")?.takeIf { it.isNotEmpty() }?.let { dir ->
        if (!file("$dir/radio-bundle/manifest").isFile || !file("$dir/radio-bundle/manifest.sig").isFile) {
            throw GradleException("S1_RADIO_BUNDLE_ASSETS=$dir has no radio-bundle/manifest(.sig)")
        }
        sourceSets.getByName("main").assets.srcDir(dir)
    }

    // The GeckoView shadows and harness that run the real MainActivity (testing/gecko, shared with the generic app, #23).
    sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/gecko"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("testing/gecko"))
}

dependencies {
    // Libraries extracted from this app (libs/). esphome-api brings BouncyCastle (Noise), which
    // Sendspin's encryption also uses.
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    implementation(project(":engine-gecko"))
    implementation(project(":engine-vitro"))
    // Vitro's cameras over WebRTC (libs/ha-webrtc on libwebrtc), registered when Vitro is on.
    implementation(project(":engine-vitro-webrtc"))
    implementation(project(":pane-diagnostics"))
    implementation(project(":pane-bluetooth-proxy"))
    implementation(project(":pane-camera"))
    implementation(project(":pane-notifications"))
    implementation(project(":pane-assist"))
    implementation(project(":pane-timers"))
    implementation(project(":pane-sendspin"))
    implementation(project(":aqara-s1-plus-wall-base"))
    implementation(project(":pane-zigbee-thread"))
    implementation(project(":esphome-api-android")) // brings :esphome-api
    implementation(project(":improv"))
    implementation(project(":bluetooth-proxy")) // the Bluetooth proxy pane's engine (#121)

    // JVM unit tests for the ESPHome API (against aioesphomeapi) and the wake word engines.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // Mock-up screenshots of the real settings/setup views (RadioMockups, only with -Ps1MockupDir=<dir>).
    testImplementation("org.robolectric:robolectric:4.17")
    // The panes' blueprints parsed as YAML (BlueprintCatalogTest): the version GeckoView already puts in the APK.
    testImplementation("org.yaml:snakeyaml:2.2")
    // StartPathVerifyTest reads the app's compiled classes: the ASM Robolectric already brings (BSD-3-Clause), tests only.
    testImplementation("org.ow2.asm:asm:9.10.1")
}

// StartPathVerifyTest's API database: api-versions.xml beside the compile SDK's android.jar.
val apiVersions = androidComponents.sdkComponents.bootClasspath.map { jars ->
    jars.map { it.asFile }.single { it.name == "android.jar" }.resolveSibling("data/api-versions.xml")
}
tasks.withType<Test>().configureEach {
    inputs.file(apiVersions).withPropertyName("apiVersions").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dglassworks.apiVersions=${apiVersions.get().path}") })
}

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
// Reads only the resolved dependency graph Gradle already has for the build (apkModules, in build-logic), and fails
// when a dependency in it didn't resolve.
val checkThirdPartyNotices = tasks.register("checkThirdPartyNotices") {
    val modules = apkModules(configurations.named("releaseRuntimeClasspath"))
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

// Fails a release build early and clearly instead of producing an APK nobody can install as an update.
val checkReleaseSigning by tasks.registering {
    val partial = signingEnv.filterValues { it == null }.keys.takeIf { it.size in 1..3 }
    val required = System.getenv("S1_REQUIRE_RELEASE_SIGNING") == "true"
    val keyFile = signingEnv["S1_KEYSTORE_FILE"]
    val keyFileMissing = keyFile != null && !file(keyFile).isFile
    doLast {
        if (partial != null) throw GradleException("release signing: missing ${partial.joinToString()} (see docs/releasing.md)")
        if (keyFileMissing) throw GradleException("release signing: S1_KEYSTORE_FILE does not exist")
        if (required && keyFile == null) throw GradleException("release signing required (S1_REQUIRE_RELEASE_SIGNING) but no S1_KEYSTORE_* set")
        if (keyFile == null) logger.warn("release APK is UNSIGNED: set S1_KEYSTORE_FILE, S1_KEYSTORE_PASSWORD, S1_KEY_ALIAS, S1_KEY_PASSWORD to sign it")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }
