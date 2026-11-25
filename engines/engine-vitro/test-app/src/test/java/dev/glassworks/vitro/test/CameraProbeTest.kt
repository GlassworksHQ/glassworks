/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.vitro.test

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import dev.glassworks.engine.vitro.camera.CameraStream
import dev.glassworks.engine.vitro.camera.CameraStreamFactories
import dev.glassworks.engine.vitro.camera.CameraStreamFactory
import dev.glassworks.engine.vitro.camera.CameraStreamHost
import dev.glassworks.engine.vitro.camera.CameraStreamKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

/** The camera end-to-end test's probe changes nothing a stream's choice or card sees, and logs what the test reads. */
@RunWith(RobolectricTestRunner::class)
class CameraProbeTest {
    /** An optional interface a card may look for with `as?` (as a stream's video size). */
    interface Sized {
        val videoWidth: Int
    }

    class FakeStream(context: Context) : CameraStream, Sized {
        override val view: View = View(context)
        override var listener: CameraStream.Listener? = null
        override val videoWidth = 640
        var started = false
        var activeNow = true
        var closed = false

        override fun start() {
            started = true
        }

        override fun setActive(active: Boolean) {
            activeNow = active
        }

        override fun close() {
            closed = true
        }
    }

    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun unregister() {
        CameraStreamFactories.unregister(CameraStreamKind.HLS)
    }

    @Test
    fun the_choice_gets_the_streams_calls_events_and_interfaces_and_logcat_gets_its_life() {
        ShadowLog.clear()
        val fake = FakeStream(context)
        CameraStreamFactories.register(CameraStreamKind.HLS) {
            object : CameraStreamFactory {
                override fun create(entityId: String, kind: CameraStreamKind): CameraStream = fake
            }
        }
        CameraProbe.install()
        val wrapped = CameraStreamFactories.provider(CameraStreamKind.HLS)
        CameraProbe.install()
        assertSame("a second install wraps nothing again", wrapped, CameraStreamFactories.provider(CameraStreamKind.HLS))

        val host = CameraStreamHost(context, Handler(Looper.getMainLooper()), "http://homeassistant.local:8123", { null },
            { _, _ -> }, { _, _, _ -> {} })
        val stream = wrapped!!(host).create("camera.front_door", CameraStreamKind.HLS)!!
        assertNotSame(fake, stream)
        assertSame(fake.view, stream.view)
        assertEquals(640, (stream as Sized).videoWidth)

        val heard = mutableListOf<String>()
        val choice = object : CameraStream.Listener {
            override fun onFirstFrame(sinceStartMs: Long) {
                heard += "first $sinceStartMs"
            }

            override fun onFrameRendered() {
                heard += "frame"
            }

            override fun onError(reason: String) {
                heard += "error $reason"
            }

            override fun onEnded() {
                heard += "ended"
            }
        }
        stream.listener = choice
        assertSame(choice, stream.listener)
        stream.start()
        assertTrue(fake.started)
        fake.listener!!.onFirstFrame(812)
        fake.listener!!.onFrameRendered()
        fake.listener!!.onError("no video\nat all")
        fake.listener!!.onEnded()
        assertEquals(listOf("first 812", "frame", "error no video\nat all", "ended"), heard)
        stream.setActive(false)
        assertFalse(fake.activeNow)
        stream.close()
        assertTrue(fake.closed)

        val lines = ShadowLog.getLogsForTag("VitroCamE2E").map { it.msg }
        assertTrue(lines.toString(), lines.any { Regex("^open id=\\d+ entity=camera.front_door kind=HLS live=\\d+$").matches(it) })
        assertTrue(lines.toString(), lines.any {
            Regex("^first_frame id=\\d+ entity=camera.front_door kind=HLS since_start_ms=812 since_open_ms=\\d+$").matches(it)
        })
        assertTrue(lines.toString(), lines.any { it.endsWith("reason=no video at all") })
        assertTrue(lines.toString(), lines.any { Regex("^active id=\\d+ value=false$").matches(it) })
        assertTrue(lines.toString(), lines.any { Regex("^close id=\\d+ entity=camera.front_door kind=HLS total=2 live=0$").matches(it) })
        // No tick ran before the close: the frames it drew are logged on close, before the close line.
        val frames = lines.indexOfFirst { Regex("^frames id=\\d+ rendered=2 total=2$").matches(it) }
        assertTrue(lines.toString(), frames >= 0 && frames < lines.indexOfFirst { it.startsWith("close ") })
    }
}
