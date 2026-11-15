package sh.edm.s1kiosk.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/** A pane's page in the approved layout (2026-11-15), at the S1's size: cards of rows, values on the right. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanePageLayoutTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    private val page = SettingsSection("assist", "Voice", listOf(
        SettingsItem.Status({ "Listening" }, ok = { true }),
        SettingsItem.Group("On the panel", listOf(
            SettingsItem.Choice("Wake word", { "okay_nabu" }, { listOf("okay_nabu" to "Okay Nabu") }) { _, d -> d(null) },
            SettingsItem.Switch("Sound when listening", "A short chime", { true }) { _, d -> d(null) },
        )),
        SettingsItem.States("In Home Assistant") { listOf(SettingsItem.StateRow("Assist in progress", "Off")) },
    ), master = SettingsItem.Switch("Voice", "", { true }) { _, d -> d(null) })

    private fun open(): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        o.show(KioskSettingsOverlay.Model(listOf(page, SettingsSection("other", "Other", listOf(SettingsItem.Note { "x" }))), "0.4.3", setOf("assist", "other")))
        Mockup.idle()
        Mockup.layout(o)
        return o
    }

    private fun x(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[0]

    /** The card a label sits in: its nearest parent with a background. */
    private fun cardOf(v: View): ViewGroup = generateSequence(v.parent as View) { it.parent as? View }.first { it.background != null } as ViewGroup

    @Test
    fun aRowsValueSitsOnItsRight() {
        val o = open()
        val name = Mockup.label(o, "Wake word").let { l -> generateSequence(l as View) { it.parent as? View }.first { it.isClickable } as ViewGroup }
        val value = Mockup.label(o, "Okay Nabu")
        assertEquals("on the row itself", name, value.parent)
        assertTrue("right of its name", x(value) > x(name.getChildAt(0)) + name.getChildAt(0).width)
        assertTrue("at least 52 dp: ${name.height}", name.height >= (52 * o.resources.displayMetrics.density).toInt())
    }

    @Test
    fun eachGroupAndInHomeAssistantIsACardOfItsOwn() {
        val o = open()
        val group = cardOf(Mockup.label(o, "Sound when listening"))
        val ha = cardOf(Mockup.label(o, "Assist in progress"))
        assertTrue("two cards", group !== ha)
        assertTrue(Mockup.label(o, "In Home Assistant").let { generateSequence(it as View) { v -> v.parent as? View }.any { v -> v === ha } })
        // The status line sits between the head and the cards, in no card.
        assertTrue(generateSequence(Mockup.label(o, "Listening").parent as View) { it.parent as? View }.takeWhile { it !== o }.none { it.background != null })
    }

    @Test
    fun aCardHasTheSamePaddingOnEverySideAndNoLineAboveItsFirstRow() {
        val o = open()
        o.previewSection("other")
        Mockup.idle(); Mockup.layout(o)
        val card = cardOf(Mockup.label(o, "x"))
        assertEquals("the Note is the card's first child: no hairline above it", Mockup.label(o, "x"), card.getChildAt(0))
        assertEquals(card.paddingTop, card.paddingBottom)
        assertEquals(card.paddingLeft, card.paddingRight)
    }
}
