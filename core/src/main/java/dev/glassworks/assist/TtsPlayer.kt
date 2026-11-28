package dev.glassworks.assist

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue

/** Sink for PCM audio (chimes and other locally generated sounds). */
interface AudioOutput {
    /** Starts a new stream (cancels any stream still playing). */
    fun begin(rate: Int, width: Int, channels: Int)

    /** Queues raw PCM in the format given to [begin]. Must not block for long. */
    fun write(pcm: ByteArray)

    /** Marks the end of the stream; [onDone] runs once everything queued has been played. */
    fun end(onDone: () -> Unit)

    /** Stops playback immediately and drops queued audio. */
    fun cancel()
}

/** Plays the timer chime ([Chimes.timer]): the timers pane plays it when a timer finishes. */
fun AudioOutput.chime(onDone: () -> Unit = {}) = play(Chimes.timer, onDone)

/** Plays the wake chime ([Chimes.wake]), "I'm listening", when the wake word is heard. */
fun AudioOutput.wakeChime(onDone: () -> Unit = {}) = play(Chimes.wake, onDone)

private fun AudioOutput.play(pcm: ByteArray, onDone: () -> Unit) {
    begin(Chimes.RATE, 2, 1)
    write(pcm)
    end(onDone)
}

/**
 * Plays raw PCM with [AudioTrack] on its own thread, so callers never block.
 *
 * Honours the stream's rate/width/channels: 8-bit (unsigned, as in WAV) and 16-bit are played
 * natively; 24/32-bit integer samples are reduced to 16-bit; more than 2 channels are reduced
 * to the first channel.
 */
