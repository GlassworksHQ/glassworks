/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dev.glassworks.camera.onvif.H264AccessUnitAssembler
import dev.glassworks.camera.onvif.H264ParameterSetCache

/**
 * The stream's H.264 encoder: the camera draws [width] x [height] into [surface], which [TurnedInput] turns [turn]
 * degrees clockwise on the GPU before encoding, so the stream stands as stills do. Each access unit goes to
 * [onAccessUnit] in Annex-B with its parameter sets before every keyframe (the ported
 * [H264ParameterSetCache]), on the encoder's own thread. A keyframe every second, so a new viewer
 * waits at most that long, and [requestKeyFrame] for one now.
 *
 * The device's own (hardware) encoder when it takes the picture ([encoderOrder]): `createEncoderByType` gave the
 * Mirror Google's software encoder, which spends CPU and memory the page needs (#679). Each encoder that refuses is
 * logged with why, and the next is tried.
 */
class H264Encoder(
    width: Int,
    height: Int,
    turn: Int = 0,
    fps: Int = FPS,
    bitrate: Int = bitrateFor(width, height),
    private val onAccessUnit: (annexB: ByteArray, key: Boolean) -> Unit,
    /** The encoder stopped by itself (a codec error, or the GPU step failing a frame), not through [release]. On the encoder's thread. */
    private val onFailure: (StreamEncoder) -> Unit = {},
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : StreamEncoder {
    private val codec: MediaCodec
    /** The codec's own input, which [turned] draws into. */
    private val input: Surface
    private val turned: TurnedInput
    override val surface: Surface get() = turned.surface
    @Volatile private var running = true
    /** Why the GPU step stopped drawing, set on its thread; the drain loop reports it, as it does a codec error. */
    @Volatile private var inputFailed: String? = null
    private val drain: Thread

    init {
        val (outWidth, outHeight) = turnedSize(width, height, turn)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val tried = encoderOrder(avcEncoders(), outWidth, outHeight)
        log("camera: H.264 encoders for ${outWidth}x$outHeight (turned $turn°): ${tried.joinToString { it.describe() }.ifEmpty { "none listed" }}")
        var opened: Pair<MediaCodec, Surface>? = null
        for (e in tried) {
            opened = try { open(MediaCodec.createByCodecName(e.name), format) } catch (x: Exception) {
                log("camera: ${e.name} refused: ${x.javaClass.simpleName}: ${x.message}")
                null
            }
            if (opened != null) { log("camera: H.264 encoder ${e.name} (${if (e.hardware) "hardware" else "software"})"); break }
        }
        // None listed, or each refused: Android's own pick, as before.
        val (c, made) = opened ?: open(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC), format)
            .also { log("camera: H.264 encoder ${it.first.name} (Android's pick)") }
        codec = c
        input = made
        turned = try { TurnedInput(made, width, height, turn) { why -> inputFailed = why } } catch (e: Exception) {
            try { c.stop() } catch (_: Exception) {}
            c.release(); made.release()
            throw e
        }
        drain = Thread(::drainLoop, "glassworks-h264").apply { isDaemon = true; start() }
    }

    /** Configures and starts [codec] for [format]; frees it and throws when it refuses. */
    private fun open(codec: MediaCodec, format: MediaFormat): Pair<MediaCodec, Surface> {
        var made: Surface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            made = codec.createInputSurface()
            codec.start()
            return codec to made
        } catch (e: Exception) {
            made?.release()
            codec.release()
            throw e
        }
    }

    override fun requestKeyFrame() {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    /**
     * Stops the encoder and frees it, the GPU step first so nothing draws into a stopped codec. Take [surface] out of
     * the camera session first. An interrupted
     * wait for the drain thread still frees the codec and the surface, then sets the interrupt again.
     */
    override fun release() {
        running = false
        var interrupted = false
        if (Thread.currentThread() !== drain) { // from onFailure, it is this thread
            try { drain.join(1_000) } catch (_: InterruptedException) { interrupted = true }
        }
        try {
            try { turned.release() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            input.release()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        val cache = H264ParameterSetCache()
        val assembler = H264AccessUnitAssembler()
        while (running) {
            inputFailed?.let { why ->
                Log.w(TAG, "H.264 encoder input stopped: $why")
                running = false
                onFailure(this)
                return
            }
            val i = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (e: IllegalStateException) {
                if (running) {
                    Log.w(TAG, "H.264 encoder stopped: ${e.javaClass.simpleName}")
                    running = false
                    onFailure(this)
                }
                break
            }
            if (i < 0) continue
            try {
                val buf = codec.getOutputBuffer(i) ?: continue
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.get(bytes)
                    val whole = assembler.append(bytes, info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0)
                    val au = whole?.let { cache.prepare(it, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) }
                    if (au != null) onAccessUnit(au.bytes, au.isKeyframe)
                }
            } catch (e: Exception) {
                Log.w(TAG, "H.264 output dropped: ${e.javaClass.simpleName}")
            } finally {
                try { codec.releaseOutputBuffer(i, false) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "Glassworks.Camera"
        const val FPS = 15

        /** About 0.1 bit a pixel at [FPS]: 1.4 Mbit/s at 1280x720. */
        fun bitrateFor(width: Int, height: Int): Int = (width * height * FPS / 10).coerceIn(500_000, 6_000_000)

        /** Whether some H.264 encoder takes [width] x [height] ([encoderOrder]). False without one. True when Android can't say: the encoder then tries. */
        fun encodes(width: Int, height: Int): Boolean = try {
            encoderOrder(avcEncoders(), width, height).isNotEmpty()
        } catch (e: Exception) {
            true
        }

        /** The H.264 encoders Android lists, in its order. */
        private fun avcEncoders(): List<AvcEncoder> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .map { info ->
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
                AvcEncoder(
                    info.name,
                    hardware = !isSoftwareCodec(info.name),
                    listsSurface = caps?.colorFormats?.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) ?: false,
                    takes = { w, h -> caps?.videoCapabilities?.let { v -> takesEitherWay(w, h) { a, b -> runCatching { v.isSizeSupported(a, b) }.getOrDefault(true) } } ?: true },
                )
            }
    }
}

/**
 * Whether an encoder takes [width] x [height] either way round. Encoders list their sizes landscape (the Mirror's:
 * up to 1920x1088), as phones record landscape and turn on playback, but what bounds the hardware is macroblocks a
 * frame and a second. A stream turned upright ([TurnedInput]) is portrait, and the Mirror's encoder took 720x1280.
 */
fun takesEitherWay(width: Int, height: Int, listed: (Int, Int) -> Boolean): Boolean = listed(width, height) || listed(height, width)

/** One H.264 encoder as [encoderOrder] weighs it: [listsSurface] when it names Surface input among its color formats. */
class AvcEncoder(val name: String, val hardware: Boolean, val listsSurface: Boolean, val takes: (width: Int, height: Int) -> Boolean) {
    fun describe() = "$name (${if (hardware) "hardware" else "software"}${if (listsSurface) "" else ", no surface listed"})"
}

/**
 * The encoders to try at [width] x [height]: those that take the size, hardware first, then the ones that list Surface
 * input first (an older encoder may take it without listing it), each group in Android's own order.
 */
fun encoderOrder(all: List<AvcEncoder>, width: Int, height: Int): List<AvcEncoder> =
    all.filter { it.takes(width, height) }.sortedWith(compareBy({ !it.hardware }, { !it.listsSurface }))

/**
 * AOSP's own test (`MediaCodecList::isSoftwareCodec`): Google's and Android's codecs, and any whose name starts with
 * neither `OMX.` nor `c2.`, run on the CPU.
 */
fun isSoftwareCodec(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") || n.startsWith("c2.android.") || (!n.startsWith("omx.") && !n.startsWith("c2."))
}
