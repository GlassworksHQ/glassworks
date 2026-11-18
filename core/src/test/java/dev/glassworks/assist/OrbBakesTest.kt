package dev.glassworks.assist

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** OrbBakes (#118): the orb's sphere and shine, baked once for every orb that asks, and never stuck on a failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrbBakesTest {
    private val art = OrbArt(1f)
    private fun layers() = OrbBakes.Layers(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    /** The timer's orb and the critical alert's ask; one bake serves both, and a later ask gets the same bitmaps at once. */
    @Test fun oneBakeServesEveryOrb() {
        var bakes = 0
        var finish: (() -> Unit)? = null
        val baked = layers()
        val orbs = OrbBakes(bake = { work, done -> bakes++; finish = { done(work()) } }, make = { baked })
        val got = mutableListOf<OrbBakes.Layers>()
        orbs.layers(art) { got += it }       // the timer's orb asks
        orbs.layers(art) { got += it }       // the critical alert's asks while that bake runs
        finish!!()
        orbs.layers(art) { got += it }       // asked later: no second bake
        assertEquals(1, bakes)
        assertEquals(3, got.size)
        got.forEach { assertSame(baked, it) }
    }

    /** Verifier on #228: a bake that fails leaves nothing waiting, and the next ask bakes again. */
    @Test fun aFailedBakeIsntStuck() {
        var bakes = 0
        val baked = layers()
        val orbs = OrbBakes(bake = { work, done -> bakes++; done(work()) }, make = { if (bakes == 1) error("no memory") else baked })
        val first = mutableListOf<OrbBakes.Layers>()
        orbs.layers(art) { first += it }
        assertTrue("nothing for the one that waited on the failed bake", first.isEmpty())
        val second = mutableListOf<OrbBakes.Layers>()
        orbs.layers(art) { second += it }
        assertEquals("the next ask bakes again", 2, bakes)
        assertEquals(listOf(baked), second)
        assertTrue("the first ask's callback was dropped, not kept", first.isEmpty())
    }

    /** Verifier on #228: a bake that can't even start (it throws at once) doesn't wedge it either. */
    @Test fun aBakeThatThrowsAtOnceIsntStuck() {
        var tries = 0
        val baked = layers()
        val orbs = OrbBakes(bake = { work, done -> tries++; if (tries == 1) throw IllegalStateException("rejected") else done(work()) }, make = { baked })
        orbs.layers(art) { }
        val got = mutableListOf<OrbBakes.Layers>()
        orbs.layers(art) { got += it }
        assertEquals(2, tries)
        assertEquals(listOf(baked), got)
    }
}
