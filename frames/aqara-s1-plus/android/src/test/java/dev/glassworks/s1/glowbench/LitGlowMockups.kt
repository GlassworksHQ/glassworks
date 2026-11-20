package dev.glassworks.s1.glowbench

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** THROWAWAY (test-build/glowbench-s1): LitGlow's layout at the S1's 1440x720, at a few moments. Skipped unless asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LitGlowMockups {
    @Test fun mockups() {
        val dir = System.getProperty("s1.mockupDir")
        assumeTrue(dir != null)
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(a).apply { setBackgroundColor(Color.rgb(17, 19, 24)) }
        val g = LitGlow(a)
        root.addView(g, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root)
        fun layout() { root.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1440, 720) }
        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun save(name: String) {
            layout()
            val b = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888); root.draw(Canvas(b))
            File(dir!!).mkdirs(); File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        layout(); idle(50)
        g.enter(); idle(300); save("lit-1-enter-300ms")
        idle(400); save("lit-2-enter-700ms")
        g.listening(); g.level(.8f); g.ripple(.8f); idle(500); save("lit-3-listening")
        g.thinking(0); idle(600); save("lit-4-thinking")
        g.speakingStart(); g.speaking(.4f); idle(400); save("lit-5-speaking")
        g.exit(); idle(200); save("lit-6-exit-200ms")
        g.reset(); g.visibility = View.VISIBLE; g.enter(); idle(700); g.error(); idle(300); save("lit-7-error")
    }
}
