package dev.glassworks.s1.glowbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** THROWAWAY (test-build/glowbench-s1): the glow model runs every variant's whole timeline with finite light. */
class GlowModelTest {
    @Test fun everyFrameIsFinite() {
        for (v in GlowModel.Variant.entries) {
            val m = GlowModel(960f, 480f, v)
            val out = FloatArray(5)
            var t = 0f
            var lit = 0
            while (t < m.totalMs) {
                val f = m.frame(t)
                if (f != null) {
                    var s = 0f
                    while (s < m.p) {
                        f.eval(s, out)
                        for (x in out) assertTrue("$v t=$t s=$s", x.isFinite() && x >= 0f)
                        s += 4f
                    }
                    for (fl in f.flares) assertTrue(fl.col in 0..3)
                    lit++
                }
                t += 16.7f
            }
            assertTrue("$v draws", lit > 600)
        }
    }

    @Test fun phasesInOrder() {
        val m = GlowModel(960f, 480f, GlowModel.Variant.SPARK)
        assertEquals(GlowModel.Phase.ENTER, m.at(100f).phase)
        assertEquals(GlowModel.Phase.LISTENING, m.at(700f).phase)
        assertEquals(GlowModel.Phase.GAP, m.at(m.totalMs - 100f).phase)
    }
}
