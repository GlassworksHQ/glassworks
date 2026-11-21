/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import dev.glassworks.frame.FrameRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139: a frame's own version, from the stamp or from before the stamps, against its newest release
 * (`frames/<id>/v<version>`), as the Frame update entity and the Device page show it.
 */
class FrameUpdateTest {
    private val install = "To install it: flash the device again with Kiln, at kiln.glassworks.dev"

    private fun release(stamp: String?, image: String? = null) = FrameRelease("lululemon-mirror", stamp, image, install)

    private fun gh(tag: String, draft: Boolean = false, prerelease: Boolean = false, body: String = "Notes for $tag.") =
        GithubRelease(tag, tag, body, "https://github.com/GlassworksHQ/glassworks/releases/tag/$tag", draft, prerelease, emptyList())

    @Test fun theStampNamesTheFrameAndItsVersion() {
        val props = mapOf(FrameRelease.ID_PROP to "lululemon-mirror", FrameRelease.VERSION_PROP to "1.0.0\n")
        assertEquals("1.0.0", FrameRelease.stamp("lululemon-mirror", { props[it].orEmpty() }))
        assertNull("another frame's stamp is no stamp", FrameRelease.stamp("aqara-s1-plus", { props[it].orEmpty() }))
        assertNull("no stamp", FrameRelease.stamp("lululemon-mirror", { "" }))
    }

    @Test fun aStampThatIsntAVersionCountsAsBeforeTheStamps() {
        assertEquals(FrameVersion.Stamped(AppVersion(1, 0, 1)), FrameVersion.of(release("1.0.1")))
        assertEquals(FrameVersion.BeforeStamps("Glass Panes image 2"), FrameVersion.of(release("1.0", "Glass Panes image 2")))
        assertEquals("Before 1.0.0 (Glass Panes image 2)", FrameVersion.of(release(null, "Glass Panes image 2")).shown)
        assertEquals("Before 1.0.0", FrameVersion.of(release(null)).shown)
    }

    @Test fun theNewestStableReleaseOfThisFrameOnly() {
        val list = listOf(
            gh("aqara-s1-plus-v2.0.0"), gh("v2.0.0"), gh("generic-android-v2.0.0"), gh("libs/improv/v2.0.0"),
            gh("frames/aqara-s1-plus/v3.0.0"),
            gh("frames/lululemon-mirror/v1.2.0", draft = true),
            gh("frames/lululemon-mirror/v1.3.0", prerelease = true),
            gh("frames/lululemon-mirror/v1.4.0-rc.1"),
            gh("frames/lululemon-mirror/vnope"),
            gh("frames/lululemon-mirror/v1.0.0"),
            gh("frames/lululemon-mirror/v1.1.0"),
        )
        val o = FrameOffer.newest(list, "lululemon-mirror")!!
        assertEquals(AppVersion(1, 1, 0), o.version)
        assertEquals("frames/lululemon-mirror/v1.1.0", o.tag)
        assertEquals("Notes for frames/lululemon-mirror/v1.1.0.", o.notes)
        assertNull("no release of this frame yet", FrameOffer.newest(list.take(4), "lululemon-mirror"))
    }

    @Test fun theAppsOwnUpdateCheckSkipsFrameTags() {
        val list = listOf(gh("frames/aqara-s1-plus/v9.0.0"), gh("frames/lululemon-mirror/v9.0.0"))
        for (line in listOf(ReleaseLine.AQARA_S1_PLUS, ReleaseLine.GENERIC_ANDROID, ReleaseLine.GENERIC_ANDROID_LEGACY)) {
            val pick = Releases.newest(list, listOf("arm64-v8a", "armeabi-v7a"), line)
            assertNull(line.id, pick.offer)
            assertNull("${line.id}: skipped silently", pick.problem)
        }
    }

    @Test fun beforeTheFirstCheckAndWithNoReleaseTheCardShowsNoUpdate() {
        val m = FrameUpdateModel(release(null, "Glass Panes image 2"), "lululemon Studio Mirror frame")
        val v = m.view()
        assertTrue(v.missingState)
        assertEquals("Before 1.0.0 (Glass Panes image 2)", v.currentVersion)
        assertEquals("latest = installed: Home Assistant shows no update", v.currentVersion, v.latestVersion)
        m.onChecked(null)
        assertFalse(m.view().missingState)
        assertEquals(m.view().currentVersion, m.view().latestVersion)
        assertFalse(m.updateAvailable)
        assertEquals("Before 1.0.0 (Glass Panes image 2)", m.detail())
    }

