/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.gecko.legacy

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #684: on the Mirror Android's trim level sits at RUNNING_CRITICAL for hours, and GeckoView tells Gecko only once. */
class MemoryPressureTest {
    private class Run(levels: List<Int>) {
        private val left = ArrayDeque(levels)
        val delays = mutableListOf<Long>()
        var told = 0
        private var next: (() -> Unit)? = null
        val pressure = MemoryPressure(
            trimLevel = { left.removeFirst() },
            notify = { told++ },
            later = { ms, task -> delays += ms; next = task },
            log = {},
        )

        /** Starts it and lets every queued check run, one interval apart. */
        fun all(): Run {
            pressure.start()
            while (left.isNotEmpty()) {
                val task = next ?: break
                next = null
                task()
            }
            return this
        }
    }

    @Test fun `while memory stays critical Gecko hears it at every check, not once`() {
        val r = Run(List(5) { TRIM_MEMORY_RUNNING_CRITICAL }).all()
        assertEquals(5, r.told)
        assertTrue(r.delays.all { it == MemoryPressure.INTERVAL_MS })
    }

    @Test fun `with memory to spare Gecko hears nothing, and the checks go on`() {
        val r = Run(listOf(0, TRIM_MEMORY_RUNNING_MODERATE, TRIM_MEMORY_UI_HIDDEN, 0)).all()
        assertEquals(0, r.told)
        assertEquals("one check after another, and the next one queued", 5, r.delays.size)
    }

    @Test fun `low, then fine, then low again`() {
        val r = Run(listOf(TRIM_MEMORY_RUNNING_LOW, 0, TRIM_MEMORY_RUNNING_CRITICAL)).all()
        assertEquals(2, r.told)
    }

    @Test fun `which levels say memory is short`() {
        for (l in listOf(TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_BACKGROUND, TRIM_MEMORY_COMPLETE)) {
            assertTrue("$l", MemoryPressure.isLow(l))
        }
        for (l in listOf(0, TRIM_MEMORY_RUNNING_MODERATE, TRIM_MEMORY_UI_HIDDEN)) assertFalse("$l", MemoryPressure.isLow(l))
    }

    @Test fun `the first check waits an interval, since GeckoView told Gecko at the trim itself`() {
        val r = Run(listOf(TRIM_MEMORY_RUNNING_CRITICAL))
        r.pressure.start()
        assertEquals(0, r.told)
        assertEquals(listOf(MemoryPressure.INTERVAL_MS), r.delays)
    }
}
