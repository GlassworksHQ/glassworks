package dev.glassworks.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.ui.Glass
import dev.glassworks.ui.Mockup

/**
 * Panes' states at the panel's size: the sidebar's dots and the Overview's chips, with real text measuring (native graphics, Inter), so a
 * cut word shows: the legacy graphics mode measures text wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RailChipsTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private fun open(model: (android.content.Context) -> KioskSettingsOverlay.Model): KioskSettingsOverlay {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(model(a))
        Mockup.idle()
        return o
    }

    /**
     * #253 verifiers: the sidebar reads a pane's state from paneState, as the Manage page's nav. Off wins over
     * can't run: such a pane folds under "Off" and isn't greyed; a pane that is on but can't run is greyed
     * with its dot.
     */
    @Test
    fun offWinsOverCantRunAndCantRunGreys() {
        fun master(on: Boolean) = SettingsItem.Switch("x", "", { on }) { _, d -> d(null) }
        val o = open { _ ->
            KioskSettingsOverlay.Model(listOf(
                SettingsSection("radio", "Radio", listOf(SettingsItem.Note { "" }), master = master(true)),
                SettingsSection("wall-base", "Wall base", listOf(SettingsItem.Note { "" }), master = master(true), unavailable = { "no base port" }),
                SettingsSection("bluetooth-proxy", "Bluetooth proxy", listOf(SettingsItem.Note { "" }), master = master(false), unavailable = { "it has no Bluetooth LE" }),
            ), "0.4.3", setOf("radio", "wall-base", "bluetooth-proxy"))
        }
        fun railName(title: String) = generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filter { it.contentDescription == title && it is ViewGroup }.map { (it as ViewGroup).getChildAt(0) }
            .filterIsInstance<android.widget.TextView>().single()
        fun chips() = generateSequence(listOf<View>(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }
            .flatten().filter { it.contentDescription?.startsWith("status ") == true && it.isShown }.map { it.contentDescription.toString() }.toList()
        o.previewSection("radio")
        Mockup.idle()
        assertTrue("folded under Off", runCatching { Mockup.label(o, "Off · 1") }.isSuccess)
        assertEquals("on but can't run: greyed", Glass.FA, railName("Wall base").currentTextColor)
        assertEquals(listOf("status On", "status Can't run"), chips())
        // Picked, the off pane opens its fold: drawn as picked, not greyed, with the Off chip.
        o.previewSection("bluetooth-proxy")
        Mockup.idle()
        assertEquals("off and can't run: not greyed", Glass.TX, railName("Bluetooth proxy").currentTextColor)
        assertTrue("status Off" in chips())
    }

    /** The menu on its Overview, with [panes] as the Manage page's status has them. */
    private fun overview(vararg panes: dev.glassworks.manage.PaneRow): KioskSettingsOverlay {
        val f = dev.glassworks.manage.ManageFixture()
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a)
        o.overview = KioskSettingsOverlay.Overview({ f.status.copy(panes = panes.toList()) }, f.actions)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(KioskSettingsOverlay.Model(emptyList(), "0.4.3"))
        Mockup.layout(o)
        return o
    }

    private fun views(o: View) = generateSequence(listOf(o)) { l -> l.flatMap { v -> (v as? ViewGroup)?.let { g -> List(g.childCount) { g.getChildAt(it) } }.orEmpty() }.ifEmpty { null } }.flatten()

    @Test
    fun everyChipWordIsDrawnWhole() {
        // #235 verifier: "Bluetooth proxy" + "Can't run" drew "Can", "Problem" "Pro". On the Overview's Panes card now.
        fun row(id: String, title: String, kind: dev.glassworks.pane.PaneState.Kind) =
            dev.glassworks.manage.PaneRow(id, title, dev.glassworks.pane.PaneState(kind, "A line under the pane's name that runs on for a while"), id)
        val o = overview(
            row("bluetooth-proxy", "Bluetooth proxy", dev.glassworks.pane.PaneState.Kind.CANT_RUN),
            row("diagnostics", "Bluetooth proxy two", dev.glassworks.pane.PaneState.Kind.PROBLEM),
            row("radio", "Radio", dev.glassworks.pane.PaneState.Kind.RUNNING),
        )
        val chips = views(o).filter { it.contentDescription?.startsWith("status ") == true }.toList()
        assertEquals(listOf("status Can't run", "status Problem", "status Running"), chips.map { it.contentDescription.toString() })
        for (chip in chips) {
            val word = (chip as ViewGroup).getChildAt(1) as android.widget.TextView
            val needs = word.paint.measureText(word.text.toString())
            assertTrue("${word.text}: ${word.width} px for $needs", word.width - word.paddingLeft - word.paddingRight >= kotlin.math.ceil(needs).toInt())
            val row = chip.parent as View
            assertTrue("the chip stays inside its row", chip.right <= row.width)
        }
    }

    @Test
    fun aWordLongerThanTheBackstopShortensAndTheRowStaysInside() {
        // #249 verifier: nothing pinned the backstop. A word wider than it ellipsizes; the name keeps room; the row stays in the card.
        val long = dev.glassworks.pane.PaneState(dev.glassworks.pane.PaneState.Kind.RUNNING, "", chip = LONG_WORD)
        val o = overview(dev.glassworks.manage.PaneRow("radio", "Radio", long, "radio"))
        val chip = views(o).single { it.contentDescription == "status $LONG_WORD" } as ViewGroup
        val word = chip.getChildAt(1) as android.widget.TextView
        val density = o.resources.displayMetrics.density
        assertTrue("wider than the backstop", word.paint.measureText(LONG_WORD) > Glass.CHIP_WORD_MAX_DP * density)
        val name = (chip.parent as ViewGroup).getChildAt(0)
        assertTrue("the name keeps room: ${name.width / density} dp", name.width >= 60 * density)
        assertTrue("shortened with an ellipsis", (word.layout?.getEllipsisCount(0) ?: 0) > 0)
        val row = chip.parent as View
        assertTrue("the chip stays inside its row", chip.right <= row.width)
    }

    private companion object {
        /** No chip says this today: a word longer than the backstop (CHIP_WORD_MAX_DP). */
        const val LONG_WORD = "Waiting for Bluetooth to turn on"
    }
}
