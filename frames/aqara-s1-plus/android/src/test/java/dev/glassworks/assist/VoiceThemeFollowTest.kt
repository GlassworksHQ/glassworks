package dev.glassworks.assist

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.glassworks.KioskPrefs
import dev.glassworks.VoiceOverlay
import dev.glassworks.theme.Themes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #691: the voice overlay follows a theme picked while it is attached, also after a garbage collection. Its listener
 * was once registered through a throwaway settings object, which held the only strong reference to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class VoiceThemeFollowTest {

    private fun palette(v: VoiceOverlay): Themes.Palette =
        VoiceOverlay::class.java.getDeclaredField("palette").apply { isAccessible = true }.get(v) as Themes.Palette

    @Test fun aThemePickedLaterIsTakenEvenAfterAGarbageCollection() {
        val prefs = KioskPrefs(RuntimeEnvironment.getApplication())
        prefs.overlayTheme = "dusk/light"
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(a)
        val v = VoiceOverlay(a)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Themes.DUSK.light, palette(v))
        repeat(3) { System.gc(); System.runFinalization() }
        prefs.overlayTheme = "aurora/dark"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Themes.AURORA.dark, palette(v))
    }
}
