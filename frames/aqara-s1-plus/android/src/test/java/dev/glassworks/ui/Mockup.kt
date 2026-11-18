package dev.glassworks.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.testing.RepoFiles
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * What the `*Mockups` classes share: the real views drawn by Robolectric's native graphics at the
 * panel's 1440x720 px, written as PNGs to `-Ps1MockupDir=<dir>`.
 */
object Mockup {
    const val W = 1440
    const val H = 720

    val dir: String? get() = System.getProperty("s1.mockupDir")

    /** Robolectric runs without the app's resources: read Inter from the source tree, as the app does from res/font. */
    fun interFromSources() {
        val font = RepoFiles.inModule("src/main/res/font/inter.ttf")
        Inter.load = { font.readBytes() }
    }

    fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    fun <V : View> show(v: V): V {
        activity().setContentView(v, ViewGroup.LayoutParams(-1, -1))
        return v
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    /** Lays [v] out at [w] by [h] px: the S1's 1440x720 unless told (the Pixel Tablet's 1280x800 at mdpi). */
    fun layout(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    fun save(name: String, v: View, w: Int = W, h: Int = H) {
        layout(v, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val out = File(dir!!).apply { mkdirs() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The smallest text view under [root] that says exactly [text]: a section's label rather than its nav row. */
    fun label(root: View, text: String): TextView =
        views(root).filterIsInstance<TextView>().filter { it.text.toString() == text }.minBy { it.textSize }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }
}
