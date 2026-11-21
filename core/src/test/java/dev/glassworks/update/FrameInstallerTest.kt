/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.esphome.UpdateStateData
import dev.glassworks.frame.FrameRelease
import dev.glassworks.update.FrameBundleFixture.FILES
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139: Home Assistant's Frame update installs the S1's frame bundle. The download is checked against the release's
 * `.sha256`, unpacked into the loader's staging dir, handed to the loader's install, and once the loader's record says
 * it is staged the panel restarts at once. Every way it can stop says why on the card, and nothing restarts then.
 */
class FrameInstallerTest {
    private val files: File = Files.createTempDirectory("frame-install").toFile()
    private val tag = "frames/aqara-s1-plus/v1.0.1"
    private val base = "https://github.com/GlassworksHQ/glassworks/releases/download/$tag"
    private val zipName = "frame-bundle-aqara-s1-plus-1.0.1.zip"
    private val zip = FrameBundleFixture.bundle()
    private val digest = MessageDigest.getInstance("SHA-256").digest(zip)

    private val loader = FrameBundleFixture.Loader(files)
    private val served = HashMap<String, ByteArray>().apply {
        put("$base/$zipName", zip)
        put("$base/$zipName.sha256", "${Releases.hex(digest)}  $zipName\n".toByteArray())
    }
    private var offline = false
    private var clock = 0L
    private val views = ArrayList<UpdateStateData>()

    private fun release(assets: List<ReleaseAsset> = listOf(asset(zipName, zip.size.toLong()), asset("$zipName.sha256", 90))) =
        GithubRelease(tag, "Aqara S1 Plus frame 1.0.1", "**Fixes the agent.**", "https://github.com/GlassworksHQ/glassworks/releases/tag/$tag", false, false, assets)

    private fun asset(name: String, size: Long, digest: ByteArray? = null) = ReleaseAsset(name, "$base/$name", size, digest)

    private val model = FrameUpdateModel(FrameRelease("aqara-s1-plus", "1.0.0", install = null), "Aqara S1 Plus frame", installs = true)

    private val net = object : FrameInstaller.Net {
        override fun text(url: String, max: Int): String {
            if (offline) throw UnknownHostException("github.com")
            return served[url]?.toString(Charsets.UTF_8) ?: throw IOException("HTTP 404 for ${url.substringAfterLast('/')}")
        }

        override fun download(url: String, to: File, size: Long, max: Long, progress: (Long) -> Boolean): ByteArray? {
            if (offline) throw UnknownHostException("objects.githubusercontent.com")
            val b = served[url] ?: throw IOException("HTTP 404")
            val md = MessageDigest.getInstance("SHA-256")
            to.outputStream().use { out ->
                for (chunk in b.toList().chunked(8192)) {
                    val c = chunk.toByteArray()
                    out.write(c); md.update(c)
                    clock += 2_000 // a slow link: every chunk publishes
                    if (!progress(to.length())) return null
                }
            }
            return md.digest()
        }
    }

    private val installer = FrameInstaller(
        "aqara-s1-plus", loader, files, model, net, onChange = { views += model.view() },
        now = { clock }, sleep = { clock += it },
    )

    /** Checked against [r], then Install: what the card said each time it changed. */
    private fun install(r: GithubRelease = release(), appBusy: Boolean = false): FrameUpdateModel.Begin {
        model.onChecked(FrameOffer.newest(listOf(r), "aqara-s1-plus"))
        val b = model.begin(clock, installer.busy(appBusy))
        if (b is FrameUpdateModel.Begin.Started) installer.run(b.attempt)
        return b
    }

    private fun last() = model.view()

    @Test fun anInstallDownloadsChecksStagesAndRestartsAtOnce() {
        val b = install()
        assertTrue(b is FrameUpdateModel.Begin.Started)
        assertEquals("the loader took it, then the panel restarted", listOf("request", "restart"), loader.calls)
        // What the loader found in the staging dir: the bundle's files and nothing else.
        assertEquals(FILES.keys + setOf("manifest", "manifest.sig"), loader.seen.keys)
        // The card: in progress with a percentage while downloading, then checking, staging, restarting.
        val downloading = views.filter { it.hasProgress }
        assertTrue("progress while downloading", downloading.size >= 3 && downloading.all { it.inProgress })
        assertEquals("rises to 100", downloading.map { it.progress }.sorted(), downloading.map { it.progress })
        val lines = views.map { it.releaseSummary.lineSequence().first() }.distinct()
        assertTrue(lines.toString(), lines.containsAll(listOf("Downloading frame 1.0.1", "Checking frame 1.0.1", "Staging frame 1.0.1",
            "Frame 1.0.1 is staged. Restarting the panel to finish")))
        val restarting = views.last { it.releaseSummary.startsWith("Frame 1.0.1 is staged") }
        assertTrue(restarting.inProgress)
        assertFalse(restarting.hasProgress)
        // A restart that didn't happen (the fake one returns): said, so nobody waits for it.
        assertFalse(last().inProgress)
        assertTrue(last().releaseSummary, last().releaseSummary.startsWith("Install of frame 1.0.1 failed: frame 1.0.1 is staged, but the panel didn't restart"))
        assertFalse("downloads cleaned up", File(files, FrameInstaller.DOWNLOAD_DIR).exists())
        assertFalse("staging dir cleaned up once the loader is done", File(files, loader.stageDir).exists())
    }

