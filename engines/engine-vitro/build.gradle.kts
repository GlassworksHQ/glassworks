/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.net.URI

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
    // Live cameras: HA's HLS stream on ExoPlayer (the version core already ships).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
    // The HLS player's tests run ExoPlayer on fake renderers and a fake MediaSource.
    testImplementation("androidx.media3:media3-test-utils:1.11.1")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.11.1")
}

// The parity harness (#793, src/test/parity, VitroParityHarnessTest): its corpus and Home Assistant's reference
// renders are test inputs, and -PvitroParity* options reach the test (tools/vitro-parity/README.md).
tasks.withType<Test>().configureEach {
    // The browser locale HA's frontend would see (HaLocale's "system" and its language before the profile answers):
    // the same on every machine, so a test's English is English.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    // A unit test never reaches the network: HTTP and HTTPS to any host but this computer go to a closed port on
    // loopback and fail at once. FakeHaServer.BASE (192.0.2.10, a documentation address) or a card's CDN would
    // otherwise hold an image-pool thread for its whole timeout, and the load would land in a later test (D28).
    // Robolectric's own jar download (build-logic's robolectric.dependency.repo.url) stays direct.
    val robolectricRepo = (systemProperties["robolectric.dependency.repo.url"] as String?)?.let { URI(it).host }
    systemProperty("http.proxyHost", "127.0.0.1")
    systemProperty("http.proxyPort", "9")
    systemProperty("https.proxyHost", "127.0.0.1")
    systemProperty("https.proxyPort", "9")
    systemProperty("http.nonProxyHosts", listOfNotNull("localhost", "127.*", "[::1]", robolectricRepo, "repo1.maven.org").joinToString("|"))
    inputs.dir("src/test/parity").withPropertyName("vitroParity").withPathSensitivity(PathSensitivity.RELATIVE)
    // The island page's scripts under node (IslandHassContractTest, island-hass.test.mjs), HA's types it's held to, and
    // the island end to end (IslandEndToEndTest, island-e2e.mjs): the pinned cards and the browser it drives.
    inputs.dir("src/test/js").withPropertyName("vitroIslandJs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/test/ha-frontend").withPropertyName("vitroHaTypes").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.file("tools/vitro-parity/cards.json"), rootProject.file("tools/vitro-parity/lib/browser.mjs"))
        .withPropertyName("vitroIslandE2eTools").withPathSensitivity(PathSensitivity.RELATIVE)
    // The Playwright pin the end to end drives Chromium with (npm ci builds node_modules from these).
    inputs.files(rootProject.file("tools/vitro-parity/package.json"), rootProject.file("tools/vitro-parity/package-lock.json"))
        .withPropertyName("vitroIslandE2ePackages").withPathSensitivity(PathSensitivity.RELATIVE)
    // The end to end runs only with GW_VITRO_ISLAND_E2E=1 (CI's app job): a run with it is never one without it.
    inputs.property("vitroIslandE2e", System.getenv("GW_VITRO_ISLAND_E2E") ?: "")
    // ClarityStatsTest holds Vitro's Clarity harness to the web harness's cases.
    inputs.file(rootProject.file("tools/perf/test/stats-cases.json")).withPropertyName("clarityStatsCases")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val options = mapOf(
        "vitroParityOut" to "vitro.parity.out",
        "vitroParityLocal" to "vitro.parity.local",
        "vitroParityCollect" to "vitro.parity.collect",
        "vitroParityUpdateBaseline" to "vitro.parity.updateBaseline",
        "vitroParityCases" to "vitro.parity.cases",
        "vitroParityFrames" to "vitro.parity.frames",
        // The *Mockups renders for a PR's screenshots (ClimateRangeMockups): PNGs into this directory.
        "vitroMockupDir" to "vitro.mockupDir",
        // An island page for tools/vitro-parity/island-probe.mjs (IslandPageDumpTest): the case file it draws.
        "vitroIslandCase" to "vitro.islandCase",
        // The entity store's size for a whole dashboard (DashboardStoreMeasure): its input files and report directory.
        "vitroStoreMeasure" to "vitro.storeMeasure",
        // HeavyViewMeasure: Clarity's heavy view, its views per card and what culling leaves drawn.
        "vitroHeavyMeasure" to "vitro.heavyMeasure",
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
