// The legacy Gecko engine (#198): the frozen GeckoView 144 for Android 6-7 (API 23-25: older tablets and the
// lululemon Mirror), locked to the panel's Home Assistant. It compiles engine-gecko's own sources (GeckoEngine.kt,
// PageGuard.kt) against GeckoView 144, with this module's GeckoVariant.kt, which supplies the lockdown, in place
// of engine-gecko's. The web extension (assets/extension) is engine-gecko's too; assets/lockdown is this module's.
plugins {
    id("com.android.library")
}

val shared = rootProject.layout.projectDirectory.dir("engines/engine-gecko/src/main")

android {
    namespace = "dev.glassworks.engine.gecko.legacy"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // GeckoView 144's AAR declares minSdk 21; the legacy engine is for Android 6 and up.
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// RepoFiles, the tests' way to find files across every module from the repo root (#21).
// The helper extension (content.js, window.s1kiosk) is engine-gecko's, unchanged.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("test").kotlin.srcDir(rootProject.file("testing/kotlin"))
    sourceSets.getByName("main").assets.srcDir(shared.dir("assets"))
}

/** engine-gecko's sources but its GeckoVariant.kt, copied into this module's build so they compile against GeckoView 144. */
abstract class SharedGeckoSources : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val from: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val fs: FileSystemOperations

    @TaskAction fun copy() {
        fs.sync {
            from(from) { exclude("**/GeckoVariant.kt") }
            into(outputDir)
        }
    }
}
val sharedSources = tasks.register<SharedGeckoSources>("sharedGeckoSources") {
    from.set(shared.dir("java"))
    outputDir.set(layout.buildDirectory.dir("generated/shared-gecko"))
}
androidComponents {
    onVariants { v -> v.sources.kotlin?.addGeneratedSourceDirectory(sharedSources, SharedGeckoSources::outputDir) }
}

// The legacy test APK's ABI (root build.gradle.kts): arm64-v8a by default, the Mirror's.
@Suppress("UNCHECKED_CAST")
val legacyAbi: String = (rootProject.extensions.extraProperties.get("legacyAbi") as Lazy<*>).value as String

dependencies {
    api(project(":glassworks-core"))
    // The last GeckoView whose AAR allows minSdk 21 (145 needs API 26). Decided in #39 (2026-11-15): 144, else 143.
    api("org.mozilla.geckoview:geckoview-$legacyAbi:144.0.20251027123126") {
        // As in engine-gecko: Play services only serve WebAuthn, and the panels have none.
        exclude(group = "com.google.android.gms")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
