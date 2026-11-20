/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.settings

import android.app.Application
import dev.glassworks.KioskPrefs
import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.text
import dev.glassworks.pane.SettingsChange
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #691: the Appearance page, the same on the panel and on the Manage page. One card per theme with a light and a dark
 * orb; a pick is saved as `<theme>/<variant>`, Glassworks dark by default, and a factory reset clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppearanceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: KioskPrefs
    private var changed = 0

    @Before fun clean() {
        app.getSharedPreferences("kiosk", 0).edit().clear().commit()
        prefs = KioskPrefs(app)
    }

    private fun section(): SettingsSection = CoreSettings(
        prefs, "1.0.0", status = { "" }, deviceSettings = null,
        licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {}, hardware = MenuFixture.S1_HARDWARE,
        live = CoreSettings.Live(theme = { changed++ }),
    ).sections().getValue(SettingsPlace.APPEARANCE)

    private fun picker(s: SettingsSection): SettingsItem.Choice =
        SettingsChange.find(s, "${CoreSettings.THEME_TITLE}/${CoreSettings.THEME_TITLE}") as SettingsItem.Choice

    @Test fun thePageHasTheThemePickerWithFiveThemesLightAndDark() {
        val s = section()
        assertEquals(CoreSettings.APPEARANCE, s.id)
        assertEquals("Appearance", s.title)
        val c = picker(s)
        assertEquals("glassworks/dark", c.value())
        val swatches = c.options().map { c.swatch!!(it.first) }
        assertEquals(listOf("Glassworks", "Aurora", "Dusk", "Dune", "Graphite"), swatches.map { it.group }.distinct())
        assertEquals(5, swatches.count { it.dark })
        assertEquals(listOf("Default"), swatches.map { it.note }.filter { it.isNotEmpty() }.distinct())
    }

    @Test fun aPickIsSavedAndTheOverlaysAreTold() {
        val c = picker(section())
        var why: String? = "not called"
        SettingsChange.change(c, "dusk/light") { why = it }
        assertEquals(null, why)
        assertEquals("dusk/light", prefs.overlayTheme)
        assertEquals("dusk/light", c.value())
        assertEquals(1, changed)
        assertEquals("Dusk, light", SettingsChange.shown(c))
        // An id no theme has never gets to the save.
        SettingsChange.change(c, "ember/dark") { why = it }
        assertTrue(why != null)
        assertEquals("dusk/light", prefs.overlayTheme)
    }

    @Test fun aSavedCustomThemeIsOneMoreCardAndShowsAsPicked() {
        val custom = dev.glassworks.theme.Themes.custom(
            dev.glassworks.theme.Themes.Palette(0xffff5722.toInt(), 0xff00bcd4.toInt(), 0xff101418.toInt()),
            dev.glassworks.theme.Themes.Palette(0xffbf360c.toInt(), 0xff006064.toInt(), 0xfff5f5f5.toInt()),
        )
        prefs.customTheme = custom
        prefs.overlayTheme = "custom/light"
        val c = picker(section())
        assertEquals("custom/light", c.value())
        assertEquals(12, c.options().size)
        val s = c.swatch!!("custom/light")
        assertEquals("Custom", s.group)
        assertEquals(0xffbf360c.toInt(), s.a)
        assertEquals(custom.light, prefs.overlayPick.palette)
    }

    @Test fun theAssistLookIsTheOrbOrTheEdgeGlow() {
        val c = SettingsChange.find(section(), CoreSettings.ASSIST_TITLE) as SettingsItem.Choice
        assertEquals("orb", c.value())
        assertEquals(listOf("orb" to "Orb", "glow" to "Edge glow"), c.options())
        assertEquals(CoreSettings.ASSIST_GLOW_LINE, c.cards!!("glow"))
        SettingsChange.change(c, "glow") {}
        assertEquals(KioskPrefs.ASSIST_GLOW, prefs.assistLook)
        app.getSharedPreferences("kiosk", 0).edit().putString("assist_look", "sparkles").commit()
        assertEquals("anything else reads as the orb", KioskPrefs.ASSIST_ORB, KioskPrefs(app).assistLook)
    }

    @Test fun anUnknownSavedThemeReadsAsTheDefaultAndAResetClearsIt() {
        app.getSharedPreferences("kiosk", 0).edit().putString("overlay_theme", "neon/dark").commit()
        assertEquals("glassworks/dark", KioskPrefs(app).overlayTheme)
        prefs.overlayTheme = "aurora/light"
        assertFalse(KioskPrefs.keptByReset("overlay_theme"))
        assertTrue(prefs.resetSettings())
        assertEquals("glassworks/dark", KioskPrefs(app).overlayTheme)
    }

    @Test fun theManagePageDrawsTheSameCardsAndAPickChangesIt() {
        val s = section()
        val f = ManageFixture()
        f.statusFn = { f.status.copy(sections = listOf(s)) }
        val b = f.Browser().also { it.signInWithHa() }
        val page = b.get("/${CoreSettings.APPEARANCE}").text()
        assertEquals(5, Regex("""class="tc[ "]""").findAll(page).count())
        assertEquals(10, Regex("""<button class="sw""").findAll(page).count())
        assertTrue("the default is ringed, with a moon", page.contains("""value="glassworks/dark" aria-pressed="true""""))
        assertTrue(page.contains("""class="bd moon""""))
        assertTrue(page.contains(CoreSettings.THEME_NOTE))
        val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to CoreSettings.APPEARANCE, "key" to "Theme/Theme", "value" to "dune/light"))
        assertEquals(303, r.status)
        assertEquals("dune/light", prefs.overlayTheme)
        val after = b.get("/${CoreSettings.APPEARANCE}").text()
        assertTrue(after.contains("""value="dune/light" aria-pressed="true""""))
        assertTrue(after.contains("""class="bd sun""""))
        assertTrue("the Assist look's two cards", after.contains(">Orb</b>") && after.contains(">Edge glow</b>"))
    }
}
