/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEncoderTest {

    private val events = mutableListOf<String>()

    private inner class Encoder(val n: Int, val fail: (StreamEncoder) -> Unit, val releaseThrows: Boolean = false) : StreamEncoder {
        override val surface: Surface? = null
        var keyFrames = 0
        var released = false
        override fun requestKeyFrame() { keyFrames++ }
        /** Like [H264Encoder.release]: it waits for its drain thread, which fails at once on a thread already interrupted. */
        override fun release() {
            Thread { Thread.sleep(5) }.apply { start() }.join(1_000)
            released = true; events += "release $n"
            if (releaseThrows) throw IllegalStateException("codec")
        }
        override fun toString() = "encoder $n"
    }

    private inner class Rig(val detach: () -> Unit = {}, val releaseThrows: Boolean = false, val fit: StreamFit = StreamFit.Size(1280, 720)) {
        val sizes = mutableListOf<StreamFit.Size>()
        var now = 0L
        val posted = mutableListOf<Pair<Long, Runnable>>()
        val made = mutableListOf<Encoder>()
        var watching = true
        /** How many more times making an encoder throws, as when the GPU's input can't set up. */
        var throwsLeft = 0
        /** While set, work for the encoder's worker waits here instead of running at once. */
        var holdWorker = false
        val onWorker = mutableListOf<Runnable>()
        val egressSeen = mutableListOf<Boolean>()
        val egress = FrameEgress({ now }, { _, _ -> }) { egressSeen += it }
        val ve = ViewerEncoder(
            input = object : EncoderInput {
                override fun setEncoder(encoder: StreamEncoder?) { events += "input ${encoder ?: "none"}"; if (encoder == null) detach() }
                override val streamFit get() = fit
            },
            encoders = { size, _, _, _, failed ->
                if (throwsLeft > 0) { throwsLeft--; throw IllegalStateException("GL setup timed out") }
                sizes += size; Encoder(made.size + 1, failed, releaseThrows).also { made += it; events += "made ${it.n}" } },
            egress = egress, onAccessUnit = { _, _ -> }, watched = { watching },
            clock = { now }, after = { ms, r -> posted += (now + ms) to r },
            worker = { r -> if (holdWorker) onWorker += r else r.run() },
        )

        fun runPosted(to: Long) {
            now = to
            posted.filter { it.first <= to }.also { posted.removeAll(it) }.forEach { it.second.run() }
        }
    }

    @Test fun aViewerStartsTheEncoderAndTheLastOneStopsIt() {
        val r = Rig()
        r.ve.viewers(1)
        assertEquals(1, r.made.size)
        assertTrue("the indicator is on while someone watches", r.egress.leaving)
        r.ve.viewers(2)
        assertEquals("a second viewer asks for a keyframe, not a second encoder", 1, r.made.size)
        assertEquals(1, r.made[0].keyFrames)
        r.ve.viewers(0)
        assertTrue(r.made[0].released)
        assertFalse(r.egress.leaving)
        assertEquals("the camera stops drawing into it before it is released",
            listOf("made 1", "input encoder 1", "input none", "release 1"), events)
    }

    /** #479: a LEGACY camera without 1280x720 streams at the size it lists. */
    @Test fun theEncoderRunsAtTheCamerasSize() {
        val r = Rig(fit = StreamFit.Size(640, 480))
        r.ve.viewers(1)
        assertEquals(listOf(StreamFit.Size(640, 480)), r.sizes)
        assertTrue(r.egress.leaving)
    }

    /** #479: a camera that can't feed the encoder gets none, and the indicator never says images leave. */
    @Test fun aCameraThatCantStreamGetsNoEncoderAndTheIndicatorStaysOff() {
        val r = Rig(fit = StreamFit.Refused("The camera can't send its 640 × 480 picture to a video encoder"))
        r.ve.viewers(1)
        r.ve.viewers(2)
        assertEquals(0, r.made.size)
        assertEquals("the camera is never asked to draw into one", emptyList<String>(), events)
        assertFalse(r.egress.leaving)
        assertEquals(emptyList<Boolean>(), r.egressSeen)
    }

    @Test fun aReplacementSurvivesTheOldEncodersLateFailure() {
        val r = Rig()
        r.ve.viewers(1)
        val first = r.made[0]
        first.fail(first)                 // fails: replaced at once (the first restart isn't limited)
        assertEquals(2, r.made.size)
        val second = r.made[1]
        first.fail(first)                 // its late report again
        assertFalse("the replacement keeps running", second.released)
        assertTrue(r.egress.leaving)
        assertEquals(2, r.made.size)
    }

    @Test fun aRestartTooSoonAfterTheLastIsPostedNotDropped() {
        val r = Rig()
        r.ve.viewers(1)
        r.made[0].fail(r.made[0])         // t=0: restarted, the limit starts now
        r.now = 1_000
        r.made[1].fail(r.made[1])         // t=1 s: too soon
        assertEquals(2, r.made.size)
        assertEquals(1, r.posted.size)
        assertEquals("when the limit allows", ViewerEncoder.RESTART_MS, r.posted[0].first)
        r.runPosted(ViewerEncoder.RESTART_MS)
        assertEquals("the viewers get frames again", 3, r.made.size)
        assertTrue(r.egress.leaving)
    }

    @Test fun aPostedRestartWithNobodyWatchingDoesNothing() {
        val r = Rig()
        r.ve.viewers(1)
        r.made[0].fail(r.made[0])
        r.now = 1_000
        r.made[1].fail(r.made[1])
        r.watching = false
        r.runPosted(ViewerEncoder.RESTART_MS)
        assertEquals(2, r.made.size)
        assertFalse(r.egress.leaving)
    }

    /** CodeRabbit on #924: a replacement that can't be made while someone watches is tried again, paced, until one runs. */
    @Test fun aReplacementThatCantBeMadeIsTriedAgainWhileSomeoneWatches() {
        val r = Rig()
        r.ve.viewers(1)
        r.throwsLeft = 2
        r.made[0].fail(r.made[0])
        assertEquals("the replacement failed to be made", 1, r.made.size)
        assertEquals(1, r.posted.size)
        assertEquals(ConnectBackoff.FIRST_MS, r.posted[0].first - r.now)
        r.runPosted(r.posted[0].first)
        assertEquals("the second try failed too, and the next waits longer", 1, r.posted.size)
        assertEquals(2 * ConnectBackoff.FIRST_MS, r.posted[0].first - r.now)
        r.runPosted(r.posted[0].first)
        assertEquals(2, r.made.size)
        assertTrue(r.egress.leaving)
        assertTrue("nothing more is posted once one runs", r.posted.isEmpty())
    }

    /** Greptile on #924: viewers who come while an encoder can't be made wait for the posted try, not a try each. */
    @Test fun viewersWhoComeDuringTheRetryWaitMakeNoExtraTries() {
        val r = Rig()
        r.throwsLeft = 5
        r.ve.viewers(1)
        assertEquals("one try, failed", 4, r.throwsLeft)
        r.ve.viewers(2); r.ve.viewers(1); r.ve.viewers(3)
        assertEquals("no extra tries", 4, r.throwsLeft)
        assertEquals(1, r.posted.size)
        r.throwsLeft = 0
        r.runPosted(r.posted[0].first)
        assertEquals("the posted try makes it", 1, r.made.size)
        assertTrue(r.egress.leaving)
    }

    @Test fun aPostedTryAfterStopMakesNoEncoder() {
        val r = Rig()
        r.throwsLeft = 1
        r.ve.viewers(1)
        assertEquals(1, r.posted.size)
        r.ve.close()
        r.runPosted(r.posted[0].first)
        assertTrue(r.made.isEmpty())
        assertFalse(r.egress.leaving)
    }

    /** CodeRabbit and Greptile on #924: a posted try makes its encoder on the worker, not on the (main) thread that posted it. */
    @Test fun aPostedTryMakesItsEncoderOnTheWorker() {
        val r = Rig()
        r.throwsLeft = 1
        r.ve.viewers(1)
        r.holdWorker = true
        r.runPosted(r.posted[0].first)
        assertTrue("nothing is made on the posting thread", r.made.isEmpty())
        assertEquals(1, r.onWorker.size)
        r.onWorker.removeAt(0).run()
        assertEquals(1, r.made.size)
        assertTrue(r.egress.leaving)
    }

    @Test fun theDefaultWorkerIsItsOwnThreadAndCloseStopsIt() {
        val made = java.util.concurrent.CountDownLatch(1)
        var thread: Thread? = null
        var encoder: Encoder? = null
        var throws = true
        val posted = mutableListOf<Runnable>()
        val ve = ViewerEncoder(
            input = { }, egress = FrameEgress({ 0L }, { _, _ -> }) {}, onAccessUnit = { _, _ -> }, watched = { true },
            encoders = { _, _, _, _, failed ->
                if (throws) { throws = false; throw IllegalStateException("GL setup timed out") }
                thread = Thread.currentThread(); Encoder(1, failed).also { encoder = it; made.countDown() }
            },
            clock = { 0L }, after = { _, go -> posted += go },
        )
        ve.viewers(1)
        posted.removeAt(0).run()
        assertTrue(made.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("glassworks-encoder", thread?.name)
        // A restart posted before close runs after it: the shut-down worker takes nothing, and nothing throws. The
        // failure report takes the lock, so it comes after the worker's start has made this encoder the current one.
        val e = encoder!!
        e.fail(e)
        assertEquals("the restart waits for the limit", 1, posted.size)
        ve.close()
        posted.removeAt(0).run()
        assertTrue(e.released)
    }

    @Test fun afterCloseNothingHappens() {
        val r = Rig()
        r.ve.viewers(1)
        r.ve.close()
        assertTrue(r.made[0].released)
        r.ve.viewers(1)                   // a stopped server's late report
        r.made[0].fail(r.made[0])
        assertEquals(1, r.made.size)
        assertFalse(r.egress.leaving)
    }

    @Test fun anInterruptedDetachStillReleasesTheEncoderAndTheIndicator() {
        val r = Rig(detach = { throw InterruptedException() })
        r.ve.viewers(1)
        r.ve.viewers(0)
        assertTrue("the thread keeps its interrupt", Thread.interrupted())
        assertTrue("no codec or surface left behind", r.made[0].released)
        assertFalse("the indicator goes off", r.egress.leaving)
        r.ve.viewers(1)
        assertEquals("the next viewer gets a new encoder", 2, r.made.size)
    }

    @Test fun aFailingReleaseStillLetsGoOfTheIndicator() {
        val r = Rig(releaseThrows = true)
        r.ve.viewers(1)
        assertThrows(IllegalStateException::class.java) { r.ve.viewers(0) }
        assertFalse(r.egress.leaving)
        r.ve.viewers(1)
        assertEquals(2, r.made.size)
    }
}