    @Test fun aDeviceFromBeforeTheStampsIsOlderThanAnyRelease() {
        val m = FrameUpdateModel(release(null, "Glass Panes image 2"), "lululemon Studio Mirror frame")
        m.onChecked(FrameOffer.newest(listOf(gh("frames/lululemon-mirror/v1.0.0", body = "**Glassworks 1.0.0.**\n\n### Added\n- **A thing**")), "lululemon-mirror"))
        assertTrue(m.updateAvailable)
        val v = m.view()
        assertEquals("1.0.0", v.latestVersion)
        assertEquals("lululemon Studio Mirror frame", v.title)
        assertTrue(v.releaseSummary, v.releaseSummary.startsWith("$install\n\n**Glassworks 1.0.0.**"))
        assertEquals("https://github.com/GlassworksHQ/glassworks/releases/tag/frames/lululemon-mirror/v1.0.0", v.releaseUrl)
        assertEquals("Before 1.0.0 (Glass Panes image 2). 1.0.0 is out. $install", m.detail())
    }

    @Test fun aStampedDeviceComparesVersions() {
        val m = FrameUpdateModel(release("1.0.0"), "t")
        m.onChecked(FrameOffer.newest(listOf(gh("frames/lululemon-mirror/v1.0.0")), "lululemon-mirror"))
        assertFalse("same version", m.updateAvailable)
        assertEquals("1.0.0", m.view().latestVersion)
        assertEquals("", m.view().releaseSummary)
        assertEquals("1.0.0", m.detail())
        m.onChecked(FrameOffer.newest(listOf(gh("frames/lululemon-mirror/v1.0.1")), "lululemon-mirror"))
        assertTrue(m.updateAvailable)
        assertEquals("1.0.1", m.view().latestVersion)
        val pre = FrameUpdateModel(release("1.0.0-dev"), "t")
        pre.onChecked(FrameOffer.newest(listOf(gh("frames/lululemon-mirror/v1.0.0")), "lululemon-mirror"))
        assertTrue("a build from before the release is older than it", pre.updateAvailable)
    }

    @Test fun anUpdateThatInstallsFromHomeAssistantHasNoNote() {
        val m = FrameUpdateModel(FrameRelease("aqara-s1-plus", "1.0.0", install = null), "Aqara S1 Plus frame")
        m.onChecked(FrameOffer.newest(listOf(gh("frames/aqara-s1-plus/v1.0.1", body = "Fixes.")), "aqara-s1-plus"))
        assertEquals("Fixes.", m.view().releaseSummary)
        assertEquals("1.0.0. 1.0.1 is out", m.detail())
    }

    private fun asset(tag: String, name: String, size: Long = 100) =
        ReleaseAsset(name, "https://github.com/GlassworksHQ/glassworks/releases/download/$tag/$name", size)

    @Test fun aReleaseWithItsBundleOffersIt() {
        val tag = "frames/aqara-s1-plus/v1.0.1"
        val zip = "frame-bundle-aqara-s1-plus-1.0.1.zip"
        val r = gh(tag).copy(assets = listOf(asset(tag, zip, 60_000_000), asset(tag, "$zip.sha256", 90)))
        val b = FrameOffer.newest(listOf(r), "aqara-s1-plus")!!.bundle!!
        assertEquals(zip, b.zip.name)
        assertEquals("$zip.sha256", b.sha256.name)
        // GitHub may give the tag's slashes encoded.
        val encoded = gh(tag).copy(assets = listOf(
            ReleaseAsset(zip, "https://github.com/GlassworksHQ/glassworks/releases/download/frames%2Faqara-s1-plus%2Fv1.0.1/$zip", 1),
            ReleaseAsset("$zip.sha256", "https://github.com/GlassworksHQ/glassworks/releases/download/frames%2Faqara-s1-plus%2Fv1.0.1/$zip.sha256", 1),
        ))
        assertTrue(FrameOffer.newest(listOf(encoded), "aqara-s1-plus")!!.bundle != null)
    }

    @Test fun aBundleNeedsBothFilesFromThisReleaseAtASaneSize() {
        val tag = "frames/aqara-s1-plus/v1.0.1"
        val zip = "frame-bundle-aqara-s1-plus-1.0.1.zip"
        fun offer(vararg a: ReleaseAsset) = FrameOffer.newest(listOf(gh(tag).copy(assets = a.toList())), "aqara-s1-plus")!!.bundle
        assertNull("no .sha256", offer(asset(tag, zip)))
        assertNull("no zip", offer(asset(tag, "$zip.sha256")))
        assertNull("another version's", offer(asset(tag, "frame-bundle-aqara-s1-plus-1.0.0.zip"), asset(tag, "frame-bundle-aqara-s1-plus-1.0.0.zip.sha256")))
        assertNull("another frame's", offer(asset(tag, "frame-bundle-lululemon-mirror-1.0.1.zip"), asset(tag, "frame-bundle-lululemon-mirror-1.0.1.zip.sha256")))
        assertNull("another release's download", offer(asset("frames/aqara-s1-plus/v1.0.0", zip), asset(tag, "$zip.sha256")))
        assertNull("empty", offer(asset(tag, zip, 0), asset(tag, "$zip.sha256")))
        assertNull("too large", offer(asset(tag, zip, FrameBundleZip.MAX_ZIP + 1), asset(tag, "$zip.sha256")))
        assertNull("not https", offer(ReleaseAsset(zip, "http://github.com/GlassworksHQ/glassworks/releases/download/$tag/$zip", 1), asset(tag, "$zip.sha256")))
        assertNull("the Mirror's notice-only release", FrameOffer.newest(listOf(gh("frames/lululemon-mirror/v1.0.0")), "lululemon-mirror")!!.bundle)
    }

