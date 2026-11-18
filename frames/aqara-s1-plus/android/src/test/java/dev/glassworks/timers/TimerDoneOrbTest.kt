package dev.glassworks.timers

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import dev.glassworks.ui.Mockup
import dev.glassworks.assist.OrbBakes
import java.time.Duration

/** The timer-done stage takes its orb's sphere and shine from the shared OrbBakes (#118, verifier on #228). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class TimerDoneOrbTest {
    @Test fun itsSphereAndShineAreTheSharedOnes() {
        val shared = OrbBakes.Layers(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        val orbs = OrbBakes(bake = { work, done -> done(work()) }, make = { shared })
        val root = Mockup.show(FrameLayout(Mockup.activity()))
        val view = TimerDoneView(root.context, orbs)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_100))   // it bakes 4 s after attach
        fun layer(name: String) = (TimerDoneView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) as ImageView)
            .drawable.let { (it as BitmapDrawable).bitmap }
        assertSame(shared.sphere, layer("sphere"))
        assertSame(shared.shine, layer("shine"))
    }
}
