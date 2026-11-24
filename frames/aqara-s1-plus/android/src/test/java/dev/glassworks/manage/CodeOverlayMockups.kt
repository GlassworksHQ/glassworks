package dev.glassworks.manage

import android.app.Application
import dev.glassworks.manage.PanelCode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.glassworks.ui.Mockup

/**
 * The Manage page's code on the panel's screen (#33), at 1440x720. Illustrative code only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*CodeOverlayMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeOverlayMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val a = Mockup.activity()
        Mockup.save("manage-code-sign-in", Mockup.show(CodeOverlay.build(a, PanelCode.Shown("482913", "signin", "Sign in to manage this panel", 0), {})))
        Mockup.save("manage-code-reboot", Mockup.show(CodeOverlay.build(a, PanelCode.Shown("482913", "reboot", "Reboot the panel", 0), {})))
        // A frame without touch (Input.NONE): no Close; the code goes when used or run out (#34).
        Mockup.save("setup-lan-code-no-touch", Mockup.show(CodeOverlay.build(a,
            PanelCode.Shown("482913", dev.glassworks.setup.LanSetup.PURPOSE, dev.glassworks.setup.LanSetup.LABEL, 0), {}, touch = false)))
        // Setup over the LAN (#34): the same overlay, for pairing a laptop.
        Mockup.save("setup-lan-code", Mockup.show(CodeOverlay.build(a,
            PanelCode.Shown("482913", dev.glassworks.setup.LanSetup.PURPOSE, dev.glassworks.setup.LanSetup.LABEL, 0), {})))
        // Over the night clock, as on the panel: nothing behind may show through.
        val behind = android.widget.FrameLayout(a).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(android.widget.TextView(a).apply {
                text = "1:22"; textSize = 160f; setTextColor(android.graphics.Color.parseColor("#F4F4F5"))
                gravity = android.view.Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(CodeOverlay.build(a, PanelCode.Shown("122202", "signin", "Sign in to manage this panel", 0), {}),
                android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        Mockup.save("manage-code-over-clock", Mockup.show(behind))
    }

    /** On a phone held upright (360 dp): the code shrinks to stay on one line, the words keep off the edges. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun phone() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val a = Mockup.activity()
        Mockup.save("phone-manage-code-sign-in", Mockup.show(CodeOverlay.build(a, PanelCode.Shown("482913", "signin", "Sign in to manage this panel", 0), {})), 720, 1280)
    }

    /** The code stays on one line on a phone, and inside the screen. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun theCodeFitsAPhone() {
        val a = Mockup.activity()
        val v = Mockup.show(CodeOverlay.build(a, PanelCode.Shown("482913", "signin", "Sign in to manage this panel", 0), {}))
        Mockup.layout(v, 720, 1280); Mockup.layout(v, 720, 1280)
        val code = Mockup.label(v, "482  913")
        assertTrue("one line", code.lineCount == 1)
        val r = android.graphics.Rect().also { code.getGlobalVisibleRect(it) }
        assertTrue("inside the screen: $r", r.left >= 0 && r.right <= 720 && r.width() == code.width)
    }

    /** Without touch there's nothing to tap: no Close (#34). With touch, Close closes it. */
    @Test
    fun closeOnlyWhereTheScreenTakesATap() {
        fun texts(v: android.view.View): List<String> = when (v) {
            is android.widget.TextView -> listOf(v.text.toString())
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
            else -> emptyList()
        }
        val shown = PanelCode.Shown("482913", "setup", "Set up this panel from a computer", 0)
        var closed = 0
        val touch = CodeOverlay.build(Mockup.activity(), shown, { closed++ })
        assertTrue("Close" in texts(touch))
        val none = CodeOverlay.build(Mockup.activity(), shown, { closed++ }, touch = false)
        assertFalse("Close" in texts(none))
        assertTrue("the code still shows", "482  913" in texts(none))
    }

    /** The overlay hides what's behind it completely (the night clock showed through, panel run 2026-11-13). */
    @Test
    fun theOverlayIsOpaque() {
        val v = CodeOverlay.build(Mockup.activity(), PanelCode.Shown("482913", "signin", "Sign in", 0), {})
        val bg = v.background as android.graphics.drawable.ColorDrawable
        org.junit.Assert.assertEquals(0xFF0A0B0F.toInt(), bg.color)
    }
}