    @Test fun appReleaseDownloadsAreCheckedAsBefore() {
        val u = "https://github.com/GlassworksHQ/glassworks/releases/download/v0.4.5/s1-kiosk-0.4.5.apk"
        assertTrue(Releases.isReleaseDownload(u, "v0.4.5", "s1-kiosk-0.4.5.apk"))
        assertFalse(Releases.isReleaseDownload(u, "v0.4.4", "s1-kiosk-0.4.5.apk"))
        assertFalse(Releases.isReleaseDownload(u, "v0.4.5", "s1-kiosk-0.4.4.apk"))
        assertFalse(Releases.isReleaseDownload("$u?x=1", "v0.4.5", "s1-kiosk-0.4.5.apk"))
        assertFalse(Releases.isReleaseDownload("https://example.com/GlassworksHQ/glassworks/releases/download/v0.4.5/s1-kiosk-0.4.5.apk", "v0.4.5", "s1-kiosk-0.4.5.apk"))
        assertFalse("the name can't hide in the tag", Releases.isReleaseDownload(u, "v0.4.5/s1-kiosk-0.4.5.apk", ""))
    }

    @Test fun onlyAFrameThatInstallsFromHereAndDeclaresBundlesInstalls() {
        val s1 = object : dev.glassworks.frame.FrameAgent {
            override val declared = setOf(dev.glassworks.frame.AgentCapability.FRAME_BUNDLE)
            override fun version(): Int? = null
            override fun offers(capability: dev.glassworks.frame.AgentCapability) = false
        }
        assertTrue(FrameUpdateModel.installs(FrameRelease("aqara-s1-plus", "1.0.0", install = null), s1))
        assertFalse("a panel without the loader says how instead", FrameUpdateModel.installs(FrameRelease("aqara-s1-plus", null, install = "Kiln"), s1))
        assertFalse("the Mirror", FrameUpdateModel.installs(release("1.0.0"), dev.glassworks.frame.NoAgent))
        assertFalse("no frame-bundle in frame.json", FrameUpdateModel.installs(FrameRelease("x", "1.0.0", install = null), dev.glassworks.frame.NoAgent))
    }

    @Test fun aFrameThatDoesntInstallFromHereKeepsTodaysCardAndRefusesInstall() {
        val m = FrameUpdateModel(release("1.0.0"), "lululemon Studio Mirror frame")
        m.onChecked(FrameOffer.newest(listOf(gh("frames/lululemon-mirror/v1.0.1", body = "Fixes.")), "lululemon-mirror"))
        val before = m.view()
        assertEquals(install, (m.begin(0, null) as FrameUpdateModel.Begin.Refused).reason)
        assertEquals("the card didn't change", before.releaseSummary, m.view().releaseSummary)
        assertEquals("$install\n\nFixes.", m.view().releaseSummary)
        assertFalse(m.view().inProgress)
    }

    @Test fun theInstallRowSaysWhatInstallDoes() {
        val m = FrameUpdateModel(FrameRelease("aqara-s1-plus", "1.0.0", install = null), "Aqara S1 Plus frame", installs = true)
        assertEquals("Not checked yet", m.installLine())
        assertEquals(FrameUpdateModel.NOT_CHECKED, (m.begin(0, null) as FrameUpdateModel.Begin.Refused).reason)
        m.onChecked(null)
        assertEquals("Up to date", m.installLine())
        assertEquals(FrameUpdateModel.UP_TO_DATE, (m.begin(0, null) as FrameUpdateModel.Begin.Refused).reason)
        assertEquals("up to date is no error on the card", "", m.view().releaseSummary)
        val tag = "frames/aqara-s1-plus/v1.0.1"
        val zip = "frame-bundle-aqara-s1-plus-1.0.1.zip"
        m.onChecked(FrameOffer.newest(listOf(gh(tag).copy(assets = listOf(asset(tag, zip, 1000), asset(tag, "$zip.sha256", 90)))), "aqara-s1-plus"))
        assertEquals("Frame 1.0.1 is out. Installing it restarts the panel once", m.installLine())
        val a = (m.begin(0, null) as FrameUpdateModel.Begin.Started).attempt
        m.onProgress(a, 0.42)
        assertEquals("Downloading frame 1.0.1: 42%", m.installLine())
        assertEquals(42f, m.view().progress)
        assertTrue(m.view().hasProgress)
        m.onFailed(a, "no network (UnknownHostException)")
        assertEquals("Install of frame 1.0.1 failed: no network (UnknownHostException)", m.installLine())
    }
}
