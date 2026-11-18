package dev.glassworks.assist

import android.graphics.Bitmap
import android.util.Log
import dev.glassworks.ui.Bakery
import dev.glassworks.ui.Dither
import dev.glassworks.ui.FloatImage
import java.nio.ByteBuffer

/**
 * The glass orb's untinted layers, the sphere and its shine ([OrbArt.sphere], [OrbArt.shine]), baked
 * once and shared by every orb that uses them: the timer-done stage and the critical alert (#118).
 * The same in both, and about 1.2 MB at the S1's density, so baking them twice only cost memory and
 * time. The tinted layers (glow, core) stay with each orb.
 *
 * A bake that fails leaves nothing stuck: the orbs that waited get nothing (they stay empty), and the
 * next request bakes again.
 *
 * One per process, on the app's runtime; a test makes its own. Main thread: [layers] calls back there.
 */
class OrbBakes(
    /** Runs the work off the main thread and calls back on it: [Bakery] in the app. */
    private val bake: (work: () -> Outcome, done: (Outcome) -> Unit) -> Unit = { work, done -> Bakery.bake("orb sphere and shine", work, done) },
    /** Makes the layers; throws when it can't. */
    private val make: (OrbArt) -> Layers = { art ->
        val d = Dither(0x51ED270B)
        Layers(art.sphere().bitmap(d), art.shine().bitmap(d))
    },
) {
    class Layers(val sphere: Bitmap, val shine: Bitmap)

    /** What one bake gave: the layers, or null when making them failed. */
    class Outcome(val layers: Layers?)

    private var baked: Layers? = null
    private var baking = false
    private val waiting = ArrayList<(Layers) -> Unit>()

    /** Calls [done] with the layers: at once once baked, else when the one bake running finishes well. */
    fun layers(art: OrbArt, done: (Layers) -> Unit) {
        baked?.let { done(it); return }
        waiting += done
        if (baking) return
        baking = true
        try {
            // The work never throws: Bakery drops a failed bake without calling back, which would leave
            // baking set and the waiting orbs (and their views) held for good.
            bake({ Outcome(runCatching { make(art) }.onFailure { Log.w("S1Kiosk", "orb layers failed", it) }.getOrNull()) }) { finish(it.layers) }
        } catch (t: Throwable) {
            Log.w("S1Kiosk", "orb layers not baked", t)
            finish(null)
        }
    }

    private fun finish(layers: Layers?) {
        baking = false
        val each = waiting.toList()
        waiting.clear()
        if (layers == null) return
        baked = layers
        each.forEach { it(layers) }
    }

    companion object {
        fun FloatImage.bitmap(d: Dither): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .apply { copyPixelsFromBuffer(ByteBuffer.wrap(toRgbaBytes(d))) }
    }
}
