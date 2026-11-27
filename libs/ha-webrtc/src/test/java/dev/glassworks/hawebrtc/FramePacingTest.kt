package dev.glassworks.hawebrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paced TextureView players: their frames drawn together on one display frame, capped, and a lone one left alone. */
class FramePacingTest {
    private class Frame(val camera: Int, val n: Int) {
        var refs = 1
    }

    private val drawn = ArrayList<Pair<Long, Frame>>()
    private val pacing = FramePacing<Frame>(30, { it.refs++ }, { it.refs-- })
    private var now = 0L
    private val vsync = FramePacing.DEFAULT_VSYNC_NS

    private fun client() = pacing.register { f -> drawn += now to f }

    /** The caller's side of a frame: drawn at once when [FramePacing.offer] says so, its own reference released. */
    private fun frame(c: FramePacing<Frame>.Client, f: Frame, immediate: Boolean = false) {
        if (!pacing.offer(c, f, now, immediate)) drawn += now to f
        f.refs--
    }

    /**
     * [ms] of playback in 1 ms steps: camera `i` sends a frame every `periodsMs[i]`, from `phasesMs[i]`; each display
     * frame ticks. Returns how many display frames drew something.
     */
    private fun play(ms: Int, periodsMs: List<Double>, phasesMs: List<Double>): Int {
        val clients = periodsMs.map { client() }
        val next = phasesMs.toMutableList()
        val counts = IntArray(periodsMs.size)
        var nextVsync = 0L
        var redraws = 0
        var counted = drawn.size
        val start = now
        while (now - start < ms * 1_000_000L) {
            for (i in clients.indices) {
                if ((now - start) / 1e6 >= next[i]) {
                    frame(clients[i], Frame(i, counts[i]++))
                    next[i] += periodsMs[i]
                }
            }
            if (now >= nextVsync) {
                pacing.tick(now)
                nextVsync += vsync
                // A display frame that shows any camera's new frame (drawn at once since the last one, or on this
                // tick) is one window redraw.
                if (drawn.size > counted) redraws++
                counted = drawn.size
            }
            now += 1_000_000L
        }
        return redraws
    }

    @Test
    fun `one camera alone is drawn as each frame comes, never held for a tick`() {
        val c = client()
        repeat(10) { i ->
            frame(c, Frame(0, i))
            assertEquals("frame $i drawn at once", i + 1, drawn.size)
            now += 40_000_000L
            assertFalse(pacing.hasPending())
        }
    }

    @Test
    fun `four 25 fps cameras on their own clocks redraw at most 30 times a second, every frame shown`() {
        val redraws = play(2_000, listOf(40.0, 40.0, 40.0, 40.0), listOf(0.0, 7.0, 19.0, 31.0))
        // Unpaced, nearly every display frame (about 53 a second) would carry one camera's frame.
        assertTrue("redraws a second: ${redraws / 2}", redraws / 2 <= 31)
        // 25 fps under a 30 Hz tick: next to none dropped (only while the second camera's first frames come).
        assertTrue("dropped ${pacing.dropped}", pacing.dropped <= 4)
        for (cam in 0..3) {
            val shown = drawn.filter { it.second.camera == cam }.map { it.second.n }
            assertEquals("camera $cam frames in order", shown.sorted(), shown)
        }
    }

    @Test
    fun `slower cameras get slower ticks, and frames waiting on one tick are drawn together`() {
        play(3_000, listOf(66.7, 66.7), listOf(0.0, 23.0))
        // 15 fps with headroom: every 3rd display frame (20 a second), not 30.
        assertEquals(3, pacing.framesPerTick(now))
        val times = drawn.filter { it.first > 1_000_000_000L }.map { it.first }.toSet()
        assertTrue("ticks a second: ${times.size / 2}", times.size / 2 <= 21)
    }

    @Test
    fun `the cap holds for cameras faster than it, the newest frame winning`() {
        val a = client()
        val b = client()
        // Both playing.
        frame(a, Frame(0, 0), immediate = true)
        frame(b, Frame(1, 0), immediate = true)
        drawn.clear()
        now += 1_000_000L
        val f1 = Frame(0, 1)
        val f2 = Frame(0, 2)
        frame(a, f1)
        frame(a, f2)
        assertEquals("a replaced frame is released", 0, f1.refs)
        assertEquals(1, pacing.dropped)
        pacing.tick(now)
        assertEquals(listOf(2), drawn.map { it.second.n })
        assertEquals("drawn, then released", 0, f2.refs)
        // 60 fps cameras: capped at every 2nd display frame.
        assertEquals(2, pacing.framesPerTick(now))
    }

    @Test
    fun `a player that goes releases what it had waiting, and is drawn at once if offered again`() {
        val a = client()
        val b = client()
        frame(a, Frame(0, 0), immediate = true)
        frame(b, Frame(1, 0), immediate = true)
        val waiting = Frame(0, 1)
        frame(a, waiting)
        assertTrue(pacing.hasPending())
        pacing.unregister(a)
        assertEquals(0, waiting.refs)
        assertFalse(pacing.hasPending())
        assertFalse(pacing.offer(a, Frame(0, 2), now))
    }

    @Test
    fun `ticks stop once nothing has played for a while`() {
        val a = client()
        val b = client()
        frame(a, Frame(0, 0), immediate = true)
        frame(b, Frame(1, 0), immediate = true)
        assertTrue(pacing.tick(now))
        now += FramePacing.ACTIVE_NS
        assertFalse(pacing.tick(now))
    }

    @Test
    fun `the display frame's length is measured from the ticks`() {
        var t = 0L
        repeat(200) {
            pacing.tick(t)
            t += 13_888_889L // 72 Hz
        }
        assertEquals(13_888_889.0, pacing.vsyncNs.toDouble(), 200_000.0)
    }
}
