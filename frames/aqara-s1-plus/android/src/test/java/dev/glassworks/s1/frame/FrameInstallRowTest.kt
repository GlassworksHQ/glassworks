package dev.glassworks.s1.frame

import android.app.Application
import android.content.ContextWrapper
import dev.glassworks.GlassworksApp
import dev.glassworks.frame.FrameRelease
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.answer
import dev.glassworks.manage.text
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.platform.AppBuild
import dev.glassworks.radio.SysProps
import dev.glassworks.s1.KioskApp
import dev.glassworks.update.FrameInstaller
import dev.glassworks.update.FrameUpdateModel
import dev.glassworks.update.UpdateChannel
import dev.glassworks.update.UpdateManager
import dev.glassworks.web.PageShell.esc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties

/**
 * #139: on an S1 whose loader stamps its frame version, the Device page has a Frame update row with Install, the same
 * install as Home Assistant's card; the Manage page draws the same row and runs the same install, after its question
 * and the code the panel shows (it restarts the panel, as Reboot does). A panel without the loader and the Mirror keep
 * the version row alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FrameInstallRowTest {
    private val app = RuntimeEnvironment.getApplication()

    @After fun reset() = ShadowSystemProperties.reset()

    private fun stamp() {
        ShadowSystemProperties.override(FrameRelease.ID_PROP, "aqara-s1-plus")
        ShadowSystemProperties.override(FrameRelease.VERSION_PROP, "1.0.0")
    }

    private val props = object : SysProps {
        override fun get(name: String) = ""
        override fun set(name: String, value: String) {}
    }

    private fun page(release: FrameRelease, installs: Boolean): SettingsSection =
        S1Device(AqaraCloud(run = { "" }), props, reader = { it.run() }).section(app, release, installs) { null }

    private fun install(s: SettingsSection) = s.items.filterIsInstance<SettingsItem.Action>().singleOrNull { it.title == GenericAndroid.FRAME_UPDATE_TITLE }

    @Test fun anS1WithTheLoaderHasTheInstallRow() {
        stamp()
        val frame = AqaraS1Plus()
        assertEquals(null, frame.frameRelease.install)
        assertTrue("frame.json declares frame-bundle", FrameUpdateModel.installs(frame.frameRelease, frame.agent))
        val s = frame.deviceSection(app) { null }
        val titles = s.items.map { (it as? SettingsItem.Info)?.title?.invoke() ?: (it as? SettingsItem.Action)?.title }
        assertEquals("right under Frame version", listOf("Frame version", "Frame update"), titles.takeLast(2))
        val a = install(s)!!
        assertEquals("Install", a.label)
        assertTrue("from the Manage page only with the panel's code: it restarts the panel", a.needsCode)
        assertNotNull("it asks first", a.confirm)
        assertEquals("Checking", a.detail())
        var said: String? = "unset"
        a.run { said = it }
        assertEquals("The update service isn't running.", said)
    }

    @Test fun aPanelWithoutTheLoaderAndTheMirrorKeepTheVersionRowAlone() {
        val old = AqaraS1Plus()
        assertFalse(FrameUpdateModel.installs(old.frameRelease, old.agent))
        assertEquals(null, install(old.deviceSection(app) { null }))
        assertEquals(null, install(page(old.frameRelease, installs = false)))
        val mirror = TestFrames.mirror(app)
        assertFalse(FrameUpdateModel.installs(mirror.frameRelease!!, mirror.agent))
        assertEquals(null, install(mirror.deviceSection(app)!!))
    }

    @Test fun theManagePageRunsTheSameInstallAsThePanel() {
        stamp()
        val runtime = KioskApp.runtime(AqaraS1Plus(), AppBuild("1.0.0", 10000, debug = false))
        val context = object : ContextWrapper(app), GlassworksApp {
            override val glassworks = runtime
            override fun getApplicationContext() = this
        }
        val updates = UpdateManager(context, runtime, channel = { UpdateChannel.STABLE }, onChange = {})
        runtime.updates = updates
        try {
            val items = GenericAndroid.frameVersionItems(context, runtime.frame.frameRelease!!, installs = true)
            val section = SettingsSection(GenericAndroid.DEVICE, "S1 Plus", items)
            val f = ManageFixture().also { f -> f.statusFn = { f.status.copy(sections = f.status.sections.filter { it.id != section.id } + section) } }
            val b = f.Browser().also { it.signInWithHa() }
            val key = SettingsChange.changeable(section).first { it.second is SettingsItem.Action }.first
            fun form(extra: Map<String, String> = emptyMap()) =
                mapOf("csrf" to b.csrf(), "section" to section.id, "key" to key, "value" to SettingsItem.Action.RUN) + extra

            val page = b.get("/device").text()
            for (t in listOf("Frame version", "Frame update", "Install")) assertTrue("$t on the Manage page", page.contains(t))
            val question = b.post("/settings", form())
            assertTrue("the panel's question first", question.text().contains("Install the frame update?"))
            val asked = b.post("/settings", form(mapOf("answer" to question.answer()!!)))
            assertTrue("then the code", asked.text().contains("Enter the code on the panel."))
            val ran = b.post("/settings", form(mapOf("answer" to asked.answer()!!, "code" to f.shown.last()!!.code)))
            // Robolectric has no loader record: the install refuses, and says so on both surfaces.
            assertTrue(ran.text(), ran.text().contains(esc(FrameInstaller.NO_LOADER.substringBefore("."))))
            val row = items.filterIsInstance<SettingsItem.Action>().single()
            assertEquals("the panel's row says the same", "Not installed: ${FrameInstaller.NO_LOADER}", row.detail())
            assertEquals("Home Assistant's card too", "Not installed: ${FrameInstaller.NO_LOADER}", updates.frame!!.view().releaseSummary)
        } finally {
            updates.stop()
        }
    }

    /** An app update and a frame update never run together: the second is refused with a message, not queued. */
    @Test fun anAppUpdateAndAFrameUpdateRefuseEachOther() {
        stamp()
        val runtime = KioskApp.runtime(AqaraS1Plus(), AppBuild("0.4.5", 4005, debug = false))
        val updates = UpdateManager(app, runtime, channel = { UpdateChannel.STABLE }, onChange = {})
        try {
            val base = "https://github.com/GlassworksHQ/glassworks/releases/download"
            fun asset(tag: String, n: String) = dev.glassworks.update.ReleaseAsset(n, "$base/$tag/$n", 1000)
            // The app update is downloading: Install on the Frame update card is refused at once.
            val appTag = "v0.4.6"
            updates.model.onChecked(dev.glassworks.update.UpdateOffer(dev.glassworks.update.AppVersion(0, 4, 6), appTag, "", "",
                asset(appTag, "s1-kiosk-0.4.6.apk"), asset(appTag, "s1-kiosk-0.4.6.apk.sha256")))
            val appAttempt = updates.model.beginInstall(0)!!
            var said: String? = "unset"
            updates.installFrame { said = it }
            assertEquals(FrameInstaller.APP_BUSY, said)
            assertEquals("Not installed: ${FrameInstaller.APP_BUSY}", updates.frame!!.installLine())
            updates.model.onFailed(appAttempt, "test")

            // The frame update is installing: Install on the App update card says so and starts nothing.
            val tag = "frames/aqara-s1-plus/v1.0.1"
            val zip = "frame-bundle-aqara-s1-plus-1.0.1.zip"
            val f = updates.frame!!
            f.onChecked(dev.glassworks.update.FrameOffer.newest(listOf(dev.glassworks.update.GithubRelease(tag, "", "", "", false, false,
                listOf(asset(tag, zip), asset(tag, "$zip.sha256")))), "aqara-s1-plus"))
            assertTrue(f.begin(0, null) is FrameUpdateModel.Begin.Started)
            updates.onCommand(UpdateManager.CMD_INSTALL)
            assertEquals(UpdateManager.FRAME_BUSY, updates.model.view().releaseSummary.lineSequence().first())
            assertEquals(dev.glassworks.update.UpdateModel.Phase.IDLE, updates.model.currentPhase)
        } finally {
            updates.stop()
        }
    }
}