    @Test fun theRestartComesOnlyAfterTheLoadersRecordSaysStaged() {
        loader.state = "none - 1000000 -"
        var stateAtRestart: String? = null
        val watching = object : dev.glassworks.frame.BundleAgent by loader {
            override fun restart() { stateAtRestart = loader.state; loader.restart() }
        }
        val i = FrameInstaller("aqara-s1-plus", watching, files, model, net, onChange = {}, now = { clock }, sleep = { clock += it })
        model.onChecked(FrameOffer.newest(listOf(release()), "aqara-s1-plus"))
        i.run((model.begin(clock, null) as FrameUpdateModel.Begin.Started).attempt)
        assertEquals("none - 1000000 1.0.1", stateAtRestart)
    }

    @Test fun aHashMismatchStopsBeforeAnythingIsStaged() {
        served["$base/$zipName.sha256"] = "${"0".repeat(64)}  $zipName\n".toByteArray()
        install()
        assertEquals(emptyList<String>(), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.startsWith("Install of frame 1.0.1 failed: the download doesn't match the release's SHA-256"))
        assertFalse(last().inProgress)
        assertFalse(File(files, loader.stageDir).exists())
    }

    @Test fun githubsOwnDigestMustAgreeWithTheSha256File() {
        install(release(listOf(asset(zipName, zip.size.toLong(), digest = ByteArray(32)), asset("$zipName.sha256", 90))))
        assertEquals(emptyList<String>(), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.contains("disagrees with GitHub's digest"))
    }

    @Test fun aRefusalByTheLoaderSaysItsDetailAndDoesntRestart() {
        loader.refuse = "error: refused: bundle version 1000001 is below the floor 1000002 (no downgrades)"
        install()
        assertEquals(listOf("request"), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.startsWith(
            "Install of frame 1.0.1 failed: the panel refused it (bundle version 1000001 is below the floor 1000002 (no downgrades))"))
    }

    @Test fun aStatusPropertyAloneNeverRestarts() {
        // Any app may set sys.glassworks.bundle.status: an "ok" there with no staged bundle in the record is no staging.
        loader.refuse = "ok: frame bundle 1.0.1 staged (3 files); it loads at the next restart"
        install()
        assertEquals(listOf("request"), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.contains("the panel didn't stage it"))
    }

    @Test fun aServiceThatNeverStartsIsSaid() {
        loader.dead = true
        install()
        assertEquals(listOf("request"), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.contains("the panel didn't stage it (its bundle service did not start)"))
    }

    @Test fun noNetworkIsSaid() {
        offline = true
        install()
        assertEquals(emptyList<String>(), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.startsWith("Install of frame 1.0.1 failed: no network (UnknownHostException)"))
    }

    @Test fun aBusyDeviceIsRefusedWithoutStarting() {
        val app = install(appBusy = true)
        assertTrue(app is FrameUpdateModel.Begin.Refused)
        assertEquals("Not installed: ${FrameInstaller.APP_BUSY}", last().releaseSummary.lineSequence().first())
        assertFalse(last().inProgress)
        loader.running = true
        val svc = model.begin(clock, installer.busy(false)) as FrameUpdateModel.Begin.Refused
        assertEquals(FrameInstaller.SERVICE_BUSY, svc.reason)
        loader.running = false
        loader.state = null
        assertEquals(FrameInstaller.NO_LOADER, installer.busy(false))
        assertEquals(emptyList<String>(), loader.calls)
    }

    @Test fun aSpoiltZipIsRefusedBeforeTheLoaderSeesIt() {
        val bad = FrameBundleFixture.withUnixMode(zip, "services/agent.sh", 0xA1FF)
        served["$base/$zipName"] = bad
        served["$base/$zipName.sha256"] = "${Releases.hex(MessageDigest.getInstance("SHA-256").digest(bad))}  $zipName\n".toByteArray()
        install(release(listOf(asset(zipName, bad.size.toLong()), asset("$zipName.sha256", 90))))
        assertEquals(emptyList<String>(), loader.calls)
        assertTrue(last().releaseSummary, last().releaseSummary.startsWith("Install of frame 1.0.1 failed: the bundle doesn't check out (a link (services/agent.sh))"))
        assertFalse(File(files, loader.stageDir).exists())
    }

