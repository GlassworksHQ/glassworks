/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** An H.264 encoder as the stream drives it: [H264Encoder] on a device. */
interface StreamEncoder {
    /** Where the camera draws for it. */
    val surface: Surface?
    fun requestKeyFrame()
    fun release()
}

/**
 * Makes an encoder at [size], turned [turn] degrees ([EncoderInput.streamTurn]) and run at [fps]. [onFailure] gets the
 * encoder that failed, so a late report from a replaced one is told apart.
 */
fun interface EncoderFactory {
    fun create(size: StreamFit.Size, turn: Int, fps: Int, onAccessUnit: (annexB: ByteArray, key: Boolean) -> Unit, onFailure: (StreamEncoder) -> Unit): StreamEncoder
}

/** Where the encoder's input comes from: [CameraHub]. Detaching (null) returns once the camera no longer draws into the old one. */
fun interface EncoderInput {
    fun setEncoder(encoder: StreamEncoder?)

    /** The frame rate the encoder's input runs at while streaming, for the SDP; 0 when it can't say. */
    val streamFps: Int get() = 0

    /** How far the stream turns the camera's picture clockwise to stand upright, as a still does ([stillRotation]). */
    val streamTurn: Int get() = 0

    /** The size the camera can draw into an encoder at, or why it can't: [CameraHub] reads it from the camera. */
    val streamFit: StreamFit get() = StreamFit.Size(CameraHub.WIDTH, CameraHub.HEIGHT)
}

/**
 * The stream's encoder for one RTSP server's life: started when someone watches, stopped when nobody
 * does, replaced when it fails while someone still watches, at most once every [RESTART_MS] (a later
 * attempt is posted, not dropped). An encoder that can't be made while someone watches (the GPU's input fails to set
 * up) is tried again, paced as the camera's reconnects are ([ConnectBackoff]); viewers who come meanwhile wait for that try. Each running encoder holds the privacy indicator on through
 * [egress]. After [close], nothing more happens, so a stopped server's late reports change nothing. A camera
 * that can't stream ([StreamFit.Refused]) gets no encoder, and the indicator stays off.
 *
 * [watched] says whether the server still has viewers; [after] runs a callback later (a Handler, on the main thread).
 * A posted try makes its encoder on [worker], never on [after]'s thread: making one waits for the GPU's input to set up
 * (up to seconds when it can't), which must not freeze the panel. [close] shuts the worker down.
 */
internal class ViewerEncoder(
    private val input: EncoderInput,
    private val encoders: EncoderFactory,
    private val egress: FrameEgress,
    private val onAccessUnit: (ByteArray, Boolean) -> Unit,
    private val watched: () -> Boolean,
    private val clock: () -> Long,
    private val after: (delayMs: Long, Runnable) -> Unit,
    private val log: (String) -> Unit = {},
    /** The turn and rate every encoder of this server runs at, fixed when the server starts, as ONVIF advertises them. */
    private val turn: Int = 0,
    private val fps: Int = H264Encoder.FPS,
    private val worker: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "glassworks-encoder").apply { isDaemon = true } },
) {
    private val lock = Any()
    private var current: StreamEncoder? = null
    private var viewing: AutoCloseable? = null
    private var closed = false
    private var lastRestartMs = Long.MIN_VALUE / 2
    private val retry = ConnectBackoff()
    private var retryPosted = false

    /** RTSP's count of viewers, waiting ones included. */
    fun viewers(count: Int) {
        synchronized(lock) {
            if (closed) return
            when {
                // A pending retry keeps its wait: more viewers don't make more tries at a failing encoder.
                count > 0 && current == null -> if (!retryPosted) start()
                count == 0 -> stop()
                else -> current?.requestKeyFrame()
            }
        }
    }

    /** The server stopped: the encoder goes, and later reports do nothing. */
    fun close() {
        synchronized(lock) {
            closed = true
            stop()
        }
        (worker as? ExecutorService)?.shutdown()
    }

    /** [go] on [worker], under the lock. After [close] the worker takes nothing, and that's fine: nothing more happens. */
    private fun onWorker(go: () -> Unit) {
        try {
            worker.execute { synchronized(lock) { go() } }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun start() {
        val size = when (val fit = input.streamFit) {
            is StreamFit.Size -> fit
            is StreamFit.Refused -> { log("no stream: ${fit.why}"); return }
        }
        val e = try {
            encoders.create(size, turn, fps, onAccessUnit, ::failed)
        } catch (ex: Exception) {
            log("H.264 encoder failed: ${ex.javaClass.simpleName}")
            retryLater()
            return
        }
        retry.reset()
        current = e
        input.setEncoder(e)
        viewing = egress.stream()
    }

    /**
     * The camera stops drawing into it first, then it is released. Whatever the detach or the release
     * throws, the encoder is released and the indicator's hold let go. An interrupted wait for the camera
     * goes on, and the thread's interrupt is set again only after the release: set during it, the
     * encoder's own waits ([H264Encoder.release] joins its thread) would fail at once and skip the rest.
     */
    private fun stop() {
        val e = current ?: return
        current = null
        var interrupted = false
        try {
            input.setEncoder(null)
        } catch (_: InterruptedException) {
            interrupted = true
        } finally {
            try {
                e.release()
            } finally {
                try {
                    val v = viewing
                    viewing = null
                    v?.close()
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    /** One posted try at a time; it does nothing once the server stopped, an encoder runs, or nobody watches. */
    private fun retryLater() {
        if (retryPosted) return
        retryPosted = true
        after(retry.failed(clock()), Runnable {
            onWorker {
                retryPosted = false
                if (closed || current != null || !watched()) return@onWorker
                lastRestartMs = clock()
                start()
            }
        })
    }

    private fun failed(e: StreamEncoder) {
        synchronized(lock) {
            // Only the encoder in use: a replaced one's late report must not stop its replacement.
            if (closed || e !== current) return
            stop()
            val wait = lastRestartMs + RESTART_MS - clock()
            val go = Runnable {
                onWorker {
                    if (closed || current != null || !watched()) return@onWorker
                    lastRestartMs = clock()
                    start()
                }
            }
            if (wait <= 0) go.run() else after(wait, go)
        }
    }

    companion object {
        const val RESTART_MS = 5_000L
    }
}
