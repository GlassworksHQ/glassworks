package dev.glassworks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start-up work waits for the first dashboard frame, a short settle after it, or the timeout; never longer. */
class AfterFirstFrameTest {
    /** A clock and a queue of delayed tasks, run by hand. */
    private var clock = 0L
    private val pending = ArrayList<Pair<Long, () -> Unit>>()
    private val ran = ArrayList<String>()
    private val warned = ArrayList<String>()

    private fun gate(timeoutMs: Long = 10_000) = AfterFirstFrame(
        schedule = { delay, task -> pending += (clock + delay) to task },
        warn = { msg, _ -> warned += msg },
        now = { clock },
        settleMs = 250,
        timeoutMs = timeoutMs,
    )

    /** Moves the clock to [t], running every task due by then in time order. */
    private fun advanceTo(t: Long) {
        while (true) {
            val next = pending.filter { it.first <= t }.minByOrNull { it.first } ?: break
            pending.remove(next)
            clock = next.first
            next.second()
        }
        clock = t
    }

    @Test
    fun `work waits for the first frame and its settle, then runs in the order it was asked for`() {
        val g = gate()
        g.run("device") { ran += "device" }
        g.run("discovery") { ran += "discovery" }
        advanceTo(3_000)
        assertEquals(emptyList<String>(), ran)
        assertFalse(g.passed)
        g.reached("first paint")
        assertTrue(g.passed)
        advanceTo(3_249)
        assertEquals("nothing before the settle", emptyList<String>(), ran)
        advanceTo(3_250)
        assertEquals(listOf("device", "discovery"), ran)
    }

    @Test
    fun `only the first reach counts`() {
        val g = gate()
        g.run("device") { ran += "device" }
        g.reached("first paint")
        advanceTo(100)
        g.reached("page loaded")
        advanceTo(250)
        assertEquals(listOf("device"), ran)
        advanceTo(1_000)
        assertEquals(listOf("device"), ran)
    }

    @Test
    fun `work asked for after the frame runs at once, posted`() {
        val g = gate()
        g.reached("setup screen")
        advanceTo(250)
        g.run("device") { ran += "device" }
        assertEquals("posted, not run inline", emptyList<String>(), ran)
        advanceTo(250)
        assertEquals(listOf("device"), ran)
    }

    @Test
    fun `asked for between the frame and the settle, it runs with the others`() {
        val g = gate()
        g.run("a") { ran += "a" }
        g.reached("first paint")
        advanceTo(100)
        g.run("b") { ran += "b" }
        advanceTo(249)
        assertEquals(emptyList<String>(), ran)
        advanceTo(250)
        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun `without a frame the timeout starts the work`() {
        val g = gate(timeoutMs = 10_000)
        g.run("device") { ran += "device" }
        advanceTo(9_999)
        assertEquals(emptyList<String>(), ran)
        advanceTo(10_000)
        assertTrue(g.passed)
        advanceTo(10_250)
        assertEquals(listOf("device"), ran)
    }

    @Test
    fun `the timeout after a frame changes nothing`() {
        val g = gate(timeoutMs = 1_000)
        g.run("device") { ran += "device" }
        g.reached("first paint")
        advanceTo(5_000)
        assertEquals(listOf("device"), ran)
    }

    @Test
    fun `cancelled work doesn't run`() {
        val g = gate()
        val cancel = g.run("device") { ran += "device" }
        g.run("discovery") { ran += "discovery" }
        cancel()
        g.reached("first paint")
        advanceTo(250)
        assertEquals(listOf("discovery"), ran)
    }

    @Test
    fun `work that throws is logged and the rest still runs`() {
        val g = gate()
        g.run("broken") { throw IllegalStateException("no") }
        g.run("device") { ran += "device" }
        g.reached("first paint")
        advanceTo(250)
        assertEquals(listOf("device"), ran)
        assertEquals(1, warned.size)
        assertTrue(warned[0], "broken" in warned[0])
    }

    @Test
    fun `the default timeout is past the S1's slowest first frame and short enough not to strand voice`() {
        assertTrue(AfterFirstFrame.TIMEOUT_MS in 5_000L..15_000L)
        assertTrue(AfterFirstFrame.SETTLE_MS in 100L..1_000L)
    }
}
