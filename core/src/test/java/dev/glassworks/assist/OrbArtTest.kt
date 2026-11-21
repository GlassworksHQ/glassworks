package dev.glassworks.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.glassworks.ui.Dither
import dev.glassworks.ui.FloatImage

/** Spot checks of the baked orb layers against the prototype's CSS numbers, at the panel's density 1.5. */
class OrbArtTest {

    private val art = OrbArt(1.5f)

    private fun FloatImage.centre() = (h / 2) * w + w / 2

    @Test
    fun glowsHaveTheirPeakAtTheCentreAndFadeOutInsideTheCircle() {
        val amber = art.glow(OrbArt.Tint.AMBER)
        assertEquals(450, amber.w)
        assertEquals(0.34f, amber.a[amber.centre()], 0.005f)
        // 62 % of the farthest corner (212 dp) = 131.5 dp: nothing left beyond it.
        val c = amber.w / 2
        assertEquals(0f, amber.a[c * amber.w + c + 200], 0f) // 133 dp right of the centre
        assertTrue(amber.a[c * amber.w + c + 90] in 0.05f..0.30f)
        for (t in listOf(OrbArt.Tint.SKY, OrbArt.Tint.GREEN, OrbArt.Tint.ROSE)) {
            val g = art.glow(t)
            assertEquals(0f, g.a[0], 0f) // corners are outside the 300 dp circle
            assertTrue(g.a[g.centre()] in 0.25f..0.29f)
        }
        val thinking = art.glow(null, thinking = true)
        assertTrue("two colours", thinking.b[thinking.centre()] > 0.1f && thinking.r[thinking.centre()] > 0.05f)
    }

    @Test
    fun coresAreBlurredDiscsInsideTheirCanvas() {
        val core = art.core(OrbArt.Tint.AMBER)
        assertEquals(art.px(OrbArt.CORE_DP), core.w)
        val mid = core.a[core.centre()]
        assertTrue("strong middle: $mid", mid > 0.6f && mid < 0.95f) // blur takes the very peak off
        assertEquals(0f, core.a[0], 0.002f) // the blur has died out at the canvas corner
        val bytes = core.toRgbaBytes(Dither())
        assertEquals(core.w * core.h * 4, bytes.size)
    }

    @Test
    fun swirlStillCoversTheSphereAndIsBlurred() {
        val sw = art.swirl()
        assertEquals(art.px(OrbArt.SWIRL_DP), sw.w)
        val c = sw.w / 2
        // Opaque-ish colour near the rim of the 55 dp sphere in the 70..150° band (right/lower-right).
        val r = (50 * 1.5f).toInt()
        val right = c * sw.w + c + r
        assertTrue(sw.a[right] > 0.4f)
        // Up (0°) is transparent in the gradient but the blur bleeds neighbours into it: not a hard cut.
        val up = (c - r) * sw.w + c
        assertTrue(sw.a[up] < sw.a[right])
    }

    @Test
    fun sphereHasBorderFillAndShadowBelow() {
        val s = art.sphere()
        val c = s.w / 2
        val border = c * s.w + c + (55.5f * 1.5f).toInt()
        assertTrue("border ${s.a[border]}", s.a[border] > 0.1f)
        assertEquals(0.04f, s.a[s.centre()], 0.01f)
        val below = (c + (66 * 1.5f).toInt()) * s.w + c
        val above = (c - (66 * 1.5f).toInt()) * s.w + c
        assertTrue("shadow offset down", s.a[below] > s.a[above])
        assertEquals(0f, s.r[below], 1e-6f) // black
    }

    /**
     * The swirl and the cores are cut to the sphere's inside when baked, not only by the view's round outline, which
     * software drawing ignores (#703's mockups showed the turning swirl as a square).
     */
    @Test
    fun theSwirlAndTheCoresHaveNothingOutsideTheSpheresInside() {
        fun outside(img: FloatImage): Float {
            val c = img.w / 2f; val r = OrbArt.INSIDE_DP / 2f * 1.5f
            var most = 0f
            for (y in 0 until img.h) for (x in 0 until img.w) {
                val dx = x + 0.5f - c; val dy = y + 0.5f - c
                if (Math.sqrt((dx * dx + dy * dy).toDouble()) > r + 1.0) most = maxOf(most, img.a[y * img.w + x])
            }
            return most
        }
        val swirl = art.swirl()
        assertEquals("the swirl outside the sphere's inside", 0f, outside(swirl), 0f)
        assertTrue("and still there inside it", swirl.a.maxOrNull()!! > 0.3f)
        for (t in OrbArt.Tint.entries) assertEquals("the $t core outside", 0f, outside(art.core(t)), 0f)
    }
}
