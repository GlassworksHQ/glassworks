// The engine contract on a real Android runtime (docs/engine-contract.md): one set of checks, in src/androidTest, that
// every Engine passes on an emulator. CI runs it in core-emulators.yml: the current GeckoView and Vitro over it on
// API 34, the legacy GeckoView and Vitro over it on API 23. This module ships nothing; it has no main code.
//
//   ./gradlew :engine-conformance:connectedDebugAndroidTest                                   # API 26+ emulator
//   ./gradlew --init-script tools/legacy/api23-emulator.gradle -PengineConformanceGecko=legacy \
//       :engine-conformance:connectedDebugAndroidTest                                         # API 23 emulator
//
// -PengineConformanceAbi picks the emulator's ABI: x86_64 (CI's emulators, the default) or arm64-v8a (an Apple
// Silicon Mac). The GeckoView the engine modules name is swapped for that ABI's build of the same version: no frame
// ships an x86_64 GeckoView, but the engine code is the same for every ABI.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

/** Which Gecko engine the run tests: `current` (engine-gecko, GeckoView 159, API 26+) or `legacy` (API 23+). */
val gecko: String = (findProperty("engineConformanceGecko") as String?)?.trim().orEmpty().ifEmpty { "current" }
    .also { if (it != "current" && it != "legacy") throw GradleException("engineConformanceGecko must be current or legacy, not '$it'") }
val abi: String = (findProperty("engineConformanceAbi") as String?)?.trim().orEmpty().ifEmpty { "x86_64" }
    .also { if (it !in listOf("x86_64", "arm64-v8a", "armeabi-v7a", "x86")) throw GradleException("engineConformanceAbi '$it' is not an Android ABI") }

android {
    namespace = "dev.glassworks.engine.conformance"
    defaultConfig {
        // The legacy engine runs from Android 6 (#198). The api23 init script sets the same, and adds desugaring.
        if (gecko == "legacy") minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A fresh process for every test: an engine reads its settings once per process, and GeckoView allows one
        // runtime per process, so each test builds its engine as an app does at start.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        buildConfigField("String", "GECKO", "\"$gecko\"")
    }
    buildFeatures { buildConfig = true }
    testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        // The engines' GeckoView, at the same version, built for the emulator's ABI.
        if (requested.group == "org.mozilla.geckoview" && requested.name.startsWith("geckoview-") && requested.name != "geckoview-$abi") {
            useTarget("${requested.group}:geckoview-$abi:${requested.version}")
            because("the conformance test runs on a $abi emulator")
        }
    }
}

dependencies {
    androidTestImplementation(project(":engine-vitro"))
    androidTestImplementation(project(if (gecko == "legacy") ":engine-gecko-legacy" else ":engine-gecko"))

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
    androidTestUtil("androidx.test.services:test-services:1.6.0")
}
