// Glassworks for generic Android (#23): the core and the panes that need no special hardware, as an app any
// Android 8+ device installs (frames/generic-android). Its own applicationId and signing key: it never installs
// over the S1 app (sh.edm.s1kiosk), and the two can sit side by side on one device.
//
// Its legacy build (#198) is the `legacy` build type: Android 6-7 (minSdk 23) on GeckoView 144, locked to the
// panel's Home Assistant (engines/engine-gecko-legacy), as dev.glassworks.generic_android.legacy, for older tablets
// and the lululemon Mirror. Same sources, manifest and key as the release build; its own id and release line.
//   ./gradlew :generic-android:assembleLegacy -Ps1Abi=arm64-v8a -PlegacyAbi=arm64-v8a
// A build type, not a product flavor: a flavor would rename debug's and release's tasks and add BuildConfig.FLAVOR
// to them. Their tasks stay as they were. Their APKs change only through desugaring, which is the module's (below).
plugins {
    id("com.android.application")
    id("glassworks.android")
}

// Release version: -PgenericVersion=1.2.3 or GENERIC_VERSION=1.2.3. versionCode = major*10000 + minor*100 + patch,
// the rule the app compares releases with (update/AppVersion.kt), as for the S1 app.
val genericVersion: String? = ((findProperty("genericVersion") as String?) ?: System.getenv("GENERIC_VERSION"))
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
val genericVersionCode: Int? = genericVersion?.let { v ->
    val m = Regex("""^(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)$""").matchEntire(v)
        ?: throw GradleException("GENERIC_VERSION '$v' must be MAJOR.MINOR.PATCH with minor and patch below 100")
    val (major, minor, patch) = m.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

// A test build's commit (-Ps1TestBuild or S1_TEST_BUILD, as the S1 app's), shown next to the version.
val testBuild: String = ((findProperty("s1TestBuild") as String?) ?: System.getenv("S1_TEST_BUILD")).orEmpty().trim()
    .also { if (!Regex("^[0-9a-f]{0,40}$").matches(it)) throw GradleException("S1_TEST_BUILD '$it' must be a commit hash") }

// The ABI this APK is built for (-Ps1Abi or S1_ABI; the root build.gradle.kts picks it for every module). This
// frame runs arm64-v8a and armeabi-v7a (frames/generic-android/design/frame.json): build each with -Ps1Abi.
val s1Abi: String by rootProject.extra

// Release signing, from the environment only, with this app's own key (frames/generic-android/README.md,
// "Signing"). Never the S1 app's key. Nothing set: an unsigned release APK. Partly set, or
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
        // The legacy build (#198): the release build's settings and key, its own id. Its minSdk (23) and engine
        // are set below; the libraries have no legacy build type, so they give it their release one.
        create("legacy") {
            initWith(getByName("release"))
            applicationIdSuffix = ".legacy"
            matchingFallbacks += "release"
        }
    }

    // java.time, Base64 and friends below API 26, for the legacy build. AGP has no per-variant switch, so the release
    // build has it too. At its minSdk 26 no desugared library code (j$.*) goes in, but D8 then adds a small dex of
    // inert API stubs and renumbers some synthetic classes: its APK isn't byte-identical to one built without it
    // (#408, measured 2026-11-16).
    compileOptions { isCoreLibraryDesugaringEnabled = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    // The GeckoView shadows and harness that run the real MainActivity (testing/gecko, shared with the S1 app's tests).
    sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/gecko"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("testing/gecko"))
}

// The legacy build runs on Android 6 and up, and has no unit tests of its own: they run on the release build's
// sources and GeckoView 159 (the shadows in testing/gecko). The emulators run the core on API 23 (core-emulators.yml).
androidComponents {
    beforeVariants(selector().withBuildType("legacy")) { v ->
        v.minSdk = 23
        (v as com.android.build.api.variant.HasHostTestsBuilder).hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

dependencies {
    // The core, the Gecko engine, and the panes: Assist (with its timers), Sendspin, notifications, the Bluetooth
    // proxy, which runs only on a device with Bluetooth LE, the camera (#117), which runs only on a device with a
    // camera, and diagnostics. Nothing S1: no radio, wall base, time zone or root side. The engine is GeckoView 159,
    // but GeckoView 144 for the legacy build (#198): both have the same classes, so each build has one.
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    debugImplementation(project(":engine-gecko"))
    releaseImplementation(project(":engine-gecko"))
    "legacyImplementation"(project(":engine-gecko-legacy"))
    implementation(project(":engine-vitro"))
    // Vitro's cameras over WebRTC (libs/ha-webrtc on libwebrtc), registered when Vitro is on.
    implementation(project(":engine-vitro-webrtc"))
    implementation(project(":pane-assist"))
    implementation(project(":pane-timers"))
    implementation(project(":pane-sendspin"))
    implementation(project(":pane-notifications"))
    implementation(project(":pane-bluetooth-proxy"))
    implementation(project(":pane-camera"))
    implementation(project(":pane-diagnostics"))
    implementation(project(":esphome-api-android"))
    implementation(project(":improv"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")

    // GPL-2.0 with the Classpath exception, like the JDK it backports (THIRD-PARTY-NOTICES.md). The classes the
    // legacy build calls go into its APK; at the release build's minSdk 26 none do.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

// The licences screen and the notices check are the S1 app's (frames/aqara-s1-plus/android/build.gradle.kts),
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

// Every library in the release and legacy APKs must be listed, with its version, in THIRD-PARTY-NOTICES.md
// (`group:name:version`). Runs before those builds, so a dependency bump can't ship without its notice.
// Reads only the resolved dependency graph Gradle already has for the build (apkModules, in build-logic), and fails
// when a dependency in it didn't resolve.
fun checkNotices(name: String, variant: String) = tasks.register(name) {
    val modules = apkModules(configurations.named("${variant}RuntimeClasspath"))
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
val checkThirdPartyNotices = checkNotices("checkThirdPartyNotices", "release")
val checkLegacyNotices = checkNotices("checkLegacyThirdPartyNotices", "legacy")
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkThirdPartyNotices) }
tasks.matching { it.name == "preLegacyBuild" }.configureEach { dependsOn(checkLegacyNotices) }

// The legacy engine's GeckoView is built for -PlegacyAbi (root build.gradle.kts), the APK for -Ps1Abi: an APK must
// have them match, or it would carry no engine for its ABI.
val checkLegacyAbi by tasks.registering {
    val apk = s1Abi
    val engine = providers.provider { (rootProject.extra["legacyAbi"] as Lazy<*>).value as String }
    doLast {
        if (engine.get() != apk) throw GradleException("legacy build: -PlegacyAbi (${engine.get()}) must be the APK's -Ps1Abi ($apk)")
    }
}
// On packaging only: lint and compiling the legacy build don't care which ABI it is for (api23-report.py lints it).
tasks.matching { it.name == "packageLegacy" || it.name == "packageLegacyBundle" }.configureEach { dependsOn(checkLegacyAbi) }


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
tasks.matching { it.name == "preReleaseBuild" || it.name == "preLegacyBuild" }.configureEach { dependsOn(checkReleaseSigning) }
