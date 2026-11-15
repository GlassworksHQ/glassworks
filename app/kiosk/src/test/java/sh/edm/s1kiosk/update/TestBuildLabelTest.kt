package sh.edm.s1kiosk.update

import dev.glassworks.platform.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #263: a test build says so next to its version; a release shows only its version. */
class TestBuildLabelTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, ".github/workflows").isDirectory }

    @Test fun aTestBuildSaysSo() = assertEquals("0.4.3 · test build 28c157d", AppBuild("0.4.3", 403, "28c157d").shown)

    @Test fun aReleaseIsItsVersion() = assertEquals("0.4.3", AppBuild("0.4.3", 403).shown)

    /**
     * Every place that shows the app's version shows [AppBuild.shown], so a test build says so there too
     * (the Manage page, the settings' About). The raw versionName stays only where a version is compared or
     * sent as a protocol field: updates, HA's DeviceInfo and version entity, Sendspin, Improv, user agents.
     * A merge that brings `build.versionName` (or `BuildConfig.VERSION_NAME`) into a page fails here.
     */
    @Test fun pagesShowTheLabelNotTheRawVersion() {
        // The S1 app and the Glassworks core both: a core page must not read the raw version either.
        val main = File(repo, "app/kiosk/src/main/java")
        val raw = setOf("KioskApp.kt", "update/UpdateManager.kt", "esphome/PanelDevice.kt", "sendspin/SendspinPlayer.kt",
            "improv/ImprovController.kt", "improv/ImprovSerialServer.kt", "improv/ImprovIdentity.kt", "radio/RadioPane.kt")
            .map { "sh/edm/s1kiosk/$it" }.toSet()
        val found = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().let { s -> "BuildConfig.VERSION_NAME" in s || "build.versionName" in s } }
            .map { it.relativeTo(main).path }.filter { it !in raw }.toList()
        assertTrue("show AppBuild.shown, not the raw versionName, in: $found", found.isEmpty())
    }

    /** The label comes from the build: test-build.yml passes the commit, release.yml passes none. */
    @Test fun onlyTestBuildsPassTheirCommit() {
        assertTrue(File(repo, ".github/workflows/test-build.yml").readText().contains("S1_TEST_BUILD: \${{ env.SHORT_SHA }}"))
        assertFalse(File(repo, ".github/workflows/release.yml").readText().contains("S1_TEST_BUILD"))
        assertTrue(File(repo, "app/kiosk/build.gradle.kts").readText().contains("buildConfigField(\"String\", \"TEST_BUILD\""))
    }
}
