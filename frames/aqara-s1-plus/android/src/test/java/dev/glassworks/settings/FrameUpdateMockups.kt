package dev.glassworks.settings

import android.app.Application
import dev.glassworks.Glassworks
import dev.glassworks.GlassworksApp
import dev.glassworks.GlassworksRuntime
import dev.glassworks.KioskPrefs
import dev.glassworks.auth.HaTokenClient
import dev.glassworks.auth.HttpPoster
import dev.glassworks.auth.SignInServer
import dev.glassworks.frame.FrameRelease
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.manage.ManageFixture
import dev.glassworks.platform.AppBuild
import dev.glassworks.s1.KioskApp
import dev.glassworks.s1.frame.AqaraS1Plus
import dev.glassworks.s1.manage.S1Manage
import dev.glassworks.ui.Mockup
import dev.glassworks.update.FrameOffer
import dev.glassworks.update.FrameUpdateModel
import dev.glassworks.update.GithubRelease
import dev.glassworks.update.ReleaseAsset
import dev.glassworks.update.UpdateChannel
import dev.glassworks.update.UpdateManager
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemProperties

/**
 * The S1's Device page with its frame rows (#139), on the panel at 1440x720 and as the Manage page's HTML: a frame
 * update out, downloading, restarting to finish, and a refusal. The real settings model and Manage page over the S1
 * frame with the loader's stamp (frame 1.0.0) and an illustrative release (1.0.1). Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*FrameUpdateMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FrameUpdateMockups.App::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameUpdateMockups {
    /** The app the rows find the update manager through, as KioskApp is on the panel. */
    class App : Application(), GlassworksApp {
        lateinit var runtime: Glassworks
        override val glassworks: Glassworks get() = runtime
    }

    private val app = RuntimeEnvironment.getApplication() as App
    private val prefs = KioskPrefs(app)
    private var updates: UpdateManager? = null

    @After fun tearDown() {
        updates?.stop()
        ShadowSystemProperties.reset()
    }

    private fun shoot(name: String, runtime: GlassworksRuntime) {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        fun model() = S1Settings.model(runtime, prefs, runtime.paneDefinitions(app), deviceSettings = true, fullScreen = {}, version = "0.4.5", context = app)
        o.reload = ::model
        o.show(model())
        o.previewSection(GenericAndroid.DEVICE)
        Mockup.idle()
        // The frame rows end the page: scrolled to its end, as CustomThemeMockups does.
        fun scrolls(v: android.view.View): Sequence<android.widget.ScrollView> = sequence {
            if (v is android.widget.ScrollView) yield(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) yieldAll(scrolls(v.getChildAt(i)))
        }
        Mockup.layout(o, 1440, 720)
        for (sv in scrolls(o).filter { it.width > 720 }) sv.scrollTo(0, sv.getChildAt(0)?.height ?: 0) // the page, not the nav
        Mockup.idle()
        Mockup.save(name, o, 1440, 720)
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val m = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs, version = "0.4.5")
        val browser = ManageFixture().apply { statusFn = { m.status() } }.Browser().also { it.signInWithHa() }
        File(Mockup.dir!!, "manage").mkdirs()
        File(Mockup.dir!!, "manage/$name.html").writeBytes(browser.get("/device").body)
    }

    @Test fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        Mockup.interFromSources()
        ShadowSystemProperties.override(FrameRelease.ID_PROP, "aqara-s1-plus")
        ShadowSystemProperties.override(FrameRelease.VERSION_PROP, "1.0.0")
        val runtime = KioskApp.runtime(AqaraS1Plus(), build = AppBuild("0.4.5", 4005, debug = false))
        app.runtime = runtime
        val u = UpdateManager(app, runtime, channel = { UpdateChannel.STABLE }, onChange = {}).also { updates = it }
        runtime.updates = u
        val tag = "frames/aqara-s1-plus/v1.0.1"
        val zip = "frame-bundle-aqara-s1-plus-1.0.1.zip"
        fun asset(n: String, size: Long) = ReleaseAsset(n, "https://github.com/GlassworksHQ/glassworks/releases/download/$tag/$n", size)
        val release = GithubRelease(tag, "Aqara S1 Plus frame 1.0.1", "**Fixes the frame agent.**", "https://github.com/GlassworksHQ/glassworks/releases/tag/$tag",
            false, false, listOf(asset(zip, 48_000_000), asset("$zip.sha256", 103)))
        val f = u.frame!!
        f.onChecked(FrameOffer.newest(listOf(release), "aqara-s1-plus"))
        shoot("frame-01-available", runtime)

        val a = (f.begin(0, null) as FrameUpdateModel.Begin.Started).attempt
        f.onProgress(a, 0.42)
        shoot("frame-02-downloading", runtime)
        f.onPhase(a, FrameUpdateModel.Phase.RESTARTING, 0, "Frame 1.0.1 is staged. Restarting the panel to finish")
        shoot("frame-03-restarting", runtime)
        f.onFailed(a, "the panel refused it (bundle version 1000001 is below the floor 1000002 (no downgrades))")
        shoot("frame-04-refused", runtime)
    }
}
