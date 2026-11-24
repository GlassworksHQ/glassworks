package dev.glassworks.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import dev.glassworks.radio.FixedReadout
import dev.glassworks.radio.radioDefinition
import dev.glassworks.ui.Mockup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings menu on a phone held upright (360 dp wide): no room for the sidebar beside a page, so the list fills
 * the screen, a row opens its page across it under "‹ Settings" and Done, and Back goes from the page to the list
 * before it closes the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
class SettingsPhoneTest {

    private fun open(): KioskSettingsOverlay {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(MenuFixture.model(a, radioDefinition(a, FixedReadout(null, running = true))))
        Mockup.idle()
        lay(o)
        return o
    }

    /** Laid out by the activity's window, at the screen's size less its bars. */
    private fun lay(o: View) { o.requestLayout(); Mockup.idle() }

    private fun shown(o: View, text: String) = runCatching { Mockup.label(o, text).isShown }.getOrDefault(false)

    /** The sidebar's row called [name]. */
    private fun row(o: View, name: String): View {
        var v: View = Mockup.label(o, name)
        while (!v.isClickable) v = v.parent as View
        return v
    }

    @Test fun opensOnTheList() {
        val o = open()
        assertTrue(shown(o, "Screen"))
        assertFalse("no page beside it", o.pageShown)
        assertFalse(shown(o, "‹  Settings"))
    }

    @Test fun aRowOpensItsPageAndBackGoesToTheList() {
        val o = open()
        row(o, "Screen").performClick()
        Mockup.idle(); lay(o)
        assertTrue(o.pageShown)
        assertTrue(shown(o, "‹  Settings"))
        assertTrue("the page's rows", shown(o, "Turn off when idle"))
        val page = generateSequence(Mockup.label(o, "Turn off when idle") as View) { it.parent as? View }.first { it is android.widget.ScrollView }
        assertTrue("the page takes the screen's width: ${page.width} of ${o.width}", page.width == o.width)
        o.back()
        Mockup.idle(); lay(o)
        assertTrue("Back: the list", o.isShowing)
        assertFalse(o.pageShown)
        assertTrue(shown(o, "Screen"))
        o.back()
        assertFalse("Back again: closed", o.isShowing)
    }

    @Test fun theBarsSettingsGoesBackAndItsDoneCloses() {
        val o = open()
        row(o, "Screen").performClick()
        Mockup.idle(); lay(o)
        Mockup.label(o, "‹  Settings").performClick()
        Mockup.idle(); lay(o)
        assertFalse(o.pageShown)
        // The Overview is on screen behind the list, so its row opens it again, as any other row does.
        row(o, "Screen").performClick()
        Mockup.idle(); lay(o)
        assertTrue(o.pageShown)
        views(o).filterIsInstance<android.widget.TextView>().first { it.isShown && it.text.toString() == "Done" }.performClick()
        Mockup.idle()
        assertFalse(o.isShowing)
    }

    @Test fun openedAgainItStartsOnTheList() {
        val o = open()
        row(o, "Screen").performClick()
        Mockup.idle(); lay(o)
        o.hide(); Mockup.idle()
        o.show(MenuFixture.model(o.context, radioDefinition(o.context, FixedReadout(null, running = true))))
        Mockup.idle(); lay(o)
        assertFalse(o.pageShown)
        assertTrue("the list on screen", row(o, "Screen").isShown)
        assertFalse("no page bar", shown(o, "‹  Settings"))
    }

    /** A sheet opened on its side (a 560 dp card) keeps its card inside the screen once the phone is turned upright. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun aSheetFollowsTheWidthItHas() {
        val o = open()
        o.previewSection("screen")
        Mockup.idle(); lay(o)
        row(o, "Turn off when idle").performClick()
        Mockup.idle()
        val card = generateSequence(Mockup.label(o, "Cancel") as View) { it.parent as? View }.first { (it.parent as? View)?.isClickable == true && it.isClickable }
        Mockup.layout(o, 720, 1280)
        val r = android.graphics.Rect().also { card.getDrawingRect(it); (o as ViewGroup).offsetDescendantRectToMyCoords(card, it) }
        assertTrue("upright, the card is ${card.width} px at ${r.left}..${r.right} of 720", r.left >= 0 && r.right <= 720)
    }

    /** The S1's 960 dp: the sidebar and the page side by side, no bar. */
    @Test
    @Config(qualifiers = "w960dp-h480dp-land-hdpi")
    fun aPanelShowsBoth() {
        val a = Mockup.activity()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(MenuFixture.model(a, radioDefinition(a, FixedReadout(null, running = true))))
        o.previewSection("screen")
        Mockup.idle(); lay(o)
        assertTrue(o.pageShown)
        assertTrue(shown(o, "Turn off when idle"))
        assertTrue("the sidebar beside it", row(o, "About").isShown)
        assertFalse(shown(o, "‹  Settings"))
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }
}
