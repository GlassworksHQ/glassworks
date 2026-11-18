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
        for (line in listOf(ReleaseLine.S1_KIOSK, ReleaseLine.GENERIC_ANDROID, ReleaseLine.GENERIC_ANDROID_LEGACY)) {
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
}