    @Test fun aReleaseWithoutABundleOffersNoInstall() {
        val b = install(release(emptyList()))
        assertEquals("Frame 1.0.1 has no bundle for this device.", (b as FrameUpdateModel.Begin.Refused).reason)
        assertEquals(emptyList<String>(), loader.calls)
    }

    @Test fun atStartAStagedBundleIsSaidAndLeftoversGo() {
        File(files, "${FrameInstaller.DOWNLOAD_DIR}/bundle.zip").apply { parentFile.mkdirs(); writeText("x") }
        File(files, "${loader.stageDir}/manifest").apply { parentFile.mkdirs(); writeText("x") }
        loader.state = "none - 1000000 1.0.1"
        installer.atStart()
        assertFalse(File(files, FrameInstaller.DOWNLOAD_DIR).exists())
        assertFalse(File(files, loader.stageDir).exists())
        model.onChecked(FrameOffer.newest(listOf(release()), "aqara-s1-plus"))
        assertTrue(model.view().releaseSummary, model.view().releaseSummary.startsWith("Frame 1.0.1 is staged. It loads at the next restart."))
    }

    @Test fun theStagingDirStaysWhileTheLoaderStillReadsIt() {
        File(files, "${loader.stageDir}/manifest").apply { parentFile.mkdirs(); writeText("x") }
        loader.running = true
        installer.atStart()
        assertTrue(File(files, "${loader.stageDir}/manifest").exists())
    }

    @Test fun aSecondInstallWhileOneRunsIsRefused() {
        model.onChecked(FrameOffer.newest(listOf(release()), "aqara-s1-plus"))
        assertTrue(model.begin(clock, null) is FrameUpdateModel.Begin.Started)
        assertTrue(model.busy)
        assertEquals(FrameUpdateModel.ALREADY, (model.begin(clock, null) as FrameUpdateModel.Begin.Refused).reason)
    }

    @Test fun aStuckDownloadTimesOut() {
        model.onChecked(FrameOffer.newest(listOf(release()), "aqara-s1-plus"))
        assertTrue(model.begin(0, null) is FrameUpdateModel.Begin.Started)
        assertEquals("timed out while downloading", model.expire(UpdateModel.DOWNLOAD_TIMEOUT_MS))
        assertFalse(model.busy)
    }

    /** S1 frame 1.0.0: the boot image and the first bundle are both 1.0.0. Without a bundle loaded, 1.0.0's is an update. */
    @Test fun aPanelOnItsBootImagesCopiesTakesTheSameVersionsBundle() {
        val t0 = "frames/aqara-s1-plus/v1.0.0"
        val b0 = "https://github.com/GlassworksHQ/glassworks/releases/download/$t0"
        val z0 = "frame-bundle-aqara-s1-plus-1.0.0.zip"
        val bytes = FrameBundleFixture.bundle("1.0.0")
        served["$b0/$z0"] = bytes
        served["$b0/$z0.sha256"] = "${Releases.hex(MessageDigest.getInstance("SHA-256").digest(bytes))}  $z0\n".toByteArray()
        val r0 = GithubRelease(t0, "Aqara S1 Plus frame 1.0.0", "**The first frame bundle.**", "https://github.com/GlassworksHQ/glassworks/releases/tag/$t0", false, false,
            listOf(ReleaseAsset(z0, "$b0/$z0", bytes.size.toLong()), ReleaseAsset("$z0.sha256", "$b0/$z0.sha256", 90)))

        loader.state = "none - 1000000 -"
        installer.atStart()
        assertEquals("1.0.0 (boot image)", model.installed.shown)
        model.onChecked(FrameOffer.newest(listOf(r0), "aqara-s1-plus"))
        assertTrue(model.updateAvailable)
        assertEquals("1.0.0 (boot image)", model.view().currentVersion)
        assertEquals("1.0.0", model.view().latestVersion)
        model.begin(clock, installer.busy(false)).let { installer.run((it as FrameUpdateModel.Begin.Started).attempt) }
        assertEquals(listOf("request", "restart"), loader.calls)

        // After the restart the loader has loaded it: up to date.
        val next = FrameUpdateModel(FrameRelease("aqara-s1-plus", "1.0.0", install = null), "Aqara S1 Plus frame", installs = true)
        next.onLoaderRecord(FrameBundleState.parse("ok 1.0.0 1000000 -"))
        next.onChecked(FrameOffer.newest(listOf(r0), "aqara-s1-plus"))
        assertEquals("1.0.0", next.installed.shown)
        assertFalse(next.updateAvailable)
        assertEquals(next.view().currentVersion, next.view().latestVersion)
    }

    @Test fun aBadBundleCountsAsNoneAndAStagedOneIsSaidEvenAtTheSameVersion() {
        model.onLoaderRecord(FrameBundleState.parse("bad - 1000000 1.0.0"))
        assertEquals("1.0.0 (boot image)", model.installed.shown)
        assertEquals("Frame 1.0.0 is staged. It loads at the next restart.", model.installLine())
    }
}
