/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.vitro

import dev.glassworks.engine.vitro.clarity.FrameProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A Clarity wait that ends stops its frame step for good, so what the step wrote is read without a race (D16). */
class FrameProbeTest {
    @Test
    fun aStepRunningAsTheWaitEndsFinishesFirstAndNoneRunsAfter() {
        val inStep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val looks = AtomicInteger()
        val probe = FrameProbe { _ ->
            looks.incrementAndGet()
            inStep.countDown()
            release.await(5, TimeUnit.SECONDS)
            false
        }
        val frame = thread { probe.look(16.0) }
        assertTrue(inStep.await(5, TimeUnit.SECONDS))
        // The wait times out while that frame's step still runs: end() waits for it.
        val ended = CountDownLatch(1)
        var verdict: Boolean? = null
        val waiter = thread { verdict = probe.end(); ended.countDown() }
        assertFalse("end() returned while a step was still running", ended.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        frame.join(); waiter.join()
        assertEquals(false, verdict)
        // The next frame drops it unread.
        assertTrue(probe.look(32.0))
        assertEquals(1, looks.get())
    }

    @Test
    fun aStepThatSaidDoneBeforeTheWaitEndedCounts() {
        val probe = FrameProbe { ts -> ts >= 32.0 }
        assertFalse(probe.look(16.0))
        assertTrue(probe.look(32.0))
        assertTrue("done just before end() still counts", probe.end())
    }

    @Test
    fun aProbeWhoseWaitEndedNeverLooksAgain() {
        val looks = AtomicInteger()
        val probe = FrameProbe { _ -> looks.incrementAndGet(); false }
        assertFalse(probe.end())
        assertTrue(probe.look(16.0))
        assertEquals(0, looks.get())
    }
}
