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
import dev.glassworks.theme.Themes
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

    @Test fun thePageHasTheThemePickerWithFiveThemesAndCustomLightAndDark() {
        val s = section()
        assertEquals(CoreSettings.APPEARANCE, s.id)
        assertEquals("Appearance", s.title)
        val c = picker(s)
        assertEquals("glassworks/dark", c.value())
        val swatches = c.options().map { c.swatch!!(it.first) }
        // Custom is always a card: Glassworks' colors until some are picked.
        assertEquals(listOf("Glassworks", "Aurora", "Dusk", "Dune", "Graphite", "Custom"), swatches.map { it.group }.distinct())
        assertEquals(6, swatches.count { it.dark })
        assertEquals(Themes.GLASSWORKS.dark.a, c.swatch!!("custom/dark").a)
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
        assertEquals("the edge glow is the default", "glow", c.value())
        assertEquals(listOf("orb" to "Orb", "glow" to "Edge glow"), c.options())
        assertEquals(CoreSettings.ASSIST_GLOW_LINE, c.cards!!("glow"))
        SettingsChange.change(c, "orb") {}
        assertEquals(KioskPrefs.ASSIST_ORB, prefs.assistLook)
        assertEquals("a picked orb is kept", "orb", app.getSharedPreferences("kiosk", 0).getString("assist_look", null))
        app.getSharedPreferences("kiosk", 0).edit().putString("assist_look", "sparkles").commit()
        assertEquals("anything else reads as the edge glow", KioskPrefs.ASSIST_GLOW, KioskPrefs(app).assistLook)
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
        assertEquals(6, Regex("""class="tc[ "]""").findAll(page).count())
        assertEquals(12, Regex("""<button class="sw""").findAll(page).count())
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

    // ---- #691's Custom themes: six colors, a light and a dark of each, on both surfaces

    private fun colour(s: SettingsSection, group: String, title: String) = SettingsChange.find(s, "$group/$title") as SettingsItem.Text

    @Test fun pickingCustomBeforeAnyColorStartsFromGlassworks() {
        var why: String? = "not called"
        SettingsChange.change(picker(section()), "custom/light") { why = it }
        assertEquals(null, why)
        assertEquals("the pick holds", "custom/light", prefs.overlayTheme)
        assertEquals(Themes.GLASSWORKS.light, prefs.customTheme!!.light)
        assertEquals(Themes.GLASSWORKS.dark, prefs.customTheme!!.dark)
    }

    @Test fun theCustomColorsAreSixRowsThatShowGlassworksUntilChanged() {
        val s = section()
        for (group in listOf(CoreSettings.CUSTOM_DARK_TITLE, CoreSettings.CUSTOM_LIGHT_TITLE)) {
            for (title in listOf(CoreSettings.CUSTOM_LIGHT_A, CoreSettings.CUSTOM_LIGHT_B, CoreSettings.CUSTOM_GROUND)) {
                assertEquals(SettingsItem.TextKind.COLOR, colour(s, group, title).kind)
            }
        }
        assertEquals("#38bdf8", colour(s, CoreSettings.CUSTOM_DARK_TITLE, CoreSettings.CUSTOM_LIGHT_A).value())
        assertEquals("#eceef2", colour(s, CoreSettings.CUSTOM_LIGHT_TITLE, CoreSettings.CUSTOM_GROUND).value())
        assertTrue("grounds offer grounds", "#0a0b0f" in colour(s, CoreSettings.CUSTOM_DARK_TITLE, CoreSettings.CUSTOM_GROUND).suggestions())
        assertTrue("lights offer lights", "#38bdf8" in colour(s, CoreSettings.CUSTOM_LIGHT_TITLE, CoreSettings.CUSTOM_LIGHT_B).suggestions())
        assertFalse("#0a0b0f" in colour(s, CoreSettings.CUSTOM_LIGHT_TITLE, CoreSettings.CUSTOM_LIGHT_B).suggestions())
    }

    @Test fun changingAColorSavesItPicksCustomAndKeepsTextReadable() {
        prefs.overlayTheme = "aurora/dark"
        val s = section()
        var why: String? = "not called"
        // Typed without its # and in capitals: saved one way.
        SettingsChange.change(colour(s, CoreSettings.CUSTOM_LIGHT_TITLE, CoreSettings.CUSTOM_GROUND), "FFF7ED") { why = it }
        assertEquals(null, why)
        assertEquals("custom/light", prefs.overlayTheme)
        assertEquals(0xfffff7ed.toInt(), prefs.customTheme!!.light.ground)
        assertEquals("the dark one is untouched", Themes.GLASSWORKS.dark, prefs.customTheme!!.dark)
        assertEquals("#fff7ed", colour(section(), CoreSettings.CUSTOM_LIGHT_TITLE, CoreSettings.CUSTOM_GROUND).value())
        assertEquals(1, changed)
        val p = prefs.overlayPick.palette
        assertTrue(Themes.contrast(p.ink, p.ground) >= 4.5)
        // A light ground for the dark variant: the text turns dark to stay readable.
        SettingsChange.change(colour(section(), CoreSettings.CUSTOM_DARK_TITLE, CoreSettings.CUSTOM_GROUND), "#f5f5f4") { why = it }
        assertEquals("custom/dark", prefs.overlayTheme)
        assertTrue(Themes.contrast(prefs.overlayPick.palette.ink, 0xfff5f5f4.toInt()) >= 4.5)
    }

    @Test fun aColorThatIsntOneIsRefused() {
        val c = colour(section(), CoreSettings.CUSTOM_DARK_TITLE, CoreSettings.CUSTOM_LIGHT_A)
        for (bad in listOf("blue", "#12345", "#1234567", "#12345g", "")) {
            var why: String? = null
            SettingsChange.change(c, bad) { why = it }
            assertEquals(bad, "Use a color like #38bdf8.", why)
        }
        assertEquals(null, prefs.customTheme)
        assertEquals("glassworks/dark", prefs.overlayTheme)
    }

    @Test fun theManagePageShowsTheColorsAndChangesOneWithAColorPicker() {
        val s = section()
        val f = ManageFixture()
        f.statusFn = { f.status.copy(sections = listOf(s)) }
        val b = f.Browser().also { it.signInWithHa() }
        val page = b.get("/${CoreSettings.APPEARANCE}").text()
        assertTrue(page.contains(CoreSettings.CUSTOM_NOTE))
        assertTrue("each color row shows its color", page.contains("""<i class="dot" style="background:#38bdf8" aria-hidden="true"></i>#38bdf8"""))
        val key = "${CoreSettings.CUSTOM_DARK_TITLE}/${CoreSettings.CUSTOM_LIGHT_A}"
        val sheet = b.get("/${CoreSettings.APPEARANCE}?edit=${java.net.URLEncoder.encode(key, "UTF-8")}").text()
        assertTrue(sheet.contains("""<input type="color" name="value" value="#38bdf8" list="cpk-s""""))
        assertTrue("the panel's swatches, offered by the picker", sheet.contains("""<option value="#a78bfa"></option>"""))
        val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to CoreSettings.APPEARANCE, "key" to key, "value" to "#22d3ee"))
        assertEquals(303, r.status)
        assertEquals(0xff22d3ee.toInt(), prefs.customTheme!!.dark.a)
        assertEquals("custom/dark", prefs.overlayTheme)
    }

    @Test fun thePanelsPickerRedrawsWhenTheCustomColorsChangeElsewhere() {
        prefs.customTheme = Themes.CUSTOM_START
        prefs.overlayTheme = "custom/dark"
        val o = dev.glassworks.ui.Mockup.show(KioskSettingsOverlay(dev.glassworks.ui.Mockup.activity()))
        val radio = dev.glassworks.radio.radioDefinition(app, dev.glassworks.radio.FixedReadout(null, running = false))
        o.show(MenuFixture.model(app, radio, panes = listOf(radio)))
        o.previewSection(CoreSettings.APPEARANCE)
        fun picker(v: android.view.View = o): ThemePicker? = when (v) {
            is ThemePicker -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { picker(v.getChildAt(it)) }
            else -> null
        }
        val first = picker()!!
        o.refresh()
        assertTrue("nothing changed: the same picker", first === picker())
        // The page's scroll, the one the picker is in (the sidebar has its own).
        fun scroll(): android.widget.ScrollView? {
            var v: android.view.View? = picker()
            while (v != null && v !is android.widget.ScrollView) v = v.parent as? android.view.View
            return v as? android.widget.ScrollView
        }
        dev.glassworks.ui.Mockup.layout(o)
        scroll()!!.scrollTo(0, 200)
        val y = scroll()!!.scrollY
        assertTrue("the page scrolls", y > 0)
        // Manage changes the dark Custom accent: the pick is still custom/dark, but the Custom orb's colors changed.
        prefs.customTheme = Themes.custom(Themes.Palette(0xff22d3ee.toInt(), Themes.GLASSWORKS.dark.b, Themes.GLASSWORKS.dark.ground), Themes.GLASSWORKS.light)
        o.refresh()
        val next = picker()!!
        assertTrue("drawn again", first !== next)
        dev.glassworks.ui.Mockup.layout(o)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("where the person had scrolled it", y, scroll()!!.scrollY)
        assertEquals("custom/dark", prefs.overlayTheme)
    }

    // ---- #691: follow Home Assistant's dark mode

    private fun follow(s: SettingsSection) =
        SettingsChange.find(s, "${CoreSettings.THEME_TITLE}/${CoreSettings.FOLLOW_HA_DARK}") as SettingsItem.Switch

    @Test fun followingHomeAssistantsDarkModeKeepsTheThemeAndTakesItsVariant() {
        prefs.overlayTheme = "dune/light"
        val s = section()
        val f = follow(s)
        assertFalse("off by default", f.value())
        var why: String? = "not called"
        SettingsChange.change(f, "on") { why = it }
        assertEquals(null, why)
        assertTrue(prefs.followHaDark)
        assertEquals(1, changed)
        // Home Assistant hasn't said yet: the pick as saved.
        assertEquals("dune/light", prefs.overlayPick.id)
        prefs.haDark = true
        assertEquals("dune/dark", prefs.overlayPick.id)
        assertEquals("the picker rings what shows now", "dune/dark", picker(section()).value())
        assertEquals("the saved pick stays", "dune/light", prefs.overlayTheme)
        prefs.haDark = false
        assertEquals("dune/light", prefs.overlayPick.id)
        // Off again: the pick as saved, whatever HA says.
        prefs.haDark = true
        SettingsChange.change(f, "off") { why = it }
        assertEquals("dune/light", prefs.overlayPick.id)
    }

    @Test fun aCustomThemeFollowsToo() {
        val c = Themes.custom(Themes.Palette(0xffff5722.toInt(), 0xff00bcd4.toInt(), 0xff101418.toInt()), Themes.Palette(0xffbf360c.toInt(), 0xff006064.toInt(), 0xfff5f5f5.toInt()))
        prefs.customTheme = c
        prefs.overlayTheme = "custom/light"
        prefs.followHaDark = true
        prefs.haDark = true
        assertEquals(c.dark, prefs.overlayPick.palette)
    }

    @Test fun theSwitchIsUnderTheCardsOnManageToo() {
        val s = section()
        val f = ManageFixture()
        f.statusFn = { f.status.copy(sections = listOf(s)) }
        val b = f.Browser().also { it.signInWithHa() }
        val page = b.get("/${CoreSettings.APPEARANCE}").text()
        assertTrue(page.contains(CoreSettings.FOLLOW_HA_DARK))
        assertTrue("under the theme cards", page.indexOf(CoreSettings.FOLLOW_HA_DARK) > page.indexOf("""<button class="sw"""))
        val r = b.post("/settings", mapOf("csrf" to b.csrf(), "section" to CoreSettings.APPEARANCE, "key" to "${CoreSettings.THEME_TITLE}/${CoreSettings.FOLLOW_HA_DARK}", "value" to "on"))
        assertEquals(303, r.status)
        assertTrue(prefs.followHaDark)
    }

    @Test fun whatHomeAssistantSaidIsKeptAndAFactoryResetForgetsIt() {
        assertEquals(null, prefs.haDark)
        prefs.haDark = true
        assertEquals(true, KioskPrefs(app).haDark)
        prefs.followHaDark = true
        assertTrue(prefs.resetSettings())
        assertEquals(null, KioskPrefs(app).haDark)
        assertFalse(KioskPrefs(app).followHaDark)
    }

    // ---- #691: Home Assistant's theme

    private val haDarkColors = Themes.Palette(0xff03a9f4.toInt(), 0xffff9800.toInt(), 0xff111111.toInt())

    @Test fun homeAssistantsCardComesOnceTheDashboardHasShownItsColors() {
        assertFalse("not before", picker(section()).options().any { it.first == Themes.HA_PICK })
        var why: String? = null
        SettingsChange.change(picker(section()), Themes.HA_PICK) { why = it }
        assertTrue("can't be picked before", why != null)
        prefs.haColors = haDarkColors
        val c = picker(section())
        assertTrue(c.options().contains(Themes.HA_PICK to "Home Assistant"))
        val sw = c.swatch!!(Themes.HA_PICK)
        assertEquals("Home Assistant", sw.group)
        assertEquals(CoreSettings.HA_THEME_NOTE, sw.note)
        assertTrue("its badge is half sun, half moon", sw.auto)
        assertTrue("a dark ground: dark glass", sw.dark)
        assertEquals(1, c.options().count { c.swatch!!(it.first).group == "Home Assistant" })
    }

    @Test fun pickedTheOverlaysTakeHomeAssistantsColorsAndFollowThem() {
        prefs.haColors = haDarkColors
        var why: String? = "not called"
        SettingsChange.change(picker(section()), Themes.HA_PICK) { why = it }
        assertEquals(null, why)
        assertEquals(Themes.HA_PICK, prefs.overlayTheme)
        assertEquals(haDarkColors, prefs.overlayPick.palette)
        assertTrue(prefs.overlayPick.dark)
        assertEquals("Home Assistant", SettingsChange.shown(picker(section())))
        // The dashboard turns light: so do the overlays, without the switch.
        val light = Themes.Palette(0xff03a9f4.toInt(), 0xffff9800.toInt(), 0xfffafafa.toInt())
        prefs.haColors = light
        assertEquals(light, prefs.overlayPick.palette)
        assertFalse(prefs.overlayPick.dark)
        // The switch changes nothing for HA's own theme.
        prefs.followHaDark = true; prefs.haDark = true
        assertEquals(light, prefs.overlayPick.palette)
        assertEquals(Themes.HA_PICK, picker(section()).value())
    }

    @Test fun onManageTheHomeAssistantCardHasTheHalfBadge() {
        prefs.haColors = haDarkColors
        prefs.overlayTheme = Themes.HA_PICK
        val s = section()
        val f = ManageFixture()
        f.statusFn = { f.status.copy(sections = listOf(s)) }
        val b = f.Browser().also { it.signInWithHa() }
        val page = b.get("/${CoreSettings.APPEARANCE}").text()
        assertTrue(page.contains("""value="${Themes.HA_PICK}" aria-pressed="true""""))
        assertTrue(page.contains("""class="bd auto""""))
        assertTrue(page.contains("Home Assistant, light or dark as Home Assistant is"))
        assertTrue(page.contains(CoreSettings.HA_THEME_NOTE))
    }
}

