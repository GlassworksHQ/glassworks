package dev.glassworks.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.glassworks.KioskPrefs
import dev.glassworks.manage.ManageFixture
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.radioDefinition
import dev.glassworks.theme.Themes
import dev.glassworks.ui.Mockup
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.URLEncoder

/**
 * #691's Custom themes on the panel: the Appearance page with Custom picked and its colors under the cards, and the
 * sheet that changes one (swatches to tap, the color typed). At the S1's 1440x720 and at a portrait 1080x1920.
 * Illustrative colors only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*CustomThemeMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomThemeMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private fun menu(): KioskSettingsOverlay {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply {
            customTheme = Themes.custom(
                Themes.Palette(0xfffb923c.toInt(), 0xfff472b6.toInt(), 0xff14101c.toInt()),
                Themes.Palette(0xffea580c.toInt(), 0xffdb2777.toInt(), 0xfffff7ed.toInt()),
            )
            overlayTheme = "custom/dark"
        }
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val radio = radioDefinition(app, FixedReadout(null, running = false))
        o.show(MenuFixture.model(app, radio, panes = listOf(radio)))
        Mockup.idle()
        return o
    }

    private fun shot(name: String, w: Int, h: Int, then: (KioskSettingsOverlay) -> Unit = {}) {
        val o = menu()
        o.previewSection(CoreSettings.APPEARANCE)
        Mockup.idle()
        Mockup.layout(o, w, h)
        then(o)
        Mockup.idle()
        Mockup.save(name, o, w, h)
    }

    /** Scrolls the page to its end, where the custom colors are. */
    private fun toEnd(o: View) {
        fun scrolls(v: View): Sequence<ScrollView> = sequence {
            if (v is ScrollView) yield(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(scrolls(v.getChildAt(i)))
        }
        for (s in scrolls(o)) s.scrollTo(0, s.getChildAt(0)?.height ?: 0)
    }

    @Test fun landscape() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("custom-01-appearance-custom-picked", Mockup.W, Mockup.H)
        shot("custom-02-appearance-custom-colors", Mockup.W, Mockup.H) { toEnd(it) }
        shot("custom-03-color-sheet", Mockup.W, Mockup.H) { it.previewEdit(CoreSettings.CUSTOM_GROUND) }
        shot("custom-04-color-sheet-typed", Mockup.W, Mockup.H) { it.previewEdit(CoreSettings.CUSTOM_LIGHT_A, typed = "#22d3ee") }
    }

    @Config(qualifiers = "w540dp-h960dp-port-xhdpi")
    @Test fun portrait() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("custom-11-portrait-appearance", 1080, 1920)
        shot("custom-12-portrait-color-sheet", 1080, 1920) { it.previewEdit(CoreSettings.CUSTOM_LIGHT_A) }
    }

    /** The Manage page's Appearance, and its color dialog, as HTML (screenshots in a browser). */
    @Test fun manage() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val app = RuntimeEnvironment.getApplication()
        menu()
        val s = CoreSettings(
            KioskPrefs(app), "1.0.0", status = { "" }, deviceSettings = null,
            licences = dev.glassworks.pane.SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, hardware = MenuFixture.S1_HARDWARE,
        ).sections().getValue(SettingsPlace.APPEARANCE)
        val f = ManageFixture()
        f.statusFn = { f.status.copy(sections = listOf(s)) }
        val b = f.Browser().also { it.signInWithHa() }
        val out = File(Mockup.dir!!, "manage").apply { mkdirs() }
        File(out, "custom-appearance.html").writeBytes(b.get("/${CoreSettings.APPEARANCE}").body)
        val key = URLEncoder.encode("${CoreSettings.CUSTOM_DARK_TITLE}/${CoreSettings.CUSTOM_LIGHT_A}", "UTF-8")
        File(out, "custom-color-dialog.html").writeBytes(b.get("/${CoreSettings.APPEARANCE}?edit=$key").body)
    }
}
