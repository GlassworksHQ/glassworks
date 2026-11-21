package dev.glassworks.timers

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.KioskPrefs
import dev.glassworks.TimerBoard
import dev.glassworks.TimerBoard.Entry
import dev.glassworks.TimerOverlay
import dev.glassworks.theme.Themes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #691: the timers take the overlays' theme, and follow it when it changes; their text reads in every theme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class TimerThemeTest {

    @After fun tearDown() = TimerBoard.reset()

    private fun overlay(): TimerOverlay {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(a)
        val t = TimerOverlay(a)
        root.addView(t, t.layoutParams())
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        shadowOf(Looper.getMainLooper()).idle()
        return t
    }

    private fun rings(t: TimerOverlay): List<RingView> {
        val out = ArrayList<RingView>()
        fun walk(v: android.view.View) {
            if (v is RingView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(t)
        return out
    }

    @Test fun theRingsTakeThePickedThemeAndFollowAChange() {
        val prefs = KioskPrefs(RuntimeEnvironment.getApplication())
        prefs.overlayTheme = "dusk/light"
        val t = overlay()
        TimerBoard.update(listOf(Entry("p", "Pasta", 600, 492, true), Entry("e", "Eggs", 420, 220, true)))
        t.refresh()
        assertEquals(2, rings(t).size)
        for (r in rings(t)) assertEquals(Themes.DUSK.light, r.look.palette)
        // The listener outlives a garbage collection (it was once held only weakly and went at the next one).
        repeat(3) { System.gc(); System.runFinalization() }
        // Picked again on the Appearance page (or on Manage): the rings follow.
        prefs.overlayTheme = "aurora/dark"
        shadowOf(Looper.getMainLooper()).idle()
        for (r in rings(t)) assertEquals(Themes.AURORA.dark, r.look.palette)
        // A ring that comes after the change is in it too.
        TimerBoard.update(listOf(Entry("p", "Pasta", 600, 492, true), Entry("e", "Eggs", 420, 220, true), Entry("r", "Rice", 900, 800, true)))
        t.refresh()
        assertEquals(3, rings(t).size)
        for (r in rings(t)) assertEquals(Themes.AURORA.dark, r.look.palette)
    }

    @Test fun theDefaultIsGlassworksDark() {
        KioskPrefs(RuntimeEnvironment.getApplication()).overlayTheme = "glassworks/dark"
        val t = overlay()
        TimerBoard.update(listOf(Entry("p", "Pasta", 600, 492, true)))
        t.refresh()
        assertEquals(Themes.GLASSWORKS.dark, rings(t).single().look.palette)
    }

    @Test fun aWhiteLightOnAGrayGroundStillShowsOnTheChipsWhiteGlass() {
        val l = TimerLook(Themes.Palette(0xffffffff.toInt(), 0xffdddddd.toInt(), 0xff808080.toInt()))
        assertTrue(l.light)
        assertTrue(Themes.contrast(l.glassAccent, l.glass) >= 3.0)
    }

    @Test fun aMidGrayGroundStillGivesTextThatReadsOnTheGlass() {
        // Dark glass (the ground is just on the dark side) with the ground's ink black: the glass's own text is light.
        for (g in listOf(0xff747676.toInt(), 0xff767676.toInt(), 0xff787878.toInt(), 0xff7a7a7a.toInt())) {
            val l = TimerLook(Themes.Palette(0xff38bdf8.toInt(), 0xffa78bfa.toInt(), g))
            assertTrue(Themes.hex(g), Themes.contrast(l.glassInk, l.glass) >= 4.5)
            assertTrue(Themes.hex(g), Themes.contrast(l.glassMu, l.glass) >= 4.5)
        }
    }

    @Test fun theTextReadsAndTheLastSecondsStayAmberInEveryTheme() {
        val custom = Themes.custom(
            Themes.Palette(0xfffde68a.toInt(), 0xfffef3c7.toInt(), 0xfffffbeb.toInt()), // pale lights on a pale ground
            Themes.Palette(0xff1e293b.toInt(), 0xff334155.toInt(), 0xff0f172a.toInt()), // dark lights on a dark ground
        )
        for (theme in Themes.ALL + custom) for (p in listOf(theme.dark, theme.light)) {
            val l = TimerLook(p)
            val name = "${theme.name} ${Themes.hex(p.ground)}"
            assertTrue(name, Themes.contrast(l.ink, p.ground) >= 4.5)
            assertTrue("$name: the last seconds' text", Themes.contrast(l.hotText, p.ground) >= 4.5)
            assertTrue("$name: the last seconds' digits, on the glass", Themes.contrast(l.hotDigits, l.glass) >= 3.0)
            assertTrue("$name: the digits and the chip's label, on the glass", Themes.contrast(l.glassInk, l.glass) >= 4.5)
            assertTrue("$name: paused digits, on the glass", Themes.contrast(l.glassMu, l.glass) >= 4.5)
            assertTrue("$name: the drag guide's target", Themes.contrast(l.accent, p.ground) >= 3.0)
            assertTrue("$name: the chip's arrow and the peek's arc, on the glass", Themes.contrast(l.glassAccent, l.glass) >= 3.0)
            assertEquals("$name: amber is a status color", TimerLook.AMBER, l.hotRing)
            assertEquals(!p.darkGround, l.light)
        }
    }
}
