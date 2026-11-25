/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// Vitro: experimental native Android Lovelace rendering engine for Glassworks.
// Runs directly on Android 6+ (minSdk 23, including the lululemon Studio Mirror) with zero GeckoView
// dependency of its own; an optional fallback Engine handles non-dashboard pages and unknown custom cards.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.engine.vitro"
    defaultConfig {
        minSdk = 23
    }
    // Robolectric reads the bundled icon catalog (src/main/assets/vitro-mdi.tsv.gz) through context.assets.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":glassworks-core"))
    implementation(project(":sendspin")) // WsClient for HA WebSocket data plane

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
}

// The parity harness (#793, src/test/parity, VitroParityHarnessTest): its corpus and Home Assistant's reference
// renders are test inputs, and -PvitroParity* options reach the test (tools/vitro-parity/README.md).
tasks.withType<Test>().configureEach {
    // The browser locale HA's frontend would see (HaLocale's "system" and its language before the profile answers):
    // the same on every machine, so a test's English is English.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    inputs.dir("src/test/parity").withPropertyName("vitroParity").withPathSensitivity(PathSensitivity.RELATIVE)
    val options = mapOf(
        "vitroParityOut" to "vitro.parity.out",
        "vitroParityLocal" to "vitro.parity.local",
        "vitroParityCollect" to "vitro.parity.collect",
        "vitroParityUpdateBaseline" to "vitro.parity.updateBaseline",
        "vitroParityCases" to "vitro.parity.cases",
        "vitroParityFrames" to "vitro.parity.frames",
        // The *Mockups renders for a PR's screenshots (ClimateRangeMockups): PNGs into this directory.
        "vitroMockupDir" to "vitro.mockupDir",
    )
    for ((prop, sys) in options) {
        (project.findProperty(prop) as String?)?.let {
            systemProperty(sys, it)
            // A run with options (another corpus, collect mode) is never UP-TO-DATE.
            outputs.upToDateWhen { false }
        }
    }
    // The local-dashboard check (CompatCheckerLocalDashboardsTest) reads VITRO_PARITY_DIR's *-new.json files: the
    // variable and those files are inputs, so setting it or editing one runs the check again. Unset (CI), nothing.
    System.getenv("VITRO_PARITY_DIR")?.takeIf { it.isNotBlank() }?.let { d ->
        inputs.property("vitroParityDir", d)
        val dir = file(d)
        if (dir.isDirectory) {
            inputs.files(fileTree(dir) { include("*-new.json") }).withPropertyName("vitroParityDashboards")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
    // Native graphics renders every case at three panel sizes: give it room.
    maxHeapSize = "2g"
}
