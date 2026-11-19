/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.update

import android.app.Application
import android.content.Context
import dev.glassworks.KioskPrefs
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.pane.SettingsChange
import dev.glassworks.platform.AppBuild
import dev.glassworks.s1.KioskApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** #605: the update channel's storage, its Device page row, and the update check following it at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateChannelTest {
    private val app = RuntimeEnvironment.getApplication()
    private val managers = ArrayList<UpdateManager>()

    @After fun tearDown() = managers.forEach { it.stop() }

    @Test fun storedWithTheOtherPrefsAndStableByDefault() {
        val prefs = KioskPrefs(app)
        assertEquals(UpdateChannel.STABLE, prefs.updateChannel)
        prefs.updateChannel = UpdateChannel.BETA
        assertEquals(UpdateChannel.BETA, KioskPrefs(app).updateChannel)
        assertEquals("beta", app.getSharedPreferences("kiosk", Context.MODE_PRIVATE).getString(KioskPrefs.KEY_UPDATE_CHANNEL, null))
        KioskPrefs(app).updateChannel = UpdateChannel.STABLE
        assertEquals(UpdateChannel.STABLE, KioskPrefs(app).updateChannel)
    }

    @Test fun theRowSaysWhichChannelInAFewWords() {
        var c = UpdateChannel.STABLE
        val row = UpdateChannelRow.item({ c }, { c = it }) {}
        assertEquals("Update channel", row.title)
        assertEquals(listOf("stable" to "Stable", "beta" to "Beta"), row.options())
        assertEquals("Stable", SettingsChange.shown(row))
        c = UpdateChannel.BETA
        assertEquals("Beta · may have bugs", SettingsChange.shown(row))
        assertTrue("short enough to keep the title on the Mirror's portrait page", SettingsChange.shown(row).length <= 24)
    }

    @Test fun eachChangeAsksFirstAndSaysNothingGoesBackToAnOlderVersion() {
        var c = UpdateChannel.STABLE
        val row = UpdateChannelRow.item({ c }, { c = it }) {}
        assertEquals(UpdateChannelRow.TO_BETA, row.confirm("beta"))
        assertTrue(UpdateChannelRow.TO_BETA.detail.contains("may have bugs"))
        assertTrue(UpdateChannelRow.TO_BETA.detail.contains("keeps the version it has until a newer stable one is out"))
        assertNull("the channel it has asks nothing", row.confirm("stable"))
        c = UpdateChannel.BETA
        assertNull(row.confirm("beta"))
        assertEquals(UpdateChannelRow.TO_STABLE, row.confirm("stable"))
        assertTrue(UpdateChannelRow.TO_STABLE.detail.contains("stable version newer than it"))
        assertNull("not a channel: no question, the save refuses it", row.confirm("nightly"))
    }

    @Test fun savingTellsTheUpdateCheckOnlyWhenItChanges() {
        var c = UpdateChannel.STABLE
        var changed = 0
        val row = UpdateChannelRow.item({ c }, { c = it }) { changed++ }
        val answers = ArrayList<String?>()
        row.save("beta") { answers += it }
        assertEquals(UpdateChannel.BETA, c)
        assertEquals(1, changed)
        row.save("beta") { answers += it }
        assertEquals("the same channel again: nothing to pick again", 1, changed)
        row.save("nightly") { answers += it }
        assertEquals(UpdateChannel.BETA, c)
        assertEquals(listOf(null, null, "Not a channel: nightly"), answers)
    }

    @Test fun everyFramesDevicePageHasTheRowNextToSilentUpdates() {
        val items = GenericAndroid(app).deviceSection(app).items
        val titles = items.map { (it as? dev.glassworks.pane.SettingsItem.Choice)?.title ?: (it as? dev.glassworks.pane.SettingsItem.Info)?.title?.invoke() }
        assertEquals(titles.indexOf(GenericAndroid.SILENT_UPDATES_TITLE) + 1, titles.indexOf(UpdateChannelRow.TITLE))
    }

    /** The release list as GitHub gives it: a beta 1.4.0 above the stable 1.3.0. */
    private fun releases(): List<GithubRelease> {
        fun r(v: String, pre: Boolean): GithubRelease {
            val tag = "aqara-s1-plus-v$v"
            fun a(n: String) = ReleaseAsset(n, "https://github.com/GlassworksHQ/glassworks/releases/download/$tag/$n", 100)
            return GithubRelease(tag, tag, "Notes for $v.", "https://github.com/GlassworksHQ/glassworks/releases/tag/$tag", false, pre,
                listOf(a("s1-kiosk-$v.apk"), a("s1-kiosk-$v.apk.sha256"), a("s1-kiosk-arm64-v8a-$v.apk"), a("s1-kiosk-arm64-v8a-$v.apk.sha256")))
        }
        return listOf(r("1.4.0", pre = true), r("1.3.0", pre = false))
    }

    /** A manager on [installed] whose last check listed [releases], as after a check. */
    private fun checked(installed: String, channel: () -> UpdateChannel, changes: LinkedBlockingQueue<Unit>): UpdateManager {
        val (maj, min, pat) = installed.split('.').map { it.toInt() }
        val m = UpdateManager(app, KioskApp.runtime(GenericAndroid(app), AppBuild(installed, maj * 10000 + min * 100 + pat, debug = false)), channel) { changes.add(Unit) }
        managers += m
        fun set(name: String, v: Any) = UpdateManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(m, v)
        set("cachedList", releases())
        set("listed", true)
        return m
    }

    @Test fun theUpdateEntityFollowsTheChannelAtOnce() {
        var c = UpdateChannel.STABLE
        val changes = LinkedBlockingQueue<Unit>()
        val m = checked("1.3.0", { c }, changes)
        c = UpdateChannel.BETA
        m.onChannelChanged()
        assertTrue("HA hears of it", changes.poll(5, TimeUnit.SECONDS) != null)
        assertEquals("1.4.0", m.model.view().latestVersion)
        assertTrue(m.model.updateAvailable)
        assertTrue(m.model.view().releaseSummary.startsWith(UpdateModel.BETA_NOTE))

        c = UpdateChannel.STABLE
        m.onChannelChanged()
        assertTrue(changes.poll(5, TimeUnit.SECONDS) != null)
        assertEquals("1.3.0", m.model.view().latestVersion)
        assertFalse(m.model.updateAvailable)
    }

    @Test fun backOnStableFromTheBetaNothingLowerIsOffered() {
        var c = UpdateChannel.BETA
        val changes = LinkedBlockingQueue<Unit>()
        val m = checked("1.4.0", { c }, changes)
        c = UpdateChannel.STABLE
        m.onChannelChanged()
        assertTrue(changes.poll(5, TimeUnit.SECONDS) != null)
        assertEquals("1.3.0", m.model.latestOffer!!.version.toString())
        assertFalse("1.3.0 is lower than the beta it runs", m.model.updateAvailable)
        assertEquals("1.4.0", m.model.view().latestVersion)
        assertNull(m.model.beginInstall(0))
    }

    /** #613 review: a 200 whose list doesn't parse is a failed check; it keeps neither the empty list nor the ETag. */
    @Test fun anUnusableListIsAFailedCheckAndKeepsNoEtag() {
        val m = checked("1.3.0", { UpdateChannel.STABLE }, LinkedBlockingQueue())
        fun field(name: String) = UpdateManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("etag").set(m, "\"before\"")
        val listed = UpdateManager::class.java.getDeclaredMethod("listed", String::class.java, String::class.java).apply { isAccessible = true }
        val failed = listed.invoke(m, "{\"message\": \"not a list\"}", "\"new\"")
        assertEquals("Failed", failed!!.javaClass.simpleName)
        assertEquals("the ETag stays the last good list's", "\"before\"", field("etag").get(m))
        assertEquals("the last good list stays", 2, (field("cachedList").get(m) as List<*>).size)

        val found = listed.invoke(m, "[]", "\"empty\"")
        assertEquals("Found", found!!.javaClass.simpleName)
        assertEquals("\"empty\"", field("etag").get(m))
    }

    @Test fun beforeTheFirstCheckAChangeWaitsForIt() {
        val changes = LinkedBlockingQueue<Unit>()
        val m = UpdateManager(app, KioskApp.runtime(GenericAndroid(app), AppBuild("1.3.0", 10300, debug = false)), { UpdateChannel.BETA }) { changes.add(Unit) }
        managers += m
        m.onChannelChanged()
        assertNull("nothing to pick from: no change", changes.poll(500, TimeUnit.MILLISECONDS))
        assertTrue(m.model.view().missingState)
    }
}