class TtsPlayer(
    /**
     * The gain each sound opens at, read as it opens: the master volume where the device keeps its volume fixed
     * ([dev.glassworks.audio.MasterVolume.gain]), so a critical alert raised just before it plays at full. 1 by default.
     */
    private val gainNow: () -> Float = { 1f },
) : AudioOutput {

    private sealed class Cmd {
        class Begin(val gen: Int, val rate: Int, val width: Int, val channels: Int) : Cmd()
        class Data(val gen: Int, val pcm: ByteArray) : Cmd()
        class End(val gen: Int, val onDone: () -> Unit) : Cmd()
        data object Quit : Cmd()
    }

    private val queue = LinkedBlockingQueue<Cmd>()
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    private val trackLock = Any()
    /** Under [trackLock]: the gain the track plays at ([gainNow] as it opened, then [setVolume]). */
    private var gain = 1f

    /** The gain the last sound opened at, for tests. */
    internal val openedAt: Float get() = synchronized(trackLock) { gain }
    private val thread = Thread({ loop() }, "voice-tts").apply { isDaemon = true; start() }

    override fun begin(rate: Int, width: Int, channels: Int) {
        val g = synchronized(this) { ++gen }
        haltTrack()
        queue.put(Cmd.Begin(g, rate, width, channels))
    }

    override fun write(pcm: ByteArray) {
        queue.put(Cmd.Data(gen, pcm))
    }

    override fun end(onDone: () -> Unit) {
        queue.put(Cmd.End(gen, onDone))
    }

    override fun cancel() {
        synchronized(this) { ++gen }
        queue.clear()
        haltTrack()
    }

    /** A new gain, 0 to 1, for the sound playing now (the next ones read [gainNow]). Safe from any thread. */
    fun setVolume(gain: Float) {
        synchronized(trackLock) {
            this.gain = gain.coerceIn(0f, 1f)
            try { track?.setVolume(this.gain) } catch (_: IllegalStateException) {}
        }
    }

    fun release() {
        cancel()
        queue.put(Cmd.Quit)
        thread.join(1_000)
    }

    /** Unblocks a blocking AudioTrack.write on the player thread. */
    private fun haltTrack() {
        synchronized(trackLock) {
            try {
                track?.pause()
                track?.flush()
            } catch (_: IllegalStateException) {}
        }
    }

    // ------------------------------------------------------------------ player thread

    private var fmtRate = 22_050
    private var fmtWidth = 2
    private var fmtChannels = 1
    private var outChannels = 1
    private var outWidth = 2
    private var framesWritten = 0L
    private var carry = ByteArray(0)

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (true) {
            val cmd = try { queue.take() } catch (_: InterruptedException) { break }
            try {
                when (cmd) {
                    is Cmd.Begin -> if (cmd.gen == gen) open(cmd.rate, cmd.width, cmd.channels)
                    is Cmd.Data -> if (cmd.gen == gen) {
                        if (track == null) open(fmtRate, fmtWidth, fmtChannels)
                        play(cmd.pcm)
                    }
                    is Cmd.End -> {
                        if (cmd.gen == gen) drain()
                        closeTrack()
                        cmd.onDone()
                    }
                    Cmd.Quit -> { closeTrack(); return }
                }
            } catch (e: Exception) {
                VoiceLog.w("TTS playback error", e)
                closeTrack()
            }
        }
    }

    private fun open(rate: Int, width: Int, channels: Int) {
        closeTrack()
        fmtRate = rate
        fmtWidth = width
        fmtChannels = channels
        outChannels = if (channels == 2) 2 else 1
        outWidth = if (width == 1) 1 else 2
        carry = ByteArray(0)
        framesWritten = 0
        val encoding = if (outWidth == 1) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val mask = if (outChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, encoding)
        // Must be a whole number of frames: e.g. 22050 Hz mono 16-bit -> 250 ms = 11025 bytes (odd).
        val frame = outWidth * outChannels
        val bufBytes = (maxOf(minBuf, rate * frame / 4) + frame - 1) / frame * frame
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(mask).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufBytes) // >= 250 ms
            .build()
        t.play()
        // Nothing is written yet: the gain is in place before the first sample. Read under the lock, so a
        // setVolume can't land between the read and the track.
        synchronized(trackLock) {
            gain = runCatching { gainNow().coerceIn(0f, 1f) }.getOrDefault(1f)
            track = t
            t.setVolume(gain)
        }
        VoiceLog.i("TTS stream: ${rate} Hz, ${width * 8}-bit, $channels ch")
    }

    private fun play(pcm: ByteArray) {
        val t = track ?: return
        val inFrame = fmtWidth * fmtChannels
        var data = if (carry.isEmpty()) pcm else carry + pcm
        val usable = data.size - data.size % inFrame
        carry = data.copyOfRange(usable, data.size)
        if (usable == 0) return
        if (usable != data.size) data = data.copyOf(usable)
        val out = convert(data)
        var off = 0
        val myGen = gen
        while (off < out.size && myGen == gen) {
            val n = t.write(out, off, out.size - off)
            if (n <= 0) break
            off += n
        }
        framesWritten += off / (outWidth * outChannels)
    }

    /** Converts whole input frames to the AudioTrack format (8/16-bit, 1-2 channels). */
    private fun convert(data: ByteArray): ByteArray {
        if (fmtWidth == outWidth && fmtChannels == outChannels) return data
        val frames = data.size / (fmtWidth * fmtChannels)
        val out = ByteArray(frames * outWidth * outChannels)
        var o = 0
        for (f in 0 until frames) {
            for (c in 0 until outChannels) {
                val i = (f * fmtChannels + c) * fmtWidth
                if (outWidth == 1) {
                    out[o++] = data[i]
                } else {
                    // Little-endian signed; keep the two most significant bytes.
                    out[o++] = data[i + fmtWidth - 2]
                    out[o++] = data[i + fmtWidth - 1]
                }
            }
        }
        return out
    }

    /** In MODE_STREAM, stop() plays out what's buffered; wait until the head reaches the end. */
    private fun drain() {
        val t = track ?: return
        val myGen = gen
        try { t.stop() } catch (_: IllegalStateException) { return }
        val total = framesWritten
        val deadline = nowMs() + total * 1000L / fmtRate + 1_500L
        while (myGen == gen && nowMs() < deadline) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= total) break
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
    }

    private fun closeTrack() {
        synchronized(trackLock) {
            track?.let {
                try { it.stop() } catch (_: IllegalStateException) {}
                it.release()
            }
            track = null
        }
    }
}
