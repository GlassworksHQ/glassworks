// The legacy engine's test APK (#198): the S1 app's own sources, resources and manifest (frames/aqara-s1-plus/android),
// built on :engine-gecko-legacy for Android 6-7 instead of :engine-gecko. It exists so the frozen engine can run on
// an emulator, a tablet and the lululemon Mirror before the generic-android frame (#23) replaces it.
//
// - Debug only, debug-signed, never published. applicationId dev.glassworks.s1.legacy, so it never installs over,
//   or updates, the S1 app.
// - The S1 app's module and its release APK don't change: this module only reads its folders.
// - One ABI, -PlegacyAbi (root build.gradle.kts): arm64-v8a by default, the Mirror's.
//
//   ./gradlew :kiosk-legacy:assembleDebug     → engines/engine-gecko-legacy/test-app/build/outputs/apk/debug/kiosk-legacy-debug.apk
plugins {
    id("com.android.application")
    id("glassworks.android")
}

val s1App = rootProject.layout.projectDirectory.dir("frames/aqara-s1-plus/android")
@Suppress("UNCHECKED_CAST")
val legacyAbi: String = (rootProject.extensions.extraProperties.get("legacyAbi") as Lazy<*>).value as String
val testBuild: String = (System.getenv("S1_TEST_BUILD")).orEmpty().trim()
    .also { if (!Regex("^[0-9a-f]{0,40}$").matches(it)) throw GradleException("S1_TEST_BUILD '$it' must be a commit hash") }

android {
    // The S1 app's: its sources use dev.glassworks.s1.R and dev.glassworks.s1.BuildConfig.
    namespace = "dev.glassworks.s1"
    defaultConfig {
        applicationId = "dev.glassworks.s1.legacy"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-legacy"
        buildConfigField("String", "TEST_BUILD", "\"$testBuild\"")
        ndk { abiFilters += listOf(legacyAbi) }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        // java.time and friends on API 23-25 (the S1 app's code assumes API 26).
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        // The S1 app's code is written for API 26+; this APK is a test bed, and #23 brings the real checks.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    sourceSets.getByName("main") {
        manifest.srcFile(s1App.file("src/main/AndroidManifest.xml"))
        kotlin.srcDir(s1App.dir("src/main/java"))
        res.srcDir(s1App.dir("src/main/res"))
        assets.srcDir(s1App.dir("src/main/assets"))
    }
}

// Debug only: there is no legacy release yet (#198's release part).
androidComponents {
    beforeVariants { v -> if (v.buildType == "release") v.enable = false }
}

// The licences screen's assets, as the S1 app copies them (its LicenceAssets task).
abstract class LegacyLicenceAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val files: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val texts: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val fs: FileSystemOperations

    @TaskAction fun copy() {
        fs.sync {
            into(outputDir.dir("licenses"))
            from(files)
            from(texts) { include("*.txt"); into("texts") }
        }
    }
}
val licenceAssets = tasks.register<LegacyLicenceAssets>("licenceAssets") {
    files.from(rootProject.file("THIRD-PARTY-NOTICES.md"), rootProject.file("LICENSE"))
    texts.set(rootProject.layout.projectDirectory.dir("LICENSES"))
    outputDir.set(layout.buildDirectory.dir("generated/licence-assets"))
}
androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(licenceAssets, LegacyLicenceAssets::outputDir) }
}

dependencies {
    // The S1 app's dependencies (frames/aqara-s1-plus/android/build.gradle.kts), with the legacy engine.
    implementation(project(":glassworks-pane-api"))
    implementation(project(":glassworks-core"))
    implementation(project(":engine-gecko-legacy"))
    implementation(project(":engine-vitro")) // as the S1 app does (minSdk 23, no override needed)
    implementation(project(":engine-vitro-webrtc")) // as the S1 app does (minSdk 23; libwebrtc's is 21, no override)
    implementation(project(":pane-diagnostics"))
    implementation(project(":pane-bluetooth-proxy"))
    implementation(project(":pane-camera"))
    implementation(project(":pane-notifications"))
    implementation(project(":pane-assist"))
    implementation(project(":pane-timers"))
    implementation(project(":pane-sendspin"))
    implementation(project(":aqara-s1-plus-wall-base"))
    implementation(project(":pane-zigbee-thread"))
    implementation(project(":esphome-api-android"))
    implementation(project(":improv"))
    implementation(project(":bluetooth-proxy"))

    // GPL-2.0 with the Classpath exception, like the JDK it backports: the classes the app calls (java.time and
    // friends) are compiled into the APK. This debug APK is never released, only a test build's artifact (test-build.yml,
    // on branches named "legacy"); the legacy release is the generic app's. Allowed in this app too (maintainer,
    // 2026-11-16; AGENTS.md, rule 4).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
